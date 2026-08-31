// ref: lstate.h (global_State)
// diff: DebugFrame 是 CallInfo 链的调试视图(C 用逐次 lua_getinfo 填 lua_Debug，无常驻等价物);
//       字段命名部分未对齐C源码; 编译器/加载器等Java特有字段
package org.luajvm.core;

import org.luajvm.vm.LuaCall;
import org.luajvm.spi.BaseLibrary;
import org.luajvm.spi.Compiler;
import org.luajvm.spi.DebugTracer;
import org.luajvm.spi.Loader;
import org.luajvm.spi.LuaConfig;
import org.luajvm.vm.LuaPlatform;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;

public class Globals extends LuaTable {
    private final ReentrantLock executionLock = new ReentrantLock();
    /** C：lstate.h : global_State。 */
    final GCState gc = new GCState();

    /**
     * C：lstate.h : global_State.mt[LUA_NUMTYPES]  -  基础类型的按状态共享元表。
     * 表和 userdata 各自携带元表，不用这里；nil/boolean/number/string/thread/
     * lightuserdata 的元表是状态属性（C 用 {@code G(L)->mt[ttype(o)]} 取）。
     * 静态共享会让第二个状态的 stringlib 元表指向第一个状态的 string 表。
     */
    final LuaValue[] typeMetatables = new LuaValue[TTHREAD + 1];

    /**
     * C：liolib.c : createmeta 建的 FILE* 元表与方法表，存于该状态的 registry。
     * 静态共享会让第二个状态的文件句柄拿到第一个状态的方法表函数对象。
     */
    public LuaTable ioFileMetatable;

    /**
     * java-only：luajava 的 {@code JavaClass} 缓存（Class -> 绑定 userdata）。
     * 必须按状态隔离：{@code JavaClass} 是 {@code LuaUserdata}，携带
     * {@code ownerGlobals}，跨状态复用会拿到携带他状态归属的绑定对象而被拒用。
     */
    public final Map<Class<?>, LuaValue> javaClassCache =
            new ConcurrentHashMap<>();
    public final Map<String, LuaValue> javaClassByNameCache =
            new ConcurrentHashMap<>();

    /**
     * C：lstring.c : luaS_new 的 g->strcache  -  按状态存放。
     * 静态共享会把 A 状态的长串返给 B（而它登记在 A 的 allgc，A 的 GC 会释放它），
     * 且 luaS_clearcache 会按另一个状态的白色观念驱逐本状态的条目。
     */
    LuaString[][] strCache;

    /** C：ltm.c : luaT_gettmbyobj 取 G(L)->mt[t]。 */
    public LuaValue typeMetatable(int type) {
        return (type >= 0 && type < typeMetatables.length) ? typeMetatables[type] : null;
    }

    /** C：lapi.c : lua_setmetatable 对基础类型写 G(L)->mt[t]。 */
    public void setTypeMetatable(int type, LuaValue mt) {
        if (type >= 0 && type < typeMetatables.length) {
            if (mt != null) bindValue(this, mt);
            typeMetatables[type] = mt;
        }
    }

    public Globals() {
        super();
        bindGlobals(this);
        registry.bindGlobals(this);
        LuaTable.registerGlobals(this);
        // 登记到中立登记表：字符串分配路径经它取所属状态，
        // 不引用 LuaTable/Globals 静态成员，避免静态初始化环。
        LuaStates.register(this);
    }

    /** C：lstate.h : global_State 的收集器和对象链。 */
    static final class GCState {
        final LuaGC.GrayList gray = new LuaGC.GrayList();
        final LuaGC.GrayList grayagain = new LuaGC.GrayList();
        // lstate.h: global_State 的弱表三链。对象同时只能属于一条链（与 gray/grayagain
        // 共用 LuaValue.gclist 作 next 指针），对应 C 的单一灰链所有权。
        final LuaGC.GrayList weak = new LuaGC.GrayList();       // 弱值表，待 clearbyvalues
        final LuaGC.GrayList allweak = new LuaGC.GrayList();    // 含白键，待 clearbykeys
        final LuaGC.GrayList ephemeron = new LuaGC.GrayList();  // 含白键->白值，需继续收敛
        final ArrayList<LuaTable> allTables = new ArrayList<>();
        final ArrayDeque<LuaTable.PendingFinalizer> finobj = new ArrayDeque<>();
        final ArrayList<LuaValue> tobefnz = new ArrayList<>();
        final ArrayList<LuaFunction> allFunctions = new ArrayList<>();
        final ArrayList<LuaClosure> allClosures = new ArrayList<>();
        final ArrayList<LuaThread> allThreads = new ArrayList<>();
        final ArrayList<LuaUserdata> allUserdata = new ArrayList<>();
        final ArrayList<Prototype> allProtos = new ArrayList<>();
        final ArrayList<LuaString> longStrings = new ArrayList<>();

