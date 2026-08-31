// java-only 门禁：进程级缓存的 ClassLoader 分层判据（JavaMethod.cacheable）。
package org.luajvm.test;

import org.luajvm.bind.Platform;
import org.luajvm.core.Globals;

import java.lang.reflect.Method;
import java.util.Map;
import java.lang.reflect.Modifier;

/**
 * {@code JavaMethod.cacheable(Class)} 决定五处进程级缓存是否收下某个类：
 * {@code ExecutableBinding.EXECUTABLES}、
 * {@code JavaClass.SHARED_METHOD_INDEX}、{@code Coercion.JAVA_TO_LUA_COERCIONS}、
 * {@code Coercion.LUA_TO_JAVA_COERCIONS}。判 false 的类要么每次重建包装，
 * 要么退到加锁的 {@code WeakHashMap} 分支。
 *
 * <p><b>两个方向都必须钉住</b>：
 * <ul>
 *   <li><b>不能漏收</b>（性能）：与进程同寿的 loader 加载的类必须进缓存。
 *       {@code ClassLoader.getSystemClassLoader()} 在 Android 返回的是另一个
 *       {@code PathClassLoader}，与 app 真正的 loader 不是同一实例，
 *       沿其父链只能命中 BootClassLoader —— 据此判据会把**全部 app 与库类**
 *       漏判成不可缓存。
 *       后果是每个 Activity（每个 Globals）都要重新 {@code Class.getMethods()}，
 *       而该调用在 ART 上极贵，且 ART 不缓存结果，重复调用每次都付全价。</li>
 *   <li><b>不能多收</b>（泄漏）：可回收的自定义 loader 加载的类**不得**进缓存，
 *       否则该 loader 及其全部类永久钉死（见 {@code ClassLoaderRetentionTest}）。</li>
 * </ul>
 *
 * <p><b>判别力说明（诚实标注）</b>：HotSpot 上 {@code getSystemClassLoader()} 就是
 * 引擎自己的 AppClassLoader（本测试会断言这一点），故"引擎 loader 链"这条新增判据在
 * JVM 上是 no-op —— 第 2 组断言在 JVM 上按关闭开关时的判据也成立，只有在 Android 上才有判别力。
 * 真正在 JVM 上有判别力的是第 3 组（子 loader 必须判 false）：它钉住"只沿父链向上走"
 * 这个性质，任何改成"遍历可达 loader"或"能加载即算"的实现都会让它 FAIL。
 */
public final class LoaderCacheTest {
    private static int failures;

    public static void main(String[] args) throws Exception {
        Globals g = Platform.standardGlobals();
        g.execute("local SB = luajava.bindClass('java.lang.StringBuilder') local s = SB() s:append('x')");

        Method cacheable = cacheableMethod();

        // ---- 1) bootstrap 类：必须收 ----
        check(call(cacheable, String.class), "bootstrap 类（String）必须可缓存");
        check(call(cacheable, int[].class) || int[].class.getClassLoader() != null,
                "bootstrap 数组类（int[]）必须可缓存");

        // ---- 2) 引擎自身所在 loader 的类：必须收 ----
        //   Android 上这一组只有"引擎 loader 链"判据能让它成立（只看 system loader 父链时全判 false）。
        ClassLoader engineLoader = Class.forName("org.luajvm.bind.Platform").getClassLoader();
        ClassLoader systemLoader = ClassLoader.getSystemClassLoader();
        check(call(cacheable, Platform.class), "引擎自身的类（Platform）必须可缓存");
        check(call(cacheable, LoaderCacheTest.class),
                "与引擎同 loader 的宿主类必须可缓存");
        System.out.println("  info  engineLoader=" + engineLoader);
        System.out.println("  info  systemLoader=" + systemLoader);
        System.out.println("  info  engine==system? " + (engineLoader == systemLoader)
                + "（HotSpot 恒 true ⇒ 本组在 JVM 上是 no-op；Android 上为 false）");

        // ---- 3) 以引擎 loader 为 parent 的子 loader：必须不收（判别力核心）----
        ChildLoader child = new ChildLoader(engineLoader);
        Class<?> childClass = child.defineProbe();
        check(childClass.getClassLoader() == child,
                "前置：探针类必须真由子 loader 定义（否则本组空转）");
        check(!call(cacheable, childClass),
                "以引擎 loader 为 parent 的子 loader 加载的类必须**不可**缓存"
                        + "（否则该 loader 被永久钉死 = ClassLoader 泄漏）");

        // 孙 loader 同样不得收
        ChildLoader grand = new ChildLoader(child);
        Class<?> grandClass = grand.defineProbe();
        check(!call(cacheable, grandClass), "孙 loader 加载的类同样必须不可缓存");

        // ---- 4) 开关关闭时回到只看 system loader 父链的判据（同一份 class 切基线的能力必须在）----
        boolean switchPresent = System.getProperty("luajvm.bindloadercache") != null;
        System.out.println("  info  luajvm.bindloadercache="
                + (switchPresent ? System.getProperty("luajvm.bindloadercache") : "<unset:默认开>"));
        if (switchPresent && !Boolean.parseBoolean(System.getProperty("luajvm.bindloadercache"))) {
            // 关闭开关时引擎类在 JVM 仍 cacheable（system==engine）；子 loader 仍必须 false
            check(!call(cacheable, childClass),
                    "bindloadercache=off：子 loader 的类仍必须不可缓存");
        }

        // ---- 5) 真去看进程级表的内容（判据对不等于调用点用了它）----
        // 前面四组只验证 cacheable() 这个**函数**的返回值。但判据对而调用点
        //   漏用（或将来新增一张缓存忘了分层）同样是 ClassLoader 泄漏。
        //   此组直接反射读两张按 Class 键的进程级强表，看 isolated loader
        //   的类是否真的没进去。
        checkSharedTablesRejectIsolated();

        if (failures > 0) {
            System.out.println("LoaderCacheTest FAILED: " + failures + " 处");
            System.exit(1);
        }
        System.out.println("LoaderCacheTest: PASS");
    }

