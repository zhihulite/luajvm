// ref: ldo.c
// diff: instanceof替代ttypetag switch | Varargs桥接precallC | nCcalls/nny拆分
package org.luajvm.vm;

import org.luajvm.core.CallInfo;
import org.luajvm.core.Globals;
import org.luajvm.core.LuaClosure;
import org.luajvm.core.LuaError;
import org.luajvm.core.LuaErrors;
import org.luajvm.core.LuaFunction;
import org.luajvm.core.LuaString;
import org.luajvm.core.LuaThread;
import org.luajvm.core.LuaTable;
import org.luajvm.core.LuaValue;
import org.luajvm.core.Metamethod;
import org.luajvm.core.Prototype;
import org.luajvm.core.Varargs;
import org.luajvm.vm.CFnCallStats;
import org.luajvm.vm.LuaVM;


public final class LuaCall {
    // ldo.c: ccall  -  java diff: nCcalls/nny 拆两字段（C 单字段高低 16 位）。
    //   java-only: 用 try/catch(LuaError) 收尾 —— 无异常路径无 finally 开销，且仅捕获
    //   LuaError 不影响致命异常传播。开关 -Dluajvm.ccall_tryfinally=true 回退 try/finally
    private static final boolean CCALL_TRY_FINALLY =
            Boolean.getBoolean("luajvm.ccall_tryfinally");

    private LuaCall() {
    }

    // C：ldo.c : luaD_call
    // Java：从被调用对象或参数显式取得所属 Lua 状态。
    private static Globals ownerOf(LuaValue value) {
        if (value instanceof LuaFunction function) return function.ownerGlobals;
        if (value instanceof LuaTable table) return table.ownerGlobals;
        if (value instanceof LuaThread thread) return thread.l_G;
        return null;
    }

    private static Globals ownerGlobals(LuaValue target, Varargs args) {
        Globals targetGlobals = ownerOf(target);
        if (targetGlobals != null) return targetGlobals;
        if (args != null) {
            for (int i = 1; i <= args.narg(); i++) {
                LuaValue value = args.arg(i);
                if (value instanceof Globals globals) return globals;
                Globals valueGlobals = ownerOf(value);
                if (valueGlobals != null) return valueGlobals;
            }
        }
        return null;
    }

    private static Globals resolveGlobals(LuaValue target, Varargs args) {
        Globals g = ownerGlobals(target, args);
        // java diff: 库函数创建的闭包（如 table.sort 比较器 SortFn）所属表不携带
        // ownerGlobals，可能为 null；回退 runningGlobalsForGC()（仅取活动/运行状态，
        // 进程内仅一个时取单例，避免跨线程误取）建立 CI 链，否则 getfuncname 看不到
        // 外层 sort C 帧，报错名从 "table.sort" 退化为 "sort"（errors.lua:150 回归）。
        return g != null ? g : LuaTable.runningGlobalsForGC();
    }

    // ldo.c: tryfuncTM
    private static int tryfuncTM(LuaThread L, int func, int status) {
        LuaValue funcVal = L.stack[func];
        LuaValue tm = Metamethod.CALL.lookup(funcVal);
        if (tm == null || tm.isnil()) {
            LuaErrors.callError(funcVal);
        }
        for (int p = L.top; p > func; p--) {
            L.stack[p] = L.stack[p - 1];
        }
        L.top++;
        L.stack[func] = tm;
        if ((status & CallInfo.MAX_CCMT) == CallInfo.MAX_CCMT) {
            LuaErrors.runErrorWithInfo("'__call' chain too long");
        }
        return status + (1 << CallInfo.CIST_CCMT);
    }

    // ldo.c: genmoveresults
    private static void genmoveresults(LuaThread L, int res, int nres, int wanted) {
        int firstresult = L.top - nres;
        int i;
        if (nres > wanted) nres = wanted;
        for (i = 0; i < nres; i++) {
            L.stack[res + i] = L.stack[firstresult + i];
        }
        for (; i < wanted; i++) {
            L.stack[res + i] = LuaValue.NIL;
        }
        L.top = res + wanted;
    }

