// ref: ldblib.c
// diff: 核心函数拆分到DebugInfo/DebugHook; upvalueid用UpVal/LuaCClosure槽位对象代替C指针; UpVal.get()/set()保持共享upvalue同步
package org.luajvm.lib;

import org.luajvm.core.LuaDebug;
import org.luajvm.core.Globals;
import org.luajvm.core.LuaCClosure;
import org.luajvm.core.LuaClosure;
import org.luajvm.core.LuaErrors;
import org.luajvm.core.LuaFunction;
import org.luajvm.core.LuaLightUserdata;
import org.luajvm.core.LuaString;
import org.luajvm.core.LuaTable;
import org.luajvm.core.LuaUserdata;
import org.luajvm.core.LuaValue;
import org.luajvm.core.Varargs;
import org.luajvm.spi.DebugTracer;

public class DebugLib extends LuaFunction implements DebugTracer {
    public static boolean CALLS, TRACE;
    Globals globals;

    public DebugLib() {
    }

    // ldblib.c: db_upvalueid
    private static LuaValue upvalueId(LuaValue func, int up) {
        if (func instanceof LuaClosure cl) {
            if (up < 1 || up > cl.upvals.length) return LuaValue.NIL;
            return new LuaLightUserdata(cl.upvals[up - 1]);
        }
        if (func instanceof LuaCClosure cc) {
            Object id = cc.upvalueid(up);
            return id != null ? new LuaLightUserdata(id) : LuaValue.NIL;
        }
        if (func instanceof LuaFunction) {
            return LuaValue.NIL;
        }
        LuaErrors.typeError(1, func, "function");
        return LuaValue.NIL;
    }

    public void onCall(LuaFunction f) {
    }

    public void onReturn() {
    }

    // ldblib.c: luaopen_debug
    @Override
    public Varargs call(Varargs args) {
        LuaValue modname = args.arg1();
        LuaValue env = args.arg(2);
        globals = env.checkglobals();
        // ldebug.c: luaD_hook 直接读 L->hook；Java 的 ldblib 层多一跳 HOOKF 间接
        //   （hook 函数按线程存于 registry 的 _HOOKKEY 表），登记进状态使 vm/LuaVM.callHook 只依赖 core。
        globals.hookResolver = DebugHook::resolveHookFunction;
        LuaTable debug = new LuaTable();
        debug.bindGlobals(globals);
        debug.set("debug", new DebugHook.DbDebugFn());
        debug.set("gethook", new DebugHook.DbGetHookFn());
        debug.set("getinfo", new DebugInfo.DbGetInfoFn());
        debug.set("getlocal", new DebugInfo.DbGetLocalFn());
        debug.set("getregistry", new DebugInfo.DbGetRegistryFn());
        debug.set("sethook", new DebugHook.DbSetHookFn());
        debug.set("setlocal", new DebugInfo.DbSetLocalFn());
        DebugHook.DbTracebackFn traceback = new DebugHook.DbTracebackFn();
        traceback.bindGlobals(globals);
        debug.set("traceback", traceback);
        debug.set("getmetatable", new DbGetMetatableFn());
        debug.set("setmetatable", new DbSetMetatableFn());
        debug.set("getupvalue", new DbGetUpvalueFn());
        debug.set("setupvalue", new DbSetUpvalueFn());
        debug.set("getuservalue", new DbGetUserValueFn());
        debug.set("setuservalue", new DbSetUserValueFn());
        debug.set("upvalueid", new DbUpvalueIdFn());
        debug.set("upvaluejoin", new DbUpvalueJoinFn());
        env.set("debug", debug);
        if (!env.get("package").isnil()) env.get("package").get("loaded").set("debug", debug);
        return debug;
    }

    // ldblib.c: db_getmetatable
    static class DbGetMetatableFn extends LuaFunction {
        @Override
        public Varargs call(Varargs args) {
            LuaValue arg = args.arg1();
            LuaValue mt = arg.getmetatable();
            return mt == null ? LuaValue.NIL : mt;
        }
    }

    // ldblib.c: db_setmetatable
    static class DbSetMetatableFn extends LuaFunction {
        @Override
        public Varargs call(Varargs args) {
            LuaValue a = args.arg1();
            LuaValue b = args.arg(2);
            LuaValue mt = b.isnil() ? null : b.checktable();
            return a.setmetatable(mt);
        }
    }

