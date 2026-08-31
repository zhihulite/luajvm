package org.luajvm.android;

import org.luajvm.android.api.LuaContext;
import org.luajvm.android.LuaApplication;
import org.luajvm.android.proxy.LuaClassProxy;
import org.luajvm.android.proxy.LuaInterfaceProxy;

import org.luajvm.core.LuaTable;
import org.luajvm.core.LuaValue;
import org.luajvm.core.Varargs;
import org.luajvm.spi.LuaJavaContext;

/**
 * Android 端 LuaJavaContext SPI 实现。
 *
 * <p>通过 Java SPI（{@link java.util.ServiceLoader}）自动注入到 luajvm-core，
 * 使 {@code luajava.createProxy} / {@code luajava.override} 在 Android 上可用，
 * 无需宿主显式调用 {@code JavaLib.setLuaContext}。
 *
 * <p>路由策略：
 * <ul>
 *   <li>{@link #createProxy(Class[], LuaTable)} -> {@link LuaInterfaceProxy#create}
 *        -  纯接口代理（JDK Proxy，跨平台兼容）</li>
 *   <li>{@link #override(Class, LuaTable)} -> {@link LuaClassProxy}
 *        -  抽象类/类方法重写（基于 dx ProxyBuilder，仅 Android）</li>
 * </ul>
 *
 * <p>{@link LuaContext}（Android 端错误上报接口）由 {@link org.luajvm.android.engine.LuaEngine} 初始化时通过
 * {@link #setLuaContext(LuaContext)} 显式设置；未设置时 {@link LuaClassProxy}
 * 内部 fallback 到 {@code LuaConfig.logError}，功能仍可用。
 */
public class AndroidLuaJavaContext implements LuaJavaContext {

    //  volatile：LuaEngine 在主线程初始化，Lua 可能在 worker 线程触发 override。
    private static volatile LuaContext sLuaContext;

    /**
     * 获取当前 LuaContext（可能为 null）。
     */
    public static LuaContext getLuaContext() {
        return sLuaContext;
    }

    /**
     * 设置 Android 端 LuaContext，供 {@link LuaClassProxy} 错误上报使用。
     *
     * <p>由 {@link org.luajvm.android.engine.LuaEngine} 初始化时调用。SPI 自动注入的
     * {@link AndroidLuaJavaContext} 不依赖此调用即可工作（createProxy 完全独立，
     * override 在 sLuaContext==null 时走 fallback 日志）。
     */
    public static void setLuaContext(LuaContext ctx) {
        sLuaContext = ctx;
    }

    /**
     * 清除 Android 端 LuaContext，供宿主销毁时调用。
     *
     * <p><b>为什么必须有</b>：{@code LuaEngine} 初始化时把 {@code getLuaContext()}
     * 存进进程级 static {@code sLuaContext}，而 {@code mContext} 是 {@code LuaContext}
     * 时 {@code LuaEngine.mLuaContext} 就是那个 {@code Activity} 本身（见
     * {@code LuaEngine} 构造器）。没有清除路径的话 Activity 销毁后仍被静态字段强引用，
     * 整棵 View 树与其 Context 滞留到进程结束，属 Android 最典型的一类泄漏。
     *
     * <p><b>身份守卫</b>：仅在 {@code sLuaContext == ctx} 时清除。多 Activity 下后建的
     * Activity 会覆盖 {@code sLuaContext}，先销毁的那个若无条件置 null，会抹掉仍在用的
     * 新 Activity 引用。
     */
    public static void clearLuaContext(LuaContext ctx) {
        if (sLuaContext == ctx) {
            sLuaContext = null;
        }
    }

    @Override
    public ClassLoader getClassLoader() {
        var app = LuaApplication.getInstance();
        return app != null ? app.getClassLoader() : Thread.currentThread().getContextClassLoader();
    }

    @Override
    public Object getApplication() {
        return LuaApplication.getInstance();
    }

    /**
     * 创建纯接口代理（JDK Proxy）。
     *
     * <p>对应 Lua: {@code luajava.createProxy(Iface1, Iface2, ..., methodsTable)}。
     * 不支持 super 调用，仅接口方法直接分发给 Lua handler。
     * handler 可为 Lua 函数（所有方法分发到该函数）或 Lua 表（按方法名分发）。
     */
    @Override
    public Object createProxy(Class<?>[] ifaces, LuaValue handler) {
        return LuaInterfaceProxy.create(ifaces, handler, getLuaContext());
    }

    /**
     * 重写类方法（基于 dx ProxyBuilder）。
     *
     * <p>对应 Lua: {@code luajava.override(AbstractClass, methodsTable, ctorArgs...)}。
     * 支持抽象类继承和 super 调用，仅 Android 可用（dx 是 Android 专有）。
     * {@code sLuaContext} 为 null（引擎未初始化）时由 {@link LuaClassProxy#create}
     * 回落 application 的 code cache 目录，仍缺则抛 LuaError。
     *
     * @param clazz   要重写的类（通常为抽象类或具体类）
     * @param args    首项为方法表，其余项为目标类构造参数
     * @return 代理实例（已重写指定方法）
     */
    @Override
    public Object override(Class<?> clazz, Varargs args) {
        LuaClassProxy proxy = new LuaClassProxy(clazz);
        return proxy.create(sLuaContext, args);
    }
}
