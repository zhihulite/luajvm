// java-only: 反射调用统一入口（纯 Method.invoke / Field.get-set，不含 MethodHandle）
package org.luajvm.bind;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;

/**
 * Java 绑定的反射调用统一入口：只用 {@code Method.invoke} / {@code Field.get-set}。
 *
 * <p>机制依据：ART 上只有 direct handle 是 native，通用 MethodHandle 路径是 libcore
 * {@code Transformers} 的纯 Java 实现 + {@code EmulatedStackFrame}；绑定层签名不定、
 * 必然要 transform，执行仍落在纯 Java 适配层。varargs 数组会被 MethodHandle 展开
 * 成独立参数，{@code setColorSchemeColors(int...)} 等语义要求整体传数组。
 * 字段/数组访问走 {@code Field}（VarHandle 需 API 33+，超出当前 minSdk）。
 */
final class InvokeSupport {

    /**
     * java-only：A/B 开关 —— {@code -Dluajvm.bindfastcall=false} 时 bind 层内部的
     * Java-&gt;Java 调用走完整 Lua 调用协议（{@code LuaCall.callLua}）。
     *
     * <p>与反射机制无关，只决定"调纯 Java 绑定包装时是否建 Lua 调用帧"。
     * 见 {@code LuaCall.callJavaBinding}。
     */
    static final boolean FAST_CALL =
            System.getProperty("luajvm.bindfastcall") == null
                    || Boolean.parseBoolean(System.getProperty("luajvm.bindfastcall"));

    private InvokeSupport() {
    }

    // ============================================================
    // Method 调用
    // ============================================================

    static void invokeVoid(Method method, Object instance, Object[] args)
            throws InvocationTargetException, IllegalAccessException {
        method.invoke(instance, args);
    }

    static Object invoke(Method method, Object instance, Object[] args)
            throws InvocationTargetException, IllegalAccessException {
        return method.invoke(instance, args);
    }

    // ============================================================
    // Constructor
    // ============================================================

    static Object construct(Constructor<?> ctor, Object[] args)
            throws InvocationTargetException, IllegalAccessException, InstantiationException {
        return ctor.newInstance(args);
    }

    // ============================================================
    // Field 访问
    // ============================================================

    static Object getField(Field f, Object instance) throws IllegalAccessException {
        return f.get(instance);
    }

    static void setField(Field f, Object instance, Object value) throws IllegalAccessException {
        f.set(instance, value);
    }
}
