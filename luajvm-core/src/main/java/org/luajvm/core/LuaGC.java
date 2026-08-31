// ref: lgc.c, lmem.c
// diff: gray/grayAgain 用侵入式 GrayList 链表（LuaValue.gclist 作 next 指针）对齐 C 的 g->gray 链表
package org.luajvm.core;

import org.luajvm.vm.LuaCall;

public final class LuaGC {
    // Java 诊断：仅在独立 JVM 测试时启用，不参与 GC 决策。
    static final boolean TRACE_GC = Boolean.getBoolean("luajvm.trace.gc");
    // lgc.h: GCSphases
    public static final int GCSpropagate = 0;
    public static final int GCSenteratomic = 1;
    public static final int GCSatomic = 2;
    public static final int GCSswpallgc = 3;
    public static final int GCSswpfinobj = 4;
    public static final int GCSswptobefnz = 5;
    public static final int GCSswpend = 6;
    public static final int GCScallfin = 7;
    public static final int GCSpause = 8;
    // lgc.h: KGC modes  -  KGC_INC(0), KGC_GENMINOR(1), KGC_GENMAJOR(2)
    // java diff（有意分叉，无 major 机制）：C 有 minor2inc/entergen/fullgen 等一整套
    //   minor<->major 切换（lgc.c）；Java 的"分代"只实现『每次 minor = 全堆原子标记 +
    //   sweepgen 年龄推进』。⇒ KGC_GENMAJOR 恒不被设置（本常量仅为对齐 C 的枚举保留），
    //   setParam 的 MINORMAJOR/MAJORMINOR 接受但不参与决策；
    //   gengc.lua 依赖的年龄转换（new→survival→old1→old）保留。
    public static final int KGC_INC = 0;
    public static final int KGC_GENMINOR = 1;
    public static final int KGC_GENMAJOR = 2;
    // java-only: GC 追踪的调试开关
    static final boolean GC_DEBUG = false;
    // lgc.h: WHITE0=0, WHITE1=1, GRAY=2, BLACK=3
    static final byte WHITE0 = 0, WHITE1 = 1, GRAY = 2, BLACK = 3;
    // lgc.c: g->gray / g->grayagain  -  待传播灰对象（增量 mark / write barrier 触发）
    // java diff: C 用 per-object gclist 指针链表；Java 用侵入式 LIFO（gclist 作 next，
    //   push/pop 仅 2 次指针赋值零分配）。非 GRAY 对象 gclist 必须为 null。
    // java-only
    static final long defaultMemoryLimit =
            Long.getLong("luajvm.luaMemoryLimitBytes", 128L * 1024L * 1024L);
    // C：ltests.c : l_memcontrol 是进程级测试分配器状态（memlimit/countlimit/failnext/total），
    // 对所有 lua_State 生效。它非 Lua GC 的按状态字段（GCdebt/gcstate/颜色/对象链），
    // 故保持进程级：否则 T.newstate() 建的新状态不受 T.totalmem/T.alloccount 约束。
    private static long luaMemoryLimitBytes = defaultMemoryLimit;
    private static long afterGCMem;
    private static long allocCountLimit = -1;
    private static boolean allocFailNext;

    private static final long JVM_GC_MIN_INTERVAL_MILLIS =
            Long.getLong("luajvm.jvmGcMinIntervalMillis", -1L);
    private static final long JVM_GC_MIN_INTERVAL_NANOS =
            JVM_GC_MIN_INTERVAL_MILLIS < 0 ? -1L : JVM_GC_MIN_INTERVAL_MILLIS * 1_000_000L;
    // lstate.h: lu_byte gcparams[LUA_GCPN]  -  Java 用 long[] 保证全精度
    // java diff: C 用 lu_byte + luaO_codeparam/luaO_applyparam 紧凑编码；
    // Java 直接存原始 long 值（无需编码）
    private static final int GCP_PAUSE = 0;
    private static final int GCP_STEPMUL = 1;
    private static final int GCP_STEPSIZE = 2;
    private static final int GCP_MINORMUL = 3;
    private static final int GCP_MAJORMINOR = 4;
    private static final int GCP_MINORMAJOR = 5;
    private static final int GCP_COUNT = 6;
    // java-only: GCSpropagate 批量大小 - C 每调用 1 对象（propagatemark），Java 批量
    //   摊薄 dispatch 开销（A/B 开关 -Dluajvm.gcpropbatch=64 恢复批量传播）。
    private static final int PROPAGATE_BATCH =
            Integer.getInteger("luajvm.gcpropbatch", 64);
    // lgc.c: luaC_checkGC 在 GCdebt > 0 时触发 step
    // java diff: 基于批量；阈值控制频率。系统属性可覆盖：调大延迟 GC，
    //   用于复现/回归 finalizer 栈恢复 bug（见 LuaTable.callFinalizers 的 luaD_pcall 对齐注释）
    // java diff: 默认 512KB
    // java-only: 动态自适应阈值 - 无回收倍增/有回收重置（机制见 setpause）
    static final long BASE_ALLOCATION_GC_THRESHOLD =
            Long.getLong("luajvm.allocationGcThresholdBytes", 512L * 1024L);
    private static final long MAX_ALLOCATION_GC_THRESHOLD = 4L * 1024L * 1024L;  // 4MB cap
    // java-only: A/B 开关 - -Dluajvm.dynGcThreshold=false 关闭动态阈值调整（基线对照用），默认开启
    private static final boolean DYN_GC_THRESHOLD =
            System.getProperty("luajvm.dynGcThreshold") == null ||
                    Boolean.parseBoolean(System.getProperty("luajvm.dynGcThreshold"));
    static final long ALLOCATION_GC_FINALIZER_CANDIDATE_MIN_THRESHOLD =
            Long.getLong("luajvm.allocationGcFinalizerCandidateThresholdBytes", 256L * 1024L);
    private static final long ALLOCATION_GC_FINALIZER_CANDIDATE_MAX_THRESHOLD =
            Long.getLong("luajvm.allocationGcFinalizerCandidateMaxThresholdBytes", 512L * 1024L * 1024L);
    private static final long ALLOCATION_GC_BACKGROUND_THRESHOLD =
            Long.getLong("luajvm.allocationGcBackgroundThresholdBytes", 128L * 1024L * 1024L);
    private static final long ALLOCATION_GC_WEAK_ACTIVE_THRESHOLD =
            Long.getLong("luajvm.allocationGcWeakActiveThresholdBytes", 1024L * 1024L);
    private static final long ALLOCATION_GC_WEAK_ONLY_THRESHOLD =
            Long.getLong("luajvm.allocationGcWeakOnlyThresholdBytes", 512L * 1024L * 1024L);
    // java-only: ltests-OFF 分配快速路径开关（A/B 对照：-Dluajvm.allocfast=false）
    //   ON 且无 ltests 限制（allocCountLimit<0 && 内存限制=默认 && !allocFailNext）时，
    //   分配入口跳过 firsttry/tryagain - 无限制时 firsttry 恒 true，跳过在语义上等价且省
    //   每次分配的方法调用开销（对齐 C release build：lmem.c firsttry = callfrealloc）。
    private static final boolean ALLOC_FAST_DEFAULT =
            Boolean.parseBoolean(System.getProperty("luajvm.allocfast", "true"));
    // java-only: firsttry 中 afterGCMem 的运行时开关（默认开，供 A/B 测试）
    // -Dluajvm.aftergcmem=true|false
    private static final boolean USE_AFTER_GC_MEM =
            Boolean.parseBoolean(System.getProperty("luajvm.aftergcmem", "true"));
    // java-only: 临时插桩计数器（诊断链分配的 O(n^2)）
    private LuaGC() {
    }

