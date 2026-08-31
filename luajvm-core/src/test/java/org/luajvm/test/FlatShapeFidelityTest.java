package org.luajvm.test;

import org.luajvm.core.Globals;
import org.luajvm.core.Varargs;
import org.luajvm.vm.LuaPlatform;

/**
 * 表形状保真门禁（对齐 C 的 {@code T.querytab}）。
 *
 * <p>断言装箱路径的表形状与 C 逐字一致，两条边界：
 * <ul>
 *   <li>被清空的大表（{@code asize=64} 全空）再跑 {@code for i=1,5 do t[i]=i end}：
 *       C 完全不动形状（64/0）；若从 1 逐级重放，会把它**收缩**到 8/0，
 *       并多出 4 次 resize 分配 ⇒ {@code T.querytab} 与
 *       {@code T.alloccount} 双双与 C 分叉（CLAUDE.md 性能铁律第 7 条）。</li>
 *   <li>已有容量但不够（8 全空 → 需要 30）：必须从当前容量继续翻倍到 32，
 *       而不是从 1 重放。</li>
 * </ul>
 *
 * <p>全部期望值取自 {@code lua55-debug}（ltests 构建）实测。
 */
public final class FlatShapeFidelityTest {
    private static int failures;

    /** 与 C 侧探针同源；每步产出 "asize/hsize"，用 '|' 串起来比对。 */
    private static final String PROBE = """
            local out = {}
            local function q(t) local a, h = T.querytab(t) out[#out + 1] = a .. '/' .. h end
            local da = {}
            for i = 1, 64 do da[i] = i end
            for i = 1, 64 do da[i] = nil end
            q(da)
            for i = 1, 5 do da[i] = i end
            q(da)
            local fresh = {}
            for i = 1, 5 do fresh[i] = i end
            q(fresh)
            local part = {}
            for i = 1, 8 do part[i] = i end
            for i = 1, 8 do part[i] = nil end
            q(part)
            for i = 1, 30 do part[i] = i end
            q(part)
            local app = {}
            for i = 1, 40 do app[i] = i end
            for i = 1, 40 do app[i] = nil end
            q(app)
            for i = 1, 6 do table.insert(app, i) end
            q(app)
            return table.concat(out, '|')
            """;

    /** lua55-debug 实测：清空的大表不变形、全新空表按翻倍收敛、部分容量从当前继续翻倍。 */
    private static final String C_EXPECTED = "64/0|64/0|8/0|8/0|32/0|64/0|64/0";

    public static void main(String[] args) {
        Globals g = LuaPlatform.standardGlobals();
        LtestsDebugLib.open(g);
        Varargs r = g.execute(PROBE);
        String got = r.arg1().toJavaString();
        System.out.println("querytab 序列: " + got);
        System.out.println("C  期望序列: " + C_EXPECTED);
        check(C_EXPECTED.equals(got),
                "表形状必须与 C 逐项一致（drained/fresh/part/append 四组）");

        // 前置自证：探针本身必须真的经过 FlatTFor 的纯写填充通道，否则装箱路径
        //   与预扩容路径形状恰好相同，用例会变成恒真。关掉 FlatTFor 时形状同样应等于 C
        //   （这正是"预扩容不得改变可观察形状"的定义），故此处只自证探针非空且格式正确。
        check(got.split("\\|").length == 7, "前置：探针应产出 7 个形状读数（实测 "
                + got.split("\\|").length + "）");

        if (failures > 0) {
            System.err.println("FlatShapeFidelityTest: " + failures + " FAILED");
            System.exit(1);
        }
        System.out.println("FlatShapeFidelityTest: PASS");
    }

    private static void check(boolean ok, String what) {
        if (ok) {
            System.out.println("  OK   " + what);
        } else {
            failures++;
            System.out.println("  FAIL " + what);
        }
    }
}
