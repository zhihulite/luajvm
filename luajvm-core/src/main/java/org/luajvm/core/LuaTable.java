// ref: ltable.c / lobject.h (Table)
// diff: long[] array_numVals+Object[] array_refs+byte[] array_tags(T_* tag)替代Value*+tag* | null检查替代dummynode | next绝对下标替代gnext偏移 | ArrayList替代GCObject链表 | hashpow2统一
package org.luajvm.core;

import org.luajvm.vm.FlatArith;
import org.luajvm.vm.LuaCall;
import org.luajvm.vm.LuaVM;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Random;
import java.util.function.Consumer;

public class LuaTable extends LuaValue {
    // java-only: 哈希节点的 VNIL 标签 - 区别于 T_NIL(=VEMPTY)
    //   C: LUA_VNIL vs LUA_VEMPTY（isempty 对二者同判空）；Java 保留区分：
    //   T_NIL(0)=VEMPTY(未用槽), T_NILVAL(5)=VNIL(占用槽的 nil 值)。
    public static final byte T_NILVAL = 5;
    // ltable.h: luaH_pset  -  results (hres 编码)
    public static final int HOK = 0;
    public static final int HNOTFOUND = 1;
    public static final int HNOTATABLE = 2;
    public static final int HFIRSTNODE = 3;
    // ltm.h: checknoTM(mt,e)  -  元表无标签方法 e 时为真
    // java diff: C 检查 metatable->flags；Java 检查 metatable.flags（LuaTable.flags 字段）
    // NEWINDEX 序数=1 ⇒ 1 << 1 = 2；预计算避免 ordinal() 调用
    public static final byte MASK_NEWINDEX = (byte) (1 << Metamethod.NEWINDEX.ordinal());
    // MODE 序数=3 ⇒ 1 << 3 = 8；为 weakMode() 标志检查预计算
    // java-only: 对齐 C 的 gfasttm flags 位图（ltm.h: gfasttm）
    public static final byte MASK_MODE = (byte) (1 << Metamethod.MODE.ordinal());
    // java-only: key_tt GC 标记跳过优化的 A/B 开关
    // -Dluajvm.gckeytt=false 禁用 key_tt 检查（总是调用 markValue）
    static final boolean GC_KEYTT =
            System.getProperty("luajvm.gckeytt") == null ||
                    Boolean.parseBoolean(System.getProperty("luajvm.gckeytt"));
    // GC 根状态注册表；仅保存生命周期，不提供隐式"当前 Globals"解析。
    // java-only
    private static final long ALLOCATION_GC_THRESHOLD = 256L * 1024;
    // java-only
    private static final long ALLOCATION_GC_BACKGROUND_THRESHOLD = 1024 * 1024;
    // java-only: 近似 ltable.c:concretesize/sizehash 的 Lua 管理内存单位。
    private static final int ARRAY_SLOT_BYTES = 16;
    private static final int HASH_NODE_BYTES = 16;
    // 对齐 C 的 G(L)->seed（lstate.c: makeseed，per-state 随机）：供 hash_search 的
    // j=2j+(rnd&1) 抖动，打破"键全是 2 的幂"构造攻击（见 hashSearch，nextvar.lua attack）。
    // Java 用进程级随机 seed，同进程内所有表共享，等价于 C 的 per-state 语义。
    private static final int HASH_SEED = new Random().nextInt();
    private static final byte LUA_TDEADKEY = 21;  // lobject.h: LUA_TDEADKEY; java: (1<<4)|TTABLE=21
    // 仅表对象按需保存所属状态；普通 LuaValue 不保存 Globals。
    public Globals ownerGlobals;
    public LuaValue metatable;      // lobject.h: Table.metatable; public for VM inline access (C uses h->metatable)

    // C：lstate.h : GCObject 归属 global_State。
    public final void bindGlobals(Globals globals) {
        if (globals == null || ownerGlobals == globals) return;
        if (ownerGlobals != null) throw LuaErrors.errorObject("table belongs to another Globals");
        ownerGlobals = globals;
        long storageBytes = currentStorageBytes();
        // 分配限额检查在构造处完成（对齐 C 的 luaM_newobject）：ltests 限额是进程级静态，
        // 构造期即可检查。未绑定构造阶段没有状态，绑定时才完成 C luaC_newobj 的颜色与内存记账。
        gcColor = LuaGC.isWhite(globals);
        globals.gc.allTables.add(this);
        bindContainedValues(globals);
        LuaGC.commitRealloc(globals, 0, storageBytes);
        // C：lgc.c : luaC_newobj 仅登记对象并记账。
        // 收集统一由调用方锚定对象之后的 checkGC 完成 —— bind 阶段新表仅被局部引用，
        // 此处触发 GC 会使其被判死并从 allTables 移除、永久脱离管理。
    }

    private void bindContainedValues(Globals globals) {
        bindValue(globals, metatable);
        if (array_tags != null) {
            for (int i = 0; i < array_tags.length; i++) {
                if (array_tags[i] == FlatArith.T_REF) bindValue(globals, (LuaValue) array_refs[i]);
            }
        }
        if (node != null) {
            for (Node n : node) {
                if (n == null || !n.live()) continue;
                bindValue(globals, n.key);
                if (n.value_tag == FlatArith.T_REF) bindValue(globals, (LuaValue) n.value_ref);
            }
        }
    }

    public static void bindValue(Globals globals, LuaValue value) {
        if (value instanceof LuaFunction fn) fn.bindGlobals(globals);
        else if (value instanceof LuaTable table) {
            table.bindGlobals(globals);
        }
        else if (value instanceof LuaUserdata userdata) userdata.bindGlobals(globals);
    }
    // ltable.h: getArrTag；java diff: byte[] 标签用 FlatArith T_* 常量（T_NIL=0=空，T_INT=1，T_FLT=2，T_REF=3，T_BOOL=4）
    // 对齐 C 的 lu_byte* tag 数组；tag==0 仍表空槽，所有 array_tags[u]!=0 检查零改动。
    public byte[] array_tags;       // lobject.h: Table.alist (tags); public for VM inline access (C uses macros)
    // ltable.h: getArrVal; java diff: long[] 存数值位（整数=long，浮点=doubleToRawLongBits，布尔=0/1）
    public long[] array_numVals;    // lobject.h: Table.alist (value bits); public for VM inline access
    // ltable.h: getArrVal; java diff: Object[] 引用边车（T_REF 时存 LuaValue；其余类型=null）
    public Object[] array_refs;     // lobject.h: Table.alist (ref values); public for VM inline access
    public Node[] node;          // lobject.h: Table.node; public for VM inline access
    public byte lsizenode;            // lobject.h: Table.lsizenode; public for VM inline access
    public int lenhint;         // lobject.h: Table.lenhint

    // -- ltable.h: farr2val / fval2arr -- sidecar 三数组 <-> LuaValue 编解码（C 用 TValue 联合体，
    //   Java 用并行数组模拟）；tag==0(T_NIL) 表空槽。static 供 LuaVM/FlatTFor 内联（与 C 宏同级）。
    public byte flags;                // lobject.h: Table.flags; public for VM inline access
    private int lastfree;       // lobject.h: Table.lastfree
    private boolean m_finalizerRegistered;  // java-only: finalizer登记标记
    // java-only: weakMode() 用 flags 位图缓存（对齐 C gfasttm）。
    // -- border 缓存（对齐 C luaH_getn 的 *lenhint(t)），独立字段，仅 rawlen/resize 维护 --
    // 本类 lenhint 是 java-only 的"数组段最高非 nil 下标"，写路径必须维护，与 border 语义不同。
    private int borderHint;

    // ltable.c: luaH_new  -  空表只有 1 次 frealloc
    public LuaTable() {
        super(LUA_VTABLE | BIT_ISCOLLECTABLE);
        long memBytes = tableStorageBytes(0, 1);
        LuaGC.checkMemory(memBytes);
        allocationGCStep((int) Math.min(Integer.MAX_VALUE, memBytes));
        lenhint = 0;
        array_tags = null;
        array_numVals = null;
        array_refs = null;
        lsizenode = 0;
        node = new Node[0];
        lastfree = 0;
        m_finalizerRegistered = false;
        flags = (byte) 0xFF;
        gcColor = LuaGC.isWhite();  // lgc.c: luaC_newobj sets marked = isWhite(g)
        LuaGC.commitRealloc(0, memBytes);
    }

    public LuaTable(Globals g) {
        this();
        bindGlobals(g);
    }

    // ltable.c: luaH_new
    public LuaTable(int na, int nh) {
        super(LUA_VTABLE | BIT_ISCOLLECTABLE);
        int hsize = nextPow2(nh);  // ltable.c: setnodevector; nh=0 -> hsize=0 (dummy node)
        long memBytes = tableStorageBytes(na, Math.max(hsize, 1));  // java diff: always allocate at least 1 for memory accounting
        // ltable.c: luaH_new + luaH_resize 的 frealloc 次数
        // java diff: 三数组算 1 次 frealloc（保 T.alloccount 逐位一致）
        int nAllocs = 1 + (hsize > 0 ? 1 : 0) + (na > 0 ? 1 : 0);
        LuaGC.checkMemoryN(memBytes, nAllocs);
        allocationGCStep((int) Math.min(Integer.MAX_VALUE, memBytes));
        lenhint = 0;
        // java diff: byte[] 默认 0=T_NIL（空槽），long[] 默认 0，Object[] 默认 null - 无需 fill
        array_tags = na > 0 ? new byte[na] : null;
        array_numVals = na > 0 ? new long[na] : null;
        array_refs = na > 0 ? new Object[na] : null;
        // ltable.c: setnodevector；size=0 -> dummynode（Java：空数组）
        if (hsize == 0) {
            lsizenode = 0;
            node = new Node[0];  // ltable.c: dummynode equivalent
            lastfree = 0;
        } else {
            lsizenode = (byte) (31 - Integer.numberOfLeadingZeros(hsize));
            node = new Node[hsize];
            lastfree = hsize;
            // ltable.c: setnodevector  -  initialize all nodes
            for (int j = 0; j < hsize; j++) {
                node[j] = new Node(null, null);
            }
        }
        m_finalizerRegistered = false;
        flags = (byte) 0xFF;  // lobject.h: maskflags
        gcColor = LuaGC.isWhite();  // lgc.c: luaC_newobj sets marked = isWhite(g)
        LuaGC.commitRealloc(0, memBytes);
    }

    public LuaTable(Globals g, int na, int nh) {
        this(na, nh);
        bindGlobals(g);
    }

    // luaH_new（从 Varargs）
    // java-only
    public LuaTable(Varargs v, int f) {
        this(0, 0);
        if (v != null)
            for (int i = f, n = v.narg(); i <= n; i++)
                rawset(lenhint + 1, v.arg(i));
    }

    // 活动状态登记表统一存放在 LuaStates（中立类，无静态依赖），此处仅转发：
    // 字符串分配路径须经 LuaStates 取状态，不能反向引用 LuaTable（静态初始化环）。
    public static void registerGlobals(Globals g) {
        LuaStates.register(g);
    }

    public static void unregisterGlobals(Globals g) {
        LuaStates.unregister(g);
    }

    public static int activeGlobalsCount() {
        return LuaStates.count();
    }

    /**
     * C：ltests.c : l_memcontrol 是进程级单例，其分配配额对所有 lua_State 生效。
     * ltests 广播配额、以及按进程总量求和时遍历全部登记状态。
     */
    public static void forEachActiveGlobals(Consumer<Globals> action) {
        Globals[] states = LuaStates.snapshot();
        for (Globals g : states) action.accept(g);
    }

    // ldo.c: luaD_checkminstack；多状态 Java 实现检查当前登记状态中的运行线程。
    static boolean checkMinStackForActiveGlobals() {
        Globals[] states = LuaStates.snapshot();
        for (Globals g : states) {
            if (g.running != null && LuaCall.checkMinStack(g.running)) return true;
        }
        // Java 的终结器调用经 Globals 执行器，不依赖当前线程栈；状态存在即可执行。
        return states.length > 0;
    }

    // lgc.c: GCTM 的 lua_State 上下文；对象未携带归属时仅在 GC 回调期间取运行状态。
    // 测试库（LtestsDebugLib）模拟 ltests 调试 API 处理 LUA_REGISTRYINDEX 时需跨包取当前运行
    // Globals 的 registry，故公开（语义与 GC 回调一致：取活动/运行状态兜底）。
    public static Globals runningGlobalsForGC() {
        return LuaStates.owner();
    }

    // ltable.h: fval2arr  -  LuaValue -> sidecar（写表槽）
    public static void fval2arr(byte[] tags, long[] numVals, Object[] refs, int u, LuaValue val) {
        int tt = val.tt_;
        if (tt == LuaValue.LUA_VNUMINT) {
            tags[u] = FlatArith.T_INT;
            numVals[u] = ((LuaInteger) val).v;
            refs[u] = null;
        } else if (tt == LuaValue.LUA_VNUMFLT) {
            tags[u] = FlatArith.T_FLT;
            numVals[u] = Double.doubleToRawLongBits(((LuaFloat) val).v);
            refs[u] = null;
        } else if (tt == LuaValue.LUA_VTRUE) {
            tags[u] = FlatArith.T_BOOL;
            numVals[u] = 1;
            refs[u] = null;
        } else if (tt == LuaValue.LUA_VFALSE) {
            tags[u] = FlatArith.T_BOOL;
            numVals[u] = 0;
            refs[u] = null;
        } else if (tt == LuaValue.LUA_VNIL) {
            tags[u] = FlatArith.T_NIL;
            numVals[u] = 0;
            refs[u] = null;
        } else {
            tags[u] = FlatArith.T_REF;
            refs[u] = val;
            numVals[u] = 0;
        }
    }

    // ltable.h: farr2val  -  sidecar -> LuaValue（读表槽）
    public static LuaValue farr2val(byte[] tags, long[] numVals, Object[] refs, int u) {
        switch (tags[u]) {
            case FlatArith.T_INT:
                return LuaInteger.valueOf(numVals[u]);
            case FlatArith.T_FLT:
                return LuaFloat.valueOf(Double.longBitsToDouble(numVals[u]));
            case FlatArith.T_BOOL:
                return numVals[u] != 0 ? LuaValue.TRUE : LuaValue.FALSE;
            case FlatArith.T_REF:
                return (LuaValue) refs[u];
            default:
                return LuaValue.NIL;  // T_NIL
        }
    }

    // java-only: 清空表槽（写 nil），对齐 C 的 setempty + tag=LUA_VNIL
    public static void clearArrSlot(byte[] tags, long[] numVals, Object[] refs, int u) {
        tags[u] = FlatArith.T_NIL;
        numVals[u] = 0;
        refs[u] = null;
    }

    // ltable.c: concretesize
    private static long arrayBytes(int asize) {
        return asize > 0 ? (long) asize * ARRAY_SLOT_BYTES : 0;
    }

    // ltable.c: sizehash
    private static long hashBytes(int hsize) {
        return (long) hsize * HASH_NODE_BYTES;
    }

    // luaH_size 中的 array/hash 存储部分；Table 对象头由 JVM 承载。
    private static long tableStorageBytes(int asize, int hsize) {
        return arrayBytes(asize) + hashBytes(hsize);
    }

    // luaO_ceillog2 的辅助; ltable.c: nextPow2 maps to twoto(luaO_ceillog2)
    // java diff: n<=0 返回 0（dummy node）；C 用 setnodevector(size=0) 构造 dummynode
    private static int nextPow2(int n) {
        if (n <= 0) return 0;
        if (n <= 1) return 1;
        int p = 1;
        while (p < n) p <<= 1;
        return p;
    }

    // luaC_checkGC/luaC_step; java: 无完整增量GC，按显式collect顺序推进弱表清理与finalizer
    private void allocationGCStep(int bytes) {
        if (ownerGlobals == null) return;
        if (isCollecting(ownerGlobals)) return;
        ownerGlobals.gcCheck(bytes);
    }


    // java-only
    public static boolean isCollecting(Globals g) {
        return g != null && (g.gc.allocationGcRunning || g.gc.runningFinalizers || g.gc.sweepingWeakTables);
    }


    // java-only
    static boolean isGcCallbackRunning(Globals g) {
        return g != null && (g.gc.runningFinalizers || g.gc.sweepingWeakTables);
    }


    // lgc.c: singlestep GCScallfin  -  判据是 g->tobefnz（本轮已分离待调用），不是 g->finobj
    static boolean hasToBeFinalized(Globals g) {
        return g != null && !g.gc.tobefnz.isEmpty();
    }


    // java-only
    public static int managedTableCount(Globals g) {
        return g == null ? 0 : g.gc.allTables.size();
    }



    // lgc.c: separatetobefnz + markbeingfnz
    public static void separateAndMarkFinalizers(Globals g) {
        separateAndMarkFinalizersInternal(g);
    }