    static int gcState(Globals g) {
        return g.gc.gcstate;
    }

    // lgc.h: otherwhite(g) = g->currentwhite ^ WHITEBITS
    // java diff: C 翻转两个白色位；Java 在 0 和 1 间翻转
    static byte otherwhite(Globals g) {
        return (byte) (1 - g.gc.currentwhite);
    }

    // lgc.h: luaC_white(g) = g->currentwhite & WHITEBITS
    static byte isWhite(Globals g) {
        return g.gc.currentwhite;
    }

    // 未绑定到 Globals 的构造阶段没有 GC 状态；对象绑定时会改为所属状态的白色。
    static byte isWhite() {
        return WHITE0;
    }

    // lgc.h: iswhite(x) = testbits(x->marked, WHITEBITS)
    static boolean iswhite(byte color) {
        return color == WHITE0 || color == WHITE1;
    }

    // lgc.h: isblack(x) = testbit(x->marked, BLACKBIT)
    static boolean isblack(byte color) {
        return color == BLACK;
    }

    // java-only: 跨包公开访问器（LuaVM 在 org.luajvm.vm）
    public static boolean isblackGC(LuaValue v) {
        return v.gcColor == BLACK;
    }


    // java-only: 强制下次 fullGC repropagateAll - barrier 被跳过时闭包 upvalue 可能
    //   漏标（lgc.c: propagateOne 等价），强制确保 markRoots 运行。
    public static void setNeedRepropagate(Globals g) {
        g.gc.needRepropagate = true;
    }

    // lgc.h: isgray(x) = !iswhite && !isblack
    static boolean isgray(byte color) {
        return color == 2;
    }

    // lgc.h: isdeadm(ow,m) = (m) & (ow)
    // java diff: C 用位掩码 AND；Java 与 otherwhite 做相等检查
    static boolean isdeadm(byte ow, byte color) {
        return color == ow;
    }

    // lgc.h: isdead(g,v) = isdeadm(otherwhite(g), v->marked)
    static boolean isdead(Globals g, byte color) {
        return color == otherwhite(g);
    }

    // lgc.h: changewhite(x) = (x)->marked ^= WHITEBITS
    // java diff: C 异或两个白色位；Java 在 0 和 1 间翻转
    static byte changewhite(byte color) {
        return (byte) (1 - color);
    }

    // 翻转 currentwhite  -  atomic() 结束时调用
    public static void flipwhite(Globals g) {
        if (g == null) throw new IllegalArgumentException("Globals required");
        flipwhiteInternal(g);
    }

    private static void flipwhiteInternal(Globals g) {
        g.gc.currentwhite = (byte) (1 - g.gc.currentwhite);
    }

    // java-only: GC 是否处于增量模式？
    public static boolean isIncrementalMode(Globals g) {
        return g.gc.gckind == KGC_INC;
    }

    // lgc.h: keepinvariant(g) = g->gcstate <= GCSatomic
    // 只有传播/atomic 相位需要维持黑不指白的不变量；sweep 及之后置灰会留下
    // "非白但不在灰链"的对象，其子对象永不被标记（弱键被误清的根因）。
    static boolean keepinvariant(Globals g) {
        return g.gc.gcstate <= GCSatomic;
    }

    // lgc.h: luaC_barrier(L, p, v)  -  forward luaC_barrier（p 黑 v 白时直接标 v）
    // java diff: C 的 UpVal 有 gcColor，Java 无 - 仅需处理闭包 upvalue 白值标灰。
    // java diff: 长串（LUA_VLNGSTR）非 pinned 会被 sweep 回收，须受 barrier 保护 -
    //   BLACK 容器 mark 期间新增长串不标 -> 误回收（对齐 reallymarkobject set2black）。
    public static void barrier(Globals g, LuaValue v) {
        // java diff: iscollectable() 排除字符串（无 BIT_ISCOLLECTABLE）；长串需显式判 tt_。
        //   short strings 是 pinned（fixedgc），barrier 跳过它们正确（与 markValue 一致）。
        boolean gcObj = v.iscollectable() || v.tt_ == LuaValue.LUA_VLNGSTR;
        if (gcObj && v.gcColor == g.gc.currentwhite) {
            // lgc.c: luaC_barrier_  -  非 keepinvariant（sweep 及之后）不得置灰
            if (!keepinvariant(g)) return;
            if (v.tt_ == LuaValue.LUA_VLNGSTR) {
                // lgc.c: reallymarkobject 对字符串 set2black + GCmarked（无子对象）
                v.gcColor = BLACK;
                g.gc.GCmarked += v.gcSize();
            } else {
                v.gcColor = 2;  // GRAY
                g.gc.gray.push(v);
            }
            g.gc.needRepropagate = true;  // java-only: object became non-white
        }
    }

    // lgc.h: luaC_barrierback(L,p,v)  -  表/闭包的反向写屏障
    // lgc.c: luaC_barrierback_  -  处理增量和分代两种情形（见下方两个分支）
    // java diff: C 的宏检查 isblack(p)&&iswhite(v) 后调 luaC_barrierback_；
    // Java 在一个方法内联两个检查
    // java diff: 同 barrier，长串需受 backward barrier 保护（容器 p 被 re-gray，
    //   随后 re-traverse 时 markValue 标记长串）。short strings 跳过（pinned）。
    public static void barrierback(Globals g, LuaValue p, LuaValue v) {
        if (p instanceof LuaTable table) LuaTable.bindValue(g, v);
        if (!v.iscollectable() && v.tt_ != LuaValue.LUA_VLNGSTR) return;
        // lgc.c: luaC_barrierback_  -  incremental: isblack(p) && iswhite(v)
        // java diff: C 的 luaC_barrierback_ 无相位守卫（sweep 会重置存活对象的全部标记位），
        //   Java 的 sweep 同样把存活对象复位为 currentwhite，因此此处也不加守卫。
        if (v.gcColor == g.gc.currentwhite && isblack(p.gcColor)) {
            p.gcColor = 2;  // GRAY
            g.gc.grayagain.push(p);
            g.gc.needRepropagate = true;  // java-only: object became non-white
        }
        // lgc.c: luaC_barrierback_  -  generational: isold(p) && isnew(v)
        if (p.gcAge >= LuaValue.G_OLD && v.gcAge == LuaValue.G_NEW) {
            if (p.gcAge == LuaValue.G_TOUCHED2) {
                p.gcColor = 2;  // GRAY  -  lgc.c set2gray
                g.gc.needRepropagate = true;
            } else if (p.gcColor != 2) {  // not already gray
                p.gcColor = 2;  // GRAY
                g.gc.grayagain.push(p);
                g.gc.needRepropagate = true;  // java-only: object became non-white
            }
            p.gcAge = LuaValue.G_TOUCHED1;  // lgc.c: luaC_barrierback_
        }
    }

    // lgc.c: atomic 的弱表/终结器收尾段（convergeephemerons 之后到 flipwhite 之前）。
    // 调用前提：gray 已传播完，gcstate 已为 GCSatomic（否则 traverse* 会按 propagate 相位入 grayagain）。
    private static void atomicWeakAndFinalizers(Globals g) {
        Globals.GCState s = g.gc;
        LuaTable.convergeEphemeron(g, s.gray);

        LuaTable.clearByValues(g, s.weak.head, null);
        LuaTable.clearByValues(g, s.allweak.head, null);
        LuaValue origweak = s.weak.head;
        LuaValue origall = s.allweak.head;

        LuaTable.separateAndMarkFinalizers(g);
        LuaTable.propagateGray(g, s.gray);
        LuaTable.convergeEphemeron(g, s.gray);

        LuaTable.clearByKeys(g, s.ephemeron.head);
        LuaTable.clearByKeys(g, s.allweak.head);
        LuaTable.clearByValues(g, s.weak.head, origweak);
        LuaTable.clearByValues(g, s.allweak.head, origall);
    }

