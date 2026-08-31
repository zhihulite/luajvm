// java-only: jdk.internal.vm.Continuation 的反射适配层（协程第三模式的底座）。
package org.luajvm.core;

import java.lang.reflect.AccessibleObject;
import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;

/**
 * java-only：把 {@code jdk.internal.vm.Continuation} 包成运行期可选依赖（反射加载）。
 * Android（ART）没有该类，编译期引用会 {@code NoClassDefFoundError} 且需给整个模块加
 * {@code --add-exports}（污染下游构建）；反射拿不到就 {@code SUPPORTED=false}，
 * 调用方回落线程实现。
 *
 * <p>调用统一走 {@code Method.invoke}：D8 对 {@code MethodHandle.invoke/invokeExact}
 * 要求 min-api 26，会把整个 AAR 的 minSdk 顶到 26。
 *
 * <p>Continuation 是默认关闭的第三模式：需 {@code --add-exports
 * java.base/jdk.internal.vm=ALL-UNNAMED} 且显式 {@code -Dluajvm.cont=true}，两者缺一即
 * 回落；Android 上 {@code SUPPORTED} 恒 false、代码永不执行。不默认开启：JDK 内部 API
 * 无兼容性承诺，且需额外 JVM flag。
 *
 * <p>异常语义必须对齐：{@code Method.invoke} 会把目标异常包进
 * {@code InvocationTargetException} —— 每个调用点都必须解包后按原规则分类，
 * 否则 {@code LuaError} 的错误对象与 traceback 会被包装层吞掉。
 */
public final class ContSupport {
    /** 是否显式请求 Continuation 模式（还需 API 可用）。 */
    private static final boolean REQUESTED = Boolean.getBoolean("luajvm.cont");

    private static final Class<?> CONT_CLASS;
    private static final Object SCOPE;
    /** {@code new Continuation(scope, Runnable)} */
    private static final Constructor<?> CTOR_CONT;
    /** {@code cont.run()}  -  进入/恢复 */
    private static final Method M_RUN;
    /** {@code Continuation.yield(scope)}  -  静态，挂起当前 */
    private static final Method M_YIELD;
    /** {@code cont.isDone()} */
    private static final Method M_IS_DONE;

    /** Continuation 协程模式是否可用（API 在位 + 已显式开启）。 */
    public static final boolean SUPPORTED;

    static {
        Class<?> cc = null;
        Object scope = null;
        Constructor<?> ctor = null;
        Method mRun = null, mYield = null, mDone = null;
        boolean ok = false;
        if (REQUESTED) {
            try {
                cc = Class.forName("jdk.internal.vm.Continuation");
                Class<?> sc = Class.forName("jdk.internal.vm.ContinuationScope");
                // ContinuationScope 是有公开构造器的类；scope 仅作身份标识
                //   （方法全为 final 且无调用点），直接调公开构造器造实例。
                Constructor<?> scopeCtor = sc.getConstructor(String.class);
                setAccessible(scopeCtor);
                scope = scopeCtor.newInstance("LuaCoroutineScope");
                ctor = cc.getConstructor(sc, Runnable.class);
                mRun = cc.getMethod("run");
                mYield = cc.getMethod("yield", sc);
                mDone = cc.getMethod("isDone");
                setAccessible(ctor);
                setAccessible(mRun);
                setAccessible(mYield);
                setAccessible(mDone);
                // 冒烟：真跑一次 yield/resume 往返，确认不只是"类在"而是"能用"。
                //   这一步不可省 - ART 上可能有同名类但语义不同，或 add-exports 缺失
                //   致 getMethod 成功而调用失败。
                final boolean[] hit = {false};
                // lambda 仅能捕获 final 局部量，故先固化（scope/mYield 上面还在赋值中）
                final Object fScope = scope;
                final Method fYield = mYield;
                Object probe = ctor.newInstance(scope, (Runnable) () -> {
                    hit[0] = true;
                    try {
                        fYield.invoke(null, fScope);
                    } catch (Throwable t) {
                        throw new AssertionError(t);
                    }
                });
                mRun.invoke(probe);
                ok = hit[0] && !(boolean) mDone.invoke(probe);
                if (ok) mRun.invoke(probe);   // 跑完，不留挂起对象
            } catch (Throwable t) {
                ok = false;
                if (Boolean.getBoolean("luajvm.contDebug")) {
                    System.err.println("[CONT-INIT-FAIL] " + t);
                    t.printStackTrace(System.err);
                }
            }
        }
        CONT_CLASS = cc;
        SCOPE = scope;
        CTOR_CONT = ctor;
        M_RUN = mRun;
        M_YIELD = mYield;
        M_IS_DONE = mDone;
        SUPPORTED = ok;
    }

    private ContSupport() {
    }

    private static void setAccessible(AccessibleObject ao) {
        try {
            ao.setAccessible(true);
        } catch (RuntimeException ignored) {
            // 未加 --add-exports 时会失败；后续调用同样失败，由 SUPPORTED=false 兜住
        }
    }

    /**
     * 把 {@code Method.invoke} 的 {@code InvocationTargetException} 解包成原异常并按
     * MethodHandle 路径的原规则分类。{@code LuaError} 与 {@code RuntimeException}/
     * {@code Error} 必须原样传播（前者要保住错误对象与 traceback，后者含
     * {@code StackOverflowError}）。
     */
    private static RuntimeException rethrow(Throwable t, String what) {
        Throwable cause = t instanceof InvocationTargetException ite && ite.getCause() != null
                ? ite.getCause() : t;
        if (cause instanceof LuaError le) throw le;
        if (cause instanceof RuntimeException re) throw re;
        if (cause instanceof Error err) throw err;
        return LuaErrors.errorObject(what + cause);
    }

    /** 造一个挂起态 Continuation（尚未开始执行）。 */
    public static Object create(Runnable body) {
        try {
            return CTOR_CONT.newInstance(SCOPE, body);
        } catch (Throwable t) {
            throw rethrow(t, "Continuation create failed: ");
        }
    }

    /**
     * 进入或恢复。
     *
     * <p>协程体抛出的异常原样穿过这里  -  调用方（{@code lua_resume}）负责捕获，
     * 与线程模式下 {@code runCoroutine} 的 catch 链等价。
     */
    public static void run(Object cont) {
        try {
            M_RUN.invoke(cont);
        } catch (Throwable t) {
            throw rethrow(t, "Continuation run failed: ");
        }
    }

    /** 挂起当前 Continuation（必须在 {@link #run} 的动态作用域内调用）。 */
    public static void yieldCurrent() {
        try {
            M_YIELD.invoke(null, SCOPE);
        } catch (Throwable t) {
            throw rethrow(t, "Continuation yield failed: ");
        }
    }

    /** 协程体是否已跑完（{@code true} 表示不可再 resume）。 */
    public static boolean isDone(Object cont) {
        try {
            return (boolean) M_IS_DONE.invoke(cont);
        } catch (Throwable t) {
            return true;
        }
    }
}
