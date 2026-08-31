// ref: lstring.c, lobject.h (TString)
// diff: 短串用独立 byte[]（C 内联 contents[]），shrlen 统一存长度；JVM GC 替代
// diff: free 逐对象回调；无外部字符串
// diff: 短串驻留表持软引用（C 是 strt 上的可回收对象 + luaS_remove 摘链） -
//   referent 被清除的充要条件是"Java 侧再无强引用"，此时对 Lua 亦不可达，
//   故不需要 C 的 isdead+changewhite 复活逻辑。详见 Intern.table 字段注释。
package org.luajvm.core;


import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.lang.ref.ReferenceQueue;
import java.lang.ref.SoftReference;
import java.nio.charset.StandardCharsets;
import java.text.DecimalFormatSymbols;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.concurrent.locks.ReentrantLock;

public final class LuaString extends LuaValue {
    // java-only: A/B 开关 - -Dluajvm.stropt=false 禁用（基线对照），默认开启
    //   equals 用 Arrays.equals（HotSpot intrinsify，对齐 C memcmp）；intern 入口转 byte[]
    //   让候选比较走内联路径（对齐 C internshrstr）
    static final boolean STR_OPT =
            System.getProperty("luajvm.stropt") == null ||
                    Boolean.parseBoolean(System.getProperty("luajvm.stropt"));
    // java-only: A/B 开关 - -Dluajvm.hashreuse=false 禁用 hash 复用（基线对照用），默认开启
    static final boolean HASH_REUSE =
            System.getProperty("luajvm.hashreuse") == null ||
                    Boolean.parseBoolean(System.getProperty("luajvm.hashreuse"));
    // java-only: A/B 开关 - -Dluajvm.strjcache=false 回退"每次 new String(UTF_8)"的
    //   旧行为（同一份 class 切基线）。默认开启，理由见 asciiState 字段注释。
    static final boolean STR_JCACHE =
            System.getProperty("luajvm.strjcache") == null ||
                    Boolean.parseBoolean(System.getProperty("luajvm.strjcache"));
    // java-only: 长字符串列表供 sweep（C 经 allgc 列表跟踪）
    // LUAI_MAXSHORTLEN
    private static final int LUAI_MAXSHORTLEN = 40;
    // lstate.h: STRCACHE_N/STRCACHE_M（默认 53/2；ltests.h 覆盖 N=23）
    // java diff: C 编译期宏 -> Java 系统属性（-Dluajvm.strcachen / -Dluajvm.strcachem）
    private static final int STRCACHE_N = Integer.getInteger("luajvm.strcachen", 53);
    private static final int STRCACHE_M = Integer.getInteger("luajvm.strcachem", 2);
    // lstring.c: luaS_hash 的 seed
    // java diff: C 的 g->seed 由 luai_makeseed（lauxlib.c）per-state 随机生成抗碰撞；
    //   Java 用固定常量 - 牺牲随机化换取可复现哈希
    private static final int HASH_SEED = 0x5bd1e995;
    // lstring.h: sizestrshr(l) = offsetof(TString, contents) + l + 1
    // 64 位: CommonHeader(16) + extra(1) + shrlen(1) + hash(4) + u(8) = 30 -> 对齐 32
    private static final int STRING_HEADER_BYTES = 32;
    // java-only
    private static int stringCount = 0;
    // 类初始化期间创建 MEMERRMSG 时不能反向触发 LuaTable 的初始化。
    private static final boolean initialized;
    /**
     * 短串驻留表状态。独立成 holder 类（initialization-on-demand）：字段在首次访问
     * {@code Intern.xxx} 时才初始化，规避 Metamethod 构造函数经 newStr 反向触发
     * 本类 {@code <clinit>} 的初始化顺序环（否则 table 未就绪即 NPE）。
     * 回归测试见 {@code ClassInitOrderTest}。
     */
    private static final class Intern {
        // volatile  -  读路径（findShortString）完全无锁无分配：resize/purge 先建满新表
        //   再一次性发布引用，读者要么看到旧表要么看到完整新表，不存在半初始化中间态。
        //   写路径（首次 intern）必须互斥：短串靠 == 判等，重复对象会破坏身份恒等。
        //
        // 软引用驻留（对齐 C：strt 里可回收对象，luaC_newobj 登记、sweep 时 luaS_remove 摘链）：
        //   引用被清除的充要条件是"Java 侧再无强引用"，而 Lua 栈槽、table 的 array_refs/node、
        //   upvalue 全是 Java 强引用，故清除时该串对 Lua 与 Java 双向不可达；此后同字节
        //   新 intern 得到新对象也不破坏 == 恒等（旧对象已无人持有，无从比较）。
        static final int MIN_CAPACITY = 128;
        static volatile Slot[] table = new Slot[MIN_CAPACITY];
        // 被 JVM 清除的槽经此队列通知，purge 时摘除。
        static final ReferenceQueue<LuaString> QUEUE = new ReferenceQueue<>();
        // java-only: ReentrantLock 作 intern 写锁 - Virtual Thread 阻塞时不 pin
        //   carrier（同 LuaThread 的协程锁）。只在 intern miss 时获取，命中路径不涉及。
        static final ReentrantLock LOCK = new ReentrantLock();
        // 只在 LOCK 下读写
        static int count;
    }

    /*
     * 驻留强度为 Soft：仅在堆压力下清除，且 JLS 保证抛 OOM 前全部软引用被清除 -
     * 可回收性与防抖兼得。
     */

    /**
     * 驻留表槽位。缓存 {@code hash}/{@code len} 使探测无需 {@code get()}：
     * 开放寻址下绝大多数被探到的槽是"撞了 home 位但键不同"，只在 hash+len 都相等时
     * 才取真对象比字节，从而把 {@code Reference.get()} 的读屏障挡在多数探测之外。
     *
     * <p>被清除的槽保留在表中充当 tombstone（{@code get()} 返回 null 但槽非 null），
     * 否则会截断探测链使后续同 home 的键查不到。tombstone 由 purge 一次性清除。
     *
     * <p>直接继承 {@code SoftReference}：槽位即引用，热探测路径上经槽位一次
     * {@code get()} 直达目标，无中间 wrapper。
     */
    private static final class Slot extends SoftReference<LuaString> {
        final int hash;
        final int len;

        Slot(LuaString s) {
            super(s, Intern.QUEUE);
            this.hash = s.hash;
            this.len = s.shrlen;
        }
    }

    /**
     * java-only 测试钩子：立即摘除 referent 已被 JVM 清除的槽位。
     *
     * <p>生产路径在 GC sweep 与 intern 扩容判定两处自动 purge（见
     * {@link #sweepShortStringsByColor} / {@link #insertShortString}）。测试需要在
     * "刚制造完堆压力"这一刻确认回收确已发生，不能等下一次 Lua GC，故单独暴露。
     */
    public static void purgeForTest() {
        Intern.LOCK.lock();
        try {
            purgeClearedSlots();
        } finally {
            Intern.LOCK.unlock();
        }
    }

    // java-only 诊断：-Dluajvm.internstats=true 在退出时打印 intern 命中/未命中/purge 统计。
    //   用于归因弱引用驻留的开销来源（重新 intern？purge 重建？），不参与生产路径判断。
    private static final boolean INTERN_STATS = Boolean.getBoolean("luajvm.internstats");
    private static long internMisses;      // insertShortString 次数 = 真实 intern miss 次数
    private static long purgeCalls;        // purgeClearedSlots 被调用次数（含空转）
    private static long purgeCount;        // purgeClearedSlots 实际重建次数
    private static long purgeRemoved;      // purge 摘除的已清除槽总数
    private static long purgeScanned;      // purge 扫过的槽位总数（重建成本）
    private static long resizeRebuilds;    // resizeShortStringTable 次数
    private static long rebuildSlotsScanned;  // resize 扫过的槽位总数

