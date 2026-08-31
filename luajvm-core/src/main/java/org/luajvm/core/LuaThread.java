// ref: lstate.c, lstate.h (lua_State)
// diff: 协程 wait/notify（非 longjmp）；GC 用 JVM；nCcalls 拆 nCcalls+nny；openupval 用 ArrayList；错误用 try/catch+LuaError
package org.luajvm.core;

import org.luajvm.vm.LuaCall;
import org.luajvm.vm.LuaVM;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Iterator;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;

public class LuaThread extends LuaValue {
    public static final int LUA_OK = 0;
    public static final int LUA_YIELD = 1;
    public static final int LUA_ERRRUN = 2;
    public static final int LUA_ERRSYNTAX = 3;
    public static final int LUA_ERRMEM = 4;
    public static final int LUA_ERRERR = 5;
    public static final int BASIC_STACK_SIZE = 40;  // llimits.h: BASIC_STACK_SIZE（对齐 C checkminstack 用）
    public static final int EXTRA_STACK = 5;
    public static final int LUA_MASKCALL = 1;
    public static final int LUA_MASKRET = 2;
    public static final int LUA_MASKLINE = 4;
    public static final int LUA_MASKCOUNT = 8;
    // lstate.c: sizeof(LX) 约 sizeof(lua_State) + extraspace 约 64 位下 320
    private static final long THREAD_MEM_BYTES = 320L;
    // lstate.c: sizeof(StackValue) = sizeof(TValue) = 64 位下 16
    private static final long REF_BYTES = 16L;
    // java-only: 虚拟线程支持检测 - ART/Dalvik 不支持 Thread.ofVirtual()（JEP 444）
    //   抛 NoSuchMethodError；按 java.vm.name 检测，-Dluajvm.vthread=false 可禁用
    private static final boolean VTHREAD_SUPPORTED;
    // java-only: Android/平台线程的原生栈大小。0 表示使用平台默认值；
    //   -Dluajvm.corostack=67108864 可切回 64MB 栈做同 class A/B。
    private static final long PLATFORM_THREAD_STACK =
            Long.getLong("luajvm.corostack", 0L);

    static {
        boolean vthread;
        if ("false".equalsIgnoreCase(System.getProperty("luajvm.vthread"))) {
            vthread = false;
        } else {
            String vmName = System.getProperty("java.vm.name", "");
            // Dalvik（旧 Android）与 ART（新 Android）均不支持虚拟线程
            vthread = !vmName.contains("Dalvik") && !vmName.contains("ART");
        }
        VTHREAD_SUPPORTED = vthread;
    }

