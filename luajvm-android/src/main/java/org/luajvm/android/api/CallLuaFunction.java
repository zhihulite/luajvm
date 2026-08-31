package org.luajvm.android.api;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 标记「这个方法/构造器收下的 Lua 函数会被事后回调」，并声明**回调跑在哪个线程**。
 *
 * <p><b>为什么需要标</b>：收 {@code LuaFunction} 作回调的入口遍布全仓，回调线程
 * 至少四种 —— 主线程、{@code LuaScheduler} 的调度线程、Android {@code Filter} 的工作
 * 线程、绘制线程；甚至同一个回调（{@code LuaWebView} 的 adsFilter）在
 * {@code shouldOverrideUrlLoading} 是主线程、在 {@code shouldInterceptRequest} 是后台
 * 线程。改这些代码的人必须知道自己身处哪个线程，否则容易做出错误假设
 * （比如以为能直接碰 View，或以为不需要进执行区就能读写 {@code LuaTable}）。
 *
 * <p><b>线程安全本身不用你操心</b>：{@code LuaCall.invoke} 会检查
 * {@code Globals.isExecutingOnCurrentThread()}，不在执行区时自动走
 * {@code globals.invoke} 进入 {@code ReentrantLock} 串行化。所以本注解**不是**安全警告，
 * 而是让"回调在哪跑"这件事显式可读 —— 需要自己动手的场合只有一个：
 * 在非执行区的线程上**直接读写 {@code LuaTable}**（不经 {@code LuaCall}），
 * 那必须自己进执行区，参见 {@code LuaArrayAdapter.ArrayFilter} 的 {@code guarded(...)}。
 *
 * <p><b>门禁</b>：{@code AndroidStaticGatesTest.hostContract} 断言"凡是形参含
 * {@code LuaFunction} 的 public 方法/构造器都必须带本注解"，新增回调入口时漏标会
 * FAIL —— 这是本注解不退化为空壳的机械保证。
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.METHOD, ElementType.CONSTRUCTOR})
public @interface CallLuaFunction {

    /** 回调实际执行的线程。 */
    Thread value();

    /** 补充说明，例如"由哪个 Android 回调触发"。 */
    String note() default "";

    /** 回调线程的取值。 */
    enum Thread {
        /** Android 主线程（UI 线程）。可直接碰 View。 */
        MAIN,
        /** {@code LuaScheduler} 的单线程调度器。不可直接碰 View。 */
        SCHEDULER,
        /** {@code LuaScheduler} 的 IO 线程池。不可直接碰 View。 */
        IO,
        /** Android {@code Filter} 的工作线程。不可直接碰 View。 */
        FILTER_WORKER,
        /** 执行绘制的线程（通常是主线程，硬件加速下可能是 RenderThread）。 */
        DRAW,
        /** 随触发点而变 —— 必须在 {@link #note()} 里写清各自是哪个。 */
        MIXED,
    }
}
