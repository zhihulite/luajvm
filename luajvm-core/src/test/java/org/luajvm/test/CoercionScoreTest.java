// java-only 门禁：钉住 Coercion.scoreParams 的重载打分与基元零值的**精确装箱类型**。
package org.luajvm.test;

import org.luajvm.bind.Coercion;
import org.luajvm.core.LuaString;
import org.luajvm.core.LuaTable;
import org.luajvm.core.LuaValue;
import org.luajvm.core.Varargs;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;
import java.util.Locale;

/**
 * java-only：钉住 {@code org.luajvm.android.proxy.LuaClassProxy} 依赖的两条 Coercion 契约。
 *
 * <p>{@code LuaClassProxy} 在 {@code luajvm-android}（Android 库）里，其类不在本模块
 * classpath 上，无法直接测；但它的两处缺陷都是**误解了 Coercion 的语义**造成的，
 * 把语义钉在这里，等价于给那两处修复上锁：
 *
 * <ol>
 *   <li><b>重载选择必须用分数，不能用 {@code isInstance}</b>。
 *       {@code Class.isInstance} 在基元类型上**恒返回 false**（JDK 规定），
 *       所以"先转成 Object 再 {@code paramType.isInstance(arg)}"的写法让任何带
 *       {@code int/long/boolean} 形参的重载永远匹配不上，退化成"取声明顺序第一个"。</li>
 *   <li><b>基元返回类型的零值必须是精确装箱类型</b>。动态代理生成的字节码会
 *       {@code ((Long) r).longValue()}，给 {@code Integer} 就 {@code ClassCastException}。
 *       用 JDK {@link Proxy} 现场复现这个崩溃，再证明
 *       {@code Coercion.toJava(NIL, long.class)} 的返回值不崩。</li>
 * </ol>
 */
public final class CoercionScoreTest {

    private static int checks;
    private static int failures;

    /** 被测重载形态：同名不同签名，且**基元在前**——正是打分缺陷暴露的形态。 */
    @SuppressWarnings("unused")
    public static final class Overloads {
        public void f(int a) {
        }

        public void f(String a) {
        }

        public void g(long a) {
        }

        public void g(double a) {
        }
    }

    /** 返回 {@code long} 的接口：用来复现"装箱类型不对就崩"。 */
    public interface LongReturn {
        long value();
    }

    public static void main(String[] args) throws Exception {
        scoring();
        offsetSemantics();
        primitiveZeroBoxing();
        proxyUnboxing();
        bytesParam();

        System.out.println(failures == 0
                ? "CoercionScoreTest: PASS (" + checks + " checks)"
                : "CoercionScoreTest: FAIL");
        if (failures != 0) throw new AssertionError(failures + " 项断言失败");
    }

    /**
     * Lua 字符串必须能传给 {@code byte[]} 形参。
     *
     * <p>Lua 的字符串**就是字节串**，`MessageDigest.digest(byte[])`、
     * `OutputStream.write(byte[])` 这类签名在 Lua 侧只能靠字符串来喂。
     * 若 {@code getCoercion(byte[].class)} 产出只认表与 userdata 的
     * {@code ArrayCoercion(byte)}，字符串一律 {@code SCORE_UNCOERCIBLE}
     * ⇒ 报 "no coercible public method"。
     */
    private static void bytesParam() throws Exception {
        LuaValue str = LuaString.newStr("abc");
        LuaTable tbl = new LuaTable();
        tbl.set(1, LuaValue.valueOf(1));
        tbl.set(2, LuaValue.valueOf(2));

        var adapter = Coercion.getCoercion(byte[].class);
        int sStr = adapter.score(str);
        int sTbl = adapter.score(tbl);
        check("Lua 字符串 -> byte[] 可强转", sStr < Coercion.SCORE_UNCOERCIBLE,
                "score=" + sStr + " via " + adapter);
        check("Lua 表 -> byte[] 仍可强转（不能为了字符串把表弄坏）",
                sTbl < Coercion.SCORE_UNCOERCIBLE, "score=" + sTbl);

        Object outStr = Coercion.toJava(str, byte[].class);
        check("字符串转出的 byte[] 内容正确",
                outStr instanceof byte[] b && b.length == 3
                        && b[0] == 'a' && b[1] == 'b' && b[2] == 'c',
                describeBytes(outStr));
        Object outTbl = Coercion.toJava(tbl, byte[].class);
        check("表转出的 byte[] 内容正确",
                outTbl instanceof byte[] b && b.length == 2 && b[0] == 1 && b[1] == 2,
                describeBytes(outTbl));

        // 重载消歧：String 形参必须严格优于 byte[]，否则 f(String)/f(byte[]) 变成看声明顺序
        int toString = Coercion.getCoercion(String.class).score(str);
        check("同一个字符串：String 形参严格优于 byte[]", toString < sStr,
                "String=" + toString + " < byte[]=" + sStr);

        // 端到端：MessageDigest.digest(byte[]) 是典型签名
        var digest = java.security.MessageDigest.class.getMethod("digest", byte[].class);
        int score = Coercion.scoreParams(digest.getParameterTypes(),
                Varargs.of(new LuaValue[]{str}), 1);
        check("MessageDigest.digest(byte[]) 用 Lua 字符串可选中",
                score < Coercion.SCORE_UNCOERCIBLE, "score=" + score);

        bytesAdapterRetention(adapter);
    }