    // lgc.c: cleargraylists
    static void cleargraylists(Globals g) {
        g.gc.gray.clear();
        g.gc.grayagain.clear();
        g.gc.weak.clear();
        g.gc.allweak.clear();
        g.gc.ephemeron.clear();
    }

    // java-only: sweep 方法在回收死对象时调用
    public static void markObjectsSwept(Globals g) {
        g.gc.objectsSweptThisCycle = true;
    }

    // lmem.c: luaM_toobig
    public static void tooBig() {
        LuaErrors.tooBig();
    }

    // lmem.c: luaM_free_
    public static void free(Globals g, long osize) {
        if (osize <= 0) return;
        freeBytes(g, osize);
        g.gc.GCdebt += osize;
    }

    // lmem.c: luaM_realloc_ // java diff: 两段式分配用luaM_checkmemory+luaM_commitrealloc
    public static boolean realloc(long osize, long nsize) {
        return true;
    }

    // java-only: checkMemory  -  两段式分配的检查阶段（C直接在luaM_realloc_内完成）
    /**
     * C：lmem.c : luaM_realloc_ 的检查阶段（构造期无 Globals 的入口）。
     * ltests 的 memlimit/countlimit 是进程级静态，无需 Globals 即可完成与 C 等价的检查；
     * 检查点必须留在分配处（对齐 C），移到对象绑定处会改变 T.alloccount 观察到的分配次数序列。
     */
    public static void checkMemory(long delta) {
        checkMemoryProcess(delta, 1);
    }

    private static void checkMemoryProcess(long delta, int n) {
        if (delta <= 0 || n <= 0) return;
        if (allocFastEnabledProcess()) return;
        if (firsttryProcess(delta, n)) return;
        // lgc.c: tryagain  -  先做一次紧急完整收集再重试，仍不足才报内存错误
        Globals g = LuaTable.runningGlobalsForGC();
        if (g != null && !g.gc.gcstopem && !LuaTable.isCollecting(g)) {
            // allocCountLimit 导致的失败与内存无关，紧急 GC 徒劳无益
            if (allocCountLimit < 0) {
                fullGCInternal(g, true);
                if (firsttryProcess(delta, n)) return;
            }
        }
        memError();
    }

    // lmem.c: firsttry  -  消耗 ltests 配额并按进程总量判断内存限制
    private static boolean firsttryProcess(long delta, int n) {
        if (allocFailNext) {
            allocFailNext = false;
            return false;
        }
        if (allocCountLimit >= 0) {
            if (allocCountLimit < n) return false;
            allocCountLimit -= n;
        }
        long baseline = (USE_AFTER_GC_MEM && afterGCMem > 0
                && luaMemoryLimitBytes != defaultMemoryLimit)
                ? afterGCMem : processBytes();
        return baseline + delta <= luaMemoryLimitBytes;
    }

    // ltests 限制未生效时跳过（对齐 C release build 的 firsttry 恒 true）
    private static boolean allocFastEnabledProcess() {
        return ALLOC_FAST_DEFAULT && allocCountLimit < 0
                && luaMemoryLimitBytes == defaultMemoryLimit && !allocFailNext;
    }

    // ltests 的 l_memcontrol.total：所有登记状态的 Lua 内存之和
    private static long processBytes() {
        final long[] sum = {0L};
        LuaTable.forEachActiveGlobals(o -> sum[0] += o.gc.luaMemoryBytes);
        return sum[0];
    }

    /** C：lmem.c : luaM_realloc_。阶段 C 完成前，分配记账仍由旧存储承载。 */
    public static void checkMemory(Globals g, long delta) {
        if (g == null) throw new IllegalArgumentException("Globals required");
        checkMemoryInternal(g, delta);
    }

    private static void checkMemoryInternal(Globals g, long delta) {
        if (delta <= 0) return;
        if (allocFastEnabled(g)) return;
        if (!firsttry(g, delta, true) && !tryagain(g, delta, true)) memError();
    }

    // java-only: checkMemoryN  -  n次frealloc的检查阶段
    /** C：lmem.c : luaM_reallocvector 的检查阶段（构造期无 Globals 的入口）。 */
    public static void checkMemoryN(long delta, int n) {
        checkMemoryProcess(delta, n);
    }

    // java-only: ltests-OFF 快速路径的三个条件（任一不满足则走原 firsttry/tryagain，
    //   保 T.alloccount/T.totalmem 语义逐位一致）：
    //   allocCountLimit < 0（无 T.alloccount）、内存限制=默认（无 T.totalmem）、!allocFailNext
    private static boolean allocFastEnabled(Globals g) {
        return ALLOC_FAST_DEFAULT
                && allocCountLimit < 0
                && luaMemoryLimitBytes == defaultMemoryLimit
                && !allocFailNext;
    }

    // java-only: commitRealloc  -  两段式分配的提交阶段（C直接在luaM_realloc_内完成）
    public static void commitRealloc(long osize, long nsize) {
    }

    /** C：lmem.c : luaM_reallocvector。显式状态入口。 */
    public static void checkMemoryN(Globals g, long delta, int n) {
        if (g == null) throw new IllegalArgumentException("Globals required");
        checkMemoryNInternal(g, delta, n);
    }

    private static void checkMemoryNInternal(Globals g, long delta, int n) {
        if (delta <= 0 || n <= 0) return;
        if (allocFastEnabled(g)) return;
        if (!firsttryN(g, delta, n) && !tryagainN(g, delta, n)) memError();
    }

    /** C：lmem.c : luaM_realloc_。 */
    public static void commitRealloc(Globals g, long osize, long nsize) {
        if (g == null) throw new IllegalArgumentException("Globals required");
        long delta = nsize - osize;
        if (delta > 0) allocBytes(g, delta);
        else if (delta < 0) freeBytes(g, -delta);
        g.gc.GCdebt -= delta;
    }

    // lgc.h: luaC_checkGC = luaC_condGC(L,(void)0,(void)0)  -  if (G(L)->GCdebt <= 0) luaC_step(L)
    // java diff: C 的 checkGC 在 GCdebt <= 0 时触发；Java 用 allocationDebt 阈值，
    // 因为 repropagateAll 使逐分配 GC 代价过高
    public static void checkGC(Globals g, int bytes) {
        if (g == null) throw new IllegalArgumentException("Globals required");
        if (g.gc.allocationGcRunning) return;
        if (!gcrunning(g)) return;
        g.gc.allocationDebt += Math.max(64, bytes);
        if (g.gc.GCdebt <= 0) {
            // lgc.c: luaC_step  -  KGC_GENMINOR 分支 youngcollection
            if (g.gc.gckind == KGC_GENMINOR) {
                youngCollection(g);
                setminordebt(g);
                return;
            }
            g.gc.allocationGcRunning = true;
            try {
                incstep(g);
            } finally {
                g.gc.allocationGcRunning = false;
            }
            g.gc.allocationDebt = 0;
        } else if (g.gc.allocationDebt >= g.gc.currentAllocationGcThreshold) {
            // java-only: 超过分配阈值时批量 GC
            if (g.gc.gckind == KGC_GENMINOR) {
                youngCollection(g);
                setminordebt(g);
                return;
            }
            g.gc.allocationGcRunning = true;
            try {
                incstep(g);
            } finally {
                g.gc.allocationGcRunning = false;
            }
            g.gc.allocationDebt = 0;
        }
    }

