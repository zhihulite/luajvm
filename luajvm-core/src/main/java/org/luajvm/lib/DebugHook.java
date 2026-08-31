// ref: ldblib.c (hookf)
// diff: db_debug 简化为空实现；CallInfo 链遍历设 trap；DebugFrame 链替代 CallInfo 链；LuaError.savedStack 处理 finally 块提前弹出帧
package org.luajvm.lib;

import org.luajvm.core.LuaDebug;
import org.luajvm.core.CallInfo;
import org.luajvm.core.Globals;
import org.luajvm.core.LuaClosure;
import org.luajvm.core.LuaError;
import org.luajvm.core.LuaFunction;
import org.luajvm.core.LuaInteger;
import org.luajvm.core.LuaString;
import org.luajvm.core.LuaTable;
import org.luajvm.core.LuaThread;
import org.luajvm.core.LuaValue;
import org.luajvm.core.Prototype;
import org.luajvm.core.Varargs;
import org.luajvm.vm.LuaCall;
import org.luajvm.vm.LuaVM;

import java.util.ArrayList;

public final class DebugHook {
    public static final LuaString HOOKKEY = LuaString.newStr("_HOOKKEY");
    // lauxlib.c: LEVELS1/LEVELS2  -  traceback 前段与后段的帧数
    private static final int LEVELS1 = 10;
    private static final int LEVELS2 = 11;
    private static final hookf HOOKF = new hookf();

    private DebugHook() {
    }

    // ldblib.c: gethooktable
    private static LuaTable getHookTable(Globals g) {
        LuaValue hookKey = g.registry.hashGet(HOOKKEY);
        if (hookKey.istable()) {
            return hookKey.checktable();
        }
        LuaTable hookTable = LuaValue.tableOf();
        LuaTable mt = LuaValue.tableOf();
        mt.setEntry(LuaValue.MODE, LuaString.newStr("k"));
        hookTable.setmetatable(mt);
        g.registry.setEntry(HOOKKEY, hookTable);
        return hookTable;
    }

    // ldblib.c: resolvehookfunction
    public static LuaValue resolveHookFunction(LuaThread L) {
        if (L == null) return LuaValue.NIL;
        LuaValue hook = L.hook;
        if (hook == null || hook.isnil()) return LuaValue.NIL;
        if (hook == HOOKF) {
            Globals g = L.l_G;
            if (g == null) return LuaValue.NIL;
            return getHookTable(g).get(L);
        }
        return hook;
    }

    // lstate.c: lua_newthread  -  新线程继承创建线程的 hook
    // java diff: C 只拷 hookmask/basehookcount/hook，不动 ldblib.c 的 HOOKKEY 表；
    //   子线程无表项 ⇒ debug.gethook(co) 为 nil 且 hook 不触发，故此处不复制 hookTable。
    public static void inheritHooks(Globals g, LuaThread parent, LuaThread child) {
        if (g == null || parent == null || child == null) return;
        child.hookmask = parent.hookmask;
        child.basehookcount = parent.basehookcount;
        child.hookcount = parent.hookcount;
        child.hook = parent.hook;
    }

    // ldebug.c: lua_sethook
    private static void setHookState(Globals g, LuaThread target, LuaValue func, String mask, int count) {

        LuaThread L = target != null ? target : g.running;
        if (L == null) return;
        LuaTable hookTable = getHookTable(g);
        int hookmask = stringToHookmask(mask);
        if (count > 0) {
            hookmask |= LuaThread.LUA_MASKCOUNT;
        }
        // ldebug.c: lua_sethook  -  func 为空或 mask==0 都强制关 hook，
        //   否则会留下 HOOKF 与 hookmask=0 的矛盾态
        if (func == null || func.isnil() || hookmask == 0) {
            hookTable.set(L, LuaValue.NIL);
            L.hook = LuaValue.NIL;
            L.hookmask = 0;
        } else {
            hookTable.set(L, func);
            L.hook = HOOKF;
            L.hookmask = hookmask;
        }
        L.basehookcount = count;
        L.hookcount = count;
        // ldebug.c: lua_sethook  -  if (mask) settraps(L->ci)：须按生效后的 hookmask 判断，
        //   不能用原始 mask 串（mask==0 时 hookmask 已归零）。
        if (L.hookmask != 0) {
            for (CallInfo ci = L.ci; ci != null; ci = ci.previous) {
                if (ci.isLua()) ci.trap = true;
            }
        }
    }