    // ldo.c: moveresults
    private static void moveresults(LuaThread L, int res, int nres, int fwanted) {

        if (fwanted == 1 && (fwanted & CallInfo.CIST_TBC) == 0) {
            L.top = res;
            return;
        }
        if (fwanted == 1 + 1 && (fwanted & CallInfo.CIST_TBC) == 0) {
            if (nres == 0) {
                L.stack[res] = LuaValue.NIL;
            } else {
                int src = L.top - nres;

                L.stack[res] = L.stack[src];
            }
            L.top = res + 1;
            return;
        }
        if (fwanted == (LuaValue.LUA_MULTRET + 1) && (fwanted & CallInfo.CIST_TBC) == 0) {
            genmoveresults(L, res, nres, nres);
            return;
        }
        {
            int wanted = CallInfo.getNResults(fwanted);
            if ((fwanted & CallInfo.CIST_TBC) != 0) {
                L.ci.nres = nres;
                L.ci.callstatus |= CallInfo.CIST_CLSRET;
                LuaValue closeErr = LuaVM.closeUpvalsAtTop(L, res);
                if (closeErr != null) LuaErrors.error(closeErr);
                L.ci.callstatus &= ~CallInfo.CIST_CLSRET;
                if (L.hookmask != 0) {
                    LuaVM.rethook(L, L.ci, nres);
                }
                if (wanted == LuaValue.LUA_MULTRET) {
                    wanted = nres;
                }
            }
            genmoveresults(L, res, nres, wanted);
        }
    }

    // ldo.c: luaD_poscall
    public static void poscall(LuaThread L, CallInfo ci, int nres) {
        int fwanted = ci.callstatus & (CallInfo.CIST_TBC | CallInfo.CIST_NRESULTS);
        if (L.hookmask != 0 && (fwanted & CallInfo.CIST_TBC) == 0) {
            LuaVM.rethook(L, ci, nres);
        }
        moveresults(L, ci.func, nres, fwanted);
        L.ci = ci.previous;
    }

    // ldo.c: prepCallInfo
    private static CallInfo prepCallInfo(LuaThread L, int func, int status, int top) {
        CallInfo ci = L.extendCI();
        if (ci == null) {
            // ldo.c: next_ci luaB_error=1 -> luaE_extendCI 失败 -> luaM_error（"not enough memory"）。
            //   ltests 分配受限（T.alloccount(0)/allocfailnext）时 finalizer 等调用由此失败，
            //   由 callFinalizers 的 pcall 保护捕获（gc.lua:729）。
            LuaErrors.error("not enough memory");
        }
        ci.func = func;
        ci.callstatus = status;
        ci.top = top;
        return ci;
    }

    // C 语义：C 函数内的任何错误统一经 luaG_errormsg 变为 Lua 错误（longjmp）；
    //   Java 侧等价包装，让 pcall 捕获与 traceback 在全部路径一致。LuaError 原样放行；
    //   Error（SO/OOM）不经此（catch RuntimeException 天然排除），原样上抛。
    private static RuntimeException javaBridgeError(RuntimeException e) {
        if (e instanceof LuaError) return e;
        // 类名前缀保留异常类型信息（个数不匹配/类型不匹配等错误来源不同）
        String msg = e.getMessage();
        return new LuaError(e.getClass().getName() + ": " + (msg != null ? msg : e), e);
    }

    // ldo.c: precallC (C function branch)
    // java diff: C 直接调 (*f)(L)；Java 先试 callOnStack，失败回退 Varargs 桥接
    private static int precallC(LuaThread L, int func, int status, LuaFunction f) {
        // ldo.c: precallC 不动 nCcalls —— 经 OP_CALL 调 C 函数、返回即释放帧，不构成
        //   "嵌套 C 调用"；只有 C 函数体再回调 Lua（luaD_call/ccall）才算一层。
        //   再入路径均经 ccall 计数，PcallFn 将 StackOverflowError 兜底转 'C stack overflow'。
        LuaVM.checkStack(L, 20);
        CallInfo ci = prepCallInfo(L, func, status | CallInfo.CIST_C, L.top + 20);
        int narg = L.top - func - 1;
        if ((L.hookmask & LuaThread.LUA_MASKCALL) != 0) {
            LuaVM.callHook(L, LuaVM.LUA_HOOKCALL, -1, 1, narg);
        }
        int n;
        int stackResult;
        try {
            stackResult = f.callOnStack(L, func, narg);
        } catch (RuntimeException e) {
            throw javaBridgeError(e);
        }
        // java-only: -Dluajvm.countcfn=true 时统计栈直调 vs Varargs 回退（默认关闭，热路径零开销）
        if (CFnCallStats.ENABLED) CFnCallStats.record(f, stackResult >= 0, narg);
        if (stackResult >= 0) {
            // callOnStack 成功: 结果在 L.stack[L.top]，L.top 已更新
            n = stackResult;
        } else {
            // 回退 Varargs 路径
            Varargs args;
            if (narg <= 0) {
                args = LuaValue.NONE;
            } else if (narg == 1) {
                args = L.stack[func + 1];
            } else if (narg == 2) {
                args = Varargs.of(L.stack[func + 1], L.stack[func + 2]);
            } else if (narg == 3) {
                args = Varargs.of(L.stack[func + 1], L.stack[func + 2], L.stack[func + 3]);
            } else {
                LuaValue[] a = new LuaValue[narg];
                for (int i = 0; i < narg; i++) {
                    a[i] = L.stack[func + 1 + i];
                }
                args = Varargs.of(a);
            }
            Varargs result;
            try {
                result = f.call(args);
            } catch (RuntimeException e) {
                throw javaBridgeError(e);
            }
            n = result.narg();
            // java-only 防御：C 的 precallC 对 C 函数返回数无独立上限（只受 LUAI_MAXSTACK
            //   的栈检查约束），此消息 C 不存在。正常代码不可达（需返回 >1e6 个值）。
            if (n > 1000000) {
                LuaErrors.error("too many results to unpack");
            }
            LuaVM.checkStack(L, n);
            if (n == 1) {
                L.stack[L.top] = result.arg1();
            } else if (n == 2) {
                L.stack[L.top] = result.arg1();
                L.stack[L.top + 1] = result.arg(2);
            } else if (n > 2) {
                result.copyTo(L.stack, L.top, n);
            }
            L.top += n;
        }
        poscall(L, ci, n);
        return n;
    }