        long GCdebt = 1024 * 1024;
        long allocationDebt;
        long luaMemoryBytes;
        long maxLuaMemoryBytes;
        long GCmarked;
        long lastJvmGcNanos;
        long currentAllocationGcThreshold = LuaGC.BASE_ALLOCATION_GC_THRESHOLD;
        long allocationFinalizerCandidateThreshold = LuaGC.ALLOCATION_GC_FINALIZER_CANDIDATE_MIN_THRESHOLD;
        int gcStepRemain;
        int gcstate = LuaGC.GCSpause;
        int gckind = LuaGC.KGC_INC;
        final long[] gcParams = {250L, 200L, 11200L, 20L, 50L, 70L};
        byte currentwhite;
        boolean allocationGcRunning;
        boolean objectsSweptThisCycle;
        boolean heapDirty = true;
        boolean needRepropagate = true;
        boolean gcStoppedByUser;
        boolean gcemergency;
        boolean gcstopem;
        boolean runningFinalizers;
        boolean sweepingWeakTables;
        long youngCollectionCount;
    }
    // LUAI_MAXCCALLS（ldo.h；测试版 ltests.h 覆盖为 180）
    // C 编译期 #define -> Java 系统属性可配置（lua55-debug 测试环境经
    //   -Dluajvm.maxccalls=180 对齐 ltests.h；生产默认 200）
    public static final int LUAI_MAXCCALLS =
            Integer.getInteger("luajvm.maxccalls", 200);
    // TValue l_registry
    public final LuaTable registry = new LuaTable();
    // 编译器
    public Compiler compiler;
    // 加载器
    public Loader loader;
    // 配置
    public LuaConfig config;
    // 当前运行线程（协程 resume/yield 时切换） - C 处处显式传 lua_State *L，
    //   Java 不逐层传故集中存放。非 mainthread（见 LuaThread.mainThread，对齐 C mainthread(G)）
    public volatile LuaThread running;
    LuaThread mainThread;
    // 标准流
    // C：liolib.c 的 IO_prefix "_IO_input"/"_IO_output" registry 槽  -  当前默认输入/输出句柄。
    // java diff: 存为字段而非 registry 键。无 STDERR 字段：C 也没有"当前 stderr"概念，
    //   io.stderr 是固定句柄，直接放在 io 表里（经 io 表可达，GC 自然覆盖）。
    public LuaValue STDIN = LuaValue.NIL, STDOUT = LuaValue.NIL;
    // 基础库
    // java diff: 类型是 spi.BaseLibrary 而非 lib.BaseLib —— core 只需要"当前状态的基础库
    //   服务"（loadFile/loadStream/where/err…，C 里由 lauxlib/lbaselib 直接提供），
    //   不该为此反向 import lib。BaseLib 装配时 implements 该接口并登记自身。
    public BaseLibrary baselib;
    // lstate.h: global_State.warnf / ud_warn  -  warn 通道（C 的 lua_setwarnf 装在状态上，
    //   不属标准库）。BaseLib 装配时注册自身；core 侧（LuaTable 的 __gc 错误路径）经此调用，
    //   使 core 不反向 import lib。
    public WarnFunction warnf;
    // 包库
    // java-only: hook 函数解析器（C 的 L->hook 直接就是函数指针；Java 的 debug.sethook
    //   经 ldblib.c 的 hookf 派发器 + 弱键 hook 表，故 L.hook 可能是那个派发器而非用户函数）。
    //   VM 侧（LuaVM.callHook）只需"当前该调谁"，不该为此反向依赖 lib：DebugLib 装配时
    //   把解析器登记到状态，未登记时按 C 语义直接用 L.hook。
    public HookResolver hookResolver;

