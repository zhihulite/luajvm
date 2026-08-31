// ref: ldblib.c (db_getinfo/db_getlocal/db_setlocal/db_getregistry)
// diff: Info 类替代 lua_Debug 结构体；ldebug.c 的名字/行号解析已下移到 core.LuaDebug（见其类注释）
package org.luajvm.lib;

import org.luajvm.core.CallInfo;
import org.luajvm.core.Globals;
import org.luajvm.core.LuaClosure;
import org.luajvm.core.LuaDebug;
import org.luajvm.core.LuaErrors;
import org.luajvm.core.LuaFunction;
import org.luajvm.core.LuaInteger;
import org.luajvm.core.LuaString;
import org.luajvm.core.LuaTable;
import org.luajvm.core.LuaThread;
import org.luajvm.core.LuaValue;
import org.luajvm.core.Prototype;
import org.luajvm.core.Varargs;

/**
 * {@code ldblib.c} 的 {@code debug.getinfo}/{@code getlocal}/{@code setlocal}/
 * {@code getregistry} 实现。
 *
 * <p>{@code ldebug.c} 的名字解析与行号族（{@code luaO_chunkid}、
 * {@code luaG_getfuncline}、{@code getobjname}、{@code varinfo}、{@code funcnamefromcall}…）
 * 在 {@link LuaDebug}——C 里它们属于核心（{@code lobject.c}/{@code ldebug.c}），
 * 被 {@code luaG_typeerror} 等错误路径直接调用，须与 core 错误路径同层。
 * 本类保留静态转发（宿主与 ltests 的调用点无需改动）。
 */
public final class DebugInfo {

    private DebugInfo() {
    }

    // 下述 ldblib.c 实现直接调 core.LuaDebug（ldebug.c 家族）。

    // ldebug.c: lua_Debug
    public static class Frame {
        public String name;
        public String namewhat;
        public String what;
        public String source;
        public int currentline;
        public int linedefined;
        public int lastlinedefined;
        public int nups;
        public int numparams;
        public boolean isvararg;
        public boolean istailcall;
        public String short_src;
    }

    // Info
    public static class Info {
        public String name, namewhat, what, source, short_src;
        public int currentline = -1, linedefined = -1, lastlinedefined = -1;
        public int ftransfer, ntransfer;
        public int extraargs;
        public int nups, numparams;
        public boolean isvararg, istailcall;
        public LuaFunction func;

        public static Info fromFunction(LuaFunction f) {
            Info i = new Info();
            i.func = f;
            if (f.isLclosure()) {
                LuaClosure cl = (LuaClosure) f;
                Prototype p = cl.p;
                if (p != null) {
                    i.what = p.linedefined == 0 ? "main" : "Lua";
                    String src = p.source != null ? p.source.toJavaString() : "=?";
                    i.source = src;
                    i.short_src = LuaDebug.chunkid(src);
                    i.linedefined = p.linedefined;
                    i.lastlinedefined = p.lastlinedefined;
                    i.nups = p.sizeupvalues;
                    i.numparams = p.numparams;
                    i.isvararg = p.isVararg();  // ldebug.c: auxgetinfo 'u'
                }
            } else {

                i.what = "C";
                i.source = "=[C]";
                i.short_src = "[C]";
                i.linedefined = -1;
                i.lastlinedefined = -1;
                i.nups = f.nupvalues();
                i.numparams = 0;
                i.isvararg = true;
            }
            return i;
        }
    }