    // lgc.c: separatetobefnz + markbeingfnz
    // java diff: 用 gcColor 而非 marked 集合
    private static void separateAndMarkFinalizersInternal(Globals g) {
        List<LuaValue> toFinalize = new ArrayList<>();
        int n = g.gc.finobj.size();
        while (n-- > 0 && !g.gc.finobj.isEmpty()) {
            PendingFinalizer pf = g.gc.finobj.removeFirst();
            if (LuaGC.isblack(pf.value.gcColor)) {
                g.gc.finobj.addLast(pf);
            } else {
                toFinalize.add(pf.value);
            }
        }
        // lgc.c: markbeingfnz  -  标记将被终结的对象
        LuaGC.GrayList gray = new LuaGC.GrayList();
        for (LuaValue v : toFinalize) {
            markValue(g, v, gray);
        }
        // java-only: 同时标记上次 emergency GC 分离但未 finalize 的对象 - 否则本周期 sweep 会
        //   当 dead 清掉，finalizer 永不运行（memerr.lua testbytes 泄漏根因）
        for (LuaValue v : g.gc.tobefnz) {
            markValue(g, v, gray);
        }
        if (!gray.isEmpty()) {
            propagateGray(g, gray);
            convergeEphemeron(g, gray);
        }
        // java diff: APPEND 而非覆盖 - emergency GC 分离未调的 finalizer 保留在列表中，覆盖会丢
        g.gc.tobefnz.addAll(toFinalize);
    }

    // lgc.c: callfin  -  对每个待终结对象调用 __gc
    // java diff: 终结器调用期间在当前 CallInfo 上设置 CIST_FIN（C: lgc.c）
    static void callFinalizers(Globals g, List<LuaValue> toFinalize) {
        for (LuaValue v : toFinalize) {
            LuaValue finalizer = v.getmetatable() != null
                    ? v.getmetatable().rawget(LuaValue.GC)
                    : LuaValue.NIL;
            if (finalizer != null && !finalizer.isnil()) {
                LuaThread L = g != null ? g.running : null;
                CallInfo ci = L != null ? L.ci : null;
                // lgc.c: GCTM 用 luaD_pcall 运行 __gc，出错恢复 L->ci/L.top - 仅 catch 会丢栈
                //   恢复，陈旧 L.top 被当 func 槽 -> ClassCastException
                int oldTop = L != null ? L.top : 0;
                byte oldAllowhook = L != null ? L.allowhook : 0;
                // lgc.c: GCTM  -  finalizer 执行期间禁 hook
                if (L != null) L.allowhook = 0;
                try {
                    if (ci != null) ci.callstatus |= CallInfo.CIST_FIN;
                    LuaCall.invoke(finalizer, v);
                } catch (LuaError e) {
                    LuaValue errObj = e.luaError;
                    String msg = (errObj != null && errObj.isstring()) ? errObj.toJavaString() : "error object is not a string";
                    // 对齐 luaD_pcall 出错路径（ldo.c: luaD_pcall）：恢复 L->ci/allowhook ->
                    //   luaD_closeprotected 关闭 old_top 以上 tbc 变量 -> restorestack 恢复栈顶
                    //   （closeUpvals 内部重试至无错，不向外抛）
                    if (L != null) {
                        L.ci = ci;
                        L.allowhook = oldAllowhook;
                        LuaVM.closeUpvals(L, oldTop);  // ldo.c: luaD_closeprotected(L, old_top, status)
                        L.top = oldTop;
                    }
                    g.warnerror("__gc", msg);   // lstate.c: luaE_warnerror
                } finally {
                    if (L != null) L.allowhook = oldAllowhook;  // lgc.c: GCTM 恢复
                    if (ci != null) ci.callstatus &= ~CallInfo.CIST_FIN;
                }
            }
            clearFinalizerRegistration(v);
        }
    }

    // ltable.c: l_hashfloat
    private static int hashFloat(double n) {
        if (Double.isNaN(n) || Double.isInfinite(n)) return 0;
        long bits = Double.doubleToLongBits(n);
        int h = (int) (bits ^ (bits >>> 32));
        return h == Integer.MIN_VALUE ? 0 : Math.abs(h);
    }

    // luaC_checkfinalizer (查询)
    static boolean hasFinalizer(LuaValue mt) {
        if (mt == null || !mt.istable()) return false;
        LuaValue gc = mt.rawget(LuaValue.GC);
        return gc != null && !gc.isnil();
    }

    // luaC_checkfinalizer (登记候选)
    static void registerFinalizerCandidate(LuaValue value) {
        if (value instanceof LuaTable table) {
            if (table.m_finalizerRegistered) return;
            if (table.ownerGlobals == null) return;
            table.m_finalizerRegistered = true;
            table.ownerGlobals.gc.finobj.addFirst(new PendingFinalizer(table));
        } else if (value instanceof LuaUserdata userdata) {
            if (userdata.m_finalizerRegistered) return;
            if (userdata.ownerGlobals == null) return;
            userdata.m_finalizerRegistered = true;
            userdata.ownerGlobals.gc.finobj.addFirst(new PendingFinalizer(userdata));
        }
    }

    // java-only: 清除finalizer登记标记
    static void clearFinalizerRegistration(LuaValue value) {
        if (value instanceof LuaTable table) {
            table.m_finalizerRegistered = false;
        } else if (value instanceof LuaUserdata userdata) {
            userdata.m_finalizerRegistered = false;
        }
    }

    // runafewfinalizers; java: setmetatable时登记候选，执行前用roots过滤
    // lgc.c: GCTM  -  调用待处理的终结器
    // java diff: 仅从 pendingCallFinalizers 列表调用终结器（由 separateAndMarkFinalizers 设置）；
    // 不做 mark+sweep，那由 fullGC/checkGC 完成
    public static void runPendingFinalizers(Globals g) {
        if (g.gc.runningFinalizers || g.gc.sweepingWeakTables) return;
        if (g.gc.tobefnz.isEmpty()) return;
        g.gc.runningFinalizers = true;
        try {
            List<LuaValue> toFinalize = new ArrayList<>(g.gc.tobefnz);
            g.gc.tobefnz.clear();
            callFinalizers(g, toFinalize);
        } finally {
            g.gc.runningFinalizers = false;
        }
    }

    // java-only: 调用 separateAndMarkFinalizers 存储的待处理终结器
    public static void callPendingFinalizers(Globals g) {
        // lgc.c: GCScallfin  -  调用 finalizer 前检查最小栈空间（含 ltests alloccount 限制下
        //   CallInfo 分配失败时不调用，对象保留等下次 GC - gc.lua）。
        // java diff: 用 tryAllocCount（ltests.c: allocf 递减语义）：alloccount(0) 恒 false
        //   （gc.lua 语义不变）；alloccount(N) 允许 N 次后失败（memerr.lua 靠 M 递增突破）
        if (!LuaGC.tryAllocCount(g)) {
            return;  // 分配受限：跳过本次，对象保留 pendingCallFinalizers
        }
        callFinalizers(g, g.gc.tobefnz);
        g.gc.tobefnz.clear();
    }

    // java-only: 检查 pendingCallFinalizers 是否有条目
    // 供 LuaGC.fullGC 快路径使用：确保 emergency fullGC 延迟的
    // 终结器（因 isemergency 被跳过）由下一次非 emergency fullGC 调用
    public static boolean tobefnzEmpty(Globals g) {
        return g == null || g.gc.tobefnz.isEmpty();
    }

    // java-only: 待处理终结器计数（调试日志用）
    public static int tobefnzCount(Globals g) {
        return g == null ? 0 : g.gc.tobefnz.size();
    }

    // lgc.c: finishfullgc  -  把所有存活对象置为 G_OLD + gcColor 设为 currentwhite
    // java diff: C 的 sweeplist 置存活对象为 currentwhite|G_NEW，Java 的 sweep 只置 currentwhite，
    //   故 agesAfterFullGC 仅需为 currentwhite（非 otherwhite/dead）者设 gcAge
    // java-only: 非白对象重置为白再 markRoots（fullGC 用 - grayagain 已清、barrier 信息丢失）
    static void repropagateAll(Globals g, LuaGC.GrayList gray, byte cw) {
        for (int _gi = 0, _gn = g.gc.allTables.size(); _gi < _gn; _gi++) {
            LuaTable t = g.gc.allTables.get(_gi);
            // 复位为白须同时脱链（白对象不得留在灰链中，否则再次入链会截断链表）
            if (!LuaGC.iswhite(t.gcColor)) {
                t.makeWhite(cw);
                t.gclist = null;
            }
        }
        LuaClosure.repropagateAll(g, cw);
        // java diff: LuaFunction 在 allFunctions 列表跟踪（替代 O(n_threads x stack_size) 栈扫
        //   描）；修掉存储于 table/upvalue 的 LuaFunction 不被重置颜色的 bug
        LuaFunction.repropagateAllFunctions(g, cw);
        LuaThread.repropagateAll(g, cw);
        LuaUserdata.repropagateAll(g, cw);
        Prototype.repropagateAll(g, cw);
        LuaString.repropagateLongStrings(g, cw);
        markRoots(g, gray);
    }

    // lgc.c: atomic 的 remark 段 - 仅重新标记根，不复位任何对象颜色：
    // 线程已在 propagate 相位由 traversethread 登记进 grayagain，atomic 会重新遍历；
    // 复位反而清掉挂在 markRoots 灰链上的 gclist 使链截断。
    static void remarkRootsForAtomic(Globals g, LuaGC.GrayList gray) {
        markRoots(g, gray);
    }

    // lgc.c: sweep2old  -  把所有存活对象置为 G_OLD + currentwhite
    // java diff: tables/userdata/closures/protos/functions 在 sweep 时已置 G_OLD+currentwhite
    // （对齐 C 的 sweep2old），故本方法仅处理：
    // 1) thread  -  sweepByColor 跳过主线程，故在此设置其 age/color（对非主线程幂等）
    // 2) LuaString 颜色重置（strCache 清空）
    // LuaFunction（LUA_VLCF）由 sweepFunctionsByColor 处理（在 allFunctions 列表中），
    // O(n_threads) 无内层循环
    public static void agesAfterFullGC(Globals g) {
        byte cw = LuaGC.isWhite(g);
        for (int _gi = 0, _gn = g.gc.allThreads.size(); _gi < _gn; _gi++) {
            LuaThread thread = g.gc.allThreads.get(_gi);
            // sweepByColor 跳过主线程；为所有线程设置 age/color（对非主线程幂等）
            thread.gcAge = LuaValue.G_OLD;
            thread.makeWhite(cw);
        }
        LuaString.resetColorsAfterFullGC(g);
    }

    // lgc.c: sweepgen  -  nextage[] age transitions
    // java diff: C 用链表且仅处理非白对象；Java 处理所有对象，因 markReachableValues 可能漏掉
    // 一些（Java 栈帧上的 Lua 局部变量不在 LuaThread.stack 上）
    // lgc.c: sweepgen  -  TOUCHED1/TOUCHED2 不在此推进（correctgraylist 处理）
    public static void agesAfterStepGC(Globals g) {
        for (int _gi = 0, _gn = g.gc.allTables.size(); _gi < _gn; _gi++) {
            LuaTable table = g.gc.allTables.get(_gi);
            switch (table.gcAge) {
                case LuaValue.G_NEW:
                    table.gcAge = LuaValue.G_SURVIVAL;
                    break;
                case LuaValue.G_SURVIVAL:
                    table.gcAge = LuaValue.G_OLD1;
                    break;
                case LuaValue.G_OLD0:
                    table.gcAge = LuaValue.G_OLD1;
                    break;
                case LuaValue.G_OLD1:
                    table.gcAge = LuaValue.G_OLD;
                    break;
                // G_OLD, G_TOUCHED1, G_TOUCHED2: 不变（sweepgen nextage）
            }
        }
        for (int _gi = 0, _gn = g.gc.allUserdata.size(); _gi < _gn; _gi++) {
            LuaUserdata ud = g.gc.allUserdata.get(_gi);
            switch (ud.gcAge) {
                case LuaValue.G_NEW:
                    ud.gcAge = LuaValue.G_SURVIVAL;
                    break;
                case LuaValue.G_SURVIVAL:
                    ud.gcAge = LuaValue.G_OLD1;
                    break;
                case LuaValue.G_OLD0:
                    ud.gcAge = LuaValue.G_OLD1;
                    break;
                case LuaValue.G_OLD1:
                    ud.gcAge = LuaValue.G_OLD;
                    break;
            }
        }
        for (int _gi = 0, _gn = g.gc.allThreads.size(); _gi < _gn; _gi++) {
            LuaThread thread = g.gc.allThreads.get(_gi);
            switch (thread.gcAge) {
                case LuaValue.G_NEW:
                    thread.gcAge = LuaValue.G_SURVIVAL;
                    break;
                case LuaValue.G_SURVIVAL:
                    thread.gcAge = LuaValue.G_OLD1;
                    break;
                case LuaValue.G_OLD0:
                    thread.gcAge = LuaValue.G_OLD1;
                    break;
                case LuaValue.G_OLD1:
                    thread.gcAge = LuaValue.G_OLD;
                    break;
            }
        }
        for (int _gi = 0, _gn = g.gc.allClosures.size(); _gi < _gn; _gi++) {
            LuaClosure cl = g.gc.allClosures.get(_gi);
            switch (cl.gcAge) {
                case LuaValue.G_NEW:
                    cl.gcAge = LuaValue.G_SURVIVAL;
                    break;
                case LuaValue.G_SURVIVAL:
                    cl.gcAge = LuaValue.G_OLD1;
                    break;
                case LuaValue.G_OLD0:
                    cl.gcAge = LuaValue.G_OLD1;
                    break;
                case LuaValue.G_OLD1:
                    cl.gcAge = LuaValue.G_OLD;
                    break;
            }
        }
        // lgc.c: traversestrongtable luaC_barrier  -  old table referencing new object -> touched1
        for (int _gi = 0, _gn = g.gc.allTables.size(); _gi < _gn; _gi++) {
            LuaTable table = g.gc.allTables.get(_gi);
            if (LuaGC.isdead(g, table.gcColor) || table.gcAge < LuaValue.G_OLD) continue;
            boolean hasNewRef = false;
            if (table.array_tags != null) {
                // java diff: 仅检查 T_REF 槽（可回收类型），对齐节点段的 n.value_tag == T_REF
                // 检查，消除 farr2val + iscollectable() 虚调用开销
                byte[] at = table.array_tags;
                Object[] ar = table.array_refs;
                for (int i = 0; i < at.length; i++) {
                    if (at[i] == FlatArith.T_REF) {
                        LuaValue val = (LuaValue) ar[i];
                        if (val.gcAge == LuaValue.G_NEW) {
                            hasNewRef = true;
                            break;
                        }
                    }
                }
            }
            if (!hasNewRef && table.node != null && table.node.length > 0) {
                int limit = Math.min(table.sizenode(), table.node.length);
                for (int i = 0; i < limit; i++) {
                    Node n = table.node[i];
                    if (n != null && n.live()) {
                        if (n.key != null && n.key.iscollectable() && n.key.gcAge == LuaValue.G_NEW) {
                            hasNewRef = true;
                            break;
                        }
                        if (n.value_tag == FlatArith.T_REF && ((LuaValue) n.value_ref).gcAge == LuaValue.G_NEW) {
                            hasNewRef = true;
                            break;
                        }
                    }
                }
            }
            if (hasNewRef && table.gcAge == LuaValue.G_OLD) {
                table.gcAge = LuaValue.G_TOUCHED1;
                table.gcColor = LuaGC.GRAY;
            }
        }
    }


