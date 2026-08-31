package org.luajvm.android.lib;

import org.luajvm.android.api.LuaContext;

import org.luajvm.core.Globals;
import org.luajvm.core.LuaFunction;
import org.luajvm.core.LuaValue;
import org.luajvm.core.Varargs;
import org.luajvm.vm.LuaCall;

public class print extends LuaFunction {
    private final LuaContext mContext;
    private final Globals mGlobals;

    public print(LuaContext context) {
        mContext = context;
        mGlobals = context.getLuaState();
    }

    public print(LuaContext context, Globals globals) {
        mContext = context;
        mGlobals = globals;
    }

    @Override
    public Varargs call(Varargs args) {
        // java diff: 经 spi.BaseLibrary.tostringFn() 取（原直读 lib.BaseLib.tostring 字段）。
        //   Globals.baselib 的类型已收敛为 spi.BaseLibrary，core 不再反向依赖 lib。
        LuaValue tostring = mGlobals.baselib.tostringFn();
        StringBuilder buf = new StringBuilder();
        for (int i = 1, n = args.narg(); i <= n; i++) {
            // java diff: 分隔符是 4 个空格，C 的 luaB_print 用 '\t'。
            // 这是 Lua 可观察输出，改成 '\t' 会动到已上线脚本的日志文本，勿当 bug 顺手改。
            if (i > 1) buf.append("    ");
            buf.append(LuaCall.invoke(tostring, args.arg(i)).arg1().toJavaString());
        }
        mContext.sendMsg(buf.toString());
        return LuaValue.NONE;
    }
}