    // ldo.c: luaD_pretailcall
    public static int preTailcall(LuaThread L, CallInfo ci, int func, int narg1, int delta) {
        int status = LuaValue.LUA_MULTRET + 1;
        while (true) {
            LuaValue funcVal = L.stack[func];
            if (funcVal instanceof LuaClosure lc) {
                Prototype p = lc.p;
                int fsize = p.maxstacksize;
                int nfixparams = p.numparams;
                // ldo.c: checkstackp(L, fsize - delta, func)  -  按 C 的检查量（可为负=不检查）
                LuaVM.checkStack(L, fsize - delta);
                ci.func -= delta;
                for (int i = 0; i < narg1; i++) {
                    L.stack[ci.func + i] = L.stack[func + i];
                }
                func = ci.func;
                for (; narg1 <= nfixparams; narg1++) {
                    L.stack[func + narg1] = LuaValue.NIL;
                }
                ci.top = func + 1 + fsize;
                CallInfo.savepc(ci, 0);
                ci.callstatus |= CallInfo.CIST_TAIL;
                L.top = func + narg1;
                return -1;
            } else if (funcVal instanceof LuaFunction fn) {
                return precallC(L, func, status, fn);
            } else {
                LuaVM.checkStack(L, 1);
                status = tryfuncTM(L, func, status);
                narg1++;
                continue;
            }
        }
    }

    // ldo.c: luaD_precall  -  switch(ttypetag) dispatch
    public static CallInfo precall(LuaThread L, int func, int nresults) {
        int status = nresults + 1;
        while (true) {
            LuaValue funcVal = L.stack[func];
            int tt = funcVal.tt_;
            // ldo.c: switch(ttypetag(s2v(func)))  -  标签分派，非 instanceof
            if (tt == (LuaValue.LUA_VLCL | LuaValue.BIT_ISCOLLECTABLE)) {
                LuaClosure lc = (LuaClosure) funcVal;
                Prototype p = lc.p;
                int narg = L.top - func - 1;
                int nfixparams = p.numparams;
                int fsize = p.maxstacksize;
                // ldo.c: checkstackp(L, fsize, func)  -  C 按帧大小检查（narg>=0 ⇒ 不小于精确量）
                LuaVM.checkStack(L, fsize);
                CallInfo ci = prepCallInfo(L, func, status, func + 1 + fsize);
                CallInfo.savepc(ci, 0);
                for (; narg < nfixparams; narg++) {
                    L.stack[L.top++] = LuaValue.NIL;
                }

                return ci;
            } else if (tt == LuaValue.LUA_VLCF || tt == (LuaValue.LUA_VCCL | LuaValue.BIT_ISCOLLECTABLE)) {
                // ldo.c: LUA_VCCL/LUA_VLCF  -  C function
                LuaFunction fn = (LuaFunction) funcVal;
                precallC(L, func, status, fn);
                return null;
            } else {
                // ldo.c: default  -  不是函数，试 __call 元方法
                LuaVM.checkStack(L, 1);
                status = tryfuncTM(L, func, status);
                continue;
            }
        }
    }