    // ldblib.c: hookmaskToString
    private static String hookmaskToString(int mask) {
        StringBuilder sb = new StringBuilder();
        if ((mask & LuaThread.LUA_MASKCALL) != 0) sb.append('c');
        if ((mask & LuaThread.LUA_MASKRET) != 0) sb.append('r');
        if ((mask & LuaThread.LUA_MASKLINE) != 0) sb.append('l');
        return sb.toString();
    }

    // ldblib.c: stringToHookmask
    private static int stringToHookmask(String mask) {
        int m = 0;
        if (mask != null) {
            for (int i = 0; i < mask.length(); i++) {
                switch (mask.charAt(i)) {
                    case 'c' -> m |= LuaThread.LUA_MASKCALL;
                    case 'r' -> m |= LuaThread.LUA_MASKRET;
                    case 'l' -> m |= LuaThread.LUA_MASKLINE;
                }
            }
        }
        return m;
    }

    // lauxlib.c: luaL_traceback
    // java diff: C 的 CallInfo 链在 longjmp 后仍存活可直接遍历；Java 分 live 路径
    //   （异常展开不弹 CallInfo）与快照路径（死协程/宿主边界恢复后 L.ci 已复位，
    //   只能用抛错时的 DebugFrame 列表）。
    public static LuaValue traceback(LuaThread target, LuaValue msg, int level) {
        return traceback(null, target, msg, level);
    }

    // java-only: 用抛错快照生成 traceback。宿主在 catch 里已越过 CI 链复位点，
    //   拿不到 live 帧（C 的 msghandler 在 longjmp 前运行，链仍完整），故给出这个入口
    //   让宿主复用同一份帧格式化逻辑，输出与 live 路径逐字节一致。
    public static LuaValue tracebackFromSnapshot(
            LuaThread target, ArrayList<Globals.DebugFrame> savedStack, LuaValue msg, int level) {
        if (savedStack == null || savedStack.isEmpty()) return LuaValue.NIL;
        StringBuilder b = new StringBuilder();
        if (!msg.isnil()) b.append(msg.toJavaString()).append('\n');
        b.append("stack traceback:");
        tracebackFrames(b, filterFrames(savedStack, target, null), level);
        return LuaString.newStr(b.toString());
    }

    private static LuaValue traceback(Globals owner, LuaThread target, LuaValue msg, int level) {
        // ldblib.c: db_traceback  -  lua_tostring 转不出字符串的非 nil msg 原样返回
        //   （C 的 lua_tostring 会转换数字，故数字 msg 作消息前缀）
        if (!msg.isnil() && !msg.isstring() && !msg.isnumber()) return msg;
        StringBuilder b = new StringBuilder();
        // lauxlib.c: luaL_traceback  -  C 判 msg != NULL，空串同样输出换行
        if (!msg.isnil()) b.append(msg.toJavaString()).append('\n');
        b.append("stack traceback:");
        Globals g = target != null ? target.l_G : owner;
        if (g == null) return LuaString.newStr(b.toString());
        LuaThread l1 = target != null ? target : g.running;
        ArrayList<Globals.DebugFrame> frames;
        if (target != null && "dead".equals(target.auxstatus()) && target.errorStack != null) {
            // java diff: 死协程 CI 链已复位，用 runCoroutine 保存的快照
            //（C 里出错协程的 CallInfo 链仍存活，可直接遍历）
            tracebackFrames(b, filterFrames(target.errorStack, target, null), level);
        } else if (l1 != null && (frames = errorFrames(g, l1)) != null) {
            // message handler 执行期间
            tracebackFrames(b, frames, level);
        } else if (l1 != null) {
            tracebackLive(b, l1, level);
        }
        return LuaString.newStr(b.toString());
    }