    // java-only: ReentrantLock 作协程锁 - Virtual Thread 阻塞时不 pin carrier
    final ReentrantLock coroLock = new ReentrantLock();
    final Condition coroCond = coroLock.newCondition();
    // lstate.h: allowhook
    public byte allowhook;
    // lstate.h: status
    public int status = LUA_OK;
    // lstate.h: top
    public int top;
    // lstate.h: l_G
    public Globals l_G;
    // lstate.h: ci
    public CallInfo ci;
    // lstate.h: stack_last
    public int stack_last;
    // lstate.h: stack
    public LuaValue[] stack;
    // lstate.h: openupval
    // java diff: C 用链表（GCObject *openupval），Java 用 ArrayList<UpVal>
    public ArrayList<UpVal> openupval;
    // lstate.h: tbclist
    public int tbclist;
    // lstate.h: base_ci
    public CallInfo base_ci;
    /**
     * java-only：GC 请求收缩本线程栈的待办标志。
     *
     * <p>C 在 atomic 相位直接调 {@code luaD_shrinkstack}：它的栈是指针，
     * {@code correctstack} 逐个改指针即可。Java 的 {@code LuaVM.execute} 把栈数组
     * 缓存在方法局部量里，仅在 {@code startfunc} 等少数点刷新，三处 {@code checkGC}
     * 之后都不刷新。GC 若直接换掉运行线程的数组，解释器会继续写旧数组 ⇒ 值错乱。
     *
     * <p>故运行线程改为"GC 仅置标志、到刷新点再实收"：GC 置此位，
     * {@code execute} 在 {@code startfunc} 消费（那里本就要重读 {@code L.stack}
     * 并重算 {@code base}）。
     */
    public boolean pendingStackShrink;
    // lstate.h: hook
    public LuaValue hook = LuaValue.NIL;
    // lstate.h: ptrdiff_t errfunc  -  luaL_error 处理器函数（C 存栈偏移，Java 存函数引用）
    // java diff: C 把 errfunc 存为栈偏移（ptrdiff_t）；Java 存直接函数引用
    public LuaValue errfuncRef;
    // java-only: __close 出错时保存的 LuaError.savedStack，供后续 traceback 用
    // C 的 luaG_errormsg 在 luaD_throw 前调用 handler，此时 ci 链完整；Java 的 closeUpvals
    // catch 后恢复 ci，故须保存原始 savedStack 供后续 LuaError.error() 使用
    public ArrayList<Globals.DebugFrame> closeSavedStack;
    // java-only: 调用 message handler 时的 L.ci 与被处理的错误 —— traceback 要把 handler
    // 之上的 live 帧接在错误快照之前才与 C 一致（C 的 luaG_errormsg 在 longjmp 前调 handler，
    // handler 帧直接压在抛错帧之上；Java 的 handler 调用发生在 catch 里，
    // 抛错点起的那段只能来自快照）。见 DebugHook.errorFrames。
    // 只在 handler 执行期间非空（XpcallFn.callMessageHandler 设置并复原）——
    // 不能用 pendingError 代替：它可能是别处（如 __gc）抛出后遗留的过期错误。
    public CallInfo errfuncBaseCi;
    public LuaError errfuncError;
    // lstate.h: nCcalls
    // java diff: C 把 nCcalls（低 16 位）与 nny（高 16 位）编码进单个无符号 short；
    // Java 拆成两个独立 int 字段以求清晰
    public int nCcalls;
    // lstate.h: nny (C 中高位 16 位)
    public int nny;
    // lstate.h: oldpc
    public int oldpc;
    // lstate.h: nci
    public int nci;
    // lstate.h: basehookcount
    public int basehookcount;
    // lstate.h: hookcount
    public int hookcount;
    // lstate.h: hookmask
    public volatile int hookmask;
    // lstate.h: ftransfer
    public int ftransfer;
    // lstate.h: ntransfer
    public int ntransfer;
    // java-only
    public LuaError pendingError = null;
    // java-only
    public ArrayList<Globals.DebugFrame> errorStack = null;
    // java-only
    public boolean isNormal = false;
    // java-only: 记录 resume 链（closeFromCollector 检查 running 链用）
    public LuaThread prevRunningThread = null;
    // java-only
    protected LuaFunction func;
    // java-only
    protected boolean isMain = false;
    // java-only
    private Varargs resumeArgs = LuaValue.NONE;
    // java-only
    private Varargs yieldResult = LuaValue.NONE;
    // java-only: yield 序列计数器 - lua_yieldk 时递增，callLua 前后比较即知是否发生 yield
    //   （C 的 longjmp 天然区分，Java wait/notify 返回后无法区分）
    private int yieldSeq = 0;
    // java-only
    private LuaValue errorValue = null;
    // java-only
    private LuaValue closeErrorValue = null;
    // java-only
    private boolean closing = false;
    // java-only
    private boolean firstResume = true;
    // java-only: close() 置位，令 lua_yieldk 抛 CloseSelf 终止协程线程 - 否则 close() 后
    //   yieldk 醒来返回、协程继续执行改 ci，与调用方的 status 检查竞态
    //   （C 的 lua_closethread 用 longjmp 同步展开；Java 线程模型需显式信号）
    private boolean forceClose = false;
    // java-only: 协程第三模式（Continuation）的载体。null = 走线程模式。
    //   同线程换栈、无握手无线程：往返开销远低于线程模式，挂起态不占线程，
    //   结构上根除"丢弃的挂起协程令执行线程永久 park"一类问题。
    //   仅 ContSupport.SUPPORTED（-Dluajvm.cont=true 且 API 可用）时启用；
    //   Android/ART 无此 API，自动回落线程模式（协程必须兼容 ART/Dalvik 是项目硬约束）。
    private Object cont;

    /** java-only: 本协程是否走 Continuation 模式（决定 resume/yield 的实现路径）。 */
    private boolean useCont() {
        return ContSupport.SUPPORTED && !isMain;
    }

    // lstate.c: lua_newthread
    // java diff: C 单个 lua_newthread(L) 创建附加到 L 的线程；
    // Java 分两个构造器 - 主线程（Globals）与协程（Globals, LuaFunction）
    public LuaThread(Globals env) {
        super(LUA_VTHREAD | BIT_ISCOLLECTABLE);
        // lstate.c: l_newthread -> luaM_new + stack_init -> luaM_reallocvector
        // C: luaC_newobj(1次) + luaD_reallocstack(1次) = 2次 frealloc
        long threadSize = THREAD_MEM_BYTES + BASIC_STACK_SIZE * REF_BYTES;
        if (env == null) throw LuaErrors.errorObject("LuaThread requires Globals");
        LuaGC.checkMemoryN(env, threadSize, 2);
        gcColor = LuaGC.isWhite(env);  // lgc.c: luaC_newobj sets marked = isWhite(g)
        env.gc.allThreads.add(this);
        // java-only: C 存于 global_State.mainthread，Java 缓存在 Globals 上避免 markRoots 扫 allThreads
        env.mainThread = this;  // lstate.h: global_State.mainthread
        this.l_G = env;
        this.isMain = true;
        // lstate.c: init_registry  -  registry[1]=false、[2]=globals、[3]=mainthread
        //   （LUA_RIDX_GLOBALS=2 / LUA_RIDX_MAINTHREAD=3，见 lua.h）
        // java diff: C 的 globals 表是独立 Table，Java 的 Globals 本体即 _G ⇒ 直接放 env。
        //   registry[2] 是 lua_getglobal/LUA_RIDX_GLOBALS 的契约槽，Lua 层
        //   debug.getregistry()[2] 可见（C 为 _G），不设即与 C 分叉。
        env.registry.setEntry(LuaInteger.valueOf(1), LuaBoolean.FALSE);
        env.registry.setEntry(LuaInteger.valueOf(2), env);
        env.registry.setEntry(LuaInteger.valueOf(3), this);
        this.status = LUA_OK;
        initStack();
        LuaGC.commitRealloc(env, 0, threadSize);
    }

