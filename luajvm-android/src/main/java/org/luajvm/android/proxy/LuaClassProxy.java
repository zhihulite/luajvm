package org.luajvm.android.proxy;

import android.content.Context;

import com.android.dx.stock.ProxyBuilder;
import org.luajvm.android.LuaApplication;
import org.luajvm.android.api.LuaContext;
import org.luajvm.android.runtime.LuaConfig;
import org.luajvm.android.util.LuaUtil;

import org.luajvm.bind.Coercion;
import org.luajvm.bind.JavaCall;
import org.luajvm.core.LuaError;
import org.luajvm.core.LuaErrors;
import org.luajvm.core.LuaFunction;
import org.luajvm.core.LuaTable;
import org.luajvm.core.LuaValue;
import org.luajvm.core.Varargs;

import java.io.File;
import java.io.IOException;
import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 基于 ProxyBuilder 的类方法重写。
 */
public class LuaClassProxy {

    private final Class<?> type;

    public LuaClassProxy(Class<?> type) {
        this.type = type;
    }

    public LuaClassProxy(String name) throws ClassNotFoundException {
        this(Class.forName(name));
    }

    public static boolean canOverride(Method method) {
        int mod = method.getModifiers();
        if (Modifier.isFinal(mod) || Modifier.isStatic(mod) || Modifier.isPrivate(mod))
            return false;
        return !(method.getName().equals("finalize") && method.getParameterTypes().length == 0);
    }

    public static Object callSuper(Object obj, Method method, Set<String> proxyNames, Object... args) throws Throwable {
        if (proxyNames != null && proxyNames.contains(method.getName())) {
            try {
                return ProxyBuilder.callSuper(obj, method, args);
            } catch (NoSuchMethodException ignored) {
            }
        }
        return method.invoke(obj, args);
    }

    public static boolean isProxy(Class<?> c) {
        return ProxyBuilder.isProxyClass(c);
    }

    /**
     * 在同名重载里挑一个：按 Lua-&gt;Java 强转分数选最优，全不可强转时退回第一个参数个数相符的。
     *
     * @param firstArgIndex {@code candidates} 的第一个形参对应的实参下标（1 基）。
     *            {@code SuperMethodFn}（{@code super.name(...)}）传 1，{@code Dispatch}（{@code super("name", ...)}）传 2 ——
     *            **必须由调用方给**：两条路径的实参起点不同，写死一个值会让另一条错位一格。
     */
    private static Method match(List<Method> candidates, int argCount, Varargs args, int firstArgIndex) {
        Method best = null;
        Method firstArity = null;
        int bestScore = Coercion.SCORE_UNCOERCIBLE;
        for (Method method : candidates) {
            var pts = method.getParameterTypes();
            if (pts.length != argCount) continue;
            if (firstArity == null) firstArity = method;
            // 用 Coercion 打分，不要自己 isInstance —— 基元形参上 isInstance 恒 false，
            //   会让带 int/boolean 形参的重载永远匹配不上（见 Coercion.scoreParams 的说明）。
            int score = Coercion.scoreParams(pts, args, firstArgIndex);
            if (score < bestScore) {
                best = method;
                bestScore = score;
                if (score == 0) break;
            }
        }
        return best != null ? best : firstArity;
    }

    private static Varargs invoke(Object target, Method method, Set<String> proxyNames, Varargs a, int off, int n) {
        Object[] ja = new Object[n];
        var pts = method.getParameterTypes();
        for (int i = 0; i < n; i++)
            ja[i] = Coercion.toJava(a.arg(i + off), i < pts.length ? pts[i] : Object.class);
        try {
            return Coercion.toLua(LuaClassProxy.callSuper(target, method, proxyNames, ja));
        } catch (VirtualMachineError e) {
            // StackOverflowError/OOM 包成 LuaError 会让 pcall 在坏栈上继续跑，原样上抛
            throw e;
        } catch (Throwable e) {
            // AbstractMethodError 等 Error 不是 Exception，强转会 CCE 掩盖根因；
            //   LuaError(Exception) 在 message==null 时二次 NPE
            throw LuaErrors.errorObject(String.valueOf(e), e);
        }
    }