    // java-only: message handler 执行期间的帧序列 = handler 之上的 live 帧 ++ 抛错点起的快照帧。
    // C 的 CallInfo 链在 longjmp 后完整保留（handler 帧压在抛错帧之上，故 level=1 看到 handler、
    // level=2 看到 "[C]: in global 'error'"）。Java 的 handler 在 catch 里调用：
    //   前段（handler 与 debug.traceback 自身）是 live 的；
    //   后段必须用快照 - closeUpvals 捕获 __close 错误后已恢复 ci，live 链里没有
    //   __close 元方法帧（locals.lua:544 的 "in metamethod 'close'" 钉住这条）。
    // 判据只用 errfuncBaseCi/errfuncError（仅 handler 执行期间非空）：pendingError 可能是
    // 别处（如 __gc 里）抛出后遗留的过期错误，用它会读到不相干的旧帧链。
    private static ArrayList<Globals.DebugFrame> errorFrames(Globals g, LuaThread l1) {
        if (l1 != g.running || l1.errfuncBaseCi == null || l1.errfuncError == null) return null;
        LuaError le = l1.errfuncError;
        le.ensureSnapshot();
        if (le.savedStack == null) return null;
        ArrayList<Globals.DebugFrame> frames = liveFramesAbove(g, l1, l1.errfuncBaseCi);
        frames.addAll(filterFrames(le.savedStack, l1, Thread.currentThread()));
        return frames;
    }

    // java-only: L.ci 起、到 handler 调用点（不含）之间的 live 帧
    private static ArrayList<Globals.DebugFrame> liveFramesAbove(
            Globals g, LuaThread L, CallInfo boundary) {
        ArrayList<Globals.DebugFrame> above = new ArrayList<>();
        if (boundary == null) return above;
        for (CallInfo ci = L.ci; ci != null && ci != L.base_ci; ci = ci.previous) {
            if (ci == boundary) return above;
            above.add(g.ciToFrame(ci, L));
        }
        above.clear();  // 边界不在链上：退回纯快照（不重复输出边界以下的帧）
        return above;
    }

    // lauxlib.c: lastlevel
    // java diff: C 用 lua_getstack 二分查找（每次 O(depth)）；Java 沿 previous 单趟计数，结果相同
    private static int lastlevel(LuaThread L) {
        int last = -1;
        for (CallInfo ci = L.ci; ci != null && ci != L.base_ci; ci = ci.previous) last++;
        return last;
    }

    // ldebug.c: lua_getstack
    private static CallInfo getstack(LuaThread L, int level) {
        if (level < 0) return null;  // invalid (negative) level
        CallInfo ci = L.ci;
        for (; level > 0 && ci != null && ci != L.base_ci; level--) ci = ci.previous;
        return (level == 0 && ci != null && ci != L.base_ci) ? ci : null;
    }

    // java-only: 由已定位的帧再上跳 n 层（替代 C 每轮 lua_getstack 从 L->ci 重走链）
    private static CallInfo skiplevels(CallInfo ci, LuaThread L, int n) {
        for (int i = 0; i < n && ci != null; i++) {
            ci = ci.previous;
            if (ci == L.base_ci) return null;
        }
        return ci;
    }

    // lauxlib.c: luaL_traceback 的层级循环（live CI 链）
    private static void tracebackLive(StringBuilder b, LuaThread l1, int level) {
        int last = lastlevel(l1);
        int limit2show = (last - level > LEVELS1 + LEVELS2) ? LEVELS1 : -1;
        CallInfo ci = getstack(l1, level);
        while (ci != null) {  // C: while (lua_getstack(L1, level++, &ar))
            level++;
            if (limit2show-- == 0) {  // too many levels?
                int n = last - level - LEVELS2 + 1;  // number of levels to skip
                b.append("\n\t...\t(skipping ").append(n).append(" levels)");
                level += n;  // and skip to last levels
                // C: 本轮取到的帧被 skip 分支消费（不输出），故再上跳 n+1 层
                ci = skiplevels(ci, l1, n + 1);
            } else {
                appendTracebackFrameCI(b, ci, l1);
                ci = skiplevels(ci, l1, 1);
            }
        }
    }

