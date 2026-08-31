package org.luajvm.android.lib;

import org.luajvm.android.api.LuaContext;

import org.luajvm.core.Globals;
import org.luajvm.core.LuaFunction;
import org.luajvm.core.LuaValue;
import org.luajvm.core.Varargs;
import org.luajvm.vm.LuaCall;

public class printf extends LuaFunction {
    private final LuaContext mContext;
    private final Globals mGlobals;

    public printf(LuaContext context) {
        mContext = context;
        mGlobals = context.getLuaState();
    }

    public printf(LuaContext context, Globals globals) {
        mContext = context;
        mGlobals = globals;
    }

    @Override
    public Varargs call(Varargs args) {
        String formatted = LuaCall.invoke(mGlobals.get("string").get("format"), args).arg1().toJavaString();
        mContext.sendMsg(formatted);
        return LuaValue.NONE;
    }
}
