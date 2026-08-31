// ref: lbaselib.c
// diff: try/catch 代替 longjmp; InputStream 代替 FILE*; LuaError 异常; pcall 手动恢复 nCcalls/nny/allowhook/ci/top; LuaGC 静态方法; warn 枚举状态机
package org.luajvm.lib;

import org.luajvm.core.LuaDebug;
import org.luajvm.core.CallInfo;
import org.luajvm.core.Globals;
import org.luajvm.core.LuaBoolean;
import org.luajvm.core.LuaFloat;
import org.luajvm.core.LuaInteger;
import org.luajvm.core.LuaClosure;
import org.luajvm.core.LuaError;
import org.luajvm.core.LuaErrors;
import org.luajvm.core.LuaFunction;
import org.luajvm.core.LuaGC;
import org.luajvm.core.LuaInteger;
import org.luajvm.core.LuaNumber;
import org.luajvm.core.LuaString;
import org.luajvm.core.LuaTable;
import org.luajvm.core.LuaThread;
import org.luajvm.core.LuaValue;
import org.luajvm.core.Varargs;
import org.luajvm.vm.LuaCall;
import org.luajvm.vm.LuaIndex;
import org.luajvm.vm.LuaVM;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import org.luajvm.core.IpairsMark;
import org.luajvm.core.NextMark;
import org.luajvm.spi.BaseLibrary;

public final class BaseLib extends LuaFunction implements BaseLibrary {

    // lbaselib.c: luaB_next
    // Java：每个基础库各有一个 next 函数；函数对象已绑定所属 Globals，不可跨状态复用。
    private final LuaFunction next = new NextFn();
    private Globals globals;
    // java-only: 可设置的工作目录 - System.setProperty("user.dir") 对 new File 的相对路径
    //   解析无效（JDK 缓存进程启动时的 cwd）。测试运行器调 setCwd 指向测试资源目录，
    //   使 attrib.lua 的 dofile("libs\\err.lua") 等相对路径能正确解析。
    private static volatile String sCwd;
    private final StringBuilder warningBuffer = new StringBuilder();
    private ToStringFn tostring;
    private WarningState warningState = WarningState.ON;
    private WarningMode warningMode = WarningMode.ALLOW;

    public static String getCwd() {
        return sCwd != null ? sCwd : System.getProperty("user.dir");
    }

    public static void setCwd(String cwd) {
        sCwd = cwd;
    }

    // java-only: 相对路径统一用可设置的 cwd 解析（io 库与 openResource 共享同一基准），
    //   未设置 sCwd 时回退 user.dir 属性（进程启动 cwd 语义）。
    public static File resolveFile(String filename) {
        File f = new File(filename);
        if (!f.isAbsolute()) {
            String base = getCwd();
            if (base != null) {
                File f2 = new File(base, filename);
                return f2;
            }
        }
        return f;
    }

    private static String getMode(Varargs args, int idx) {
        // lbaselib.c: getMode  -  默认 NULL（二进制与文本皆收）
        String mode = args.optJavaString(idx, null);
        if (mode != null && mode.indexOf('B') >= 0) LuaErrors.argError(idx, "invalid mode");
        return mode;
    }

    private static LuaValue optionalEnv(Varargs args, int index) {
        return args.narg() >= index ? args.arg(index) : null;
    }

    private static Globals activeGlobals(LuaFunction function) {
        return function != null ? function.ownerGlobals : null;
    }

    // ldo.c: luaD_rawrunprotected
    private static void restoreProtectedCallState(
            Globals g, LuaThread protectedLuaThread,
            int savedNCcalls, int savedNny) {
        if (g == null || protectedLuaThread == null) return;
        protectedLuaThread.nCcalls = savedNCcalls;
        protectedLuaThread.nny = savedNny;
    }

    // java-only: 受保护调用的错误善后（PcallFn.callOnStack 快路径专用），与
    //   PcallFn.call(Varargs) 的 catch 链对应：恢复 nCcalls/nny/pendingError/ci/
    //   allowhook -> 归一化错误对象 -> closeUpvals -> shrinkStack -> top 复位。
    //   level 必须是"被保护函数所在槽"（C 用 savestack(L, c.func)），不是调用方 top；
    //   传入口 top 会漏关 TBC 变量（locals.lua:554 失败的成因）。
    private static LuaValue recoverProtectedOnStack(
            LuaThread th, Throwable t,
            int savedNCcalls, int savedNny, int level, CallInfo oldCi, byte oldAllowhook) {
        // java diff: CloseSelf 必须穿透 pcall 到达 runCoroutine（C 用 longjmp 跳过中间 setjmp）
        if (t instanceof LuaThread.CloseSelf cs) throw cs;
        th.nCcalls = savedNCcalls;
        th.nny = savedNny;
        th.pendingError = null;
        th.ci = oldCi;
        th.allowhook = oldAllowhook;
        LuaValue errObj;
        if (t instanceof LuaError le) {
            errObj = normalizeErrorObject(le.getMessageObject());
        } else if (t instanceof StackOverflowError) {
            // java diff: C 靠 setjmp/longjmp 不受栈溢出影响，Java 必须显式转错误对象
            errObj = LuaString.newStr("C stack overflow");
        } else {
            String m = t.getMessage();
            errObj = LuaValue.valueOf(m != null ? m : t.toString());
        }
        boolean isYieldable = th.nny == 0 && !th.isMainThread();
        errObj = LuaVM.closeUpvals(th, level, errObj, isYieldable);
        LuaVM.shrinkStack(th);
        th.top = level;
        return errObj;
    }

    private static LuaValue normalizeErrorObject(LuaValue value) {
        return value == null || value.isnil()
                ? LuaString.newStr("<no error object>")
                : value;
    }

    // spi.BaseLibrary: tostringFn  -  暴露 tostring 函数对象（宿主 print 要用，见
    //   luajvm-android 的 lib/print）。
    @Override
    public LuaValue tostringFn() {
        return tostring;
    }

    // lauxlib.c: luaL_where
    public LuaString where(int level) {
        Globals g = globals;
        if (g == null || g.running == null) return LuaString.newStr("");
        LuaThread L = g.running;
        CallInfo ci = g.getCallInfoAtLevel(L, level, false);
        if (ci != null && ci.func >= 0 && ci.func < L.stack.length && L.stack[ci.func] instanceof LuaClosure cl) {
            int line = LuaDebug.getFuncLinePub(cl.p, CallInfo.currentpc(ci));
            if (line > 0) {
                String src = cl.p.source != null ? cl.p.source.toJavaString() : null;
                return LuaString.newStr(LuaDebug.chunkid(src) + ":" + line + ": ");
            }
        }
        return LuaString.newStr("");
    }

    // lbaselib.c: luaB_error
    public Varargs err(Varargs args) {
        int level = args.arg(2).optint(1);
        LuaValue msg = args.arg1();
        if (msg instanceof LuaString s && level > 0) {
            LuaString where = globals != null ? where(level) : LuaString.newStr("");
            msg = LuaString.newStr(where.toJavaString() + s.toJavaString());
        }
        LuaValue errObj = normalizeErrorObject(msg);
        throw LuaErrors.errorObject(errObj, 0);
    }