    private static void ccall(LuaThread L, int func, int nResults, int inc) {
        // ldo.c:  -  L->nCcalls += inc
        int savedNCcalls = L.nCcalls;
        int savedNny = L.nny;
        L.nCcalls += (inc & 0xffff);
        L.nny += (inc >> 16);
        if (L.nCcalls >= Globals.LUAI_MAXCCALLS) {
            checkCStack(L);
        }
        if (CCALL_TRY_FINALLY) {
            // 基线路径：try/finally
            try {
                ccallBody(L, func, nResults);
            } finally {
                L.nCcalls = savedNCcalls;
                L.nny = savedNny;
            }
        } else {
            // 快路径：try/catch(LuaError)  -  无异常时零开销，异常时恢复并重抛
            try {
                ccallBody(L, func, nResults);
            } catch (LuaError e) {
                L.nCcalls = savedNCcalls;
                L.nny = savedNny;
                throw e;
            }
            L.nCcalls = savedNCcalls;
            L.nny = savedNny;
        }
    }

    // java-only: ccall 主体独立方法，供两条路径复用（利于 C2 内联）
    private static void ccallBody(LuaThread L, int func, int nResults) {
        CallInfo ci = precall(L, func, nResults);
        if (ci != null) {
            ci.callstatus |= CallInfo.CIST_FRESH;
            LuaVM.execute(L, ci);
        }
    }

    // lstate.c: luaE_checkcstack
    private static void checkCStack(LuaThread L) {
        if (L.nCcalls == Globals.LUAI_MAXCCALLS)
            LuaErrors.runErrorWithInfo("C stack overflow");
        else if (L.nCcalls >= Globals.LUAI_MAXCCALLS / 10 * 11)
            LuaErrors.runErrorWithInfo("error in error handling");
    }

    // lstate.c: luaE_checkcstack  -  供 LuaVM.OP_CALL 公开
    public static void checkCStackPublic(LuaThread L) {
        checkCStack(L);
    }

    // ldo.c: luaD_checkminstack  -  检查运行简单函数（如 finalizer）的最小栈空间
    // java diff: C 用 luaE_extendCI(L,0) 预分配 2 个 CI（err=0 失败返回 NULL）；
    //   Java 的 CallInfo 是 new 的，不会分配失败（OOM 抛 OutOfMemoryError），故 CI 检查总通过，
    //   仅确保 ci.next/ci.next.next 存在（对齐 C 预分配语义，不切换 ci）。
    public static boolean checkMinStack(LuaThread L) {
        if (L.nCcalls >= Globals.LUAI_MAXCCALLS - 2)
            return false;  // C 栈槽不足
        // CI 可用性：确保 ci.next 和 ci.next.next 存在（对齐 C 的 extendCI(L,0) 预分配）
        if (L.ci.next == null) {
            CallInfo next = new CallInfo();
            next.previous = L.ci;
            L.ci.next = next;
            L.nci++;
        }
        if (L.ci.next.next == null) {
            CallInfo next2 = new CallInfo();
            next2.previous = L.ci.next;
            L.ci.next.next = next2;
            L.nci++;
        }
        // ldo.c:  -  C 检查栈总容量 stack_last-stack < BASIC_STACK_SIZE 才 growstack（不依赖 grow 结果）。
        // java diff: Java 的 L.stack_last 即栈总容量；不能用剩余空间（stack_last-top）判断——
        //   finalizer 调用点可能栈满（cstack.lua:105），按剩余判断会跳过 finalizer
        //   致对象积压、GC 误回收（"non-closable" 回归）。
        if (L.stack_last < LuaThread.BASIC_STACK_SIZE) {
            LuaVM.growStack(L, LuaThread.BASIC_STACK_SIZE, 0);
        }
        return true;
    }

    // ldo.c: luaD_call
    public static void callLua(LuaThread L, int func, int nResults) {
        ccall(L, func, nResults, 1);
    }

    // luaD_callnoyield  -  nyci = 0x10001
    public static void callNoYield(LuaThread L, int func, int nResults) {
        ccall(L, func, nResults, 0x10001);
    }

    // -- java compat: Varargs入口 (C无对应) ----------------------