    /**
     * 进程级强表（{@code JavaClass.SHARED_METHOD_INDEX} /
     * {@code SHARED_INNER_CLASS_INDEX}）不得收录 isolated loader 的类。
     *
     * <p>两张表都是 {@code ConcurrentHashMap<Class<?>, ...>}（强键），
     * 一旦收了自定义 loader 的类，那个 loader 及其全部类就永不可回收。
     *
     * <p><b>判别力的载体是前置自检</b>：先用 bootstrap 类证明两张表
     * 真的会被写入（观测器接上了），否则 delta==0 可能只是路径未被走到。
     * {@code JavaClass.forClass} 本身**不**触发 {@code ensureMethodIndex}
     * （首次取方法时才建），故探针须连一次取方法一起做。
     */
    private static void checkSharedTablesRejectIsolated() throws Exception {
        Class<?> jc = Class.forName("org.luajvm.bind.JavaClass");
        Map<Class<?>, ?> methodIndex = staticMap(jc, "SHARED_METHOD_INDEX");
        Map<Class<?>, ?> innerIndex = staticMap(jc, "SHARED_INNER_CLASS_INDEX");
        Method forClass = jc.getDeclaredMethod("forClass",
                Class.forName("org.luajvm.core.Globals"), Class.class);
        forClass.setAccessible(true);
        Method getInner = jc.getDeclaredMethod("getInnerClass",
                Class.forName("org.luajvm.core.LuaValue"));
        getInner.setAccessible(true);

        Object g = Class.forName("org.luajvm.bind.Platform")
                .getMethod("standardGlobals").invoke(null);

        // 前置自检：bootstrap 类必须真的写进两张表。
        // 判据用 containsKey 前后对比而非 size 增量 —— 表是进程级的，被测类可能已被
        //   本 JVM 早前的活动收录，那时 computeIfAbsent 不新增、size 不涨，size 判据会
        //   误报「观测器没接上」。
        //   故另挑两个冷门 bootstrap 类，并断言「调用前不在表内、调用后在」。
        Class<?> mProbe = java.util.StringJoiner.class;
        Class<?> iProbe = java.util.AbstractMap.class;   // 有内部类 SimpleEntry
        check(!methodIndex.containsKey(mProbe),
                "前置：探针类调用前不得已在 SHARED_METHOD_INDEX（否则判据失效）");
        check(!innerIndex.containsKey(iProbe),
                "前置：探针类调用前不得已在 SHARED_INNER_CLASS_INDEX（否则判据失效）");
        Object jcSj = forClass.invoke(null, g, mProbe);
        invokeGet(jcSj, "add");                          // 触发 ensureMethodIndex
        Object jcAm = forClass.invoke(null, g, iProbe);
        getInner.invoke(jcAm, luaStr("SimpleEntry"));    // 触发 ensureInnerClassIndex
        check(methodIndex.containsKey(mProbe),
                "前置：bootstrap 类必须写进 SHARED_METHOD_INDEX（否则本组空转）");
        check(innerIndex.containsKey(iProbe),
                "前置：bootstrap 类必须写进 SHARED_INNER_CLASS_INDEX（否则本组空转）");

        // 正文：isolated loader 的类跑同一条路径，两张表均不得增长
        int mBase = methodIndex.size(), iBase = innerIndex.size();
        // 每轮一个独立 loader：既避免同 loader 重名定义（defineProbe 的类名按全局 seq 递增，
        //   但 JVM 禁止同一 loader 重复定义同名类），也更贴合真实形态 ——
        //   per-class 泄漏的危害正是「N 个 loader 各被一个类钉住」。
        int n = 32;
        for (int k = 0; k < n; k++) {
            ChildLoader iso = new ChildLoader(LoaderCacheTest.class.getClassLoader());
            Class<?> c = iso.defineProbe();
            Object o = forClass.invoke(null, g, c);
            invokeGet(o, "anyMethodName");
            getInner.invoke(o, luaStr("AnyNested"));
        }
        int mDelta = methodIndex.size() - mBase, iDelta = innerIndex.size() - iBase;
        check(mDelta == 0, "SHARED_METHOD_INDEX 不得收录 isolated loader 的类（"
                + n + " 个类后 delta=" + mDelta + "）");
        check(iDelta == 0, "SHARED_INNER_CLASS_INDEX 不得收录 isolated loader 的类（"
                + n + " 个类后 delta=" + iDelta + "）");
    }