    // lgc.c: singlestep  -  GC 状态机前进一步
    // java diff: C 有细粒度 sweep 状态（swpallgc/swpfinobj/swptobefnz/swpend）；
    // Java 把 sweep 合并为一个状态。返回完成的工作单元（类似 C 的 singlestep 返回值）
    private static int singlestep(Globals g) {
        Globals.GCState s = g.gc;
        // java-only: 增量 GC 把对象标记为 BLACK/GRAY，使 fullGC 的
        // needRepropagate=false 快路径失效。任何 singlestep 调用都意味着
        // 对象可能已非白，下一次 fullGC 必须 repropagate
        s.needRepropagate = true;
        switch (s.gcstate) {
            case GCSpause: {
                // lgc.c: restartcollection  -  cleargraylists + GCmarked=0 + 标记根
                cleargraylists(g);
                s.GCmarked = 0;
                // C：lgc.c : sweeplist 保证进入 restartcollection 时无非白对象；Java 的 sweep
                // 只覆盖登记表中的对象，且脱链对象可残留 GRAY（被 markValue 跳过、子对象误清扫），
                // 故在此显式复位为白。
                LuaTable.repropagateAll(g, s.gray, isWhite(g));
                s.gcstate = GCSpropagate;
                return 1;
            }
            case GCSpropagate: {
                if (s.gray.isEmpty()) {
                    s.gcstate = GCSenteratomic;
                    return 1;
                } else {
                    // java diff: C 的 singlestep 每调用传播 1 对象（propagatemark）；
                    //   Java 批量处理 PROPAGATE_BATCH 个摊薄 dispatch 开销（JFR 依据）。
                    int batch = PROPAGATE_BATCH;
                    int steps = 0;
                    while (steps < batch && !s.gray.isEmpty()) {
                        LuaValue v = s.gray.pop();
                        v.gcColor = BLACK;
                        s.GCmarked += v.gcSize();
                        LuaTable.propagateOne(g, v, s.gray);
                        steps++;
                    }
                    return steps;
                }
            }
            // lgc.c: atomic 进入时置 GCSatomic，随后 entersweep 转入 GCSswpallgc；
            // 两个状态在 Java 走同一段 atomic 实现。
            case GCSatomic:
            case GCSenteratomic: {
                // lgc.c: atomic  -  remark roots + grayagain
                // java diff: C 的 atomic 第 7 步 remarkupvals（twups 链）在 Java 无对应 -
                //   Java 的 UpVal 不是 GC 对象，上值经其闭包的 gcRefs 直接标记
                //   （LuaTable.propagateOne 的 LuaClosure 分支），不依赖线程存活。
                // java diff: C 的增量标记正确（写屏障 + 线程/弱表的 grayagain
                // 确保不漏对象）。Java 对表/userdata/闭包有完整写屏障，
                // 但线程缺栈写屏障。
                // repropagateThreadsOnly 只重置线程（O(allThreads) 而非 O(全部对象)）

                // lgc.c: atomic  -  先保存 grayagain 再置 GCSatomic，使随后的 traverse*
                //   按 atomic 相位（而非 propagate 相位）把弱表分派进 weak/allweak/ephemeron。
                // 先摘链再置空表头：clear() 会清空整条链的 gclist，直接调用会摧毁刚保存的链
                LuaValue savedGrayagain = s.grayagain.detach();
                s.gcstate = GCSatomic;

                LuaTable.remarkRootsForAtomic(g, s.gray);
                LuaTable.propagateGray(g, s.gray);

                s.gray.head = savedGrayagain;
                LuaTable.propagateGray(g, s.gray);
                atomicWeakAndFinalizers(g);

                flipwhiteInternal(g);
                s.gcstate = GCSswpallgc;
                return 1;
            }
            case GCSswpallgc: {
                LuaUserdata.sweepByColor(g);
                LuaThread.sweepByColor(g);

                LuaClosure.sweepClosuresByColor(g);
                LuaFunction.sweepFunctionsByColor(g);  // java-only: sweep LUA_VLCF (not in allClosures)
                Prototype.sweepProtosByColor(g);
                LuaTable.sweepDeadTables(g);
                LuaString.sweepShortStringsByColor(g);
                LuaString.sweepLongStringsByColor(g);

                s.gcstate = GCSswpfinobj;
                return 64;
            }
            // lgc.c: singlestep 的 sweepstep 链 - Java 在 GCSswpallgc 一次清扫全部登记表，
            // 其余子状态保留为对齐 C 的状态转移（供 luaC_runtilstate 与 ltests gcstate 使用）。
            case GCSswpfinobj: {
                s.gcstate = GCSswptobefnz;
                return 1;
            }
            case GCSswptobefnz: {
                s.gcstate = GCSswpend;
                return 1;
            }
            case GCSswpend: {
                s.gcstate = GCScallfin;
                return 1;
            }
            case GCScallfin: {
                // lgc.c: finishgencycle  -  C 加 !gcemergency && luaD_checkminstack 检查
                if (LuaTable.hasToBeFinalized(g) && !s.gcemergency && checkMinStack(g)) {
                    LuaTable.runPendingFinalizers(g);
                }
                if (s.gckind != KGC_INC) {
                    LuaTable.resetColorsAfterStepGC(g);
                }
                s.gcstate = GCSpause;
                setpause(g);
                return 64;
            }
            default: {
                s.gcstate = GCSpause;
                return 0;
            }
        }
    }

    // lgc.c: incstep
    // java diff: C 用 applygcparam(STEPMUL, stepsize/sizeof(void*)) 算工作预算；
    // Java 用简化的工作计数。C 的 singlestep 返回工作单元；Java 数步骤
    private static void incstep(Globals g) {
        long stepsize = g.gc.gcParams[GCP_STEPSIZE];
        long steppmul = g.gc.gcParams[GCP_STEPMUL];
        incstep(g, steppmul * (stepsize / 8) / 100);
    }

    // java-only: collectgarbage("step", siz) 手动步进 - gcstp 作每步预算
    //   （lapi.c: LUA_GCSTEP + lgc.c: luaC_step）；gc.lua dosteps 断言依赖。
    private static void incstep(Globals g, long work2do) {
        // lgc.c: incstep  -  work2do == 0 时快速模式
        boolean fast = (work2do == 0);
        boolean firstStep = true;
        int stepCount = 0;
        while (true) {
            if (fast && g.gc.gcstate == GCSpropagate) {
                g.gc.gcstate = GCSenteratomic;
            }
            int work = singlestep(g);
            stepCount++;
            if (!firstStep && g.gc.gcstate == GCSpause) break;
            firstStep = false;
            work2do -= work;
            if (!fast && work2do <= 0) break;
        }
        if (g.gc.gcstate == GCSpause) setpause(g);
        else setDebt(g, g.gc.gcParams[GCP_STEPSIZE]);
    }

    // lgc.c: setminordebt
    private static void setminordebt(Globals g) {
        long minormul = g.gc.gcParams[GCP_MINORMUL];
        long debt = minormul * g.gc.luaMemoryBytes / 100;
        setDebt(g, debt);
    }

    // lstate.c: luaE_setdebt
    private static void setDebt(Globals g, long debt) {
        g.gc.GCdebt = debt;
    }

    // lgc.c: luaC_step
    public static void gcStep(Globals g) {
        if (g == null) throw new IllegalArgumentException("Globals required");
        if (!gcrunning(g)) {
            if (g.gc.gcStoppedByUser) g.gc.GCdebt = 20000;
            return;
        }
        if (g.gc.gckind == KGC_GENMINOR) {
            youngCollection(g);
            setminordebt(g);
            return;
        }
        g.gc.allocationGcRunning = true;
        try {
            incstep(g);
        } finally {
            g.gc.allocationGcRunning = false;
        }
    }