    // java-only: Varargs 入口共用收尾 - 结果打包成 Varargs 后退回 top 并清槽（C 留栈由调用方 pop）。
    //   不退 top 则宿主反复调用下栈永不收缩，滞留槽还会钉死返回值。门禁 HostCallStackBoundTest。
    private static Varargs packResultsAndPop(LuaThread L, int func) {
        int nres = L.top - func;
        if (nres <= 0) return LuaValue.NONE;
        Varargs out;
        if (nres == 1) {
            out = L.stack[func];
        } else if (nres == 2) {
            out = Varargs.of(L.stack[func], L.stack[func + 1]);
        } else if (nres == 3) {
            out = Varargs.of(L.stack[func], L.stack[func + 1], L.stack[func + 2]);
        } else {
            LuaValue[] results = new LuaValue[nres];
            System.arraycopy(L.stack, func + 0, results, 0, nres);
            out = Varargs.of(results);
        }
        for (int i = 0; i < nres; i++) L.stack[func + i] = null;
        L.top = func;
        return out;
    }

    // java-only: 单结果快路径共用收尾（call 2/3 参），理由同 packResultsAndPop。
    private static LuaValue firstResultAndPop(LuaThread L, int func) {
        int nres = L.top - func;
        if (nres <= 0) return LuaValue.NIL;
        LuaValue out = L.stack[func];
        for (int i = 0; i < nres; i++) L.stack[func + i] = null;
        L.top = func;
        return out;
    }

    // java-only: 宿主边界（最外层）入口的错误恢复，对齐 ldo.c luaD_pcall 恢复段（ci/allowhook
    //   复位 -> luaD_closeprotected 关 TBC -> 退栈 -> shrinkstack）。不恢复则 top/ci/CallInfo
    //   链随失败调用无界增长、错误链对象被滞留槽钉死（门禁 HostCallStackBoundTest 错误路径段）。
    private static LuaError recoverUnprotected(LuaThread L, int func, CallInfo oldCi,
            byte oldAllowhook, LuaError e) {
        // java diff: CloseSelf 必须穿透到 runCoroutine，状态由它统一收尾（C 用 longjmp 跳过中间 setjmp）
        if (e instanceof LuaThread.CloseSelf) return e;
        // 非最外层（引擎内经本入口调 Lua，如 callclosemethod/元方法/库回调）必须原样穿透，
        //   交由外层 pcall/resume 保护点恢复（对齐 C 的 longjmp 越过中间 luaD_call）；
        //   在此恢复会毁掉 xpcall traceback 与 __close 重试（locals.lua "in metamethod 'close'" 回归）。
        if (oldCi != L.base_ci) return e;
        // ci 复位后 CallInfo 会被后续调用重用，traceback 须先定格
        e.ensureSnapshot();
        L.pendingError = null;
        L.ci = oldCi;
        L.allowhook = oldAllowhook;
        LuaValue errObj = e.luaError != null ? e.luaError
                : (e.getMessage() != null ? LuaString.newStr(e.getMessage()) : LuaValue.NIL);
        LuaValue closed = LuaVM.closeUpvals(L, func, errObj, L.nny == 0 && !L.isMainThread());
        // java-only: 失败调用弄脏的 [func, 栈顶) 全是死槽，须显式清空——clearDeadStackSlice
        //   的下界取 max(top, ci.top)，基帧窗口 [top, base_ci.top) 是它永不清理的盲区。
        //   [顺序]必须先清再 shrinkStack：ensureSnapshot 的 DebugFrame 持有旧栈数组引用，先 shrink 再清只清新数组。
        for (int i = func, hi = L.stack.length; i < hi; i++) L.stack[i] = null;
        L.top = func;
        LuaVM.shrinkStack(L);
        // ldo.c: luaD_closeprotected  -  __close 出错则以新错误对象上抛
        if (closed != errObj) LuaErrors.error(closed);
        return e;
    }

    // C：ldo.c : luaD_call
    // Java：Java 回调显式指定所属 Globals；不依赖当前线程或进程级静态状态。
    // 同一 Globals 的执行串行化由上层执行所有者负责，本入口只负责选择正确的 Lua 栈。
    public static Varargs callLua(Globals globals, LuaFunction target, Varargs args) {
        if (target == null) return LuaValue.NONE;
        target.bindGlobals(globals);
        LuaThread L = globals != null ? globals.running : null;
        if (L == null) return target.call(args);
        int func = L.top;
        CallInfo oldCi = L.ci;
        byte oldAllowhook = L.allowhook;
        int nargs = args != null ? args.narg() : 0;
        int needed = func + 1 + nargs - L.top;
        if (needed > 0) LuaVM.checkStack(L, needed);
        L.stack[func] = target;
        if (nargs > 0) args.copyTo(L.stack, func + 1, nargs);
        L.top = func + 1 + nargs;
        try {
            callLua(L, func, LuaValue.LUA_MULTRET);
        } catch (LuaError e) {
            throw recoverUnprotected(L, func, oldCi, oldAllowhook, e);
        }
        return packResultsAndPop(L, func);
    }