    // lstate.c: lua_newthread
    // java diff: C 单个 lua_newthread(L)；Java 为协程线程单设构造器
    public LuaThread(Globals env, LuaFunction func) {
        super(LUA_VTHREAD | BIT_ISCOLLECTABLE);
        // lstate.c: l_newthread -> luaM_new + stack_init -> luaM_reallocvector
        // C: luaC_newobj(1次) + luaD_reallocstack(1次) = 2次 frealloc
        long threadSize = THREAD_MEM_BYTES + BASIC_STACK_SIZE * REF_BYTES;
        if (env == null) throw LuaErrors.errorObject("LuaThread requires Globals");
        LuaGC.checkMemoryN(env, threadSize, 2);
        gcColor = LuaGC.isWhite(env);  // lgc.c: luaC_newobj sets marked = isWhite(g)
        env.gc.allThreads.add(this);
        this.l_G = env;
        this.func = func;
        this.isMain = false;
        this.status = LUA_YIELD;
        initStack();
        LuaGC.commitRealloc(env, 0, threadSize);
    }

    private static boolean useVirtualThreads() {
        return VTHREAD_SUPPORTED;
    }

    // java-only
    public static int managedThreadCount(Globals g) {
        return g.gc.allThreads.size();
    }

    // java-only: 从跟踪列表移除线程（供 closeRemoteState）
    public static void removeThread(LuaThread thread) {
        if (thread != null && thread.l_G != null) thread.l_G.gc.allThreads.remove(thread);
    }

    /**
     * 标记 Java 独有字段中的 Lua 引用。
     *
     * <p>C 的 {@code traversethread} 仅需扫栈 + {@code openupval}：resume/yield 的传参、
     * 错误值、错误处理器等在 C 里都位于线程栈上，栈扫描自然覆盖。Java 把它们存为独立字段，
     * 故必须显式标记，否则仍被持有的对象会被判死。
     *
     * <p>{@code prevRunningThread} 是 thread->thread 引用（resume 链），漏标会使
     * 仅被 resume 链持有的协程被回收。
     */
    void markJavaOnlyRefs(Globals g, LuaGC.GrayList gray) {
        // 挂起态协程的这些字段全为空：lua_resume 的 finally 每次退出都清 resumeArgs/
        //   yieldResult/errorValue/prevRunningThread（见 lua_resume）。故绝大多数线程在此
        //   直接返回，标记开销仅落在真正处于 resume 交接窗口的线程上。
        if (errfuncRef == null && errorValue == null && closeErrorValue == null
                && prevRunningThread == null && pendingError == null && closeSavedStack == null
                && errfuncError == null
                && (resumeArgs == null || resumeArgs == LuaValue.NONE)
                && (yieldResult == null || yieldResult == LuaValue.NONE)) {
            return;
        }
        LuaTable.markValue(g, errfuncRef, gray);
        LuaTable.markValue(g, errorValue, gray);
        LuaTable.markValue(g, closeErrorValue, gray);
        LuaTable.markValue(g, prevRunningThread, gray);
        markVarargs(g, resumeArgs, gray);
        markVarargs(g, yieldResult, gray);
        if (pendingError != null) {
            LuaTable.markValue(g, pendingError.getMessageObject(), gray);
            markFrames(g, pendingError.savedStack, gray);
        }
        // handler 执行期间 traceback 会读 errfuncError 的快照（DebugHook.errorFrames）
        if (errfuncError != null && errfuncError != pendingError) {
            LuaTable.markValue(g, errfuncError.getMessageObject(), gray);
            markFrames(g, errfuncError.savedStack, gray);
        }
        markFrames(g, closeSavedStack, gray);
    }

    private static void markVarargs(Globals g, Varargs v, LuaGC.GrayList gray) {
        if (v == null || v == LuaValue.NONE) return;
        for (int i = 1, n = v.narg(); i <= n; i++) {
            LuaTable.markValue(g, v.arg(i), gray);
        }
    }

    private static void markFrames(Globals g, ArrayList<Globals.DebugFrame> frames,
                                   LuaGC.GrayList gray) {
        if (frames == null) return;
        for (int i = 0, n = frames.size(); i < n; i++) {
            LuaTable.markDebugFrame(g, frames.get(i), gray);
        }
    }

    // lgc.c: sweep  -  按 gcColor 释放不可达线程（对齐 freeobj：不看 status，无条件释放）
    static void sweepByColor(Globals g) {
        byte cw = LuaGC.isWhite(g);
        boolean inc = LuaGC.isIncrementalMode(g);
        // java diff: 反向索引遍历替代 Iterator，消除 ArrayList$Itr 分配
        for (int j = g.gc.allThreads.size() - 1; j >= 0; j--) {
            LuaThread thread = g.gc.allThreads.get(j);
            if (thread.isMainThread()) continue;
            if (LuaGC.isdead(g, thread.gcColor)) {
                thread.closeFromCollector();
                LuaGC.markObjectsSwept(g);  // java-only: 动态阈值跟踪
                g.gc.allThreads.remove(j);
            } else {
                // java diff: fullGC 模式下此处设 gcAge=G_OLD（对齐 C 的 sweep2old lgc.c），
                // 消除 agesAfterFullGC 对 allThreads 的独立 O(n) 遍历
                if (!LuaGC.iswhite(thread.gcColor)) thread.makeWhite(cw);
                thread.gcAge = (byte) (inc ? LuaValue.G_NEW : LuaValue.G_OLD);
            }
        }
    }

