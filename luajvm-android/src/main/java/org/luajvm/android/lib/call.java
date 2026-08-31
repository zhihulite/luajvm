package org.luajvm.android.lib;

import android.app.Activity;
import android.content.Context;
import android.os.Handler;
import android.os.Looper;

import org.luajvm.android.api.LuaContext;

import org.luajvm.core.Globals;
import org.luajvm.core.LuaFunction;
import org.luajvm.core.LuaValue;
import org.luajvm.core.Varargs;
import org.luajvm.vm.LuaCall;

/**
 * 在 UI 线程中调用 Lua 函数。
 * Activity 场景用 {@code runOnUiThread}，Service 等场景回落到 {@code Handler(主线程)}。
 */
public class call extends LuaFunction {
    private final LuaContext mContext;
    private final Globals mGlobals;
    private final Handler mMainHandler;

    public call(LuaContext context) {
        mContext = context;
        mGlobals = context.getLuaState();
        mMainHandler = new Handler(Looper.getMainLooper());
    }

    @Override
    public Varargs call(Varargs args) {
        final LuaValue func = args.arg1();
        // 零参调用时 narg()-1 为 -1，不夹紧会抛 NegativeArraySizeException
        final int extraCount = Math.max(args.narg() - 1, 0);
        final LuaValue[] argv = new LuaValue[extraCount];
        for (int i = 0; i < extraCount; i++) {
            argv[i] = args.arg(i + 2);
        }

        Runnable action = () -> {
            try {
                LuaValue target = func.isfunction() ? func : mGlobals.get(func);
                if (target.isfunction()) {
                    LuaCall.invoke(target, Varargs.of(argv));
                }
            } catch (Exception e) {
                mContext.sendError("call", e);
            }
        };
        Context ctx = mContext.getContext();
        if (ctx instanceof Activity activity) {
            activity.runOnUiThread(action);
        } else {
            mMainHandler.post(action);
        }
        return LuaValue.NONE;
    }
}