    // lgc.c: youngcollection
    // java diff: C 用每列表指针（survival, old1, reallyold）按 age 划分 allgc 链表、
    // sweepgen 逐列表处理；Java 用全局集合 + gcAge 字段，一次处理所有对象
    private static void youngCollection(Globals g) {
        if (g.gc.allocationGcRunning) return;
        g.gc.youngCollectionCount++;
        // java-only: youngCollection 把对象标记为 BLACK（同 singlestep），故下次
        // fullGC 必须 repropagate。否则 fullGC 在 needRepropagate=false 时
        // 跳过 repropagateAll，留下未重置的 BLACK 对象导致追踪遗漏
        g.gc.needRepropagate = true;
        g.gc.allocationGcRunning = true;
        try {
            // lgc.c: markold  -  把 OLD1 对象标记为 OLD
            LuaTable.markOld(g);

            // lgc.c: atomic step (same as GCSenteratomic)
            // java diff: 分代 GC 需要 repropagateAll，因为 TOUCHED2 对象
            // 必须被重新传播（C 的 correctgraylist 把 TOUCHED2 留在灰列表；
            // Java 的 correctGrayLists 立即推进 TOUCHED2->OLD）

            // 进入 atomic 相位后再遍历，弱表才会分派进 weak/allweak/ephemeron
            g.gc.gcstate = GCSatomic;
            // repropagateAll 会复位颜色并清 gclist；必须先清空灰链，否则会截断残留链
            cleargraylists(g);
            LuaTable.repropagateAll(g, g.gc.gray, isWhite(g));
            LuaTable.propagateGray(g, g.gc.gray);
            g.gc.gray.addAll(g.gc.grayagain);
            LuaTable.propagateGray(g, g.gc.gray);
            atomicWeakAndFinalizers(g);

            flipwhiteInternal(g);

            // lgc.c: sweepgen  -  G_NEW->G_SURVIVAL|white；其余前进 age
            byte cw = isWhite(g);
            LuaTable.sweepGen(g, cw);
            LuaUserdata.sweepGen(g, cw);
            LuaThread.sweepGen(g, cw);
            LuaClosure.sweepGen(g, cw);
            LuaFunction.sweepGen(g, cw);  // java-only: sweepgen LUA_VLCF (not in allClosures)
            Prototype.sweepGen(g, cw);
            LuaString.sweepShortStringsByColor(g);
            LuaString.sweepLongStringsByColor(g);

            // lgc.c: correctgraylists
            LuaTable.correctGrayLists(g);

            // lgc.c: finishgencycle
            if (LuaTable.hasToBeFinalized(g) && !g.gc.gcemergency && checkMinStack(g)) {
                LuaTable.runPendingFinalizers(g);
            }
            g.gc.gcstate = GCSpause;
        } finally {
            g.gc.allocationGcRunning = false;
        }
    }

    // lgc.c: luaC_fullgc
    // java diff: 用 gcColor 而非 marked 集合；无 IdentityHashMap 分配
    // java diff: C 的 fullinc 经 singlestep 做 entersweep + 两个完整周期；
    //   Java 仅做一个 mark+sweep 周期（repropagateAll 是 O(n)；C 有完整写屏障无需它）
    public static void fullGC(Globals g, boolean isemergency) {
        if (g == null) throw new IllegalArgumentException("Globals required");
        fullGCInternal(g, isemergency);
    }

    private static void fullGCInternal(Globals g, boolean isemergency) {
        // C：lgc.c : fullinc  -  entersweep 把黑对象扫回白后跑完一个周期（含 callfin）。
        // 被终结对象本周期被 markbeingfnz 复活，须留到下一次收集才释放
        // （api.lua："首次收集只调 TM，不释放内存"）；而"本轮刚由 BLACK 复位为白"的对象
        // 还需再走一个周期才判死，故仅在本轮无终结器可调时补跑第二周期——
        // 既不提前释放刚终结对象，也收齐颜色滞后的对象。
        boolean ranFinalizers = fullGCCycle(g, isemergency);
        if (!isemergency && !ranFinalizers) {
            fullGCCycle(g, false);
        }
    }

    /** @return 本轮是否实际调用过终结器（被终结对象须留到下一轮才释放）。 */
    private static boolean fullGCCycle(Globals g, boolean isemergency) {
        Globals.GCState s = g.gc;
        if (LuaTable.isCollecting(g)) return false;
        boolean ranFinalizers = false;

        // C：lgc.c : luaC_fullgc 永远执行完整周期。对象无需新分配也可变为不可达，
        // 不得以"自上次收集无分配"为由跳过 —— 否则弱表永不清扫（gc.lua weak tables 失败根因）。
        if (!isemergency) s.heapDirty = false;

        boolean oldEmergency = s.gcemergency;
        boolean oldAllocationGcRunning = s.allocationGcRunning;
        s.gcemergency = isemergency;
        s.allocationGcRunning = true;
        boolean completed = false;

        try {
            s.gcstopem = true;
            s.gcstate = GCSatomic;

            if (isemergency && luaMemoryLimitBytes != defaultMemoryLimit) {
                // 跳过 requestJvmGC  -  直接进入 mark+sweep
            } else if (isemergency) {
                requestJvmGC(g, true);
            }

            // lgc.c: fullinc  -  若 keepinvariant，重新传播黑/灰对象
            // java diff: repropagateAll 必须（非 repropagateThreadsOnly） - finalized 对象被
            //   separateAndMarkFinalizers 标 BLACK 存活到 sweep 结束，需重置回 currentwhite
            //   否则永不回收（write barrier 无法检测 finalization 后的不可达）。
            cleargraylists(g);
            // C：lgc.c : sweeplist 保证周期开始时所有存活对象为白，因此 C 无需重传播。
            // Java 必须无条件 repropagateAll：上轮遗留的 BLACK 对象（尤其 longStrings，
            // 其颜色只在自身 sweep 中复位）否则本轮既不被标记也不被判死，需多一个周期才回收。
            LuaTable.repropagateAll(g, s.gray, isWhite(g));
            LuaTable.propagateGray(g, s.gray);

            // lgc.c: atomic  -  gray = grayagain; propagateall
            s.gray.addAll(s.grayagain);   // addAll 已把 grayagain 置空，不可再 clear（会清链上 gclist）
            LuaTable.propagateGray(g, s.gray);

            // lgc.c: atomic  -  convergeephemerons + clearbyvalues/clearbykeys（flipwhite 之前）
            atomicWeakAndFinalizers(g);

            // lgc.c: atomic  -  flip currentwhite at end
            flipwhiteInternal(g);

            // lgc.c: sweep2old  -  清扫死对象，存活对象置 G_OLD + currentwhite
            LuaUserdata.sweepByColor(g);
            LuaThread.sweepByColor(g);

            LuaClosure.sweepClosuresByColor(g);
            LuaFunction.sweepFunctionsByColor(g);  // java-only: sweep LUA_VLCF (not in allClosures)
            Prototype.sweepProtosByColor(g);
            LuaTable.sweepDeadTables(g);
            LuaString.sweepShortStringsByColor(g);
            LuaString.sweepLongStringsByColor(g);

            // java diff: agesAfterFullGC 必须无条件调用 - sweepByColor 跳过主线程，只有它
            //   重置 mainThread.gcColor；跳过会留 BLACK -> markRoots 跳过 -> 栈值不标 -> 弱表误清。
            LuaTable.agesAfterFullGC(g);
            // java-only: agesAfterFullGC 把所有对象重置为 currentwhite，使下次
            // fullGC 可跳过 repropagateAll（O(n) 扫描），除非 singlestep/屏障使
            // 对象再次非白
            s.needRepropagate = false;

            // lgc.c: fullinc  -  callfin 在 atomic[flip]+sweep 全部完成之后
            //（若在 flip 前调 __gc：finalizer 期间新建的对象以旧白色创建，会被同周期
            //  sweep 判死摘出 GC 管理——finalizer 提前触发、弱表失效、记账漂移）
            if (!isemergency) {
                boolean hadFinalizers = !LuaTable.tobefnzEmpty(g);
                if (hadFinalizers && Boolean.getBoolean("luajvm.fullgc_debug"))
                    System.err.println("[fullgc] calling " + LuaTable.tobefnzCount(g) + " finalizers, mem=" + s.luaMemoryBytes);
                LuaTable.callPendingFinalizers(g);
                if (hadFinalizers) ranFinalizers = true;
                // java-only: finalized 对象需下次 fullGC 重新传播后被清扫 -
                //   置 heapDirty=true 保证后续收集跑完整周期
                //   （memerr.lua 双 collectgarbage 释放 file handle 依赖此）。
                if (hadFinalizers) s.heapDirty = true;
            }

            s.gcstate = GCSpause;
            // java-only: 显式 collectgarbage() 后清零分配债务，避免积压债务在下一次
            //   分配时意外触发 GC/推进 age（对齐 C luaC_fullgc：singlestep 廉价无积压概念）。
            s.allocationDebt = 0;
            setpause(g);
            // afterGCMem 对应 ltests 的进程级 total 快照：必须是所有状态之和，
            // 否则被"最后收集的那个状态"覆盖而丢掉其余状态的用量。
            // java diff（关键）：ltests 限额生效期间的紧急 GC 不得重算此快照 —— 紧急 GC 是
            //   firsttry 失败后的重试路径（checkMemoryProcess 的 tryagain），重算会把基线
            //   整体下移，让分配绕过 ltests 硬限额（memerr.lua 断言失败的根因）。C 无此问题：
            //   debug_realloc 比的是实时值 mc->total，memlimit 是设限时刻固定的上限；
            //   显式 collectgarbage() 仍应刷新（C 的 mc->total 确实下降），故只挡 emergency。
            if (!(isemergency && luaMemoryLimitBytes != defaultMemoryLimit)) {
                afterGCMem = s.luaMemoryBytes + otherStatesBytes(g);
            }
            completed = true;

        } finally {
            s.gcstopem = false;
            s.gcemergency = oldEmergency;
            s.allocationGcRunning = oldAllocationGcRunning;
            // java-only: 若 fullGC 被打断（callFinalizers 捕获 LuaError，
            // 但 RuntimeException/Error 可能逃逸），强制下次 fullGC 运行
            if (!completed) {
                s.heapDirty = true;
                s.needRepropagate = true;  // also force repropagate on incomplete cycle
            }
        }
        return ranFinalizers;
    }