    // 包库
    // java diff: 类型是 LuaValue 而非 lib.PackageLib —— core 从不调它的方法，本字段纯粹是
    //   per-Globals 注册处（PackageLib 装配时写入自己）。声明类型若写具体库类，core 就会
    //   反向依赖 lib；而 core 唯一需要的能力是"持有它"。需要 PackageLib 本身的调用方
    //   （只有库自己）直接持有实例，Lua 侧的 require 走全局（C 的 luaopen_package 同样
    //   把 require 注册为全局）。
    public LuaValue package_;
    // 调试库
    // java diff: 类型是 spi.DebugTracer（只暴露 onCall/onReturn 两个 java-only 追踪钩子）
    public DebugTracer debuglib;
    // Java 绑定库
    // java diff: 类型是 LuaValue 而非 bind.JavaLib，理由同 package_（JavaLib 本身就是
    //   LuaFunction 子类）。需要 JavaLib 实例的调用方经 bind.JavaLib.forGlobals(g) 取回
    //   —— bind → core 是正常的向下依赖。
    public LuaValue luajavaLib;

    @Override
    public Globals checkglobals() {
        return this;
    }

    // C：lgc.c : luaC_checkGC  -  GC 检查的调用边界，以本状态执行。
    public void gcCheck(int bytes) {
        LuaGC.checkGC(this, bytes);
    }

    // 加载库
    public LuaValue load(LuaValue library) {
        return LuaCall.call(library);
    }

    // 加载脚本
    public LuaValue load(String script) {
        return load(script, "stdin");
    }

    // 加载脚本(指定chunk名)
    public LuaValue load(String script, String chunkname) {
        try {
            InputStream is = new ByteArrayInputStream(script.getBytes(StandardCharsets.UTF_8));
            if (compiler != null) {
                LuaValue f = compiler.compile(is, chunkname, "bt", null);
                if (f instanceof LuaFunction fn) return LuaCall.call(fn);
            }
        } catch (Exception e) {
            throw LuaErrors.errorObject(e.getMessage());
        }
        return LuaValue.NIL;
    }

    // 加载输入流
    public Varargs load(InputStream is, String chunkname, String mode, LuaValue env) {
        if (compiler == null) throw LuaErrors.errorObject("no compiler");
        // lapi.c: luaC_checkGC(L)  -  加载前检查 GC
        LuaGC.checkGC(this, 0);
        LuaValue f = compiler.compile(is, chunkname, mode, env);
        if (f instanceof LuaFunction fn) {
            // lapi.c:  -  lua_load: if no env given (null), set global table as 1st upvalue.
            // If env is explicitly nil, set _ENV to nil (load(s, "", "b", nil)).
            // If env is a table, set _ENV to that table.
            if (fn instanceof LuaClosure cl) {
                if (cl.upvals.length > 0) {
                    LuaValue actualEnv = (env != null) ? env : this;
                    cl.upvals[0] = UpVal.closedOf(actualEnv);
                }
            }
        }
        return f != null ? f : LuaValue.NIL;
    }

    // 直接字节源加载 - 跳过 InputStream->readAllBytes 拷贝，用于 load(string) 热路径
    public Varargs loadBytes(byte[] data, String chunkname, String mode, LuaValue env) {
        // lapi.c: luaC_checkGC(L)  -  加载前检查 GC
        LuaGC.checkGC(this, 0);
        LuaValue f = LuaPlatform.protectedParserBytes(this, data, chunkname, mode);
        if (f instanceof LuaFunction fn) {
            if (fn instanceof LuaClosure cl) {
                if (cl.upvals.length > 0) {
                    LuaValue actualEnv = (env != null) ? env : this;
                    cl.upvals[0] = UpVal.closedOf(actualEnv);
                }
            }
        }
        return f != null ? f : LuaValue.NIL;
    }

    // 加载文件
    public LuaValue loadfile(String filename) {
        if (baselib == null) throw LuaErrors.errorObject("no baselib");
        Varargs v = baselib.loadFile(filename, "bt", this);
        return v.isnil(1) ? LuaValue.NIL : v.arg1();
    }

    /** Java：在调用者当前线程进入此状态的唯一 Lua 执行区。 */
    public Varargs invoke(LuaFunction function, Varargs args) {
        if (function == null) return LuaValue.NONE;
        return withExecutionLock(() -> LuaCall.callLua(this, function, args));
    }

    /** Java：在调用者当前线程执行 Lua 源码。 */
    public Varargs execute(String source) {
        return withExecutionLock(() -> LuaPlatform.execute(this, source));
    }