    // lgc.c: sweepgen  -  G_NEW->G_SURVIVAL|white；其余前进 age，保持颜色
    static void sweepGen(Globals g, byte cw) {
        for (int j = g.gc.allThreads.size() - 1; j >= 0; j--) {
            LuaThread thread = g.gc.allThreads.get(j);
            if (thread.isMainThread()) continue;
            if (LuaGC.isdead(g, thread.gcColor)) {
                // lgc.c: sweepgen 对 dead 对象走 freeobj，不看 status（同 sweepByColor）
                thread.closeFromCollector();
                LuaGC.markObjectsSwept(g);  // java-only: 动态阈值跟踪
                g.gc.allThreads.remove(j);
            } else if (thread.gcAge == LuaValue.G_NEW) {
                thread.makeWhite(cw);
                thread.gcAge = LuaValue.G_SURVIVAL;
            } else {
                switch (thread.gcAge) {
                    case LuaValue.G_SURVIVAL:
                        thread.gcAge = LuaValue.G_OLD1;
                        break;
                    case LuaValue.G_OLD0:
                        thread.gcAge = LuaValue.G_OLD1;
                        break;
                    case LuaValue.G_OLD1:
                        thread.gcAge = LuaValue.G_OLD;
                        break;
                }
            }
        }
    }


    // java-only: 把全部非白线程重置为当前白色，供 full GC 重新传播
    static void repropagateAll(Globals g, byte cw) {
        for (int _gi = 0, _gn = g.gc.allThreads.size(); _gi < _gn; _gi++) {
            LuaThread t = g.gc.allThreads.get(_gi);
            if (!LuaGC.iswhite(t.gcColor)) t.makeWhite(cw);
        }
    }

    // lgc.c: objsize  -  GC 记账的近似大小
    @Override
    public int gcSize() {
        return 256 + (stack != null ? stack.length * 8 : 0);
    }

    // java-only
    public LuaFunction getFunc() {
        return func;
    }

    // lstate.c: stack_init
    // java diff: C 分配 BASIC_STACK_SIZE + EXTRA_STACK（尾部余量供错误处理越界写），
    //   stack_last 仍取 BASIC_STACK_SIZE。Java 不留尾部余量：所有写都经 checkStack/
    //   growStack 显式扩容，越界会是 AIOOBE 而非静默改写；clearDeadStackSlice 以数组
    //   实长为界，故无 EXTRA_STACK 也安全。
    private void initStack() {
        this.stack = new LuaValue[BASIC_STACK_SIZE];
        Arrays.fill(this.stack, LuaValue.NIL);
        this.stack_last = BASIC_STACK_SIZE;
        this.top = 1;
        this.tbclist = 0;
        this.allowhook = 1;
        this.errfuncRef = null;
        this.errfuncBaseCi = null;
        this.errfuncError = null;
        this.oldpc = 0;
        this.nci = 0;
        this.nCcalls = 0;
        this.nny = 0;
        this.hookmask = 0;
        this.basehookcount = 0;
        this.hookcount = 0;
        this.ftransfer = 0;
        this.ntransfer = 0;
        // base_ci: C 宿主的虚拟 CallInfo，func=0, top=1
        this.base_ci = new CallInfo();
        this.base_ci.func = 0;
        this.base_ci.top = 1;

        this.ci = this.base_ci;
    }

    // ldo.c: next_ci 宏 + lstate.c: luaE_extendCI - 先复用 ci->next 空闲 CI，用尽才分配；
    //   Java 合并到 extendCI/extendCINew，语义逐字对齐
    public CallInfo extendCI() {
        if (ci.next != null) {           // next_ci: L->ci->next ? L->ci->next : ...
            CallInfo next = ci.next;
            ci = next;
            return next;
        }
        return extendCINew();            // next_ci: ... : luaE_extendCI(L)
    }

    // java-only: ltests 分配受限（T.alloccount(0)/allocfailnext）时模拟 C 的
    //   luaM_reallocvector 失败（返回 NULL）- extendCINew 失败返回 null，由调用方
    //   （prepCallInfo/precallLua）抛 "not enough memory"（对齐 C 的 next_ci err=1 ->
    //   memError）。gc.lua 依赖此语义使 finalizer 调用失败被 pcall 捕获。
    public boolean isCINewBlocked() {
        // ltests.c:  -  allocf 的 countlimit 递减语义：alloccount(N) 允许 N 次分配后失败
        //   （memerr.lua testalloc 靠 M 递增突破）；alloccount(0) 场景（gc.lua）
        //   首次即失败。
        return !LuaGC.tryAllocCount(l_G);
    }

    // lstate.c: luaE_extendCI (分配路径，对齐 C 的 luaM_new(L, CallInfo))
    // java diff: C 加 int err 参数（err=0 失败返回 NULL，err=1 报错）并改为前插
    //   （ci->next = L->ci->next）；Java 的 new CallInfo() 不会分配失败（OOM 抛
    //   OutOfMemoryError），故无 err 参数；extendCI 仅在 ci.next==null 时调用，前插等价于追加。
    private CallInfo extendCINew() {
        if (isCINewBlocked()) return null;   // 模拟 luaM_reallocvector 失败（ltests 分配受限）
        CallInfo next = new CallInfo();
        next.previous = ci;
        ci.next = next;
        ci = next;
        nci++;
        return next;
    }