    static {
        if (INTERN_STATS) {
            Runtime.getRuntime().addShutdownHook(new Thread(() -> System.err.printf(
                    "[internstats] misses=%d purgeCalls=%d purges=%d purgeRemoved=%d"
                            + " purgeScanned=%d resizes=%d resizeScanned=%d cap=%d live=%d%n",
                    internMisses, purgeCalls, purgeCount, purgeRemoved, purgeScanned,
                    resizeRebuilds, rebuildSlotsScanned, Intern.table.length, Intern.count)));
        }
    }
    // lstring.c: luaS_init / MEMERRMSG
    public static final LuaString MEMERRMSG = fixedLiteral("not enough memory");
    static {
        initialized = true;
    }
    // TString.contents
    public final byte[] contents;
    // TString.shrlen; <0表示长串
    public final int shrlen;
    // TString.u.lnglen
    public final int lnglen;
    // TString.hash; 长串惰性: extra==0未计算
    // java diff: C 经 TString.hash 宏访问；Java 用包内字段直接访问
    public int hash;              // lobject.h: TString.hash; public for VM inline access
    // TString.extra; 短串:保留字标记 长串:是否已计算hash
    public byte extra;
    // TString.u.hnext
    // java diff: C 用 u.hnext separate chaining；Java 用 open addressing（清除槽由 purge
    //   一次性重建摘除，见 purgeClearedSlots，不删除 -> 无 tombstone）
    // java-only: ISO-8859-1 String 缓存，供 String.indexOf (HotSpot SIMD intrinsic) 复用
    // contents 是 final 不可变 -> 缓存安全（benign race：多线程同时初始化结果恒等）
    // 仅在 lmemfind 等需要 String.indexOf SIMD 加速的路径按需初始化，避免普遍内存开销
    String cachedString;
    // java-only: toJavaString() 的 ASCII 判定缓存（0=未判定 1=纯ASCII 2=含非ASCII）。
    //   Java 绑定的成员名/属性名分派会反复调 toJavaString，缓存避免每次重新解码。
    //   纯 ASCII 时 UTF-8 与 ISO-8859-1 解码逐字符相同，故直接复用上面的 cachedString，
    //   不新增 String 字段；本 byte 落在 extra 之后的既有对齐 padding 内，不增实例大小。
    //   benign race：contents final 不可变，多线程各判定一次的结果恒等。
    private byte asciiState;

    // lstring.c: luaS_createlngst
    // java diff: tt_ 不含 BIT_ISCOLLECTABLE（避免标记过多对象的性能损耗）
    public LuaString(byte[] b, int o, int l) {
        super(l <= LUAI_MAXSHORTLEN ? LUA_VSHRSTR : LUA_VLNGSTR);
        stringCount++;

        // java-only: o!=0 时复制对齐到偏移 0；o==0 时**直接持有 b 不拷贝**
        //   ⇒ 调用方必须交出 b 的所有权，构造后再改写 b 会静默损坏本对象。
        if (o != 0) {
            byte[] copy = new byte[l];
            System.arraycopy(b, o, copy, 0, l);
            b = copy;
            o = 0;
        }
        contents = b;
        shrlen = l;
        lnglen = 0;
        // 短串立即hash，长串惰性(hash=0,extra=0)
        if (l <= LUAI_MAXSHORTLEN) {
            hash = hashCode(b, 0, l);
            extra = 0;
        } else {
            hash = 0;
            extra = 0;  // 0 表示未计算
        }
    }

    // java-only: 短串工厂，复用预计算 hash（对齐 C internshrstr 一次 hash）；b 须为
    //   offset 0 owned byte[]；第 4 参仅为消歧构造器签名
    private LuaString(byte[] b, int l, int precomputedHash, boolean _shortMarker) {
        super(LUA_VSHRSTR);
        stringCount++;
        contents = b;
        shrlen = l;
        lnglen = 0;
        hash = precomputedHash;
        extra = 0;
    }

    private static LuaString newShort(byte[] b, int l, int precomputedHash) {
        if (HASH_REUSE) return new LuaString(b, l, precomputedHash, false);
        // 基线：走 public 构造器，重算 hash（用于 A/B 对照）
        return new LuaString(b, 0, l);
    }

    // java-only
    public static int managedStringCount() {
        return stringCount;
    }

    // lgc.c: 短串在 C 里是 strt 上的普通可回收对象，由 mark 阶段染色。
    // java diff: 短串不参与 Lua 侧染色 - 生命周期交给 JVM 的引用可达性判定
    //   （见 Intern.table 字段注释），barrier/markValue 已跳过短串，故本方法为 no-op。
    public static void markInternedStrings(Globals g, LuaGC.GrayList gray) {
        // 无操作：可达性由 JVM 的 Soft/WeakReference 判定，不需要 Lua 侧染色。
    }

    // lgc.c: sweeplist 应用于短字符串哈希桶 + checkSizes 的字符串表收缩
    // java diff: 不按 gcColor 清扫，而是摘除 referent 已被 JVM 清除的槽位。
    //   在此处（而非只在 intern 扩容时）purge，使 T.totalmem"string" 在
    //   collectgarbage() 后就下降，与 C 的 sweep 时机对齐。
    public static void sweepShortStringsByColor(Globals g) {
        Intern.LOCK.lock();
        try {
            drainInternQueue();
            if (clearedPending > 0) purgeClearedSlots();
        } finally {
            Intern.LOCK.unlock();
        }
    }

    /**
     * 待摘除的已清除槽数。只在 {@link Intern#LOCK} 下读写。
     * 使"是否值得全表重建"成为精确判据而非提示：soft 模式下无堆压力时恒无清除，
     * 无此计数就只能先做一趟 O(capacity) 全表扫描才知道有没有可摘者。
     */
    private static int clearedPending;

    /**
     * 排空 {@link Intern#QUEUE} 并累计清除数。必须在 {@link Intern#LOCK} 下调用。
     *
     * <p>累计而非返回布尔：出队信息一旦丢弃就无法重建，而排空点有两处
     * （GC sweep 与 intern 扩容判定）。累计使任一处排空的结果都不会丢失。
     * 不需要把出队的 Slot 与表槽位对应 - 摘除仍按 {@code get()==null} 判定，
     * 故与出队顺序无关。
     */
    private static void drainInternQueue() {
        while (Intern.QUEUE.poll() != null) clearedPending++;
    }

    // lgc.c: sweepstrtbucket (long strings)
    // java diff: 用 longStrings ArrayList 替代 allgc 链表；removeIf 替代手动链表操作
    public static void sweepLongStringsByColor(Globals g) {
        g.gc.longStrings.removeIf(s -> {
            if (LuaGC.isdead(g, s.gcColor)) {
                stringCount--;
                long size = sizeLngStr(s.shrlen);
                LuaGC.freeBytes(g, size);
                LuaGC.markObjectsSwept(g);  // java-only: 动态阈值跟踪
                return true;
            }
            if (LuaGC.isblack(s.gcColor)) {
                s.gcColor = LuaGC.isWhite(g);
            }
            return false;
        });
        clearStrCacheByColor(g);
    }

    // lgc.c: sweep2old  -  sweep 后幸存串已是 currentwhite
    // java diff: 无需再遍历设色（sweepByColor 已设）；仅清 strCache（对齐 luaS_clearcache）
    public static void resetColorsAfterFullGC(Globals g) {
        if (g.strCache != null) {
            for (int i = 0; i < g.strCache.length; i++) g.strCache[i] = null;
        }
    }