    // java-only: Varargs->栈->luaD_call->栈->Varargs
    public static Varargs callLua(LuaFunction target, Varargs args) {
        Globals g = ownerGlobals(target, args);
        LuaThread L = g != null ? g.running : null;
        if (L == null) {
            return target.call(args);
        }
        int func = L.top;
        CallInfo oldCi = L.ci;
        byte oldAllowhook = L.allowhook;
        int nargs = args != null ? args.narg() : 0;
        int needed = func + 1 + nargs - L.top;
        if (needed > 0) LuaVM.checkStack(L, needed);
        L.stack[func] = target;
        if (nargs > 0) args.copyTo(L.stack, func + 1, nargs);
        L.top = func + 1 + nargs;
        try {
            callLua(L, func, LuaValue.LUA_MULTRET);
        } catch (LuaError e) {
            throw recoverUnprotected(L, func, oldCi, oldAllowhook, e);
        }
        // java diff: C 在栈上返回结果；Java 打包成 Varargs 并退栈
        return packResultsAndPop(L, func);
    }

    public static Varargs callLua(LuaValue target, Varargs args) {
        if (target instanceof LuaFunction fn) {
            return callLua(fn, args);
        }
        Globals g = resolveGlobals(target, args);
        LuaThread L = g != null ? g.running : null;
        if (L == null) {
            LuaValue h = Metamethod.CALL.lookup(target);
            if (h == null || h.isnil()) LuaErrors.callError(target);
            if (h instanceof LuaFunction fn) return fn.call(Varargs.of(target, args));
            LuaErrors.callError(target);
            return LuaValue.NONE;
        }
        int func = L.top;
        CallInfo oldCi = L.ci;
        byte oldAllowhook = L.allowhook;
        int nargs = args != null ? args.narg() : 0;
        int needed = func + 1 + nargs - L.top;
        if (needed > 0) LuaVM.checkStack(L, needed);
        L.stack[func] = target;
        if (nargs > 0) args.copyTo(L.stack, func + 1, nargs);
        L.top = func + 1 + nargs;
        try {
            callLua(L, func, LuaValue.LUA_MULTRET);
        } catch (LuaError e) {
            throw recoverUnprotected(L, func, oldCi, oldAllowhook, e);
        }
        return packResultsAndPop(L, func);
    }

    public static Varargs callNoYield(LuaValue target, Varargs args) {
        Globals g = resolveGlobals(target, args);
        LuaThread L = g != null ? g.running : null;
        if (L == null) {
            if (target instanceof LuaFunction fn) return fn.call(args);
            LuaErrors.callError(target);
            return LuaValue.NONE;
        }
        int func = L.top;
        CallInfo oldCi = L.ci;
        byte oldAllowhook = L.allowhook;
        int nargs = args != null ? args.narg() : 0;
        int needed = func + 1 + nargs - L.top;
        if (needed > 0) LuaVM.checkStack(L, needed);
        L.stack[func] = target;
        if (nargs > 0) args.copyTo(L.stack, func + 1, nargs);
        L.top = func + 1 + nargs;
        try {
            callNoYield(L, func, LuaValue.LUA_MULTRET);
        } catch (LuaError e) {
            throw recoverUnprotected(L, func, oldCi, oldAllowhook, e);
        }
        return packResultsAndPop(L, func);
    }

    // java diff: C 的 luaD_callnoyield 直接从栈读参数零分配；此重载同样直接放栈，
    // 不经 Varargs->copyTo。调用方：PackageLib searcher(name) 等高频 C->Lua 1 参数调用。
    public static Varargs callNoYield(LuaValue target, LuaValue a) {
        Globals g = resolveGlobals(target, a);
        LuaThread L = g != null ? g.running : null;
        if (L == null) {
            return callNoYield(target, (Varargs) a);
        }
        int func = L.top;
        CallInfo oldCi = L.ci;
        byte oldAllowhook = L.allowhook;
        LuaVM.checkStack(L, 2);  // func + 1 arg
        L.stack[func] = target;
        L.stack[func + 1] = a;
        L.top = func + 2;
        try {
            callNoYield(L, func, LuaValue.LUA_MULTRET);
        } catch (LuaError e) {
            throw recoverUnprotected(L, func, oldCi, oldAllowhook, e);
        }
        return packResultsAndPop(L, func);
    }