    // lgc.c: sweepgen + correctgraylist
    // java diff: C 用链表；Java 遍历全局集合
    // sweepgen: G_NEW->G_SURVIVAL+white；其他 age 前进，保持颜色
    // correctgraylist: TOUCHED1->TOUCHED2+black；TOUCHED2->OLD+black；thread 保持 gray
    // java diff: 处理所有对象（不限非白对象），因 markReachableValues 可能漏掉一些
    // （Java 栈帧上的 Lua 局部变量不在 LuaThread.stack 上）
    public static void resetColorsAfterStepGC(Globals g) {
        agesAfterStepGC(g);
        byte cw = LuaGC.isWhite(g);
        for (int _gi = 0, _gn = g.gc.allTables.size(); _gi < _gn; _gi++) {
            LuaTable table = g.gc.allTables.get(_gi);
            switch (table.gcAge) {
                case LuaValue.G_SURVIVAL:
                    // lgc.c: sweepgen  -  新对象回到白色
                    table.makeWhite(cw);
                    break;
                case LuaValue.G_TOUCHED1:
                    // lgc.c: correctgraylist  -  TOUCHED1->TOUCHED2+black
                    table.gcAge = LuaValue.G_TOUCHED2;
                    table.gcColor = LuaGC.BLACK;
                    break;
                case LuaValue.G_TOUCHED2:
                    // lgc.c: correctgraylist  -  TOUCHED2->OLD+black
                    table.gcAge = LuaValue.G_OLD;
                    table.gcColor = LuaGC.BLACK;
                    break;
                // 否则：保持颜色（sweepgen lgc.c）
            }
        }
        for (int _gi = 0, _gn = g.gc.allUserdata.size(); _gi < _gn; _gi++) {
            LuaUserdata ud = g.gc.allUserdata.get(_gi);
            switch (ud.gcAge) {
                case LuaValue.G_SURVIVAL:
                    ud.makeWhite(cw);
                    break;
                case LuaValue.G_TOUCHED1:
                    ud.gcAge = LuaValue.G_TOUCHED2;
                    ud.gcColor = LuaGC.BLACK;
                    break;
                case LuaValue.G_TOUCHED2:
                    ud.gcAge = LuaValue.G_OLD;
                    ud.gcColor = LuaGC.BLACK;
                    break;
            }
        }
        for (int _gi = 0, _gn = g.gc.allThreads.size(); _gi < _gn; _gi++) {
            LuaThread thread = g.gc.allThreads.get(_gi);
            switch (thread.gcAge) {
                case LuaValue.G_SURVIVAL:
                    thread.makeWhite(cw);
                    break;
                case LuaValue.G_TOUCHED1:
                    thread.gcAge = LuaValue.G_TOUCHED2;
                    thread.gcColor = LuaGC.BLACK;
                    break;
                case LuaValue.G_TOUCHED2:
                    thread.gcAge = LuaValue.G_OLD;
                    thread.gcColor = LuaGC.BLACK;
                    break;
                // lgc.c: correctgraylist  -  灰色 thread 保持灰色（留在 grayagain）
            }
        }
        for (int _gi = 0, _gn = g.gc.allClosures.size(); _gi < _gn; _gi++) {
            LuaClosure cl = g.gc.allClosures.get(_gi);
            switch (cl.gcAge) {
                case LuaValue.G_SURVIVAL:
                    cl.makeWhite(cw);
                    break;
                case LuaValue.G_TOUCHED1:
                    cl.gcAge = LuaValue.G_TOUCHED2;
                    cl.gcColor = LuaGC.BLACK;
                    break;
                case LuaValue.G_TOUCHED2:
                    cl.gcAge = LuaValue.G_OLD;
                    cl.gcColor = LuaGC.BLACK;
                    break;
            }
        }
        for (int _gi = 0, _gn = g.gc.allProtos.size(); _gi < _gn; _gi++) {
            Prototype pt = g.gc.allProtos.get(_gi);
            switch (pt.gcAge) {
                case LuaValue.G_SURVIVAL:
                    pt.gcColor = cw;  // Prototype 非 LuaValue，无 gclist
                    break;
                case LuaValue.G_TOUCHED1:
                    pt.gcAge = LuaValue.G_TOUCHED2;
                    pt.gcColor = LuaGC.BLACK;
                    break;
                case LuaValue.G_TOUCHED2:
                    pt.gcAge = LuaValue.G_OLD;
                    pt.gcColor = LuaGC.BLACK;
                    break;
            }
        }
        LuaString.resetColorsAfterFullGC(g);
    }

    // lgc.c: sweep  -  luaM_free_ dead tables
    // java diff: 用 LuaGC.isdead 检查对象是否来自上一轮的 white
    public static void sweepDeadTables(Globals g) {
        byte cw = LuaGC.isWhite(g);
        boolean inc = LuaGC.isIncrementalMode(g);
        g.gc.allTables.removeIf(table -> {
            if (LuaGC.isdead(g, table.gcColor) && !table.m_finalizerRegistered) {
                // 被扫的表若仍被主线程栈引用，则是"可达却被清扫" - 记录为违规
                LuaGC.free(g, table.currentStorageBytes());
                LuaGC.markObjectsSwept(g);  // java-only: 动态阈值跟踪
                return true;
            }
            boolean isWhite = LuaGC.iswhite(table.gcColor);
            if (inc || isWhite) {
                if (!isWhite) {
                    table.makeWhite(cw);
                }
                // java diff: fullGC 模式下此处设 gcAge=G_OLD（对齐 C 的 sweep2old lgc.c），
                // 消除 agesAfterFullGC 对 allTables 的独立 O(n) 遍历
                table.gcAge = (byte) (inc ? LuaValue.G_NEW : LuaValue.G_OLD);
            } else {
                // java-only: fullGC 模式，BLACK 表 - 置为 cw + G_OLD
                table.makeWhite(cw);
                table.gcAge = LuaValue.G_OLD;
            }
            return false;
        });
    }

    // lgc.c: sweepgen  -  G_NEW->G_SURVIVAL|white；其余前进 age，保持颜色
    public static void sweepGen(Globals g, byte cw) {
        g.gc.allTables.removeIf(table -> {
            if (LuaGC.isdead(g, table.gcColor) && !table.m_finalizerRegistered) {
                LuaGC.free(g, table.currentStorageBytes());
                LuaGC.markObjectsSwept(g);  // java-only: 动态阈值跟踪
                return true;
            }
            if (table.gcAge == LuaValue.G_NEW) {
                table.makeWhite(cw);
                table.gcAge = LuaValue.G_SURVIVAL;
            } else {
                switch (table.gcAge) {
                    case LuaValue.G_SURVIVAL:
                        table.gcAge = LuaValue.G_OLD1;
                        break;
                    case LuaValue.G_OLD0:
                        table.gcAge = LuaValue.G_OLD1;
                        break;
                    case LuaValue.G_OLD1:
                        table.gcAge = LuaValue.G_OLD;
                        break;
                }
            }
            return false;
        });
    }

    // lgc.c: markold  -  把 OLD1 对象标记为 OLD
    // java diff: C 用每列表指针，Java 遍历全局集合
    public static void markOld(Globals g) {
        LuaGC.GrayList grayLocal = new LuaGC.GrayList();
        for (int _gi = 0, _gn = g.gc.allTables.size(); _gi < _gn; _gi++) {
            LuaTable table = g.gc.allTables.get(_gi);
            if (table.gcAge == LuaValue.G_OLD1) {
                table.gcAge = LuaValue.G_OLD;
                if (LuaGC.isblack(table.gcColor)) {
                    table.gcColor = LuaGC.GRAY;
                    grayLocal.push(table);
                }
            }
        }
        for (int _gi = 0, _gn = g.gc.allUserdata.size(); _gi < _gn; _gi++) {
            LuaUserdata ud = g.gc.allUserdata.get(_gi);
            if (ud.gcAge == LuaValue.G_OLD1) {
                ud.gcAge = LuaValue.G_OLD;
                if (LuaGC.isblack(ud.gcColor)) {
                    ud.gcColor = LuaGC.GRAY;
                    grayLocal.push(ud);
                }
            }
        }
        for (int _gi = 0, _gn = g.gc.allThreads.size(); _gi < _gn; _gi++) {
            LuaThread thread = g.gc.allThreads.get(_gi);
            if (thread.gcAge == LuaValue.G_OLD1) {
                thread.gcAge = LuaValue.G_OLD;
                if (LuaGC.isblack(thread.gcColor)) {
                    thread.gcColor = LuaGC.GRAY;
                    grayLocal.push(thread);
                }
            }
        }
        for (int _gi = 0, _gn = g.gc.allClosures.size(); _gi < _gn; _gi++) {
            LuaClosure cl = g.gc.allClosures.get(_gi);
            if (cl.gcAge == LuaValue.G_OLD1) {
                cl.gcAge = LuaValue.G_OLD;
                if (LuaGC.isblack(cl.gcColor)) {
                    cl.gcColor = LuaGC.GRAY;
                    grayLocal.push(cl);
                }
            }
        }
        for (int _gi = 0, _gn = g.gc.allProtos.size(); _gi < _gn; _gi++) {
            Prototype pt = g.gc.allProtos.get(_gi);
            if (pt.gcAge == LuaValue.G_OLD1) {
                pt.gcAge = LuaValue.G_OLD;
                // Prototype 非 LuaValue；其引用经 LuaClosure.propagateOne 标记
            }
        }
        if (!grayLocal.isEmpty()) {
            propagateGray(g, grayLocal);
        }
    }

    // lgc.c: correctgraylists + correctgraylist
    // TOUCHED1->TOUCHED2+BLACK；TOUCHED2->OLD+BLACK；thread 保持 gray
    // java diff: C 用每对象 gclist 链表，Java 遍历全局集合
    public static void correctGrayLists(Globals g) {
        for (int _gi = 0, _gn = g.gc.allTables.size(); _gi < _gn; _gi++) {
            LuaTable table = g.gc.allTables.get(_gi);
            if (table.gcAge == LuaValue.G_TOUCHED1) {
                table.gcAge = LuaValue.G_TOUCHED2;
                table.gcColor = LuaGC.BLACK;
            } else if (table.gcAge == LuaValue.G_TOUCHED2) {
                table.gcAge = LuaValue.G_OLD;
                table.gcColor = LuaGC.BLACK;
            }
        }
        for (int _gi = 0, _gn = g.gc.allUserdata.size(); _gi < _gn; _gi++) {
            LuaUserdata ud = g.gc.allUserdata.get(_gi);
            if (ud.gcAge == LuaValue.G_TOUCHED1) {
                ud.gcAge = LuaValue.G_TOUCHED2;
                ud.gcColor = LuaGC.BLACK;
            } else if (ud.gcAge == LuaValue.G_TOUCHED2) {
                ud.gcAge = LuaValue.G_OLD;
                ud.gcColor = LuaGC.BLACK;
            }
        }
        for (int _gi = 0, _gn = g.gc.allThreads.size(); _gi < _gn; _gi++) {
            LuaThread thread = g.gc.allThreads.get(_gi);
            if (thread.gcAge == LuaValue.G_TOUCHED1) {
                thread.gcAge = LuaValue.G_TOUCHED2;
                // lgc.c: correctgraylist  -  灰色 thread 保持灰色（留在 grayagain）
            } else if (thread.gcAge == LuaValue.G_TOUCHED2) {
                thread.gcAge = LuaValue.G_OLD;
                thread.gcColor = LuaGC.BLACK;
            }
        }
        for (int _gi = 0, _gn = g.gc.allClosures.size(); _gi < _gn; _gi++) {
            LuaClosure cl = g.gc.allClosures.get(_gi);
            if (cl.gcAge == LuaValue.G_TOUCHED1) {
                cl.gcAge = LuaValue.G_TOUCHED2;
                cl.gcColor = LuaGC.BLACK;
            } else if (cl.gcAge == LuaValue.G_TOUCHED2) {
                cl.gcAge = LuaValue.G_OLD;
                cl.gcColor = LuaGC.BLACK;
            }
        }
        for (int _gi = 0, _gn = g.gc.allProtos.size(); _gi < _gn; _gi++) {
            Prototype pt = g.gc.allProtos.get(_gi);
            if (pt.gcAge == LuaValue.G_TOUCHED1) {
                pt.gcAge = LuaValue.G_TOUCHED2;
                pt.gcColor = LuaGC.BLACK;
            } else if (pt.gcAge == LuaValue.G_TOUCHED2) {
                pt.gcAge = LuaValue.G_OLD;
                pt.gcColor = LuaGC.BLACK;
            }
        }
    }

    // lgc.c: reallymarkobject / lgc.h: markvalue  -  标记白色可收集对象
    // java diff: C 对字符串直接 set2black + GCmarked += objsize；Java 长串置 BLACK +
    //   gcSize 不入灰列表、短串由 fixedgc 固定黑跳过 - 否则非 pinned 长串被误回收
    static void markValue(Globals g, LuaValue value, LuaGC.GrayList gray) {
        if (g == null || value == null) return;
        // lgc.c: markobject 仅标记可回收对象（table/function/userdata/thread）。
        // java diff: Java LuaFunction 与长串为降低对象开销不一定带 BIT_ISCOLLECTABLE，
        //   故仍按低 nibble 分派；但 JavaLib.Package 仅借用 userdata 标签提供包访问，
        //   既不是 LuaUserdata 也不进 GC 对象链，必须在压入灰链前排除。
        int t = value.tt_ & 0x0F;
        if (t < TTABLE) {
            if (value.tt_ == LuaValue.LUA_VLNGSTR) markStringValue(g, (LuaString) value);
            return;
        }
        if (t == TUSERDATA && !(value instanceof LuaUserdata)) return;
        if (!LuaGC.iswhite(value.gcColor)) return;
        value.gcColor = 2;  // GRAY
        gray.push(value);
    }

    // Java：从 markValue 抽出的长串标记慢路径，保持 markValue 体量小以利 JIT 内联。
    // C：lgc.c : reallymarkobject。
    // lgc.c: reallymarkobject 对字符串 set2black（无子对象，不入灰链）
    private static void markStringValue(Globals g, LuaString s) {
        if (!LuaGC.iswhite(s.gcColor)) return;
        s.gcColor = LuaGC.BLACK;
        g.gc.GCmarked += s.gcSize();
    }

    // lgc.c: restartcollection  -  标记根（thread、registry、metatables、finalizers）
    // java diff: C uses g->gray linked list; Java uses LuaGC.gray GrayList (侵入式链表)
    static void markRoots(Globals g, LuaGC.GrayList gray) {
        if (g != null) {
            markValue(g, g, gray);
            markValue(g, g.registry, gray);
            markValue(g, g.running, gray);
            markValue(g, g.STDIN, gray);
            markValue(g, g.STDOUT, gray);
            // lgc.c: markobject(g, mainthread(g))  -  C 恒标记主线程（Java 缓存于 LuaThread.mainThread）；
            //   同时标记 normal 态线程（其栈含 g.running 不可达的协程引用）
            LuaThread mt = g.mainThread;
            if (mt != null) markValue(g, mt, gray);
            for (int _ti = 0, _tn = g.gc.allThreads.size(); _ti < _tn; _ti++) {
                LuaThread t = g.gc.allThreads.get(_ti);
                if (t.isNormal) markValue(g, t, gray);
            }
        }
        LuaString.markInternedStrings(g, gray);
    }


    // lgc.c: propagateall  -  传播所有灰色对象至灰列表为空
    static void propagateGray(Globals g, LuaGC.GrayList gray) {
        int pops = 0;
        boolean sawG = false;
        while (!gray.isEmpty()) {
            LuaValue value = gray.pop();
            value.gcColor = 3;  // BLACK  -  fully propagated
            pops++;
            if (value instanceof Globals) sawG = true;
            propagateOne(g, value, gray);
        }
    }

    // lgc.c: convergeephemerons  -  反复遍历 g->ephemeron 链至无新标记。
    // 每轮取出整条链并清空（表在 traverseephemeron 中会重新入链），方向逐轮反转。
    static void convergeEphemeron(Globals g, LuaGC.GrayList gray) {
        boolean changed;
        boolean dir = false;
        do {
            // lgc.c: next = g->ephemeron; g->ephemeron = NULL  -  detach 仅摘表头，
            // 各节点 gclist 保持完整，供本轮逐个取出处理。
            LuaValue next = g.gc.ephemeron.detach();
            changed = false;
            while (next != null) {
                LuaTable h = (LuaTable) next;
                next = h.gclist;
                h.gclist = null;
                // lgc.c: nw2black(h)  -  暂时移出链
                h.gcColor = LuaGC.BLACK;
                if (traverseEphemeron(g, h, gray, dir)) {
                    propagateGray(g, gray);
                    changed = true;
                }
            }
            dir = !dir;
        } while (changed);
    }

    // lgc.c: clearbykeys  -  清除键未被标记的条目；空条目一律 clearkey，
    // 使其键不再被 GC 追踪（否则字符串键每轮都被 iscleared 标黑，永不回收）。
    static void clearByKeys(Globals g, LuaValue list) {
        for (LuaValue w = list; w != null; w = w.gclist) {
            LuaTable h = (LuaTable) w;
            for (int i = 0; i < h.node.length; i++) {
                Node n = h.node[i];
                if (n == null) continue;
                if (!n.empty() && iscleared(g, n.key)) {
                    n.nodeSetVal(LuaValue.NIL);
                }
                // lgc.c: if (isempty(gval(n))) clearkey(n);
                if (n.empty()) n.setdeadkey();
            }
        }
    }

    // lgc.c: clearbyvalues  -  清除链上各表中值未被标记的条目；到 f 为止（不含）
    static void clearByValues(Globals g, LuaValue list, LuaValue f) {
        for (LuaValue w = list; w != f; w = w.gclist) {
            LuaTable h = (LuaTable) w;
            if (h.array_tags != null) {
                for (int i = 0; i < h.array_tags.length; i++) {
                    if (h.array_tags[i] != FlatArith.T_REF) continue;
                    LuaValue o = (LuaValue) h.array_refs[i];
                    if (iscleared(g, o)) {
                        clearArrSlot(h.array_tags, h.array_numVals, h.array_refs, i);
                        if (i + 1 == h.lenhint) {
                            while (h.lenhint > 0 && h.array_tags[h.lenhint - 1] == 0) h.lenhint--;
                        }
                    }
                }
            }
            for (int i = 0; i < h.node.length; i++) {
                Node n = h.node[i];
                if (n == null || n.empty()) continue;
                if (n.value_tag == FlatArith.T_REF && iscleared(g, (LuaValue) n.value_ref)) {
                    n.nodeSetVal(LuaValue.NIL);
                }
            }
        }
    }