    // ldblib.c: db_getinfo
    public static class DbGetInfoFn extends LuaFunction {
        @Override
        public Varargs call(Varargs args) {
            int arg = args.arg(1) instanceof LuaThread ? 2 : 1;
            LuaThread targetThread = arg == 2 ? args.checkthread(1) : null;
            LuaValue obj = args.arg(arg);
            // ldblib.c: luaL_optstring(L, arg+2, "flnSrtu")
            String options = args.arg(arg + 1).optJavaString("flnSrtu");
            // ldblib.c: luaL_argcheck(options[0] != '>', arg+2, "invalid option '>'")
            //   （'>' 是 lua_getinfo 的"取栈上函数"前缀，Lua 层不可用）
            if (!options.isEmpty() && options.charAt(0) == '>') {
                LuaErrors.argError(arg + 2, "invalid option '>'");
            }
            Info info;
            Globals globals = targetThread != null && targetThread.l_G != null
                    ? targetThread.l_G
                    : ownerGlobals;

            if (obj.isnumber()) {
                int level = obj.checkint();

                LuaThread levelThread = targetThread != null ? targetThread : (globals != null ? globals.running : null);
                if (globals == null || globals.getFrameAtLevel(levelThread, level, false) == null) {
                    return LuaValue.NIL;
                }
                info = infoFromLevel(globals, levelThread, level);
            } else if (obj instanceof LuaFunction fn) {
                info = Info.fromFunction(fn);
            } else {
                // ldblib.c: luaL_checkinteger(L, arg+1)  -  既非函数也非数字即参数错误
                LuaErrors.typeError(arg, args, "number");
                return LuaValue.NIL;
            }
            // ldblib.c: if (!lua_getinfo(...)) return luaL_argerror(L, arg+2, "invalid option")
            //   -  C 在取到帧之后才报未知 option（level 越界先返回 nil）
            for (int i = 0; i < options.length(); i++) {
                if ("flnSrtuL".indexOf(options.charAt(i)) < 0) {
                    LuaErrors.argError(arg + 2, "invalid option");
                }
            }
            LuaTable t = new LuaTable();
            if (options.contains("n") && info.name != null)
                t.set("name", LuaString.newStr(info.name));
            if (options.contains("n"))
                t.set("namewhat", LuaString.newStr(info.namewhat != null ? info.namewhat : ""));
            if (options.contains("f") && info.func != null) t.set("func", info.func);
            if (options.contains("l")) t.set("currentline", LuaInteger.valueOf(info.currentline));
            if (options.contains("S")) {
                t.set("what", LuaString.newStr(info.what != null ? info.what : "Lua"));
                t.set("source", LuaString.newStr(info.source != null ? info.source : "=?"));
                t.set("short_src", LuaString.newStr(info.short_src != null ? info.short_src : "=?"));
                t.set("linedefined", LuaInteger.valueOf(info.linedefined));
                t.set("lastlinedefined", LuaInteger.valueOf(info.lastlinedefined));
            }
            if (options.contains("u")) {
                t.set("nups", LuaInteger.valueOf(info.nups));
                t.set("nparams", LuaInteger.valueOf(info.numparams));
                t.set("isvararg", info.isvararg ? LuaValue.TRUE : LuaValue.FALSE);
            }
            if (options.contains("r")) {
                t.set("ftransfer", LuaInteger.valueOf(info.ftransfer));
                t.set("ntransfer", LuaInteger.valueOf(info.ntransfer));
            }

            if (options.contains("t")) {
                t.set("istailcall", info.istailcall ? LuaValue.TRUE : LuaValue.FALSE);
                t.set("extraargs", LuaInteger.valueOf(info.extraargs));
            }

            if (options.contains("L") && info.func instanceof LuaClosure cl)
                t.set("activelines", LuaDebug.activeLines(cl.p));
            return t;
        }

        // ldebug.c: auxgetinfo
        private Info infoFromLevel(Globals globals, LuaThread targetThread, int level) {
            Info i = new Info();
            LuaThread levelThread = targetThread != null ? targetThread : (globals != null ? globals.running : null);
            Globals.DebugFrame frame = globals != null ? globals.getFrameAtLevel(levelThread, level, false) : null;
            CallInfo ci = globals != null ? globals.getCallInfoAtLevel(levelThread, level, false) : null;
            if (frame == null) {
                i.what = "main";
                i.source = "=?";
                i.short_src = "=?";
                i.currentline = -1;
                return i;
            }
            LuaFunction fn = frame.func;
            i.func = fn;
            if (fn instanceof LuaClosure cl && cl.p != null) {
                Prototype p = cl.p;
                i.what = p.linedefined == 0 ? "main" : "Lua";
                String rawSrc = p.source != null ? p.source.toJavaString() : "=?";
                i.source = rawSrc;
                i.short_src = LuaDebug.chunkid(rawSrc);
                i.linedefined = p.linedefined;
                i.lastlinedefined = p.lastlinedefined;
                i.nups = p.sizeupvalues;
                i.numparams = p.numparams;
                i.isvararg = p.isVararg();  // ldebug.c: auxgetinfo 'u'
                if (frame.pc >= 0) {
                    i.currentline = LuaDebug.getFuncLine(p, frame.pc);
                } else {
                    i.currentline = -1;
                }
            } else {
                i.what = "C";
                i.source = "=[C]";
                i.short_src = "[C]";
                i.currentline = -1;
                i.nups = fn != null ? fn.nupvalues() : 0;
                i.numparams = 0;
                i.isvararg = true;
            }

            LuaDebug.NameWhat nw = LuaDebug.getfuncname(globals, targetThread, level);
            if (nw != null && nw.namewhat != null && !nw.namewhat.isEmpty()) {
                i.name = nw.name;
                i.namewhat = nw.namewhat;
            } else {
                i.namewhat = "";
            }
            i.istailcall = frame.istailcall;
            i.extraargs = frame.extraargs;
            if (ci != null && (ci.callstatus & CallInfo.CIST_HOOKED) != 0) {
                i.ftransfer = levelThread != null ? levelThread.ftransfer : 0;
                i.ntransfer = levelThread != null ? levelThread.ntransfer : 0;
            } else if (frame.extrasIfPresent() != null && frame.extrasIfPresent().transferValues != null) {
                i.ftransfer = frame.extrasIfPresent().transferStart;
                i.ntransfer = frame.extrasIfPresent().transferValues.length;
            }
            return i;
        }
    }

