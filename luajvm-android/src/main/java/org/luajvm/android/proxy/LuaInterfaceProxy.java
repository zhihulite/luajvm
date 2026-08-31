package org.luajvm.android.proxy;


import org.luajvm.android.api.LuaContext;
import org.luajvm.android.runtime.LuaConfig;
import org.luajvm.bind.Coercion;
import org.luajvm.core.LuaError;
import org.luajvm.core.LuaErrors;
import org.luajvm.core.LuaValue;
import org.luajvm.core.Varargs;
import org.luajvm.vm.LuaCall;

import java.lang.reflect.Array;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;

/**
 * 纯接口代理（JDK Proxy）。
 * 不支持 super 调用，接口方法直接分发给 Lua handler。
 */
public class LuaInterfaceProxy {

    /**
     * 创建多个接口的代理实例。
     */
    public static Object create(Class<?>[] ifaces, LuaValue handler, LuaContext ctx) {
        for (Class<?> iface : ifaces) {
            if (!iface.isInterface()) {
                throw LuaErrors.errorObject("not an interface: " + iface.getName());
            }
        }
        return Proxy.newProxyInstance(ifaces[0].getClassLoader(), ifaces, new Handler(handler, ctx));
    }

    /**
     * 创建单个接口的代理实例。
     */
    public static Object create(Class<?> iface, LuaValue handler, LuaContext ctx) {
        return create(new Class[]{iface}, handler, ctx);
    }

    // ============================================================
    // InvocationHandler
    // ============================================================

    private record Handler(LuaValue luaObject, LuaContext ctx) implements InvocationHandler {

        /**
         * 是否为 Object 的 equals/hashCode/toString。
         */
        private static boolean isObjectMethod(Method method) {
            if (method.getDeclaringClass() != Object.class) return false;
            return switch (method.getName()) {
                case "equals", "hashCode", "toString" -> true;
                default -> false;
            };
        }

        /**
         * Object 方法的默认实现。
         * 仅能用 switch 手动实现，反射和 MethodHandle 都会死循环。
         */
        private static Object invokeObjectMethodDefault(Object proxy, Method method, Object[] args) {
            return switch (method.getName()) {
                case "equals" -> proxy == args[0];
                case "hashCode" -> System.identityHashCode(proxy);
                case "toString" -> proxy.getClass().getName() + "@"
                        + Integer.toHexString(System.identityHashCode(proxy));
                default -> throw LuaErrors.errorObject("unexpected Object method: " + method.getName());
            };
        }

        @Override
        public Object invoke(Object proxy, Method method, Object[] args) {
            String name = method.getName();

            // Object 方法：Lua 没定义就走默认实现
            if (isObjectMethod(method)) {
                if (luaObject.isfunction() || luaObject.get(name).isnil()) {
                    return invokeObjectMethodDefault(proxy, method, args);
                }
            }

            LuaValue func = luaObject.isfunction() ? luaObject : luaObject.get(name);
            if (func.isnil()) {
                return Coercion.toJava(LuaValue.NIL, method.getReturnType());
            }

            boolean isVarArgs = method.isVarArgs();
            int fixedCount = args != null ? args.length : 0;
            LuaValue[] luaArgs;

            if (isVarArgs && fixedCount > 0) {
                Object varArg = args[fixedCount - 1];
                fixedCount--;
                int varArgCount = varArg != null ? Array.getLength(varArg) : 0;
                luaArgs = new LuaValue[fixedCount + varArgCount];
                for (int i = 0; i < fixedCount; i++) {
                    luaArgs[i] = Coercion.toLua(args[i]);
                }
                for (int i = 0; i < varArgCount; i++) {
                    luaArgs[i + fixedCount] = Coercion.toLua(Array.get(varArg, i));
                }
            } else {
                luaArgs = new LuaValue[fixedCount];
                for (int i = 0; i < fixedCount; i++) {
                    luaArgs[i] = Coercion.toLua(args[i]);
                }
            }

            try {
                LuaValue result = LuaCall.invoke(func, Varargs.of(luaArgs)).arg1();
                return Coercion.toJava(result, method.getReturnType());
            } catch (Exception e) {
                // 回传 onError（LuaError 的 Lua 栈快照由 LuaLog 拼 traceback，方法名
                //   上下文经 title 传递），返回类型零值；不上抛给 CrashHandler 闪退。
                //   非 LuaError 的 Java 侧异常包一层，保住 cause 链
                Exception err = e instanceof LuaError le ? le
                        : LuaErrors.errorObject("LuaInterfaceProxy." + name + ": " + e, e);
                if (ctx != null) ctx.sendError("LuaInterfaceProxy." + name, err);
                else LuaConfig.logError("LuaInterfaceProxy", err);
                return zeroValueFor(method.getReturnType());
            }
        }

        /** 基元返回类型必须给出精确装箱类型：拆箱 null 会 NPE 逃进框架调用栈。 */
        private static Object zeroValueFor(Class<?> t) {
            if (!t.isPrimitive() || t == void.class) return null;
            return Coercion.toJava(LuaValue.NIL, t);
        }
    }
}