    // ldblib.c: db_getupvalue
    static class DbGetUpvalueFn extends LuaFunction {
        @Override
        public Varargs call(Varargs args) {
            LuaFunction func = args.checkfunction();
            int up = args.checkint(2);
            if (func.isLclosure()) {
                LuaClosure c = func.checkclosure();
                if (up > 0 && up <= c.upvals.length) {
                    LuaString name = LuaDebug.findUpvalue(c, up);
                    LuaValue v = c.upvals[up - 1].get();
                    return LuaValue.varargsOf(name != null ? name : LuaString.newStr("(no name)"), v);
                }
            } else if (func.isCclosure()) {
                LuaCClosure c = (LuaCClosure) func;
                if (up > 0 && up <= c.nupvalues()) {
                    return LuaValue.varargsOf(LuaString.newStr(""), c.upvalue(up));
                }
            }
            return LuaValue.NIL;
        }
    }

    // ldblib.c: db_setupvalue
    static class DbSetUpvalueFn extends LuaFunction {
        @Override
        public Varargs call(Varargs args) {
            LuaFunction func = args.checkfunction();
            int up = args.checkint(2);
            LuaValue value = args.arg(3);
            if (func.isLclosure()) {
                LuaClosure c = func.checkclosure();
                if (up > 0 && up <= c.upvals.length) {
                    LuaString name = LuaDebug.findUpvalue(c, up);
                    c.upvals[up - 1].set(value);
                    return name != null ? name : LuaString.newStr("(no name)");
                }
            } else if (func.isCclosure()) {
                LuaCClosure c = (LuaCClosure) func;
                if (c.setupvalue(up, value)) return LuaString.newStr("");
            }
            return LuaValue.NIL;
        }
    }

    // ldblib.c: db_getuservalue
    static class DbGetUserValueFn extends LuaFunction {
        @Override
        public Varargs call(Varargs args) {
            LuaValue arg = args.arg1();
            if (!arg.isuserdata()) return LuaValue.NIL;
            int n = args.optint(2, 1);
            if (arg instanceof LuaUserdata ud) {
                LuaValue v = ud.getuservalue(n);
                if (v != null) return LuaValue.varargsOf(v, LuaValue.TRUE);
            }
            return LuaValue.NIL;
        }
    }

    // ldblib.c: db_setuservalue
    static class DbSetUserValueFn extends LuaFunction {
        @Override
        public Varargs call(Varargs args) {
            LuaValue a = args.arg1();
            LuaValue b = args.arg(2);
            int n = args.optint(3, 1);

            if (a instanceof LuaLightUserdata) {
                LuaErrors.argError(1, "full userdata expected, got light userdata");
            }
            if (!(a instanceof LuaUserdata)) {
                LuaErrors.typeError(1, args, "userdata");
            }
            LuaValue.checkany(2, args);
            if (!((LuaUserdata) a).setuservalue(n, b)) return LuaValue.NIL;
            return a;
        }
    }

    // ldblib.c: db_upvalueid
    static class DbUpvalueIdFn extends LuaFunction {
        @Override
        public Varargs call(Varargs args) {
            LuaValue func = args.arg1();
            int up = args.checkint(2);

            return upvalueId(func, up);
        }
    }

    // ldblib.c: db_upvaluejoin
    static class DbUpvalueJoinFn extends LuaFunction {
        @Override
        public Varargs call(Varargs args) {
            LuaValue f1 = args.arg1();
            int n1 = args.checkint(2);
            LuaValue f2 = args.arg(3);
            int n2 = args.checkint(4);
            if (upvalueId(f1, n1).isnil()) LuaErrors.argError(2, "invalid upvalue index");
            if (upvalueId(f2, n2).isnil()) LuaErrors.argError(4, "invalid upvalue index");
            if (!(f1 instanceof LuaClosure)) LuaErrors.argError(1, "Lua function expected");
            if (!(f2 instanceof LuaClosure)) LuaErrors.argError(3, "Lua function expected");
            LuaClosure c1 = (LuaClosure) f1;
            LuaClosure c2 = (LuaClosure) f2;

            c1.upvals[n1 - 1] = c2.upvals[n2 - 1];
            return LuaValue.NONE;
        }
    }


}
