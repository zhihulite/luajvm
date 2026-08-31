package org.luajvm.android.lib;

import org.luajvm.android.api.LuaContext;
import org.luajvm.android.api.LuaGcable;
import org.luajvm.android.runtime.LuaScheduler;

import org.luajvm.bind.Coercion;
import org.luajvm.core.LuaFunction;
import org.luajvm.core.LuaValue;
import org.luajvm.core.Varargs;
import org.luajvm.vm.LuaCall;

import java.util.Collections;
import java.util.ArrayList;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledFuture;

public class task extends LuaFunction implements LuaGcable {
    private final LuaContext mContext;
    /**
     * task 在 registerBaseModules 里只注册一个实例，Lua 每调一次就提交一个任务；
     * 只存最后一个 Future 会让 gc() 漏掉更早的任务，其 lambda 捕获的 mContext
     * 会把 Activity 钉到任务自己结束。并发集合同时解决跨线程写、主线程读的可见性。
     */
    private final Set<Future<?>> mFutures = Collections.newSetFromMap(new ConcurrentHashMap<>());

    public task(LuaContext context) {
        mContext = context;
        context.regGc(this);
    }

    @Override
    public Varargs call(Varargs args) {
        LuaValue func = args.arg1();
        int n = args.narg();
        LuaValue callback = n > 1 ? args.arg(n) : LuaValue.NIL;
        // 末位是回调时两种模式同款减 2：睡眠模式只减 1 会把 task(1000, cb) 的 cb
        //   当实参回传给自身。减 2 = 跳过 ms 与回调位
        int argCount = n - (callback.isfunction() ? 2 : 1);
        LuaValue[] as = new LuaValue[Math.max(argCount, 0)];
        for (int i = 0; i < as.length; i++) {
            as[i] = args.arg(i + (func.isnumber() ? 2 : 1));
        }
        // 每次提交先清掉已结束的任务：否则这个集合只增不减，长命 Activity 上
        //   反复调 task() 会把完成的 Future 一直攒着，成为新的泄漏。
        //   removeIf 需 API 24；按快照逐条删已完成的，弱一致迭代不抛 CME。
        for (Future<?> f : new ArrayList<>(mFutures)) {
            if (f.isDone()) mFutures.remove(f);
        }
        Future<?> future;
        if (func.isnumber()) {
            // 睡眠型经 timer 池延迟到点，不占 IO 线程：IO 池是共享固定池（CPU 数、
            //   最少 2 个），几个 task(ms) 睡在池里会饿死全部后台任务
            long delayMs = func.tolong();
            future = LuaScheduler.getInstance().schedule(() ->
                    deliverResult(LuaValue.varargsOf(as), callback), delayMs);
        } else {
            future = LuaScheduler.getInstance().runOnIo(() -> {
                Varargs result;
                try {
                    result = LuaCall.invoke(func, Varargs.of(as));
                } catch (Exception e) {
                    mContext.sendError("task", e);
                    result = LuaValue.varargsOf(LuaValue.NIL, LuaValue.valueOf(e.toString()));
                }
                deliverResult(result, callback);
            });
        }
        mFutures.add(future);
        return Coercion.toLua(future);
    }

    private void deliverResult(Varargs result, LuaValue callback) {
        if (!callback.isfunction()) return;
        LuaScheduler.getInstance().runOnMain(() -> {
            try {
                LuaCall.invoke(callback, result);
            } catch (Exception e) {
                mContext.sendError("task", e);
            }
        });
    }

    @Override
    public void gc() {
        for (Future<?> future : mFutures) {
            // schedule 型任务跑在共享 timer 线程上，cancel(true) 的中断会残留在
            //   线程上祸及后续无关定时任务，只做未触发取消
            boolean mayInterrupt = !(future instanceof ScheduledFuture);
            future.cancel(mayInterrupt);
        }
        mFutures.clear();
    }

    @Override
    public boolean isGc() {
        return false;
    }
}
