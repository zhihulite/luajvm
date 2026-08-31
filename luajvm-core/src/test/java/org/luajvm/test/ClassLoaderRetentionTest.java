package org.luajvm.test;

import org.luajvm.bind.JavaLib;
import org.luajvm.bind.Platform;
import org.luajvm.core.Globals;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.lang.ref.WeakReference;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;

/**
 * java-only：经 luajava 调用过的类，其 {@code ClassLoader} 必须能被回收。
 *
 * <p>{@code bind/} 下有五张**只增不减**的进程级 static 缓存：
 * {@code ExecutableBinding.EXECUTABLES}（键 {@code Method}/{@code Constructor}）、
 * {@code Coercion} 两张（键 {@code Class}）、
 * {@code JavaClass.javaClassMethods}。反射对象强引用其声明类，声明类强引用其
 * {@code ClassLoader}，故这些缓存一旦收下某个自定义 loader 加载的类，
 * **该 loader 及其加载的全部类就永远无法回收** - 经典的 ClassLoader 泄漏。
 *
 * <p>该路径真实可达：{@code JavaLib.classLoaders} 是公开的 per-JavaLib 列表，
 * {@code luajava.bindClass} 依次用其中的 loader 尝试按名加载。Android 上
 * 热重载/插件化每次都新建 loader；若每个都被钉住，进程内存单调增长。
 *
 * <p>做法：用独立 {@code ClassLoader} 加载一份本进程已有类的**副本字节码**
 * （同名类经独立 loader 加载即是不同 Class 对象），经 luajava 调用它的方法与构造器，
 * 然后丢弃全部强引用，检查 loader 是否可被回收。
 */
public final class ClassLoaderRetentionTest {
    private static int failures;

    /** 被复制进独立 loader 的目标类：有公开构造器与公开实例方法即可。 */
    public static final class Probe {
        private final int seed;

        public Probe(int seed) {
            this.seed = seed;
        }

        public int doubled() {
            return seed * 2;
        }
    }

    /**
     * 改名后的类名（**类路径上不存在**）。
     *
     * <p>这是本测试能成立的关键：若用类路径上查得到的原名，{@code luajava.bindClass}
     * -> {@code JavaLib.classForName} -> {@code Class.forName(name)} 在 app loader 上
     * 一次即解析成功（Probe 是本测试的嵌套类，类路径上当然有），
     * **isolated loader 从未被查**、也从未被任何缓存收下 => "可回收"断言空转通过。
     *
     * <p>故必须让类名在类路径上查不到，`bindClassForName` 才会遍历
     * {@code luajavaLib.classLoaders}。做法：把 Probe 字节码里的内部名做**等长**
     * 替换（{@code Probe} -> {@code Prob0}），长度不变故常量池所有偏移仍有效。
     */
    private static final String ISOLATED_SUFFIX = "Prob0";

    /** 仅加载改名后的那个类，其余委派给父 loader - 使该 Class 归属本 loader。 */
    private static final class IsolatedLoader extends ClassLoader {
        private final byte[] probeBytes;
        private final String target;

        IsolatedLoader(ClassLoader parent, byte[] probeBytes, String target) {
            super(parent);
            this.probeBytes = probeBytes;
            this.target = target;
        }