    // lstate.c: luaE_threadsize
    // java diff: C 含 nci*sizeof(CallInfo) 与 EXTRA_STACK；Java 简化省略
    public long threadSize() {
        int stack_last = stack != null ? stack.length : BASIC_STACK_SIZE;
        return THREAD_MEM_BYTES + stack_last * REF_BYTES;
    }

    // lobject.h: ttype
    @Override
    public int type() {
        return TTHREAD;
    }

    // ltm.h: ttypename
    @Override
    public String typeName() {
        return "thread";
    }

    // java-only
    @Override
    public LuaThread checkthread() {
        return this;
    }

    // java-only
    @Override
    public LuaValue getmetatable() {
        // C：ltm.c : luaT_gettmbyobj  -  基础类型元表存于 G(L)->mt[t]（L 是显式的查询状态）。
        // java diff: Java 的 getmetatable() 无状态形参，优先取本线程自己的 l_G
        //   （确定、不依赖"当前活跃状态"）；仅在协程已被 closeFromCollector 置空 l_G 时
        //   才退回 LuaStates.owner()。
        Globals g = l_G != null ? l_G : LuaStates.owner();
        return g == null ? null : g.typeMetatable(LuaValue.TTHREAD);
    }

    // java-only
    @Override
    public LuaValue setmetatable(LuaValue mt) {
        // C：lapi.c : lua_setmetatable  -  写 G(L)->mt[t]（同 getmetatable：优先本线程 l_G）
        Globals g = l_G != null ? l_G : LuaStates.owner();
        if (g != null) g.setTypeMetatable(LuaValue.TTHREAD, mt);
        return this;
    }

    // ldo.c: lua_resume
    // java diff: C 用 longjmp 切协程；Java 用 ReentrantLock+Condition（避免 pin
    //   Virtual Thread carrier）。C 直接返回状态码；
    //   Java 返回 Varargs(false,errmsg)/Varargs(true,result)
    public Varargs lua_resume(Varargs args) {
        if (l_G == null) {
            // 已被收集器释放（closeFromCollector 置 l_G=null）- 对齐 C 的 dead thread
            return varargsOf(LuaValue.FALSE, LuaString.newStr("cannot resume dead coroutine"));
        }
        if (isNormal) {
            // ldo.c: lua_resume  -  统一 non-suspended 消息（C 无 normal 专属文案）
            return varargsOf(LuaValue.FALSE, LuaString.newStr("cannot resume non-suspended coroutine"));
        }
        if (isMain) {
            return varargsOf(LuaValue.FALSE, LuaString.newStr("cannot resume non-suspended coroutine"));
        }
        // ldo.c: LUA_OK 且当前 CallInfo 不在 base_ci 时，线程仍处于运行帧，
        // 统一返回 non-suspended；C 不单独返回 running coroutine。
        if (status == LUA_ERRRUN || status == LUA_ERRMEM || status == LUA_ERRERR) {
            return varargsOf(LuaValue.FALSE, LuaString.newStr("cannot resume dead coroutine"));
        }
        if (status == LUA_OK && func == null) {
            return varargsOf(LuaValue.FALSE, LuaString.newStr("cannot resume dead coroutine"));
        }
        if (status == LUA_OK && ci != base_ci) {
            return varargsOf(LuaValue.FALSE, LuaString.newStr("cannot resume non-suspended coroutine"));
        }
        if (status == LUA_OK || status == LUA_YIELD) {
        } else {
            return varargsOf(LuaValue.FALSE, LuaString.newStr("cannot resume non-suspended coroutine"));
        }
        LuaThread prevRunning = l_G.running;
        int callerNCcalls = prevRunning != null ? prevRunning.nCcalls : 0;
        coroLock.lock();
        try {

            if (callerNCcalls >= Globals.LUAI_MAXCCALLS) {
                return varargsOf(LuaValue.FALSE, LuaString.newStr("C stack overflow"));
            }
            l_G.running = this;
            this.prevRunningThread = prevRunning;
            if (prevRunning != null) {
                prevRunning.status = LUA_YIELD;  // "normal"
                prevRunning.isNormal = true;
            }

            this.resumeArgs = args;
            this.status = LUA_OK;  // running
            this.errorValue = null;
            this.errorStack = null;
            this.forceClose = false;  // java-only: clear stale close signal from previous close()
            this.closeErrorValue = null;

            this.nCcalls = callerNCcalls + 1;
            this.nny = 0;
            if (useCont()) {
                // -- 第三模式：Continuation（同线程换栈，无握手、无线程）--
                // 协程体在调用方线程上跑，故无需 signal/await 握手：run() 返回即表示
                //   协程已 yield 或已结束，状态字段的写入对调用方天然可见
                //   （同线程无跨线程可见性问题，coroLock 在此模式下退化为无竞争锁）。
                if (firstResume) {
                    firstResume = false;
                    cont = ContSupport.create(this::runCoroutine);
                }
                ContSupport.run(cont);
                // run() 返回后：要么协程调了 yieldCurrent()（lua_yieldk 已置 status=
                //   LUA_YIELD 并填 yieldResult），要么 runCoroutine 跑完并设了终态。
                //   两种情形的字段语义与线程模式逐字段等价  -  这是本模式可替换的前提。
            } else if (firstResume) {
                firstResume = false;
                // java diff: C 用 ucontext/longjmp；Java 用 OS 线程 - 虚拟线程
                //   （Java 21+）创建/切换成本最接近 C，默认开启
                //   （-Dluajvm.vthread=false 禁用）；ART/Dalvik 不支持（JEP 444），自动回退平台线程
                Thread t;
                if (useVirtualThreads()) {
                    t = Thread.ofVirtual().name("LuaCoroutine").start(this::runCoroutine);
                } else {
                    t = new Thread(null, this::runCoroutine, "LuaCoroutine", PLATFORM_THREAD_STACK);
                    t.setDaemon(true);
                    t.start();
                }
            } else {
                coroCond.signal();  // java diff: signalAll->signal（严格握手，最多 1 个等待者）
            }

            // Continuation 模式无需等待：run() 已同步返回。
            //   仅线程模式要等协程线程把状态推进到 yield 或终态。
            while (!useCont() && status == LUA_OK && func != null) {
                try {
                    coroCond.await(5, TimeUnit.SECONDS);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                }
            }
            if (errorValue != null) {
                return varargsOf(LuaValue.FALSE, errorValue);
            }
            return varargsOf(LuaValue.TRUE, yieldResult);
        } catch (StackOverflowError soe) {
            // java diff: 协程 resume 中的 StackOverflowError 不可恢复；
            // 转为 LuaError 供 pcall/xpcall 捕获

            return varargsOf(LuaValue.FALSE, LuaString.newStr("stack overflow"));
        } finally {
            this.prevRunningThread = null;
            this.resumeArgs = LuaValue.NONE;
            this.yieldResult = LuaValue.NONE;
            this.errorValue = null;
            this.nCcalls = 0;
            this.nny = 0;
            // java diff: closeFromCollector 在 wait() 期间运行时 l_G 可能为 null。
            // 即使 l_G 为 null，也必须把 g.running 恢复为 prevRunning，
            // 否则后续 lua_resume 会用错误的 prevRunning
            Globals g = l_G;
            if (g != null) g.running = prevRunning;

            if (prevRunning != null) {
                prevRunning.status = LUA_OK;
                prevRunning.isNormal = false;
            }
            coroLock.unlock();
        }
    }