    /**
     * C：lgc.c : sweeplist  -  存活对象无条件重置为当前白。
     * 长串在 allgc（Java：longStrings）中，重传播时必须一并复位为白，
     * 否则上轮遗留的 BLACK 使其本轮既不被标记也不被判死，要多一个完整周期才回收
     * （gc.lua 的 "everything collected" 内存断言依赖单轮回收）。
     */
    static void repropagateLongStrings(Globals g, byte cw) {
        ArrayList<LuaString> list = g.gc.longStrings;
        for (int i = 0, n = list.size(); i < n; i++) {
            LuaString s = list.get(i);
            if (!LuaGC.iswhite(s.gcColor)) s.makeWhite(cw);
        }
    }

    // lstring.c: luaS_clearcache  -  clear API string cache
    // C: iswhite(g->strcache[i][j])->用 memerrmsg 替换
    // java diff: isdead->set null; C用memerrmsg替换（非空保证）；Java用null
    private static void clearStrCacheByColor(Globals g) {
        if (g.strCache != null) {
            for (int i = 0; i < g.strCache.length; i++) {
                LuaString[] line = g.strCache[i];
                if (line == null) continue;
                for (int j = 0; j < line.length; j++) {
                    if (line[j] != null && LuaGC.isdead(g, line[j].gcColor)) {
                        line[j] = null;
                    }
                }
            }
        }
    }

    // java-only: long->短串直转（跳过 Long.toString 中间 String；对齐 lobject.c: lua_integer2str），
    //   miss 时 byte[] 直接成为串内容零拷贝。整数串恒 <=20 位必为短串。
    // java diff: 写数字时同步累加 hash（对齐 hashCode 反向扫描顺序），省一次遍历。
    // java-only: 十进制位数（含负号）。供 concat 直写路径预算 byte[] 大小。
    //   与 valueOfLong 的位数循环同一算法（负域累加，避免 Long.MIN_VALUE 取反溢出）。
    public static int digitLen(long v) {
        boolean neg = v < 0;
        long n = neg ? v : -v;
        int len = neg ? 1 : 0;
        long t = n;
        do {
            len++;
            t /= 10;
        } while (t != 0);
        return len;
    }

    // java-only: 把 v 的十进制表示直接写进 dst[pos..pos+digitLen(v))，返回写入长度。
    //   用于 concat 的数字直写：中间数字串在 concat 结果产生后立刻是垃圾（Lua 侧不可
    //   观察），而 Java 侧驻留 miss 需锁+双查+分配，远贵于 C，故跳过驻留。
    //   [不影响结果串] 拼接结果本身照旧经 valueOfOwned 驻留，保真度不变。
    public static int digitsInto(byte[] dst, int pos, long v) {
        boolean neg = v < 0;
        long n = neg ? v : -v;
        int len = digitLen(v);
        int p = pos + len;
        do {
            dst[--p] = (byte) ('0' - (int) (n % 10));
            n /= 10;
        } while (n != 0);
        if (neg) dst[--p] = (byte) '-';
        return len;
    }

    public static LuaString valueOfLong(long v) {
        boolean neg = v < 0;
        // 用负域累加，避免 Long.MIN_VALUE 取反溢出
        long n = neg ? v : -v;
        int len = neg ? 1 : 0;
        long t = n;
        do {
            len++;
            t /= 10;
        } while (t != 0);
        byte[] b = new byte[len];
        // 同步累加 hash：初值 HASH_SEED^len（与 hashCode 一致）
        int h = HASH_SEED ^ len;
        int p = len;
        do {
            int digit = (int) (n % 10);
            byte bv = (byte) ('0' - digit);
            b[--p] = bv;
            // hashCode: h ^= (h<<5)+(h>>>2)+(bv&0xFF)
            h ^= ((h << 5) + (h >>> 2) + (bv & 0xFF));
            n /= 10;
        } while (n != 0);
        if (neg) {
            byte bv = (byte) '-';
            b[--p] = bv;
            // 负号是最后一个扫描的（b[0]），同样累加
            h ^= ((h << 5) + (h >>> 2) + (bv & 0xFF));
        }
        // 复用预计算 hash，省去 internShortOwned 内的 hashCode 全量扫描
        return internShortOwnedHashed(b, len, h);
    }

    // lstring.c: luaS_new  -  先查 cache 再建串
    // java diff: 用 String.identityHashCode 分桶；短串流量走 newLstr(byte[]) 路径，
    //   跳过 cache 直走 internShort
    public static LuaString newStr(String s) {
        // java diff: UTF-16 String -> 字节数组按 UTF-8 编码（Lua<->Java 互操作规范编码，
        //   对齐 encodeToUtf8 语义；防中文丢 '?'）。内部字节往返用 newStrLatin1。
        byte[] b = s.getBytes(StandardCharsets.UTF_8);
        return newStrBytes(b);
    }

    // java-only: 从 UTF-8 编码的 byte[] 创建（newStr(String)/valueOf 共用）。
    // 短串走 internShort(byte[])（hash 基于字节，与源码解析产生的同字节串一致，
    // intern 表不分裂）；长串走 strCache + newLstr。
    private static LuaString newStrBytes(byte[] b) {
        int len = b.length;
        if (len <= LUAI_MAXSHORTLEN) return internShort(b, 0, len);
        // C: lstring.c luaS_new  -  strcache 存于 global_State，按状态隔离
        Globals g = initialized ? LuaStates.owner() : null;
        if (g == null) return newLstr(b, 0, len);
        // java diff: C 用 point2uint(str) 分桶 —— 静态 C 字符串的指针稳定，故命中率高。
        //   Java 的 b 是每次 newStr 新建的 byte[]，identityHashCode 近似随机 ⇒ 分桶命中率
        //   远低于 C 的设计意图（正确性不受影响：命中判定仍是长度+内容比较）。
        int slot = (System.identityHashCode(b) & 0x7FFFFFFF) % STRCACHE_N;
        if (g.strCache == null) g.strCache = new LuaString[STRCACHE_N][];
        LuaString[] line = g.strCache[slot];
        if (line != null) for (LuaString ls : line)
            if (ls != null && ls.shrlen == len && equals(ls.contents, 0, b, 0, len)) return ls;
        LuaString r = newLstr(b, 0, len);
        if (line == null) {
            line = new LuaString[STRCACHE_M];
            g.strCache[slot] = line;
        }
        for (int i = STRCACHE_M - 1; i > 0; i--) line[i] = line[i - 1];
        line[0] = r;
        return r;
    }

    // java-only: Latin-1（字节保真）String -> LuaString。仅供内部字节往返
    // （StringFormat 格式化结果 StringBuilder 存的是字节 1:1 的 char），
    // 与 UTF-8 的 newStr(String) 语义严格分离，防止双重编码。
    public static LuaString newStrLatin1(String s) {
        byte[] b = s.getBytes(StandardCharsets.ISO_8859_1);
        return newStrBytes(b);
    }

    // luaS_new (CharSequence)
    // java diff: 同 newStr(String) 按 UTF-8 编码；内部字节往返用 valueOfLatin1。
    public static LuaString valueOf(CharSequence s) {
        return newStrBytes(s.toString().getBytes(StandardCharsets.UTF_8));
    }

    // java-only: Latin-1（字节保真）CharSequence -> LuaString（StringFormat 专用）。
    public static LuaString valueOfLatin1(CharSequence s) {
        int len = s.length();
        if (len <= LUAI_MAXSHORTLEN) return internShort(s, len);
        byte[] b = new byte[len];
        for (int i = 0; i < len; i++) {
            int c = s.charAt(i) & 0xFF;
            b[i] = (byte) c;
        }
        return newLstr(b, 0, len);
    }

