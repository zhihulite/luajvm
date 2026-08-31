package org.luajvm.android.runtime;

import org.luajvm.android.api.CallLuaFunction;
import org.luajvm.android.api.LuaContext;
import org.luajvm.android.api.LuaGcable;

import org.luajvm.core.LuaFunction;
import org.luajvm.core.LuaValue;
import org.luajvm.core.Varargs;
import org.luajvm.vm.LuaCall;

import java.util.concurrent.ScheduledFuture;

/**
 * Lua 定时器，基于 ScheduledExecutorService 实现。
 */
public class LuaTimer implements LuaGcable {

    /** 空实参（{@code Varargs.of(空数组)} 本就归一为 {@link LuaValue#NONE}，只读可共享） */
    private static final Varargs NO_ARGS = LuaValue.NONE;

    private final LuaContext mLuaContext;
    private final LuaValue mFunction;
    private Varargs mArgs;
    private volatile boolean mEnabled = true;
    private volatile boolean mGced;
    private volatile long mPeriod;
    private ScheduledFuture<?> mFuture;

    @CallLuaFunction(value = CallLuaFunction.Thread.SCHEDULER,
            note = "tick() 跑在 LuaScheduler 的单线程调度器上，不可直接碰 View")
    public LuaTimer(LuaContext context, LuaFunction function, Varargs args) {
        mLuaContext = context;
        mFunction = function;
        mArgs = args != null ? args : NO_ARGS;
        if (context != null) {
            context.regGc(this);
        }
    }

    /**
     * 启动定时器
     */
    public void start(long delayMs, long periodMs) {
        stop();
        mPeriod = periodMs;
        mFuture = LuaScheduler.getInstance().scheduleWithFixedDelay(this::tick, delayMs, periodMs);
    }

    /**
     * 启动一次性定时器
     */
    public void start(long delayMs) {
        stop();
        mPeriod = 0; // 一次性：清掉前次周期调度残留的 mPeriod，getPeriod 不再报旧值
        mFuture = LuaScheduler.getInstance().schedule(this::tick, delayMs);
    }

    /**
     * 停止定时器
     */
    public void stop() {
        if (mFuture != null) {
            mFuture.cancel(false);
            mFuture = null;
        }
    }

    private void tick() {
        if (!mEnabled || mGced) return;
        try {
            LuaCall.invoke(mFunction, mArgs);
        } catch (Exception e) {
            if (mLuaContext != null) {
                mLuaContext.sendError("LuaTimer", e);
            } else {
                LuaConfig.logError("LuaTimer", e);
            }
        }
    }

    public boolean isEnabled() {
        return mEnabled;
    }

    public boolean getEnabled() {
        return mEnabled;
    }

    public void setEnabled(boolean enabled) {
        mEnabled = enabled;
    }

    public long getPeriod() {
        return mPeriod;
    }

    /**
     * 生效周期只在 start(delay, period) 时确定：运行中调用本方法只更新 getPeriod 的
     * 读数，不改动已在排程的周期；要换周期须 stop() 后重新 start()。
     */
    public void setPeriod(long period) {
        mPeriod = period;
    }

    public boolean isRunning() {
        // isDone 排掉已完成的 schedule()：一次性 timer 跑完后不再算"运行中"
        return mFuture != null && !mFuture.isCancelled() && !mFuture.isDone();
    }

    public void setArgs(LuaValue... args) {
        mArgs = args != null ? Varargs.of(args) : NO_ARGS;
    }

    @Override
    public void gc() {
        stop();
        mGced = true;
    }

    @Override
    public boolean isGc() {
        return mGced;
    }
}
