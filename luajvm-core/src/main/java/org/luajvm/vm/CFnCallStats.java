// java-only: C 函数 callOnStack 命中率探针（-Dluajvm.countcfn=true）
package org.luajvm.vm;

import org.luajvm.core.LuaFunction;

import java.util.ArrayList;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 统计 {@code precallC} 走栈直调还是回退 Varargs。
 * 启用：{@code -Dluajvm.countcfn=true}，由测试运行器在收尾处打印。
 */
public final class CFnCallStats {

    public static final boolean ENABLED = Boolean.getBoolean("luajvm.countcfn");

    private static final ConcurrentHashMap<String, Row> BY_CLASS = new ConcurrentHashMap<>();

    private CFnCallStats() {
    }

    static final class Row {
        final AtomicLong stack = new AtomicLong();
        final AtomicLong varargs = new AtomicLong();
        final AtomicLong nargSum = new AtomicLong();
    }

    public static void record(LuaFunction f, boolean stackHit, int narg) {
        String name = f.getClass().getName();
        Row row = BY_CLASS.computeIfAbsent(name, k -> new Row());
        if (stackHit) {
            row.stack.incrementAndGet();
        } else {
            row.varargs.incrementAndGet();
            row.nargSum.addAndGet(narg);
        }
    }

    public static void printStats() {
        long stackTotal = 0, varargsTotal = 0;
        for (Row r : BY_CLASS.values()) {
            stackTotal += r.stack.get();
            varargsTotal += r.varargs.get();
        }
        long total = stackTotal + varargsTotal;
        System.err.println("\n=== C 函数 callOnStack 命中率 ===");
        System.err.printf("  总计 %,d  栈直调 %,d (%.1f%%)  Varargs 回退 %,d (%.1f%%)%n",
                total, stackTotal, pct(stackTotal, total),
                varargsTotal, pct(varargsTotal, total));

        printBucket("org.luajvm.lib.", "库函数");
        printBucket("org.luajvm.bind.", "Java 绑定");
        printBucket("org.luajvm.test.", "测试辅助（T 库等）");
        printOther();
    }

    private static void printBucket(String prefix, String title) {
        ArrayList<Map.Entry<String, Row>> rows = new ArrayList<>();
        long stack = 0, varargs = 0;
        for (Map.Entry<String, Row> e : BY_CLASS.entrySet()) {
            if (!e.getKey().startsWith(prefix)) continue;
            rows.add(e);
            stack += e.getValue().stack.get();
            varargs += e.getValue().varargs.get();
        }
        long total = stack + varargs;
        if (total == 0) return;
        System.err.printf("%n[%s] 总计 %,d  栈直调 %,d (%.1f%%)  Varargs %,d (%.1f%%)%n",
                title, total, stack, pct(stack, total), varargs, pct(varargs, total));
        printTop(rows, "  Varargs 回退 Top", true);
        printTop(rows, "  栈直调 Top", false);
    }

    private static void printOther() {
        ArrayList<Map.Entry<String, Row>> rows = new ArrayList<>();
        for (Map.Entry<String, Row> e : BY_CLASS.entrySet()) {
            String k = e.getKey();
            if (k.startsWith("org.luajvm.lib.")
                    || k.startsWith("org.luajvm.bind.")
                    || k.startsWith("org.luajvm.test.")) continue;
            rows.add(e);
        }
        if (rows.isEmpty()) return;
        System.err.println("\n[其它]");
        printTop(rows, "  Varargs 回退 Top", true);
        printTop(rows, "  栈直调 Top", false);
    }

    private static void printTop(ArrayList<Map.Entry<String, Row>> rows, String title, boolean varargs) {
        rows.sort((a, b) -> Long.compare(
                varargs ? b.getValue().varargs.get() : b.getValue().stack.get(),
                varargs ? a.getValue().varargs.get() : a.getValue().stack.get()));
        System.err.println(title);
        int shown = 0;
        for (Map.Entry<String, Row> e : rows) {
            Row r = e.getValue();
            long n = varargs ? r.varargs.get() : r.stack.get();
            if (n == 0) continue;
            String shortName = e.getKey();
            int lastDot = shortName.lastIndexOf('.');
            if (lastDot >= 0) shortName = shortName.substring(lastDot + 1);
            if (varargs) {
                long nargs = r.nargSum.get();
                System.err.printf("    %-40s %,12d  avgNarg=%.1f  (栈直调 %,d)%n",
                        shortName, n, n == 0 ? 0.0 : nargs / (double) n, r.stack.get());
            } else {
                System.err.printf("    %-40s %,12d  (Varargs %,d)%n",
                        shortName, n, r.varargs.get());
            }
            if (++shown >= 20) break;
        }
        if (shown == 0) System.err.println("    (无)");
    }

    private static double pct(long n, long d) {
        return d == 0 ? 0.0 : n * 100.0 / d;
    }
}