        @Override
        protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
            if (name.equals(target)) {
                Class<?> existing = findLoadedClass(name);
                if (existing != null) return existing;
                Class<?> c = defineClass(name, probeBytes, 0, probeBytes.length);
                if (resolve) resolveClass(c);
                return c;
            }
            return super.loadClass(name, resolve);
        }
    }

    public static void main(String[] args) throws Exception {
        check("bootstrap classes remain process-cacheable", cacheable(String.class));
        check("application classes remain process-cacheable", cacheable(Probe.class));

        byte[] orig = readClassBytes(Probe.class);
        check("probe bytecode readable (" + orig.length + " bytes)", orig.length > 0);

        String from = Probe.class.getName().replace('.', '/');
        String to = from.substring(0, from.length() - ISOLATED_SUFFIX.length()) + ISOLATED_SUFFIX;
        check("rename is equal-length (constant-pool offsets stay valid)", from.length() == to.length());
        byte[] probeBytes = renameInPlace(orig, from, to);
        String isolatedName = to.replace('/', '.');

        // 前提自检：若类路径上能查到这个名字，bindClass 会短路到 app loader，
        //   isolated loader 不被查，整个测试将空转通过。
        check("isolated class name is NOT on the classpath (else the test is vacuous)",
                !canLoadByName(isolatedName));

        WeakReference<ClassLoader> loaderRef = exerciseAndDrop(probeBytes, isolatedName);

        for (int i = 0; i < 6; i++) {
            System.gc();
            Thread.sleep(60);
        }

        boolean collected = loaderRef.get() == null;
        check("a ClassLoader used through luajava is collectable after being dropped"
                + (collected ? "" : " -- retained by a bind/ static cache"), collected);

        if (failures > 0) {
            System.err.println("ClassLoaderRetentionTest: " + failures + " FAILED");
            System.exit(1);
        }
        System.out.println("ClassLoaderRetentionTest: PASS");
    }

    /**
     * 在独立作用域里建 loader、经 luajava 用它，返回仅持弱引用的句柄。
     *
     * <p>Globals 也在此作用域内丢弃：否则 Globals->JavaLib.classLoaders 会合法地
     * 持有该 loader，测出的便不是缓存泄漏。
     */
    private static WeakReference<ClassLoader> exerciseAndDrop(byte[] probeBytes, String isolatedName) {
        Globals g = Platform.standardGlobals();
        IsolatedLoader loader = new IsolatedLoader(ClassLoaderRetentionTest.class.getClassLoader(),
                probeBytes, isolatedName);
        // 让 bindClassForName 的 classLoaders 遍历能找到它
        JavaLib.forGlobals(g).classLoaders.add(loader);

        // 用 luajava.import：它走 bindClassForName，先试 Class.forName（失败，
        //   改名后的类不在类路径上），再遍历 classLoaders => 真正用到 isolated loader。
        //   路径覆盖 JavaClass.forName -> JavaConstructor.forConstructor -> JavaMethod.forMethod
        //   -> MemberSupport.ParamInfo -> Coercion.getCoercion，以及返回值经 Coercion.toLua。
        // 注：import 是 JavaLib 装到**全局**的函数（不在 luajava 表上） - 见 JavaLib.call
        long r = g.execute("local P = import('" + isolatedName + "')"
                + " local p = P.new(21) return p:doubled()").arg1().tolong();
        check("luajava.import loaded the isolated class and called it (doubled(21) -> " + r + ")",
                r == 42);

        // 关键自检：确认 luajava 用的确实是 isolated loader 的那个 Class 对象。
        //   若不成立，说明又回到了"短路到 app loader"的空转状态。
        try {
            Class<?> viaIsolated = loader.loadClass(isolatedName);
            check("isolated loader owns the Class luajava used",
                    viaIsolated.getClassLoader() == loader);
            check("isolated-loader classes are not process-cacheable",
                    !cacheable(viaIsolated));
            // 再调一次，确保各缓存都已收下 isolated 类的反射对象
            long r2 = g.execute("local P = import('" + isolatedName + "')"
                    + " local p = P.new(4) return p:doubled()").arg1().tolong();
            check("second call through the isolated class also works (-> " + r2 + ")", r2 == 8);
        } catch (ClassNotFoundException e) {
            check("isolated loader can load the renamed probe: " + e, false);
        }
        return new WeakReference<>(loader);
    }

    /**
     * 反射调用包私有判据，直接守住 bootstrap/app/custom 三类 loader 的分层语义。
     * 测试代码也故意不调用 {@code ClassLoader.getPlatformClassLoader()}，确保同一门禁
     * 可在没有该 Java 9 API 的 Android 运行时复用。
     */
    private static boolean cacheable(Class<?> c) {
        try {
            Class<?> javaMethod = Class.forName("org.luajvm.bind.JavaMethod");
            Method predicate = javaMethod.getDeclaredMethod("cacheable", Class.class);
            predicate.setAccessible(true);
            return (Boolean) predicate.invoke(null, c);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError("cannot invoke JavaMethod.cacheable", e);
        }
    }

    /**
     * 在 class 字节里把内部类名做**等长**替换。
     *
     * <p>类名以 UTF8 常量池项的原始字节出现（内部形式，'/' 分隔）。等长替换不改变
     * 任何长度前缀与偏移，故无需重算常量池即得到一个合法的、类路径上不存在的类。
     */
    private static byte[] renameInPlace(byte[] data, String from, String to) {
        byte[] f = from.getBytes(StandardCharsets.UTF_8);
        byte[] t = to.getBytes(StandardCharsets.UTF_8);
        if (f.length != t.length) throw new IllegalArgumentException("rename must be equal-length");
        byte[] out = data.clone();
        outer:
        for (int i = 0; i + f.length <= out.length; i++) {
            for (int j = 0; j < f.length; j++) {
                if (out[i + j] != f[j]) continue outer;
            }
            System.arraycopy(t, 0, out, i, t.length);
        }
        return out;
    }

    /** 类路径上能否按名字加载 - 自检本测试是否空转。 */
    private static boolean canLoadByName(String name) {
        try {
            Class.forName(name);
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    private static byte[] readClassBytes(Class<?> c) throws Exception {
        String res = c.getName().replace('.', '/') + ".class";
        try (InputStream in = c.getClassLoader().getResourceAsStream(res)) {
            if (in == null) throw new IllegalStateException("cannot find " + res);
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            for (int n; (n = in.read(buf)) > 0; ) out.write(buf, 0, n);
            return out.toByteArray();
        }
    }

    static void check(String name, boolean ok) {
        System.out.println((ok ? "  OK: " : "  FAIL: ") + name);
        if (!ok) failures++;
    }
}
