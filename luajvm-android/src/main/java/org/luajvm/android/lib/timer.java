package org.luajvm.android.lib;

import org.luajvm.android.api.LuaContext;
import org.luajvm.android.runtime.LuaTimer;

import org.luajvm.bind.Coercion;
import org.luajvm.core.LuaError;
import org.luajvm.core.LuaErrors;
import org.luajvm.core.LuaFunction;
import org.luajvm.core.LuaValue;
import org.luajvm.core.Varargs;

public class timer extends LuaFunction {
    private final LuaContext mContext;

    public timer(LuaContext context) {
        mContext = context;
    }

    @Override
    public Varargs call(Varargs args) {
        LuaValue func = args.arg1();
        int delay = args.arg(2).toint();
        int period = args.arg(3).toint();
        // 先校验函数：period 检查排在前面会让 timer(nil) 报成 bad argument #3，指错参数位
        LuaFunction fn = func.checkfunction();
        // 缺参经 NIL.toint() 变 0：period=0 直达 scheduleWithFixedDelay 会抛裸
        //   IllegalArgumentException，这里给出语义化 LuaError
        if (period <= 0) {
            throw LuaErrors.errorObject("bad argument #3 to 'timer' (period must be positive, got " + period + ")");
        }
        LuaValue[] argv = new LuaValue[Math.max(args.narg() - 3, 0)];
        for (int i = 0; i < argv.length; i++) {
            argv[i] = args.arg(i + 4);
        }

        LuaTimer luaTimer = new LuaTimer(mContext, fn, Varargs.of(argv));
        luaTimer.start(delay, period);
        return Coercion.toLua(luaTimer);
    }
}