    /** Java：当前线程是否已进入此状态的执行区。 */
    public boolean isExecutingOnCurrentThread() {
        return executionLock.isHeldByCurrentThread();
    }

    /**
     * Java：终止本状态挂起协程的执行线程，并从活动状态登记表注销。
     *
     * <p>与 C 的 {@code lua_close} 不同，本方法只在有协程挂起时才必要：登记表持弱
     * 引用，纯 Lua 对象图在宿主丢弃引用后由 JVM 自动回收（见 {@code LuaStates}）。
     *
     * <p>挂起协程是唯一的例外：线程模式下每个挂起协程占一个 park 着的 Java 线程，
     * 它是 GC 根并经 {@code runCoroutine} 帧强持 {@code LuaThread} -> {@code l_G} ->
     * 整个 {@code Globals}，宿主直接丢弃状态时无法自动收尾。
     *
     * <p>协程正常跑完或经 {@code coroutine.close} 收尾的状态无需调用本方法。
     * 幂等。关闭后不得再执行 Lua。
     */
    public void close() {
        // java diff（有意分叉）：C 的 lua_close 走 close_state -> luaC_freeallobjects ->
        //   callallpendingfinalizers（关 upvalue、跑完所有待终结对象的 __gc）。
        //   Java 的 close() 只终止挂起协程 + 注销登记表：其余对象由 JVM GC 回收，
        //   **不保证剩余 __gc 被调用**。宿主若靠 __gc 释放句柄，须自行显式关闭。
        // closeFromCollector 置 l_G=null 但不摘表项（GC 路径由 sweep 循环摘），故在此自行摘除
        LuaThread[] snapshot = gc.allThreads.toArray(new LuaThread[0]);
        for (LuaThread t : snapshot) {
            if (t == null || t.isMainThread()) continue;
            t.closeFromCollector();
        }
        gc.allThreads.removeIf(t -> t != null && !t.isMainThread() && t.l_G == null);
        LuaStates.unregister(this);
    }

    /**
     * Java：在调用者当前线程进入此状态的执行区运行一段 Java 动作。
     *
     * <p>宿主（Android 适配器等）改本状态的 {@code LuaTable} 必须经此入口：
     * 宿主自己的 monitor 只能与其他宿主线程互斥，无法阻止 Lua 脚本同时改同一张表。
     * 同一状态可重入 - 从 Lua 回调进来时已持锁，不会自死锁。
     */
    public void runGuarded(Runnable action) {
        withExecutionLock(() -> {
            action.run();
            return null;
        });
    }

    /** Java：同一状态可重入，不同状态使用不同锁因而可并行。 */
    private <T> T withExecutionLock(Callable<T> action) {
        executionLock.lock();
        try {
            return callUnchecked(action);
        } finally {
            executionLock.unlock();
        }
    }

    private static <T> T callUnchecked(Callable<T> action) {
        try {
            return action.call();
        } catch (RuntimeException | Error e) {
            throw e;
        } catch (Exception e) {
            throw LuaErrors.errorObject("Lua invocation failed", e);
        }
    }

    // 查找资源
    public InputStream findResource(String name) {
        if (baselib == null) throw LuaErrors.errorObject("no baselib");
        return baselib.openResource(name);
    }

    // 查找文件
    public String findFile(String filename) {
        return filename;
    }

    // lcorolib.c: luaB_yield
    public Varargs yield(Varargs args) {
        if (running == null || running.isMainThread()) {
            throw LuaErrors.errorObject("attempt to yield from outside a coroutine");
        }
        if (running.nny > 0) {
            if (Boolean.getBoolean("luajvm.traceYieldBoundary")) {
                System.err.println("Globals.yield boundary nny=" + running.nny + " nCcalls=" + running.nCcalls +
                        " ciC=" + (running.ci != null && (running.ci.callstatus & CallInfo.CIST_C) != 0));
            }
            throw LuaErrors.errorObject("attempt to yield across a C-call boundary");
        }
        return running.lua_yieldk(args);
    }

