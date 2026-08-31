// java-only: Lua/Java上下文
package org.luajvm.spi;
import org.luajvm.core.LuaValue;
import org.luajvm.core.Varargs;

/**
 * Lua-Java 互操作上下文。
 *
 * <p>Java 特有：C 无对应。提供类加载器、代理创建、方法重写等宿主环境能力。
 */
public interface LuaJavaContext {
    ClassLoader getClassLoader();

    Object getApplication();

    /**
     * 创建纯接口代理（JDK Proxy）。
     *
     * <p>{@code handler} 可以是 Lua 函数（所有接口方法都分发给该函数）或
     * Lua 表（按方法名分发）。调用方不得把函数强转成空表，否则代理查不到
     * handler，接口方法（如 {@code onApplyWindowInsets}）会返回 null 引发
     * androidx 等调用方 NPE。
     */
    Object createProxy(Class<?>[] ifaces, LuaValue handler);

    /**
     * 重写类方法并构造代理实例。
     *
     * <p>{@code args.arg1()} 是方法表，{@code args.subargs(2)} 是原样保留的构造参数。
     * 构造参数不能在 core 层先转成 {@code Object[]}：Android 代理层需要按目标构造器的
     * 精确参数类型转换，尤其 {@code boolean}/{@code int} 等基本类型不能退化成装箱类。
     */
    Object override(Class<?> clazz, Varargs args);
}
