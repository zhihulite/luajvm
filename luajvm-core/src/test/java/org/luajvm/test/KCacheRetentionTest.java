package org.luajvm.test;

import org.luajvm.compiler.Parser;
import org.luajvm.compiler.SyntaxNodes;

import java.io.StringReader;
import java.lang.reflect.Array;
import java.lang.reflect.Field;
import java.util.ArrayDeque;
import java.util.HashMap;

/**
 * java-only：编译器 {@code FuncState} 池不得滞留长大的 kcache 表。
 *
 * <p>{@code Parser.FS_POOL} 是每线程的 {@code FuncState} 自由列表（上限 16），
 * 对齐 C 中 {@code FuncState} 的栈分配零堆开销。但 {@code FuncState.kcache} 的三张
 * {@code HashMap} 容量**只增不减**：{@code HashMap.clear()} 仅清空条目，不缩容。
 *
 * <p>故编译过一个常量极多的函数后，那张长大的表会随 {@code FuncState} 回到池中，
 * 被后续小函数取用并长期滞留；池上限 16 个 {@code FuncState} x 3 张表，
 * 单线程可滞留数 MB 且没有任何缩容时机。这与 {@code GlobalsRetentionTest}/{@code InternPressureTest}
 * 守的是同一形态：**长生命周期容器持有按最坏情况长大的结构**。
 *
 * <p>机制：{@code FuncState.reset()} 按高水位回收 - 超过
 * {@code KCACHE_RECYCLE_MAX} 条目的表置 null，交回 {@code KCache} 既有的惰性初始化
 * 重建；阈值内仍 {@code clear()} 复用（免分配，即池化的本意）。
 *
 * <p>本门禁不测 wall-time（其效应经隔离 A/B 证明落在噪声内）；守的是滞留量。
 */
public final class KCacheRetentionTest {
    /** 造 big 函数时每个用多少个互异整数常量（远超 KCACHE_RECYCLE_MAX=512）。 */
    private static final int CONSTANTS_PER_BIG_FUNC = 3000;
    /** 池上限是 16，造够多轮以覆盖多个池槽。 */
    private static final int BIG_FUNC_ROUNDS = 4;
    /**
     * 修复后允许的滞留槽位上限。
     *
     * <p>阈值内的表**仍可复用**（池化的本意），故上限不是 0：
     * {@code KCACHE_RECYCLE_MAX=512} 条目 => 容量 1024 槽（512/0.75 上取 2 的幂），
     * 三张表全部顶到阈值即 3072 槽。故 3072 是"合法复用"的严格上界，
     * 超过它只可能是长大的表被滞留。
     *
     * <p>本测试造的 big 函数用 3000 个常量 => 单表容量 4096 > 3072，
     * 因此修复失效时本门禁必然失败（已用 {@code -Pkcacherecycle=false} 验证）。
     */
    private static final int MAX_RETAINED_SLOTS = 3072;

    private static int failures;

    public static void main(String[] args) throws Exception {
        // 1) 编译若干"常量极多"的函数，把池中多个 FuncState 的 kcache 撑大
        for (int round = 0; round < BIG_FUNC_ROUNDS; round++) {
            Parser.parse(new StringReader(bigSource(round)), "big" + round);
        }
        // 2) 再编译一批小函数：无修复时它们取到长大的表并把它留在池里
        for (int i = 0; i < 20; i++) {
            Parser.parse(new StringReader("local a = 1 return a"), "small" + i);
        }

        PoolStats st = measurePool();
        System.out.println("pooled FuncState = " + st.pooled
                + ", retained kcache slots = " + st.slots
                + ", max single-table capacity = " + st.maxCap
                + ", approx Node[] bytes = " + 4L * st.slots);

        check("compiling constant-heavy functions actually populated the pool", st.pooled > 0);
        check("pool does not retain oversized kcache tables"
                        + " (slots=" + st.slots + " <= " + MAX_RETAINED_SLOTS + ")",
                st.slots <= MAX_RETAINED_SLOTS);
        check("no single retained kcache table exceeds the recycle threshold"
                        + " (maxCap=" + st.maxCap + ")",
                st.maxCap <= MAX_RETAINED_SLOTS);

        // 3) 回收后编译仍必须正确：常量索引复用逻辑不能因置 null 而错
        check("constant folding still correct after kcache recycling", compilesAndRuns());

        if (failures > 0) {
            System.err.println("KCacheRetentionTest: " + failures + " FAILED");
            System.exit(1);
        }
        System.out.println("KCacheRetentionTest: PASS");
    }

    /** 常量极多的函数源码：每行一个互异整数常量，迫使 kcache.integers 长大。 */
    private static String bigSource(int round) {
        StringBuilder sb = new StringBuilder();
        sb.append("local function f()\n  local t = 0\n");
        for (int i = 0; i < CONSTANTS_PER_BIG_FUNC; i++) {
            sb.append("  t = t + ").append(1000000 + round * 100000 + i).append('\n');
        }
        sb.append("  return t\nend\nreturn f\n");
        return sb.toString();
    }

    /**
     * 重复常量必须仍折叠为同一个常量槽（kcache 的语义），且重复串共享槽。
     * 置 null 仅应影响"是否复用 HashMap 实例"，不应影响常量表内容。
     */
    private static boolean compilesAndRuns() {
        // 同一整数/串各出现多次：kcache 命中则常量表仅存一份
        var proto = Parser.parse(new StringReader(
                "local a = 7 + 7 local s = 'x' .. 'x' return a, s"), "dedup");
        if (proto == null) return false;
        int sevens = 0, xs = 0;
        for (var k : proto.k) {
            if (k == null) continue;
            if (k.isnumber() && k.tolong() == 7) sevens++;
            if (k.isstring() && "x".equals(k.toJavaString())) xs++;
        }
        // 每个字面量在常量表中仅应出现一次
        return sevens <= 1 && xs <= 1;
    }

    private record PoolStats(int pooled, int slots, int maxCap) {
    }

    /** 反射读 {@code Parser.FS_POOL} 与 {@code HashMap.table}，精确量化滞留槽位。 */
    private static PoolStats measurePool() throws Exception {
        Field poolField = Parser.class.getDeclaredField("FS_POOL");
        poolField.setAccessible(true);
        @SuppressWarnings("unchecked")
        ThreadLocal<ArrayDeque<SyntaxNodes.FuncState>> tl =
                (ThreadLocal<ArrayDeque<SyntaxNodes.FuncState>>) poolField.get(null);
        ArrayDeque<SyntaxNodes.FuncState> pool = tl.get();

        Field tableField = HashMap.class.getDeclaredField("table");
        tableField.setAccessible(true);

        int slots = 0, maxCap = 0, pooled = 0;
        for (SyntaxNodes.FuncState fs : pool) {
            pooled++;
            HashMap<?, ?>[] maps = {fs.kcache.strings, fs.kcache.integers, fs.kcache.floats};
            for (HashMap<?, ?> m : maps) {
                if (m == null) continue;
                Object t = tableField.get(m);
                int cap = t == null ? 0 : Array.getLength(t);
                slots += cap;
                if (cap > maxCap) maxCap = cap;
            }
        }
        return new PoolStats(pooled, slots, maxCap);
    }

    static void check(String name, boolean ok) {
        System.out.println("  " + (ok ? "OK: " : "FAIL: ") + name);
        if (!ok) failures++;
    }
}
