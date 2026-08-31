// java-only: 绑定层缓存分层判据
package org.luajvm.bind;

/**
 * ClassLoader 可回收性判据与分层缓存表的公共设施。
 *
 * <p>判据被三处共享（Coercion 的双层适配器表、JavaClass 的共享方法/内部类索引、
 * JavaMethod/JavaConstructor 的进程级包装表），单独成类避免"判据住在其中一个消费方、
 * 其余两方反向引用"的错位。
 */
final class BindCaches {
    private BindCaches() {
    }

    /**
     * 本引擎自身所在的 loader，与 {@link #SYSTEM_LOADER} 一起构成"与进程同寿"判据。
     * 取值一次并缓存：{@code getClassLoader()} 与 {@code getSystemClassLoader()} 都在
     * 热路径上被反复问到。
     */
    private static final ClassLoader ENGINE_LOADER = engineLoader();
    private static final ClassLoader SYSTEM_LOADER = systemLoader();
    /** A/B 开关：{@code -Dluajvm.bindloadercache=false} 关闭本缓存，退回逐次判定。 */
    private static final boolean ENGINE_LOADER_CACHE =
            System.getProperty("luajvm.bindloadercache") == null
                    || Boolean.parseBoolean(System.getProperty("luajvm.bindloadercache"));

    private static ClassLoader engineLoader() {
        try {
            return BindCaches.class.getClassLoader();
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static ClassLoader systemLoader() {
        try {
            return ClassLoader.getSystemClassLoader();
        } catch (Throwable ignored) {
            // 受限环境（无 system loader）时退化为只看引擎 loader 父链
            return null;
        }
    }

    /**
     * java-only：该类的 {@code ClassLoader} 是否与进程同生命周期（故强引用无害）。
     *
     * <p>{@code null} = bootstrap。判据是"cl 出现在某个与进程同寿的 loader 的父链上"，
     * 该集合取两处并集：
     * <ol>
     *   <li><b>本引擎自身所在的 loader</b>（{@code BindCaches.class.getClassLoader()}）
     *       及其父链。引擎的类就由它加载，故它至少与引擎同寿，强引用它加载的类无害。</li>
     *   <li>{@code ClassLoader.getSystemClassLoader()} 及其父链（HotSpot 上覆盖
     *       platform/app loader）。</li>
     * </ol>
     *
     * <p><b>第 1 条在 Android 上不可省</b>：{@code getSystemClassLoader()} 返回的是
     * libcore 用 {@code java.class.path} 现造的<b>另一个</b> {@code PathClassLoader}
     * （parent 为 BootClassLoader），与 app 真正的 {@code PathClassLoader} 不是同一实例。
     * 只沿 system loader 父链走 ⇒ framework 类（android.*／java.*）判 true，
     * 而全部 app 与库类（material、androidx、app 自身）一律判 false。
     *
     * <p>那样会让调用点全部退化：{@code JavaMethod.forMethod} 不缓存 ⇒ 每次
     * {@code getMethod} 都 {@code new JavaMethod}；{@code JavaClass.ensureMethodIndex}
     * 不进 {@code SHARED_METHOD_INDEX} ⇒ {@code methodIndex} 只挂在按 {@code Globals}
     * 缓存的实例上，而 Android 每个 Activity 一个 {@code Globals} ⇒ 每开一次页面就要重新
     * {@code Class.getMethods()}，该调用在 ART 上极贵且 ART 不缓存结果。
     *
     * <p><b>不会因此重新引入泄漏</b>：自定义 loader（热重载／插件化）是以 app loader 为
     * <b>parent</b> 新建的<b>子</b> loader，而本方法只沿<b>父</b>链向上走，永不命中它们
     * ⇒ 仍判 false、仍不进进程级表、仍可回收。app 主 loader 本身不可能被回收
     * （引擎自己的类就在其中），对它加载的类持强引用不产生额外保留。
     *
     * <p>不能直接调用 Java 9 的 {@code ClassLoader.getPlatformClassLoader()}：Android
     * 即使以 Java 21 编译也没有这个运行时方法，会在绑定初始化阶段抛
     * {@code NoSuchMethodError}。
     *
     * <p>A/B 开关 {@code -Dluajvm.bindloadercache=false} 回到只看 system loader 父链的
     * 判据。
     */
    static boolean cacheable(Class<?> c) {
        ClassLoader cl = c.getClassLoader();
        if (cl == null) return true;
        if (ENGINE_LOADER_CACHE) {
            for (ClassLoader l = ENGINE_LOADER; l != null; l = l.getParent()) {
                if (cl == l) return true;
            }
        }
        for (ClassLoader l = SYSTEM_LOADER; l != null; l = l.getParent()) {
            if (cl == l) return true;
        }
        return false;
    }
}