    // lauxlib.c: luaL_traceback 的层级循环（DebugFrame 列表 - 下标即 level）
    private static void tracebackFrames(
            StringBuilder b, ArrayList<Globals.DebugFrame> frames, int level) {
        if (frames == null || level < 0) return;
        int last = frames.size() - 1;
        int limit2show = (last - level > LEVELS1 + LEVELS2) ? LEVELS1 : -1;
        while (level <= last) {
            int current = level++;
            if (limit2show-- == 0) {  // too many levels?
                int n = last - level - LEVELS2 + 1;  // number of levels to skip
                b.append("\n\t...\t(skipping ").append(n).append(" levels)");
                level += n;  // and skip to last levels
            } else {
                appendTracebackFrame(b, frames.get(current));
            }
        }
    }

    // java-only: 抛错快照按 CI 链顺序保存，同一 Globals 的多线程共用一份列表，需按线程过滤。
    //   优先"同 Lua 线程且同 Java 线程"；无匹配时退回按 Java 线程（协程线程模型下
    //   一个 Java 线程可承载多个 Lua 线程的帧）。
    private static ArrayList<Globals.DebugFrame> filterFrames(
            ArrayList<Globals.DebugFrame> stack, LuaThread target, Thread javaThread) {
        ArrayList<Globals.DebugFrame> out = new ArrayList<>();
        if (stack == null || stack.isEmpty()) return out;
        if (javaThread == null) {
            for (Globals.DebugFrame frame : stack) {
                if (frame.thread == target) out.add(frame);
            }
            return out;
        }
        for (Globals.DebugFrame frame : stack) {
            if (frame.thread == target && frame.javaThread == javaThread) out.add(frame);
        }
        if (out.isEmpty()) {
            for (Globals.DebugFrame frame : stack) {
                if (frame.javaThread == javaThread) out.add(frame);
            }
        }
        return out;
    }

    // lauxlib.c: luaL_traceback  -  帧头：currentline <= 0（C 函数 / 无行号信息）不输出行号
    private static void appendFrameHead(StringBuilder b, String shortSrc, int currentline) {
        b.append("\n\t").append(shortSrc);
        if (currentline > 0) b.append(':').append(currentline);
        b.append(": in ");
    }

    // lauxlib.c: pushfuncname
    // java diff: C 从 lua_Debug 取 namewhat/what/short_src/linedefined，Java 由调用方解析后传入
    private static void pushfuncname(StringBuilder b, String name, String namewhat,
                                     LuaValue func, String src, int linedefined, boolean isC) {
        if (name != null && namewhat != null && !namewhat.isEmpty()) {  /* is there a name from code? */
            b.append(namewhat).append(" '").append(name).append('\'');  /* use it */
        } else if (!isC && linedefined == 0) {  /* main? */
            b.append("main chunk");
        } else {
            String globalName = pushglobalfuncname(func instanceof LuaFunction fn ? fn : null);
            if (globalName != null) {  /* try a global name */
                b.append("function '").append(globalName).append('\'');
            } else if (!isC) {  /* for Lua functions, use <file:line> */
                b.append("function <").append(src).append(':').append(linedefined).append('>');
            } else {  /* nothing left... */
                b.append('?');
            }
        }
    }

