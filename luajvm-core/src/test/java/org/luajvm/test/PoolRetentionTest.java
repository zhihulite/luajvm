package org.luajvm.test;

import org.luajvm.bind.Platform;
import org.luajvm.compiler.Parser;
import org.luajvm.core.Globals;

import java.io.Reader;
import java.io.StringReader;
import java.lang.ref.WeakReference;
import java.lang.reflect.Field;
import java.util.ArrayDeque;

/**
 * java-only 门禁：ThreadLocal 对象池归还时必须清除对源数据的引用。
 *
 * <p>C 里这些结构（{@code LexState}、{@code MatchState}）是栈分配局部变量，
 * 函数返回即消失。Java 为消除分配把它们池化到 {@code ThreadLocal}，
 * 于是**归还时忘记清的字段会随池永久滞留**，且每线程一份；
 * 协程密集的负载（每协程一个线程）会放大成每线程一份源数据。
 *
 * <p>守两处滞留缺陷：
 * <ul>
 *   <li>{@code Lexer.release()} 清了词法状态（buff/token/dyd/scannerStrings）
 *       但漏了 {@code srcData}（**整份脚本源字节数组**）、{@code reader}、
 *       {@code source}/{@code shortSource}。编译一份 1MB 脚本后，
 *       那 1MB 随池滞留到该线程下次编译为止（若无下次则到线程终止）。</li>
 *   <li>{@code StringPattern} 的 {@code MatchState} 池化实例（{@code MS_FIND_POOL}
 *       单槽 + {@code MS_GSUB_FREELIST} 自由列表）归还时未清 {@code src}/{@code pat}，
 *       最后一次 {@code string.find}/{@code gsub} 的**主串与模式串字节数组**滞留。</li>
 * </ul>
 *
 * <p>两处都用"大到能与噪声区分"的载荷（约 2MB）+ 弱引用判定：
 * 编译/匹配后丢掉 Lua 侧全部引用，反射读池内实例的字段，
 * 断言那个 byte[] 已不是喂进去的那份。
 */
public final class PoolRetentionTest {
    private static int failures = 0;

    /** 载荷大小：足够大，滞留与否在 heap 上可区分。 */
    private static final int PAYLOAD = 2 * 1024 * 1024;

    public static void main(String[] args) throws Exception {
        lexerReleaseClearsSource();
        matchStateReleaseClearsSubject();
        threadDeathReleasesPools();

        if (failures > 0) {
            System.out.println("PoolRetentionTest: " + failures + " FAILED");
            System.exit(1);
        }
        System.out.println("PoolRetentionTest: PASS");
    }

    /**
     * 编译一份大脚本后，池内 Lexer 不得再持有那份源字节数组。
     *
     * <p>前置自检：必须确认池里真有一个 Lexer 实例（池化被 {@code -Dluajvm.poollex=false}
     * 关掉时本用例无判别力），且喂进去的源确实是造出的那份大数组。
     */
    private static void lexerReleaseClearsSource() throws Exception {
        Globals g = Platform.standardGlobals();

        // 造一份大脚本：约 2MB 的注释 + 一行有效代码。注释走词法扫描但不产生字节码，
        // 故源数组大而 Prototype 小 => 滞留只可能来自源数组本身。
        StringBuilder sb = new StringBuilder(PAYLOAD + 64);
        sb.append("-- ");
        while (sb.length() < PAYLOAD) sb.append("xxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxx");
        sb.append("\nreturn 1\n");
        String bigSource = sb.toString();
        int bigLen = bigSource.length();

        g.execute(bigSource);

        // 全限定名必须保留：`Lexer` 是包私有类，测试在 org.luajvm.test 包外，
        //   无法 import，仅能经反射按名字取（下面 StringPattern 的两处同理）。
        Class<?> lexerCls = Class.forName("org.luajvm.compiler.Lexer");

        // 前置自检 1：池化必须是开着的，否则本用例恒真空转
        Field poolEnabled = lexerCls.getDeclaredField("POOL_ENABLED");
        poolEnabled.setAccessible(true);
        boolean pooling = (Boolean) poolEnabled.get(null);
        check(pooling, "前置：Lexer 池化须开启（-Dluajvm.poollex 未关）；关掉则本用例无判别力");
        if (!pooling) return;

        // 前置自检 2：池里必须真有一个实例
        Field poolField = lexerCls.getDeclaredField("POOL");
        poolField.setAccessible(true);
        @SuppressWarnings("unchecked")
        ThreadLocal<Object> pool = (ThreadLocal<Object>) poolField.get(null);
        Object pooled = pool.get();
        check(pooled != null, "前置：编译后池内应有一个 Lexer 实例");
        if (pooled == null) return;

        // 断言：池内实例不得再持有那份大源数组
        Field srcDataField = lexerCls.getDeclaredField("srcData");
        srcDataField.setAccessible(true);
        byte[] retained = (byte[]) srcDataField.get(pooled);
        int retainedLen = retained == null ? 0 : retained.length;
        check(retained == null,
                "池内 Lexer 不得持有源字节数组（实测滞留 " + retainedLen + " 字节，脚本源 "
                        + bigLen + " 字节）");

        lexerReleaseClearsReader(lexerCls, pool);
    }