    // lgc.c: propagatemark (单个灰对象)
    // java diff: switch(tt_ & 0x3F) 按类型+变体分派（对齐 C ttypetag，替代 instanceof 链）
    static void propagateOne(Globals g, LuaValue value, LuaGC.GrayList gray) {
        int tag = value.tt_ & 0x3F;  // java-only: mask BIT_ISCOLLECTABLE, keep variant (aligns C ttypetag)
        switch (tag) {
            case LUA_VLCL: {  // lgc.c: traverseLclosure  -  mark prototype + upvalues
                LuaClosure closure = (LuaClosure) value;
                // lgc.c: traverseproto  -  标记原型内容（source、k、p）
                // java diff: Prototype 非 LuaValue 不能入灰列表，内联标记其内容
                if (closure.p != null) {
                    if (LuaGC.iswhite(closure.p.gcColor)) {
                        closure.p.gcColor = 3; // BLACK
                        markValue(g, closure.p.source, gray);
                        if (closure.p.k != null) {
                            for (LuaValue v : closure.p.k) markValue(g, v, gray);
                        }
                        if (closure.p.p != null) {
                            for (Prototype sub : closure.p.p) {
                                // java diff: iswhite 守卫提升到调用点（对齐 C markvalue 宏）；
                                //   已 BLACK 子原型免方法调用（与 markLongStringValue 同模式）
                                if (sub != null && LuaGC.iswhite(sub.gcColor))
                                    markSubProto(g, sub, gray);
                            }
                        }
                    }
                }
                // lgc.c: traverseLclosure  -  标记上值（C 标记 UpVal；Java 直接标记值）
                if (closure.upvals != null) {
                    for (UpVal up : closure.upvals) {
                        if (up != null) markValue(g, up.get(), gray);
                    }
                }
                break;
            }
            case LUA_VLCF:   // lgc.c: light C function  -  fallthrough to gcRefs
            case LUA_VCCL: { // lgc.c: traverseCclosure  -  mark upvalues
                LuaFunction fn = (LuaFunction) value;
                LuaValue[] refs = fn.gcRefs();
                // java-only 结构性守卫：仅经 bindGlobals 登记进 allFunctions 者才被
                //   sweep/repropagate 复位颜色；未登记者首标后永久为黑，iswhite 短路使
                //   gcRefs() 里的对象漏标被回收。
                assert refs.length == 0 || fn.ownerGlobals != null
                        : "LuaFunction with gcRefs must be registered via bindGlobals: "
                        + fn.getClass().getName();
                for (LuaValue ref : refs) markValue(g, ref, gray);
                break;
            }
            case LUA_VTABLE: {  // lgc.c: traversetable  -  入链由各 traverse* 按 C 分派完成
                markTableContents(g, (LuaTable) value, gray);
                break;
            }
            case LUA_VUSERDATA: {  // lgc.c: traverseuserdata
                LuaUserdata userdata = (LuaUserdata) value;
                markValue(g, userdata.metatable, gray);
                LuaValue[] uv = userdata.uservaluesRaw();
                if (uv != null) for (LuaValue v : uv) markValue(g, v, gray);
                break;
            }
            case LUA_VTHREAD: {  // lgc.c: traversethread
                LuaThread thread = (LuaThread) value;
                // lgc.c: traversethread  -  若 isold 或处于 GCSpropagate，加入 grayagain，
                // 使线程在 atomic() 中重新遍历（propagate 期间栈可能变化）
                if (LuaGC.gcState(g) == LuaGC.GCSpropagate || thread.gcAge >= LuaThread.G_OLD1) {
                    g.gc.grayagain.push(thread);
                }

                markValue(g, thread.l_G, gray);
                markValue(g, thread.hook, gray);
                // java diff: func 是独立字段（栈数组不含）- 须显式 mark，否则协程入口闭包
                //   不可达 -> 捕获 upvalue 被回收 -> close 链 TBC 丢失（cstack.lua 根因）
                markValue(g, thread.func, gray);
                markThreadFrames(g, thread, gray);
                if (thread.errorStack != null) {
                    for (Globals.DebugFrame frame : thread.errorStack) {
                        markFrame(g, frame, gray);
                    }
                }
                // java diff: 以下均为 Java 独有字段，C 把对应值放在栈上（traversethread 的栈扫描
                //   自然覆盖），Java 存为独立字段故须显式标记，漏标会使仍被持有的对象被判死
                thread.markJavaOnlyRefs(g, gray);
                break;
            }
            default:
                break;  // java-only: shouldn't happen (markValue only marks collectable types)
        }
    }

    // lgc.c: traverseproto  -  标记原型内容；已标记则跳过
    private static void markSubProto(Globals g, Prototype p, LuaGC.GrayList gray) {
        if (!LuaGC.iswhite(p.gcColor)) return;
        p.gcColor = 3; // BLACK
        markValue(g, p.source, gray);
        if (p.k != null) {
            for (LuaValue v : p.k) markValue(g, v, gray);
        }
        if (p.p != null) {
            for (Prototype sub : p.p) {
                if (sub != null) markSubProto(g, sub, gray);
            }
        }
    }

    // lgc.c: traversethread  -  标记栈中的活跃元素
    // java diff: Java 的 thread.top 可能低于 ci.top（不能扩到 max(ci.top) - 其间可能有过期
    //   引用；C 靠 savestate 保证 GC 时 top==ci.top，Java 靠 resetAllColorsToWhite 补偿）
    private static void markThreadFrames(Globals g, LuaThread thread, LuaGC.GrayList gray) {
        if (thread == null || thread.stack == null) return;
        int top = thread.top;
        if (top > thread.stack.length) top = thread.stack.length;
        // java diff: 直接调用 markValue（`t < TTABLE` 已优化）
        for (int i = 0; i < top; i++) {
            if (thread.stack[i] != null) {
                markValue(g, thread.stack[i], gray);
            }
        }
        // lgc.c: traversethread  -  同时标记打开的上值
        if (thread.openupval != null) {
            for (UpVal uv : thread.openupval) {
                if (uv != null) markValue(g, uv.get(), gray);
            }
        }
        // lgc.c: traversethread  -  atomic 相位收缩栈 + 清死栈片段
        if (g.gc.gcstate == LuaGC.GCSatomic) {
            // C: if (!g->gcemergency) luaD_shrinkstack(th)
            //    -  紧急回收周期内不动栈（此时 realloc 可能再失败）
            if (!g.gc.gcemergency) {
                if (thread != g.running) {
                    // 非运行线程：数组未被任何 execute 帧缓存，可立刻换
                    LuaVM.shrinkStackForGc(thread);
                } else {
                    // java diff: 运行线程不能立刻换数组 - LuaVM.execute 把 stack 缓存在方法
                    //   局部量，checkGC 后不刷新，换掉会让解释器继续写旧数组；
                    //   故置待办位 pendingStackShrink，由 execute 在 startfunc 刷新点消费。
                    thread.pendingStackShrink = true;
                }
            }
            clearDeadStackSlice(thread, top);
        }
    }

    /**
     * lgc.c: traversethread 的 {@code setnilvalue2s} 循环 - 把 top 之上的槽置 null。
     *
     * <p>不清理会真泄漏：Lua GC 不再标记这些槽，但 Java 数组仍持强引用，JVM 无法回收。
     *
     * <p>java diff: C 有 savestate 保证 GC 时 {@code top == ci.top}；Java 的
     * {@code thread.top} 可能低于活跃帧的 {@code ci.top}（含 atomic 相位），从
     * {@code thread.top} 清会毁掉活跃帧仍要用的值 - 故下界取 {@code max(top, max(活跃 ci.top))}。
     */
    private static void clearDeadStackSlice(LuaThread thread, int top) {
        int lo = top;
        for (CallInfo ci = thread.ci; ci != null; ci = ci.previous) {
            if (ci.top > lo) lo = ci.top;
        }
        // C 清到 stack_last + EXTRA_STACK；Java 以数组实长为硬上界
        int hi = thread.stack_last + LuaThread.EXTRA_STACK;
        if (hi > thread.stack.length) hi = thread.stack.length;
        for (int i = lo; i < hi; i++) {
            thread.stack[i] = null;
        }
    }

    // lgc.c: traversethread (调试帧)
    // 包私有：LuaThread.markJavaOnlyRefs 需标记 pendingError.savedStack / closeSavedStack
    static void markDebugFrame(Globals g, Globals.DebugFrame frame, LuaGC.GrayList gray) {
        markFrame(g, frame, gray);
    }

    private static void markFrame(Globals g, Globals.DebugFrame frame, LuaGC.GrayList gray) {
        if (frame == null) return;
        markValue(g, frame.func, gray);
        int top = frame.top > 0 ? frame.top : (frame.stack != null ? frame.stack.length : 0);
        if (frame.stack != null) {
            int limit = Math.min(top, frame.stack.length);
            for (int i = 0; i < limit; i++) markValue(g, frame.stack[i], gray);
        }
    }

    // lgc.c: traversetable
    private static void markTableContents(Globals g, LuaTable table, LuaGC.GrayList gray) {
        markValue(g, table.metatable, gray);
        int mode = table.weakMode();
        if (mode == 0) {
            // lgc.c: traversestrongtable  -  无弱引用
            // java diff: 用 n.key_tt 跳过非可回收键（对齐 C markkey 宏，免解引用 n.key 的 JFR 热点）
            if (table.array_tags != null) {
                // java diff: 仅标记 T_REF 槽（table/function/userdata/thread），跳过整数/浮点/布尔。
                byte[] at = table.array_tags;
                Object[] ar = table.array_refs;
                for (int i = 0; i < at.length; i++) {
                    if (at[i] == FlatArith.T_REF) markValue(g, (LuaValue) ar[i], gray);
                }
            }
            int totalNodes = table.node != null ? table.node.length : 0;
            for (int i = 0; i < totalNodes; i++) {
                Node n = table.node[i];
                if (n == null || n.empty()) continue;
                if (GC_KEYTT) {
                    int ktt = n.key_tt;
                    if ((ktt & 0x0F) >= TTABLE || ktt == LuaValue.LUA_VLNGSTR)
                        markValue(g, n.key, gray);
                } else {
                    markValue(g, n.key, gray);
                }
                if (n.value_tag == FlatArith.T_REF) markValue(g, (LuaValue) n.value_ref, gray);
            }
            return;
        }
        boolean weakValues = (mode & 1) != 0;
        boolean weakKeys = (mode & 2) != 0;
        if (weakKeys && weakValues) {
            // lgc.c: traversetable case 3（全弱表） -  不遍历内容，仅按相位入链
            if (LuaGC.gcState(g) == LuaGC.GCSpropagate) g.gc.grayagain.push(table);
            else g.gc.allweak.push(table);
        } else if (weakValues) {
            traverseWeakValue(g, table, gray);
        } else {
            traverseEphemeron(g, table, gray, false);
        }
    }

    // lgc.c: traverseweakvalue  -  键强标记，含白值则入 g->weak 待 clearbyvalues
    private static void traverseWeakValue(Globals g, LuaTable table, LuaGC.GrayList gray) {
        // lgc.c: 有数组段时直接假定可能含白值（为此遍历不值得）
        boolean hasclears = table.array_tags != null && table.array_tags.length > 0;
        int totalNodes = table.node != null ? table.node.length : 0;
        for (int i = 0; i < totalNodes; i++) {
            Node n = table.node[i];
            if (n == null || n.empty()) continue;
            // lgc.c: markkey  -  键是强引用
            int ktt = n.key_tt;
            if ((ktt & 0x0F) >= TTABLE || ktt == LuaValue.LUA_VLNGSTR) markValue(g, n.key, gray);
            if (!hasclears && n.value_tag == FlatArith.T_REF
                    && iscleared(g, (LuaValue) n.value_ref)) {
                hasclears = true;
            }
        }
        if (LuaGC.gcState(g) == LuaGC.GCSpropagate) g.gc.grayagain.push(table);
        else if (hasclears) g.gc.weak.push(table);
    }

    // lgc.c: traverseephemeron  -  返回本次是否标记了对象（决定收敛是否继续）。
    // inv=true 时哈希段倒序遍历（对齐 convergeephemerons 的方向反转）。
    private static boolean traverseEphemeron(Globals g, LuaTable table, LuaGC.GrayList gray, boolean inv) {
        boolean hasclears = false;  // 含白键
        boolean hasww = false;      // 含"白键->白值"
        // lgc.c: traversearray  -  数组段（整数键不可回收）按强引用标记
        boolean marked = false;
        if (table.array_tags != null) {
            byte[] at = table.array_tags;
            Object[] ar = table.array_refs;
            for (int i = 0; i < at.length; i++) {
                if (at[i] != FlatArith.T_REF) continue;
                LuaValue o = (LuaValue) ar[i];
                if (isWeakCollectable(o) && LuaGC.iswhite(o.gcColor)) {
                    marked = true;
                    markValue(g, o, gray);
                }
            }
        }
        int nsize = table.node != null ? table.node.length : 0;
        for (int idx = 0; idx < nsize; idx++) {
            Node n = table.node[inv ? nsize - 1 - idx : idx];
            if (n == null || n.empty()) continue;
            if (iscleared(g, n.key)) {
                hasclears = true;
                if (valIsWhite(n)) hasww = true;
            } else if (valIsWhite(n)) {
                marked = true;
                markValue(g, (LuaValue) n.value_ref, gray);
            }
        }
        // lgc.c: 按相位/状态入相应链
        if (LuaGC.gcState(g) == LuaGC.GCSpropagate) g.gc.grayagain.push(table);
        else if (hasww) g.gc.ephemeron.push(table);
        else if (hasclears) g.gc.allweak.push(table);
        return marked;
    }

    // lgc.c: valiswhite(gval(n))
    private static boolean valIsWhite(Node n) {
        if (n.value_tag != FlatArith.T_REF) return false;
        LuaValue v = (LuaValue) n.value_ref;
        return isWeakCollectable(v) && LuaGC.iswhite(v.gcColor);
    }

    // lgc.c: iscleared  -  不可回收对象永不清除；字符串按值处理（标记后永不清除）
    private static boolean iscleared(Globals g, LuaValue o) {
        if (o == null) return false;
        if (o.isstring()) {
            // lgc.c: markobject(g, o)  -  字符串作为值，永不被弱引用清除
            if (o.tt_ == LuaValue.LUA_VLNGSTR) markStringValue(g, (LuaString) o);
            return false;
        }
        if (!isWeakCollectable(o)) return false;
        return LuaGC.iswhite(o.gcColor);
    }

    // lgc.h: iscollectable  -  判断是否弱可收集类型
    // java diff: C 含字符串，Java 排除（short strings 由 markInternedStrings 处理）
    private static boolean isWeakCollectable(LuaValue v) {
        if (v == null) return false;
        // lgc.c: iscollectable  -  直读 tt_ 低 nibble 避免虚派发（语义同 v.type()）
        int t = v.tt_ & 0x0F;
        return t == TTABLE || t == TFUNCTION || t == TTHREAD || t == TUSERDATA;
    }

    // hashGet; 键规范化：整数值的浮点数（含 -0.0）转为整数键
    private static LuaValue normalizeKey(LuaValue k) {
        if (k instanceof LuaFloat f) {
            double d = f.todouble();
            if (Double.isInfinite(d) || Double.isNaN(d)) return k;
            // lua_numbertointeger上界是排他的，MAXINTEGER可能无精确浮点表示
            if (d == Math.floor(d) && d >= (double) Long.MIN_VALUE && d < -(double) Long.MIN_VALUE) {
                return LuaInteger.valueOf((long) d);
            }
        }
        return k;
    }

    // ceilLog2; ceil(log2(k))，k>=1
    private static int ceilLog2(int k) {
        return 32 - Integer.numberOfLeadingZeros(k - 1);
    }

    // 从键值对创建表
    // java-only
    public static LuaTable of(LuaValue... kv) {
        LuaTable t = new LuaTable();
        for (int i = 0; i < kv.length - 1; i += 2) t.setEntry(kv[i], kv[i + 1]);
        return t;
    }

    // lgc.c: objsize  -  GC 记账的近似大小
    @Override
    public int gcSize() {
        return 128 + (array_tags != null ? array_tags.length * 16 : 0) + (node != null ? node.length * 32 : 0);
    }

    // ltable.c: sizenode
    int sizenode() {
        return 1 << lsizenode;
    }

    // lobject.h: Table.asize  -  LuaVM 内联快路径的包内可见
    int asize() {
        return array_tags != null ? array_tags.length : 0;
    }

    // java-only
    public int nodemask() {
        return sizenode() - 1;
    }

    // java-only: sweep 时内存记账的当前存储字节数
    public long currentStorageBytes() {
        int asize = array_tags != null ? array_tags.length : 0;
        int hsize = node != null ? node.length : 0;
        return tableStorageBytes(asize, Math.max(hsize, 1));
    }

    // ltable.c: hashpow2
    private int hashpow2(int h) {
        return lsizenode > 0 ? h & nodemask() : 0;
    }