    @Override
    public Varargs call(Varargs args) {
        LuaValue modname = args.arg1();
        LuaValue env = args.arg(2);
        globals = env.checkglobals();
        ownerGlobals = globals;
        globals.baselib = this;
        // lua_setwarnf / lstate.c: global_State.warnf  -  warn 通道登记到状态。
        //   core 侧（LuaTable 的 __gc 出错路径 = luaE_warnerror）只认这个接口，
        //   反向不 import lib.BaseLib。
        globals.warnf = this::luaWarning;
        env.set("_G", env);
        env.set("_VERSION", LuaString.newStr("Lua 5.5"));

        if (!env.get("package").isnil()) env.get("package").get("loaded").set("_G", env);
        env.set("assert", new AssertFn());
        env.set("collectgarbage", new CollectGarbageFn());
        env.set("dofile", new DoFileFn());
        env.set("error", new ErrorFn());
        env.set("getmetatable", new GetMetatableFn());
        env.set("ipairs", new IPairsFn());
        env.set("load", new LoadFn());
        env.set("loadfile", new LoadFileFn());

        env.set("next", next);
        env.set("pairs", new PairsFn());
        env.set("pcall", new PcallFn());
        env.set("print", new PrintFn());
        env.set("warn", new WarnFn(this));
        env.set("rawequal", new RawEqualFn());
        env.set("rawget", new RawGetFn());
        env.set("rawlen", new RawLenFn());
        env.set("rawset", new RawSetFn());
        env.set("select", new SelectFn());
        env.set("setmetatable", new SetMetatableFn());
        env.set("tonumber", new ToNumberFn());
        env.set("tostring", tostring = new ToStringFn());
        env.set("type", new TypeFn());
        env.set("xpcall", new XpcallFn());
        return env;
    }

    // lbaselib.c: openResource
    public InputStream openResource(String filename) {
        if (filename.isEmpty()) return null;
        File f = new File(filename);
        // java-only: 相对路径须用可设置的 cwd 解析（见 resolveFile）
        if (!f.isAbsolute()) {
            String base = getCwd();
            if (base != null) {
                File f2 = new File(base, filename);
                if (f2.isFile()) {
                    try {
                        return new FileInputStream(f2);
                    } catch (IOException e) {
                        return null;
                    }
                }
            }
        }
        if (f.isFile()) {
            try {
                return new FileInputStream(f);
            } catch (IOException e) {
                return null;
            }
        }
        // classpath fallback：Windows 反斜杠路径转正斜杠后再查资源
        String resPath = filename.replace('\\', '/');
        if (!resPath.startsWith("/")) resPath = "/" + resPath;
        return getClass().getResourceAsStream(resPath);
    }

    // java-only: 预编译字节码开关，默认关闭  -  开启后 loadFile 优先尝试同名 .luac
    //   兄弟文件（x.lua -> x.luac），命中则走 LuaChunk.undump。探测不碰 package.path，
    //   覆盖 loadfile/dofile/Globals.loadfile 三个不经 searchpath 的入口。
    // -Dluajvm.luac=true 设 JVM 初值；Android 宿主无命令行，由 LuaEngine.init 编程调
    //   setLuacPreferred(true)。volatile：设置在主线程，加载可能在 worker 线程。
    private static volatile boolean luacPreferred = Boolean.getBoolean("luajvm.luac");
    private static final String LUAC_SUFFIX = ".luac";

    /** 是否优先加载同名 .luac 预编译字节码。 */
    public static boolean isLuacPreferred() {
        return luacPreferred;
    }

    /**
     * 开关预编译字节码优先加载。
     *
     * <p>Android 宿主（{@code LuaEngine.init}）在装配阶段调用；JVM 侧也可用
     * {@code -Dluajvm.luac=true} 设初值。关闭时 {@code loadFile} 全走源码路径。
     */
    public static void setLuacPreferred(boolean enabled) {
        luacPreferred = enabled;
    }

    public Varargs loadFile(String filename, String mode, LuaValue env) {
        // 预编译优先：仅当 mode 允许二进制时才尝试（mode==null 或含 'b'）。
        //   mode 含 't' 而不含 'b'（纯文本模式）时必须跳过，否则违反 load 的 mode 契约。
        if (luacPreferred && filename != null && filename.endsWith(".lua")
                && (mode == null || mode.isEmpty() || mode.indexOf('b') >= 0)) {
            String luacName = filename.substring(0, filename.length() - 4) + LUAC_SUFFIX;
            InputStream lc = openResource(luacName);
            if (lc != null) {
                try (lc) {
                    // chunkname 仍用源文件名：错误消息/调试信息里的文件名须与源码路径一致，
                    //   否则 traceback 文本会因加载方式不同而变化（保真要求）。
                    Varargs v = loadStream(lc, "@" + filename, mode, env);
                    if (v.arg1().isfunction()) return v;
                    // .luac 损坏/版本不符 -> 静默回落到源码，不让缓存故障变成加载失败
                } catch (Exception e) {
                    // 同上：回落源码
                }
            }
        }
        InputStream is = openResource(filename);
        if (is == null)
            return varargsOf(LuaValue.NIL, LuaString.newStr("cannot open " + filename + ": No such file or directory"));
        try (is) {
            return loadStream(is, "@" + filename, mode, env);
        } catch (Exception e) {
            return varargsOf(LuaValue.NIL, LuaString.newStr("error loading " + filename + ": " + e.getMessage()));
        }
    }

    public Varargs loadStream(InputStream is, String chunkname, String mode, LuaValue env) {
        try {
            if (is == null)
                return varargsOf(LuaValue.NIL, LuaString.newStr("not found: " + chunkname));
            // java-only: 多 Globals 实例须用 ownerGlobals，避免静态字段互相覆盖
        Globals state = ownerGlobals != null ? ownerGlobals : activeGlobals(this);
            Varargs v = state.load(is, chunkname, mode, env);
            return v;
        } catch (Exception e) {
            String msg = e.getMessage();
            if (msg == null) msg = e.toString();
            return varargsOf(LuaValue.NIL, LuaString.newStr(msg));
        }
    }

    private void luaWarning(String message, boolean tocont) {

        switch (warningState) {
            case OFF -> warnfoff(message, tocont);
            case ON -> warnfon(message, tocont);
            case CONT -> warnfcont(message, tocont);
        }
    }

    private boolean checkWarningControl(String message, boolean tocont) {

        if (tocont || !message.startsWith("@")) return false;
        String control = message.substring(1);
        if (control.equals("off")) {
            warningState = WarningState.OFF;
        } else if (control.equals("on")) {
            warningState = WarningState.ON;
        } else if (control.equals("normal")) {
            warningMode = WarningMode.NORMAL;
        } else if (control.equals("allow")) {
            warningMode = WarningMode.ALLOW;
        } else if (control.equals("store")) {
            warningMode = WarningMode.STORE;
        }
        return true;
    }

    private void warnfoff(String message, boolean tocont) {

        checkWarningControl(message, tocont);
    }

    private void warnfon(String message, boolean tocont) {

        if (checkWarningControl(message, tocont)) return;
        if (warningMode == WarningMode.STORE) {
            warningBuffer.setLength(0);
            warnfcont(message, tocont);
            return;
        }
        if (warningMode == WarningMode.NORMAL && !message.startsWith("#")) {
            warnfcont(message, tocont);
            return;
        }
        System.err.print("Lua warning: ");
        warnfcont(message, tocont);
    }

    private void warnfcont(String message, boolean tocont) {

        if (warningMode == WarningMode.STORE) {
            warningBuffer.append(message);
            if (tocont) {
                warningState = WarningState.CONT;
            } else {
                Globals g = activeGlobals(this);
                if (g != null && !g.get("_WARN").toboolean()) {
                    g.set("_WARN", LuaString.newStr(warningBuffer.toString()));
                }
                warningBuffer.setLength(0);
                warningState = WarningState.ON;
            }
            return;
        }
        System.err.print(message);
        if (tocont) {
            warningState = WarningState.CONT;
        } else {
            System.err.println();
            warningState = WarningState.ON;
        }
    }


    private enum WarningState {
        OFF,
        ON,
        CONT
    }

    private enum WarningMode {
        NORMAL,
        ALLOW,
        STORE
    }


