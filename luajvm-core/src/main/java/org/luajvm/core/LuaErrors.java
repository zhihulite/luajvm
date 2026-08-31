// ref: ldebug.c
// diff: C的luaG_runerror立即调用luaG_addinfo；Java拆runError/runErrorWithInfo两方法，runErrorWithInfo在构造错误时立即添加源码位置
package org.luajvm.core;


public final class LuaErrors {
    private LuaErrors() {
    }

    // ldebug.c: luaG_runerror  -  不含源码位置信息
    public static LuaValue runError(String msg) {
        throw new LuaError(msg);
    }

    // ldebug.c: luaG_runerror + luaG_addinfo  -  含源码位置信息
    // java diff: C 统一调 luaG_addinfo；Java 拆 runError/runErrorWithInfo 两方法。
    //   C 还调 luaC_checkGC；Java 依赖 JVM GC 无需
    public static LuaValue runErrorWithInfo(String msg) {
        LuaThread L = currentThread();
        if (L != null && L.ci != null) {
            // ldebug.c: if (isLua(ci))
            LuaValue funcVal = L.ci.func >= 0 && L.ci.func < L.stack.length ? L.stack[L.ci.func] : null;
            if (funcVal instanceof LuaClosure cl && cl.p != null) {
                // ldebug.c: luaG_addinfo
                String source = cl.p.source != null ? cl.p.source.toJavaString() : null;
                if (source == null) {
                    // ldebug.c: if (src == NULL) return "?:?: %s"
                    msg = "?:?: " + msg;
                } else {
                    // ldebug.c: luaG_addinfo 直接用 luaG_getfuncline 的返回值（无行号信息时 -1，
                    //   输出 "src:-1: msg"）。
                    int line = LuaDebug.getFuncLinePub(cl.p, CallInfo.currentpc(L.ci));
                    String src = LuaDebug.chunkid(source);
                    msg = src + ":" + line + ": " + msg;
                }
            }
        }
        throw new LuaError(msg);
    }

    // ldebug.c: luaG_typeerror
    public static LuaValue typeError(LuaValue value, String op) {
        return typeError(value, op, LuaDebug.varinfo(currentThread(), value));
    }

    // ldebug.c: luaG_typeerror
    // C 的第二个参数是 const TValue *；stackSlot 是 Java 共享栈中的对应槽。
    public static LuaValue typeError(LuaThread L, int stackSlot, LuaValue value, String op) {
        return typeError(value, op, LuaDebug.varinfoAtStack(L, stackSlot));
    }

    // ldebug.c: typeerror  -  调用 luaG_runerror（含 luaG_addinfo）
    public static LuaValue typeError(LuaValue value, String op, String extra) {
        if (extra == null) extra = "";
        return runErrorWithInfo("attempt to " + op + " a " + LuaValue.objTypeName(value) + " value" + extra);
    }

    // ldebug.c: luaG_callerror
    public static LuaValue callError(LuaValue value) {
        LuaThread L = currentThread();
        // java diff: funcnamefromcall now returns String namewhat + writes to NameWhat out
        //   (aligns C's const char **name output param, eliminates internal NameWhat allocations)
        String extra;
        if (L != null && L.ci != null) {
            LuaDebug.NameWhat nw = new LuaDebug.NameWhat();
            String what = LuaDebug.funcnamefromcall(L, L.ci, nw);
            extra = (what != null && !what.isEmpty())
                    ? LuaDebug.formatvarinfo(what, nw.name)
                    : LuaDebug.varinfo(L, value);
        } else {
            extra = LuaDebug.varinfo(L, value);
        }
        return typeError(value, "call", extra);
    }

    // ldebug.c: luaG_callerror
    public static LuaValue callError(LuaValue value, String extra) {
        return typeError(value, "call", extra);
    }

    // ldebug.c: luaG_forerror  -  C calls luaG_runerror which calls luaG_addinfo
    public static LuaValue forError(LuaValue value, String what) {
        return runErrorWithInfo("bad 'for' " + what + " (number expected, got " + LuaValue.objTypeName(value) + ")");
    }

