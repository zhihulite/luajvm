package org.luajvm.android.runtime;

import android.os.Handler;
import android.os.Looper;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;

/**
 * 统一调度器，提供 IO 线程池、定时调度、主线程 Handler。
 */
public final class LuaScheduler {

    /**
     * 惰性 holder：JVM 保证类初始化线程安全且恰好执行一次；
     * {@code synchronized} 阻塞会 pin 虚拟线程的 carrier。
     */
    private static final class Holder {
        static final LuaScheduler INSTANCE = new LuaScheduler();
    }

    private final ExecutorService mIoPool;
    private final ScheduledExecutorService mTimerPool;
    private final Handler mMainHandler;

    private LuaScheduler() {
        int poolSize = LuaConfig.getIoPoolSize();
        mIoPool = Executors.newFixedThreadPool(poolSize, daemonFactory("LuaIO"));
        mTimerPool = Executors.newSingleThreadScheduledExecutor(daemonFactory("LuaTimer"));
        mMainHandler = new Handler(Looper.getMainLooper());
    }

    /**
     * 命名 daemon 线程工厂，供 IO 池与定时池共用。
     */
    private static ThreadFactory daemonFactory(String name) {
        return r -> {
            Thread t = new Thread(r, name);
            t.setDaemon(true);
            return t;
        };
    }

    /**
     * 获取全局单例
     */
    public static LuaScheduler getInstance() {
        return Holder.INSTANCE;
    }

    /**
     * 在 IO 线程执行任务
     */
    public Future<?> runOnIo(Runnable task) {
        return mIoPool.submit(() -> {
            try {
                task.run();
            } catch (Exception e) {
                LuaConfig.logError("LuaScheduler.IO", e);
            }
        });
    }

    /**
     * 在 IO 线程执行任务，完成后在主线程回调
     */
    public <T> Future<?> runOnIo(Producer<T> background, Consumer<T> callback) {
        return mIoPool.submit(() -> {
            T result;
            try {
                result = background.produce();
            } catch (Exception e) {
                LuaConfig.logError("LuaScheduler.IO", e);
                mMainHandler.post(() -> callback.accept(null));
                return;
            }
            mMainHandler.post(() -> callback.accept(result));
        });
    }

    /**
     * 在 IO 线程执行任务，完成后直接在 IO 线程回调，不经过主线程。
     * 适用于非 UI 场景，或调用方已在子线程手动等待结果。
     */
    public <T> Future<?> runOnIoDirect(Producer<T> background, Consumer<T> callback) {
        return mIoPool.submit(() -> {
            try {
                T result = background.produce();
                callback.accept(result);
            } catch (Exception e) {
                LuaConfig.logError("LuaScheduler.IO", e);
                callback.accept(null);
            }
        });
    }

    /**
     * 延迟执行一次性任务
     */
    public ScheduledFuture<?> schedule(Runnable task, long delay, TimeUnit unit) {
        return mTimerPool.schedule(wrapSafe(task), delay, unit);
    }

    /**
     * 延迟执行一次性任务（毫秒）
     */
    public ScheduledFuture<?> schedule(Runnable task, long delayMs) {
        return schedule(task, delayMs, TimeUnit.MILLISECONDS);
    }

    /**
     * 固定延迟周期执行：上一次执行完成后才重新计时（scheduleWithFixedDelay 语义）
     */
    public ScheduledFuture<?> scheduleWithFixedDelay(Runnable task, long initialDelay, long period, TimeUnit unit) {
        return mTimerPool.scheduleWithFixedDelay(wrapSafe(task), initialDelay, period, unit);
    }

    /**
     * 固定延迟周期执行（毫秒）
     */
    public ScheduledFuture<?> scheduleWithFixedDelay(Runnable task, long initialDelayMs, long periodMs) {
        return scheduleWithFixedDelay(task, initialDelayMs, periodMs, TimeUnit.MILLISECONDS);
    }

    /**
     * 在主线程执行
     */
    public void runOnMain(Runnable task) {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            task.run();
        } else {
            mMainHandler.post(task);
        }
    }

    /**
     * 在主线程延迟执行
     */
    public void runOnMainDelayed(Runnable task, long delayMs) {
        mMainHandler.postDelayed(task, delayMs);
    }

    /**
     * 移除主线程回调
     */
    public void removeMainCallback(Runnable task) {
        mMainHandler.removeCallbacks(task);
    }

    /**
     * 移除所有主线程回调和消息
     */
    public void removeMainCallbacksAndMessages() {
        mMainHandler.removeCallbacksAndMessages(null);
    }

    /**
     * 关闭所有线程池
     */
    public void shutdown() {
        mIoPool.shutdownNow();
        mTimerPool.shutdownNow();
        mMainHandler.removeCallbacksAndMessages(null);
    }

    /**
     * 包装任务防止异常导致线程终止
     */
    private static Runnable wrapSafe(Runnable task) {
        return () -> {
            try {
                task.run();
            } catch (Exception e) {
                LuaConfig.logError("LuaScheduler", e);
            }
        };
    }

    @FunctionalInterface
    public interface Producer<T> {
        T produce() throws Exception;
    }

    @FunctionalInterface
    public interface Consumer<T> {
        void accept(T value);
    }
}