    // lgc.c: luaC_runtilstate  -  循环 singlestep 直到到达目标状态。
    // fast 只表示"可跳过 propagate 逐对象传播"，不得替换为 fullGC：
    // fullGC 走的是另一条 mark/sweep 路径，会留下与增量状态机不一致的颜色。
    public static void runToState(Globals g, int state, boolean fast) {
        if (g == null) throw new IllegalArgumentException("Globals required");
        boolean oldRunning = g.gc.allocationGcRunning;
        g.gc.allocationGcRunning = true;
        try {
            int guard = 0;
            while (g.gc.gcstate != state) {
                if (fast && g.gc.gcstate == GCSpropagate) {
                    // lgc.c: singlestep 的 fast 分支 - 只结束传播相位，不清灰链
                    //（atomic 的 propagateall 会继续把灰链传播完）
                    g.gc.gcstate = GCSenteratomic;
                    continue;
                }
                singlestep(g);
                // java-only: 防御 - 状态机必须在有限步内到达目标，避免测试钩子挂死
                if (++guard > 1_000_000) throw new IllegalStateException("runToState stalled at " + g.gc.gcstate);
            }
        } finally {
            g.gc.allocationGcRunning = oldRunning;
        }
    }

    // lgc.c: luaC_fullgc
    // java diff: 增加 gcstopem 防重入（有意分叉）：在 __gc 里调 collectgarbage("collect")，
    //   C 会跑完整收集并返回 0；Java 直接返回 false 且不收集 —— 收集期重入会在 sweep
    //   中途改动登记表（C 的链表 sweep 对重入安全）。官方套件不覆盖该形态。
    public static Varargs fullGCCaller(Globals g) {
        if (g == null) throw new IllegalArgumentException("Globals required");
        if (LuaTable.isCollecting(g) || g.gc.gcstopem) return LuaValue.FALSE;
        long before = g.gc.luaMemoryBytes;
        fullGC(g, false);
        return LuaValue.ZERO;
    }

    // java-only: 返回单个 LuaValue 供 callOnStack 快路径
    public static LuaValue fullGCCallerResult(Globals g) {
        if (g == null) throw new IllegalArgumentException("Globals required");
        if (LuaTable.isCollecting(g) || g.gc.gcstopem) return LuaValue.FALSE;
        long before = g.gc.luaMemoryBytes;
        fullGC(g, false);
        return LuaValue.ZERO;
    }

    // luaC_fullgc (step)  -  lgc.c: luaC_step
    // java diff: 运行增量状态机直到一个完整周期结束
    public static Varargs gcStepCaller(Globals g, Varargs args) {
        if (g == null) throw new IllegalArgumentException("Globals required");
        if (LuaTable.isCollecting(g) || g.gc.gcstopem) return LuaValue.FALSE;
        int siz = args.optint(2, 0);
        // lgc.c:  -  KGC_GENMINOR calls youngcollection
        if (g.gc.gckind == KGC_GENMINOR) {
            youngCollection(g);
            setminordebt(g);
            return LuaValue.TRUE;
        }
        g.gc.allocationGcRunning = true;
        try {
            if (siz > 0) {
                // java diff: siz 直接作 incstep 预算（gc.lua dosteps 断言 siz=10 步数 < siz=2）；
                //   C 有 GCdebt-n 溢出，Java 无此问题（不计算 GCdebt-n）。
                incstep(g, Math.max(1L, siz));
            } else {
                incstep(g);
            }
        } finally {
            g.gc.allocationGcRunning = false;
        }
        boolean completed = (g.gc.gcstate == GCSpause);

        return LuaValue.valueOf(completed);
    }

    // lgc.c: luaC_changemode
    public static LuaValue changeMode(Globals g, String mode) {
        if (g == null) throw new IllegalArgumentException("Globals required");
        String old = modeName(g);
        if ("generational".equals(mode)) {
            g.gc.gckind = KGC_GENMINOR;
            // java-only: 切分代时重置阈值/债务 - 遗留高阈值或积压债务会破坏分代
            //   触发时机（首分配即 youngCollection 提前推 age，破坏 gengc.lua 断言）。
            g.gc.currentAllocationGcThreshold = BASE_ALLOCATION_GC_THRESHOLD;
            g.gc.allocationDebt = 0;
        } else {
            g.gc.gckind = KGC_INC;
            // java-only: 切回增量同样重置，从干净状态开始动态调整
            g.gc.currentAllocationGcThreshold = BASE_ALLOCATION_GC_THRESHOLD;
            g.gc.allocationDebt = 0;
        }
        return LuaString.newStr(old);
    }