    // lauxlib.c: luaL_traceback 的单帧输出（DebugFrame 快照帧）
    private static void appendTracebackFrame(StringBuilder b, Globals.DebugFrame frame) {
        if (frame == null) return;
        LuaVM.DebugName dn = LuaVM.resolveFrameName(frame);
        Globals.DebugFrame.Extras ex = frame.extrasIfPresent();
        String name = dn != null ? dn.name() : ex != null ? ex.name : null;
        String namewhat = dn != null ? dn.namewhat() : ex != null ? ex.namewhat : null;
        if (frame.func instanceof LuaClosure cl && cl.p != null) {
            Prototype p = cl.p;
            String src = LuaDebug.chunkid(p.source != null ? p.source.toJavaString() : "?");
            appendFrameHead(b, src, LuaDebug.getFuncLinePub(p, frame.pc));
            pushfuncname(b, name, namewhat, frame.func, src, p.linedefined, false);
        } else {
            appendFrameHead(b, "[C]", -1);
            pushfuncname(b, name, namewhat, frame.func, "[C]", -1, true);
        }
        // lauxlib.c: luaL_traceback —— 尾调用帧追加标记
        if (frame.istailcall) {
            b.append("\n\t(...tail calls...)");
        }
    }

    // java-only: CallInfo 直读版 appendTracebackFrame  -  消除 DebugFrame 分配
    // 对齐 C 的 traceback：直接在 CallInfo 上工作（C 用栈上 lua_Debug），不建堆对象。
    // 输出与 appendTracebackFrame 逐字节一致（同一 func/pc/name/namewhat 解析逻辑）。
    private static void appendTracebackFrameCI(StringBuilder b, CallInfo ci, LuaThread L) {
        if (ci == null) return;
        LuaValue funcVal = (ci.func >= 0 && ci.func < L.stack.length) ? L.stack[ci.func] : null;
        // 解析 name/namewhat，与 Globals.ciToFrame 同逻辑：CIST_TAIL 不解析，
        // caller 的 CIST_HOOKED/CIST_FIN 覆盖 funcnamefromcall 结果
        String name = null, namewhat = null;
        if ((ci.callstatus & CallInfo.CIST_TAIL) == 0 && ci.previous != null) {
            CallInfo caller = ci.previous;
            LuaDebug.NameWhat nw = new LuaDebug.NameWhat();
            String what = LuaDebug.funcnamefromcall(L, caller, nw);
            if (what != null && !what.isEmpty()) {
                name = nw.name;
                namewhat = what;
            }
            if ((caller.callstatus & CallInfo.CIST_HOOKED) != 0) {
                name = "?";
                namewhat = "hook";
            } else if ((caller.callstatus & CallInfo.CIST_FIN) != 0) {
                name = "__gc";
                namewhat = "metamethod";
            }
        }
        if (funcVal instanceof LuaClosure cl && cl.p != null) {
            Prototype p = cl.p;
            String src = LuaDebug.chunkid(p.source != null ? p.source.toJavaString() : "?");
            int pc = ci.isLua() ? CallInfo.currentpc(ci) : -1;
            appendFrameHead(b, src, LuaDebug.getFuncLinePub(p, pc));
            pushfuncname(b, name, namewhat, funcVal, src, p.linedefined, false);
        } else {
            appendFrameHead(b, "[C]", -1);
            pushfuncname(b, name, namewhat, funcVal, "[C]", -1, true);
        }
        // lauxlib.c: luaL_traceback —— 尾调用帧追加标记（CIST_TAIL 即 lua_Debug.istailcall）
        if ((ci.callstatus & CallInfo.CIST_TAIL) != 0) {
            b.append("\n\t(...tail calls...)");
        }
    }

    // lauxlib.c: pushglobalfuncname/findfield
    private static String pushglobalfuncname(LuaFunction fn) {
        if (fn == null) return null;
        Globals g = fn.ownerGlobals;
        if (g == null) return null;
        LuaValue loaded = g.registry.hashGet(LuaString.newStr("loaded"));
        if (!loaded.istable()) return null;
        String name = findfield(loaded.checktable(), fn, 2, null);
        if (name != null && name.startsWith("_G.")) return name.substring(3);
        return name;
    }