    public Object create(LuaContext luaContext, Varargs args) {
        // dex 缓存目录：优先取传入宿主 Context 的 getCodeCacheDir（与 LuaApplication
        //   单例同一路径）；LuaContext 未注入时回落 application 单例，仍缺则语义化报错。
        Context hostContext = luaContext != null ? luaContext.getContext() : null;
        if (hostContext == null) hostContext = LuaApplication.getInstance();
        if (hostContext == null)
            throw LuaErrors.errorObject("luajava.override: LuaContext 未初始化且无 LuaApplication，无法确定代理 dex 缓存目录");
        File dexCache = new File(hostContext.getCodeCacheDir(), "lua_proxy");
        dexCache.mkdirs();
        LuaValue handlers = args.arg1();
        int n = args.narg() - 1;
        Set<String> names = keys(handlers);
        // 校验 handler 表里的方法名都可覆写（final/static/private 立即报错，不静默失效）
        validateHandlerNames(names);
        try {
            // 不用 onlyMethods 收窄方法集。若用它，需要的额外判断与代价：
            //   1. 方法集必须在 build 前冻结为「handler 现有键 ∪ 全部抽象方法」——漏掉抽象
            //      方法生成的类无法实例化；而 handler 是运行期可变的 Lua 表，冻结集合与
            //      「同一类第二次 override 增加方法须生效」的契约直接冲突。
            //   2. generatedProxyClasses 的进程级缓存键 (baseClass, interfaces, classLoader,
            //      sharedClassLoader) 不含方法集：第二次 override 命中首次生成的代理类，
            //      新增方法静默不被代理——须自建「方法集 → 代理类」缓存并逐次比对，
            //      且已创建的旧实例停在旧方法集上，事后无法补救。
            // 全量代理 + 运行期按 handler 表分派（未列入走 ProxyBuilder.callSuper 回落基类）
            //   是上游 javadoc 的推荐用法：分派路径同样是 handler 表一次查找，收窄版省不掉；
            //   onlyMethods 省下的只是生成期桥数量，属一次性 dex 成本，已被缓存摊薄。
            ProxyBuilder<?> b = ProxyBuilder.forClass(type)
                    .dexCache(dexCache)
                    .handler(new LuaHandler(handlers, luaContext));
            if (n > 0) {
                Constructor<?> ctor = matchConstructor(args, n);
                if (ctor == null) throw LuaErrors.errorObject("no suitable constructor for: " + type.getName());
                Class<?>[] parameterTypes = ctor.getParameterTypes();
                Object[] ctorArgs = new Object[n];
                for (int i = 0; i < n; i++) {
                    ctorArgs[i] = Coercion.toJava(args.arg(i + 2), parameterTypes[i]);
                }
                b.constructorArgTypes(parameterTypes).constructorArgValues(ctorArgs);
            } else {
                if (hasConstructor(Context.class)) {
                    b.constructorArgTypes(Context.class).constructorArgValues(luaContext.getContext());
                } else if (!hasConstructor()) {
                    throw LuaErrors.errorObject("no suitable constructor for: " + type.getName());
                }
            }
            return b.build();
        } catch (IOException e) {
            throw LuaErrors.errorObject(e);
        } finally {
            try {
                LuaUtil.rmDir(dexCache);
            } catch (Exception ignored) {
            }
        }
    }

    private boolean hasConstructor(Class<?>... paramTypes) {
        try {
            type.getConstructor(paramTypes);
            return true;
        } catch (NoSuchMethodException e) {
            return false;
        }
    }

    /** 按 Lua->Java coercion 分数选择构造器，并保留 boolean/int 等基本类型签名。 */
    private Constructor<?> matchConstructor(Varargs args, int n) {
        Constructor<?> best = null;
        int bestScore = Coercion.SCORE_UNCOERCIBLE;
        Constructor<?>[] constructors = type.getConstructors();
        if (constructors.length == 0) constructors = type.getDeclaredConstructors();
        for (Constructor<?> ctor : constructors) {
            Class<?>[] parameterTypes = ctor.getParameterTypes();
            if (ctor.isVarArgs() || parameterTypes.length != n) continue;
            int score = Coercion.scoreParams(parameterTypes, args, 2);
            if (score < bestScore) {
                best = ctor;
                bestScore = score;
                if (score == 0) break;
            }
        }
        return best;
    }

    // 构造期校验：handler 表里若有不可覆写（final/static/private/finalize）的方法名，
    //   当场抛错——否则该条目在 dexmaker 里静默惰性（写了 handler 却永不触发）。
    private void validateHandlerNames(Set<String> names) {
        for (Class<?> c = type; c != null && c != Object.class; c = c.getSuperclass())
            validate(c.getDeclaredMethods(), names);
        for (Class<?> i : allInterfaces()) validate(i.getDeclaredMethods(), names);
    }