    // lua_gc (setparam) // java diff: C 用 lu_byte gcparams[] + luaO_codeparam；Java 直接用 long[]
    public static LuaValue setParam(Globals g, Varargs args) {
        if (g == null) throw new IllegalArgumentException("Globals required");
        if (args.isnil(2)) return LuaValue.ZERO;
        String key = args.checkJavaString(2);
        int idx = switch (key) {
            case "pause" -> GCP_PAUSE;
            case "stepmul" -> GCP_STEPMUL;
            case "stepsize" -> GCP_STEPSIZE;
            case "minormul" -> GCP_MINORMUL;
            case "majorminor" -> GCP_MAJORMINOR;
            case "minormajor" -> GCP_MINORMAJOR;
            default -> -1;
        };
        if (idx < 0) return LuaValue.ZERO;
        long prev = g.gc.gcParams[idx];
        if (!args.isnil(3)) g.gc.gcParams[idx] = args.checklong(3);
        return LuaInteger.valueOf(prev);
    }

    // lua_gc LUA_GCSTOP
    public static void stop(Globals g) {
        if (g == null) throw new IllegalArgumentException("Globals required");
        g.gc.gcStoppedByUser = true;
    }

    // lua_gc LUA_GCRESTART
    public static void restart(Globals g) {
        if (g == null) throw new IllegalArgumentException("Globals required");
        g.gc.gcStoppedByUser = false;
    }

    // java-only
    public static boolean gcrunning(Globals g) {
        if (g == null) throw new IllegalArgumentException("Globals required");
        return !g.gc.gcStoppedByUser;
    }

    // java-only
    public static long currentBytes(Globals g) {
        if (g == null) throw new IllegalArgumentException("Globals required");
        return g.gc.luaMemoryBytes;
    }

    // java-only
    public static long maxBytes(Globals g) {
        if (g == null) throw new IllegalArgumentException("Globals required");
        return g.gc.maxLuaMemoryBytes;
    }

    // java-only
    public static void setMemoryLimit(Globals g, long limit) {
        if (g == null) throw new IllegalArgumentException("Globals required");
        if (limit > 0 && g.gc.luaMemoryBytes > limit) {
            luaMemoryLimitBytes = limit;
        } else {
            luaMemoryLimitBytes = limit <= 0 ? defaultMemoryLimit : limit;
        }
        // java-only: 设置/修改内存限制时把 afterGCMem 对齐到当前 luaMemoryBytes。
        // C 的 debug_realloc 用 mc->total（当前内存）对比 memlimit；Java 的 luaMemoryBytes
        // 在 pcall 期间高估（死而未扫对象使其膨胀），故以 setMemoryLimit 时（collectgarbage
        // 后）快照的 afterGCMem 近似 mc->total，否则它可能来自先前测试的 fullGC 而失效
        // （memerr.lua/all.lua 超时的根因）。
        // Ref: ltests.c (debug_realloc 检查 mc->total+size-oldsize > mc->memlimit)
        afterGCMem = g.gc.luaMemoryBytes + otherStatesBytes(g);
    }

    // java-only
    public static long memoryLimit(Globals g) {
        if (g == null) throw new IllegalArgumentException("Globals required");
        return luaMemoryLimitBytes;
    }


    // java-only: ltests 分配配额消耗（每次分配调用一次）。
    //   对齐 C allocf 的 countlimit 递减语义（ltests.c）：
    //     countlimit != NO_COUNT && size != oldsize 时：countlimit == 0 -> 失败；否则 countlimit--。
    //   alloccount(N) = 允许 N 次分配后第 N+1 次失败；memerr.lua 的 testalloc/testbytes
    //   靠递增突破限制直到成功（countlimit 必须递减，永久 block 语义会使 testalloc 死循环）。
    //   allocFailNext 保持一次性：失败后清除。
    public static boolean tryAllocCount(Globals g) {
        if (allocCountLimit >= 0) {
            if (allocCountLimit == 0) return false;
            allocCountLimit--;
        }
        if (allocFailNext) {
            allocFailNext = false;
            return false;
        }
        return true;
    }

    /** C：ltests.c : alloccount。 */
    public static void clearAllocCountLimit(Globals g) {
        if (g == null) throw new IllegalArgumentException("Globals required");
        allocCountLimit = -1;
    }

    /** C：ltests.c : allocfailnext。 */
    public static void failNextAllocation(Globals g) {
        if (g == null) throw new IllegalArgumentException("Globals required");
        allocFailNext = true;
    }

    /** C：ltests.c : alloccount。 */
    public static void setAllocCountLimit(Globals g, long limit) {
        if (g == null) throw new IllegalArgumentException("Globals required");
        allocCountLimit = limit < 0 ? -1 : limit;
    }

    /** C：lmem.c : luaM_realloc_。 */
    public static void allocBytes(Globals g, long n) {
        if (g == null) throw new IllegalArgumentException("Globals required");
        if (n <= 0) return;
        g.gc.luaMemoryBytes += n;
        if (g.gc.luaMemoryBytes > g.gc.maxLuaMemoryBytes) g.gc.maxLuaMemoryBytes = g.gc.luaMemoryBytes;
        g.gc.heapDirty = true;
    }

    /** C：lmem.c : luaM_realloc_。 */
    public static void freeBytes(Globals g, long n) {
        if (g == null) throw new IllegalArgumentException("Globals required");
        if (n <= 0) return;
        g.gc.luaMemoryBytes -= n;
        if (g.gc.luaMemoryBytes < 0) {
            g.gc.luaMemoryBytes = 0;
        }
    }

    // java-only
    public static int gcstate(Globals g) {
        if (g == null) throw new IllegalArgumentException("Globals required");
        return g.gc.gcstate;
    }

    // java-only
    public static int gckind(Globals g) {
        if (g == null) throw new IllegalArgumentException("Globals required");
        return g.gc.gckind;
    }

    // java-only
    public static boolean gcemergency(Globals g) {
        if (g == null) throw new IllegalArgumentException("Globals required");
        return g.gc.gcemergency;
    }

    // ldo.c: luaD_checkminstack  -  获取一个已注册状态的运行线程并检查 finalizer 最小栈空间
    private static boolean checkMinStack(Globals g) {
        return g.running == null || LuaCall.checkMinStack(g.running);
    }

    // java-only
    public static boolean isCollecting(Globals g) {
        if (g == null) throw new IllegalArgumentException("Globals required");
        return g.gc.allocationGcRunning || LuaTable.isGcCallbackRunning(g);
    }

    // lmem.c: firsttry / debug_realloc
    // nsize > 0 且 osize != nsize 时才是一次可失败的分配调用。
    // java diff: C 的 debug_realloc 检查 mc->total+size-oldsize > mc->memlimit（mc->total
    //   随 freeblock 实时波动）；Java 的 luaMemoryBytes 在 pcall 期间高估（freeBytes 只在
    //   GC sweep 时调用），故限额生效时用 afterGCMem（GC 后基线）近似 mc->total。
    // C：ltests.c : l_memcontrol.total 是跨所有 lua_State 的进程总量，memlimit 对该总量生效。
    // Java 按 Globals 记账，故 ltests 限制生效时须以"本状态基线 + 其他状态已用量"比较，
    // 否则新建 state 的分配只与自身额度比较，永远不会触限（memerr/api 的限制用例）。
    private static long otherStatesBytes(Globals self) {
        final long[] sum = {0L};
        LuaTable.forEachActiveGlobals(o -> {
            if (o != self) sum[0] += o.gc.luaMemoryBytes;
        });
        return sum[0];
    }

