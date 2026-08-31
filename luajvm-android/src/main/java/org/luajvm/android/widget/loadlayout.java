package org.luajvm.android.widget;

import android.content.Context;

import org.luajvm.android.api.LuaContext;

import org.luajvm.core.Globals;
import org.luajvm.core.LuaError;
import org.luajvm.core.LuaErrors;
import org.luajvm.core.LuaFunction;
import org.luajvm.core.LuaThread;
import org.luajvm.core.LuaValue;
import org.luajvm.core.Varargs;

public class loadlayout extends LuaFunction {
    private final Context mContext;
    private final Globals mGlobals;

    public loadlayout(LuaContext context) {
        mContext = context.getContext();
        mGlobals = context.getLuaState();
    }

    public loadlayout(Context context, Globals globals) {
        mContext = context;
        mGlobals = globals;
    }

    // java-only: 栈直调 1-3 参形态，免适配器逐行的 Varargs 往返
    //   （宿主的 onCreateViewHolder 走的是 loadlayout(layout, views) 两参形态）；
    //   其余参数个数回退 Varargs 路径（含参数校验与报错）
    @Override
    public int callOnStack(LuaThread L, int func, int narg) {
        if (narg < 1 || narg > 3) return -1;
        var loader = new LuaLayout(mContext);
        LuaValue result = switch (narg) {
            case 1 -> loader.load(L.stack[func + 1], mGlobals);
            case 2 -> loader.load(L.stack[func + 1], L.stack[func + 2].checktable());
            default -> loader.load(L.stack[func + 1], L.stack[func + 2].checktable(), L.stack[func + 3]);
        };
        L.stack[L.top] = result;
        L.top++;
        return 1;
    }

    @Override
    public Varargs call(Varargs args) {
        int n = args.narg();
        if (n < 1 || n > 3) {
            throw LuaErrors.errorObject("loadlayout: invalid arguments, expected 1-3, got " + n);
        }

        var loader = new LuaLayout(mContext);
        return switch (n) {
            case 1 -> loader.load(args.arg1(), mGlobals);
            case 2 -> loader.load(args.arg1(), args.arg(2).checktable());
            case 3 -> loader.load(args.arg1(), args.arg(2).checktable(), args.arg(3));
            default -> LuaValue.NIL;
        };
    }
}