    /**
     * 新增的 {@code byte[]} 适配器不得引入 ClassLoader 滞留。
     *
     * <p>它被存进**进程级强引用**的 {@code LUA_TO_JAVA_COERCIONS}（键 {@code byte[].class}
     * 是 bootstrap 类，恒可缓存），所以只要它自己不持有可回收 loader 的 {@code Class}，
     * 就不会引入 ClassLoader 滞留。这条断言把这个结构性前提钉住 ——
     * 将来给它加一个 {@code Class} 字段就会 FAIL。
     */
    private static void bytesAdapterRetention(Coercion.Adapter adapter) throws Exception {
        check("byte[] 适配器被缓存（两次取到同一实例，不会每次新建）",
                Coercion.getCoercion(byte[].class) == adapter,
                "同一实例=" + (Coercion.getCoercion(byte[].class) == adapter));
        check("前提：byte[].class 是 bootstrap 类（键本身不钉 loader）",
                byte[].class.getClassLoader() == null, "loader=null");

        // 递归扫适配器持有的每个 Class 字段：loader 必须为 null（bootstrap）
        var bad = new java.util.ArrayList<String>();
        var seen = new java.util.IdentityHashMap<Object, Boolean>();
        scanForClasses(adapter, bad, seen, 0);
        check("byte[] 适配器不持有任何可回收 loader 的 Class", bad.isEmpty(),
                bad.isEmpty() ? "扫过的对象数=" + seen.size() : "违规字段：" + bad);
    }

    private static void scanForClasses(Object o, java.util.List<String> bad,
                                       java.util.Map<Object, Boolean> seen, int depth)
            throws Exception {
        if (o == null || depth > 4 || seen.put(o, Boolean.TRUE) != null) return;
        if (o instanceof Class<?> c) {
            if (c.getClassLoader() != null) bad.add(c.getName() + " (loader=" + c.getClassLoader() + ")");
            return;
        }
        for (var f : o.getClass().getDeclaredFields()) {
            if (f.getType().isPrimitive() || java.lang.reflect.Modifier.isStatic(f.getModifiers())) continue;
            try {
                f.setAccessible(true);
            } catch (RuntimeException ignored) {
                continue;
            }
            scanForClasses(f.get(o), bad, seen, depth + 1);
        }
    }

    private static String describeBytes(Object o) {
        if (!(o instanceof byte[] b)) return o == null ? "null" : o.getClass().getSimpleName();
        return "byte[" + b.length + "] " + java.util.Arrays.toString(b);
    }

