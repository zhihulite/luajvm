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

public class thread extends LuaFunction implements LuaGcable {
    private final LuaContext mContext;
    /**
     * 任务提交即登记，gc 时取消：任务 lambda 捕获 mContext（Activity）与 Lua 闭包，
     * 不取消的话引擎销毁后任务仍把整棵 Activity 钉到自然结束（HTTP 最长 30s）。
     * 并发集合：Lua 可在 IO 线程再调 thread() 提交新任务。
     */
    private final Set<Future<?>> mFutures = Collections.newSetFromMap(new ConcurrentHashMap<>());

    public thread(LuaContext context) {
        mContext = context;
        context.regGc(this);
    }

    @Override
    public Varargs call(Varargs args) {
        LuaValue func = args.arg1();
        // 零参调用时 narg()-1 为 -1，不夹紧会抛 NegativeArraySizeException
        LuaValue[] argv = new LuaValue[Math.max(args.narg() - 1, 0)];
        for (int i = 0; i < argv.length; i++) {
            argv[i] = args.arg(i + 2);
        }

        for (Future<?> f : new ArrayList<>(mFutures)) {
            if (f.isDone()) mFutures.remove(f);
        }

        Future<?> future = LuaScheduler.getInstance().runOnIo(() -> {
            try {
                LuaCall.invoke(func, Varargs.of(argv));
            } catch (Exception e) {
                mContext.sendError("thread", e);
            }
        });
        mFutures.add(future);
        return Coercion.toLua(future);
    }

    @Override
    public void gc() {
        for (Future<?> future : mFutures) {
            future.cancel(true);
        }
        mFutures.clear();
    }

    @Override
    public boolean isGc() {
        return false;
    }
}
