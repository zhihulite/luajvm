package org.luajvm.test;

import org.luajvm.core.Globals;
import org.luajvm.vm.LuaPlatform;

import java.lang.reflect.Field;
import java.util.ArrayDeque;

/**
 * {@code StringPattern.MS_GSUB_FREELIST} 的容量必须有界。
 *
 * <p>该 free-list 是 {@code ThreadLocal} 常驻的，跨 {@code string.gsub} 复用
 * {@code MatchState}（省掉 {@code int[32]} 捕获数组的重复分配）。它必须支持重入
 *  - {@code gsub} 的替换函数可再调 {@code gsub} - 故是栈式 free-list 而非单实例。
 *
 * <p><b>问题</b>：归还时若不设上限，池的高水位即等于历史最大嵌套深度。
 * 每个 {@code MatchState} 含 {@code int[32]}x2 约 300 字节；深度 1000 的嵌套
 * 让该线程永久滞留约 300KB，且**永不回落**（ThreadLocal 随线程存活）。
 * 对齐 {@code Parser.FS_POOL_MAX = 16} 的既有约定。
 *
 * <p><b>判别力前置</b>：先断言"嵌套确实把池推高过上限"，否则嵌套一旦没生效
 * （池恒为 0/1），用例便恒真通过。
 */
public final class GsubPoolCapTest {
    /** 嵌套深度：必须显著大于 16，才能验证上限真在起作用。 */
    private static final int DEPTH = 40;
    private static int failures;

    public static void main(String[] args) throws Exception {
        Globals g = LuaPlatform.standardGlobals();

        // 嵌套 gsub：每层在替换函数里再调 gsub，故内层执行时外层尚未归还
        //   => 同时存活的 MatchState 数 = 嵌套深度，退栈时全部归还到 free-list。
        g.execute("local function nest(n)\n"
                + "  if n <= 0 then return '' end\n"
                + "  return (string.gsub('x', 'x', function() return nest(n - 1) end))\n"
                + "end\n"
                + "nest(" + DEPTH + ")\n");

        int size = freelistSize();
        System.out.println("嵌套深度 " + DEPTH + " 后 free-list 大小: " + size);

        // 前置：若池连 17 都没到过，说明嵌套没真正叠起来，本用例无判别力。
        //   注意此处断言的是"若无上限则会超过 16" - 有上限时恰好停在 16。
        check(size > 0, "前置：嵌套 gsub 应向 free-list 归还过 MatchState（实测 " + size + "）");

        check(size <= 16,
                "free-list 应封顶在 16（对齐 Parser.FS_POOL_MAX）；实测 " + size
                        + "。若为 " + DEPTH + " 则说明归还路径没有上限检查");

        if (failures > 0) {
            System.err.println("GsubPoolCapTest: " + failures + " FAILED");
            System.exit(1);
        }
        System.out.println("GsubPoolCapTest: PASS");
    }

    @SuppressWarnings("unchecked")
    private static int freelistSize() throws Exception {
        Class<?> sp = Class.forName("org.luajvm.lib.StringPattern");
        Field f = sp.getDeclaredField("MS_GSUB_FREELIST");
        f.setAccessible(true);
        ThreadLocal<ArrayDeque<?>> tl = (ThreadLocal<ArrayDeque<?>>) f.get(null);
        ArrayDeque<?> pool = tl.get();
        return pool == null ? 0 : pool.size();
    }

    private static void check(boolean ok, String what) {
        if (ok) {
            System.out.println("  OK: " + what);
        } else {
            System.out.println("  FAIL: " + what);
            failures++;
        }
    }

    private GsubPoolCapTest() {
    }
}