    // AssertFn
    static class AssertFn extends LuaFunction {
        @Override
        public Varargs call(Varargs args) {
            if (args.arg1().toboolean()) return args;
            if (args.narg() < 1) LuaErrors.argError(1, "value expected");
            LuaValue msg = args.arg(2);
            if (args.narg() < 2) msg = LuaString.newStr("assertion failed!");
            if (ownerGlobals != null && ownerGlobals.baselib != null) {
                return ownerGlobals.baselib.err(LuaValue.varargsOf(new LuaValue[]{msg}));
            }
            throw LuaErrors.errorObject(normalizeErrorObject(msg), 0);
        }

        // java-only: callOnStack  -  lbaselib.c luaB_assert  -  首参数为真时返回全部参数
        @Override
        public int callOnStack(LuaThread L, int func, int narg) {
            if (narg < 1) return -1;
            LuaValue v = L.stack[func + 1];
            if (v == null || !v.toboolean()) return -1;  // false/nil: fall back for error message
            // true: 参数复制到结果位置
            int n = narg;
            for (int i = 0; i < n; i++) {
                L.stack[L.top + i] = L.stack[func + 1 + i];
            }
            L.top += n;
            return n;
        }
    }

    // CollectGarbageFn
    // lbaselib.c: luaB_collectgarbage
    public static class CollectGarbageFn extends LuaFunction {

        public static void allocBytes(Globals g, int n) {
            LuaGC.allocBytes(g, n);
        }

        public static void allocBytes(Globals g, long n) {
            LuaGC.allocBytes(g, n);
        }

        public static void freeBytes(Globals g, int n) {
            LuaGC.free(g, n);
        }

        public static void freeBytes(Globals g, long n) {
            LuaGC.free(g, n);
        }

        public static void checkLuaMemory(Globals g, long delta) {
            LuaGC.checkMemory(g, delta);
        }


        public static long currentBytes(Globals g) {
            return LuaGC.currentBytes(g);
        }

        public static long maxBytes(Globals g) {
            return LuaGC.maxBytes(g);
        }

        public static void setMemoryLimit(Globals g, long limit) {
            LuaGC.setMemoryLimit(g, limit);
        }

        public static long memoryLimit(Globals g) {
            return LuaGC.memoryLimit(g);
        }

        public static void setAllocCountLimit(Globals g, long limit) {
            LuaGC.setAllocCountLimit(g, limit);
        }

        public static void clearAllocCountLimit(Globals g) {
            LuaGC.clearAllocCountLimit(g);
        }

        public static void failNextAllocation(Globals g) {
            LuaGC.failNextAllocation(g);
        }

        @Override
        public Varargs call(Varargs args) {
            Globals g = ownerGlobals;
            if (g == null) throw new IllegalStateException("collectgarbage requires Globals");

            String s = args.optJavaString(1, "collect");
            switch (s) {
                case "collect" -> {
                    return LuaGC.fullGCCaller(g);
                }
                case "count" -> {
                    // lapi.c: lua_gc(LUA_GCCOUNT) -> gettotalbytes(g) / 1024
                    long used = LuaGC.currentBytes(g);
                    return varargsOf(LuaValue.valueOf(used / 1024.), LuaValue.valueOf(used % 1024));
                }
                case "isrunning" -> {
                    return LuaValue.valueOf(LuaGC.gcrunning(g));
                }
                case "step" -> {
                    return LuaGC.gcStepCaller(g, args);
                }
                case "stop" -> {
                    LuaGC.stop(g);
                    return LuaValue.ZERO;
                }
                case "restart" -> {
                    LuaGC.restart(g);
                    return LuaValue.ZERO;
                }

                case "incremental" -> {
                    return LuaGC.changeMode(g, "incremental");
                }
                case "generational" -> {
                    return LuaGC.changeMode(g, "generational");
                }

                case "param" -> {
                    return LuaGC.setParam(g, args);
                }
                default -> LuaErrors.argError(1, "invalid option '" + s + "'");
            }
            return LuaValue.NIL;
        }

        // java-only: callOnStack  -  lbaselib.c luaB_collectgarbage  -  执行 GC 收集
        // java diff: fullGC 的 callPendingFinalizers 经 callLua 改写 L.top
        //   ⇒ 必须保存/恢复（C 的 luaC_callfinalizer 用独立栈帧）
        @Override
        public int callOnStack(LuaThread L, int func, int narg) {
            boolean isCollect = false;
            if (narg < 1) {
                isCollect = true;
            } else {
                LuaValue arg1 = L.stack[func + 1];
                if (arg1 != null && arg1.isstring() && arg1.rawlen() == 7) {
                    String s = arg1.toJavaString();
                    if ("collect".equals(s)) isCollect = true;
                }
            }
            if (!isCollect) return -1;
            int savedTop = L.top;
            LuaValue result = LuaGC.fullGCCallerResult(L.l_G);
            L.top = savedTop;
            L.stack[L.top++] = result;
            return 1;
        }
    }

    // DoFileFn
    static class DoFileFn extends LuaFunction {
        @Override
        public Varargs call(Varargs args) {
            args.argcheck(args.isstring(1) || args.isnil(1), 1, "filename must be string or nil");
            String filename = args.isstring(1) ? args.toJavaString(1) : null;
            Globals g = activeGlobals(this);
            Varargs v = filename == null ?
                    g.baselib.loadStream(System.in, "=stdin", "bt", g) :
                    g.baselib.loadFile(filename, "bt", g);

            if (v.isnil(1)) {
                LuaValue errMsg = v.arg(2);
                throw LuaErrors.errorObject(errMsg, 1);
            }
            LuaValue fn = v.arg1();

            return LuaCall.callLua(fn);
        }
    }

    // ErrorFn
    static class ErrorFn extends LuaFunction {
        @Override
        public Varargs call(Varargs args) {
            Globals g = ownerGlobals;
            if (g != null && g.baselib != null) return g.baselib.err(args);
            throw LuaErrors.errorObject(normalizeErrorObject(args.arg1()), 0);
        }
    }

    // GetMetatableFn
    static class GetMetatableFn extends LuaFunction {
        @Override
        public Varargs call(Varargs args) {
            LuaValue arg = args.arg1();
            LuaValue mt = arg.getmetatable();
            return mt != null ? mt.rawget(LuaValue.METATABLE).optvalue(mt) : LuaValue.NIL;
        }

        // java-only: callOnStack  -  lbaselib.c luaB_getmetatable  -  返回对象元表
        @Override
        public int callOnStack(LuaThread L, int func, int narg) {
            if (narg < 1) return -1;
            LuaValue arg = L.stack[func + 1];
            if (arg == null) return -1;
            LuaValue mt = arg.getmetatable();
            L.stack[L.top++] = mt != null ? mt.rawget(LuaValue.METATABLE).optvalue(mt) : LuaValue.NIL;
            return 1;
        }
    }

    // IPairsFn
    // lbaselib.c: luaB_ipairs
    static class IPairsFn extends LuaFunction {
        // 每次调用返回同一迭代器函数（单例）：ipairs{} == ipairs{} 要求同一实例
        static final INextFn SHARED_INEXT = new INextFn();

        @Override
        public Varargs call(Varargs args) {
            // lbaselib.c 5.5: luaB_ipairs 只 luaL_checkany —— 表类型检查已移除，
            //   非表 state 的索引错误推迟到迭代器首次调用时发生（与 C 一致）
            if (args.narg() < 1) LuaErrors.argError(1, "value expected");
            return varargsOf(SHARED_INEXT, args.arg(1), LuaValue.ZERO);
        }