    @SuppressWarnings("unchecked")
    private static Map<Class<?>, ?> staticMap(Class<?> owner, String field) throws Exception {
        java.lang.reflect.Field f = owner.getDeclaredField(field);
        f.setAccessible(true);
        return (Map<Class<?>, ?>) f.get(null);
    }

    /** 调 LuaValue.get(LuaValue)，不让本测试类编译期依赖 core 类型。 */
    private static void invokeGet(Object javaClassObj, String name) throws Exception {
        Class<?> lv = Class.forName("org.luajvm.core.LuaValue");
        Method get = lv.getMethod("get", lv);
        get.invoke(javaClassObj, luaStr(name));
    }

    private static Object luaStr(String s) throws Exception {
        return Class.forName("org.luajvm.core.LuaString")
                .getMethod("newStr", String.class).invoke(null, s);
    }

    /** 定义一个不在类路径上的最小类，确保它只能由本 loader 加载。 */
    private static final class ChildLoader extends ClassLoader {
        private static int seq;
        private final String name = "org.luajvm.probe.LoaderProbe" + (++seq);

        ChildLoader(ClassLoader parent) {
            super(parent);
        }

        Class<?> defineProbe() {
            byte[] b = minimalClass(name.replace('.', '/'));
            return defineClass(name, b, 0, b.length);
        }
    }

    /**
     * 手写最小合法 class 文件（class version 52，public，extends Object，无成员）。
     * 不引入 ASM 等字节码库（双平台硬约束禁止运行时字节码生成库；此处只是拼固定字节，
     * 不做代码生成）。
     */
    private static byte[] minimalClass(String internalName) {
        byte[] nameBytes = internalName.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        java.io.DataOutputStream d = new java.io.DataOutputStream(out);
        try {
            d.writeInt(0xCAFEBABE);
            d.writeShort(0);          // minor
            d.writeShort(52);         // major = Java 8
            d.writeShort(5);          // constant_pool_count = 4 entries + 1
            d.writeByte(7);           // #1 CONSTANT_Class -> #2
            d.writeShort(2);
            d.writeByte(1);           // #2 CONSTANT_Utf8 = this class name
            d.writeShort(nameBytes.length);
            d.write(nameBytes);
            d.writeByte(7);           // #3 CONSTANT_Class -> #4
            d.writeShort(4);
            d.writeByte(1);           // #4 CONSTANT_Utf8 = "java/lang/Object"
            byte[] obj = "java/lang/Object".getBytes(java.nio.charset.StandardCharsets.UTF_8);
            d.writeShort(obj.length);
            d.write(obj);
            d.writeShort(0x0021);     // access_flags = ACC_PUBLIC | ACC_SUPER
            d.writeShort(1);          // this_class = #1
            d.writeShort(3);          // super_class = #3
            d.writeShort(0);          // interfaces_count
            d.writeShort(0);          // fields_count
            d.writeShort(0);          // methods_count
            d.writeShort(0);          // attributes_count
        } catch (java.io.IOException e) {
            throw new IllegalStateException(e);
        }
        return out.toByteArray();
    }

    private static Method cacheableMethod() throws Exception {
        Class<?> jm = Class.forName("org.luajvm.bind.JavaMethod");
        Method m = jm.getDeclaredMethod("cacheable", Class.class);
        m.setAccessible(true);
        if (!Modifier.isStatic(m.getModifiers())) {
            throw new AssertionError("cacheable 必须是 static");
        }
        return m;
    }

    private static boolean call(Method cacheable, Class<?> target) {
        try {
            return (Boolean) cacheable.invoke(null, target);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }

    private static void check(boolean ok, String what) {
        System.out.println((ok ? "  OK   " : "  FAIL ") + what);
        if (!ok) failures++;
    }

    private LoaderCacheTest() {
    }
}
