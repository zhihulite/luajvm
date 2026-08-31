// java-only 门禁：JavaClass 的三张 accessTypeCache 不得随「任意键」无界增长。
package org.luajvm.test;

import org.luajvm.bind.Platform;
import org.luajvm.core.Globals;

import java.lang.reflect.Field;
import java.util.HashMap;
import java.util.Map;

/**
 * 被包装的 {@code Map}/{@code List} 用互异键访问时，{@code JavaClass} 的三张
 * {@code accessTypeCache} 条目数必须保持常数。
 *
 * <p>守的缺陷形态：把 {@code TYPE_GETVALUE}/{@code TYPE_SETVALUE} 按 key 存进这三张表，
 * 而集合的 key 是<b>用户数据的任意键</b>（map key、list 下标）而非类成员名 ⇒ 条目数
 * 随互异键数单调增长，且缓存挂在 {@code JavaClass} 上、与 {@code Globals} 同寿，
 * {@code fullGC} 一条不掉。
 *
 * <p>触发面不限于显式 {@code luajava.newInstance}：{@code Coercion.toLua} 对<b>任何</b>
 * 返回 {@code Map}/{@code List} 的 Java 方法都自动 {@code JavaCollection.wrap}，
 * 故 Android 宿主里 {@code SharedPreferences.getAll()}、Bundle 转的 map 等拿到后按
 * 动态键访问即开始积累（{@code cfg['user_' .. uid]} 是典型形态）。
 *
 * <p><b>判别力前置</b>：先断言对照组（固定成员名访问）确实<b>会</b>写入缓存，
 * 证明被测路径真的走到了 {@code accessTypeCache}。否则若将来 {@code JavaCollection}
 * 提前返回覆盖全部路径，缓存恒为 0，本用例会因「没增长」而空转通过。
 */
public final class AccessCacheBoundTest {
    /** 互异键访问次数；取够大才能把线性增长与常数开销区分开。 */
    private static final int KEYS = 20_000;
    /** 容差：允许少量固定条目（成员名、size/put 等方法名）。 */
    private static final int SLACK = 64;

    private static int failures;

    public static void main(String[] args) throws Exception {
        Globals g = Platform.standardGlobals();

        // -- 判别力前置：固定成员名访问必须写入缓存，证明被测路径真的可达 --
        g.execute("SB = luajava.newInstance('java.lang.StringBuilder')\n"
                + "SB:append('x')\n"
                + "local _ = SB.length\n");
        int afterMember = total(g);
        check(afterMember > 0,
                "前置：固定成员名访问应写入 accessTypeCache（实测 " + afterMember
                        + " 条）- 若为 0 则本用例无判别力");

        // -- Map：互异键读 --
        g.execute("M = luajava.newInstance('java.util.HashMap')");
        int base = total(g);
        g.execute("for i = 1, " + KEYS + " do local _ = M['k' .. i] end");
        int afterGet = total(g);
        check(afterGet - base <= SLACK,
                "Map 互异键读 " + KEYS + " 次后缓存增量应 <= " + SLACK
                        + "（实测 +" + (afterGet - base) + "）");

        // -- Map：互异键写 --
        g.execute("for i = 1, " + KEYS + " do M['w' .. i] = i end");
        int afterSet = total(g);
        check(afterSet - afterGet <= SLACK,
                "Map 互异键写 " + KEYS + " 次后缓存增量应 <= " + SLACK
                        + "（实测 +" + (afterSet - afterGet) + "）");

        // -- List：整数下标读写 --
        g.execute("L = luajava.newInstance('java.util.ArrayList')\n"
                + "for i = 1, 64 do L:add(i) end\n"
                + "for i = 1, " + KEYS + " do local _ = L[i % 64] end\n"
                + "for i = 1, " + KEYS + " do L[i % 64] = i end\n");
        int afterList = total(g);
        check(afterList - afterSet <= SLACK,
                "List 下标读写各 " + KEYS + " 次后缓存增量应 <= " + SLACK
                        + "（实测 +" + (afterList - afterSet) + "）");

        // -- 语义未被破坏：集合读写、方法调用、getter 都要正常 --
        g.execute("local m = luajava.newInstance('java.util.HashMap')\n"
                + "m:put('a', 1)\n"
                + "m['b'] = 2\n"
                + "assert(m.a == 1, 'Map 读已存在键')\n"
                + "assert(m['b'] == 2, 'Map 写入后读回')\n"
                + "assert(m:size() == 2, 'Map 方法调用 size()')\n"
                + "assert(m.nosuchkey == nil, 'Map 不存在的键应为 nil')\n"
                + "local l = luajava.newInstance('java.util.ArrayList')\n"
                + "l:add('x') l:add('y')\n"
                + "assert(l[0] == 'x', 'List 下标读')\n"
                + "l[1] = 'z'\n"
                + "assert(l[1] == 'z', 'List 下标写')\n"
                + "assert(l:size() == 2, 'List 方法调用 size()')\n"
                + "local sb = luajava.newInstance('java.lang.StringBuilder')\n"
                + "sb:append('hi')\n"
                + "assert(sb:toString() == 'hi', '非集合对象方法调用')\n"
                + "assert(sb:length() == 2, '非集合对象 getter')\n");
        check(true, "集合读写 / 方法调用 / getter 语义均正常");

        if (failures > 0) {
            System.out.println("AccessCacheBoundTest FAILED: " + failures + " 处");
            System.exit(1);
        }
        System.out.println("AccessCacheBoundTest OK");
    }

    /** 三张 accessTypeCache 在全部 JavaClass 上的条目总数。 */
    private static int total(Globals g) throws Exception {
        Object cache = field(g, "javaClassCache");
        if (!(cache instanceof Map<?, ?> m)) return -1;
        int sum = 0;
        for (Object jc : m.values()) {
            sum += size(jc, "getAccessTypeCache")
                    + size(jc, "memberAccessTypeCache")
                    + size(jc, "setAccessTypeCache");
        }
        return sum;
    }

    private static int size(Object owner, String name) {
        try {
            Object v = field(owner, name);
            return v instanceof HashMap<?, ?> hm ? hm.size() : 0;
        } catch (Exception e) {
            return 0;
        }
    }

    private static void check(boolean ok, String what) {
        System.out.println((ok ? "  OK   " : "  FAIL ") + what);
        if (!ok) failures++;
    }

    private static Object field(Object o, String n) throws Exception {
        for (Class<?> c = o.getClass(); c != null; c = c.getSuperclass()) {
            try {
                Field f = c.getDeclaredField(n);
                f.setAccessible(true);
                return f.get(o);
            } catch (NoSuchFieldException ignored) {
                // 继续找父类
            }
        }
        throw new NoSuchFieldException(n + " on " + o.getClass());
    }

    private AccessCacheBoundTest() {
    }
}