        // java-only: callOnStack  -  lbaselib.c luaB_ipairs  -  返回迭代器、表、初始索引
        @Override
        public int callOnStack(LuaThread L, int func, int narg) {
            if (narg < 1) return -1;
            LuaValue tv = L.stack[func + 1];
            if (tv == null) return -1;
            L.stack[L.top] = SHARED_INEXT;
            L.stack[L.top + 1] = tv;
            L.stack[L.top + 2] = LuaValue.ZERO;
            L.top += 3;
            return 3;
        }
    }

    // lbaselib.c: ipairsaux
    static final class INextFn extends LuaFunction implements IpairsMark {
        @Override
        public Varargs call(Varargs args) {
            return args.checktable(1).inext(args.arg(2));
        }

        // java-only: callOnStack  -  lbaselib.c ipairsaux  -  ipairs 迭代器，返回索引和值
        // java diff: 对齐 C lua_geti  -  无元表时直走 getInt（跳过 finishGet 的
        //   rawget->normalizeKey->instanceof 开销），有元表时回退 finishget 做 __index 分派
        @Override
        public int callOnStack(LuaThread L, int func, int narg) {
            if (narg < 2) return -1;
            LuaValue tbl = L.stack[func + 1];
            LuaValue idx = L.stack[func + 2];
            if (tbl == null || idx == null) return -1;
            if (!(tbl instanceof LuaTable t)) return -1;
            if (!idx.isinteger()) return -1;
            long i = idx.tolong() + 1;
            LuaValue nextKey = LuaInteger.valueOf(i);
            // lbaselib.c: lua_geti(L, 1, i)  -  无元表时等价 luaH_getint（raw get）；
            //   有元表时 getInt 返回 nil 后仍需 __index 分派 -> 回退 finishget
            LuaValue v;
            if (t.metatable == null && i >= Integer.MIN_VALUE && i <= Integer.MAX_VALUE) {
                v = t.getInt((int) i);
            } else {
                v = LuaIndex.finishGet(t, nextKey);
            }
            if (v.isnil()) {
                L.stack[L.top] = LuaValue.NIL;
                L.top++;
                return 1;
            }
            L.stack[L.top] = nextKey;
            L.stack[L.top + 1] = v;
            L.top += 2;
            return 2;
        }
    }

    // LoadFn
    static class LoadFn extends LuaFunction {
        @Override
        public Varargs call(Varargs args) {
            LuaValue ld = args.arg1();
            if (!ld.isstring() && !ld.isfunction())
                LuaErrors.typeError(1, ld, "string or function");
            String source = args.optJavaString(2, ld.isstring() ? ld.toJavaString() : "=(load)");
            String mode = getMode(args, 3);
            LuaValue env = optionalEnv(args, 4);
            Globals g = activeGlobals(this);
            // java-only: 字符串输入走直接字节路径，跳过 InputStream->readAllBytes 拷贝
            if (ld.isstring()) {
                LuaString ls = ld.strValue();
                byte[] bytes = ls.bytesIfExact();
                if (bytes != null) {
                    try {
                        return g.loadBytes(bytes, source, mode, env);
                    } catch (Exception e) {
                        String msg = e.getMessage();
                        if (msg == null) msg = e.toString();
                        return varargsOf(LuaValue.NIL, LuaString.newStr(msg));
                    }
                }
                return g.baselib.loadStream(ls.toInputStream(), source, mode, env);
            }
            return g.baselib.loadStream(new StringInputStream(ld.checkfunction()), source, mode, env);
        }
    }

    // LoadFileFn
    static class LoadFileFn extends LuaFunction {
        @Override
        public Varargs call(Varargs args) {
            args.argcheck(args.isstring(1) || args.isnil(1), 1, "filename must be string or nil");
            String filename = args.isstring(1) ? args.toJavaString(1) : null;
            String mode = getMode(args, 2);
            LuaValue env = optionalEnv(args, 3);
            Globals g = activeGlobals(this);
            return filename == null ?
                    g.baselib.loadStream(System.in, "=stdin", mode, env) :
                    g.baselib.loadFile(filename, mode, env);
        }
    }

    // lbaselib.c: luaB_next  -  身份标记见 core.NextMark
    static class NextFn extends LuaFunction implements NextMark {
        @Override
        public Varargs call(Varargs args) {

            Varargs result = args.checktable(1).nextEntry(args.arg(2));
            return result.narg() == 0 ? LuaValue.NIL : result;
        }

        // java-only: callOnStack  -  lbaselib.c luaB_next  -  返回下一个键值对
        // java diff: 用 nextEntryOnStack 直写栈，省去 Varargs 分配与 arg(i) 提取开销
        @Override
        public int callOnStack(LuaThread L, int func, int narg) {
            if (narg < 1) return -1;
            LuaValue tv = L.stack[func + 1];
            if (tv == null || !(tv instanceof LuaTable)) return -1;
            LuaValue idx = (narg >= 2 && L.stack[func + 2] != null) ? L.stack[func + 2] : LuaValue.NIL;
            int nr = ((LuaTable) tv).nextEntryOnStack(idx, L.stack, L.top);
            if (nr == 0) {
                L.stack[L.top] = LuaValue.NIL;
                L.top++;
                return 1;
            }
            L.top += nr;
            return nr;
        }
    }

    // PairsFn
    class PairsFn extends LuaFunction {
        @Override
        public Varargs call(Varargs args) {
            if (args.narg() < 1) LuaErrors.argError(1, "value expected");
            // java-only: __pairs 用字符串常量查找而非 TM_ 枚举
            LuaValue h = args.arg1().metaTag(LuaString.newStr("__pairs"));
            if (!h.isnil()) return LuaCall.callLua(h, args.arg1());
            // Java：返回当前基础库的 next；VM 按 NextFn 类型识别并内联迭代。
            return varargsOf(next, args.checktable(1), LuaValue.NIL);
        }
    }