    // lstring.c: luaS_newlstr  -  带显式长度的新字符串
    // C: l<=LUAI_MAXSHORTLEN->internshrstr; 否则->luaS_createlngstrobj
    public static LuaString newLstr(byte[] b, int o, int l) {
        if (l <= LUAI_MAXSHORTLEN) return internShort(b, o, l);
        checkLstrSize(l);
        long totalsize = sizeLngStr(l);
        createLngStrObj(totalsize);
        LuaString r = new LuaString(b, o, l);
        r.gcColor = LuaGC.isWhite();  // lgc.c: luaC_newobj sets marked = isWhite(g)
        registerLongString(r);
        return r;
    }

    // luaS_newlstr（owned）
    // java-only
    public static LuaString valueOfOwned(byte[] b) {
        return valueOfOwned(b, b.length);
    }

    // luaS_newlstr（owned, len）
    // java-only
    public static LuaString valueOfOwned(byte[] b, int l) {
        if (l <= LUAI_MAXSHORTLEN) return internShortOwned(b, l);
        checkLstrSize(l);
        long totalsize = sizeLngStr(l);
        createLngStrObj(totalsize);
        LuaString r = new LuaString(b, 0, l);
        r.gcColor = LuaGC.isWhite();  // lgc.c: luaC_newobj sets marked = isWhite(g)
        registerLongString(r);
        return r;
    }

    // lstring.c: internshrstr  -  内部化短串
    // java diff: C 对命中的 dead 串 changewhite"复活"；Java 无对应动作 - 引用被清除
    //   即代表 Java 侧再无强引用，不存在"还活着但被判死"的中间态可复活；
    //   已清除的槽在探测时直接跳过，随后按 miss 建新对象（见 findShortString）。
    // java diff: C 满桶时 growstrtab（可能 fullGC）；Java 只扩数组
    // 命中走无锁无分配快路径；miss 进锁并在锁内二次查找（另一线程可能已插入同串）
    private static LuaString internShort(byte[] b, int o, int l) {
        int hash = hashCode(b, o, l);
        LuaString found = findShortString(b, o, l, hash);
        if (found != null) return found;
        Intern.LOCK.lock();
        try {
            found = findShortString(b, o, l, hash);
            if (found != null) return found;
            createstrobj(sizeStrShr(l));
            byte[] copy = Arrays.copyOfRange(b, o, o + l);
            // java-only: 复用 hash，避免构造器重算
            LuaString r = newShort(copy, l, hash);
            insertShortString(r);
            return r;
        } finally {
            Intern.LOCK.unlock();
        }
    }

    // internshrstr (owned); java: 避免多余拷贝
    private static LuaString internShortOwned(byte[] b, int l) {
        int hash = hashCode(b, 0, l);
        LuaString found = findShortString(b, 0, l, hash);
        if (found != null) return found;
        Intern.LOCK.lock();
        try {
            found = findShortString(b, 0, l, hash);
            if (found != null) return found;
            createstrobj(sizeStrShr(l));
            // java-only: 复用 hash
            LuaString r = newShort(b, l, hash);
            insertShortString(r);
            return r;
        } finally {
            Intern.LOCK.unlock();
        }
    }

    // java-only: internShortOwned 的预计算 hash 版本（valueOfLong 用）
    // 调用方已保证 hash == hashCode(b,0,l)，省去全量扫描
    private static LuaString internShortOwnedHashed(byte[] b, int l, int hash) {
        LuaString found = findShortString(b, 0, l, hash);
        if (found != null) return found;
        Intern.LOCK.lock();
        try {
            found = findShortString(b, 0, l, hash);
            if (found != null) return found;
            createstrobj(sizeStrShr(l));
            LuaString r = newShort(b, l, hash);
            insertShortString(r);
            return r;
        } finally {
            Intern.LOCK.unlock();
        }
    }

    // internshrstr (String)
    private static LuaString internShort(String s, int l) {
        int hash = hashCode(s, l);
        LuaString found = findShortString(s, l, hash);
        if (found != null) return found;
        Intern.LOCK.lock();
        try {
            found = findShortString(s, l, hash);
            if (found != null) return found;
            createstrobj(sizeStrShr(l));
            byte[] copy = new byte[l];
            for (int i = 0; i < l; i++) copy[i] = (byte) (s.charAt(i) & 0xFF);
            // java-only: 复用 hash（String 与 byte[] 字节恒等，hash 一致）
            LuaString r = newShort(copy, l, hash);
            insertShortString(r);
            return r;
        } finally {
            Intern.LOCK.unlock();
        }
    }

    // internshrstr (CharSequence)
    private static LuaString internShort(CharSequence s, int l) {
        int hash = hashCode(s, l);
        LuaString found = findShortString(s, l, hash);
        if (found != null) return found;
        Intern.LOCK.lock();
        try {
            found = findShortString(s, l, hash);
            if (found != null) return found;
            createstrobj(sizeStrShr(l));
            byte[] copy = new byte[l];
            for (int i = 0; i < l; i++) copy[i] = (byte) (s.charAt(i) & 0xFF);
            // java-only: 复用 hash
            LuaString r = newShort(copy, l, hash);
            insertShortString(r);
            return r;
        } finally {
            Intern.LOCK.unlock();
        }
    }

    /**
     * 双散列步长：必须为奇数，才能在 2 的幂容量下遍历全表（奇数与 2^k 互质）；
     * 取 hash 高位混合 - 索引只用低位（{@code h & mask}），高位信息未被利用。
     *
     * <p>java diff: C 用 {@code hnext} 分离链接，天然无聚簇；Java 用开放寻址（见
     * findShortString），双散列让 home 相同的键走不同步长，消除线性探测的主聚簇。
     */
    private static int probeStep(int h) {
        return DOUBLE_HASH ? (((h >>> 15) ^ (h >>> 27)) | 1) : 1;
    }

    // java-only: A/B 开关 -Dluajvm.dblhash=false 回退线性探测（同一份 class 切基线）
    private static final boolean DOUBLE_HASH =
            System.getProperty("luajvm.dblhash") == null
                    || Boolean.parseBoolean(System.getProperty("luajvm.dblhash"));

    // lstring.c: internshrstr find
    // java diff: open addressing（双散列）替代 separate chaining，省 hnext 字段 8B/串。
    //   槽被清除后仍留在表中充当 tombstone（slot != null 但 get() == null），
    //   故探测的终止条件是 slot == null，不是 get() == null。
    private static LuaString findShortString(byte[] b, int o, int l, int h) {
        Slot[] tab = Intern.table;
        int mask = tab.length - 1;
        int i = h & mask;
        int step = probeStep(h);
        for (Slot slot = tab[i]; slot != null; ) {
            // hash+len 预筛：不等则连 get() 都不做（读屏障不上热路径）
            if (slot.hash == h && slot.len == l) {
                LuaString s = slot.get();
                if (s != null && equals(s.contents, 0, b, o, l)) return s;
            }
            i = (i + step) & mask;
            slot = tab[i];
        }
        return null;
    }

    // internshrstr find (CharSequence)
    private static LuaString findShortString(CharSequence cs, int l, int h) {
        Slot[] tab = Intern.table;
        int mask = tab.length - 1;
        int i = h & mask;
        int step = probeStep(h);
        for (Slot slot = tab[i]; slot != null; ) {
            if (slot.hash == h && slot.len == l) {
                LuaString s = slot.get();
                if (s != null && equals(s.contents, 0, cs, l)) return s;
            }
            i = (i + step) & mask;
            slot = tab[i];
        }
        return null;
    }