    // ldebug.c: luaG_concaterror
    public static LuaValue concatError(LuaValue p1, LuaValue p2) {
        LuaValue bad = (p1 instanceof LuaString || p1 instanceof LuaNumber) ? p2 : p1;
        return typeError(bad, "concatenate");
    }

    // ldebug.c: luaG_opinterror
    public static LuaValue opIntError(LuaValue p1, LuaValue p2, String op) {
        LuaValue bad = hasNumericForm(p1) ? p2 : p1;
        return typeError(bad, op);
    }

    // ldebug.c: luaG_opinterror
    public static LuaValue opIntError(LuaThread L, int slot1, LuaValue p1,
                                      int slot2, LuaValue p2, String op) {
        if (!hasNumericForm(p1)) {
            return typeError(L, slot1, p1, op);
        }
        return typeError(L, slot2, p2, op);
    }



    // ldebug.c: luaG_tointerror  -  C calls luaG_runerror which calls luaG_addinfo
    public static LuaValue toIntError(LuaValue p1, LuaValue p2) {
        LuaValue bad = hasIntegerForm(p1) ? p2 : p1;
        return runErrorWithInfo("number" + LuaDebug.varinfo(currentThread(), bad) + " has no integer representation");
    }

    // ldebug.c: luaG_tointerror  -  C calls luaG_runerror which calls luaG_addinfo
    public static LuaValue toIntError(LuaThread L, int slot1, LuaValue p1,
                                      int slot2, LuaValue p2) {
        if (!hasIntegerForm(p1)) {
            return runErrorWithInfo("number" + LuaDebug.varinfoAtStack(L, slot1)
                    + " has no integer representation");
        }
        return runErrorWithInfo("number" + LuaDebug.varinfoAtStack(L, slot2)
                + " has no integer representation");
    }

    // ldebug.c: luaG_ordererror  -  C calls luaG_runerror which calls luaG_addinfo
    public static LuaValue orderError(LuaValue p1, LuaValue p2) {
        String t1 = LuaValue.objTypeName(p1);
        String t2 = LuaValue.objTypeName(p2);
        if (t1.equals(t2)) return runErrorWithInfo("attempt to compare two " + t1 + " values");
        return runErrorWithInfo("attempt to compare " + t1 + " with " + t2);
    }


    // lmem.c: luaM_toobig
    public static LuaValue tooBig() {
        throw new LuaError("memory allocation error: block too big", 0);
    }

    // ldebug.c: luaM_error
    public static LuaValue memError() {
        throw new LuaError(LuaString.MEMERRMSG, 0);
    }

    // ldo.c: checkmode
    public static LuaValue checkModeError(String mode, String kind) {
        throw new LuaError("attempt to load a " + kind + " chunk (mode is '" + mode + "')");
    }

    // lauxlib.c: luaL_error
    public static LuaValue error(String msg) {
        throw new LuaError(msg);
    }

    // ldo.c: lua_error 带 level（error() 的 level 版本）
    public static LuaValue error(String msg, int level) {
        throw new LuaError(msg, level);
    }

    // ldo.c: lua_error 带 level（error() 的 level 版本）
    public static LuaValue error(LuaValue msg, int level) {
        throw new LuaError(msg, level);
    }

    // lauxlib.c: luaL_error  -  java diff: 宿主异常保留 cause
    public static LuaValue error(String msg, Throwable cause) {
        throw new LuaError(msg, cause);
    }

    // lauxlib.c: luaL_error
    public static LuaValue error(Exception e) {
        throw new LuaError(e);
    }

    // ldo.c: luaD_throw  -  java diff: 如果L.closeSavedStack非空，说明错误来自__close，使用保存的savedStack
    public static LuaValue error(LuaValue m) {
        Globals g = LuaTable.runningGlobalsForGC();
        LuaThread L = g != null ? g.running : null;
        if (L != null && L.closeSavedStack != null) {
            LuaError e = new LuaError(m);
            e.savedStack = L.closeSavedStack;
            // java-only: savedStack已从closeSavedStack设置，清除惰性状态防止引用泄漏
            e.throwCi = null;
            e.throwL = null;
            L.closeSavedStack = null;
            throw e;
        }
        throw new LuaError(m);
    }