    public static Varargs callNoYield(LuaValue target, LuaValue a, LuaValue b) {
        // java diff: C 的 luaD_callnoyield 直接从栈读参数零分配；此重载直接放栈，
        // 不经 Varargs.of(a,b) 打包（对齐 C 的零分配栈操作）。
        // 调用方：PackageLib、StringLib 等高频 C->Lua 2 参数调用。
        Globals g = resolveGlobals(target, Varargs.of(a, b));
        LuaThread L = g != null ? g.running : null;
        if (L == null) {
            return callNoYield(target, Varargs.of(a, b));
        }
        int func = L.top;
        CallInfo oldCi = L.ci;
        byte oldAllowhook = L.allowhook;
        LuaVM.checkStack(L, 3);  // func + 2 args
        L.stack[func] = target;
        L.stack[func + 1] = a;
        L.stack[func + 2] = b;
        L.top = func + 3;
        try {
            callNoYield(L, func, LuaValue.LUA_MULTRET);
        } catch (LuaError e) {
            throw recoverUnprotected(L, func, oldCi, oldAllowhook, e);
        }
        return packResultsAndPop(L, func);
    }

    public static Varargs callLua(LuaValue target) {
        return callLua(target, LuaValue.NONE);
    }

    public static Varargs callLua(LuaFunction target) {
        return callLua(target, LuaValue.NONE);
    }

    public static LuaValue call(LuaValue target) {
        return callLua(target, LuaValue.NONE).arg1();
    }

    /**
     * java-only: Java 绑定对象的直调入口（bind 层内部与宿主 Java 代码用）。
     *
     * <p><b>适用性</b>：{@code target} 在这些调用点必然是 Java 实现的
     * {@link LuaFunction}（JavaMethod / JavaConstructor / 它们的 Overload /
     * JavaOOMethod），不可能是 {@link LuaClosure}；对它走完整 Lua 调用协议
     * （prepCallInfo、栈拷贝、callOnStack 试探、poscall、结果打包退栈）全是净开销。
     *
     * <p><b>保真</b>：
     * <ul>
     *   <li>有调试 hook 时回退完整路径，保留 call/return 的可观察帧。</li>
     *   <li>仍按 C 函数计一层 {@code nCcalls}，宿主递归深度继续受 {@code LUAI_MAXCCALLS}
     *       保护。</li>
     *   <li>不触碰 {@code L.top} / {@code L.ci} / {@code savedpc}：目标内部若回调 Lua
     *       （onXxx 监听器、代理）会自己走 {@code callLua} 建帧并在出错时恢复，
     *       故本入口无需 {@code recoverUnprotected}。</li>
     * </ul>
     */
    public static Varargs callJavaBinding(LuaFunction target, Varargs args) {
        if (target == null) return LuaValue.NONE;
        Globals g = ownerGlobals(target, args);
        LuaThread L = g != null ? g.running : null;
        // 不在 Lua 执行区：完整路径的 L == null 分支本来也是 target.call(args)
        if (L == null) return target.call(args);
        if (L.hookmask != 0) return callLua(target, args);
        L.nCcalls++;
        try {
            if (L.nCcalls >= Globals.LUAI_MAXCCALLS) checkCStack(L);
            return target.call(args);
        } finally {
            L.nCcalls--;
        }
    }

    public static Varargs invoke(LuaValue target, Varargs args) {
        if (target instanceof LuaFunction function) {
            Globals globals = ownerGlobals(function, args);
            if (globals != null && !globals.isExecutingOnCurrentThread()) {
                return globals.invoke(function, args);
            }
        }
        return callLua(target, args);
    }

    public static Varargs invokeNoYield(LuaValue target, Varargs args) {
        return callNoYield(target, args);
    }

    public static Varargs invokeNoYield(LuaValue target, LuaValue a) {
        return callNoYield(target, a);
    }

    public static Varargs invoke(LuaFunction target, Varargs args) {
        Globals globals = ownerGlobals(target, args);
        if (globals != null && !globals.isExecutingOnCurrentThread()) {
            return globals.invoke(target, args);
        }
        return callLua(target, args);
    }

    public static LuaValue call(LuaValue target, LuaValue a) {
        return callLua(target, a).arg1();
    }

