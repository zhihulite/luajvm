package org.luajvm.test;

import org.luajvm.core.LuaString;

/**
 * java-only：短串驻留表在堆压力下必须可回收。
 *
 * <p>这是"无内存泄漏"的判定性测试，不是计数测试。若把全部 interned 短串
 * 钉死（fixedgc），驻留表 append-only，任何动态生成的 <=40 字节串会永久滞留，
 * 且每轮新串线性累加，属无界泄漏。
 * 实际驻留表槽位持 {@code SoftReference}，堆压力下可回收。
 *
 * <p>判据取自 JLS/Javadoc 对 {@code SoftReference} 的保证：**抛出
 * {@code OutOfMemoryError} 之前，所有软引用必被清除**。故本测试先生成大量互异短串，
 * 再制造真实堆压力直到 OOM，捕获后检查驻留计数是否下降。
 * 钉死模式下计数不可能下降（无论多大压力，JVM 都无权回收）-> 必然先 OOM 死掉。
 *
 * <p>用法：{@code gradlew :luajvm-core:internPressureTests}
 * 必须以小堆运行（任务已设 {@code -Xmx256m}），否则生成阶段撑不出压力。
 */
public final class InternPressureTest {
    /** 每个串 <=40 字节才走短串驻留路径（LUAI_MAXSHORTLEN）。 */
    private static final int DISTINCT = 400_000;

    public static void main(String[] args) {
        System.out.println("maxHeap=" + (Runtime.getRuntime().maxMemory() >> 20) + "M");

        int before = LuaString.managedStringCount();
        // 生成互异短串后立即丢弃引用：Lua/Java 侧都不再持有，仅驻留表还引用它们。
        for (int i = 0; i < DISTINCT; i++) {
            LuaString.newStr("pressure_key_" + i);
        }
        int afterGen = LuaString.managedStringCount();
        System.out.println("after generating " + DISTINCT + " distinct short strings: "
                + before + " -> " + afterGen);

        // 制造真实堆压力直到 OOM。JVM 必须在抛出 OOM 前清除所有软引用，
        //   故此处捕获到 OOM 即意味着软引用已被清除。
        boolean sawOom = false;
        java.util.ArrayList<byte[]> ballast = new java.util.ArrayList<>();
        try {
            while (true) ballast.add(new byte[1 << 20]);
        } catch (OutOfMemoryError expected) {
            sawOom = true;
        } finally {
            ballast.clear();
            ballast = null;
        }
        System.out.println("heap pressure reached OOM: " + sawOom);

        // 清除仅发生在 JVM 侧；驻留表要摘除空槽才让计数下降（对齐 C 的 sweep 时机）。
        LuaString.purgeForTest();
        int afterPressure = LuaString.managedStringCount();
        System.out.println("after heap pressure + purge: " + afterGen + " -> " + afterPressure);

        int reclaimed = afterGen - afterPressure;
        boolean ok = reclaimed > DISTINCT / 2;
        System.out.println((ok ? "  OK: " : "  FAIL: ")
                + "short strings are reclaimable under heap pressure (reclaimed "
                + reclaimed + " of " + (afterGen - before) + ")");
        if (!ok) {
            System.err.println("InternPressureTest: FAILED");
            System.exit(1);
        }
        System.out.println("InternPressureTest: PASS");
    }
}