    // ltable.c: hashmod
    private int hashmod(int h) {
        if (lsizenode == 0) return 0;
        int size = sizenode();
        return (h & 0x7FFFFFFF) % ((size - 1) | 1);
    }

    // ltable.c: mainpositionTV
    // java diff: C 用 switch(ttypetag(key))，Java 用 switch(tt_ & 0x3F) 标签分派
    //   (对齐 propagateOne tag dispatch，消除 instanceof 链)
    // [记录分叉 - 影响 next() 遍历顺序，Lua 语义合法]三处哈希实现与 C 不同源：
    //   ① 整数键：C 的 hashint 是 `i % ((size-1)|1)`（模奇数），Java 用 `i & nodemask()`
    //      （免除法；对 i < size-1 的小整数两法同值，分叉体现在负键/大键的分布上）；
    //      > INT_MAX 时 C 做 64 位 hashmod，Java 先高低 32 位异或折叠再取模。
    //   ② 浮点键：C 的 l_hashfloat 用 frexp 混合尾数与指数，Java 用 doubleToLongBits
    //      位折叠（见 hashFloat）。NaN/Inf → 0 与 C 一致。
    //   ③ 可收集键：C 的 hashpointer 用 point2uint(指针)，Java 用 identityHashCode。
    //   三者都只改「键落在哪个桶」，不改 == 判定与 border 计算，故 #t / next 的合法性不变，
    //   仅 next() 的枚举次序与 C 分叉（手册未规定次序）。
    private int mainposition(LuaValue k) {
        if (lsizenode == 0) return 0;
        switch (k.tt_ & 0x3F) {
            case LUA_VNUMINT: {  // ltable.c: hashint
                long v = k.tolong();
                if (v >= 0 && v <= Integer.MAX_VALUE)
                    return (int) (v & nodemask());
                return hashmod((int) (v ^ (v >>> 32)));
            }
            case LUA_VNUMFLT:  // ltable.c: hashmod(l_hashfloat)
                return hashmod(hashFloat(k.todouble()));
            case LUA_VSHRSTR:  // ltable.c: hashstr
            case LUA_VLNGSTR:  // ltable.c: hashpow2(luaS_hashlongstr)
                // java diff: hashCode() 对长字符串（LUA_VLNGSTR）惰性计算哈希，短字符串返回
                // 缓存哈希字段。不能直接用 .hash（长串未计算）
                return hashpow2(k.hashCode());
            case LUA_VTRUE:    // ltable.c: hashboolean(t, 1)
                return hashpow2(1);
            case LUA_VFALSE:   // ltable.c: hashboolean(t, 0)
                return hashpow2(0);
            default:           // ltable.c: hashpointer(t, o)
                // [next() 顺序分叉]C 用 point2uint(指针)，Java 用 identityHashCode。
                //   同族分叉见下方 hashFloat 与本 case 上方的 hashint 说明。
                return hashmod(System.identityHashCode(k));
        }
    }

    // getgeneric(deadok=0); 死键不匹配查找但链不断
    private int findindex(LuaValue key) {
        if (node == null || node.length == 0) return -1;
        int i = mainposition(key);
        while (i >= 0) {
            Node n = node[i];
            if (n == null) return -1;
            // keyeq: 死键不匹配任何查找
            if (!n.keyisdead() && n.key != null && n.key.raweq(key)) return i;
            // 链终止：真正未用的节点（value==null 且非死键）
            if (n.vempty() && !n.keyisdead()) return -1;
            i = n.next;
        }
        return -1;
    }

    // java-only: findindex 的 int 特化版本，免建 LuaInteger 对象
    private int findindexInt(int key) {
        if (node == null || node.length == 0) return -1;
        int i = mainpositionInt(key);
        while (i >= 0) {
            Node n = node[i];
            if (n == null) return -1;
            if (!n.keyisdead() && n.key instanceof LuaInteger ki && ki.toint() == key) return i;
            if (n.vempty() && !n.keyisdead()) return -1;
            i = n.next;
        }
        return -1;
    }

    // java-only: findindex 的 LuaString 特化版本，用引用相等比较 interned 字符串
    // java diff: 无 'n == null' 检查（所有槽位急切初始化）；node[] 提升为局部变量
    private int findindexShortStr(LuaString key) {
        Node[] nodes = this.node;
        if (nodes.length == 0) return -1;
        int i = lsizenode > 0 ? key.hash & nodemask() : 0;
        while (i >= 0) {
            Node n = nodes[i];
            if (!n.keyisdead() && n.key == key) return i;
            if (n.vempty() && !n.keyisdead()) return -1;
            i = n.next;
        }
        return -1;
    }

    // java-only: mainposition 的 int 特化版本（掩码语义同上，见 mainposition 的分叉注记）
    private int mainpositionInt(int key) {
        if (lsizenode == 0) return 0;
        long v = key;
        if (v >= 0 && v <= Integer.MAX_VALUE)
            return (int) (v & nodemask());
        return hashmod((int) (v ^ (v >>> 32)));
    }

    // getgeneric(deadok=1); next() 须找到已删除键的槽位才能继续遍历
    // java-only: 链查找失败时回退线性扫描活节点，防止链断裂致 'invalid key to next'
    // java diff: 无 'n == null' 检查（所有槽位急切初始化）；
    // node[] 提升为局部变量（链扫描与回退线性扫描都用）
    private int findindexDeadOk(LuaValue key) {
        Node[] nodes = this.node;
        if (nodes.length == 0) return -1;
        int i = mainposition(key);
        while (i >= 0) {
            Node n = nodes[i];
            // deadok=1：含死键一并匹配
            if (n.key != null && n.key.raweq(key)) return i;
            // 链终止：真正未用的节点
            if (n.vempty() && !n.keyisdead()) break;
            i = n.next;
        }
        // java-only: 链查找失败，回退线性扫描活节点
        for (int j = 0; j < nodes.length; j++) {
            Node n = nodes[j];
            if (n.value_tag != FlatArith.T_NIL && n.value_tag != T_NILVAL && n.key != null && n.key.raweq(key))
                return j;
        }
        return -1;
    }

    // ltable.c: getfreepos；仅认真正未用的槽位（keyisnil），不含死键
    // [记录分叉]C 有 LIMFORLAST 双模式：lsizenode >= LIMFORLAST 时用 lastfree 指针下扫，
    //   小表则每次自顶向下线性全扫（无 lastfree 字段）。Java 统一走 lastfree 下扫 ——
    //   小表 lastfree 初值 = size，首轮与 C 的线性扫描等价；但 lastfree 只减不回升，
    //   故「小表里上方槽位被释放后」C 能重新发现、Java 不能 ⇒ 空闲槽选择次序可与 C 分叉。
    //   只影响 next() 枚举次序（Lua 语义合法），不影响可达性。
    private int getfreepos() {
        while (lastfree > 0) {
            int i = --lastfree;
            Node n = node[i];
            // ltable.c: keyisnil(luaM_free_)  -  key_tt==LUA_TNIL，而非 LUA_TDEADKEY
            if (n == null) return i;
            if (n.key == null && !n.keyisdead()) return i;  // truly unused slot
        }
        return -1;
    }

    // java-only: 查找链中目标节点的前驱（C 用 gnext 相对偏移遍历）
    private int findPreviousInChain(int main, int target) {
        int i = main;
        while (i >= 0) {
            Node n = node[i];
            if (n == null) return -1;
            if (n.next == target) return i;
            // 链终止：真正未用的节点
            if (n.vempty() && !n.keyisdead()) return -1;
            i = n.next;
        }
        return -1;
    }

    @Override
    public int type() {
        return TTABLE;
    }

    @Override
    public String typeName() {
        return "table";
    }

    @Override
    public LuaTable checktable() {
        return this;
    }

    @Override
    public LuaValue getmetatable() {
        return metatable;
    }

    // ltable.c: invalidateTMcache
    public void invalidateTMcache() {
        flags = 0;
    }

    private boolean checknoTMnewindex() {
        if (metatable == null || !(metatable instanceof LuaTable mt)) return true;
        return (mt.flags & MASK_NEWINDEX) != 0;
    }

    // ltable.c: luaH_setmetatable
    @Override
    public LuaValue setmetatable(LuaValue mt) {
        if (ownerGlobals != null) bindValue(ownerGlobals, mt);
        // lgc.c: luaC_objbarrier  -  表为黑且元表为白时标记元表
        if (LuaGC.isblack(gcColor) && mt != null && mt.iscollectable() && LuaGC.iswhite(mt.gcColor)) {
            LuaGC.barrier(ownerGlobals, mt);
        }
        // lgc.c: luaC_barrier_  -  old object referencing new metatable -> metatable becomes OLD0
        if (gcAge >= LuaValue.G_OLD && mt != null && mt.iscollectable() && mt.gcAge == LuaValue.G_NEW) {
            mt.gcAge = LuaValue.G_OLD0;
        }
        metatable = mt;
        flags = 0;
        registerFinalizer(mt);
        return this;
    }

    // lgc.c: luaC_checkfinalizer
    private void registerFinalizer(LuaValue mt) {
        if (m_finalizerRegistered) return;
        if (mt == null || !mt.istable()) return;
        LuaValue gc = mt.rawget(LuaValue.GC);
        if (gc == null || gc.isnil()) return;
        if (ownerGlobals == null) return;
        m_finalizerRegistered = true;
        ownerGlobals.gc.finobj.addFirst(new PendingFinalizer(this));

    }

    // lgc.c: getmode  -  gfasttm(g, h->metatable, TM_MODE)
    // java diff: 用 metatable->flags 位图缓存（位已设 -> __mode 不存在免 rawget）；
    //   __mode 修改路径 invalidateTMcache 清 flags
    private int weakMode() {
        LuaValue mt = metatable;
        if (mt == null || !(mt instanceof LuaTable t)) return 0;
        // ltm.h: gfasttm  -  if (cast_byte(mt->flags & (1<<TM_MODE))) return 0
        if ((t.flags & MASK_MODE) != 0) return 0;
        // flags 未设位 -> 查 __mode（首次查找或 invalidateTMcache 后）
        LuaValue mode = t.fastGetShortStr(LuaValue.MODE);
        if (!(mode instanceof LuaString s)) {
            // ltm.h: luaT_gettm  -  无 __mode -> 缓存此事实（设 flags 位）
            t.flags |= MASK_MODE;
            return 0;
        }
        // 有 __mode 字符串 -> 不缓存（每次重查，对齐 C gfasttm 对存在 tagmethod 不设位）
        int result = 0;
        if (s.containsByte('v')) result |= 1;
        if (s.containsByte('k')) result |= 2;
        return result;
    }

    // -- 原始读写 --

    // ltable.h: luaH_fastgeti  -  快速整数键查找（不建 LuaInteger），未命中返 null 走 finishget
    public final LuaValue fastGeti(int key) {
        int u = key - 1;
        if (u >= 0 && array_tags != null && u < array_tags.length) {
            if (array_tags[u] != 0) return farr2val(array_tags, array_numVals, array_refs, u);
        }
        // 内联哈希部分查找
        if (node != null && node.length > 0) {
            int i = findindexInt(key);
            if (i >= 0) {
                // java diff: 免 nodeGetVal 装箱 - live() 直接检查 value_tag，等价于
                // `v != null && !v.ttisnil()`（空槽/nil 值返回 null，命中才装箱返回）。
                Node n = node[i];
                if (n.live()) return n.nodeGetVal();
            }
        }
        return null;
    }

    // java-only: luaH_fastgeti 的哈希部分，供 VM 内联数组部分后回退
    public final LuaValue fastGetiHash(int key) {
        if (node != null && node.length > 0) {
            int i = findindexInt(key);
            if (i >= 0) {
                // java diff: 免 nodeGetVal 装箱 - live() 直接检查 value_tag（同 fastGeti）
                Node n = node[i];
                if (n.live()) return n.nodeGetVal();
            }
        }
        return null;
    }

    // java-only: FlatTFor 字段访问提升 - 循环内表是不变量、键是编译期常量 ⇒ Node 身份稳定，
    //   可环前解析一次、体内直接读写 node.value。前提：不新增/删除键、不写 nil、
    //   metatable==null（不触发 rehash/死键/元方法分叉）。返回命中的 live Node，否则 null
    public Node resolveFieldNode(LuaString key) {
        int idx = findindexShortStr(key);
        if (idx < 0) return null;
        Node n = node[idx];
        // java diff: 免 nodeGetVal 装箱 - live() 直接检查 value_tag，等价于 `v == null || v.ttisnil()`
        if (!n.live()) return null;   // 死键/空值 -> 不可提升
        return n;
    }

    // ltable.h: luaH_fastget (short string)  -  快速短串键查找（OP_GETFIELD/GETTABUP 用），
    //   内联 findindexShortStr，对齐 C 的 luaH_Hgetshortstr
    public final LuaValue fastGetShortStr(LuaString key) {
        if (node == null || node.length == 0) return null;
        int i = lsizenode > 0 ? key.hash & nodemask() : 0;
        while (i >= 0) {
            Node n = node[i];
            if (n == null) return null;
            if (!n.keyisdead() && n.key == key) {
                // java diff: 免 nodeGetVal 装箱 - live() 直接检查 value_tag，等价于
                // `(v != null && !v.ttisnil()) ? v : null`（空槽/nil 返回 null，命中才装箱）。
                if (!n.live()) return null;
                return n.nodeGetVal();
            }
            if (n.vempty() && !n.keyisdead()) return null;
            i = n.next;
        }
        return null;
    }

    // ltable.h: luaH_fastseti  -  快速整数键设置（对齐 C luaH_pset，仅设值不做 luaC_barrier）
    // 返回 hres: HOK / HNOTFOUND / HFIRSTNODE+idx(哈希) / ~u(数组)
    public final int fastSeti(int key, LuaValue val) {
        int u = key - 1;
        if (u >= 0 && array_tags != null && u < array_tags.length) {
            // ltable.h: checknoTM(h->metatable, TM_NEWINDEX) || !tagisempty(*tag)
            if (checknoTMnewindex() || array_tags[u] != 0) {
                // ltable.h: fval2arr  -  从 val->tt_ 设置标签
                if (val.isnil()) {
                    clearArrSlot(array_tags, array_numVals, array_refs, u);
                    if (key == lenhint) {
                        while (lenhint > 0 && array_tags[lenhint - 1] == 0)
                            lenhint--;
                    }
                } else {
                    fval2arr(array_tags, array_numVals, array_refs, u, val);
                    if (key > lenhint) lenhint = key;
                }
                return HOK;
            }
            // ltable.h: hres = ~cast_int(u)  -  数组槽位值为空且有元方法
            return ~u;
        }
        // 哈希部分  -  对齐 C 的 luaH_psetint: finishnodeset 仅设值不做 barrier
        if (node.length > 0) {
            int idx = findindexInt(key);
            if (idx >= 0) {
                // ltable.c: luaH_psetint  -  !ttisnil(slot) -> update value
                // java diff: live() 直接查 value_tag 免 nodeGetVal 装箱，等价于
                // `old != null && !old.ttisnil()`（old 仅用于 nil 检查，值本身不用）
                if (node[idx].live()) {
                    node[idx].nodeSetVal(val);
                    return HOK;
                }
                // ltable.c: luaH_psetint  -  ttisnil(slot) && !isabstkey -> retpsetcode
                // 死键情形：键存在但值为 nil
                if (!checknoTMnewindex()) return idx + HFIRSTNODE;
                if (val.isnil()) return HOK;
                node[idx].nodeSetVal(val);
                node[idx].key_tt = LuaValue.LUA_VNUMINT;  // java diff: store key type for GC mark skip
                node[idx].key = LuaInteger.valueOf(key);
                invalidateTMcache();
                return HOK;
            }
            // ltable.c: insertkey  -  absent key fast path
            if (checknoTMnewindex() && !val.isnil()) {
                int mpIndex = mainpositionInt(key);
                Node mp = node[mpIndex];
                if (mp.keyisdead()) {
                    mp.key_tt = LuaValue.LUA_VNUMINT;  // java diff: store key type for GC mark skip
                    mp.key = LuaInteger.valueOf(key);
                    mp.nodeSetVal(val);
                    invalidateTMcache();
                    return HOK;
                }
                if (mp.empty() && !mp.keyisdead()) {
                    // ltable.c isempty 含 T_NILVAL 墓碑（同 insertHash）；复用墓碑
                    // 时保留 next 链（C 的 isempty 分支不动 gnext），仅未用槽重置
                    mp.key_tt = LuaValue.LUA_VNUMINT;  // java diff: store key type for GC mark skip
                    mp.key = LuaInteger.valueOf(key);
                    mp.nodeSetVal(val);
                    if (mp.vempty()) mp.next = -1;
                    invalidateTMcache();
                    return HOK;
                }
            }
        }
        return HNOTFOUND;
    }

