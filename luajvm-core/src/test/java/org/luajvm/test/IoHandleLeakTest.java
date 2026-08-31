package org.luajvm.test;

import org.luajvm.core.Globals;
import org.luajvm.lib.IoFile;
import org.luajvm.vm.LuaPlatform;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * java-only：打开的文件句柄登记表必须有界。
 *
 * <p>{@code IoFile.openHandles} 是 Java 独有结构（C 的 {@code LStream} 仅是 userdata，
 * 由 Lua GC 释放），存在的理由仅 {@code closeHandlesForName} - Windows 不允许删除
 * 仍被打开的文件，故 {@code os.remove}/{@code os.rename} 必须能先关掉它们。
 *
 * <p>无上限则**只增不减**：{@code close()} 若仅置空流字段而不摘除表项，每个开过的句柄
 * 连同两个 1KB 缓冲永久滞留，反复开关文件的脚本无界增长；而已关闭的句柄对
 * {@code closeHandlesForName} 毫无用处（已关的无需再关）。
 *
 * <p>两条路径都要验证：显式 {@code f:close()}，以及丢弃引用后由 {@code __gc} 关闭
 * （对齐 C 的 {@code liolib.c : f_gc}）。
 *
 * <p>第三条是**跨状态**路径：每个 {@code Globals} 都建 stdin/stdout/stderr 三个句柄，
 * 而标准流的 {@code close()} 直接返回错误、从不摘除自己 ⇒ 登记标准流就会随状态数
 * 线性增长且永不回落。所以标准流根本不登记——本表唯一的
 * 消费者 {@code closeHandlesForName} 本就显式跳过它们。
 */
public final class IoHandleLeakTest {
    private static int failures;
    private static final int ROUNDS = 300;
    private static final int STATES = 100;

    public static void main(String[] args) throws IOException {
        Path dir = Files.createTempDirectory("luajvm_io_leak");
        try {
            Globals g = LuaPlatform.standardGlobals();
            // 标准流不登记（见类注释第三条），故建完状态计数仍应为 0
            int baseline = IoFile.openHandleCount();
            System.out.println("baseline openHandles=" + baseline);
            check("standard streams are not tracked (baseline " + baseline + ", expected 0)",
                    baseline == 0);

            String base = dir.toAbsolutePath().toString().replace("\\", "/");

            // 路径一：显式 close
            g.execute("for i = 1, " + ROUNDS + " do"
                    + "  local f = assert(io.open('" + base + "/explicit_'..i, 'w'))"
                    + "  f:write('x') f:close()"
                    + " end");
            int afterExplicit = IoFile.openHandleCount();
            check("explicit close removes the handle (now " + afterExplicit
                            + ", opened " + ROUNDS + ")",
                    afterExplicit == baseline);

            // 路径二：丢弃引用不关闭，由 __gc 关闭（同 C 的 f_gc）
            g.execute("for i = 1, " + ROUNDS + " do"
                    + "  local f = assert(io.open('" + base + "/dropped_'..i, 'w'))"
                    + "  f:write('x')"
                    + " end"
                    + " collectgarbage() collectgarbage()");
            int afterDropped = IoFile.openHandleCount();
            check("__gc closes dropped handles and removes them (now " + afterDropped
                            + ", opened " + ROUNDS + ")",
                    afterDropped == baseline);

            // closeHandlesForName 仍须能关掉真正打开的句柄（os.remove 在 Windows 依赖它）
            g.execute("_G.held = assert(io.open('" + base + "/held', 'w'))");
            int withHeld = IoFile.openHandleCount();
            check("an open handle is tracked (now " + withHeld + ")", withHeld == baseline + 1);
            g.execute("assert(os.remove('" + base + "/held'))");
            check("os.remove closed the tracked handle (now " + IoFile.openHandleCount() + ")",
                    IoFile.openHandleCount() == baseline);

            // 路径三：跨状态。宿主反复建状态再丢弃（Android 每 Activity 一个状态的形态），
            //   进程级表不得随状态数增长。缺陷态下每个状态净留 3 条。
            for (int i = 0; i < STATES; i++) {
                LuaPlatform.standardGlobals().execute("return 1");
            }
            System.gc();
            int afterStates = IoFile.openHandleCount();
            check("creating " + STATES + " Globals does not grow the table (now "
                            + afterStates + ", would be +" + (STATES * 3) + " if std streams were tracked)",
                    afterStates == baseline);

            if (failures > 0) {
                System.err.println("IoHandleLeakTest: " + failures + " FAILED");
                System.exit(1);
            }
            System.out.println("IoHandleLeakTest: PASS");
        } finally {
            deleteRecursively(dir.toFile());
        }
    }

    private static void deleteRecursively(File f) {
        File[] kids = f.listFiles();
        if (kids != null) for (File k : kids) deleteRecursively(k);
        //noinspection ResultOfMethodCallIgnored
        f.delete();
    }

    static void check(String name, boolean ok) {
        System.out.println((ok ? "  OK: " : "  FAIL: ") + name);
        if (!ok) failures++;
    }
}