    // ldebug.c: lua_getinfo/auxgetinfo/getfuncname
    // DebugFrame 仅是 CallInfo 的调试视图；方法名保留 Java 风格，但内容按 C 的 lua_Debug 填充路径构建。
    public DebugFrame ciToFrame(CallInfo ci, LuaThread L) {
        if (ci == null || L == null || L.stack == null) return null;
        LuaValue funcVal = ci.func >= 0 && ci.func < L.stack.length ? L.stack[ci.func] : null;
        LuaFunction fn = funcVal instanceof LuaFunction ? (LuaFunction) funcVal : null;
        DebugFrame frame = new DebugFrame(fn, L.stack);
        frame.thread = L;
        frame.javaThread = Thread.currentThread();
        frame.top = ci.top;
        frame.base = ci.base();
        frame.pc = ci.isLua() ? CallInfo.currentpc(ci) : -1;
        frame.istailcall = (ci.callstatus & CallInfo.CIST_TAIL) != 0;
        frame.extraargs = (ci.callstatus & CallInfo.MAX_CCMT) >> CallInfo.CIST_CCMT;
        if ((ci.callstatus & CallInfo.CIST_TAIL) == 0 && ci.previous != null) {
            CallInfo caller = ci.previous;
            // funcnamefromcall 返回 namewhat + 写 NameWhat out（对齐 C 的 **name 输出参数）
            LuaDebug.NameWhat nw = new LuaDebug.NameWhat();
            String what = LuaDebug.funcnamefromcall(L, caller, nw);
            if (what != null && !what.isEmpty()) {
                frame.extras().name = nw.name;
                frame.extras().namewhat = what;
            }
            if ((caller.callstatus & CallInfo.CIST_HOOKED) != 0) {
                frame.extras().name = "?";
                frame.extras().namewhat = "hook";
            } else if ((caller.callstatus & CallInfo.CIST_FIN) != 0) {
                frame.extras().name = "__gc";
                frame.extras().namewhat = "metamethod";
            }
        }
        return frame;
    }

    // ==================================================================
    // 调试帧方法  -  从CallInfo链构建DebugFrame视图
    // ==================================================================

    // ldebug.c: lua_getstack + lua_getinfo
    // C 的 CallInfo 链在 longjmp 后仍可由错误处理器遍历；Java 异常展开会离开原执行点，需在抛错时保存同一份调试视图。
    public ArrayList<DebugFrame> snapshotCallInfoChain(LuaThread L) {
        if (L == null || L.ci == null) return null;
        ArrayList<DebugFrame> stack = new ArrayList<>();
        for (CallInfo ci = L.ci; ci != null && ci != L.base_ci; ci = ci.previous) {
            DebugFrame frame = ciToFrame(ci, L);
            if (frame != null) stack.add(frame);
        }
        return stack.isEmpty() ? null : stack;
    }

    // 惰性快照 - 从指定 startCi 遍历 CI 链（LuaError.ensureSnapshot 用）：
    //   throw 时捕获 ci，延迟到需要 traceback 才快照（throw 到快照间 L.stack 不变）
    public ArrayList<DebugFrame> snapshotCallInfoChainFromCi(LuaThread L, CallInfo startCi) {
        if (L == null || startCi == null) return null;
        ArrayList<DebugFrame> stack = new ArrayList<>();
        for (CallInfo ci = startCi; ci != null && ci != L.base_ci; ci = ci.previous) {
            DebugFrame frame = ciToFrame(ci, L);
            if (frame != null) stack.add(frame);
        }
        return stack.isEmpty() ? null : stack;
    }

    // ldebug.c: lua_getstack
    public CallInfo getCallInfoAtLevel(LuaThread L, int level, boolean skipHook) {
        if (L == null || L.ci == null || level < 0) return null;
        CallInfo ci = L.ci;
        int currentLevel = 0;
        while (ci != null && ci != L.base_ci) {
            // ldebug.c:lua_getstack；C 的 hook 回调不作为被挂起 Lua 帧的可见层级。
            // Java 以临时 C CallInfo 执行 hook，需跳过其前一个 CIST_HOOKED Lua 帧对应的 C 帧。
            if (!ci.isLua() && ci.previous != null
                    && (ci.previous.callstatus & CallInfo.CIST_HOOKED) != 0) {
                ci = ci.previous;
                continue;
            }
            if (skipHook && (ci.callstatus & CallInfo.CIST_HOOKED) != 0) {
                ci = ci.previous;
                continue;
            }
            if (currentLevel == level) {
                return ci;
            }
            currentLevel++;
            ci = ci.previous;
        }
        return null;
    }