    /**
     * 池内 Lexer 不得持有 {@code Reader}（它可能间接持有输入流/文件句柄）。
     *
     * <p><b>必须单独走 Reader 路径</b>：{@code reader} 字段只由
     * {@code resetSource(Reader,...)} 赋值，即 {@code Parser.parse(Reader,...)} 那条重载
     * （生产侧为 {@code LuaPlatform} 的流式编译与 {@code LuacCompiler}）。而
     * {@code g.execute(String)} 走的是 {@code Parser.parse(byte[],...)} ->
     * {@code resetSource(byte[],...)}，后者自己就显式写 {@code reader = null} ⇒ 若仅用
     * {@code execute} 取证，断言前 {@code reader} 从来就是 null，与 {@code release()}
     * 是否清它无关，断言恒真空转（与 ClassLoader 门禁的失效形态同构：被测载荷从不进入
     * 被测路径）。
     *
     * <p>故这里先真跑一次 Reader 路径 parse，并<b>前置断言该路径确实给 reader 赋过值</b>——
     * 否则将来 Parser 若改走别的重载，本用例会再次静默退回空转。
     */
    private static void lexerReleaseClearsReader(Class<?> lexerCls, ThreadLocal<Object> pool)
            throws Exception {
        Field readerField = lexerCls.getDeclaredField("reader");
        readerField.setAccessible(true);

        // 前置自检：确认 Reader 路径真的会写 reader 字段。用一个记录读取次数的
        //   Reader 包装，parse 期间必须被读过 —— 证明这条路径确实以 reader 为源。
        int[] reads = {0};
        Reader probe = new Reader() {
            private final Reader delegate = new StringReader("return 1\n");

            @Override
            public int read(char[] cbuf, int off, int len) throws java.io.IOException {
                reads[0]++;
                return delegate.read(cbuf, off, len);
            }

            @Override
            public int read() throws java.io.IOException {
                reads[0]++;
                return delegate.read();
            }

            @Override
            public void close() throws java.io.IOException {
                delegate.close();
            }
        };
        Parser.parse(probe, "readerchunk");
        check(reads[0] > 0,
                "前置：Reader 路径 parse 应真的从 reader 读取（实测 " + reads[0]
                        + " 次）；若为 0 则 reader 字段未被走到，本断言无判别力");

        Object pooled = pool.get();
        check(pooled != null, "前置：Reader 路径 parse 后池内应有 Lexer 实例");
        if (pooled == null) return;

        Object retainedReader = readerField.get(pooled);
        check(retainedReader == null,
                "池内 Lexer 不得持有 Reader（可能间接持有输入流/文件句柄；实测 "
                        + retainedReader + "）");
    }

    /**
     * 大串上跑一次 find/gsub 后，池内 MatchState 不得再持有主串与模式串。
     *
     * <p>两个池分别验：{@code MS_FIND_POOL} 是单槽常驻实例（{@code string.find} 用），
     * {@code MS_GSUB_FREELIST} 是自由列表（{@code string.gsub} 用，因回调可重入）。
     */
    private static void matchStateReleaseClearsSubject() throws Exception {
        Globals g = Platform.standardGlobals();

        // 约 2MB 主串，末尾放一个可匹配的标记。
        // 关键：find 的模式必须含特殊字符（这里用 `%u+` 字符类），否则纯字面串走
        //   memchr 式快路径，根本不建 MatchState => 断言成了恒真空转。
        g.execute("local n = " + (PAYLOAD / 32) + "\n"
                + "local big = string.rep('abcdefghijklmnopqrstuvwxyz012345', n) .. 'NEEDLE'\n"
                + "assert(string.find(big, '%u+'))\n"
                + "local r = string.gsub(big, '%u+', 'X')\n"
                + "assert(#r > 0)\n"
                + "big = nil\n"
                + "r = nil\n"
                + "collectgarbage('collect')\n");

        Class<?> spCls = Class.forName("org.luajvm.lib.StringPattern");

        Field msEnabled = spCls.getDeclaredField("MS_POOL_ENABLED");
        msEnabled.setAccessible(true);
        boolean pooling = (Boolean) msEnabled.get(null);
        check(pooling, "前置：MatchState 池化须开启；关掉则本用例无判别力");
        if (!pooling) return;

        Class<?> msCls = Class.forName("org.luajvm.lib.StringPattern$MatchState");
        Field srcField = msCls.getDeclaredField("src");
        srcField.setAccessible(true);
        Field patField = msCls.getDeclaredField("pat");
        patField.setAccessible(true);

        // MS_FIND_POOL：单槽常驻
        Field findPoolField = spCls.getDeclaredField("MS_FIND_POOL");
        findPoolField.setAccessible(true);
        @SuppressWarnings("unchecked")
        ThreadLocal<Object> findPool = (ThreadLocal<Object>) findPoolField.get(null);
        Object findMs = findPool.get();
        check(findMs != null, "前置：find 后 MS_FIND_POOL 应有实例");
        if (findMs != null) {
            byte[] src = (byte[]) srcField.get(findMs);
            check(src == null,
                    "MS_FIND_POOL 的 MatchState 不得持有主串（实测滞留 "
                            + (src == null ? 0 : src.length) + " 字节）");
            byte[] pat = (byte[]) patField.get(findMs);
            check(pat == null,
                    "MS_FIND_POOL 的 MatchState 不得持有模式串（实测滞留 "
                            + (pat == null ? 0 : pat.length) + " 字节）");
        }

        // MS_GSUB_FREELIST：自由列表，可能多个实例
        Field gsubPoolField = spCls.getDeclaredField("MS_GSUB_FREELIST");
        gsubPoolField.setAccessible(true);
        @SuppressWarnings("unchecked")
        ThreadLocal<ArrayDeque<Object>> gsubPool =
                (ThreadLocal<ArrayDeque<Object>>) gsubPoolField.get(null);
        ArrayDeque<Object> freelist = gsubPool.get();
        check(freelist != null && !freelist.isEmpty(),
                "前置：gsub 后 MS_GSUB_FREELIST 应有实例（实测 "
                        + (freelist == null ? "null" : freelist.size()) + "）");
        if (freelist != null) {
            int retainedSrc = 0;
            int retainedPat = 0;
            for (Object ms : freelist) {
                if (srcField.get(ms) != null) retainedSrc++;
                if (patField.get(ms) != null) retainedPat++;
            }
            check(retainedSrc == 0,
                    "MS_GSUB_FREELIST 的 MatchState 均不得持有主串（实测 " + retainedSrc
                            + "/" + freelist.size() + " 个仍持有）");
            check(retainedPat == 0,
                    "MS_GSUB_FREELIST 的 MatchState 均不得持有模式串（实测 " + retainedPat
                            + "/" + freelist.size() + " 个仍持有）");
        }
    }