    // PcallFn
    static class PcallFn extends LuaFunction {
        @Override
        public Varargs call(Varargs args) {
            Globals g = activeGlobals(this);
            LuaValue func = args.checkvalue(1);
            if (g.debuglib != null) g.debuglib.onCall(this);

            LuaThread protectedLuaThread = g.running;
            int savedNCcalls = protectedLuaThread.nCcalls;
            int savedNny = protectedLuaThread.nny;

            int oldTop = protectedLuaThread.top;
            CallInfo oldCi = protectedLuaThread.ci;
            byte oldAllowhook = protectedLuaThread.allowhook;
            try {
                // lbaselib.c: luaB_pcall  -  压 true，调用，返回 finishpcall
                // java diff: C 用栈操作（push true + lua_pcallk）；Java 用 Varargs 返回
                Varargs result;
                try {
                    result = LuaCall.callLua(func, args.subargs(2));
                } catch (Throwable t) {
                    if (t instanceof LuaThread.CloseSelf) throw (LuaThread.CloseSelf) t;
                    if (t instanceof LuaError le) throw le;
                    if (t instanceof Exception e) throw e;
                    if (t instanceof StackOverflowError soe) throw soe;
                    // NPE 或其他 Error: 转为 LuaError
                    // java diff: C 用 setjmp/longjmp 不受 NPE 影响；Java 必须显式处理
                    throw LuaErrors.errorObject(t.getMessage() != null ? t.getMessage() : t.getClass().getSimpleName());
                }
                // lbaselib.c: lua_pushboolean(L,1); lua_insert(L,1)  -  true + 结果
                // java diff: C 压 true 后旋转；Java 用 Varargs.of(TRUE, result)
                return Varargs.of(LuaValue.TRUE, result);
            } catch (LuaError le) {
                // java diff: CloseSelf 必须传播到 runCoroutine，不可被 pcall 捕获
                // C 用 longjmp 跳过所有中间 setjmp 点；Java 必须显式重抛以到达 runCoroutine 的 catch
                if (le instanceof LuaThread.CloseSelf) throw le;
                // ldo.c: luaD_rawrunprotected  -  恢复 nCcalls/nny
                protectedLuaThread.nCcalls = savedNCcalls;
                protectedLuaThread.nny = savedNny;
                // java-only: 惰性快照 - 清除 pendingError（构造器的 setPendingError 可能已设置），
                // 让 closeUpvals 期间 traceback 走 live 路径（对齐 C：pcall 不设 pendingError），
                // 省去 DebugFrame 分配；__close 重用 throwCi 的 CI 也无正确性风险
                // （pendingError=null 时 traceback 不触及 le）。
                protectedLuaThread.pendingError = null;
                protectedLuaThread.ci = oldCi;
                protectedLuaThread.allowhook = oldAllowhook;
                LuaValue errObj = normalizeErrorObject(le.getMessageObject());
                // lapi.c: lua_pcallk  -  可 yield 的协程中 pcall 用 continuation 机制允许
                // __close yield，Java 对齐此行为
                boolean isYieldable = protectedLuaThread.nny == 0 && !protectedLuaThread.isMainThread();
                errObj = LuaVM.closeUpvals(protectedLuaThread, oldTop, errObj, isYieldable);
                // ldo.c: luaD_pcall  -  错误后 luaD_shrinkstack(L) 恢复栈大小：溢出时已 grow 到
                //   ERRORSTACKSIZE = MAXSTACK+200，pcall 返回后须收缩回最大正常大小，
                //   否则后续代码（cstack.lua f() 的 T.stacklevel/assert）在栈满时继续溢出
                LuaVM.shrinkStack(protectedLuaThread);
                protectedLuaThread.top = oldTop;
                return varargsOf(LuaValue.FALSE, errObj);

            } catch (Exception e) {
                // ldo.c: luaD_rawrunprotected  -  恢复 nCcalls/nny
                protectedLuaThread.nCcalls = savedNCcalls;
                protectedLuaThread.nny = savedNny;
                String m = e.getMessage();
                LuaValue errObj = LuaValue.valueOf(m != null ? m : e.toString());
                protectedLuaThread.pendingError = null;
                protectedLuaThread.ci = oldCi;
                protectedLuaThread.allowhook = oldAllowhook;
                boolean isYieldable = protectedLuaThread.nny == 0 && !protectedLuaThread.isMainThread();
                errObj = LuaVM.closeUpvals(protectedLuaThread, oldTop, errObj, isYieldable);
                // ldo.c: luaD_pcall  -  错误后 luaD_shrinkstack(L) 恢复栈大小
                //   （理由同 LuaError 分支：ERRORSTACKSIZE 须收缩回最大正常大小）
                LuaVM.shrinkStack(protectedLuaThread);
                protectedLuaThread.top = oldTop;
                return varargsOf(LuaValue.FALSE, errObj);
            } catch (StackOverflowError soe) {
                // java diff: C 用 setjmp/longjmp 不受栈溢出影响；Java 的 StackOverflowError 必须显式捕获
                protectedLuaThread.nCcalls = savedNCcalls;
                protectedLuaThread.nny = savedNny;
                protectedLuaThread.pendingError = null;
                protectedLuaThread.ci = oldCi;
                protectedLuaThread.allowhook = oldAllowhook;
                boolean isYieldable = protectedLuaThread.nny == 0 && !protectedLuaThread.isMainThread();
                LuaValue errObj = LuaString.newStr("C stack overflow");
                errObj = LuaVM.closeUpvals(protectedLuaThread, oldTop, errObj, isYieldable);
                protectedLuaThread.top = oldTop;
                return varargsOf(LuaValue.FALSE, errObj);
            } finally {
                try {
                    if (protectedLuaThread != null) protectedLuaThread.pendingError = null;
                    restoreProtectedCallState(g, protectedLuaThread, savedNCcalls, savedNny);
                    LuaVM.shrinkStack(protectedLuaThread);
                } catch (Exception e) {
                    // ignore
                }
                if (g.debuglib != null) g.debuglib.onReturn();
            }
        }

        // java-only: callOnStack  -  lbaselib.c luaB_pcall（对齐 lua_pcallk 的栈内协议）：
        //   参数已在栈上 func+1..func+narg，把 func+1 当新调用基址就地调用，免去两趟 Varargs 打包。
        // 铁律：不支持的形态必须在开调前挡掉；一旦开调，catch 到的错误一律就地转 false+errObj ——
        //   return -1 交回退路径重跑在递归 pcall 下按深度指数爆炸（官方套件实测挂死）。
        @Override
        public int callOnStack(LuaThread L, int func, int narg) {
            if (!LuaFunction.LIB_CALLONSTACK) return -1;
            if (narg < 1) return -1;                 // 无被保护函数：交回退路径报错
            Globals g = activeGlobals(this);
            if (g == null || g.running != L) return -1;
            if (g.debuglib != null) return -1;       // 有 debug hook：走已审计的完整路径

            int fIdx = func + 1;                     // 被保护函数所在槽
            LuaValue target = L.stack[fIdx];
            // 仅接受 Lua 闭包：C 函数与 __call 对象的错误/yield 语义分支多，交回退路径
            if (!(target instanceof LuaClosure)) return -1;

            LuaThread th = L;
            int savedNCcalls = th.nCcalls;
            int savedNny = th.nny;
            int oldTop = th.top;
            CallInfo oldCi = th.ci;
            byte oldAllowhook = th.allowhook;
            try {
                // ldo.c: luaD_call  -  就地调用，参数已在 fIdx+1..func+narg
                LuaCall.callLua(th, fIdx, LuaValue.LUA_MULTRET);
                // 结果由 poscall 落在 fIdx 起，共 (th.top - fIdx) 个
                int nres = th.top - fIdx;
                LuaVM.checkStack(th, 1);
                // lbaselib.c: lua_pushboolean(L,1); lua_insert(L,1)  -  true 插到结果前
                if (nres > 0) {
                    System.arraycopy(th.stack, fIdx, th.stack, fIdx + 1, nres);
                }
                th.stack[fIdx] = LuaValue.TRUE;
                th.top = fIdx + 1 + nres;
                // precallC 约定：结果从 L.stack[L.top - n] 起、L.top 已更新
                return nres + 1;
            } catch (Throwable t) {
                // 就地善后（绝不 return -1 让外层重跑，见方法头注释）
                // level = fIdx（被保护函数槽），对齐 C 的 savestack(L, c.func)
                LuaValue errObj = recoverProtectedOnStack(
                        th, t, savedNCcalls, savedNny, fIdx, oldCi, oldAllowhook);
                // lbaselib.c: false + errObj。栈布局同成功分支：结果从 fIdx 起
                LuaVM.checkStack(th, 2);
                th.stack[fIdx] = LuaValue.FALSE;
                th.stack[fIdx + 1] = errObj;
                th.top = fIdx + 2;
                return 2;
            }
            // 注：无 finally 的 debuglib.onReturn()  -  入口已 `if (g.debuglib != null) return -1`，
            //   本方法内 debuglib 恒为 null，钩子调用属死代码。
        }
    }

    // PrintFn
    static class PrintFn extends LuaFunction {
        @Override
        public Varargs call(Varargs args) {
            LuaValue tostring = activeGlobals(this).get("tostring");
            StringBuilder sb = new StringBuilder();
            for (int i = 1, n = args.narg(); i <= n; i++) {
                if (i > 1) sb.append('\t');

                sb.append(LuaCall.callOnStack1to1(tostring, args.arg(i)).strValue().toJavaString());
            }
            System.out.println(sb);
            return LuaValue.NONE;
        }