    // 按层级获取调试帧
    public DebugFrame getFrameAtLevel(LuaThread L, int level, boolean skipHook) {
        CallInfo ci = getCallInfoAtLevel(L, level, skipHook);
        return ci != null ? ciToFrame(ci, L) : null;
    }

    // 获取栈顶索引
    public int getStackTop(LuaThread L) {
        return L != null ? L.top : 0;
    }

    // 设置栈顶索引
    public void setStackTop(LuaThread L, int top) {
        if (L != null) L.top = top;
    }

    // lstate.h: getCcalls
    public int getNCcalls() {
        return running != null ? running.nCcalls : 0;
    }

    // lstate.h: nny
    public int getNny() {
        return running != null ? running.nny : 0;
    }

    // lstate.h: nny
    public void setNny(int v) {
        if (running != null) running.nny = v;
    }

    // lstate.h: getCcalls
    public int getNCcalls(LuaThread L) {
        return L != null ? L.nCcalls : 0;
    }

    // lstate.h: nny
    public int getNny(LuaThread L) {
        return L != null ? L.nny : 0;
    }

    // lstate.h: setCcalls
    public void setNCcalls(LuaThread L, int v) {
        if (L != null) L.nCcalls = v;
    }

    // lstate.h: nny
    public void setNny(LuaThread L, int v) {
        if (L != null) L.nny = v;
    }

    /**
     * 按 require 语义加载一个 Lua 库模块。
     *
     * <p>调用 {@code lib(name, this)}，库函数内部把自身注册进全局表和
     * {@code package.loaded}（等价 C 的 luaL_requiref）。用于那些用
     * {@code call(modname, env)} 形态注册的库（如 Android 层 lib 包的 res/json/file）。
     */
    public void loadLib(LuaFunction lib, String name) {
        LuaCall.callLua(lib, Varargs.of(LuaString.newStr(name), this));
    }

    /**
     * java-only：把 {@code L.hook} 解析成"这次真正要调用的函数"。
     *
     * <p>C 的 {@code lua_sethook} 存的就是 C 函数指针，{@code luaD_hook} 直接调；
     * Java 的 {@code debug.sethook} 存的是 {@code ldblib.c: hookf} 派发器（用弱键表
     * 按线程找用户函数），所以多一层解析。登记者：{@code DebugLib} 装配时。
     */
    public interface HookResolver {
        LuaValue resolve(LuaThread L);
    }

    /** ldebug.c: luaD_hook 取 L-&gt;hook —— 未登记解析器时即 C 的语义。 */
    public LuaValue resolveHook(LuaThread L) {
        if (L == null) return LuaValue.NIL;
        if (hookResolver != null) return hookResolver.resolve(L);
        LuaValue h = L.hook;
        return h == null ? LuaValue.NIL : h;
    }

    /** {@code lua.h: lua_WarnFunction}  -  {@code (ud, msg, tocont)}。 */
    public interface WarnFunction {
        void warn(String msg, boolean tocont);
    }

    // lstate.c: luaE_warning
    public void warning(String msg, boolean tocont) {
        WarnFunction wf = warnf;
        if (wf != null) {
            wf.warn(msg, tocont);
        }
    }

    // lstate.c: luaE_warnerror  -  由错误消息生成警告（GCTM 的 __gc 出错路径）
    // java diff: C 从栈顶取错误对象自行判 ttisstring；Java 调用方已把消息串化后传入，
    //   故此处只做 "error in %s (%s)" 拼接（C 用 5 次 luaE_warning 分段拼，语义同）。
    public void warnerror(String where, String msg) {
        warning("error in " + where + " (" + (msg != null ? msg : "error object is not a string") + ")",
                false);
    }

    // 调试帧，桥接CallInfo
    public static final class DebugFrame {
        public LuaFunction func;
        public LuaValue[] stack;
        public int pc;
        public int top;
        public LuaThread thread;
        public boolean istailcall;
        public int extraargs;
        public Thread javaThread;
        public int base;
        private Extras _extras;

        public DebugFrame(LuaFunction f, LuaValue[] s) {
            func = f;
            stack = s;
            pc = 0;
        }

        public Extras extrasIfPresent() {
            return _extras;
        }

        public Extras extras() {
            if (_extras == null) _extras = new Extras();
            return _extras;
        }

        public static final class Extras {
            public String name;
            public String namewhat;
            public int transferStart;
            public LuaValue[] transferValues;
        }
    }

}