    /**
     * per-thread 形状：N 个线程各建一份 ThreadLocal 池，线程死后池实例须随之可回收。
     *
     * <p>补的是堆字节斜率类探针的盲区 ——
     * 「per-class / per-thread 等不随轮数增长的泄漏」。本类其余用例都在<b>单线程内</b>
     * 验池字段清理，测不到「每个新线程留一份、线程死了不回收」这种形状：那种泄漏的量
     * 不随循环轮数涨，而随<b>线程数</b>涨。
     *
     * <p>判别力载体是前置自检「捕获到的池实例数 &gt; 0」：若负载压根没建池
     * （例如将来 Lexer 改走非池化路径），主断言会因为无对象可观察而恒真。
     */
    private static void threadDeathReleasesPools() throws Exception {
        Field lexPool = Class.forName("org.luajvm.compiler.Lexer").getDeclaredField("POOL");
        lexPool.setAccessible(true);
        Field msPool = Class.forName("org.luajvm.lib.StringPattern")
                .getDeclaredField("MS_FIND_POOL");
        msPool.setAccessible(true);

        int n = 24;
        java.util.List<WeakReference<Thread>> threads = new java.util.ArrayList<>();
        java.util.List<WeakReference<Object>> pools = new java.util.ArrayList<>();

        for (int i = 0; i < n; i++) {
            final Object[] captured = new Object[2];
            Thread t = new Thread(() -> {
                // 每个线程独立 Globals：跑编译（建 Lexer 池）+ 模式匹配（建 MatchState 池）
                Globals g = Platform.standardGlobals();
                g.execute("local s = 'abc' .. tostring(1) return s:find('b')");
                try {
                    captured[0] = ((ThreadLocal<?>) lexPool.get(null)).get();
                    captured[1] = ((ThreadLocal<?>) msPool.get(null)).get();
                } catch (Exception ignored) {
                    // 反射失败不影响主断言（前置自检会因捕获数为 0 而 FAIL）
                }
            }, "poolprobe-" + i);
            t.start();
            t.join();
            threads.add(new WeakReference<>(t));
            for (Object o : captured) {
                if (o != null) pools.add(new WeakReference<>(o));
            }
        }

        check(!pools.isEmpty(), "前置：负载须真的建出 ThreadLocal 池实例（实测 "
                + pools.size() + " 个；为 0 则本组空转）");

        for (int i = 0; i < 6; i++) {
            System.gc();
            Thread.sleep(30);
        }

        int liveThreads = 0;
        for (WeakReference<Thread> r : threads) if (r.get() != null) liveThreads++;
        int livePools = 0;
        for (WeakReference<Object> r : pools) if (r.get() != null) livePools++;

        check(liveThreads == 0, "死线程须全部可回收（实测仍可达 " + liveThreads + "/" + n + "）");
        check(livePools == 0, "线程死后其 ThreadLocal 池实例须全部可回收（实测仍可达 "
                + livePools + "/" + pools.size() + "；非 0 = per-thread 泄漏）");
    }

    private static void check(boolean ok, String what) {
        if (ok) {
            System.out.println("  OK: " + what);
        } else {
            System.out.println("  FAIL: " + what);
            failures++;
        }
    }
}