        // java-only: callOnStack  -  lbaselib.c luaB_print  -  直接从栈读参数，消除 Varargs 分配。
        //   逐参调 tostring 拼接（tab 分隔），对齐 C 的 luaB_print；tostring 走 callOnStack1to1
        //   （L.top 之上独立栈帧，不破坏 func+1..func+narg）。结果恒 0 值，无需 bail-out。
        @Override
        public int callOnStack(LuaThread L, int func, int narg) {
            if (!LuaFunction.LIB_CALLONSTACK) return -1;
            LuaValue tostring = activeGlobals(this).get("tostring");
            StringBuilder sb = new StringBuilder();
            for (int i = 1; i <= narg; i++) {
                if (i > 1) sb.append('\t');
                LuaValue arg = L.stack[func + i];
                if (arg == null) arg = LuaValue.NIL;
                sb.append(LuaCall.callOnStack1to1(tostring, arg).strValue().toJavaString());
            }
            System.out.println(sb);
            return 0;
        }
    }

    // WarnFn
    static class WarnFn extends LuaFunction {
        private final BaseLib base;

        WarnFn(BaseLib base) {
            this.base = base;
        }

        @Override
        public Varargs call(Varargs args) {

            args.checkstring(1);
            for (int i = 2, n = args.narg(); i <= n; i++) {
                args.checkstring(i);
            }
            int n = args.narg();
            for (int i = 1; i < n; i++) {
                base.luaWarning(args.arg(i).checkstring().toJavaString(), true);
            }
            base.luaWarning(args.arg(n).checkstring().toJavaString(), false);
            return LuaValue.NONE;
        }
    }

    // RawEqualFn
    static class RawEqualFn extends LuaFunction {
        @Override
        public Varargs call(Varargs args) {
            return LuaValue.valueOf(rawEqualNum(args.arg1(), args.arg(2)));
        }

        // java-only: callOnStack  -  lbaselib.c luaB_rawequal  -  比较两个值是否原始相等
        @Override
        public int callOnStack(LuaThread L, int func, int narg) {
            if (narg < 2) return -1;
            LuaValue arg1 = L.stack[func + 1];
            LuaValue arg2 = L.stack[func + 2];
            if (arg1 == null || arg2 == null) return -1;
            L.stack[L.top++] = LuaBoolean.valueOf(rawEqualNum(arg1, arg2));
            return 1;
        }
    }

    // lvm.c: luaV_equalobj —— lua_rawequal 走 luaV_equalobj(NULL,..)：int/float 交叉做
    // 数值比较（1 与 1.0 为真；2^63 与 9.223372036854776E18 也为真——直接 double 比较，
    // 不经 floatToInt 截断）。per-type raweq 不处理交叉，恒 false。
    private static boolean rawEqualNum(LuaValue a, LuaValue b) {
        if (a instanceof LuaInteger ai && b instanceof LuaFloat bf) return (double) ai.v == bf.v;
        if (a instanceof LuaFloat af && b instanceof LuaInteger bi) return af.v == (double) bi.v;
        return a.raweq(b);
    }

    // RawGetFn
    static class RawGetFn extends LuaFunction {
        @Override
        public Varargs call(Varargs args) {
            LuaValue arg1 = args.arg1();
            LuaValue arg2 = args.arg(2);
            return args.checktable(1).hashGet(arg2);
        }

        // java-only: callOnStack  -  lbaselib.c luaB_rawget  -  原始读取表中键对应的值
        @Override
        public int callOnStack(LuaThread L, int func, int narg) {
            if (narg < 2) return -1;
            LuaValue arg1 = L.stack[func + 1];
            LuaValue arg2 = L.stack[func + 2];
            if (arg1 == null || arg2 == null || !arg1.istable()) return -1;
            L.stack[L.top++] = ((LuaTable) arg1).hashGet(arg2);
            return 1;
        }
    }

    // RawLenFn
    static class RawLenFn extends LuaFunction {
        @Override
        public Varargs call(Varargs args) {
            LuaValue arg = args.arg1();
            if (arg.istable()) {
                // java-only: userdata 非 table，无需排除
            } else if (!arg.isstring()) {
                LuaErrors.typeError(1, arg, "table or string");
            }
            return LuaValue.valueOf(arg.rawlen());
        }

        // java-only: callOnStack  -  lbaselib.c luaB_rawlen  -  返回表或字符串的原始长度
        @Override
        public int callOnStack(LuaThread L, int func, int narg) {
            if (narg < 1) return -1;
            LuaValue arg = L.stack[func + 1];
            if (arg == null) return -1;
            if (!arg.istable() && !arg.isstring()) return -1;
            L.stack[L.top++] = LuaInteger.valueOf(arg.rawlen());
            return 1;
        }
    }

    // RawSetFn
    static class RawSetFn extends LuaFunction {
        @Override
        public Varargs call(Varargs args) {
            LuaValue table = args.arg1();
            LuaValue index = args.arg(2);
            LuaValue value = args.arg(3);
            LuaValue t = args.checktable(1);
            if (index.isnil()) LuaErrors.argError(2, "table index is nil");
            t.rawset(index, value);
            return t;
        }

        // java-only: callOnStack  -  lbaselib.c luaB_rawset  -  原始设置表中键值对
        @Override
        public int callOnStack(LuaThread L, int func, int narg) {
            if (narg < 3) return -1;
            LuaValue table = L.stack[func + 1];
            LuaValue index = L.stack[func + 2];
            LuaValue value = L.stack[func + 3];
            if (table == null || index == null || value == null) return -1;
            if (!table.istable()) return -1;
            if (index.isnil()) return -1;
            table.rawset(index, value);
            L.stack[L.top++] = table;
            return 1;
        }
    }

    // SelectFn
    static class SelectFn extends LuaFunction {
        @Override
        public Varargs call(Varargs args) {
            int n = args.narg() - 1;
            if (args.arg1().equals(LuaValue.valueOf("#"))) return LuaValue.valueOf(n);
            int i = args.checkint(1);
            if (i == 0 || i < -n) LuaErrors.argError(1, "index out of range");
            return args.subargs(i < 0 ? n + i + 2 : i + 1);
        }

        // java-only: callOnStack  -  lbaselib.c luaB_select  -  直接从栈读参数，消除 Varargs 分配。
        //   关键：select 的结果就是输入参数的子集，precallC 已把参数放在 func+1..func+narg，
        //   整数分支零拷贝直接返回 count（moveresults 从 L.top-nres 读取），"#" 分支压计数值。
        //   对齐 C 的 luaB_select（C 同样不移动数据，只 return n - i）。
        @Override
        public int callOnStack(LuaThread L, int func, int narg) {
            if (!LuaFunction.LIB_CALLONSTACK) return -1;
            if (narg < 1) return -1;
            LuaValue v1 = L.stack[func + 1];
            if (v1 == null) return -1;
            // lbaselib.c: luaB_select  -  select("#", ...) 返回 "..." 的数量
            if (v1 instanceof LuaString ls && ls.shrlen == 1 && (ls.contents[0] & 0xFF) == '#') {
                L.stack[L.top++] = LuaInteger.valueOf(narg - 1);
                return 1;
            }
            // lbaselib.c: luaB_select  -  整数索引分支
            if (!v1.isinteger()) return -1;  // 非整数（含字符串），走通用路径 checkint 报错/转换
            long i = v1.tolong();
            // lbaselib.c: luaB_select  -  C 的 n = lua_gettop(L) = narg（含 arg1）
            if (i < 0) i = narg + i;
            else if (i > narg) i = narg;
            // lbaselib.c: luaB_select  -  luaL_argcheck(L, 1 <= i, 1, "index out of range")
            if (i < 1) LuaErrors.argError(1, "index out of range");
            int count = (int) (narg - i);
            return count;
        }
    }