    // java-only
    private void runCoroutine() {
        Varargs a;
        coroLock.lock();
        try {
            a = resumeArgs;
        } finally {
            coroLock.unlock();
        }
        try {
            Varargs r = LuaCall.callLua(func, a);
            coroLock.lock();
            try {
                yieldResult = r;

                func = null;
                status = LUA_OK;  // dead
                errorStack = null;
                closeErrorValue = null;

                coroCond.signal();  // java diff: signalAll->signal（严格握手，最多 1 个等待者）
            } finally {
                coroLock.unlock();
            }
        } catch (CloseSelf close) {
            coroLock.lock();
            try {
                // ldo.c: luaD_throwbaselevel
                if (close.errorValue != null) {

                    errorValue = close.errorValue;
                    closeErrorValue = close.errorValue;
                    errorStack = null;
                    func = null;
                    status = LUA_ERRRUN;  // dead（有错误）
                } else {

                    yieldResult = LuaValue.NONE;
                    errorStack = null;
                    closeErrorValue = null;
                    func = null;
                    status = LUA_OK;  // dead（正常关闭）
                }
                coroCond.signal();  // java diff: signalAll->signal（严格握手，最多 1 个等待者）
            } finally {
                coroLock.unlock();
            }
        } catch (LuaError e) {

            coroLock.lock();
            try {
                errorValue = e.luaError != null ? e.luaError : LuaString.newStr(e.getMessage());
                closeErrorValue = errorValue;
                // java-only: 惰性快照 - 确保从 throwCi 快照后再读取
                e.ensureSnapshot();
                errorStack = e.savedStack;
                func = null;
                status = LUA_ERRRUN;  // dead
                coroCond.signal();  // java diff: signalAll->signal（严格握手，最多 1 个等待者）
            } finally {
                coroLock.unlock();
            }
        } catch (Throwable t) {

            try {
                coroLock.lock();
                try {
                    String msg = t.getMessage() != null ? t.getMessage() : t.toString();

                    if (t instanceof StackOverflowError || msg.contains("stack")) {
                        errorValue = LuaString.newStr("stack overflow");
                    } else {
                        errorValue = LuaString.newStr(msg);
                    }
                    closeErrorValue = errorValue;
                    errorStack = null;
                    func = null;
                    status = LUA_ERRRUN;  // dead
                    coroCond.signal();  // java diff: signalAll->signal（严格握手，最多 1 个等待者）
                } finally {
                    coroLock.unlock();
                }
            } catch (Throwable t2) {

                func = null;
                status = LUA_ERRRUN;
                errorValue = LuaString.newStr("stack overflow");
                closeErrorValue = errorValue;
                coroLock.lock();
                try {
                    coroCond.signal();
                } finally {
                    coroLock.unlock();
                }  // signalAll->signal
            }
        }
    }