    // ltable.c: luaH_psetshortstr  -  快速短串键设置（对齐 C luaH_pset，仅设值不做 luaC_barrier）
    public final int fastSetShortStr(LuaString key, LuaValue val) {
        if (node.length > 0) {
            int idx = findindexShortStr(key);
            if (idx >= 0) {
                // ltable.c: luaH_psetshortstr  -  键已有值 -> 更新
                // java diff: live() 直接查 value_tag 免 nodeGetVal 装箱，等价于
                // `old != null && !old.ttisnil()`（old 仅用于 nil 检查，值本身不用）
                if (node[idx].live()) {
                    node[idx].nodeSetVal(val);
                    return HOK;
                }
                // ltable.c: luaH_psetshortstr  -  键存在但值为 nil（死键）
                // C: hres > 0 -> finishSet 直接更新值
                if (!checknoTMnewindex()) return idx + HFIRSTNODE;
                if (val.isnil()) return HOK;
                node[idx].nodeSetVal(val);
                node[idx].key_tt = (byte) key.tt_;  // java diff: store key type for GC mark skip
                node[idx].key = key;
                invalidateTMcache();
                return HOK;
            }
            // ltable.c: luaH_psetshortstr  -  absent key fast path
            if (checknoTMnewindex() && !val.isnil()) {
                // ltable.c: insertkey  -  short strings interned，黑表白键恒 false；
                // java diff: 仅处理主位死键（insertHash 不扫描链上死键）
                int mpIndex = mainposition(key);
                Node mp = node[mpIndex];
                if (mp.keyisdead()) {
                    // 主位置的死键 -> 复用（同 insertHash mpEmpty 路径）
                    mp.key_tt = (byte) key.tt_;  // java diff: store key type for GC mark skip
                    mp.key = key;
                    mp.nodeSetVal(val);
                    // 保留链（mp.next）
                    invalidateTMcache();
                    return HOK;
                }
                if (mp.empty() && !mp.keyisdead()) {
                    // 主位置的空槽位 -> 直接用（empty 含 T_NILVAL 墓碑，同 insertHash；
                    // 复用墓碑保留 next 链，仅未用槽重置）
                    mp.key_tt = (byte) key.tt_;  // java diff: store key type for GC mark skip
                    mp.key = key;
                    mp.nodeSetVal(val);
                    if (mp.vempty()) mp.next = -1;
                    invalidateTMcache();
                    return HOK;
                }
                // 主位置被活跃键占用 -> 交给 setEntry 处理
                // （若链中有死键，insertHash 可能产生重复）
            }
        }
        return HNOTFOUND;
    }

    // ltable.c: luaH_pset  -  任意键类型的通用 luaH_pset
    // java diff: C 用 switch(ttypetag(key))，Java 用 switch(tt_ & 0x3F) 标签分派
    //   (对齐 mainposition/propagateOne tag dispatch，消除 instanceof 链)
    public final int pset(LuaValue key, LuaValue val) {
        if (ownerGlobals != null) {
            bindValue(ownerGlobals, key);
            bindValue(ownerGlobals, val);
        }
        switch (key.tt_ & 0x3F) {
            case LUA_VSHRSTR:  // ltable.c: luaH_psetshortstr
            case LUA_VLNGSTR:  // java diff: C routes only SHRSTR; Java routes both (preserves existing behavior)
                return fastSetShortStr((LuaString) key, val);
            case LUA_VNUMINT: {  // ltable.c: psetint
                long kl = key.tolong();
                return (kl >= Integer.MIN_VALUE && kl <= Integer.MAX_VALUE) ? fastSeti((int) kl, val) : psetLong(kl, val);
            }
            case LUA_VNIL:  // ltable.c: HNOTFOUND
                return HNOTFOUND;
            case LUA_VNUMFLT: {  // ltable.c: float->int check, else fallthrough
                double d = key.todouble();
                if (!Double.isNaN(d) && !Double.isInfinite(d)) {
                    double v = Math.floor(d);
                    if (v == d && v >= Long.MIN_VALUE && v < 9223372036854775808.0) {
                        long k = (long) d;
                        if (k >= Integer.MIN_VALUE && k <= Integer.MAX_VALUE) {
                            return fastSeti((int) k, val);
                        }
                        return psetLong(k, val);
                    }
                }
                // FALLTHROUGH 到 default（ltable.c: luaH_pset）
                return finishnodesetGeneric(key, val);
            }
            default:  // ltable.c: finishnodeset(t, getgeneric(t, key, 0), val)
                return finishnodesetGeneric(key, val);
        }
    }

    // ltable.c: psetint  -  超出 int 范围的 long 键
    private int psetLong(long key, LuaValue val) {
        if (node.length > 0) {
            int idx = findindex(LuaInteger.valueOf(key));
            if (idx >= 0) {
                // java diff: live() 直接查 value_tag 免 nodeGetVal 装箱，等价于
                // `old != null && !old.ttisnil()`（old 仅用于 nil 检查，值本身不用）
                if (node[idx].live()) {
                    node[idx].nodeSetVal(val);
                    return HOK;
                }
                if (!checknoTMnewindex()) return idx + HFIRSTNODE;
                if (val.isnil()) return HOK;
                node[idx].nodeSetVal(val);
                node[idx].key_tt = LuaValue.LUA_VNUMINT;  // java diff: store key type for GC mark skip
                node[idx].key = LuaInteger.valueOf(key);
                invalidateTMcache();
                return HOK;
            }
        }
        return HNOTFOUND;
    }

    // ltable.c: finishnodeset  -  通用键
    // java diff: C 依赖 GC clearkey 释放死节点上的可收集键；Java 无 clearkey，故值为 nil 时
    // 须调 hashRemove 释放键
    private int finishnodesetGeneric(LuaValue key, LuaValue val) {
        if (node.length > 0) {
            int idx = findindex(key);
            if (idx >= 0) {
                // java diff: live() 直接查 value_tag 免 nodeGetVal 装箱，等价于
                // `old != null && !old.ttisnil()`（old 仅用于 nil 检查，值本身不用）
                if (node[idx].live()) {
                    if (val.isnil()) {
                        hashRemove(key);
                    } else {
                        node[idx].nodeSetVal(val);
                    }
                    return HOK;
                }
                return idx + HFIRSTNODE;
            }
        }
        return HNOTFOUND;
    }

    // ltable.c: luaH_get
    public final LuaValue hashGet(LuaValue k) {
        k = normalizeKey(k);
        if (k instanceof LuaInteger i) {
            long xLong = i.tolong();
            if (xLong >= 1 && array_tags != null && xLong <= Integer.MAX_VALUE && xLong <= array_tags.length) {
                int u = (int) xLong - 1;
                if (array_tags[u] == 0) return LuaValue.NIL;
                return farr2val(array_tags, array_numVals, array_refs, u);
            }
        } else if (k.isnil()) {
            return LuaValue.NIL;
        }
        int idx = findindex(k);
        if (idx < 0) return LuaValue.NIL;
        // java diff: 免 nodeGetVal 装箱 - live() 检查等价于 `v == null || v.ttisnil()`，
        // 空槽/nil 值直接返回 NIL，命中才装箱返回。
        Node n = node[idx];
        if (!n.live()) return LuaValue.NIL;
        return n.nodeGetVal();
    }

    // ltable.c: luaH_getint  -  按整数键读取而不创建 LuaInteger
    // java-only: C 直接用 lua_Integer 键；Java 避免 LuaInteger 装箱
    public final LuaValue getInt(int key) {
        int u = key - 1;
        if (u >= 0 && array_tags != null && u < array_tags.length) {
            if (array_tags[u] == 0) return LuaValue.NIL;  // ltable.c: tagisempty
            return farr2val(array_tags, array_numVals, array_refs, u);
        }
        // getintfromhash
        int idx = findindexInt(key);
        if (idx < 0) return LuaValue.NIL;
        // java diff: 免 nodeGetVal 装箱 - live() 检查同 hashGet（空槽/nil -> NIL）
        Node n = node[idx];
        if (!n.live()) return LuaValue.NIL;
        return n.nodeGetVal();
    }

    // ltable.c: luaH_getint  -  LuaIndex long 路径的 long 重载
    // java-only: 对落在 int 范围内的 long 键避免 LuaInteger 装箱
    public final LuaValue getInt(long key) {
        if (key >= Integer.MIN_VALUE && key <= Integer.MAX_VALUE) {
            return getInt((int) key);
        }
        return hashGet(LuaInteger.valueOf(key));
    }

    // ltable.c: luaH_setint  -  直接设置整数键
    // java diff: 用 byte[] array_tags 而非 LuaValue[] array_keys；数组键无 LuaInteger 分配
    public final void setInt(int key, LuaValue v) {
        if (LuaGC.isblack(gcColor) && isWeakCollectable(v) && LuaGC.iswhite(v.gcColor)) {
            gcColor = 2;
            ownerGlobals.gc.grayagain.push(this);
            LuaGC.setNeedRepropagate(ownerGlobals);  // java-only: table became non-white
        }
        if (gcAge >= LuaValue.G_OLD && v.iscollectable() && v.gcAge == LuaValue.G_NEW) {
            gcAge = LuaValue.G_TOUCHED1;
            gcColor = 2;
            ownerGlobals.gc.grayagain.push(this);
            LuaGC.setNeedRepropagate(ownerGlobals);  // java-only: table became non-white
        }
        int u = key - 1;
        if (u >= 0 && array_tags != null && u < array_tags.length) {
            if (v.isnil()) {
                clearArrSlot(array_tags, array_numVals, array_refs, u);  // ltable.h: fval2arr sets tag to LUA_VNIL, tagisempty=true
                if (key == lenhint) {
                    while (lenhint > 0 && array_tags[lenhint - 1] == 0)
                        lenhint--;
                }
            } else {
                fval2arr(array_tags, array_numVals, array_refs, u, v);  // ltable.h: fval2arr sets tag from val->tt_, !tagisempty
                if (key > lenhint) lenhint = key;
            }
            return;
        }
        if (node.length > 0) {
            int idx = findindexInt(key);
            if (idx >= 0) {
                if (v.isnil()) {
                    hashRemove(LuaInteger.valueOf(key));
                } else {
                    node[idx].nodeSetVal(v);
                }
                return;
            }
        }
        if (v.isnil()) return;
        setEntry(LuaInteger.valueOf(key), v);
    }

    @Override
    public LuaValue rawget(LuaValue k) {
        return hashGet(k);
    }

    // ltable.c: luaH_finishset  -  用 hres 编码完成原始 set table
    // java diff: C 用 TValue* 槽；Java 用 hres 定位。C 用 actk 提前转换 float->int 键，
    //   Java 在 setEntry->normalizeKey 统一规范化，语义等价
    public void finishSet(LuaValue key, LuaValue val, int hres) {
        // ltable.c: hres == HNOTFOUND
        if (hres == HNOTFOUND) {
            if (key.isnil()) LuaErrors.runErrorWithInfo("table index is nil");
            if (key instanceof LuaFloat f) {
                double d = f.todouble();
                if (Double.isNaN(d)) LuaErrors.runErrorWithInfo("table index is NaN");
            }
            newKey(key, val);
        }
        // ltable.c: hres > 0 -> regular Node
        else if (hres > 0) {
            node[hres - HFIRSTNODE].nodeSetVal(val);
        }
        // ltable.c: hres < 0 -> array entry
        else {
            int u = ~hres;
            if (val.isnil()) {
                clearArrSlot(array_tags, array_numVals, array_refs, u);
                int k = u + 1;
                if (k == lenhint) {
                    while (lenhint > 0 && array_tags[lenhint - 1] == 0)
                        lenhint--;
                }
            } else {
                fval2arr(array_tags, array_numVals, array_refs, u, val);
                if (u + 1 > lenhint) lenhint = u + 1;
            }
        }
    }

    // ltable.c: luaH_newkey  -  插入新键（luaH_finishset 遇 HNOTFOUND 时调用）
    // java diff: C 的 newKey 做 rehash + insert；Java 委托给 setEntry
    private void newKey(LuaValue key, LuaValue val) {
        setEntry(key, val);
    }

    // ltable.c: luaH_set
    public final void setEntry(LuaValue k, LuaValue v) {
        if (ownerGlobals != null) {
            bindValue(ownerGlobals, k);
            bindValue(ownerGlobals, v);
        }
        k = normalizeKey(k);
        // lgc.c: luaC_barrierback  -  黑表引用白色可收集对象 -> 链入 grayagain
        // java diff: 长串经库/rawset 路径存入黑表同样需要屏障（对齐
        // LuaGC.barrierback 的 tt_==LUA_VLNGSTR 特判；short strings pinned 跳过）
        if (LuaGC.isblack(gcColor)
                && (isWeakCollectable(v) || v.tt_ == LuaValue.LUA_VLNGSTR)
                && LuaGC.iswhite(v.gcColor)) {
            gcColor = 2;  // GRAY
            ownerGlobals.gc.grayagain.push(this);
            LuaGC.setNeedRepropagate(ownerGlobals);  // java-only: table became non-white
        }
        // lgc.c: luaC_barrierback  -  旧表引用新值 -> touched1，链入 grayagain
        if (gcAge >= LuaValue.G_OLD && v.iscollectable() && v.gcAge == LuaValue.G_NEW) {
            gcAge = LuaValue.G_TOUCHED1;
            gcColor = 2;  // GRAY
            ownerGlobals.gc.grayagain.push(this);
            LuaGC.setNeedRepropagate(ownerGlobals);  // java-only: table became non-white
        }
        if (k instanceof LuaInteger i) {
            long xLong = i.tolong();
            // setEntry: 数组部分容量内的键走数组；大整数键走哈希；数组不在单个插入时增长，只在rehash时增长
            if (xLong >= 1 && xLong <= Integer.MAX_VALUE) {
                int x = (int) xLong;
                if (array_tags != null && x <= array_tags.length) {
                    if (v.isnil()) {
                        clearArrSlot(array_tags, array_numVals, array_refs, x - 1);
                        if (x == lenhint) {
                            while (lenhint > 0 && array_tags[lenhint - 1] == 0)
                                lenhint--;
                        }
                    } else {
                        fval2arr(array_tags, array_numVals, array_refs, x - 1, v);
                        if (x > lenhint) lenhint = x;
                    }
                    return;
                }
            }
        }
        if (k.isnil()) LuaErrors.runErrorWithInfo("table index is nil");
        if (k instanceof LuaFloat f) {
            double d = f.todouble();
            if (Double.isNaN(d)) LuaErrors.runErrorWithInfo("table index is NaN");
        }
        // t[k]=nil 删除键
        if (v.isnil()) {
            hashRemove(k);
            return;
        }
        int existing = findindex(k);
        if (existing >= 0) {
            node[existing].nodeSetVal(v);
            return;
        }
        if (!insertHash(k, v)) {
            rehash(k);
            setEntry(k, v);
        }
        // ltable.c: luaH_newkey  -  新键插入对键做 backward barrier
        //（黑表存白色新键，增量传播中段键漏标会被 sweep；含长串，同上）
        if (ownerGlobals != null) {
            LuaGC.barrierback(ownerGlobals, this, k);
        }
        invalidateTMcache();  // 新键插入，可能引入新元方法
    }

    @Override
    public void rawset(LuaValue k, LuaValue v) {
        setEntry(k, v);
    }

    // ltable.c: lua_cleartable（Lua 5.4+） - 清空数组+哈希节点，保留元表/容量
    //   java-only: 供 table.clear 使用
    public void clearTable() {
        lenhint = 0;
        borderHint = 0;   // 内容清空 ⇒ 长度提示与 border 缓存一并失效（与 clear() 同）
        if (array_tags != null) Arrays.fill(array_tags, (byte) 0);
        if (array_numVals != null) Arrays.fill(array_numVals, 0L);
        if (array_refs != null) Arrays.fill(array_refs, null);
        if (node != null) {
            for (int i = 0; i < node.length; i++) {
                Node n = node[i];
                n.key = null;
                n.key_tt = 0;
                n.value_tag = FlatArith.T_NIL;
                n.value_num = 0;
                n.value_ref = null;
                n.next = -1;
            }
        }
    }

    // insertkey/newKey; 按主位置插入，必要时搬迁不在主位的旧节点
    // ltable.c: isdummy -> insertkey returns 0 -> triggers rehash
    private boolean insertHash(LuaValue k, LuaValue v) {
        if (node.length == 0) return false;  // ltable.c: isdummy check in insertkey
        int mpIndex = mainposition(k);
        Node mp = node[mpIndex];  // ltable.c: direct access (all nodes pre-initialized)
        // ltable.c: insertkey  -  !isempty(gval(mp)) || isdummy(t)
        // java diff: 用 empty() 或 keyisdead() 判定（C 的 clearkey 置空）。
        // empty() 含 T_NILVAL 墓碑（键在、值显式 nil），墓碑槽可复用主位置，
        // 避免删光再插时过早 rehash
        boolean mpEmpty = (mp.empty()) || mp.keyisdead();
        if (mpEmpty) {
            // ltable.c: 在主位置直接设置键/值
            boolean wasUnused = mp.vempty() && !mp.keyisdead();
            mp.key_tt = (byte) k.tt_;  // java diff: store key type for GC mark skip
            mp.key = k;
            mp.nodeSetVal(v);
            if (wasUnused) mp.next = -1;  // truly new node, no chain
            // java diff: 覆写死键时保留现有链（next）
            // C 的 clearkey 不触碰 next；Java 的 hashRemove 设 value=null 但保留 next

            return true;
        }

        int freeIndex = getfreepos();
        if (freeIndex < 0) return false;
        Node free = node[freeIndex];  // direct access
        free.key_tt = 0;  // will be overwritten below (moved key or new key)
        int otherMain = mp.key != null ? mainposition(mp.key) : mpIndex;
        if (otherMain != mpIndex) {
            int previous = findPreviousInChain(otherMain, mpIndex);
            if (previous >= 0) node[previous].next = freeIndex;
            free.key = mp.key;
            free.key_tt = mp.key_tt;  // java diff: copy key type tag for GC mark skip
            free.value_tag = mp.value_tag;
            free.value_num = mp.value_num;
            free.value_ref = mp.value_ref;
            free.next = mp.next;
            mp.key = k;
            mp.nodeSetVal(v);
            mp.key_tt = (byte) k.tt_;  // java diff: store key type for GC mark skip
            mp.next = -1;
            return true;
        }

        free.key = k;
        free.key_tt = (byte) k.tt_;  // java diff: store key type for GC mark skip
        free.nodeSetVal(v);
        free.next = mp.next;
        mp.next = freeIndex;
        return true;
    }