    // SetMetatableFn
    // lbaselib.c: luaB_setmetatable
    static class SetMetatableFn extends LuaFunction {
        @Override
        public Varargs call(Varargs args) {
            LuaValue table = args.arg1();
            LuaValue metatable = args.arg(2);
            LuaErrors.argexpected(1, table, table.istable(), "table");
            LuaValue mt0 = args.checktable(1).getmetatable();
            if (mt0 != null && !mt0.rawget(LuaValue.METATABLE).isnil())
                LuaErrors.error("cannot change a protected metatable");
            LuaErrors.argexpected(2, metatable, metatable.isnil() || metatable.istable(), "nil or table");
            if (ownerGlobals != null && table instanceof LuaTable t) t.bindGlobals(ownerGlobals);
            if (ownerGlobals != null && metatable instanceof LuaTable mt) mt.bindGlobals(ownerGlobals);
            LuaValue result = table.setmetatable(metatable.isnil() ? null : metatable);
            return result;
        }

        // java-only: callOnStack  -  lbaselib.c luaB_setmetatable  -  检查 __metatable 保护后设置元表
        @Override
        public int callOnStack(LuaThread L, int func, int narg) {
            if (narg < 2) return -1;
            LuaValue tv = L.stack[func + 1];
            LuaValue mtv = L.stack[func + 2];
            if (tv == null || mtv == null) return -1;
            if (!tv.istable()) return -1;
            if (!mtv.isnil() && !mtv.istable()) return -1;
            LuaTable t = (LuaTable) tv;
            if (ownerGlobals != null) t.bindGlobals(ownerGlobals);
            if (ownerGlobals != null && mtv instanceof LuaTable mt) mt.bindGlobals(ownerGlobals);
            LuaValue mt0 = t.getmetatable();
            if (mt0 != null && !mt0.rawget(LuaValue.METATABLE).isnil()) return -1;
            LuaValue result = t.setmetatable(mtv.isnil() ? null : mtv);
            L.stack[L.top] = result;
            L.top++;
            return 1;
        }
    }

    // ToNumberFn
    // lbaselib.c: luaB_tonumber
    static class ToNumberFn extends LuaFunction {
        @Override
        public Varargs call(Varargs args) {

            LuaValue.checkany(1, args);
            LuaValue e = args.arg1();
            if (args.narg() < 2) return e.tonumber();
            LuaValue base = args.arg(2);
            if (base.isnil()) return e.tonumber();
            int b = args.checkint(2);
            if (b < 2 || b > 36) LuaErrors.argError(2, "base out of range");
            LuaNumber n = args.checkstring(1).scannumber(b);
            return n != null ? n : LuaValue.NIL;
        }

        // java-only: callOnStack  -  lbaselib.c luaB_tonumber  -  值转数字
        @Override
        public int callOnStack(LuaThread L, int func, int narg) {
            if (narg < 1) return -1;
            LuaValue e = L.stack[func + 1];
            if (e == null) return -1;
            if (narg >= 2) {
                LuaValue base = L.stack[func + 2];
                if (base != null && !base.isnil()) return -1;
            }
            LuaValue r = e.tonumber();
            if (r.isnil()) {
                L.stack[L.top] = LuaValue.NIL;
                L.top++;
                return 1;
            }
            L.stack[L.top] = r;
            L.top++;
            return 1;
        }
    }

    // ToStringFn
    public static class ToStringFn extends LuaFunction {
        @Override
        public Varargs call(Varargs args) {

            LuaValue.checkany(1, args);
            LuaValue arg = args.arg1();
            // 1) __tostring 元方法

            LuaValue h = arg.metaTag(LuaValue.TOSTRING);
            if (!h.isnil()) {
                // java diff: callNoYield(h,arg) 走 Varargs 往返；此处须 callOnStack1to1 直接栈操作
                LuaValue r = LuaCall.callOnStack1to1(h, arg);
                // lauxlib.c: luaL_tolstring  -  用 lua_isstring（数字也可）；
                // __tostring 返回数字 42 应得到 "42" 而非报错
                if (!r.isstring() && !r.isnumber()) LuaErrors.error("'__tostring' must return a string");
                // lauxlib.c: luaL_tolstring 末尾 `return lua_tolstring(L, -1, len)`  -
                //   lua_tolstring 对数字做**原地转换**（lvm.c luaV_tostring），故 __tostring
                //   返回 42 时调用方拿到的是字符串 "42"（type()=="string"）而非数字。
                return r.isnumber() ? r.tostring() : r;
            }
            // 2) 按类型转换
            LuaValue v = arg.tostring();
            if (!v.isnil()) return v;  // 数字、字符串

            if (arg.isboolean()) return LuaString.newStr(Boolean.toString(arg.toboolean()));
            if (arg.isnil()) return LuaString.newStr("nil");
            // 3) 默认：(__name 或 typename) + ": " + 指针

            String kind = LuaValue.objTypeName(arg);
            return LuaString.newStr(kind + ": " + Integer.toHexString(arg.hashCode()));
        }

        // java-only: callOnStack  -  lbaselib.c luaB_tostring  -  值转字符串
        @Override
        public int callOnStack(LuaThread L, int func, int narg) {
            if (narg < 1) return -1;
            LuaValue arg = L.stack[func + 1];
            if (arg == null) return -1;
            if (!arg.metaTag(LuaValue.TOSTRING).isnil()) return -1;
            LuaValue v = arg.tostring();
            if (!v.isnil()) {
                L.stack[L.top] = v;
                L.top++;
                return 1;
            }
            if (arg.isboolean()) {
                L.stack[L.top] = LuaString.newStr(Boolean.toString(arg.toboolean()));
                L.top++;
                return 1;
            }
            if (arg.isnil()) {
                L.stack[L.top] = LuaString.newStr("nil");
                L.top++;
                return 1;
            }
            return -1;
        }
    }

    // TypeFn
    static class TypeFn extends LuaFunction {
        @Override
        public Varargs call(Varargs args) {

            LuaValue.checkany(1, args);
            LuaValue arg = args.arg1();
            return LuaString.newStr(arg.typeName());
        }

        // java-only: callOnStack  -  lbaselib.c luaB_type  -  返回类型名字符串
        @Override
        public int callOnStack(LuaThread L, int func, int narg) {
            if (narg < 1) return -1;
            LuaValue arg = L.stack[func + 1];
            if (arg == null) return -1;
            L.stack[L.top++] = LuaString.newStr(arg.typeName());
            return 1;
        }
    }