    // java diff: C 的 luaD_call 直接从栈读参数零分配；此重载直接放栈不经 VarargsPair 打包
    // （同 callNoYield 2-arg 重载）。
    public static LuaValue call(LuaValue target, LuaValue a, LuaValue b) {
        Globals g = resolveGlobals(target, Varargs.of(a, b));
        LuaThread L = g != null ? g.running : null;
        if (L == null) {
            return callLua(target, Varargs.of(a, b)).arg1();
        }
        int func = L.top;
        CallInfo oldCi = L.ci;
        byte oldAllowhook = L.allowhook;
        LuaVM.checkStack(L, 3);  // func + 2 args
        L.stack[func] = target;
        L.stack[func + 1] = a;
        L.stack[func + 2] = b;
        L.top = func + 3;
        try {
            callLua(L, func, LuaValue.LUA_MULTRET);
        } catch (LuaError e) {
            throw recoverUnprotected(L, func, oldCi, oldAllowhook, e);
        }
        return firstResultAndPop(L, func);
    }

    // java diff: 同上 3-arg 快路径，消除 Varargs.of(a,b,c) 的 VarargsPair 分配。
    public static LuaValue call(LuaValue target, LuaValue a, LuaValue b, LuaValue c) {
        Globals g = resolveGlobals(target, Varargs.of(a, b, c));
        LuaThread L = g != null ? g.running : null;
        if (L == null) {
            return callLua(target, Varargs.of(a, b, c)).arg1();
        }
        int func = L.top;
        CallInfo oldCi = L.ci;
        byte oldAllowhook = L.allowhook;
        LuaVM.checkStack(L, 4);  // func + 3 args
        L.stack[func] = target;
        L.stack[func + 1] = a;
        L.stack[func + 2] = b;
        L.stack[func + 3] = c;
        L.top = func + 4;
        try {
            callLua(L, func, LuaValue.LUA_MULTRET);
        } catch (LuaError e) {
            throw recoverUnprotected(L, func, oldCi, oldAllowhook, e);
        }
        return firstResultAndPop(L, func);
    }



    // java-only: 基于栈的 C->Lua 调用，2 参 1 结果（C 等价: 参数已在栈上的 luaD_callnoyield(L, func, 1)）。
    // java diff: C 经 lua_pushvalue/lua_call 用栈上参数调 luaD_callnoyield；此方法直接把参数放进
    //   L.stack[] 再调 ccall，避免 Varargs 分配。供 table.sort 比较器（ltablib.c:sortcomp）、Metamethod.callTM/tryBinTM/callOrderTM 使用
    public static LuaValue callOnStack2to1(LuaValue target, LuaValue a, LuaValue b) {
        Globals g = resolveGlobals(target, Varargs.of(a, b));
        LuaThread L = g != null ? g.running : null;
        if (L == null) {
            if (target instanceof LuaFunction fn)
                return fn.call(Varargs.of(a, b)).arg1();
            LuaErrors.callError(target);
            return LuaValue.NIL;
        }
        int func = L.top;
        LuaVM.checkStack(L, 4);
        L.stack[func] = target;
        L.stack[func + 1] = a;
        L.stack[func + 2] = b;
        L.top = func + 3;
        callNoYield(L, func, 1);
        LuaValue result = L.stack[func];
        L.top = func;
        return result;
    }

    public static LuaValue callOnStack1to1(LuaValue target, LuaValue a) {
        Globals g = resolveGlobals(target, a);
        LuaThread L = g != null ? g.running : null;
        if (L == null) {
            if (target instanceof LuaFunction fn)
                return fn.call(a).arg1();
            LuaErrors.callError(target);
            return LuaValue.NIL;
        }
        int func = L.top;
        LuaVM.checkStack(L, 3);
        L.stack[func] = target;
        L.stack[func + 1] = a;
        L.top = func + 2;
        callNoYield(L, func, 1);
        LuaValue result = L.stack[func];
        L.top = func;
        return result;
    }

    public static LuaValue callOnStack3to1(LuaValue target, LuaValue a, LuaValue b, LuaValue c) {
        Globals g = resolveGlobals(target, Varargs.of(a, b, c));
        LuaThread L = g != null ? g.running : null;
        if (L == null) {
            if (target instanceof LuaFunction fn)
                return fn.call(Varargs.of(a, b, c)).arg1();
            LuaErrors.callError(target);
            return LuaValue.NIL;
        }
        int func = L.top;
        LuaVM.checkStack(L, 5);
        L.stack[func] = target;
        L.stack[func + 1] = a;
        L.stack[func + 2] = b;
        L.stack[func + 3] = c;
        L.top = func + 4;
        callNoYield(L, func, 1);
        LuaValue result = L.stack[func];
        L.top = func;
        return result;
    }
}