    // clearkey; t[k]=nil时标记为死键
    // ltable.c: clearkey 设 deadkey + setempty(gval)；Java: setempty = value=null
    private void hashRemove(LuaValue k) {
        int idx = findindex(k);
        if (idx >= 0) {
            node[idx].setdeadkey();
            node[idx].nodeClear();  // ltable.c: setempty(gval(n))

        }
    }

    // rehash; 重新计算最优数组/哈希大小并调整
    private void rehash(LuaValue newKey) {
        int[] nums = new int[32];
        int totalNa = 0;    // 数组可容纳的整数键总数
        int totalCount = 1;  // 键总数（从 1 开始，因为正在插入新键）
        int deleted = 0;

        // countint(ek)
        if (newKey instanceof LuaInteger li) {
            long x = li.tolong();
            if (x >= 1 && x <= Integer.MAX_VALUE) {
                nums[ceilLog2((int) x)]++;
                totalNa++;
            }
        }

        // ltable.c: numusehash
        for (int i = 0; i < node.length; i++) {
            Node n = node[i];
            if (n == null) continue;
            if (n.live()) {
                totalCount++;
                if (n.key instanceof LuaInteger li) {
                    long x = li.tolong();
                    if (x >= 1 && x <= Integer.MAX_VALUE) {
                        nums[ceilLog2((int) x)]++;
                        totalNa++;
                    }
                }
            } else if (!n.vempty() || n.keyisdead()) {
                // ltable.c: !isempty(gval(n)) 但非活跃 -> 已删除条目
                // 死键：value=null（setempty）但 keyisdead=true
                deleted = 1;
            }
            // 否则：真正空槽位（value=null，非死键） - 跳过
        }


        // ltable.c: ；若 ct.na==0，保持数组大小不变
        int asize;
        if (totalNa == 0) {
            asize = array_tags != null ? array_tags.length : 0;
        } else {
            // ltable.c: numusearray  -  用 arraykeyisempty（标签检查）
            if (array_tags != null) {
                for (int i = 0; i < array_tags.length; i++) {
                    if (array_tags[i] != 0) {  // ltable.c: !arraykeyisempty
                        totalCount++;
                        nums[ceilLog2(i + 1)]++;
                        totalNa++;
                    }
                }
            }

            // computesizes: 找最大的2的幂使数组至少1/3满 (arrayXhash: twotoi <= a*3)
            asize = 0;
            int na = 0;
            int a = 0;
            int twotoi = 1;
            for (int i = 0; i < 32 && twotoi > 0 && twotoi <= totalNa * 3; i++, twotoi *= 2) {
                a += nums[i];
                if (nums[i] > 0 && twotoi <= a * 3) {
                    asize = twotoi;
                    na = a;
                }
            }
            // nsize = ct.total - ct.na; ltable.c
            int nsize = totalCount - na;
            if (deleted > 0) nsize += nsize >> 2;  // ltable.c: ; 有删除时多留空间

            resize(asize, nsize);
            return;
        }

        // ltable.c: ；nsize = ct.total - ct.na（保留数组时 na=0）
        int nsize = totalCount;  // totalCount - 0
        if (deleted > 0) nsize += nsize >> 2;

        resize(asize, nsize);
    }

    // ltable.c: luaH_resizearray；调整数组部分，保持哈希部分大小
    public void resizeArray(int newAsize) {
        int nsize = node.length;  // ltable.c: allocsizenode
        resize(newAsize, nsize);
    }


    // ltable.c: luaH_resize
    private void resize(int newAsize, int newHsize) {
        byte[] oldAt = array_tags;
        long[] oldNumVals = array_numVals;
        Object[] oldRefs = array_refs;
        int oldAsize = oldAt != null ? oldAt.length : 0;
        Node[] oldHash = node;
        int oldSizenode = oldHash.length;  // ltable.c: allocsizenode; 0 for dummy
        long oldArrayBytes = arrayBytes(oldAsize);
        long oldHashBytes = hashBytes(Math.max(oldSizenode, 1));  // java diff: match constructor accounting (tableStorageBytes uses max(hsize,1))

        // ltable.c: setnodevector；nhsize=0 -> dummynode（Java：空数组）
        newHsize = nextPow2(newHsize);
        long newHashBytes = hashBytes(Math.max(newHsize, 1));  // java diff: always account at least 1 (dummy node)
        if (newHsize > 0 && ownerGlobals != null) LuaGC.checkMemory(ownerGlobals, newHashBytes);
        Node[] newNode = newHsize > 0 ? new Node[newHsize] : new Node[0];
        // java diff: always commit（含 dummy node 记账）；走带状态重载完成真实记账，
        // 无状态版 commitRealloc(long,long) 是空存根
        if (ownerGlobals != null) LuaGC.commitRealloc(ownerGlobals, oldHashBytes, newHashBytes);

        byte[] newAt = null;
        long[] newNumVals = null;
        Object[] newRefs = null;
        long newArrayBytes = arrayBytes(newAsize);
        long arrayDelta = newArrayBytes - oldArrayBytes;
        try {
            if (arrayDelta > 0 && ownerGlobals != null) LuaGC.checkMemory(ownerGlobals, arrayDelta);
            if (newAsize > 0) {
                // java diff: 三数组默认值(byte=0=T_NIL, long=0, Object=null)即空槽，无需 Arrays.fill
                newAt = new byte[newAsize];
                newNumVals = new long[newAsize];
                newRefs = new Object[newAsize];
            }
            if (ownerGlobals != null) LuaGC.commitRealloc(ownerGlobals, oldArrayBytes, newArrayBytes);
        } catch (RuntimeException | Error e) {
            if (ownerGlobals != null) LuaGC.commitRealloc(ownerGlobals, newHashBytes, oldHashBytes);  // rollback hash realloc
            throw e;
        }

        if (newAsize > 0) {
            array_tags = newAt;
            array_numVals = newNumVals;
            array_refs = newRefs;
        } else {
            array_tags = null;
            array_numVals = null;
            array_refs = null;
        }
        lenhint = 0;

        // ltable.c: setnodevector；size=0 -> dummynode
        if (newHsize == 0) {
            lsizenode = 0;
            node = newNode;
            lastfree = 0;
        } else {
            lsizenode = (byte) (31 - Integer.numberOfLeadingZeros(newHsize));
            node = newNode;
            lastfree = newHsize;
            // ltable.c: ；初始化所有节点（Java: 急切初始化，C: setnilkey+setempty）
            for (int j = 0; j < newHsize; j++) {
                node[j] = new Node(null, null);
            }
        }

        // 重新分配旧数组条目：增长时键 i+1 仍落数组段 -> 三次 arraycopy（免装箱/派发）；
        //   收缩时尾部旧条目落哈希段，走逐元素重插
        if (newAsize >= oldAsize) {
            if (oldAt != null && oldAsize > 0) {
                System.arraycopy(oldAt, 0, array_tags, 0, oldAsize);
                System.arraycopy(oldNumVals, 0, array_numVals, 0, oldAsize);
                System.arraycopy(oldRefs, 0, array_refs, 0, oldAsize);
            }
        } else {
            for (int i = 0; i < oldAsize; i++) {
                if (oldAt != null && oldAt[i] != 0) {
                    LuaValue v = farr2val(oldAt, oldNumVals, oldRefs, i);
                    putEntryDirect(LuaInteger.valueOf(i + 1), v, newAsize);
                }
            }
        }
        // 重新分配旧哈希条目
        for (int i = 0; i < oldSizenode; i++) {
            Node n = oldHash[i];
            if (n != null && n.live()) {
                putEntryDirect(n.key, n.nodeGetVal(), newAsize);
            }
        }

        // 重算 lenhint（数组部分最高非 nil 索引） - java-only 消费者语义，非 C 的 lenhint
        if (array_tags != null) {
            for (int i = array_tags.length - 1; i >= 0; i--) {
                if (array_tags[i] != 0) {
                    lenhint = i + 1;
                    break;
                }
            }
        }
        // ltable.c: luaH_resize  -  *lenhint(t) = newasize / 2u（C 的 border 起点猜测）
        //   直接决定带空洞表 # 返回哪个合法 border，必须逐字复现（否则 table.insert 落点分叉）
        if (array_tags != null) borderHint = newAsize / 2;

    }

    // newcheckedkey (直接放置条目到调整后的表中)
    private void putEntryDirect(LuaValue k, LuaValue v, int asize) {
        if (k instanceof LuaInteger li) {
            long x = li.tolong();
            if (x >= 1 && x <= asize) {
                int idx = (int) x - 1;
                fval2arr(array_tags, array_numVals, array_refs, idx, v);
                return;
            }
        }
        // ltable.c: reinserthash
        if (!insertHash(k, v)) LuaErrors.error("table overflow");
    }

    // java-only
    public void set(String k, LuaValue v) {
        setEntry(LuaString.newStr(k), v);
    }

    // java-only
    public void set(String k, LuaFunction v) {
        setEntry(LuaString.newStr(k), v);
    }

    // java-only
    public void set(int k, LuaValue v) {
        setEntry(LuaInteger.valueOf(k), v);
    }

    // java-only
    public LuaValue get(String k) {
        return hashGet(LuaString.newStr(k));
    }

    // java-only
    public LuaValue get(int k) {
        return rawget(LuaInteger.valueOf(k));
    }

    // java-only
    public LuaValue get(LuaValue k) {
        return rawget(k);
    }

    // java-only
    public void set(LuaValue k, LuaValue v) {
        rawset(k, v);
    }

    // ltable.c: luaH_next
    // java diff: 先 keyinarray 检查数组范围再哈希查找
    // java diff: 短串键先走 findindexShortStr（身份比较），未命中回退 findindexDeadOk
    public Varargs nextEntry(LuaValue idx) {
        if (idx.isnil()) {
            // ltable.c: findindex returns 0 -> first iteration
            Varargs r = nArray(0);
            if (r != LuaValue.NONE) return r;
            return nHash(0);
        }
        // ltable.c: keyinarray  -  在哈希查找前检查数组范围
        if (idx instanceof LuaInteger i) {
            long x = i.tolong();
            int asize = (array_tags != null) ? array_tags.length : 0;
            if (x >= 1 && x <= asize) {
                // ltable.c: 键在数组部分；从当前位置扫描数组，再查哈希
                int ix = (int) x;
                Varargs r = nArray(ix);
                if (r != LuaValue.NONE) return r;
                return nHash(0);  // ltable.c: array end -> hash part
            }
        }
        // java-only 快路径: 短字符串键用同一性比较（findindexShortStr）
        if (idx.tt_ == LuaValue.LUA_VSHRSTR) {
            int hashIdx = findindexShortStr((LuaString) idx);
            if (hashIdx >= 0)
                return nHash(hashIdx + 1);
            // 未作为活跃键找到 -> 落入 findindexDeadOk（死键情形）
        }
        // ltable.c: 键不在数组 -> getgeneric(deadok=1) 哈希查找
        int hashIdx = findindexDeadOk(idx);
        if (hashIdx >= 0)
            return nHash(hashIdx + 1);
        // ltable.c: isabstkey -> "invalid key to 'next'"
        LuaErrors.error("invalid key to 'next'");
        return LuaValue.NONE;
    }

    // java-only: nextEntryOnStack  -  同 nextEntry 逻辑但直写栈（免 Varargs 分配），供 NextFn 热路径
    // java diff: 短串键先走 findindexShortStr，未命中回退 findindexDeadOk（GC 收集键后的死键场景）
    public int nextEntryOnStack(LuaValue idx, LuaValue[] stack, int top) {
        if (idx.isnil()) {
            // ltable.c: findindex returns 0 -> first iteration
            int r = nArrayOnStack(0, stack, top);
            if (r > 0) return r;
            return nHashOnStack(0, stack, top);
        }
        // ltable.c: keyinarray  -  在哈希查找前检查数组范围
        if (idx instanceof LuaInteger i) {
            long x = i.tolong();
            int asize = (array_tags != null) ? array_tags.length : 0;
            if (x >= 1 && x <= asize) {
                int ix = (int) x;
                int r = nArrayOnStack(ix, stack, top);
                if (r > 0) return r;
                return nHashOnStack(0, stack, top);  // ltable.c: array end -> hash part
            }
        }
        // java-only 快路径: 短字符串键用同一性比较（findindexShortStr）
        // 而非 findindexDeadOk 的 instanceof 链 + raweq。pairs() 遍历字符串键
        // 哈希表时常见。死键（GC）回退到 findindexDeadOk
        if (idx.tt_ == LuaValue.LUA_VSHRSTR) {
            int hashIdx = findindexShortStr((LuaString) idx);
            if (hashIdx >= 0)
                return nHashOnStack(hashIdx + 1, stack, top);
            // 未作为活跃键找到 -> 落入 findindexDeadOk（死键情形）
        }
        // ltable.c: 键不在数组 -> getgeneric(deadok=1) 哈希查找
        int hashIdx = findindexDeadOk(idx);
        if (hashIdx >= 0)
            return nHashOnStack(hashIdx + 1, stack, top);
        // ltable.c: isabstkey -> "invalid key to 'next'"
        LuaErrors.error("invalid key to 'next'");
        return 0;
    }

    @Override
    public Varargs next(LuaValue idx) {
        return nextEntry(idx);
    }

    // lbaselib.c: ipairsaux
    @Override
    public Varargs inext(LuaValue idx) {
        long k = idx.isnil() ? 0 : idx.checklong();
        long nextK = k + 1;
        int asize = (array_tags != null) ? array_tags.length : 0;
        LuaValue v;
        if (nextK >= 1 && nextK <= asize) {
            int u = (int) nextK - 1;
            v = array_tags[u] != 0 ? farr2val(array_tags, array_numVals, array_refs, u) : LuaValue.NIL;
        } else {
            v = getInt((int) nextK);
        }
        if (v.isnil()) {
            return LuaValue.NONE;
        }
        return LuaValue.varargsOf(LuaInteger.valueOf(nextK), v);
    }

    // luaH_getn (ltable.c)  -  找边界 n（t[n]!=nil 且 t[n+1]==nil）。对齐 C 的 hint 驱动算法：
    //   borderHint 起点 + 邻域(maxvicinity=4)探测 + 二分；数组段内只读 array_tags（无装箱），
    //   末尾非空才去哈希段。# 允许返回任一边界，逐字沿用 C 搜索次序保证逐位一致
    @Override
    public int rawlen() {
        int asize = (array_tags != null) ? array_tags.length : 0;
        if (asize > 0) {
            final int MAXVICINITY = 4;
            int limit = borderHint;
            if (limit == 0 || limit > asize) limit = 1;   // 使 limit 成为数组段内合法下标
            if (array_tags[limit - 1] == 0) {
                // t[limit] 空 -> limit 之前必有边界；先在邻域向下找
                for (int i = 0; i < MAXVICINITY && limit > 1; i++) {
                    limit--;
                    if (array_tags[limit - 1] != 0) {
                        borderHint = limit;
                        return limit;
                    }
                }
                // 仍空 -> 在 [0, limit) 二分
                int b = binsearchArray(0, limit);
                borderHint = b;
                return b;
            } else {
                // t[limit] 非空 -> 向上找边界
                for (int i = 0; i < MAXVICINITY && limit < asize; i++) {
                    limit++;
                    if (array_tags[limit - 1] == 0) {
                        borderHint = limit - 1;
                        return limit - 1;
                    }
                }
                if (array_tags[asize - 1] == 0) {
                    // 数组段末尾空 -> 在 [limit, asize) 二分
                    int b = binsearchArray(limit, asize);
                    borderHint = b;
                    return b;
                }
                // 末尾非空：记下 hint 加速下次查找
                borderHint = asize;
            }
        }
        // 无数组段或 t[asize] 非空 -> 看哈希段是否接着有 asize+1
        if (node == null || node.length == 0 || intIsNil(asize + 1)) return asize;
        return hashSearch(asize);
    }