    // XpcallFn
    static class XpcallFn extends LuaFunction {
        @Override
        public Varargs call(Varargs args) {
            Globals g = activeGlobals(this);
            LuaThread t = g.running;
            // lapi.c: lua_pcallk  -  保存旧 errfunc，设置新 errfunc
            // java diff: C 把 errfunc 存为栈偏移（ptrdiff_t）；Java 存直接函数引用
            LuaValue savedErrfuncRef = t.errfuncRef;
            LuaValue handler = args.checkvalue(2);
            t.errfuncRef = handler;
            LuaThread protectedLuaThread = g.running;
            int savedNCcalls = protectedLuaThread.nCcalls;
            int savedNny = protectedLuaThread.nny;

            int oldTop = protectedLuaThread.top;
            CallInfo oldCi = protectedLuaThread.ci;
            byte oldAllowhook = protectedLuaThread.allowhook;
            try {
                if (g.debuglib != null) g.debuglib.onCall(this);
                try {
                    // lbaselib.c: luaB_xpcall  -  栈 [f,luaB_error,true,f]，lua_pcallk 的 c.func=
                    //   L->top-nargs = ci.func+5 -> f 帧 func = xpcall.func+5。
                    // java diff: 须模拟 C 栈布局（相对 3=true、4=f，L.top=ci.func+5），
                    //   从 L.top 压会差 2 槽致 cstack.lua 边界断言失败。
                    int xf = protectedLuaThread.ci.func;
                    protectedLuaThread.stack[xf + 3] = LuaValue.TRUE;   // C 相对 3 = true
                    protectedLuaThread.stack[xf + 4] = args.arg1();     // C 相对 4 = f（复制）
                    protectedLuaThread.top = xf + 5;                    // C 的 L.top（gettop=4）
                    Varargs result = LuaCall.callLua(args.arg1(), args.subargs(3));
                    return Varargs.of(LuaValue.TRUE, result);
                } catch (LuaError le) {
                    // java diff: CloseSelf 必须传播到 runCoroutine，不可被 xpcall 捕获
                    if (le instanceof LuaThread.CloseSelf) throw le;
                    // ldo.c: luaD_rawrunprotected  -  恢复 nCcalls/nny
                    // java diff: C 的 luaG_errormsg 在 luaD_rawrunprotected 之外调用，错误处理函数
                    // 执行时 nCcalls 仍是高值（通常 == LUAI_MAXCCALLS），故其中的递归调用会触发
                    // "error in error handling"；Java 里 ccall 的 finally 已恢复 nCcalls，必须在
                    // callMessageHandler 之前设回错误发生时的高值，否则该触发不发生（errors.lua:610）
                    LuaValue errObj = normalizeErrorObject(le.getMessageObject());
                    // luaG_errormsg: 先调用 message handler
                    LuaValue handled;
                    try {
                        handled = callMessageHandler(handler, errObj, le);
                    } finally {
                        le.savedStack = null;
                        // java-only: 惰性快照 - 清除 throw 时的 ci 引用，防止 handler 后 CI 被重用时错误快照
                        le.clearThrowState();
                        protectedLuaThread.pendingError = null;
                    }
                    // ldo.c: luaD_pcall  -  在 message handler 之后恢复 nCcalls/nny
                    protectedLuaThread.nCcalls = savedNCcalls;
                    protectedLuaThread.nny = savedNny;
                    // luaD_pcall: L->ci = old_ci; L->allowhook = old_allowhooks
                    protectedLuaThread.ci = oldCi;
                    protectedLuaThread.allowhook = oldAllowhook;
                    // luaD_closeprotected: 关闭 upvalue
                    boolean isYieldable = protectedLuaThread.nny == 0 && !protectedLuaThread.isMainThread();
                    LuaVM.closeUpvals(protectedLuaThread, oldTop, errObj, isYieldable);
                    // luaD_seterrorobj: 把 message handler 结果放到 oldTop
                    protectedLuaThread.top = oldTop;
                    return varargsOf(LuaValue.FALSE, handled);

                } catch (Exception e) {
                    // ldo.c: luaD_rawrunprotected  -  为 message handler 把 nCcalls 设为高值
                    // java diff: 同 LuaError catch 块，nCcalls 必须在 callMessageHandler 之前设为
                    // LUAI_MAXCCALLS，否则错误处理函数中的递归调用不触发
                    // "error in error handling"（errors.lua:610）
                    String m = e.getMessage();
                    LuaValue errObj = LuaValue.valueOf(m != null ? m : e.toString());
                    // luaG_errormsg: 先调用 message handler
                    LuaValue handled;
                    try {
                        protectedLuaThread.pendingError = null;
                        handled = callMessageHandler(handler, errObj, null);
                    } finally {
                        protectedLuaThread.pendingError = null;
                    }
                    // ldo.c: luaD_pcall  -  在 message handler 之后恢复 nCcalls/nny
                    protectedLuaThread.nCcalls = savedNCcalls;
                    protectedLuaThread.nny = savedNny;
                    // luaD_pcall: 恢复状态
                    protectedLuaThread.ci = oldCi;
                    protectedLuaThread.allowhook = oldAllowhook;
                    boolean isYieldable2 = protectedLuaThread.nny == 0 && !protectedLuaThread.isMainThread();
                    LuaVM.closeUpvals(protectedLuaThread, oldTop, errObj, isYieldable2);
                    protectedLuaThread.top = oldTop;
                    return varargsOf(LuaValue.FALSE, handled);
                }
            } finally {

                restoreProtectedCallState(g, protectedLuaThread, savedNCcalls, savedNny);
                LuaVM.shrinkStack(protectedLuaThread);
                // lapi.c: lua_pcallk  -  L->errfunc = ci->u.c.old_errfunc
                t.errfuncRef = savedErrfuncRef;
                if (g.debuglib != null) g.debuglib.onReturn();
            }
        }

        private LuaValue callMessageHandler(LuaValue handler, LuaValue message, LuaError handled) {
            Globals g = activeGlobals(this);
            LuaThread protectedLuaThread = g != null ? g.running : null;
            int savedNCcalls = protectedLuaThread != null ? protectedLuaThread.nCcalls : 0;
            int savedNny = protectedLuaThread != null ? protectedLuaThread.nny : 0;
            int savedTop = protectedLuaThread != null ? protectedLuaThread.top : 0;
            // java-only: 记录 handler 调用点的 ci，供 traceback 把 handler 之上的 live 帧
            //   接在错误快照之前（C 的 handler 帧直接压在抛错帧上，见 DebugHook.errorFrames）
            CallInfo savedErrfuncBaseCi = protectedLuaThread != null ? protectedLuaThread.errfuncBaseCi : null;
            LuaError savedErrfuncError = protectedLuaThread != null ? protectedLuaThread.errfuncError : null;
            if (protectedLuaThread != null) {
                protectedLuaThread.errfuncBaseCi = protectedLuaThread.ci;
                protectedLuaThread.errfuncError = handled;
            }
            LuaValue current = message;

            try {
                for (int depth = 0; depth < Globals.LUAI_MAXCCALLS; depth++) {
                    try {
                        return normalizeErrorObject(LuaCall.callOnStack1to1(handler, current));
                    } catch (LuaError e) {
                        // java diff: CloseSelf 必须传播到 runCoroutine
                        if (e instanceof LuaThread.CloseSelf) throw e;
                        LuaValue next = normalizeErrorObject(e.getMessageObject());
                        e.savedStack = null;
                        // java-only: 惰性快照 - 清除 throw 时的 ci 引用
                        e.clearThrowState();
                        if (!(next instanceof LuaNumber)) {
                            return LuaString.newStr("error in error handling");
                        }
                        if (next.raweq(current)) {
                            return LuaString.newStr("error in error handling");
                        }
                        current = next;
                    } catch (Exception e) {
                        return LuaString.newStr("error in error handling");
                    } finally {
                        if (protectedLuaThread != null) {
                            protectedLuaThread.nCcalls = savedNCcalls;
                            protectedLuaThread.nny = savedNny;
                            protectedLuaThread.top = savedTop;
                        }
                    }
                }
                return LuaString.newStr("C stack overflow");
            } finally {
                if (protectedLuaThread != null) {
                    protectedLuaThread.errfuncBaseCi = savedErrfuncBaseCi;
                    protectedLuaThread.errfuncError = savedErrfuncError;
                }
            }
        }
    }

    private static class StringInputStream extends InputStream {
        final LuaValue func;
        byte[] bytes;
        int offset, remaining = 0;

        StringInputStream(LuaValue func) {
            this.func = func;
        }

        @Override
        public int read() {
            if (remaining < 0) return -1;
            if (remaining == 0) {

                LuaValue s = LuaCall.callOnStack1to1(func, LuaValue.NIL);
                if (s.isnil()) return remaining = -1;
                LuaString ls = s.strValue();
                bytes = ls.contents;
                offset = 0;
                remaining = ls.shrlen;
                if (remaining <= 0) return -1;
            }
            --remaining;
            return 0xFF & bytes[offset++];
        }
    }


}
