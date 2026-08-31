// java-only 门禁：C 闭包（LUA_VCCL）的 upvalue 槽必须被 GC 遍历。
package org.luajvm.test;

import org.luajvm.core.Globals;
import org.luajvm.core.LuaValue;
import org.luajvm.vm.LuaPlatform;

import java.lang.reflect.Field;
import java.util.List;

/**
 * {@code LuaCClosure.upvalue[]} 里的对象必须被 GC 标记。
 *
 * <p>{@code propagateOne} 的 {@code LUA_VCCL} 分支只遍历 {@code gcRefs()}，而
 * {@code LuaCClosure} 把引用存在 {@code upvalue[]} 里且未覆写 {@code gcRefs()}
 * （默认返回 {@code NOVALS}）⇒ 这些对象在标记阶段完全不可见，被 sweep 判死：
 * 从 {@code allTables}/{@code allUserdata}/{@code longStrings} 摘除后永久脱离 GC
 * 管理（此后其 {@code __gc} 不再登记、内存记账不再计入），且 {@code __gc} 会在对象
 * 仍 Lua 可达时被提前调用。
 *
 * <p>{@code string.gmatch} 的迭代器是唯一的生产侧 {@code LuaCClosure}，其
 * {@code upvalue[0..2]} 持有被搜索串、模式串与 {@code GMatchState} userdata。
 *
 * <p>闭包本身登记正常、每周期正常复位为白，失效点是分支<em>执行了但读错了字段</em>；
 * {@code ownerGlobals != null} 之类的断言拦不住它 - 那个断言写作
 * {@code refs.length == 0 || ...}，对返回空数组的类恒真，是空转的。
 *
 * <p><b>判别力前置</b>：先断言 victim 确实只经 upvalue 可达（在 allTables 里、
 * 脚本侧引用已断开），否则一旦构造失手（victim 仍被全局表持有）用例便恒真通过。
 */
public final class CClosureUpvalueMarkTest {
    private static int failures;

    public static void main(String[] args) throws Exception {
        Globals g = LuaPlatform.standardGlobals();
        Object gc = field(g, "gc");
        List<?> allTables = (List<?>) field(gc, "allTables");
        List<?> longStrings = (List<?>) field(gc, "longStrings");

        // CFN 是 LuaCClosure（gmatch 迭代器）；LFN 是 LuaClosure，作对照组。
        // 两个 victim 都带 __gc，且只经各自闭包的 upvalue 可达。
        g.execute("CFN = string.gmatch(('a b'):upper(), '%' .. 'a+')\n"
                + "local hidden\n"
                + "LFN = function() return hidden end\n"
                + "VICTIM_C = setmetatable({tag='C'}, {__gc=function() GC_C = true end})\n"
                + "VICTIM_L = setmetatable({tag='L'}, {__gc=function() GC_L = true end})\n"
                // gmatch 迭代器只有 3 槽：upvalue[3] 越界，upvalue[2]（GMatchState userdata
                //   槽）会让迭代器不可用，故占 upvalue[1]（模式串槽）：迭代器完成首次匹配前不读它。
                + "debug.setupvalue(CFN, 2, VICTIM_C)\n"
                + "debug.setupvalue(LFN, 1, VICTIM_L)\n"
                + "GC_C = false; GC_L = false\n");

        LuaValue cfn = g.get("CFN");
        check(cfn.getClass().getSimpleName().equals("GmatchAuxFn"),
                "前置：CFN 应是 LuaCClosure 子类（实测 " + cfn.getClass().getName() + "）");

        LuaValue victimC = (LuaValue) ((Object[]) field(cfn, "upvalue"))[1];
        LuaValue victimL = g.get("VICTIM_L");

        // 断开脚本侧强引用，只留 upvalue 一条 Lua 可达路径。
        g.execute("VICTIM_C = nil; VICTIM_L = nil");

        // 前置：两个 victim 都必须在 allTables 中，否则「被摘除」无从判别。
        check(identityContains(allTables, victimC), "前置：victim C 应在 allTables 中");
        check(identityContains(allTables, victimL), "前置：victim L 应在 allTables 中（对照组）");

        int longStrBefore = longStrings.size();

        // 两轮完整收集：漏标对象第二轮必被 isdead 判死。
        g.execute("collectgarbage(); collectgarbage()");

        // 核心断言：仍经 upvalue 可达 ⇒ 必须仍受 GC 管理、__gc 不得运行。
        check(identityContains(allTables, victimC),
                "C 闭包 upvalue 里的表必须仍在 allTables 中（漏标会把它摘除）");
        check(!g.get("GC_C").toboolean(),
                "C 闭包 upvalue 里的对象仍可达，__gc 不得被调用");

        // 对照组：证明差异来自 LUA_VCCL 分支而非测试构造本身。
        check(identityContains(allTables, victimL),
                "对照：Lua 闭包 upvalue 里的表应仍在 allTables 中");
        check(!g.get("GC_L").toboolean(),
                "对照：Lua 闭包 upvalue 里的对象 __gc 不得被调用");

        // gmatch 的被搜索串/模式串是运行期 concat 出来的长串，唯一持有者是 upvalue。
        check(longStrings.size() >= longStrBefore - 1,
                "C 闭包 upvalue 持有的长串不应被成批摘除（before=" + longStrBefore
                        + " after=" + longStrings.size() + "）");

        // 迭代器功能仍须正常（Java 强引用兜底，故这不是崩溃而是保真事故）。
        g.execute("local w = CFN(); assert(w == 'A' or w == 'B', 'gmatch 迭代器应仍可用')");

        stackOnlyIteratorSurvivesGC(g);

        if (failures > 0) {
            System.out.println("CClosureUpvalueMarkTest FAILED: " + failures + " 处");
            System.exit(1);
        }
        System.out.println("CClosureUpvalueMarkTest OK");
    }