    // binsearch (ltable.c) - 在数组段 (i, j] 内二分找边界：t[i] 非空、t[j] 空。
    // 纯 array_tags 访问，无装箱。
    private int binsearchArray(int i, int j) {
        while (j - i > 1) {
            int m = (i + j) >>> 1;
            if (array_tags[m - 1] == 0) j = m;
            else i = m;
        }
        return i;
    }

    // ltable.c: hash_search  -  调用方保证 t[asize+1] 非空，seed 抖动倍增上探 + (i,j) 二分；
    //   j=2j+(rnd&1) 抖动打破"键全是 2 的幂"构造攻击（nextvar.lua attack）。
    //   [记录分叉]C 用 lua_Unsigned，Java 收窄 int 封顶 Integer.MAX_VALUE —— 触发需
    //   >2^31 个连续整键，实践不可观测
    private int hashSearch(int asize) {
        int i = asize + 1;          // 调用方已验证 t[i] 非空
        int rnd = HASH_SEED;
        int n = (asize > 0) ? ceilLog2(asize) : 0;      // ceilLog2(asize)
        int mask = (1 << n) - 1;                        // asize 位宽的全 1
        int incr = (rnd & mask) + 1;                    // 首次增量（至少 1）
        // j = (incr <= MAXINT - i) ? i+incr : i+1；用 long 防溢出比较
        int j = ((long) incr <= (long) Integer.MAX_VALUE - i) ? i + incr : i + 1;
        rnd >>>= n;                                     // 用掉 n 位
        while (!intIsNil(j)) {
            i = j;                                      // t[i] 非空
            if (j <= Integer.MAX_VALUE / 2 - 1) {
                j = j * 2 + (rnd & 1);                  // 2j 或 2j+1
                rnd >>>= 1;
            } else {
                j = Integer.MAX_VALUE;
                if (intIsNil(j)) break;
                return j;                               // 边界即最大整数（极端情形）
            }
        }
        // 不变式：i < j && t[i] 非空 && t[j] 空
        while (j - i > 1) {
            int m = (i + j) >>> 1;
            if (intIsNil(m)) j = m;
            else i = m;
        }
        return i;
    }

    // hashkeyisempty (跨数组和哈希)
    private boolean intIsNil(int idx) {
        if (idx >= 1 && array_tags != null && idx <= array_tags.length) {
            if (array_tags[idx - 1] != 0) return false;
        }
        LuaValue k = LuaInteger.valueOf(idx);
        return rawget(k).isnil();
    }


    // luaV_len; 先检查__len元方法再调用rawlen
    @Override
    public LuaValue len() {
        if (metatable != null) {
            LuaValue mm = metatable.rawget(LuaValue.LEN);
            if (!mm.isnil()) {
                return LuaCall.invoke(mm, this).arg1();
            }
        }
        return LuaInteger.valueOf(rawlen());
    }

    // java-only
    public int length() {
        return lenhint;
    }

    // java-only
    public void clear() {
        lenhint = 0;
        borderHint = 0;   // border 缓存随内容清空一并失效
        array_tags = null;
        array_numVals = null;
        array_refs = null;
        lsizenode = 0;
        node = new Node[0];  // ltable.c: dummynode
        lastfree = 0;
        m_finalizerRegistered = false;
    }

    // 作用：浅克隆表
    // java-only
    public LuaTable clone() {
        LuaTable t = new LuaTable(lenhint, node.length);  // ltable.c: allocsizenode
        for (int i = 0; i < lenhint; i++) {
            if (array_tags != null && i < array_tags.length && array_tags[i] != 0)
                t.setEntry(LuaInteger.valueOf(i + 1), farr2val(array_tags, array_numVals, array_refs, i));
        }
        return t;
    }

    // ltablib.c: tinsert  -  java-only 宿主便捷 API（android 的 LuaAdapter 族直接调用）。
    //   [归属说明]C 里 tinsert/tremove 在 ltablib.c，Lua 层入口是 lib.TableLib.InsertFn/
    //   RemoveFn；此处是宿主 Java API，只做 raw 读写、不走元方法。
    public void insert(int pos, LuaValue v) {
        int len = rawlen();
        if (pos < 1 || pos > len + 1) pos = len + 1;
        for (int i = len; i >= pos; i--)
            rawset(LuaInteger.valueOf(i + 1), rawget(LuaInteger.valueOf(i)));
        rawset(LuaInteger.valueOf(pos), v);
    }

    // ltablib.c: tremove  -  同 insert：java-only 宿主便捷 API（raw 读写，不走元方法）
    public LuaValue remove(int pos) {
        int len = rawlen();
        LuaValue v = rawget(LuaInteger.valueOf(pos));
        int p = pos;
        for (; p < len; p++) rawset(LuaInteger.valueOf(p), rawget(LuaInteger.valueOf(p + 1)));
        rawset(LuaInteger.valueOf(p), LuaValue.NIL);
        return v;
    }



    // ltable.c: luaH_next (哈希部分线性遍历)
    // java diff: C 返回索引 + setobj2s 写栈；Java 返 Varargs.of(key,value)。
    // java diff: C 是[线性扫描哈希槽]，非 Node.u.next 链遍历（gnext 仅用于查找碰撞链） -
    //   对齐保证遍历序一致（nextvar.lua）
    private Varargs nHash(int start) {
        Node[] nodes = this.node;
        int size = nodes.length;
        if (size == 0) return LuaValue.NONE;  // ltable.c: dummynode (empty hash part)
        for (int i = start; i < size; i++) {  // ltable.c: for (i ...; i < sizenode(t); i++)
            Node n = nodes[i];
            // ltable.c: !isempty(gval(gnode(t, i)))  -  跳过空/死槽位
            if (n.value_tag != FlatArith.T_NIL && n.value_tag != T_NILVAL) {
                return Varargs.of(n.key, n.nodeGetVal());
            }
        }
        return LuaValue.NONE;  // ltable.c: no more elements
    }

    // ltable.c: luaH_next (数组部分遍历)
    // java diff: C 返回 int（找到的索引）并经 setobj2s 写键/值；
    // Java 返回 Varargs.of(key, value) 避免分开的栈写入
    private Varargs nArray(int start) {
        if (array_tags == null) return LuaValue.NONE;
        int limit = Math.min(lenhint, array_tags.length);
        for (int i = start; i < limit; i++) {
            if (array_tags[i] != 0)
                return Varargs.of(LuaInteger.valueOf(i + 1), farr2val(array_tags, array_numVals, array_refs, i));
        }
        return LuaValue.NONE;
    }

    // java-only: nArrayOnStack  -  与 nArray 同逻辑但直写栈，避免 Varargs 分配
    //   和 narg()/arg(i) 提取开销。返回 0（无更多条目）或 2（找到，
    //   stack[top]=key, stack[top+1]=value）。供 nextEntryOnStack 热路径使用。
    private int nArrayOnStack(int start, LuaValue[] stack, int top) {
        if (array_tags == null) return 0;
        int limit = Math.min(lenhint, array_tags.length);
        for (int i = start; i < limit; i++) {
            if (array_tags[i] != 0) {
                stack[top] = LuaInteger.valueOf(i + 1);
                stack[top + 1] = farr2val(array_tags, array_numVals, array_refs, i);
                return 2;
            }
        }
        return 0;
    }

    // java-only: nHashOnStack  -  同 nHash 直写栈（免 Varargs 分配），供 NextFn 热路径；
    //   java diff: 对齐 ltable.c: luaH_next 线性扫描
    private int nHashOnStack(int start, LuaValue[] stack, int top) {
        Node[] nodes = this.node;
        int size = nodes.length;
        if (size == 0) return 0;  // ltable.c: dummynode (empty hash part)
        for (int i = start; i < size; i++) {  // ltable.c: for (i ...; i < sizenode(t); i++)
            Node n = nodes[i];
            // ltable.c: !isempty(gval(gnode(t, i)))  -  跳过空/死槽位
            if (n.value_tag != FlatArith.T_NIL && n.value_tag != T_NILVAL) {
                stack[top] = n.key;
                stack[top + 1] = n.nodeGetVal();
                return 2;
            }
        }
        return 0;  // ltable.c: no more elements
    }

    // java-only: T.querytab 的诊断方法（ltests.c:table_query）
    public int diagnosticArraySize() {
        return array_tags != null ? array_tags.length : 0;
    }

    // ltable.h: allocsizenode; ltests.c; isdummy(t) ? 0 : sizenode(t)
    public int diagnosticHashSize() {
        return node.length;
    }

    public LuaValue diagnosticArrayKey(int index) {
        if (array_tags == null || index < 0 || index >= array_tags.length) return null;
        return array_tags[index] != 0 ? LuaInteger.valueOf(index + 1) : null;
    }

    public LuaValue diagnosticArrayVal(int index) {
        if (array_tags == null || index < 0 || index >= array_tags.length) return null;
        return array_tags[index] != 0 ? farr2val(array_tags, array_numVals, array_refs, index) : null;
    }

    public LuaValue diagnosticHashKey(int hidx) {
        if (node == null || hidx < 0 || hidx >= node.length) return null;
        Node n = node[hidx];
        return (n != null && !n.keyisdead() && n.key != null && !n.key.isnil()) ? n.key : null;
    }

    public LuaValue diagnosticHashVal(int hidx) {
        if (node == null || hidx < 0 || hidx >= node.length) return null;
        Node n = node[hidx];
        if (n == null) return null;
        // java diff: 免 nodeGetVal 装箱 - live() 检查等价于 `hv != null && !hv.ttisnil()`
        if (!n.live()) return null;
        return n.nodeGetVal();
    }

    /**
     * java-only: 返回全部键（LuaValue 数组）。
     */
    public LuaValue[] keys() {
        List<LuaValue> list = new ArrayList<>();
        LuaValue k = LuaValue.NIL;
        while (true) {
            Varargs kv = next(k);
            LuaValue key = kv.arg1();
            if (key.isnil()) break;
            list.add(key);
            k = key;
        }
        return list.toArray(LuaValue.NOVALS);
    }

    /**
     * java-only: 返回全部值（LuaValue 数组）。
     */
    public LuaValue[] values() {
        List<LuaValue> list = new ArrayList<>();
        LuaValue k = LuaValue.NIL;
        while (true) {
            Varargs kv = next(k);
            LuaValue key = kv.arg1();
            if (key.isnil()) break;
            list.add(kv.arg(2));
            k = key;
        }
        return list.toArray(LuaValue.NOVALS);
    }

    // ============================================================
    // java-only: 便捷方法（keys/values/copyTable/add 的引擎内实现）
    // ============================================================

    /**
     * java-only: 复制数组 + 哈希键值到新表（浅拷贝，值引用不变）。
     */
    public LuaTable copyTable() {
        LuaTable t = new LuaTable();
        LuaValue k = LuaValue.NIL;
        while (true) {
            Varargs kv = next(k);
            LuaValue key = kv.arg1();
            if (key.isnil()) break;
            t.rawset(key, kv.arg(2));
            k = key;
        }
        return t;
    }

    /**
     * java-only: 追加到数组末尾（等价 Lua {@code t[#t+1]=v}）。
     */
    public void add(LuaValue v) {
        set(length() + 1, v);
    }

    // lobject.h: Node; java: 数组下标替代gnext相对偏移, -1=无后继
    // java diff: Node.i_val 从 LuaValue 扁平化为 (value_num + value_tag + value_ref)，
    // 匹配数组段的 fval2arr/farr2val 编码。消除哈希操作的装箱
    public static final class Node {
        public LuaValue key;       // lobject.h: Node.u.key_val; public for VM inline access
        // java diff: i_val 扁平化为三个字段：
        public long value_num;     // lobject.h: Node.i_val (numeric bits; int=long, flt=doubleToRawLongBits, bool=0/1)
        public byte value_tag;     // lobject.h: Node.i_val tag (FlatArith.T_* or T_NILVAL)
        public Object value_ref;   // lobject.h: Node.i_val (reference value; non-null only for T_REF)
        public int next = -1;      // lobject.h: Node.u.next; java: 绝对下标; public for VM inline access
        byte key_tt;               // lobject.h: Node.u.key_tt; LUA_TDEADKEY=死键

        Node(LuaValue key, LuaValue value) {
            this.key = key;
            // java diff: key_tt 存键的 tt_（对齐 C markkey 宏的 keyiscollectable 判定），
            //   GC markTableContents 用 (key_tt & 0x0F) >= TTABLE 跳过非可回收键（免解引用 n.key）
            this.key_tt = key != null ? (byte) key.tt_ : 0;
            nodeSetVal(value);
        }

        // ltable.c: keyisdead
        public boolean keyisdead() {
            return key_tt == LUA_TDEADKEY;
        }

        // ltable.c: setdeadkey
        // [不可在此置 key = null]死键的引用是 next() 的定位依据：findindexDeadOk 靠
        //   n.key.raweq(key) 在含死键的链上匹配，才能让"遍历中置 nil 后继续 next()"
        //   成立（置 null 则报 "invalid key to 'next'"）。键属有界滞留，界 = 表容量。
        void setdeadkey() {
            key_tt = LUA_TDEADKEY;
        }

        // ltable.h: farr2val 的哈希节点等价物  -  扁平字段 -> LuaValue（读）
        // 需要装箱 LuaValue 时使用（API/GC/next）。热路径直接读 value_num/value_tag
        public LuaValue nodeGetVal() {
            switch (value_tag) {
                case FlatArith.T_INT:
                    return LuaInteger.valueOf(value_num);
                case FlatArith.T_FLT:
                    return LuaFloat.valueOf(Double.longBitsToDouble(value_num));
                case FlatArith.T_BOOL:
                    return value_num != 0 ? LuaValue.TRUE : LuaValue.FALSE;
                case FlatArith.T_REF:
                    return (LuaValue) value_ref;
                case T_NILVAL:
                    return LuaValue.NIL;
                default:
                    return null;  // T_NIL(0) = VEMPTY (unused slot)
            }
        }

        // ltable.h: fval2arr 的哈希节点等价物  -  LuaValue -> 扁平字段（写）
        // null -> VEMPTY(T_NIL)；LuaValue.NIL -> VNIL(T_NILVAL)；其他 -> 类型专属标签
        public void nodeSetVal(LuaValue val) {
            if (val == null) {
                value_tag = FlatArith.T_NIL;
                value_num = 0;
                value_ref = null;
                return;
            }
            int tt = val.tt_;
            if (tt == LuaValue.LUA_VNUMINT) {
                value_tag = FlatArith.T_INT;
                value_num = ((LuaInteger) val).v;
                value_ref = null;
            } else if (tt == LuaValue.LUA_VNUMFLT) {
                value_tag = FlatArith.T_FLT;
                value_num = Double.doubleToRawLongBits(((LuaFloat) val).v);
                value_ref = null;
            } else if (tt == LuaValue.LUA_VTRUE) {
                value_tag = FlatArith.T_BOOL;
                value_num = 1;
                value_ref = null;
            } else if (tt == LuaValue.LUA_VFALSE) {
                value_tag = FlatArith.T_BOOL;
                value_num = 0;
                value_ref = null;
            } else if (tt == LuaValue.LUA_VNIL) {
                value_tag = T_NILVAL;
                value_num = 0;
                value_ref = null;
            } else {
                value_tag = FlatArith.T_REF;
                value_ref = val;
                value_num = 0;
            }
        }

        // java-only: 清空槽位（setempty），ltable.c: setempty(gval(n))
        public void nodeClear() {
            value_tag = FlatArith.T_NIL;
            value_num = 0;
            value_ref = null;
        }

        // ltable.c: isempty(gval(n))  -  值非 nil 且非空（槽位活跃）
        // java diff: C 的 isempty 对 VNIL 和 VEMPTY 都返回真；
        // live() = !isempty = value_tag 既非 T_NIL 也非 T_NILVAL
        boolean live() {
            return value_tag != FlatArith.T_NIL && value_tag != T_NILVAL;
        }

        // java-only: VEMPTY 检查（未用槽位）
        // ltable.c: isempty(gval(n)) 仅对 VEMPTY；VNIL(T_NILVAL) 不是 empty()
        // lobject.h: isempty(v) = ttisnil(v)  -  LUA_VEMPTY 与 LUA_VNIL 同为 nil 类型，皆算空。
        // GC 的 clearkey/弱表清扫必须用这个语义，否则"占用槽的 nil 值"被当活条目，
        // clearkey 永不执行，弱表中的字符串键每轮被 iscleared 标黑而永不回收。
        boolean empty() {
            return value_tag == FlatArith.T_NIL || value_tag == T_NILVAL;
        }

        // lobject.h: LUA_VEMPTY  -  槽从未被使用（区别于 LUA_VNIL：键在、值为 nil）。
        // 哈希链搜索的终止条件用这个：C 的链遍历按 gnext 走，不因值为 nil 而停，
        // 否则"值被置 nil 的活键"再也查不到。
        boolean vempty() {
            return value_tag == FlatArith.T_NIL;
        }
    }

    // java-only
        record PendingFinalizer(LuaValue value) {
    }
}