    // internshrstr insert  -  只在 Intern.LOCK 下调用
    // java diff: 未移植 C 的 growstrtab 上限保护（nuse==INT_MAX -> fullgc -> luaM_error
    //   "too many strings"，以及 MAXSTRTB 容量上限）：驻留 2^31 个短串前 JVM 已 OOM。
    private static void insertShortString(LuaString s) {
        s.gcColor = LuaGC.isWhite();  // lgc.c: luaC_newobj sets marked = isWhite(g)
        // java diff: 75% 负载因子时扩容（open addressing 需要余量避免聚集）；
        //   C 在 100% 时扩容（separate chaining 容忍满桶）。
        // 先清死条目再考虑扩容：对齐 C 的 luaS_resize 由 checkSizes 在 sweep 后调用 -
        //   已被回收的串腾出的空间应先复用，而不是直接翻倍。
        if ((Intern.count + 1) * 4 >= Intern.table.length * 3) {
            // 先排空引用队列：清除是 JVM 异步做的，不排空就不知道有没有空位可复用。
            drainInternQueue();
            if (clearedPending > 0) purgeClearedSlots();
            if ((Intern.count + 1) * 4 >= Intern.table.length * 3)
                resizeShortStringTable(Intern.table.length * 2);
        }
        Slot[] tab = Intern.table;
        int mask = tab.length - 1;
        int i = s.hash & mask;
        int step = probeStep(s.hash);
        while (tab[i] != null) i = (i + step) & mask;
        tab[i] = new Slot(s);
        Intern.count++;
        if (INTERN_STATS) internMisses++;
        // 重新 volatile 发布同一引用：为槽位写入和 s 的字段写入提供 release 屏障，
        //   使无锁读者（findShortString）能及时看到新条目。
        Intern.table = tab;
    }

    /**
     * 丢弃 referent 已被 JVM 回收的槽位并重建表（去 tombstone）。
     *
     * <p>C：{@code lgc.c : checkSizes} 在 sweep 后按 nuse 收缩字符串表，
     * {@code lstring.c : luaS_remove} 从桶链摘除被回收的串。Java 的等价物就是这里：
     * 一个短串的 referent 被清除，当且仅当 Java 侧再无强引用 - 而 Lua 栈、表的
     * 数组段/哈希节点、upvalue 全是 Java 引用，故"无 Java 引用"蕴含"Lua 不可达"。
     * 这比 Lua 自己的可达性分析更保守（宁可留着），不会误删仍在用的串。
     *
     * <p>只在 Intern.LOCK 下调用。重建而非置 tombstone：开放寻址的 tombstone 会重新
     * 引入被双散列消掉的聚簇（见 {@link #probeStep}），而重建是 O(capacity) 且只发生在
     * 扩容判定与 GC sweep 两处，不在命中路径。
     *
     * <p>两趟：第一趟必须先数出存活数才能给新表定容，故不可合并。调用方须先按
     * {@link #clearedPending} 判定确有可摘者，否则这两趟全表扫描纯浪费。
     */
    private static void purgeClearedSlots() {
        if (INTERN_STATS) purgeCalls++;
        Slot[] old = Intern.table;
        int live = 0;
        for (Slot slot : old) {
            if (slot != null && slot.get() != null) live++;
        }
        int removed = Intern.count - live;
        // clearedPending 已在此消费：即使 removed==0（那些槽已被 resize 顺带丢弃），
        //   也必须归零，否则该计数会永久为正、使本方法每次 sweep 都被白调一遍。
        clearedPending = 0;
        if (removed <= 0) {
            Intern.count = live;
            return;
        }
        // 容量按存活数收缩到 2 的幂，但不低于初始容量（对齐 C 的 checkSizes 收缩语义）。
        // 收缩到"刚好 75% 满"会让紧随其后的几次 intern 立刻触发 resize，两次 O(capacity)
        // 重建来回抖动。故按 live 的两倍留余量，落在约 37.5% 载荷，下次扩容前有充足空位。
        int size = Intern.MIN_CAPACITY;
        while (size * 3 < (live + 1) * 8) size <<= 1;
        Slot[] tab = new Slot[size];
        int mask = size - 1;
        for (Slot slot : old) {
            if (slot == null) continue;
            LuaString s = slot.get();
            if (s == null) continue;
            int i = slot.hash & mask;
            int step = probeStep(slot.hash);
            while (tab[i] != null) i = (i + step) & mask;
            tab[i] = slot;
        }
        Intern.count = live;
        stringCount -= removed;
        Intern.table = tab;
        if (INTERN_STATS) {
            purgeCount++;
            purgeRemoved += removed;
            purgeScanned += old.length;
        }
    }

    // lstring.c: luaS_init  -  初始化字符串表和缓存
    // C: luaM_newvector+tablerehash+memerrmsg+luaC_fix+填充 strCache
    // java diff: Java用静态初始化；fixedLiteral替代luaC_fix
    private static LuaString fixedLiteral(String s) {
        byte[] b = s.getBytes(StandardCharsets.ISO_8859_1);
        LuaString r = new LuaString(b, 0, b.length);
        insertShortString(r);
        return r;
    }

    // lstring.c: luaS_resize  -  resize string table
    // java diff: 不处理 realloc 失败回滚（JVM 抛 OutOfMemoryError）
    // 收缩路径见 purgeClearedSlots（对齐 C 的 checkSizes：sweep 后按 nuse 收缩）。
    // 只在 Intern.LOCK 下调用：先把新表填满，再一次性 volatile 发布，
    //   读者要么看到旧表（完整）要么看到新表（完整），不存在半迁移中间态。
    private static void resizeShortStringTable(int size) {
        Slot[] old = Intern.table;
        Slot[] tab = new Slot[size];
        int mask = tab.length - 1;
        // java diff: 开放寻址双散列的 rehash（步长须与 find/insert 一致）
        //   顺带丢弃已被清除的槽（get()==null），等价于 C 的 luaS_remove
        int live = 0;
        for (Slot slot : old) {
            if (slot == null || slot.get() == null) continue;
            int i = slot.hash & mask;
            int step = probeStep(slot.hash);
            while (tab[i] != null) i = (i + step) & mask;
            tab[i] = slot;
            live++;
        }
        stringCount -= (Intern.count - live);
        Intern.count = live;
        Intern.table = tab;
        // rehash 已顺带丢弃全部已清除槽，故待摘计数归零：不归零会让下一次 sweep
        //   白跑一遍 purgeClearedSlots 的两趟全表扫描。
        clearedPending = 0;
        if (INTERN_STATS) {
            resizeRebuilds++;
            rebuildSlotsScanned += old.length;
        }
    }

    // lstring.c: createstrobj -> luaC_newobj -> luaM_malloc_
    // java diff: 只做限额检查，不记账（只有长串记账，见 createLngStrObj）。
    //   短串现在可回收（软引用驻留），但回收时机由 JVM GC 决定而非 Lua 的 sweep：
    //   若在此记账，luaMemoryBytes 的下降点就变成不确定的，而 memerr.lua/locals.lua
    //   都在断言"两次 T.totalmem() 之间的字节增量" - 那些断言需要确定性。
    //   故保持不记账：字节口径与 C 有差（C 的分配器把短串算进 total），
    //   但对象计数（T.totalmem"string"）随 purge 下降，见 purgeClearedSlots。
    private static void createstrobj(long totalsize) {
        if (!initialized) return;
        Globals g = owningGlobals();
        if (g == null) return;
        LuaGC.checkMemory(g, totalsize);
    }

    // C：lgc.c : luaC_newobj  -  长串登记到所属状态的 allgc（Java：g.gc.longStrings）。
    // 记账与清扫都在同一状态内完成，避免 A 状态的长串被 B 状态的 GC 清扫。
    private static void registerLongString(LuaString r) {
        if (!initialized) return;   // 静态初始化期不得触发 LuaTable.<clinit>
        Globals g = owningGlobals();
        if (g != null) g.gc.longStrings.add(r);
    }

    // 长串会被 sweepLongStringsByColor 回收，因此分配与释放走同一状态记账，两侧对称。
    private static void createLngStrObj(long totalsize) {
        if (!initialized) return;
        Globals g = owningGlobals();
        if (g == null) return;
        LuaGC.checkMemory(g, totalsize);
        LuaGC.commitRealloc(g, 0, totalsize);
    }