    /**
     * 迭代器<b>只在栈上</b>（不进任何表）时跨完整 GC 周期仍须可用。
     *
     * <p>覆盖上面那组断言测不到的另一半缺陷：{@code GmatchAuxFn} 若不 {@code bindGlobals}
     * 就不进 {@code allFunctions}，{@code sweepFunctionsByColor} 便不为它复位颜色 ⇒ 首次
     * 标黑后永久 BLACK ⇒ {@code markValue} 的 {@code iswhite} 短路使 {@code LUA_VCCL}
     * 分支再不执行 ⇒ 即便 {@code gcRefs()} 已覆写，upvalue 子图照样漏标。
     *
     * <p><b>必须不进表</b>：{@code LuaTable.set} 会对写入的 {@code LuaFunction} 传播
     * {@code bindGlobals}，故只要把迭代器赋给全局或任何表，它就被顺带登记了，缺陷被掩盖。
     * 真实场景 {@code for w in string.gmatch(s, p) do} 的迭代器就只活在 for 的控制槽里。
     */
    private static void stackOnlyIteratorSurvivesGC(Globals g) {
        // 形态一：边迭代边完整收集
        g.execute("local s = ('x'):rep(3) .. ' ' .. ('y'):rep(3) .. ' ' .. ('z'):rep(3)\n"
                + "local n = 0\n"
                + "for w in string.gmatch(s, '%' .. 'a+') do\n"
                + "  n = n + 1\n"
                + "  collectgarbage(); collectgarbage()\n"
                + "  assert(#w == 3, 'word ' .. n .. ' 长度应为 3，实为 ' .. #w)\n"
                + "end\n"
                + "STACK_N = n\n");
        check(g.get("STACK_N").toint() == 3,
                "栈上迭代器边迭代边 GC 应得全部 3 个匹配（实测 "
                        + g.get("STACK_N").toint() + "）");

        // 形态二：迭代器存 local，跨多轮 GC 后再取值
        g.execute("local s2 = ('p'):rep(4) .. ' ' .. ('q'):rep(4)\n"
                + "local it = string.gmatch(s2, '%' .. 'a+')\n"
                + "for i = 1, 8 do collectgarbage() end\n"
                + "local a = it()\n"
                + "for i = 1, 8 do collectgarbage() end\n"
                + "local b = it()\n"
                + "STACK_AB = tostring(a) .. ',' .. tostring(b)\n");
        check("pppp,qqqq".equals(g.get("STACK_AB").toJavaString()),
                "栈上迭代器跨 16 次 GC 后仍应返回 pppp,qqqq（实测 "
                        + g.get("STACK_AB").toJavaString() + "）");
    }

    private static boolean identityContains(List<?> l, Object o) {
        for (Object x : l) if (x == o) return true;
        return false;
    }

    private static void check(boolean cond, String what) {
        if (cond) {
            System.out.println("  OK   " + what);
        } else {
            failures++;
            System.out.println("  FAIL " + what);
        }
    }

    private static Object field(Object o, String name) throws Exception {
        for (Class<?> c = o.getClass(); c != null; c = c.getSuperclass()) {
            try {
                Field f = c.getDeclaredField(name);
                f.setAccessible(true);
                return f.get(o);
            } catch (NoSuchFieldException ignored) {
                // 继续找父类
            }
        }
        throw new NoSuchFieldException(name + " on " + o.getClass());
    }
}