    /** 重载打分：Lua 整数必须选中基元形参的那个重载。 */
    private static void scoring() throws Exception {
        Varargs one = Varargs.of(new LuaValue[]{LuaValue.valueOf(7)});
        Varargs str = Varargs.of(new LuaValue[]{LuaString.newStr("hi")});

        int fInt = Coercion.scoreParams(new Class<?>[]{int.class}, one, 1);
        int fStr = Coercion.scoreParams(new Class<?>[]{String.class}, one, 1);
        check("Lua 整数 -> int 形参最优（0 分）", fInt == 0, "score(int)=" + fInt);
        check("Lua 整数 -> String 形参不可强转", fStr >= Coercion.SCORE_UNCOERCIBLE,
                "score(String)=" + fStr);
        check("整数实参下 int 严格优于 String", fInt < fStr, fInt + " < " + fStr);

        int sInt = Coercion.scoreParams(new Class<?>[]{int.class}, str, 1);
        int sStr = Coercion.scoreParams(new Class<?>[]{String.class}, str, 1);
        check("Lua 字符串 -> String 形参严格优于 int", sStr < sInt, sStr + " < " + sInt);

        // long vs double：Lua 整数应优先 long（整数语义），不该掉到浮点重载
        int gLong = Coercion.scoreParams(new Class<?>[]{long.class}, one, 1);
        int gDouble = Coercion.scoreParams(new Class<?>[]{double.class}, one, 1);
        check("Lua 整数 -> long 严格优于 double", gLong < gDouble, gLong + " < " + gDouble);

        // 用真实反射方法跑一遍"按分数选"，并对照"取声明顺序第一个"的退化形态
        var ms = Overloads.class.getMethods();
        java.lang.reflect.Method bestForInt = null, firstArity = null;
        int best = Coercion.SCORE_UNCOERCIBLE;
        for (var m : ms) {
            if (!m.getName().equals("f") || m.getParameterCount() != 1) continue;
            if (firstArity == null) firstArity = m;
            int sc = Coercion.scoreParams(m.getParameterTypes(), one, 1);
            if (sc < best) {
                best = sc;
                bestForInt = m;
            }
        }
        check("按分数选：f(7) 命中 f(int)",
                bestForInt != null && bestForInt.getParameterTypes()[0] == int.class,
                "选中 " + (bestForInt == null ? "null" : bestForInt.getParameterTypes()[0]));
        check("前提：反射的重载顺序不保证，故'取第一个'本就不可靠",
                firstArity != null, "firstArity=" + (firstArity == null ? "null"
                        : firstArity.getParameterTypes()[0].getSimpleName()));
    }

    /** off 语义：{@code paramTypes[0]} 对应 {@code args.arg(off)}。 */
    private static void offsetSemantics() {
        // arg1 = 方法名字符串（Dispatch 形态），arg2 = 真正的实参
        Varargs a = Varargs.of(new LuaValue[]{LuaString.newStr("g"), LuaValue.valueOf(42)});
        int withOff2 = Coercion.scoreParams(new Class<?>[]{int.class}, a, 2);
        int withOff1 = Coercion.scoreParams(new Class<?>[]{int.class}, a, 1);
        check("off=2 读到整数实参（可强转）", withOff2 == 0, "score=" + withOff2);
        check("off=1 读到方法名字符串（不可强转）—— 错位一格就选错重载",
                withOff1 >= Coercion.SCORE_UNCOERCIBLE, "score=" + withOff1);
    }

    /** 基元零值必须是精确装箱类型（{@code def()} 修复所依赖的契约）。 */
    private static void primitiveZeroBoxing() {
        Object[][] cases = {
                {boolean.class, Boolean.class}, {byte.class, Byte.class},
                {char.class, Character.class}, {short.class, Short.class},
                {int.class, Integer.class}, {long.class, Long.class},
                {float.class, Float.class}, {double.class, Double.class},
        };
        for (Object[] c : cases) {
            Class<?> prim = (Class<?>) c[0];
            Class<?> box = (Class<?>) c[1];
            Object v = Coercion.toJava(LuaValue.NIL, prim);
            check("toJava(NIL, " + prim.getName() + ") 精确装箱为 " + box.getSimpleName(),
                    v != null && v.getClass() == box,
                    v == null ? "null" : v.getClass().getSimpleName() + " = " + v);
        }
        check("toJava(NIL, 引用类型) 仍为 null",
                Coercion.toJava(LuaValue.NIL, Object.class) == null, "null");
    }

    /** 现场复现"装箱类型不对就崩"，并证明 Coercion 的零值不崩。 */
    private static void proxyUnboxing() {
        check("装箱类型错（Integer 冒充 long）时代理必崩",
                throwsCce(Integer.valueOf(0)), "抛出 ClassCastException");
        Object ok = Coercion.toJava(LuaValue.NIL, long.class);
        check("Coercion 的 long 零值可被代理正常拆箱", !throwsCce(ok), "未抛异常");
    }

    private static boolean throwsCce(Object ret) {
        InvocationHandler h = (p, m, a) -> ret;
        LongReturn proxy = (LongReturn) Proxy.newProxyInstance(
                CoercionScoreTest.class.getClassLoader(),
                new Class<?>[]{LongReturn.class}, h);
        try {
            proxy.value();
            return false;
        } catch (ClassCastException e) {
            return true;
        }
    }

    private static void check(String what, boolean ok, String detail) {
        checks++;
        if (!ok) failures++;
        System.out.printf(Locale.ROOT, "  %-4s %s。实测：%s%n", ok ? "OK" : "FAIL", what, detail);
    }

    private CoercionScoreTest() {
    }
}