    /**
     * 长串（可回收、计入记账）的所属状态。
     * 优先取正在运行的状态：这不是猜测 - 有线程正在该状态内执行时，此刻创建的
     * 长串就属于它。仅当无运行状态时退回首个登记状态（单状态场景恒等价）。
     * 只能在 initialized 之后调用：静态初始化期引用 LuaTable 会触发其 clinit，
     * 而后者依赖尚未初始化的 Metamethod。
     */
    private static Globals owningGlobals() {
        // 只经 LuaStates（无静态依赖）取状态，不引用 LuaTable/Globals 的静态成员，
        // 否则字符串分配路径会触发 LuaTable.<clinit> 而撞上初始化环。
        return LuaStates.owner();
    }


    // lstring.c: sizestrshr
    private static long sizeStrShr(int l) {
        return STRING_HEADER_BYTES + (long) l + 1L;
    }

    // lstring.c: luaS_sizelngstr
    private static long sizeLngStr(int l) {
        return STRING_HEADER_BYTES + (long) l + 1L;
    }

    // lstring.c: luaS_newlstr 块过大检查
    private static void checkLstrSize(int l) {
        if (l < 0 || sizeLngStr(l) < 0) LuaGC.tooBig();
    }

    // lstring.c: luaS_eqstr  -  对齐 C 的 memcmp
    // java diff: 用 Arrays.equals（HotSpot intrinsify 逐 8 字节比较，语义等价）
    public static boolean equals(byte[] a, int ai, byte[] b, int bi, int len) {
        // java diff: STR_OPT 关时回退原始逐字节循环（A/B 基线对照）
        if (STR_OPT) return Arrays.equals(a, ai, ai + len, b, bi, bi + len);
        for (int i = 0; i < len; i++) if (a[ai + i] != b[bi + i]) return false;
        return true;
    }

    private static boolean equals(byte[] a, int ai, CharSequence b, int len) {
        for (int i = 0; i < len; i++) if ((a[ai + i] & 0xFF) != (b.charAt(i) & 0xFF)) return false;
        return true;
    }

    // lstring.c: luaS_hash
    public static int hashCode(byte[] b, int o, int l) {
        int h = HASH_SEED ^ l;
        for (int i = o + l - 1; i >= o; i--) h ^= ((h << 5) + (h >>> 2) + (b[i] & 0xFF));
        return h;
    }

    // luaS_hash (CharSequence)
    private static int hashCode(CharSequence s, int l) {
        int h = HASH_SEED ^ l;
        for (int i = l - 1; i >= 0; i--) h ^= ((h << 5) + (h >>> 2) + (s.charAt(i) & 0xFF));
        return h;
    }

    // luaO_str2num 整数
    private static LuaInteger tryParseInteger(String s) {
        if (s.isEmpty()) return null;
        int i = 0;
        boolean neg = false;
        if (s.charAt(0) == '-' || s.charAt(0) == '+') {
            neg = s.charAt(0) == '-';
            i++;
        }
        if (i >= s.length()) return null;
        // 十六进制整数：0-9/a-f/A-F，可选 0x 前缀；拒绝 '.' 和 'p'/'P'
        if (i + 1 < s.length() && s.charAt(i) == '0' && (s.charAt(i + 1) == 'x' || s.charAt(i + 1) == 'X')) {
            for (int j = i + 2; j < s.length(); j++) {
                char c = s.charAt(j);
                if (c == '.' || c == 'p' || c == 'P') return null;
            }
            int j = i + 2;
            long a = 0;
            boolean empty = true;
            while (j < s.length()) {
                int d = hexDigit(s.charAt(j));
                if (d < 0) break;
                a = a * 16 + d;
                empty = false;
                j++;
            }
            if (empty) return null;
            if (j != s.length())
                return null;  // java diff: reject trailing garbage (was silently accepted)
            return LuaInteger.valueOf(neg ? -a : a);
        }
        // 十进制整数：拒绝 '.'/'e'/'E'/'p'/'P'
        for (int j = i; j < s.length(); j++) {
            char c = s.charAt(j);
            if (c == '.' || c == 'e' || c == 'E' || c == 'p' || c == 'P') return null;
        }
        try {
            long v = Long.parseLong(s);
            return LuaInteger.valueOf(v);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    // luaO_str2num 浮点
    private static LuaFloat tryParseFloat(String s) {
        if (s.isEmpty()) return null;
        boolean neg = false;
        int i = 0;
        if (s.charAt(0) == '-' || s.charAt(0) == '+') {
            neg = s.charAt(0) == '-';
            i++;
        }
        if (i + 1 < s.length() && s.charAt(i) == '0' && (s.charAt(i + 1) == 'x' || s.charAt(i + 1) == 'X')) {
            return parseHexFloatStr(s, neg);
        }
        if (looksLikeDecimalFloat(s, i, '.')) {
            try {
                double d = Double.parseDouble(s);
                return LuaFloat.valueOf(d);
            } catch (NumberFormatException e) {
                if (s.indexOf('.') < 0) return null;
                // l_str2d; java: locale小数点重试
                char decPoint = DecimalFormatSymbols.getInstance().getDecimalSeparator();
                if (decPoint != '.') {
                    String replaced = s.replace('.', decPoint);
                    try {
                        double d = Double.parseDouble(replaced.replace(decPoint, '.'));
                        return LuaFloat.valueOf(d);
                    } catch (NumberFormatException e2) {
                        return null;
                    }
                }
            }
        } else if (mayNeedLocaleDecimalFloat(s, i)) {
            // Java 的 Double.parseDouble 仅接受 '.'。仅当常规预扫描失败、且串疑似用了本地小数点时才查询 locale
            char decPoint = DecimalFormatSymbols.getInstance().getDecimalSeparator();
            if (decPoint != '.' && looksLikeDecimalFloat(s, i, decPoint)) {
                String replaced = s.replace(decPoint, '.');
                try {
                    double d = Double.parseDouble(replaced);
                    return LuaFloat.valueOf(d);
                } catch (NumberFormatException e) {
                    return null;
                }
            }
        }
        return null;
    }

    // l_str2d; java: 预扫描避免异常探测
    private static boolean looksLikeDecimalFloat(String s, int i, char decPoint) {
        boolean hasDigit = false;
        boolean hasDot = false;
        boolean hasExp = false;
        int expDigits = 0;
        for (; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c >= '0' && c <= '9') {
                hasDigit = true;
                if (hasExp) expDigits++;
            } else if ((c == '.' || c == decPoint) && !hasDot && !hasExp) {
                hasDot = true;
            } else if ((c == 'e' || c == 'E') && !hasExp && hasDigit) {
                hasExp = true;
                expDigits = 0;
                if (i + 1 < s.length() && (s.charAt(i + 1) == '-' || s.charAt(i + 1) == '+')) i++;
            } else {
                return false;
            }
        }
        return hasDigit && (!hasExp || expDigits > 0);
    }

    // lstring.c: l_str2dloc
    private static boolean mayNeedLocaleDecimalFloat(String s, int i) {
        boolean hasDigit = false;
        boolean hasDecimalCandidate = false;
        boolean hasExp = false;
        int expDigits = 0;
        for (; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c >= '0' && c <= '9') {
                hasDigit = true;
                if (hasExp) expDigits++;
            } else if (c == '.' && !hasDecimalCandidate && !hasExp) {
                hasDecimalCandidate = true;
            } else if (c == ',' && !hasDecimalCandidate && !hasExp) {
                hasDecimalCandidate = true;
            } else if ((c == 'e' || c == 'E') && !hasExp && hasDigit) {
                hasExp = true;
                expDigits = 0;
                if (i + 1 < s.length() && (s.charAt(i + 1) == '-' || s.charAt(i + 1) == '+')) i++;
            } else {
                return false;
            }
        }
        return hasDigit && hasDecimalCandidate && (!hasExp || expDigits > 0);
    }