    // ldo.c: lua_yieldk
    // java diff: C 用 longjmp 切回调用方；Java 用 ReentrantLock+Condition。
    // C 的 lua_yieldk 永不返回调用方（longjmp）；Java 的 lua_yieldk 阻塞在 await()，
    // resume 时返回 resumeArgs。yieldSeq 用于检测跨 callLua 边界的 yield
    public Varargs lua_yieldk(Varargs args) {
        if (isMain) {
            LuaErrors.runErrorWithInfo("attempt to yield from outside a coroutine");
        }
        if (nny > 0) {
            if (Boolean.getBoolean("luajvm.traceYieldBoundary")) {
                System.err.println("LuaThread.yield boundary nny=" + nny + " nCcalls=" + nCcalls +
                        " ciC=" + (ci != null && (ci.callstatus & CallInfo.CIST_C) != 0));
            }
            LuaErrors.runErrorWithInfo("attempt to yield across a C-call boundary");
        }
        if (useCont()) {
            // -- 第三模式：Continuation --
            // 语义与下面线程分支逐字段等价，区别仅在"如何把控制权交回调用方"：
            //   线程模式 signal + await（跨线程握手）；这里 yieldCurrent() 直接换栈返回，
            //   下次 ContSupport.run(cont) 从这一行之后继续。
            yieldResult = args;
            status = LUA_YIELD;
            yieldSeq++;
            ContSupport.yieldCurrent();     // 挂起；resume 后从此处恢复
            // 恢复后的检查顺序必须与线程分支一致（forceClose 优先于错误状态）
            if (forceClose) {
                forceClose = false;
                throw new CloseSelf(closeErrorValue);
            }
            if (status == LUA_ERRRUN || status == LUA_ERRMEM || status == LUA_ERRERR) {
                throw new CloseSelf(closeErrorValue);
            }
            return resumeArgs;
        }
        coroLock.lock();
        try {
            yieldResult = args;
            status = LUA_YIELD;
            yieldSeq++;
            coroCond.signal();  // java diff: signalAll->signal（严格握手，最多 1 个等待者）
            while (status == LUA_YIELD) {
                coroCond.await();
            }
            // java-only: forceClose 时抛 CloseSelf 展开协程栈终止线程 - 否则协程继续
            //   执行，与调用方的 status 检查竞态（C 用 longjmp 同步展开）
            if (forceClose) {
                forceClose = false;
                throw new CloseSelf(closeErrorValue);
            }
            if (status == LUA_ERRRUN || status == LUA_ERRMEM || status == LUA_ERRERR) {
                throw new CloseSelf(closeErrorValue);
            }
            return resumeArgs;
        } catch (InterruptedException ie) {
            status = LUA_ERRRUN;
            LuaErrors.error("coroutine interrupted");
        } finally {
            coroLock.unlock();
        }
        return LuaValue.NONE;
    }

    // lcorolib.c: auxstatus
    public String auxstatus() {
        if (l_G != null && l_G.running == this) return "running";
        if (isNormal) return "normal";
        switch (status) {
            case LUA_YIELD:
                return "suspended";
            case LUA_OK: {
                if (ci != null && ci != base_ci) return "normal";
                if (func == null) return "dead";
                return "suspended";
            }
            default:
                return "dead";
        }
    }

    // java-only
    public boolean isMainThread() {
        return isMain;
    }

    // java-only
    public int getYieldSeq() {
        return yieldSeq;
    }

    // java-only
    public void resetForResume(LuaFunction function) {
        coroLock.lock();
        try {
            if (isMain) LuaErrors.error("cannot resume non-suspended coroutine");
            this.func = function;
            this.status = LUA_YIELD;
            this.isNormal = false;
            this.resumeArgs = LuaValue.NONE;
            this.yieldResult = LuaValue.NONE;
            this.errorValue = null;
            this.closeErrorValue = null;
            this.closing = false;
            this.errorStack = null;
            this.firstResume = true;
        } finally {
            coroLock.unlock();
        }
    }

    // lstate.c: lua_closethread
    // java diff: C 调 luaE_resetthread + luaD_throwbaselevel；Java 调 LuaVM.resetThread + 抛
    // CloseSelf。C 用 longjmp 展开；Java 用异常（CloseSelf 继承 LuaError）
    public void lua_closethread() {
        if (isMain) {
            LuaErrors.error("cannot close main thread");
        }
        if (closing) return;  // already closing, prevent recursion
        LuaValue closeErr = closeErrorValue;
        closeErrorValue = null;
        status = LUA_OK;
        closing = true;
        try {
            closeErr = LuaVM.resetThread(l_G, this, closeErr);
            // lstate.c: luaE_resetthread
            func = null;
            isNormal = false;
            if (closeErr != null) {
                closeErrorValue = closeErr;
            }
            throw new CloseSelf(closeErr);
        } finally {
            closing = false;
        }
    }