    // lauxlib.c: luaL_argexpected
    public static LuaValue argexpected(int i, LuaValue value, boolean condition, String expected) {
        if (!condition) typeError(i, value, expected);
        return value;
    }

    // lauxlib.c: luaL_argerror
    public static LuaValue argError(int arg, String extramsg) {
        throw new LuaError(formatArgError(arg, extramsg));
    }


    // lauxlib.c: luaL_argerror 的消息形态（供库层自行 throw LuaError 时拼装，
    //   含函数名解析与 where 前缀 —— 与直接 argError 抛出的消息逐字一致）
    public static String argErrorMessage(int arg, String extramsg) {
        return formatArgError(arg, extramsg);
    }

    // lauxlib.c: luaL_typeerror
    public static LuaValue typeError(int arg, LuaValue value, String expected) {
        return argError(arg, expected + " expected, got " + LuaValue.objTypeName(value));
    }

    // lauxlib.c: luaL_typeerror
    public static LuaValue typeError(int arg, Varargs args, String expected) {
        String typearg = (args.narg() < arg) ? "no value" : LuaValue.objTypeName(args.arg(arg));
        return argError(arg, expected + " expected, got " + typearg);
    }


    // ldo.c: luaD_seterrorobj
    public static LuaError errorObject(LuaValue value, int level) {
        return new LuaError(value, level);
    }

    // ldo.c: luaD_seterrorobj
    public static LuaError errorObject(String value, int level) {
        return new LuaError(value, level);
    }

    // lauxlib.c: luaL_error —— 返回错误对象供调用方 throw
    public static LuaError errorObject(String msg) {
        return new LuaError(msg);
    }

    public static LuaError errorObject(LuaValue msg) {
        return new LuaError(msg);
    }

    public static LuaError errorObject(String msg, Throwable cause) {
        return new LuaError(msg, cause);
    }

    public static LuaError errorObject(Exception e) {
        return new LuaError(e);
    }

    // lauxlib.c: luaL_where（level 1 固定：argerror 的调用者帧）
    private static String whereOf(LuaThread L, CallInfo ci) {
        if (L == null || ci == null || !ci.isLua()) return "";
        LuaValue fv = ci.func >= 0 && ci.func < L.stack.length ? L.stack[ci.func] : null;
        if (!(fv instanceof LuaClosure cl) || cl.p == null) return "";
        int pc = CallInfo.currentpc(ci);
        if (pc < 0) pc = 0;
        int line = LuaDebug.getFuncLinePub(cl.p, pc);
        if (line <= 0) return "";
        return LuaDebug.chunkid(
                cl.p.source != null ? cl.p.source.toJavaString() : "?") + ":" + line + ": ";
    }

    // lauxlib.c luaL_checkinteger/luaL_checklstring 的库入口形态
    //（值级 checkXXX 直调产生 "long expected" 裸消息）
    public static long checkLong(Varargs args, int arg) {
        LuaValue v = args.arg(arg);
        if (!v.isnumber()) typeError(arg, args, "number");
        if (v.isfloat() && !LuaFloat.hasExactLong(v.todouble())) {
            // C：luaL_checkinteger 经 luaL_argerror 包装（utf8.char(1.5) 的
            // "bad argument #1 to 'char' (number has no integer representation)"）
            argError(arg, "number has no integer representation");
            return 0;  // 不可达
        }
        return v.checklong();
    }

    // lauxlib.c: luaL_checknumber —— 经 typeError 包装（参数号/函数名/where 前缀）
    public static double checkDouble(Varargs args, int arg) {
        LuaValue v = args.arg(arg);
        if (!v.isnumber()) typeError(arg, args, "number");
        return v.todouble();
    }

    // lauxlib.c: luaL_optinteger —— 缺省(nil/缺参)用默认值；类型错经 checkLong 的包装报出
    public static long optLong(Varargs args, int arg, long def) {
        LuaValue v = args.arg(arg);
        if (v.isnil()) return def;
        return checkLong(args, arg);
    }