    // lua_strx2number; java: 也接受本地小数点
    private static LuaFloat parseHexFloatStr(String s, boolean neg) {
        // 格式: [+-]?0x[HHH][.HHH][p+/-EE]
        char decPoint = DecimalFormatSymbols.getInstance().getDecimalSeparator();
        int i = 0;
        if (i < s.length() && (s.charAt(i) == '-' || s.charAt(i) == '+')) i++;
        if (i + 1 < s.length() && s.charAt(i) == '0' && (s.charAt(i + 1) == 'x' || s.charAt(i + 1) == 'X'))
            i += 2;
        double r = 0.0;
        int sigdig = 0;
        int nosigdig = 0;
        int e = 0;
        boolean hasdot = false;
        boolean hasDigit = false;
        final int MAXSIGDIG = 200;
        while (i < s.length()) {
            char c = s.charAt(i);
            if (c == '.' || c == decPoint) {
                if (hasdot) break;
                hasdot = true;
            } else {
                int d = hexDigit(c);
                if (d < 0) break;
                hasDigit = true;
                if (sigdig == 0 && d == 0) {
                    nosigdig++;
                } else if (++sigdig <= MAXSIGDIG) {
                    r = r * 16.0 + d;
                } else {
                    e++; // 超出有效位数，忽略但计入指数
                }
                if (hasdot) e--;
            }
            i++;
        }
        if (!hasDigit) return null;
        e *= 4;  // 每个十六进制位是 2^4
        if (i < s.length() && (s.charAt(i) == 'p' || s.charAt(i) == 'P')) {
            i++;
            boolean eneg = false;
            if (i < s.length() && (s.charAt(i) == '-' || s.charAt(i) == '+')) {
                eneg = s.charAt(i) == '-';
                i++;
            }
            int exp1 = 0;
            boolean any = false;
            while (i < s.length()) {
                int d = hexDigit(s.charAt(i));
                if (d < 0 || d > 9) break;  // 十进制指数数字
                exp1 = exp1 * 10 + d;
                any = true;
                i++;
            }
            if (!any) return null;
            e += eneg ? -exp1 : exp1;
        }
        if (i != s.length()) return null;
        if (neg) r = -r;
        return LuaFloat.valueOf(r * Math.pow(2.0, e));
    }

    private static int hexDigit(char c) {
        if (c >= '0' && c <= '9') return c - '0';
        if (c >= 'a' && c <= 'f') return c - 'a' + 10;
        if (c >= 'A' && c <= 'F') return c - 'A' + 10;
        return -1;
    }

    private static int digit(byte b) {
        if (b >= '0' && b <= '9') return b - '0';
        if (b >= 'a' && b <= 'z') return b - 'a' + 10;
        if (b >= 'A' && b <= 'Z') return b - 'A' + 10;
        return -1;
    }

    // lgc.c: objsize  -  GC 记账的近似大小
    @Override
    public int gcSize() {
        return 48 + (contents != null ? contents.length : 0);
    }

    // lstring.c: ttypetag
    @Override
    public int type() {
        return TSTRING;
    }

    // lstring.c: luaT_objtypename
    @Override
    public String typeName() {
        return "string";
    }

    // lapi.c: lua_isstring
    @Override
    public boolean isstring() {
        return true;
    }

    // lstring.c: luaL_checkstring
    @Override
    public LuaString checkstring() {
        return this;
    }

    // lstring.c: luaV_rawequalobj
    @Override
    public boolean raweq(LuaValue r) {
        if (!(r instanceof LuaString s)) return false;
        // 短串==身份比较
        if (this.tt_ == LUA_VSHRSTR && s.tt_ == LUA_VSHRSTR) return this == s;
        if (shrlen != s.shrlen) return false;
        return equals(contents, 0, s.contents, 0, shrlen);
    }

    // lstring.c: luaV_tonumber
    // lua_isnumber: 字符串可扫描为数字时返回true
    @Override
    public boolean isnumber() {
        return scannumber() != null;
    }

    // lstring.c: luaV_tonumber
    @Override
    public LuaValue tonumber() {
        LuaNumber n = scannumber();
        return n != null ? n : LuaValue.NIL;
    }

    // lstring.c: luaV_tostring
    @Override
    public LuaValue tostring() {
        return this;
    }

    // lstring.c: luaL_optlstring
    @Override
    public String optJavaString(String d) {
        return toJavaString();
    }

    // -- 数字字符串->整数/浮点隐式强转（对齐 luaL_checkinteger/luaL_optinteger）--
    // 未覆写时基类 check* 抛 typerror（C 正常返回）、opt* 静默返默认值（结果错） -
    // 覆写为：scannumber() 扫描，不可转报 "number expected"，非整数表示报 no-integer-representation
    private LuaNumber toNumberOrError() {
        LuaNumber n = scannumber();
        if (n == null) typeError("number");
        return n;
    }

    // 浮点表示：数字字符串直接取 double（"7"->7.0、"1e3"->1000.0）
    @Override
    public double checkdouble() {
        return toNumberOrError().todouble();
    }

    @Override
    public double optdouble(double d) {
        return checkdouble();
    }

    // 整数表示：整数串直接取；浮点串须有精确整数表示（对齐 LuaFloat.checkIntegerValid）
    private long toIntegerOrError() {
        LuaNumber n = toNumberOrError();
        if (n.isinteger()) return n.tolong();
        double v = n.todouble();
        if (Double.isNaN(v) || Double.isInfinite(v) || v != Math.floor(v))
            LuaErrors.toIntError(this, this);
        if (v < (double) Long.MIN_VALUE || v >= -(double) Long.MIN_VALUE)
            LuaErrors.toIntError(this, this);
        return (long) v;
    }

    @Override
    public int checkint() {
        return (int) toIntegerOrError();
    }

    @Override
    public long checklong() {
        return toIntegerOrError();
    }

    @Override
    public int optint(int d) {
        return (int) toIntegerOrError();
    }

    @Override
    public long optlong(long d) {
        return toIntegerOrError();
    }

    // lobject.h: l_isfalse
    @Override
    public boolean toboolean() {
        return true;
    }

    // lstring.c: getmetatable
    @Override
    public LuaValue getmetatable() {
        // C：ltm.c : luaT_gettmbyobj  -  基础类型元表存于 G(L)->mt[t]
        Globals g = LuaStates.owner();
        return g == null ? null : g.typeMetatable(LuaValue.TSTRING);
    }

    // lstring.c: setmetatable
    @Override
    public LuaValue setmetatable(LuaValue mt) {
        // C：lapi.c : lua_setmetatable  -  写 G(L)->mt[t]
        Globals g = LuaStates.owner();
        if (g != null) g.setTypeMetatable(LuaValue.TSTRING, mt);
        return this;
    }

    // java diff: UTF-8 解码 - Lua 字节数组按 UTF-8 显示中文；
    //   字节保真场景用 cachedString()（ISO-8859-1 视图）或直接操作 contents
    @Override
    public String toJavaString() {
        // 只对短串（<=40B）缓存：短串是驻留的、数量有界的，且正是 Java 绑定成员名
        //   反复分派的形态；长串可能是整个文件内容，缓存 String 会让其内存翻倍，
        //   而长串走 toJavaString 的场景（错误消息等）不是热路径。
        if (STR_JCACHE && shrlen <= LUAI_MAXSHORTLEN) {
            byte st = asciiState;
            if (st == 0) {
                st = isAsciiContents() ? (byte) 1 : (byte) 2;
                asciiState = st;
            }
            if (st == 1) return cachedString();
        }
        return new String(contents, 0, shrlen, StandardCharsets.UTF_8);
    }