    private static boolean firsttry(Globals g, long delta, boolean sizeChanged) {
        if (sizeChanged && allocFailNext) {
            allocFailNext = false;
            return false;
        }
        if (sizeChanged && allocCountLimit >= 0) {
            if (allocCountLimit == 0) return false;
            allocCountLimit--;
        }
        // afterGCMem 是进程级的 GC 后总量快照，已包含其他状态；只有以按状态的
        // luaMemoryBytes 作基线时才补其他状态用量，否则会把主状态重复计入，
        // 导致新建 state 的字节检查恒失败（memerr.lua testbytes 不收敛）。
        boolean useAfterGC = USE_AFTER_GC_MEM && afterGCMem > 0
                && luaMemoryLimitBytes != defaultMemoryLimit;
        long baseline = useAfterGC ? afterGCMem
                : g.gc.luaMemoryBytes + otherStatesBytes(g);
        return baseline + delta <= luaMemoryLimitBytes;
    }

    // lmem.c: firsttry  -  对应 C 中 n 次 frealloc 调用
    // java diff: 与 firsttry 相同的 afterGCMem 逻辑（见上方注释）
    private static boolean firsttryN(Globals g, long delta, int n) {
        if (allocFailNext) {
            allocFailNext = false;
            return false;
        }
        if (allocCountLimit >= 0) {
            if (allocCountLimit < n) return false;
            allocCountLimit -= n;
        }
        boolean useAfterGC = USE_AFTER_GC_MEM && afterGCMem > 0
                && luaMemoryLimitBytes != defaultMemoryLimit;
        long baseline = useAfterGC ? afterGCMem
                : g.gc.luaMemoryBytes + otherStatesBytes(g);
        return baseline + delta <= luaMemoryLimitBytes;
    }

    // lgc.c: tryagain
    private static boolean tryagain(Globals g, long delta, boolean sizeChanged) {
        if (!cantryagain(g)) return false;
        // java diff: allocCountLimit 导致失败时跳过 fullGC；
        // fullGC 不改变 allocCountLimit，徒劳无益
        if (allocCountLimit >= 0) return firsttry(g, delta, sizeChanged);
        if (luaMemoryLimitBytes != defaultMemoryLimit) {
            fullGCInternal(g, true);
            return firsttry(g, delta, sizeChanged);
        }
        fullGCInternal(g, true);
        System.gc();
        Runtime rt = Runtime.getRuntime();
        long freeHeap = rt.maxMemory() - (rt.totalMemory() - rt.freeMemory());
        if (freeHeap > delta * 2) {
            luaMemoryLimitBytes = Math.max(luaMemoryLimitBytes, g.gc.luaMemoryBytes + delta + 1);
        }
        return firsttry(g, delta, sizeChanged);
    }

    // lgc.c: tryagain  -  对应 C 中 n 次 frealloc 调用
    private static boolean tryagainN(Globals g, long delta, int n) {
        if (!cantryagain(g)) return false;
        // java diff: allocCountLimit 导致失败时跳过 fullGC
        if (allocCountLimit >= 0) return firsttryN(g, delta, n);
        if (luaMemoryLimitBytes != defaultMemoryLimit) {
            fullGCInternal(g, true);
            return firsttryN(g, delta, n);
        }
        fullGCInternal(g, true);
        System.gc();
        Runtime rt = Runtime.getRuntime();
        long freeHeap = rt.maxMemory() - (rt.totalMemory() - rt.freeMemory());
        if (freeHeap > delta * 2) {
            luaMemoryLimitBytes = Math.max(luaMemoryLimitBytes, g.gc.luaMemoryBytes + delta + 1);
        }
        return firsttryN(g, delta, n);
    }

    // java-only
    private static boolean cantryagain(Globals g) {
        return !g.gc.gcstopem && !LuaTable.isCollecting(g);
    }

    // lgc.c: setpause  -  threshold = applygcparam(g, PAUSE, g->GCmarked)；debt = threshold - gettotalbytes(g)
    // java diff: 用 luaMemoryBytes 近似 GCmarked
    // java-only: 动态阈值调整 - 无对象回收时倍增（减少 all-live 工作负载的 GC 周期数），
    //   有对象回收时重置为基础阈值。对齐 C 的自适应行为（GCdebt 增长慢->GC 少触发；
    //   Java 的 allocationDebt 固定步长需显式自适应）。
    private static void setpause(Globals g) {
        // java-only: 动态阈值调整（A/B 开关 luajvm.dynGcThreshold，默认开启）
        //   仅增量模式生效 - 分代模式有独立的 minor/major 阈值语义（gengc.lua 依赖
        //   固定的 age 转换 new->survival->old1->old，动态阈值会破坏其断言）。
        if (DYN_GC_THRESHOLD && g.gc.gckind == KGC_INC) {
            if (!g.gc.objectsSweptThisCycle) {
                g.gc.currentAllocationGcThreshold = Math.min(g.gc.currentAllocationGcThreshold * 2, MAX_ALLOCATION_GC_THRESHOLD);
            } else {
                g.gc.currentAllocationGcThreshold = BASE_ALLOCATION_GC_THRESHOLD;
            }
        }
        g.gc.objectsSweptThisCycle = false;

        long pause = g.gc.gcParams[GCP_PAUSE];
        long threshold = pause * g.gc.luaMemoryBytes / 100;
        long debt = threshold - g.gc.luaMemoryBytes;
        if (debt < 0) debt = 0;

        setDebt(g, debt);
    }

    // lgc.c: incstep  -  现由 gcStep 用 singlestep 循环处理

    // java-only
    private static String modeName(Globals g) {
        return g.gc.gckind == KGC_GENMINOR ? "generational" : "incremental";
    }

    // lmem.c: luaM_error // java diff: 直接抛LuaError非luaD_throw
    private static void memError() {
        if (TRACE_GC) {
            new IllegalStateException("memError thrown").printStackTrace();
        }
        LuaErrors.memError();
    }

    // java-only: requestJvmGC  -  C不请求宿主GC，Java用于emergency retry或调试节流
    private static void requestJvmGC(Globals g, boolean force) {
        if (!force && JVM_GC_MIN_INTERVAL_NANOS < 0) return;
        long now = System.nanoTime();
        if (force || now - g.gc.lastJvmGcNanos >= JVM_GC_MIN_INTERVAL_NANOS) {
            System.gc();
            g.gc.lastJvmGcNanos = now;
        }
    }

    static final class GrayList {
        LuaValue head;

        // linkgclist_  -  LIFO 头插（对齐 C: o->gclist = *list; *list = o）
        void push(LuaValue v) {
            v.gclist = head;
            head = v;
        }

        // propagatemark  -  LIFO 头删（对齐 C: g->gray = *getgclist(o)）
        LuaValue pop() {
            LuaValue v = head;
            head = v.gclist;
            v.gclist = null;
            return v;
        }

        boolean isEmpty() {
            return head == null;
        }

        // 交出整条链并置空表头（链本身保持完整，由调用方接管遍历）
        LuaValue detach() {
            LuaValue h = head;
            head = null;
            return h;
        }

        // 必须逐个清 gclist：否则对象脱离链表后 gclist 仍非 null，
        // 下次入链会覆盖别的链的 next 指针，把那条链截断（单归属违规）。
        void clear() {
            LuaValue v = head;
            while (v != null) {
                LuaValue next = v.gclist;
                v.gclist = null;
                v = next;
            }
            head = null;
        }

        // atomic()  -  merge other into this. 前置条件：this 已被 propagateGray 清空
        //   （对齐 C: atomic 先 propagateall 再 g->gray = g->grayagain; g->grayagain = NULL）
        void addAll(GrayList other) {
            if (head != null) {
                // 防御：gray 未清空时追加到尾部（理论上不发生）
                LuaValue tail = head;
                while (tail.gclist != null) tail = tail.gclist;
                tail.gclist = other.head;
            } else {
                head = other.head;
            }
            other.head = null;
        }
    }
}