    // java-only
    public Varargs close() {
        if (closing) return LuaValue.TRUE;
        // java-only: 记录是否 suspended - 是则须 signal 令 yieldk 抛 CloseSelf（forceClose）
        //   终止协程线程，否则其继续执行，与状态更新竞态
        boolean wasSuspended = (status == LUA_YIELD);
        LuaValue closeErr = closeErrorValue;
        closeErrorValue = null;
        status = LUA_OK;
        closing = true;
        try {
            LuaThread caller = l_G != null ? l_G.running : null;
            nCcalls = caller != null ? caller.nCcalls : 0;
            closeErr = LuaVM.resetThread(l_G, this, closeErr);
            // lstate.c: luaE_resetthread
            func = null;
            // resetCI 后线程不再保留可恢复调用帧，否则 auxstatus 会把已关闭线程误判为 normal。
            ci = base_ci;
            isNormal = false;
            status = closeErr != null ? LUA_ERRRUN : LUA_OK;
            // java-only: suspended 时置 forceClose - yieldk 醒来抛 CloseSelf 展开栈（对齐 C longjmp）。
            //   不设 closeErrorValue：成功/失败均由 runCoroutine 的 catch 统一收尾（状态 OK/ERRRUN
            //   在 auxstatus 均为 dead，错误值已返回给调用方）
            if (wasSuspended) {
                forceClose = true;
            }
            // java-only: closing 必须保持 true 直到 signal 完成，否则 GC 的 closeFromCollector
            // 可能在 resetThread 返回后、signal 前介入，清除 openupval/stack ⇒ 状态损坏
            coroLock.lock();
            try {
                coroCond.signalAll();
            } finally {
                coroLock.unlock();
            }
        } finally {
            closing = false;
        }
        if (closeErr != null) {
            return LuaValue.varargsOf(LuaValue.FALSE, closeErr);
        }
        // lcorolib.c co_close：无错误仅返回 1 值 true
        return LuaValue.TRUE;
    }

    // lstate.c: luaE_freethread
    // java diff: 内联 luaF_closeupval+freestack+luaM_free（closeUpval 不调 __close，对齐 C）；
    //   ArrayList 反向遍历替代链表；显式检查 isNormal 与 running 链（C 靠 markRoots 天然标记）
    // 两个调用方：GC 的 sweepByColor/sweepGen（状态存活时判死协程），以及 Globals.close()
    //   （宿主丢弃状态时 GC 永不会跑 - park 着的线程把状态钉成可达）。故非 private。
    void closeFromCollector() {
        if (isMain || closing || isNormal) return;
        // 本线程是否在运行线程的 resume 链中
        Globals g = l_G;
        if (g != null) {
            LuaThread running = g.running;
            while (running != null) {
                if (running == this) return;
                running = running.prevRunningThread;
            }
        }


        boolean wasSuspended = (status == LUA_YIELD);
        closing = true;
        // lstate.c: luaE_freethread -> freestack(g, L) -> luaM_freearray(L->stack)
        // java diff: 必须释放线程 + 栈的内存记账
        long threadSize = THREAD_MEM_BYTES + stack_last * REF_BYTES;
        try {
            // luaF_closeupval: 关闭全部打开的上值，不调 __close
            if (openupval != null) {
                for (int j = openupval.size() - 1; j >= 0; j--) {
                    UpVal uv = openupval.get(j);
                    if (uv.upisopen()) {
                        uv.closeUpval();
                    }
                }
                openupval.clear();
            }
        } finally {
            closing = false;
        }
        func = null;
        closeErrorValue = null;
        errorValue = null;
        errorStack = null;
        resumeArgs = LuaValue.NONE;
        yieldResult = LuaValue.NONE;
        errfuncRef = null;
        errfuncBaseCi = null;
        errfuncError = null;
        hook = LuaValue.NIL;
        hookmask = 0;
        hookcount = 0;
        basehookcount = 0;
        allowhook = 0;
        nCcalls = 0;
        nny = 0;
        stack = null;
        stack_last = 0;
        top = 0;
        ci = null;
        base_ci = null;
        // lstate.c: luaE_freethread -> luaM_free_  -  luaM_free_ memory accounting
        if (g != null) LuaGC.free(g, threadSize);
        l_G = null;
        // java-only: yielded 协程的执行线程停在 lua_yieldk 的 `while (status == LUA_YIELD)
        //   coroCond.await()` 里。C 的 luaE_freethread 仅释放栈内存（协程无独立执行线程，
        //   ucontext 随内存一起消失）；Java 必须显式终止该线程，否则：
        //     1. park 着的线程就是 GC 根  -  runCoroutine 方法引用强持 this，本 LuaThread
        //        连同其捕获的对象图永不被 JVM 回收（Lua 侧已判死也无用）；
        //     2. Android（VTHREAD 不可用）泄漏真实 OS 线程及其平台默认原生栈。
        //   两个写都必要，且都必须在锁内：
        //     - status 退出 LUA_YIELD：唯一能跳出 await 循环的条件。仅设 forceClose 会被
        //       循环条件挡住、醒来重新 await。
        //     - forceClose 令其抛 CloseSelf 干净展开（对齐 C longjmp）。仅设 status 时协程体
        //       会真的恢复执行，直到 luaD_growstack 的 "attempt to realloc stack of freed
        //       thread" 兜住  -  线程虽也退出，但走的是 catch (Throwable) 错误路径。
        //   状态取 LUA_OK（GC 回收非错误路径）；展开仅碰 nCcalls/nny 两个 int，不读上面已置
        //   null 的 stack/ci/l_G，故先清后唤醒安全（signal 建立 happens-before）。
        coroLock.lock();
        try {
            if (wasSuspended) {
                status = LUA_OK;
                forceClose = true;
            }
            coroCond.signalAll();
        } finally {
            coroLock.unlock();
        }
    }

    // java-only
    public static final class CloseSelf extends LuaError {
        public final LuaValue errorValue;

        public CloseSelf(LuaValue errorValue) {
            super(errorValue != null ? errorValue : LuaString.newStr("close self"));
            this.errorValue = errorValue;
        }
    }
}