    // java-only: contents 是否全为 ASCII（最高位为 0）。只在首次 toJavaString 时扫一遍。
    private boolean isAsciiContents() {
        byte[] c = contents;
        for (int i = 0, n = shrlen; i < n; i++) {
            if (c[i] < 0) return false;
        }
        return true;
    }

    // java-only: ISO-8859-1 字节视图缓存（供 lmemfind String.indexOf SIMD 路径）；
    //   contents final 不可变 -> 缓存安全（benign race）
    // java diff: 与 toJavaString() 分离 - lmemfind 按字节搜索必须 1:1 字节映射，
    //   复用 UTF-8 视图会因索引错位全部失配
    public String cachedString() {
        String s = cachedString;
        if (s == null) {
            s = new String(contents, 0, shrlen, StandardCharsets.ISO_8859_1);
            cachedString = s;
        }
        return s;
    }

    // java-only: 免分配的字节扫描，等价 toJavaString().indexOf((char)b)>=0。
    // 用于 GC 弱表 __mode 判定 'v'/'k' 等热路径，避免每次 new String + byte[]。
    public boolean containsByte(int b) {
        byte target = (byte) b;
        for (int i = 0; i < shrlen; i++) if (contents[i] == target) return true;
        return false;
    }

    // lstring.c: tsvalue
    @Override
    public LuaString strValue() {
        return this;
    }

    // lstring.c: luaS_hashlongstr  -  计算长字符串哈希（惰性）
    // C: if (ts->extra == 0) { ts->hash = luaS_hash(...); ts->extra = 1; }
    @Override
    public int hashCode() {
        if (tt_ == LUA_VLNGSTR && extra == 0) {
            hash = hashCode(contents, 0, shrlen);
            extra = 1;
        }
        return hash;
    }

    // lstring.c: luaV_rawequalobj
    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o instanceof LuaString s) {
            if (this.tt_ == LUA_VSHRSTR && s.tt_ == LUA_VSHRSTR) return this == s;
            return shrlen == s.shrlen && equals(contents, 0, s.contents, 0, shrlen);
        }
        return false;
    }

    // lobject.c: luaO_str2num
    public LuaNumber scannumber() {
        return scannumberDefault();
    }

    // luaO_str2num（base）
    public LuaNumber scannumber(int base) {
        if (shrlen == 0) return null;
        String s = toJavaString();
        return scannumberBase(s, base);
    }

    // lobject.c: luaO_str2num
    private LuaNumber scannumberDefault() {
        if (shrlen == 0) return null;
        // 免分配快路径：纯十进制整数直接在 byte[] 上解析（避免 toJavaString+trim 两次 new
        // String）；任何非纯十进制形态（float/hex/含\0/溢出/空）返回 null 回落慢路径
        LuaInteger fast = fastScanDecimalInt();
        if (fast != null) return fast;
        String s = toJavaString();
        if (s.indexOf('\0') >= 0) return null;
        s = s.trim();
        if (s.isEmpty()) return null;
        LuaInteger iv = tryParseInteger(s);
        if (iv != null) return iv;
        LuaFloat fv = tryParseFloat(s);
        return fv;
    }

    // 免分配十进制整数扫描；仅在明确是纯十进制整数且不溢出时返回值，其余返回 null 回落慢路径。
    // java-only
    private LuaInteger fastScanDecimalInt() {
        int i = 0, j = shrlen;
        // trim 首尾空白（排除 \0：含 \0 的输入交给慢路径判定，语义一致）。
        // String.trim 去除 <=0x20；这里额外排除 0x00，使含 \0 的输入必落慢路径。
        while (i < j) {
            int b = contents[i] & 0xFF;
            if (b > 0 && b <= 0x20) i++;
            else break;
        }
        while (j > i) {
            int b = contents[j - 1] & 0xFF;
            if (b > 0 && b <= 0x20) j--;
            else break;
        }
        if (i >= j) return null; // trim 后为空 -> 慢路径（返回 null，一致）
        boolean neg = false;
        int b0 = contents[i] & 0xFF;
        if (b0 == '+' || b0 == '-') {
            neg = (b0 == '-');
            i++;
        }
        if (i >= j) return null; // 只有符号无数字 -> 慢路径
        // 负域累加，精确覆盖 Long.MIN_VALUE；标准溢出检测（同 Long.parseLong）。
        long v = 0;
        for (int k = i; k < j; k++) {
            int b = contents[k] & 0xFF;
            if (b < '0' || b > '9') return null; // 非纯十进制（float/hex/inf/nan 等）-> 慢路径
            int d = b - '0';
            if (v < Long.MIN_VALUE / 10) return null;        // *10 前溢出 -> 慢路径（转 float）
            v *= 10;
            if (v < Long.MIN_VALUE + d) return null;         // -d 前溢出 -> 慢路径
            v -= d;
        }
        if (neg) return LuaInteger.valueOf(v);
        if (v == Long.MIN_VALUE) return null;                // 正数 -v 溢出（MAX+1）-> 慢路径转 float
        return LuaInteger.valueOf(-v);
    }

    // luaO_str2num（base）
    private LuaNumber scannumberBase(String s, int base) {
        boolean neg = false;
        int i = 0;
        while (i < s.length() && Character.isWhitespace(s.charAt(i))) i++;
        if (i < s.length() && (s.charAt(i) == '-' || s.charAt(i) == '+')) {
            neg = s.charAt(i) == '-';
            i++;
        }
        if (i >= s.length()) return null;
        if (base == 16 && i + 1 < s.length() && s.charAt(i) == '0' && (s.charAt(i + 1) == 'x' || s.charAt(i + 1) == 'X'))
            i += 2;
        long v = 0;
        boolean any = false;
        for (; i < s.length(); i++) {
            char c = s.charAt(i);
            if (Character.isWhitespace(c)) break;
            int d = digit((byte) c);
            if (d < 0 || d >= base) return null;
            v = v * base + d;
            any = true;
        }
        if (!any) return null;
        while (i < s.length() && Character.isWhitespace(s.charAt(i))) i++;
        if (i != s.length()) return null;
        return neg ? LuaInteger.valueOf(-v) : LuaInteger.valueOf(v);
    }

    // lstring.c: luaV_rawlen
    public int rawlen() {
        return shrlen;
    }

    // lvm.c: l_strcmp
    public int cmp(LuaString r) {
        int len = Math.min(shrlen, r.shrlen);
        for (int i = 0; i < len; i++) {
            int a = contents[i] & 0xFF, b = r.contents[i] & 0xFF;
            if (a != b) return a - b;
        }
        return shrlen - r.shrlen;
    }

    // l_strcmp; java: 字节比较替代strcoll，strings.lua 区域排序用例失败
    public int lStrcmp(LuaString r) {
        return cmp(r);
    }



    // java-only
    public InputStream toInputStream() {
        return new ByteArrayInputStream(contents, 0, shrlen);
    }

    // java-only: 返回底层 byte[] 当且仅当数组长度==shrlen（无冗余空间），供 load() 热路径
    //   跳过 InputStream->readAllBytes 拷贝。长度不匹配时返回 null，调用方回退到 InputStream 路径。
    public byte[] bytesIfExact() {
        return contents.length == shrlen ? contents : null;
    }

    // java-only
    public int luaByte(int i) {
        return contents[i] & 0xFF;
    }

    // java-only
    public char charAt(int i) {
        return (char) (contents[i] & 0xFF);
    }

    // java-only
    public byte[] bytes() {
        if (shrlen == contents.length) return contents;
        byte[] r = new byte[shrlen];
        System.arraycopy(contents, 0, r, 0, shrlen);
        return r;
    }
}