    // lauxlib.c: findfield
    private static String findfield(LuaTable table, LuaFunction fn, int level, String prefix) {
        LuaValue key = LuaValue.NIL;
        while (true) {
            Varargs next = table.next(key);
            if (next == LuaValue.NONE) return null;
            key = next.arg1();
            LuaValue value = next.arg(2);
            if (value == fn && key.isstring()) {
                String k = key.toJavaString();
                return prefix == null ? k : prefix + "." + k;
            }
            if (level > 1 && value.istable() && key.isstring()) {
                String k = key.toJavaString();
                String nested = findfield(value.checktable(), fn, level - 1,
                        prefix == null ? k : prefix + "." + k);
                if (nested != null) return nested;
            }
        }
    }

    // ldblib.c: db_debug
    public static class DbDebugFn extends LuaFunction {
        @Override
        public Varargs call(Varargs args) {
            return LuaValue.NONE;
        }
    }

    // ldblib.c: db_gethook
    public static class DbGetHookFn extends LuaFunction {
        @Override
        public Varargs call(Varargs args) {
            Globals g = ownerGlobals;
            LuaValue func;
            String mask;
            int count;
            LuaThread thread;
            if (args.arg(1) instanceof LuaThread t) {
                thread = t;
                mask = hookmaskToString(t.hookmask);
                count = t.basehookcount;
            } else {
                if (g == null || g.running == null)
                    return LuaValue.varargsOf(LuaValue.NIL, LuaValue.NIL, LuaValue.NIL);

                thread = g.running;
                mask = hookmaskToString(thread.hookmask);
                count = thread.basehookcount;
            }
            func = thread.hook;
            if (func == null || func.isnil())
                return LuaValue.varargsOf(LuaValue.NIL, LuaValue.NIL, LuaValue.NIL);
            if (func == HOOKF) {
                func = getHookTable(g).get(thread);
            } else {
                func = LuaString.newStr("external hook");
            }
            return LuaValue.varargsOf(func, LuaString.newStr(mask), LuaInteger.valueOf(count));
        }
    }

    // ldblib.c: db_sethook
    public static class DbSetHookFn extends LuaFunction {
        @Override
        public Varargs call(Varargs args) {
            Globals g = ownerGlobals;
            if (g == null) return LuaValue.NONE;
            int arg = args.arg(1) instanceof LuaThread ? 2 : 1;
            LuaThread target = arg == 2 ? args.checkthread(1) : null;
            LuaValue func = args.arg(arg);
            if (func.isnil()) {
                setHookState(g, target, LuaValue.NIL, "", 0);
                return LuaValue.NONE;
            }
            LuaFunction hook = args.checkfunction(arg);
            String mask = args.optJavaString(arg + 1, "");
            int count = args.optint(arg + 2, 0);
            setHookState(g, target, hook, mask, count);
            return LuaValue.NONE;
        }
    }

    // ldblib.c: db_traceback
    public static class DbTracebackFn extends LuaFunction {
        @Override
        public Varargs call(Varargs args) {
            // ldblib.c: getthread
            LuaThread target = args.arg(1) instanceof LuaThread t ? t : null;
            int arg = target != null ? 2 : 1;
            LuaValue msg = args.arg(arg);
            // ldblib.c:  msg == NULL && !isnoneornil -> 原样返回（lua_tostring 会转换数字）
            if (!msg.isnil() && !msg.isstring() && !msg.isnumber()) return msg;
            Globals g = ownerGlobals;
            // ldblib.c:  luaL_optinteger(L, arg + 2, (L == L1) ? 1 : 0)
            int def = (target == null || (g != null && target == g.running)) ? 1 : 0;
            int level = args.arg(arg + 1).optint(def);
            return traceback(g, target, msg, level);
        }
    }

    // ldblib.c: hookf
    private static final class hookf extends LuaFunction {
        @Override
        public Varargs call(Varargs args) {
            Globals g = ownerGlobals;
            LuaThread L = g != null ? g.running : null;
            if (g == null || L == null) return LuaValue.NONE;
            LuaValue func = getHookTable(g).get(L);
            if (!(func instanceof LuaFunction) && !func.isfunction()) {
                return LuaValue.NONE;
            }
            LuaCall.callNoYield(func, args);
            return LuaValue.NONE;
        }
    }


}