    private void validate(Method[] methods, Set<String> names) {
        for (Method method : methods)
            if (names.contains(method.getName()) && !canOverride(method))
                throw LuaErrors.errorObject("cannot override: " + method);
    }

    private static Set<String> keys(LuaValue t) {
        Set<String> s = new HashSet<>();
        if (!t.istable()) return s;
        LuaValue k = LuaValue.NIL;
        while (true) {
            Varargs n = t.next(k);
            if (n.isnil(1)) break;
            s.add(n.arg1().toJavaString());
            k = n.arg1();
        }
        return s;
    }

    // ---- super metatable ----

    private Set<Class<?>> allInterfaces() {
        Set<Class<?>> s = new HashSet<>();
        for (Class<?> c = type; c != null && c != Object.class; c = c.getSuperclass())
            for (Class<?> i : c.getInterfaces()) addAll(s, i);
        return s;
    }

    private void addAll(Set<Class<?>> s, Class<?> i) {
        if (!s.add(i)) return;
        for (Class<?> p : i.getInterfaces()) addAll(s, p);
    }

    public static class LuaHandler implements InvocationHandler {
        private final LuaValue handler;
        private final Map<Integer, LuaValue> superTableCache = new HashMap<>();
        private final Set<String> proxyNames;
        private LuaContext ctx;

        public LuaHandler(LuaValue handler) {
            this.handler = handler;
            this.proxyNames = keys(handler);
        }

        public LuaHandler(LuaValue handler, LuaContext ctx) {
            this.handler = handler;
            this.ctx = ctx;
            this.proxyNames = keys(handler);
        }

        /**
         * Lua 侧没给实现（或抛错）时的返回值。
         *
         * <p><b>基元返回类型必须给出精确的装箱类型</b>：代理生成的字节码会把
         * {@code invoke} 的返回值强转再拆箱（{@code ((Long) r).longValue()}），
         * 给 {@code Integer} 会 {@code ClassCastException} ⇒ 不能对所有基元一律返回
         * {@code 0}（会装箱成 {@code Integer}，返回 long/double/float/short/byte/char
         * 的方法就会崩）。交给 {@link Coercion} 产出零值即类型正确。
         *
         * <p>引用类型保持返回 {@code null}，不进 Coercion —— {@code List/Map} 等目标类型
         * 会走 Collection/MapCoercion，语义不是"零值"。
         */
        private static Object zeroValueFor(Class<?> t) {
            if (!t.isPrimitive() || t == void.class) return null;
            return Coercion.toJava(LuaValue.NIL, t);
        }

        public void setContext(LuaContext ctx) {
            this.ctx = ctx;
        }

        @Override
        public Object invoke(Object target, Method method, Object[] args) throws Throwable {
            String name = method.getName();
            Class<?> ret = method.getReturnType();
            LuaValue fn = handler.isfunction() ? handler : handler.get(name);
            // handler 表没这个方法：回落原实现（代理是全量的，未列入者必须表现为未被代理）。
            //   必须直连 ProxyBuilder.callSuper 走生成的 super$name$ret：本类的 callSuper
            //   在 proxyNames 不含该名时回落 method.invoke(代理实例) ⇒ 重新进 invoke 无限递归。
            //   抽象方法无 super 可调，退回零值
            if (fn.isnil()) {
                if (Modifier.isAbstract(method.getModifiers())) return zeroValueFor(ret);
                try {
                    return ProxyBuilder.callSuper(target, method, args);
                } catch (AbstractMethodError | NoSuchMethodException e) {
                    return zeroValueFor(ret);
                }
            }
            // 统一约定：super 表永远占第 0 位（抽象方法同样传入），脚本侧 override handler
            //   按 super-first 书写，不发 super 表会让 Lua 形参整体错位一格。
            //   经 super 表调用抽象方法会 AbstractMethodError，与 Java 语义一致。
            Object[] callArgs = new Object[(args != null ? args.length : 0) + 1];
            callArgs[0] = superTable(target, method.getDeclaringClass());
            if (args != null) System.arraycopy(args, 0, callArgs, 1, args.length);
            try {
                if (ret == void.class) {
                    JavaCall.callLua(fn, callArgs);
                    return null;
                }

                LuaValue r = JavaCall.callLua(fn, callArgs);
                // java diff: callLua 无结果时返回 NIL 不返回 null，判缺失必须走 isnil
                if (r.isnil()) return zeroValueFor(ret);
                if (ret.isPrimitive()) {
                    // dexmaker 生成的桥接对返回值 cast+unbox，直接返回 LuaInteger/LuaBoolean
                    //   必抛 CCE；基元目标须经 Coercion 产出精确装箱类型
                    Object boxed = Coercion.toJava(r, ret);
                    // 非 Number userdata 一类转不了的值 Coercion 给 null，unbox 会 NPE：
                    //   转成 LuaError 走下面的 sendError，别让 NPE 逃进框架调用栈
                    if (boxed == null)
                        throw LuaErrors.errorObject("override " + name + " 返回值无法转为 "
                                + ret.getName() + "（实为 " + r.typeName() + "）");
                    return boxed;
                }
                if (r.getClass() != ret) {
                    // 目标类型或其父类直接放行（避免 userdata 二次包装丢对象身份）；
                    // 其余 Lua 值按目标类型转换
                    if (ret.isInstance(r)) return r;
                    return Coercion.toJava(r, ret);
                }
                return r;
            } catch (Exception e) {
                // LuaError 与 Java 侧异常一并回传（LuaError 的 Lua 栈快照由 LuaLog 拼
                //   traceback），返回类型零值；不上抛给 CrashHandler 闪退
                if (ctx != null) ctx.sendError("LuaClassProxy." + name, e);
                else LuaConfig.logError("LuaClassProxy", e);
                return zeroValueFor(ret);
            }
        }