    // ldblib.c: db_getlocal
    public static class DbGetLocalFn extends LuaFunction {
        @Override
        public Varargs call(Varargs args) {
            int arg = args.arg(1) instanceof LuaThread ? 2 : 1;
            LuaThread targetThread = arg == 2 ? args.checkthread(1) : null;
            LuaValue target = args.arg(arg);
            int nvar = args.checkint(arg + 1);
            if (target instanceof LuaFunction fn) {
                if (fn instanceof LuaClosure cl) {
                    String name = nvar > 0 ? LuaDebug.getLocalName(cl.p, nvar, 0) : null;
                    return name != null ? LuaString.newStr(name) : LuaValue.NIL;
                }
                return LuaValue.NIL;
            }
            int level = args.checkint(arg);
            Globals globals = targetThread != null && targetThread.l_G != null
                    ? targetThread.l_G
                    : ownerGlobals;
            LuaThread levelThread = targetThread != null ? targetThread : (globals != null ? globals.running : null);
            CallInfo ci = globals != null ? globals.getCallInfoAtLevel(levelThread, level, false) : null;
            Globals.DebugFrame frame = globals != null ? globals.getFrameAtLevel(levelThread, level, false) : null;
            if (ci == null || frame == null || levelThread == null)
                LuaErrors.argError(1, "level out of range");
            Globals.DebugFrame.Extras ext = frame.extrasIfPresent();
            if (nvar > 0 && ext != null && ext.transferValues != null) {
                int idx = nvar - ext.transferStart;
                if (idx >= 0 && idx < ext.transferValues.length)
                    return LuaValue.varargsOf(LuaString.newStr("(temporary)"), ext.transferValues[idx]);
            }
            LuaDebug.FindLocal local = LuaDebug.findLocal(levelThread, ci, nvar);
            if (local == null) return LuaValue.NIL;
            LuaValue value = levelThread.stack[local.pos];
            return LuaValue.varargsOf(LuaString.newStr(local.name), value != null ? value : LuaValue.NIL);
        }
    }

    // ldblib.c: db_setlocal
    public static class DbSetLocalFn extends LuaFunction {
        @Override
        public Varargs call(Varargs args) {
            int arg = args.arg(1) instanceof LuaThread ? 2 : 1;
            LuaThread targetThread = arg == 2 ? args.checkthread(1) : null;
            int level = args.checkint(arg);
            int nvar = args.checkint(arg + 1);
            LuaValue value = LuaValue.checkany(arg + 2, args);
            Globals globals = targetThread != null && targetThread.l_G != null
                    ? targetThread.l_G
                    : ownerGlobals;
            LuaThread levelThread = targetThread != null ? targetThread : (globals != null ? globals.running : null);
            CallInfo ci = globals != null ? globals.getCallInfoAtLevel(levelThread, level, false) : null;
            Globals.DebugFrame frame = globals != null ? globals.getFrameAtLevel(levelThread, level, false) : null;
            if (ci == null || frame == null || levelThread == null)
                LuaErrors.argError(1, "level out of range");
            Globals.DebugFrame.Extras ext = frame.extrasIfPresent();
            if (nvar > 0 && ext != null && ext.transferValues != null) {
                int idx = nvar - ext.transferStart;
                if (idx >= 0 && idx < ext.transferValues.length) {
                    ext.transferValues[idx] = value;
                    return LuaString.newStr("(temporary)");
                }
            }
            LuaDebug.FindLocal local = LuaDebug.findLocal(levelThread, ci, nvar);
            if (local == null) return LuaValue.NIL;
            levelThread.stack[local.pos] = value;
            return LuaString.newStr(local.name);
        }
    }

    // ldblib.c: db_getregistry
    public static class DbGetRegistryFn extends LuaFunction {
        @Override
        public Varargs call(Varargs args) {
            Globals g = ownerGlobals;
            if (g == null) return LuaValue.tableOf();
            LuaValue hookKey = g.registry.hashGet(DebugHook.HOOKKEY);
            if (hookKey.isnil()) {
                LuaTable hookTable = LuaValue.tableOf();
                LuaTable mt = LuaValue.tableOf();
                mt.setEntry(LuaValue.MODE, LuaString.newStr("k"));
                hookTable.setmetatable(mt);
                g.registry.setEntry(DebugHook.HOOKKEY, hookTable);
            }
            return g.registry;
        }
    }
}