    public static LuaString checkStr(Varargs args, int arg) {
        LuaValue v = args.arg(arg);
        if (!v.isstring() && !v.isnumber()) typeError(arg, args, "string");
        return v.checkstring();
    }

    // lauxlib.c: luaL_argerror
    private static String formatArgError(int arg, String msg) {
        Globals g = LuaTable.runningGlobalsForGC();
        if (g == null || g.running == null) {
            return "bad argument #" + arg + " (" + msg + ")";
        }
        LuaThread L = g.running;
        CallInfo ci = g.getCallInfoAtLevel(L, 0, false);
        if (ci == null) {
            return "bad argument #" + arg + " (" + msg + ")";
        }
        LuaDebug.NameWhat nw = LuaDebug.getfuncname(g, L, 0);
        String name = nw != null && nw.name != null && !nw.name.isEmpty() ? nw.name : null;
        String namewhat = nw != null && nw.namewhat != null ? nw.namewhat : "";
        int extraargs = (ci.callstatus & CallInfo.MAX_CCMT) >> CallInfo.CIST_CCMT;
        String argword;
        if (arg <= extraargs) {
            argword = "extra argument";
        } else {
            arg -= extraargs;
            if ("method".equals(namewhat)) {
                arg--;
                if (arg == 0) {
                    return whereOf(L, ci.previous) + "calling '" + name + "' on bad self (" + msg + ")";
                }
            }
            argword = "argument";
        }
        if (name == null) {
            LuaFunction func = ci.func >= 0 && ci.func < L.stack.length && L.stack[ci.func] instanceof LuaFunction fn ? fn : null;
            name = pushGlobalFuncName(g, func);
            if (name == null) name = "?";
        }
        // lauxlib.c luaL_argerror —— 经 luaL_error 自动带 luaL_where(L,1)：
        // 调用者帧（ci.previous）的 src:line 前缀
        return whereOf(L, ci.previous) + "bad " + argword + " #" + arg + " to '" + name + "' (" + msg + ")";
    }

    // lauxlib.c: pushglobalfuncname
    private static String pushGlobalFuncName(Globals g, LuaFunction func) {
        if (func == null || g == null) return null;
        LuaValue loaded = g.registry.get("loaded");
        if (!loaded.istable()) return null;
        return findField((LuaTable) loaded, func, 2);
    }

    // lauxlib.c: findfield
    private static String findField(LuaTable table, LuaFunction func, int level) {
        if (level == 0 || table == null) return null;
        LuaValue key = LuaValue.NIL;
        while (true) {
            Varargs nr = table.nextEntry(key);
            key = nr.arg1();
            if (key.isnil()) return null;
            if (!key.isstring()) continue;
            LuaValue value = nr.arg(2);
            if (value == func) return stripGlobalPrefix(key.toJavaString());
            if (value.istable()) {
                String sub = findField((LuaTable) value, func, level - 1);
                if (sub != null) return stripGlobalPrefix(key.toJavaString() + "." + sub);
            }
        }
    }

    private static String stripGlobalPrefix(String name) {
        return name != null && name.startsWith("_G.") ? name.substring(3) : name;
    }

    // lstate.h: 当前 lua_State  -  java diff: Globals.running 保存当前运行线程
    private static LuaThread currentThread() {
        Globals g = LuaTable.runningGlobalsForGC();
        return g != null ? g.running : null;
    }

    // java-only
    private static boolean hasNumericForm(LuaValue v) {
        // ldebug.c: luaG_opinterror 只检查 ttisnumber - 快速路径用 tonumberns，
        // 不把字符串数值形式当作数值操作数
        return v instanceof LuaNumber;
    }

    // java-only
    private static boolean hasIntegerForm(LuaValue v) {
        if (v instanceof LuaInteger) return true;
        if (v instanceof LuaFloat f) {
            double d = f.todouble();
            return !Double.isNaN(d) && !Double.isInfinite(d)
                    && d == Math.floor(d)
                    && d < 9223372036854775808.0
                    && d >= -9223372036854775808.0;
        }
        if (v instanceof LuaString s) {
            LuaNumber n = s.scannumber();
            return hasIntegerForm(n);
        }
        return false;
    }
}