        private LuaValue superTable(Object target, Class<?> sc) {
            int k = System.identityHashCode(target);
            LuaValue v = superTableCache.get(k);
            if (v != null) return v;
            v = buildSuper(target, sc);
            superTableCache.put(k, v);
            return v;
        }

        private LuaValue buildSuper(Object target, Class<?> sc) {
            Map<String, List<Method>> methodsByName = new HashMap<>();
            for (Method method : sc.getDeclaredMethods()) {
                if (Modifier.isStatic(method.getModifiers()) || Modifier.isPrivate(method.getModifiers()))
                    continue;
                List<Method> list = methodsByName.get(method.getName());
                if (list == null) { list = new ArrayList<>(); methodsByName.put(method.getName(), list); }
                list.add(method);
            }

            LuaValue t = new LuaTable();
            LuaValue mt = new LuaTable();
            mt.set("__index", new Index(target, methodsByName, proxyNames));
            mt.set("__call", new Dispatch(target, methodsByName, proxyNames));
            t.setmetatable(mt);
            return t;
        }
    }


    private static final class Index extends LuaFunction {
        private final Object target;
        private final Map<String, List<Method>> methodsByName;
        private final Set<String> proxyNames;

        Index(Object target, Map<String, List<Method>> methodsByName, Set<String> proxyNames) {
            this.target = target;
            this.methodsByName = methodsByName;
            this.proxyNames = proxyNames;
        }

        @Override
        public Varargs call(Varargs a) {
            List<Method> candidates = methodsByName.get(a.arg(2).toJavaString());
            return candidates != null && !candidates.isEmpty() ? new SuperMethodFn(target, candidates, proxyNames) : LuaValue.NIL;
        }
    }

    private static final class Dispatch extends LuaFunction {
        private final Object target;
        private final Map<String, List<Method>> methodsByName;
        private final Set<String> proxyNames;

        Dispatch(Object target, Map<String, List<Method>> methodsByName, Set<String> proxyNames) {
            this.target = target;
            this.methodsByName = methodsByName;
            this.proxyNames = proxyNames;
        }

        @Override
        public Varargs call(Varargs a) {
            int n = a.narg();
            if (n < 1) throw LuaErrors.errorObject("super() needs method name");
            List<Method> candidates = methodsByName.get(a.arg1().toJavaString());
            if (candidates == null) throw LuaErrors.errorObject("no super method");
            Method method = match(candidates, n - 1, a, 2);
            if (method == null) throw LuaErrors.errorObject("no overload");
            return LuaClassProxy.invoke(target, method, proxyNames, a, 2, n - 1);
        }
    }

    private static final class SuperMethodFn extends LuaFunction {
        private final Object target;
        private final List<Method> candidates;
        private final Set<String> proxyNames;

        SuperMethodFn(Object target, List<Method> candidates, Set<String> proxyNames) {
            this.target = target;
            this.candidates = candidates;
            this.proxyNames = proxyNames;
        }

        @Override
        public Varargs call(Varargs a) {
            if (candidates.size() == 1)
                return LuaClassProxy.invoke(target, candidates.get(0), proxyNames, a, 1, a.narg());
            Method method = match(candidates, a.narg(), a, 1);
            return method != null ? LuaClassProxy.invoke(target, method, proxyNames, a, 1, a.narg()) : LuaValue.NIL;
        }
    }
}
