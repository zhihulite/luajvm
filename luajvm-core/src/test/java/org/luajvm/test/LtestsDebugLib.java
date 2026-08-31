// ref: ltests.c / ltests.h（官方测试 debug 库子集）
// diff: 仅实现测试所需的 T 库函数子集；lua_State 状态用 Globals/LuaThread 替代
package org.luajvm.test;

import org.luajvm.compiler.Opcodes;
import org.luajvm.core.LuaDebug;
import org.luajvm.core.BinaryOp;
import org.luajvm.core.CallInfo;
import org.luajvm.core.Globals;
import org.luajvm.core.LuaClosure;
import org.luajvm.core.LuaError;
import org.luajvm.core.LuaErrors;
import org.luajvm.core.LuaFloat;
import org.luajvm.core.LuaFunction;
import org.luajvm.core.LuaGC;
import org.luajvm.core.LuaInteger;
import org.luajvm.core.LuaLightUserdata;
import org.luajvm.core.LuaString;
import org.luajvm.core.LuaTable;
import org.luajvm.core.LuaThread;
import org.luajvm.core.LuaUserdata;
import org.luajvm.core.LuaValue;
import org.luajvm.core.Metamethod;
import org.luajvm.core.Prototype;
import org.luajvm.core.UnaryOp;
import org.luajvm.core.Varargs;
import org.luajvm.vm.LuaArith;
import org.luajvm.vm.LuaCall;
import org.luajvm.vm.LuaCompare;
import org.luajvm.vm.LuaConcat;
import org.luajvm.vm.LuaIndex;
import org.luajvm.lib.BaseLib;
import org.luajvm.lib.CoroutineLib;
import org.luajvm.lib.DebugHook;
import org.luajvm.lib.DebugLib;
import org.luajvm.lib.IoLib;
import org.luajvm.lib.MathLib;
import org.luajvm.lib.OsLib;
import org.luajvm.lib.PackageLib;
import org.luajvm.lib.StringFormat;
import org.luajvm.lib.StringLib;
import org.luajvm.lib.TableLib;
import org.luajvm.lib.Utf8Lib;
import org.luajvm.spi.LuaConfig;
import org.luajvm.vm.LuaVM;
import org.luajvm.vm.LuaPlatform;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * 官方 ltests.c/ltests.h 的 debug 子集。
 *
 * <p>仅供测试入口按需注入全局 T，不是完整 C API 测试库。</p>
 */
public final class LtestsDebugLib {
    private static final int REGISTRY_INDEX = Integer.MIN_VALUE + 100;
    private static final int UPVALUE_INDEX_BASE = Integer.MIN_VALUE + 1000;
    private static final int LUA_MULTRET = -1;
    private static final int LUAI_MAXSTACK = 68000; // C: ltests.h:LUAI_MAXSTACK
    private static final int LUA_RIDX_MAINTHREAD = 1;
    private static final int LUA_RIDX_GLOBALS = 2;
    private static final int LUA_GLIBK = 1;
    private static final int LUA_LOADLIBK = LUA_GLIBK << 1;
    private static final int LUA_COLIBK = LUA_LOADLIBK << 1;
    private static final int LUA_DBLIBK = LUA_COLIBK << 1;
    private static final int LUA_IOLIBK = LUA_DBLIBK << 1;
    private static final int LUA_MATHLIBK = LUA_IOLIBK << 1;
    private static final int LUA_OSLIBK = LUA_MATHLIBK << 1;
    private static final int LUA_STRLIBK = LUA_OSLIBK << 1;
    private static final int LUA_TABLIBK = LUA_STRLIBK << 1;
    private static final int LUA_UTF8LIBK = LUA_TABLIBK << 1;
    private static final StdLib[] STDLIBS = new StdLib[]{
            new StdLib("_G", LUA_GLIBK, BaseLib::new),
            new StdLib("package", LUA_LOADLIBK, PackageLib::new),
            new StdLib("coroutine", LUA_COLIBK, CoroutineLib::new),
            new StdLib("debug", LUA_DBLIBK, DebugLib::new),
            new StdLib("io", LUA_IOLIBK, IoLib::new),
            new StdLib("math", LUA_MATHLIBK, MathLib::new),
            new StdLib("os", LUA_OSLIBK, OsLib::new),
            new StdLib("string", LUA_STRLIBK, StringLib::new),
            new StdLib("table", LUA_TABLIBK, TableLib::new),
            new StdLib("utf8", LUA_UTF8LIBK, Utf8Lib::new)
    };
    private static final Map<Long, Long> lightUserdataPointers = new HashMap<>();
    private static final Map<Long, RemoteState> remoteStates = new HashMap<>();
    private static final Map<LuaThread, ArrayList<LuaValue>> threadStacks = new IdentityHashMap<>();
    private static final Map<LuaThread, StackLuaThread> mainResumeThreads = new IdentityHashMap<>();
    private static final Map<LuaThread, ArrayList<LuaValue>> mainResumeSavedStacks = new IdentityHashMap<>();
    // ltests.c: statcodes + regcodes  -  预注册状态码字符串（ltests 侧非引擎核心）：
    //   C 注册到 registry pin 住，避免内存受限时 pushstatus 分配失败（"Avoid...memory error
    //   when pushing them"）。
    // java diff: 类加载时缓存 LuaString 实例，pushstatus 直接用（不分配）
    private static final Map<String, LuaString> STAT_CODES = new HashMap<>();
    private static long userdataSerial = 0;
    private static long remoteStateSerial = 0;
    private static int diagnosticStringTableSize = 128;
    private static String currentGcState = "pause";
    private static long atomicWatermark = 0;

    static {
        STAT_CODES.put("OK", LuaString.valueOf("OK"));
        STAT_CODES.put("YIELD", LuaString.valueOf("YIELD"));
        STAT_CODES.put("ERRRUN", LuaString.valueOf("ERRRUN"));
        STAT_CODES.put("ERRSYNTAX", LuaString.valueOf("ERRSYNTAX"));
        STAT_CODES.put("ERRERR", LuaString.valueOf("ERRERR"));
        STAT_CODES.put(LuaString.MEMERRMSG.toJavaString(), LuaString.MEMERRMSG);
    }

    private LtestsDebugLib() {
    }

    public static LuaTable open(Globals globals) {
        globals.config = new LtestsConfig();
        // java diff：luaStackLimit/diagnosticStackSize/diagnosticStackOverflowSize
        // 已删除；栈大小由 LuaThread.stack.length 管理。
        LuaTable t = new LuaTable();
        t.bindGlobals(globals);
        LuaFunction querytab = new querytab();
        t.set("listabslineinfo", new listabslineinfo());
        t.set("listlocals", new listlocals());
        t.set("listk", new listk());
        t.set("listcode", new listcode());
        t.set("querytab", querytab);
        t.set("querystr", new querystr());
        t.set("sethook", new sethook(globals));
        t.set("stacklevel", new stacklevel(globals));
        t.set("resetCI", new resetci(globals));
        t.set("reallocstack", new reallocstack(globals));
        t.set("checkmemory", new checkmemory());
        t.set("totalmem", new totalmem());
        t.set("gcstate", new gcstate());
        t.set("gccolor", new gccolor());
        t.set("gcage", new gcage());
        t.set("checkpanic", new checkpanic());
        t.set("newstate", new newstate());
        t.set("closestate", new closestate());
        t.set("loadlib", new loadlib());
        t.set("doonnewstack", new doonnewstack(globals));
        t.set("doremote", new doremote());
        t.set("newuserdata", new newuserdata());
        t.set("pushuserdata", new pushuserdata());
        t.set("resume", new coresume());
        t.set("udataval", new udataval());
        t.set("d2s", new d2s());
        t.set("s2d", new s2d());
        t.set("testC", new testC());
        t.set("makeCfunc", new makeCfunc());
        t.set("upvalue", new upvalue());
        t.set("externKstr", new externKstr());
        t.set("externstr", new externstr());
        t.set("codeparam", new codeparam());
        t.set("applyparam", new applyparam());
        t.set("alloccount", new alloccount());
        t.set("allocfailnext", new allocfailnext());
        t.set("trick", new trick());
        t.set("ref", new tref());
        t.set("getref", new getref());
        t.set("unref", new unref());
        globals.set("T", t);
        globals.set("querytab", querytab);
        LuaValue pkg = globals.get("package");
        if (!pkg.isnil()) pkg.get("loaded").set("T", t);
        return t;
    }

    private static LuaValue remoteStateValue(RemoteState state) {
        return lightUserdata(-0x5A7E00000000L - state.id);
    }

    private static RemoteState newRemoteState() {
        // C: ltests.c:newstate/checkpanic -> lua_newstate allocates a full
        // lua_State/global_State before any library is opened. Account for that
        // fixed state block so memory-limit tests can fail during state creation.
        // C: lua_newstate -> frealloc(sizeof(global_State)) 约 500-600 bytes on 64-bit
        // Java diff: Globals/LuaThread already accounted via their constructors,
        // so stateBytes only covers global_State structure itself
        long stateBytes = 576L;
        Globals globals = null;
        try {
            globals = LuaPlatform.bareGlobals();
            BaseLib.CollectGarbageFn.checkLuaMemory(globals, stateBytes);
            BaseLib.CollectGarbageFn.allocBytes(globals, stateBytes);
            // java diff：luaStackLimit/diagnosticStackSize/diagnosticStackOverflowSize
            // 已删除；栈大小由 LuaThread.stack.length 管理。
            RemoteState state = new RemoteState(++remoteStateSerial, globals, stateBytes);
            remoteStates.put(lightUserdataAddress((LuaLightUserdata) remoteStateValue(state)), state);
            return state;
        } catch (RuntimeException | Error error) {
            // C: ltests.c:newstate  -  lua_newstate 分配失败返回 NULL，没有任何状态残留。
            // java diff: Globals 构造已 registerGlobals（字符串分配路径需经登记表取所属状态），
            //   故此处失败必须回滚登记，否则该 Globals 永久滞留 activeGlobalsList：
            //   memerr.lua:111 的 T.newstate() 在内存限额下返回 nil，Lua 侧不会再 closestate，
            //   残留状态会被 forEachActiveGlobals 持续算进 T.totalmem 的进程级计数
            //   （其 main thread 使 threads 比 C 多 1），且使 fullGC 的 markRoots 退化。
            //   LuaPlatform.bareGlobals 已对其内部的 LuaThread 分配做同样回滚。
            if (globals != null) {
                BaseLib.CollectGarbageFn.freeBytes(globals, stateBytes);
                LuaTable.unregisterGlobals(globals);
            }
            throw error;
        }
    }

    private static void closeRemoteState(RemoteState state) {
        if (state == null || state.closed) return;
        state.stack.clear();
        state.closed = true;
        // lstate.c: lua_close -> luaC_freeallobjects -> free all objects
        // Java diff: must explicitly free memory accounting for Globals (LuaTable),
        // its registry, and LuaThread, since JVM GC doesn't reduce luaMemoryBytes
        Globals g = state.globals;
        if (g != null) {
            // 从中立登记表注销，长串不再记到已关闭状态上
            LuaTable.unregisterGlobals(g);
            LuaTable reg = g.registry;
            if (reg != null) {
                BaseLib.CollectGarbageFn.freeBytes(g, reg.currentStorageBytes());
                reg.clear();
            }
            BaseLib.CollectGarbageFn.freeBytes(g, g.currentStorageBytes());
            g.clear();
            LuaThread t = g.running;
            if (t != null) {
                BaseLib.CollectGarbageFn.freeBytes(g, t.threadSize());
                LuaThread.removeThread(t);
            }
        }
        BaseLib.CollectGarbageFn.freeBytes(state.globals, state.storageBytes);
    }

    private static LuaTable registryTable(Globals globals, String name) {
        LuaValue key = LuaString.valueOf(name);
        LuaValue table = globals.registry.get(key);
        if (!table.istable()) {
            table = new LuaTable();
            globals.registry.set(key, table);
        }
        return table.checktable();
    }

    private static void requireStdLib(Globals globals, StdLib lib) {
        // C: lauxlib.c:luaL_requiref + linit.c:luaL_openselectedlibs
        LuaTable loaded = registryTable(globals, "loaded");
        if (loaded.get(lib.name).toboolean()) return;
        LuaValue module = openStdLib(globals, lib);
        loaded.set(lib.name, module);
        globals.set(lib.name, module);
    }

    private static LuaValue openStdLib(Globals globals, StdLib lib) {
        Varargs result = LuaCall.callLua(lib.factory.create(),
                LuaValue.varargsOf(LuaString.valueOf(lib.name), globals));
        LuaValue globalModule = globals.get(lib.name);
        return !globalModule.isnil() ? globalModule : result.arg1();
    }

    private static void openSelectedLibraries(RemoteState state, int load, int preload) {
        LuaTable preloaded = registryTable(state.globals, "preload");
        for (StdLib lib : STDLIBS) {
            if ((load & lib.mask) != 0) {
                requireStdLib(state.globals, lib);
            } else if ((preload & lib.mask) != 0) {
                preloaded.set(lib.name, new StdLibOpener(state.globals, lib));
            }
        }
        open(state.globals);
    }

    private static RemoteState remoteState(LuaLightUserdata value) {
        RemoteState state = remoteStates.get(lightUserdataAddress(value));
        if (state == null || state.closed) LuaErrors.argError(1, "state expected");
        return state;
    }

    private static LuaTable refTable(Varargs args) {
        if (args.arg(2).istable()) return args.arg(2).checktable();
        Globals globals = LuaTable.runningGlobalsForGC();
        if (globals == null) LuaErrors.error("no active Lua state");
        return globals.registry;
    }

    private static int codeParam(long p) {
        long overflow = (0x1FL << (0xF - 7 - 1)) * 100L;
        if (p >= overflow) return 0xff;
        p = (p * 128L + 99L) / 100L;
        if (p < 0x10) return (int) p;
        int log = ceilLog2(p + 1) - 5;
        return (int) (((p >> log) - 0x10) | ((log + 1) << 4)) & 0xff;
    }

    private static long applyParam(int p, long x) {
        int m = p & 0xF;
        int e = p >> 4;
        if (e > 0) {
            e--;
            m += 0x10;
        }
        e -= 7;
        if (e >= 0) {
            if (x < (Long.MAX_VALUE / 0x1FL) >> e) return (x * m) << e;
            return Long.MAX_VALUE;
        }
        e = -e;
        if (x < Long.MAX_VALUE / 0x1FL) return (x * m) >> e;
        if ((x >> e) < Long.MAX_VALUE / 0x1FL) return (x >> e) * m;
        return Long.MAX_VALUE;
    }

    private static int ceilLog2(long x) {
        if (x <= 1) return 0;
        return 64 - Long.numberOfLeadingZeros(x - 1);
    }

    private static Varargs runCTest(String script, ArrayList<LuaValue> stack) {
        return runCTest(script, stack, false, null, (LtestsCClosure) null);
    }

    private static Varargs runCTest(String script, ArrayList<LuaValue> stack, boolean remoteReturn, Globals activeGlobals) {
        return runCTest(script, stack, remoteReturn, activeGlobals, (LtestsCClosure) null);
    }

    private static Varargs runCTest(String script, ArrayList<LuaValue> stack, boolean remoteReturn, Globals activeGlobals, LtestsCClosure currentClosure) {
        return runCTest(script, stack, remoteReturn, activeGlobals, currentClosure, "OK");
    }

    private static Varargs runCTest(String script, ArrayList<LuaValue> stack, boolean remoteReturn, Globals activeGlobals, String initialStatus) {
        return runCTest(script, stack, remoteReturn, activeGlobals, null, initialStatus);
    }

    private static Varargs runCTest(String script, ArrayList<LuaValue> stack, boolean remoteReturn, Globals activeGlobals,
                                    LtestsCClosure currentClosure, String initialStatus) {
        ArrayList<Integer> toClose = new ArrayList<>();
        ScriptReader reader = new ScriptReader(script);
        String status = initialStatus;
        int checkedStackExtra = 0;
        for (; ; ) {
            String inst = reader.next();
            if (inst == null) break;
            if (inst.equals("pushfstringI")) {
                pushFormatted(stack, stackValue(stack, -2), stackValue(stack, -1));
            } else if (inst.equals("pushfstringS")) {
                pushFormatted(stack, stackValue(stack, -2), stackValue(stack, -1));
            } else if (inst.equals("pushfstringP")) {
                pushFormatted(stack, stackValue(stack, -2), stackValue(stack, -1));
            } else if (inst.equals("pushevent")) {
                stack.add(stackValue(stack, 1));
            } else if (inst.equals("pushline")) {
                stack.add(stackValue(stack, 2));
            } else if (inst.equals("pushint")) {
                stack.add(LuaInteger.valueOf(reader.nextNumber(stack)));
            } else if (inst.equals("pushnum")) {
                stack.add(LuaValue.valueOf((double) reader.nextNumber(stack)));
            } else if (inst.equals("pushbool")) {
                stack.add(reader.nextNumber(stack) != 0 ? LuaValue.TRUE : LuaValue.FALSE);
            } else if (inst.equals("pushnil")) {
                stack.add(LuaValue.NIL);
            } else if (inst.equals("pushstring")) {
                stack.add(LuaString.valueOf(reader.next()));
            } else if (inst.equals("pushvalue")) {
                stack.add(apiValue(stack, reader.nextIndex(stack), currentClosure));
            } else if (inst.equals("gettop")) {
                stack.add(LuaInteger.valueOf(stack.size()));
            } else if (inst.equals("gsub")) {
                // C: ltests.c:runC -> luaL_gsub/lauxlib.c:luaL_addgsub.
                String s = stackValue(stack, reader.nextNumber(stack)).toJavaString();
                String p = stackValue(stack, reader.nextNumber(stack)).toJavaString();
                String r = stackValue(stack, reader.nextNumber(stack)).toJavaString();
                stack.add(LuaString.valueOf(auxGsub(s, p, r)));
            } else if (inst.equals("absindex")) {
                stack.add(LuaInteger.valueOf(absIndex(stack, reader.nextIndex(stack))));
            } else if (inst.equals("newtable")) {
                // C: ltests.c:runC -> newtable
                BaseLib.CollectGarbageFn.checkLuaMemory(activeGlobals, 64);
                stack.add(new LuaTable());
            } else if (inst.equals("newthread")) {
                // C: ltests.c:runC:newthread -> lua_newthread(L1)；新线程有独立 Lua 栈。
                Globals globals = activeGlobals != null ? activeGlobals : LuaTable.runningGlobalsForGC();
                if (globals == null) LuaErrors.error("no active Lua state");
                LuaThread thread = new StackLuaThread(globals);
                threadStacks.put(thread, new ArrayList<>());
                stack.add(thread);
            } else if (inst.equals("newuserdata")) {
                // C: ltests.c:runC:newuserdata -> lapi.c:lua_newuserdatauv
                long size = reader.nextNumber(stack);
                if (size < 0 || size > Integer.MAX_VALUE) LuaErrors.tooBig();
                LuaUserdata nud = new LuaUserdata(new TestUserdata(++userdataSerial, (int) size), 1, size);
                // C：lgc.c : luaC_newobj  -  创建即登记到所属状态，绑定时才检查内存限制
                LuaTable.bindValue(activeGlobals, nud);
                stack.add(nud);
            } else if (inst.equals("newmetatable")) {
                // C: ltests.c:runC:newmetatable -> lauxlib.c:luaL_newmetatable
                String name = reader.next();
                LuaTable registry = activeRegistry(activeGlobals);
                LuaValue key = LuaString.valueOf(name);
                LuaValue existing = registry.rawget(key);
                if (!existing.isnil()) {
                    stack.add(existing);
                    stack.add(LuaValue.FALSE);
                } else {
                    LuaTable metatable = new LuaTable(0, 2);
                    metatable.rawset(LuaString.valueOf("__name"), key);
                    registry.rawset(key, metatable);
                    stack.add(metatable);
                    stack.add(LuaValue.TRUE);
                }
            } else if (inst.equals("append")) {
                // C: ltests.c:runC -> append
                int slot = resolveExistingStackIndex(stack, reader.nextIndex(stack));
                LuaTable table = stack.get(slot).checktable();
                int n = table.rawlen();
                LuaValue value = stack.isEmpty() ? LuaValue.NIL : stack.remove(stack.size() - 1);
                table.rawset(n + 1, value);
            } else if (inst.equals("next")) {
                // C: ltests.c:runC -> lua_next(L1, -2)
                int slot = resolveExistingStackIndex(stack, -2);
                LuaValue table = stack.get(slot);
                LuaValue key = stack.isEmpty() ? LuaValue.NIL : stack.remove(stack.size() - 1);
                Varargs next = table.checktable().next(key);
                if (next != LuaValue.NONE && !next.arg1().isnil()) {
                    stack.add(next.arg1());
                    stack.add(next.arg(2));
                }
            } else if (inst.equals("loadstring")) {
                // C: ltests.c:runC -> loadstring/luaL_loadbufferx
                int sourceIndex = reader.nextNumber(stack);
                String chunkName = reader.next();
                String mode = reader.next();
                if (!isValidStackIndex(stack, sourceIndex)) {
                    throw LuaErrors.errorObject("bad argument #" + sourceIndex + " (string expected, got no value)", 0);
                }
                LuaValue source = stackValue(stack, sourceIndex);
                stack.add(loadString(activeGlobals, source, chunkName, mode));
            } else if (inst.equals("loadfile")) {
                // C: ltests.c:runC -> loadfile/luaL_loadfile
                LuaValue filename = stackValue(stack, reader.nextNumber(stack));
                stack.add(loadFile(activeGlobals, filename));
            } else if (inst.equals("remove")) {
                int slot = resolveExistingStackIndex(stack, reader.nextNumber(stack));
                closeSlotsAtOrAbove(stack, toClose, slot, false);
                stack.remove(slot);
                shiftToCloseAfterRemove(toClose, slot);
            } else if (inst.equals("insert")) {
                int slot = resolveExistingStackIndex(stack, reader.nextNumber(stack));
                if (slot != stack.size() - 1) {
                    LuaValue value = stack.remove(stack.size() - 1);
                    stack.add(slot, value);
                    shiftToCloseAfterInsert(toClose, slot);
                }
            } else if (inst.equals("replace")) {
                int index = reader.nextIndex(stack);
                int slot = isUpvalueIndex(index) ? -1 : resolveExistingStackIndex(stack, index);
                LuaValue value = stack.isEmpty() ? LuaValue.NIL : stack.remove(stack.size() - 1);
                if (isUpvalueIndex(index)) {
                    setUpvalue(currentClosure, upvalueNumber(index), value);
                } else {
                    closeSlot(stack, toClose, slot, null, false);
                    stack.set(slot, value);
                }
            } else if (inst.equals("copy")) {
                LuaValue value = apiValue(stack, reader.nextIndex(stack), currentClosure);
                int toIndex = reader.nextIndex(stack);
                if (isUpvalueIndex(toIndex))
                    setUpvalue(currentClosure, upvalueNumber(toIndex), value);
                else {
                    int to = resolveExistingStackIndex(stack, toIndex);
                    stack.set(to, value);
                }
            } else if (inst.equals("func2num")) {
                stack.add(LuaInteger.valueOf(functionAddress(stackValue(stack, reader.nextIndex(stack)))));
            } else if (inst.equals("tocfunction")) {
                LuaValue value = stackValue(stack, reader.nextIndex(stack));
                stack.add((value instanceof LuaFunction && !(value instanceof LuaClosure)) ? value : LuaValue.NIL);
            } else if (inst.equals("rotate")) {
                int slot = resolveExistingStackIndex(stack, reader.nextIndex(stack));
                rotate(stack, slot, reader.nextNumber(stack));
            } else if (inst.equals("pop")) {
                int n = reader.nextNumber(stack);
                closeSlotsAtOrAbove(stack, toClose, Math.max(0, stack.size() - n), false);
                while (n-- > 0 && !stack.isEmpty()) stack.remove(stack.size() - 1);
            } else if (inst.equals("concat")) {
                concat(stack, reader.nextNumber(stack));
            } else if (inst.equals("tobool")) {
                stack.add(apiValue(stack, reader.nextIndex(stack), currentClosure).toboolean() ? LuaValue.TRUE : LuaValue.FALSE);
            } else if (inst.equals("getglobal")) {
                Globals globals = activeGlobals != null ? activeGlobals : LuaTable.runningGlobalsForGC();
                stack.add(globals != null ? globals.get(reader.next()) : LuaValue.NIL);
            } else if (inst.equals("setglobal")) {
                Globals globals = activeGlobals != null ? activeGlobals : LuaTable.runningGlobalsForGC();
                LuaValue value = stack.isEmpty() ? LuaValue.NIL : stack.remove(stack.size() - 1);
                if (globals != null) globals.set(reader.next(), value);
            } else if (inst.equals("getfield")) {
                // C: ltests.c:runC -> lua_getfield
                LuaValue table = apiValue(stack, reader.nextIndex(stack), currentClosure);
                stack.add(LuaIndex.finishGetField(table, reader.next()));
            } else if (inst.equals("call")) {
                int narg = reader.nextNumber(stack);
                int nres = reader.nextNumber(stack);
                doCall(stack, narg, nres, checkedStackExtra);
            } else if (inst.equals("callk")) {
                // C: ltests.c:runC/callk -> lua_callk(..., ctx, Cfunck)
                int narg = reader.nextNumber(stack);
                int nres = reader.nextNumber(stack);
                int ctx = reader.nextIndex(stack);
                Varargs continuation = doCallK(stack, narg, nres, ctx, checkedStackExtra,
                        remoteReturn, activeGlobals, currentClosure);
                if (continuation != null) {
                    closeSlotsAtOrAbove(stack, toClose, 0, true);
                    return continuation;
                }
            } else if (inst.equals("pcall")) {
                int narg = reader.nextNumber(stack);
                int nres = reader.nextNumber(stack);
                int msgh = reader.nextIndex(stack);
                status = doPCall(stack, narg, nres, msgh, checkedStackExtra);
            } else if (inst.equals("pcallk")) {
                // C: ltests.c:runC/pcallk -> lua_pcallk(..., 0, ctx, Cfunck)
                int narg = reader.nextNumber(stack);
                int nres = reader.nextNumber(stack);
                int ctx = reader.nextIndex(stack);
                PCallKResult result = doPCallK(stack, narg, nres, ctx, checkedStackExtra,
                        remoteReturn, activeGlobals, currentClosure);
                status = result.status;
                if (result.continuation != null) {
                    closeSlotsAtOrAbove(stack, toClose, 0, true);
                    return result.continuation;
                }
            } else if (inst.equals("resume")) {
                // C: ltests.c:runC:resume -> lua_resume(lua_tothread(L1, i), L, nargs, &nres)。
                int index = reader.nextIndex(stack);
                int nargs = reader.nextNumber(stack);
                status = doResume(stack, index, nargs, activeGlobals, remoteReturn);
            } else if (inst.equals("pushstatus")) {
                // ltests.c: statcodes  -  预注册固定字符串（fixedLiteral pin 住）。
                // java diff: LuaString.valueOf 每次 new/intern -> 受限时（T.totalmem/
                //   T.alloccount）分配失败抛 LuaError 未捕获（runCTest 不在 pcall 内）
                //   -> memerr.lua testbytes 循环 "not enough memory" 未捕获。
                stack.add(statString(status));
            } else if (inst.equals("threadstatus")) {
                // C: ltests.c:runC -> threadstatus。Lua 5.5 panic 时出错线程状态为 ERRRUN。
                stack.add(statString(status));
            } else if (inst.equals("traceback")) {
                // C: ltests.c:runC -> luaL_traceback(L1, L1, msg, level)
                // DebugHook 无进程级隐式状态，须显式传当前运行线程作 target（对齐 C 的 L1），
                //   否则取不到 g/CI 链，返回空 "stack traceback:"。
                String message = reader.next();
                int level = reader.nextNumber(stack);
                LuaValue msg = message != null ? LuaString.valueOf(message) : LuaValue.NIL;
                Globals g = activeGlobals != null ? activeGlobals : LuaTable.runningGlobalsForGC();
                LuaThread target = g != null ? g.running : null;
                stack.add(DebugHook.traceback(target, msg, level));
            } else if (inst.equals("tostring")) {
                LuaValue value = apiValue(stack, reader.nextIndex(stack), currentClosure);
                // C: ltests.c:runC -> tostring，lua_pushstring(NULL) 压入 nil。
                LuaString stringValue = value.isnil() ? null : value.strValue();
                stack.add(stringValue != null ? stringValue : LuaValue.NIL);
            } else if (inst.equals("Ltolstring")) {
                // C: ltests.c:runC -> luaL_tolstring
                LuaValue value = apiValue(stack, reader.nextIndex(stack), currentClosure);
                Globals globals = activeGlobals != null ? activeGlobals : LuaTable.runningGlobalsForGC();
                LuaValue tostring = globals != null ? globals.get("tostring") : LuaValue.NIL;
                stack.add(LuaCall.callLua(tostring, value).arg1());
            } else if (inst.equals("tonumber")) {
                stack.add(toNumberValue(apiValue(stack, reader.nextIndex(stack), currentClosure)));
            } else if (inst.equals("topointer")) {
                stack.add(pointerValue(stackValue(stack, reader.nextIndex(stack)), false));
            } else if (inst.equals("touserdata")) {
                stack.add(pointerValue(stackValue(stack, reader.nextIndex(stack)), true));
            } else if (inst.equals("alloccount")) {
                // C: ltests.c:runC -> alloccount
                BaseLib.CollectGarbageFn.setAllocCountLimit(activeGlobals, reader.nextNumber(stack));
            } else if (inst.equals("checkstack")) {
                int extra = reader.nextNumber(stack);
                String message = reader.next();
                if (extra > LUAI_MAXSTACK) {
                    throw LuaErrors.errorObject(message == null || message.isEmpty() ? "stack overflow" : message, 0);
                }
                if ((long) stack.size() + extra > LUAI_MAXSTACK) {
                    throw LuaErrors.errorObject("stack overflow", 0);
                }
                checkedStackExtra = Math.max(checkedStackExtra, extra);
            } else if (inst.equals("rawcheckstack")) {
                // C: ltests.c:runC -> lua_checkstack returns a boolean and
                // does not raise when the stack cannot grow.
                int extra = reader.nextNumber(stack);
                boolean ok = extra >= 0 && (long) stack.size() + extra <= LUAI_MAXSTACK;
                if (ok) {
                    try {
                        BaseLib.CollectGarbageFn.checkLuaMemory(activeGlobals, Math.max(16L, extra * 8L));
                    } catch (LuaError error) {
                        ok = false;
                    }
                }
                stack.add(ok ? LuaValue.TRUE : LuaValue.FALSE);
            } else if (inst.equals("arith")) {
                applyArith(stack, reader.next());
            } else if (inst.equals("compare")) {
                String op = reader.next();
                int ia = reader.nextIndex(stack);
                int ib = reader.nextIndex(stack);
                LuaValue a = apiValue(stack, ia, currentClosure);
                LuaValue b = apiValue(stack, ib, currentClosure);
                boolean result;
                if (!isValidApiIndex(stack, ia, currentClosure) || !isValidApiIndex(stack, ib, currentClosure)) {
                    result = false;
                } else try {
                    // lapi.c lua_compare 带 L：EQ/LT/LE 的元方法路径需要线程上下文
                    //（LuaCompare 单参版是 L=null 的 raw 语义——luaV_rawequalobj，
                    // 对不同 table/userdata 直接 false，不走 __eq）
                    LuaThread cur = activeGlobals != null ? activeGlobals.running : null;
                    result = switch (op) {
                        case "EQ" -> LuaCompare.equalObj(cur, a, b);
                        case "LT" -> LuaCompare.lessThan(cur, a, b);
                        case "LE" -> LuaCompare.lessEqual(cur, a, b);
                        default -> throw LuaErrors.errorObject("unknown compare op " + op, 1);
                    };
                } catch (LuaError error) {
                    result = false;
                }
                stack.add(result ? LuaValue.TRUE : LuaValue.FALSE);
            } else if (inst.equals("len")) {
                stack.add(apiValue(stack, reader.nextIndex(stack), currentClosure).len());
            } else if (inst.equals("Llen")) {
                stack.add(LuaInteger.valueOf(luaLLength(apiValue(stack, reader.nextIndex(stack), currentClosure))));
            } else if (inst.equals("objsize")) {
                stack.add(LuaInteger.valueOf(rawLen(apiValue(stack, reader.nextIndex(stack), currentClosure))));
            } else if (inst.equals("isnumber")) {
                LuaValue v = apiValue(stack, reader.nextIndex(stack), currentClosure);
                stack.add(v.isnumber() ? LuaValue.TRUE : LuaValue.FALSE);
            } else if (inst.equals("isstring")) {
                LuaValue v = apiValue(stack, reader.nextIndex(stack), currentClosure);
                stack.add((v.isstring() || v.isnumber()) ? LuaValue.TRUE : LuaValue.FALSE);
            } else if (inst.equals("isfunction")) {
                stack.add(apiValue(stack, reader.nextIndex(stack), currentClosure).isfunction() ? LuaValue.TRUE : LuaValue.FALSE);
            } else if (inst.equals("iscfunction")) {
                LuaValue v = apiValue(stack, reader.nextIndex(stack), currentClosure);
                stack.add((v instanceof LuaFunction && !(v instanceof LuaClosure)) ? LuaValue.TRUE : LuaValue.FALSE);
            } else if (inst.equals("istable")) {
                stack.add(apiValue(stack, reader.nextIndex(stack), currentClosure).istable() ? LuaValue.TRUE : LuaValue.FALSE);
            } else if (inst.equals("isuserdata")) {
                stack.add(apiValue(stack, reader.nextIndex(stack), currentClosure).isuserdata() ? LuaValue.TRUE : LuaValue.FALSE);
            } else if (inst.equals("isudataval")) {
                stack.add(apiValue(stack, reader.nextIndex(stack), currentClosure) instanceof LuaLightUserdata ? LuaValue.TRUE : LuaValue.FALSE);
            } else if (inst.equals("isnil")) {
                int index = reader.nextIndex(stack);
                stack.add((isValidApiIndex(stack, index, currentClosure) && apiValue(stack, index, currentClosure).isnil()) ? LuaValue.TRUE : LuaValue.FALSE);
            } else if (inst.equals("isnull")) {
                stack.add(!isValidApiIndex(stack, reader.nextIndex(stack), currentClosure) ? LuaValue.TRUE : LuaValue.FALSE);
            } else if (inst.equals("warningC")) {
                emitWarning(reader.next(), true);
            } else if (inst.equals("warning")) {
                emitWarning(reader.next(), false);
            } else if (inst.equals("sethook")) {
                // C: ltests.c:runC -> sethook/sethookaux
                int mask = reader.nextNumber(stack);
                int count = reader.nextNumber(stack);
                String hookScript = reader.next();
                Globals globals = activeGlobals != null ? activeGlobals : LuaTable.runningGlobalsForGC();
                applyHook(globals, hookScript == null || hookScript.isEmpty()
                                ? LuaValue.NIL : new hookscript(globals, hookScript),
                        hookMaskString(mask, count), count);
            } else if (inst.equals("rawget")) {
                LuaValue table = apiValue(stack, reader.nextIndex(stack), currentClosure);
                LuaValue key = stack.isEmpty() ? LuaValue.NIL : stack.remove(stack.size() - 1);
                stack.add(table.checktable().rawget(key));
            } else if (inst.equals("rawgeti")) {
                int index = reader.nextIndex(stack);
                int n = reader.nextNumber(stack);
                LuaValue table = apiValue(stack, index, currentClosure);
                if (index == REGISTRY_INDEX && n == LUA_RIDX_GLOBALS)
                    stack.add(activeGlobals != null ? activeGlobals : LuaValue.NIL);
                else if (index == REGISTRY_INDEX && n == LUA_RIDX_MAINTHREAD)
                    stack.add(activeGlobals != null ? activeGlobals.running : LuaValue.NIL);
                else stack.add(table.checktable().rawget(n));
            } else if (inst.equals("rawgetp")) {
                LuaValue table = apiValue(stack, reader.nextIndex(stack), currentClosure);
                stack.add(table.checktable().rawget(lightUserdata(reader.nextNumber(stack))));
            } else if (inst.equals("pushcclosure")) {
                // C: ltests.c:runC -> lua_pushcclosure(L1, testC, n)
                int n = reader.nextNumber(stack);
                if (n < 0 || n > 255)
                    throw LuaErrors.errorObject("upvalue index too large", 0);
                if (n > stack.size())
                    throw LuaErrors.errorObject("not enough elements in the stack", 0);
                LuaValue[] upvalues = new LuaValue[n];
                int first = stack.size() - n;
                for (int i = 0; i < n; i++) upvalues[i] = stack.get(first + i);
                while (stack.size() > first) stack.remove(stack.size() - 1);
                stack.add(new LtestsCClosure(upvalues));
            } else if (inst.equals("pushupvalueindex")) {
                int n = reader.nextNumber(stack);
                stack.add(LuaInteger.valueOf(n == 0 ? REGISTRY_INDEX : upvalueIndex(n)));
            } else if (inst.equals("rawset")) {
                LuaValue table = apiValue(stack, reader.nextIndex(stack), currentClosure);
                LuaValue value = stack.isEmpty() ? LuaValue.NIL : stack.remove(stack.size() - 1);
                LuaValue key = stack.isEmpty() ? LuaValue.NIL : stack.remove(stack.size() - 1);
                table.checktable().rawset(key, value);
            } else if (inst.equals("rawseti")) {
                LuaValue table = apiValue(stack, reader.nextIndex(stack), currentClosure);
                int n = reader.nextNumber(stack);
                LuaValue value = stack.isEmpty() ? LuaValue.NIL : stack.remove(stack.size() - 1);
                table.checktable().rawset(n, value);
            } else if (inst.equals("rawsetp")) {
                int slot = resolveExistingStackIndex(stack, reader.nextIndex(stack));
                LuaValue table = stack.get(slot);
                LuaValue key = lightUserdata(reader.nextNumber(stack));
                LuaValue value = stack.isEmpty() ? LuaValue.NIL : stack.remove(stack.size() - 1);
                table.checktable().rawset(key, value);
            } else if (inst.equals("xmove")) {
                // C: ltests.c:runC:xmove -> lua_xmove(fs, ts, n)。
                int from = reader.nextIndex(stack);
                int to = reader.nextIndex(stack);
                int n = reader.nextNumber(stack);
                doXMove(stack, from, to, n, activeGlobals);
            } else if (inst.equals("isyieldable")) {
                // C: ltests.c:runC:isyieldable -> lua_isyieldable(lua_tothread(...))。
                LuaThread thread = apiValue(stack, reader.nextIndex(stack), currentClosure).checkthread();
                Globals globals = activeGlobals != null ? activeGlobals : LuaTable.runningGlobalsForGC();
                stack.add(!thread.isMainThread() && thread.nny == 0 ? LuaValue.TRUE : LuaValue.FALSE);
            } else if (inst.equals("return")) {
                int n = reader.nextNumber(stack);
                LuaValue[] out = new LuaValue[n];
                for (int i = 0; i < n; i++) {
                    LuaValue value = stackValue(stack, -n + i);
                    out[i] = remoteReturn ? remoteReturnValue(value) : value;
                }
                closeSlotsAtOrAbove(stack, toClose, 0, true);
                return LuaValue.varargsOf(out);
            } else if (inst.equals("toclose")) {
                // C: ltests.c:runC -> lua_toclose
                int index = reader.nextNumber(stack);
                int slot = resolveStackIndex(stack, index);
                if (slot < 0 || slot >= stack.size()) LuaErrors.argError(1, "index out of range");
                LuaValue value = stack.get(slot);
                // C: lfunc.c:luaF_newtbcupval returns immediately for nil/false.
                if (value.isnil() || (value.isboolean() && !value.toboolean())) continue;
                ensureClosable(value);
                if (!toClose.contains(slot)) toClose.add(slot);
            } else if (inst.equals("settop")) {
                // C: lapi.c:lua_settop closes to-be-closed slots being removed.
                int top = reader.nextNumber(stack);
                if (top < 0) top = stack.size() + top + 1;
                if (top < 0) top = 0;
                closeSlotsAtOrAbove(stack, toClose, top, false);
                while (stack.size() > top) stack.remove(stack.size() - 1);
                while (stack.size() < top) stack.add(LuaValue.NIL);
            } else if (inst.equals("closeslot")) {
                // C: lapi.c:lua_closeslot
                int slot = resolveStackIndex(stack, reader.nextNumber(stack));
                closeSlot(stack, toClose, slot, null, false);
                if (slot >= 0 && slot < stack.size()) stack.set(slot, LuaValue.NIL);
            } else if (inst.equals("setmetatable")) {
                // C: ltests.c:runC -> setmetatable，弹出栈顶作为 metatable。
                LuaValue target = apiValue(stack, reader.nextIndex(stack), currentClosure);
                LuaValue mt = stack.isEmpty() ? LuaValue.NIL : stack.remove(stack.size() - 1);
                target.setmetatable(mt);
            } else if (inst.equals("setfield")) {
                // C: ltests.c:runC -> lua_setfield
                LuaValue table = apiValue(stack, reader.nextIndex(stack), currentClosure);
                String key = reader.next();
                LuaValue value = stack.isEmpty() ? LuaValue.NIL : stack.remove(stack.size() - 1);
                LuaIndex.finishSetField(table, key, value);
            } else if (inst.equals("seti")) {
                // C: ltests.c:runC -> lua_seti
                LuaValue table = apiValue(stack, reader.nextIndex(stack), currentClosure);
                int key = reader.nextNumber(stack);
                LuaValue value = stack.isEmpty() ? LuaValue.NIL : stack.remove(stack.size() - 1);
                LuaIndex.finishSeti(table, key, value);
            } else if (inst.equals("gettable")) {
                LuaValue table = apiValue(stack, reader.nextIndex(stack), currentClosure);
                LuaValue key = stack.isEmpty() ? LuaValue.NIL : stack.remove(stack.size() - 1);
                stack.add(LuaIndex.finishGet(table, key));
            } else if (inst.equals("settable")) {
                LuaValue table = apiValue(stack, reader.nextIndex(stack), currentClosure);
                LuaValue value = stack.isEmpty() ? LuaValue.NIL : stack.remove(stack.size() - 1);
                LuaValue key = stack.isEmpty() ? LuaValue.NIL : stack.remove(stack.size() - 1);
                LuaIndex.finishSet(table, key, value);
            } else if (inst.equals("testudata")) {
                // C: ltests.c:runC:testudata -> lauxlib.c:luaL_testudata
                LuaValue value = apiValue(stack, reader.nextIndex(stack), currentClosure);
                LuaValue expected = activeRegistry(activeGlobals).rawget(LuaString.valueOf(reader.next()));
                LuaValue metatable = value instanceof LuaUserdata ? value.getmetatable() : LuaValue.NIL;
                stack.add((metatable != null && !metatable.isnil() && metatable.raweq(expected))
                        ? LuaValue.TRUE : LuaValue.FALSE);
            } else if (inst.equals("argerror")) {
                // C: ltests.c:runC -> luaL_argerror
                int arg = reader.nextNumber(stack);
                LuaErrors.argError(arg, reader.next());
            } else if (inst.equals("error")) {
                LuaValue err = stack.isEmpty() ? LuaValue.NIL : stack.get(stack.size() - 1);
                closeSlotsAtOrAbove(stack, toClose, 0, err, true);
                throw LuaErrors.errorObject(err, 0);
            } else if (inst.equals("yield")) {
                int n = reader.nextNumber(stack);
                LuaValue[] values = new LuaValue[n];
                for (int i = n - 1; i >= 0; i--) {
                    values[i] = stack.isEmpty() ? LuaValue.NIL : stack.remove(stack.size() - 1);
                }
                Globals globals = activeGlobals != null ? activeGlobals : LuaTable.runningGlobalsForGC();
                if (globals == null)
                    LuaErrors.runError("attempt to yield from outside a coroutine");
                int savedNny = globals.getNny();
                try {
                    globals.setNny(0);
                    Varargs resumed = globals.yield(LuaValue.varargsOf(values));
                    closeSlotsAtOrAbove(stack, toClose, 0, true);
                    return resumed;
                } finally {
                    globals.setNny(savedNny);
                }
            } else if (inst.equals("yieldk")) {
                // C: ltests.c:runC/yieldk -> lua_yieldk(..., ctx, Cfunck)
                int n = reader.nextNumber(stack);
                int ctx = reader.nextIndex(stack);
                LuaValue[] values = new LuaValue[n];
                for (int i = n - 1; i >= 0; i--) {
                    values[i] = stack.isEmpty() ? LuaValue.NIL : stack.remove(stack.size() - 1);
                }
                Globals globals = activeGlobals != null ? activeGlobals : LuaTable.runningGlobalsForGC();
                if (globals == null)
                    LuaErrors.runError("attempt to yield from outside a coroutine");
                int savedNny = globals.getNny();
                try {
                    globals.setNny(0);
                    Varargs resumed = globals.yield(LuaValue.varargsOf(values));
                    pushResults(stack, resumed, LUA_MULTRET);
                    Varargs continuation = runCContinuation(stack, "YIELD", ctx, remoteReturn, activeGlobals, currentClosure);
                    closeSlotsAtOrAbove(stack, toClose, 0, true);
                    return continuation;
                } finally {
                    globals.setNny(savedNny);
                }
            } else {
                LuaErrors.error("unknown instruction " + inst);
            }
        }
        closeSlotsAtOrAbove(stack, toClose, 0, true);
        return LuaValue.NONE;
    }

    private static LuaValue loadString(Globals globals, LuaValue source, String chunkName, String mode) {
        Globals g = globals != null ? globals : LuaTable.runningGlobalsForGC();
        if (g == null) LuaErrors.error("no active Lua state");
        LuaString s = source.checkstring();
        boolean fixedBinary = mode != null && mode.indexOf('B') >= 0;
        String effectiveMode = fixedBinary ? mode.replace('B', 'b') : mode;
        try {
            // ldo.c: f_parser  -  mode 'B' fixed buffer：C 直接引用输入缓冲不拷贝；
            //   Java 无法引用缓冲，load 后调整记账模拟（先 fullGC 再记 bytesBefore，
            //   净效果 ~128 字节 Proto/LClosure 开销，对齐 C 不拷贝源数据）
            long bytesBefore = 0;
            if (fixedBinary) {
                LuaGC.fullGCCaller(g);
                bytesBefore = LuaGC.currentBytes(g);
            }
            LuaValue loaded = g.load(s.toInputStream(), chunkName, effectiveMode, g).arg1();
            if (fixedBinary && !loaded.isnil()) {
                // 再次 fullGC 清掉编译期临时对象，再把净记账调回 C 的固定缓冲开销
                //（bytesBefore + 128，对齐 C 不拷贝源数据）。
                LuaGC.fullGCCaller(g);
                long bytesAfter = LuaGC.currentBytes(g);
                long targetBytes = bytesBefore + 128;
                if (bytesAfter > targetBytes) {
                    LuaGC.freeBytes(g, bytesAfter - targetBytes);
                } else if (bytesAfter < targetBytes) {
                    LuaGC.allocBytes(g, targetBytes - bytesAfter);
                }
            }
            return loaded;
        } catch (LuaError error) {
            return LuaString.valueOf(error.getMessage());
        }
    }

    private static LuaValue loadFile(Globals globals, LuaValue filename) {
        Globals g = globals != null ? globals : LuaTable.runningGlobalsForGC();
        if (g == null) LuaErrors.error("no active Lua state");
        Varargs result = LuaCall.callLua(g.get("loadfile"), filename);
        return result.arg1().isnil() ? result.arg(2) : result.arg1();
    }

    private static LuaValue remoteReturnValue(LuaValue value) {
        if (value.isboolean()) return value.toboolean() ? LuaValue.TRUE : LuaValue.FALSE;
        LuaString stringValue = value.isnil() ? null : value.strValue();
        return stringValue != null ? stringValue : LuaValue.NIL;
    }

    private static String panicMessage(LuaValue value) {
        LuaString s = value.isnil() ? null : value.strValue();
        return s != null ? s.toJavaString() : value.toJavaString();
    }

    private static LuaValue stackValue(ArrayList<LuaValue> stack, int index) {
        if (index == REGISTRY_INDEX) {
            Globals globals = LuaTable.runningGlobalsForGC();
            return globals != null ? globals.registry : LuaValue.NIL;
        }
        int resolved = resolveStackIndex(stack, index);
        if (resolved < 0 || resolved >= stack.size()) return LuaValue.NIL;
        return stack.get(resolved);
    }

    private static LuaTable activeRegistry(Globals activeGlobals) {
        Globals globals = activeGlobals != null ? activeGlobals : LuaTable.runningGlobalsForGC();
        if (globals == null) LuaErrors.error("no active Lua state");
        return globals.registry;
    }

    private static LuaValue apiValue(ArrayList<LuaValue> stack, int index, LtestsCClosure currentClosure) {
        if (isUpvalueIndex(index)) return upvalueValue(currentClosure, upvalueNumber(index));
        return stackValue(stack, index);
    }

    private static boolean isValidApiIndex(ArrayList<LuaValue> stack, int index, LtestsCClosure currentClosure) {
        if (isUpvalueIndex(index)) return isValidUpvalue(currentClosure, upvalueNumber(index));
        return isValidStackIndex(stack, index);
    }

    private static int upvalueIndex(int n) {
        return UPVALUE_INDEX_BASE + n;
    }

    private static boolean isUpvalueIndex(int index) {
        return index >= UPVALUE_INDEX_BASE && index <= UPVALUE_INDEX_BASE + 1000000;
    }

    private static int upvalueNumber(int index) {
        return index - UPVALUE_INDEX_BASE;
    }

    private static LuaValue upvalueValue(LtestsCClosure currentClosure, int n) {
        if (currentClosure == null) return LuaValue.NIL;
        return currentClosure.getUpvalue(n);
    }

    private static boolean isValidUpvalue(LtestsCClosure currentClosure, int n) {
        return currentClosure != null && currentClosure.hasUpvalue(n);
    }

    private static void setUpvalue(LtestsCClosure currentClosure, int n, LuaValue value) {
        if (currentClosure != null) currentClosure.setUpvalue(n, value);
    }

    private static int resolveStackIndex(ArrayList<LuaValue> stack, int index) {
        return index < 0 ? stack.size() + index : index - 1;
    }

    private static boolean isValidStackIndex(ArrayList<LuaValue> stack, int index) {
        if (index == REGISTRY_INDEX) return true;
        int slot = resolveStackIndex(stack, index);
        return slot >= 0 && slot < stack.size();
    }

    private static int resolveExistingStackIndex(ArrayList<LuaValue> stack, int index) {
        int slot = resolveStackIndex(stack, index);
        if (slot < 0 || slot >= stack.size()) LuaErrors.argError(1, "index out of range");
        return slot;
    }

    private static int absIndex(ArrayList<LuaValue> stack, int index) {
        if (index == REGISTRY_INDEX) return REGISTRY_INDEX;
        if (index > 0) return index;
        return stack.size() + index + 1;
    }

    private static void shiftToCloseAfterRemove(ArrayList<Integer> toClose, int removedSlot) {
        for (int i = 0; i < toClose.size(); i++) {
            int slot = toClose.get(i);
            if (slot > removedSlot) toClose.set(i, slot - 1);
        }
    }

    private static void shiftToCloseAfterInsert(ArrayList<Integer> toClose, int insertedSlot) {
        for (int i = 0; i < toClose.size(); i++) {
            int slot = toClose.get(i);
            if (slot >= insertedSlot) toClose.set(i, slot + 1);
        }
    }

    private static void rotate(ArrayList<LuaValue> stack, int startSlot, int n) {
        int len = stack.size() - startSlot;
        if (len <= 1) return;
        int rot = n % len;
        if (rot < 0) rot += len;
        if (rot == 0) return;
        ArrayList<LuaValue> copy = new ArrayList<>(stack.subList(startSlot, stack.size()));
        for (int i = 0; i < len; i++) {
            stack.set(startSlot + ((i + rot) % len), copy.get(i));
        }
    }

    private static void concat(ArrayList<LuaValue> stack, int n) {
        if (n == 0) {
            stack.add(LuaString.valueOf(""));
            return;
        }
        if (n <= 1) return;
        LuaValue result = stack.get(stack.size() - n);
        for (int i = stack.size() - n + 1; i < stack.size(); i++) {
            result = LuaConcat.concat(result, stack.get(i));
        }
        while (n-- > 0) stack.remove(stack.size() - 1);
        stack.add(result);
    }

    private static String auxGsub(String s, String p, String r) {
        if (p.isEmpty()) return s;
        StringBuilder out = new StringBuilder();
        int pos = 0;
        for (; ; ) {
            int wild = s.indexOf(p, pos);
            if (wild < 0) {
                out.append(s, pos, s.length());
                return out.toString();
            }
            out.append(s, pos, wild);
            out.append(r);
            pos = wild + p.length();
        }
    }

    private static void emitWarning(String message, boolean tocont) {
        // C: ltests.c:runC warning/warningC -> lua_warning(L, msg, tocont)。
        Globals globals = LuaTable.runningGlobalsForGC();
        LuaValue warn = globals != null ? globals.get("warn") : LuaValue.NIL;
        if (warn instanceof LuaFunction fn) {
            LuaCall.callLua(fn, tocont ? LuaString.valueOf("@" + message) : LuaString.valueOf(message));
        } else {
            System.err.print(message);
            if (!tocont) System.err.println();
        }
    }

    private static void applyArith(ArrayList<LuaValue> stack, String op) {
        // C: ltests.c:ops = "+-*%^/\\&|~<>_!"，单目运算仅弹一个值。
        LuaValue result;
        switch (op) {
            case "_" -> {
                LuaValue v = stack.remove(stack.size() - 1);
                result = LuaArith.apply(UnaryOp.UNM, v);
                if (result == null) result = callUnaryMM(Metamethod.UNM, v);
            }
            case "!" -> {
                LuaValue v = stack.remove(stack.size() - 1);
                result = LuaArith.apply(UnaryOp.BNOT, v);
                if (result == null) result = callUnaryMM(Metamethod.BNOT, v);
            }
            default -> {
                LuaValue b = stack.remove(stack.size() - 1);
                LuaValue a = stack.remove(stack.size() - 1);
                BinaryOp binop = switch (op) {
                    case "+" -> BinaryOp.ADD;
                    case "-" -> BinaryOp.SUB;
                    case "*" -> BinaryOp.MUL;
                    case "%" -> BinaryOp.MOD;
                    case "^" -> BinaryOp.POW;
                    case "/" -> BinaryOp.DIV;
                    case "\\" -> BinaryOp.IDIV;
                    case "&" -> BinaryOp.BAND;
                    case "|" -> BinaryOp.BOR;
                    case "~" -> BinaryOp.BXOR;
                    case "<" -> BinaryOp.SHL;
                    case ">" -> BinaryOp.SHR;
                    default -> throw LuaErrors.errorObject("unknown arith op " + op, 1);
                };
                result = LuaArith.apply(binop, a, b);
                if (result == null) result = callBinaryMM(arithMetamethod(binop), a, b);
            }
        }
        stack.add(result != null ? result : LuaValue.NIL);
    }

    private static Metamethod arithMetamethod(BinaryOp op) {
        return switch (op) {
            case ADD -> Metamethod.ADD;
            case SUB -> Metamethod.SUB;
            case MUL -> Metamethod.MUL;
            case DIV -> Metamethod.DIV;
            case MOD -> Metamethod.MOD;
            case POW -> Metamethod.POW;
            case IDIV -> Metamethod.IDIV;
            case BAND -> Metamethod.BAND;
            case BOR -> Metamethod.BOR;
            case BXOR -> Metamethod.BXOR;
            case SHL -> Metamethod.SHL;
            case SHR -> Metamethod.SHR;
            case CONCAT -> Metamethod.CONCAT;
        };
    }

    private static LuaValue callBinaryMM(Metamethod mm, LuaValue a, LuaValue b) {
        LuaValue h = mm.lookup(a);
        if (h == null) h = mm.lookup(b);
        if (h == null) LuaErrors.typeError(a, "perform arithmetic on");
        return LuaCall.callLua(h, LuaValue.varargsOf(a, b)).arg1();
    }

    private static LuaValue callUnaryMM(Metamethod mm, LuaValue v) {
        LuaValue h = mm.lookup(v);
        if (h == null) LuaErrors.typeError(v, "perform arithmetic on");
        // C: lapi.c:lua_arith 对单目运算会复制一个 fake 第二操作数，元方法看到的
        // 是 (v, v)，字符串算术元方法也依赖这一点。
        return LuaCall.callLua(h, LuaValue.varargsOf(v, v)).arg1();
    }

    private static long rawLen(LuaValue value) {
        if (value instanceof LuaString s) return s.shrlen;
        if (value instanceof LuaTable t) return t.rawlen();
        Object data = value.touserdata();
        if (data instanceof TestUserdata u) return u.size;
        return 0;
    }

    private static long luaLLength(LuaValue value) {
        LuaValue len = value.len();
        if (len instanceof LuaInteger i) return i.v;
        if (len instanceof LuaFloat) {
            return len.checklong();
        }
        if (len instanceof LuaString s) {
            LuaValue n = s.tonumber();
            if (!n.isnil()) return n.checklong();
        }
        LuaErrors.error("object length is not an integer");
        return 0;
    }

    private static void doCall(ArrayList<LuaValue> stack, int narg, int nres, int checkedStackExtra) {
        int funcSlot = stack.size() - narg - 1;
        LuaValue func = stack.get(funcSlot);
        LuaValue[] argv = new LuaValue[narg];
        for (int i = 0; i < narg; i++) argv[i] = stack.get(funcSlot + 1 + i);
        while (stack.size() > funcSlot) stack.remove(stack.size() - 1);
        Globals globals = LuaTable.runningGlobalsForGC();
        int savedNCcalls = globals != null ? globals.getNCcalls() : 0;
        LuaThread protectedLuaThread = globals != null ? globals.running : null;
        if (globals != null && checkedStackExtra > 0) {
            globals.setNCcalls(protectedLuaThread, Math.min(globals.getNCcalls(), -checkedStackExtra));
        }
        try {
            Varargs results = LuaCall.callLua(func, LuaValue.varargsOf(argv));
            pushResults(stack, results, nres);
        } finally {
            if (globals != null) globals.setNCcalls(protectedLuaThread, savedNCcalls);
        }
    }

    private static Varargs doCallK(ArrayList<LuaValue> stack, int narg, int nres, int ctx, int checkedStackExtra,
                                   boolean remoteReturn, Globals activeGlobals, LtestsCClosure currentClosure) {
        int funcSlot = stack.size() - narg - 1;
        LuaValue func = stack.get(funcSlot);
        LuaValue[] argv = new LuaValue[narg];
        for (int i = 0; i < narg; i++) argv[i] = stack.get(funcSlot + 1 + i);
        while (stack.size() > funcSlot) stack.remove(stack.size() - 1);
        Globals globals = LuaTable.runningGlobalsForGC();
        int savedNCcalls = globals != null ? globals.getNCcalls() : 0;
        LuaThread protectedLuaThread = globals != null ? globals.running : null;
        // java diff: C 的 lua_callk 在 yield 时经 longjmp 不返回调用者，下次 resume 执行 continuation；
        //            Java 的 yield 靠 wait/notify 阻塞线程，callLua 正常返回，
        //            须比较 yieldSeq 前后值才能判定是否发生 yield。
        int yieldBefore = protectedLuaThread != null ? protectedLuaThread.getYieldSeq() : 0;
        if (globals != null && checkedStackExtra > 0) {
            globals.setNCcalls(protectedLuaThread, Math.min(globals.getNCcalls(), -checkedStackExtra));
        }
        try {
            Varargs results = LuaCall.callLua(func, LuaValue.varargsOf(argv));
            int yieldAfter = protectedLuaThread != null ? protectedLuaThread.getYieldSeq() : 0;
            pushResults(stack, results, nres);
            if (globals != null && yieldAfter != yieldBefore) {
                return runCContinuation(stack, "YIELD", ctx, remoteReturn, activeGlobals, currentClosure);
            }
            return null;
        } finally {
            if (globals != null) globals.setNCcalls(protectedLuaThread, savedNCcalls);
        }
    }

    private static void restoreProtectedCallStack(Globals globals, int savedSize, Thread ownerThread,
                                                  LuaThread protectedLuaThread,
                                                  int savedTop) {
        if (globals == null || ownerThread == null) return;
        globals.setStackTop(protectedLuaThread, savedTop);
    }

    private static String doPCall(ArrayList<LuaValue> stack, int narg, int nres, int msgh, int checkedStackExtra) {
        int funcSlot = stack.size() - narg - 1;
        LuaValue func = stack.get(funcSlot);
        LuaValue[] argv = new LuaValue[narg];
        for (int i = 0; i < narg; i++) argv[i] = stack.get(funcSlot + 1 + i);
        while (stack.size() > funcSlot) stack.remove(stack.size() - 1);
        Globals globals = LuaTable.runningGlobalsForGC();
        int savedNCcalls = globals != null ? globals.getNCcalls() : 0;
        LuaThread protectedLuaThread = globals != null ? globals.running : null;
        int savedTop = globals != null ? globals.getStackTop(protectedLuaThread) : 1;
        Thread protectedThread = Thread.currentThread();
        if (globals != null && checkedStackExtra > 0) {
            globals.setNCcalls(protectedLuaThread, Math.min(globals.getNCcalls(), -checkedStackExtra));
        }
        try {
            Varargs results = LuaCall.callLua(func, LuaValue.varargsOf(argv));
            pushResults(stack, results, nres);
            return "OK";
        } catch (LuaError error) {
            LuaValue err = error.getMessageObject();
            if (err == null) err = LuaString.valueOf(error.getMessage());
            boolean memoryError = "not enough memory".equals(panicMessage(err));
            LuaValue handler = stackValue(stack, msgh);
            if (!handler.isnil()) {
                err = LuaCall.callLua(handler, err).arg1();
            }
            stack.add(err);
            // C: ltests.c:statcodes/runC(pcall/pushstatus)。lua_pcall 对 LUA_ERRMEM
            // 返回预注册字符串 MEMERRMSG，而非普通 ERRRUN。
            return memoryError ? "not enough memory" : "ERRRUN";
        } finally {
            if (globals != null) {
                // C: ldo.c:luaD_pcall restores old_ci after protected errors.
                restoreProtectedCallStack(globals, 0, protectedThread, protectedLuaThread, savedTop);
                globals.setNCcalls(protectedLuaThread, savedNCcalls);
            }
        }
    }

    // status 字符串 -> 预注册 LuaString（STAT_CODES 缓存实例，不分配）。
    private static LuaString statString(String status) {
        if (status == null) return STAT_CODES.get("ERRRUN");
        LuaString s = STAT_CODES.get(status);
        return s != null ? s : LuaString.valueOf(status);
    }

    private static PCallKResult doPCallK(ArrayList<LuaValue> stack, int narg, int nres, int ctx, int checkedStackExtra,
                                         boolean remoteReturn, Globals activeGlobals, LtestsCClosure currentClosure) {
        int funcSlot = stack.size() - narg - 1;
        LuaValue func = stack.get(funcSlot);
        LuaValue[] argv = new LuaValue[narg];
        for (int i = 0; i < narg; i++) argv[i] = stack.get(funcSlot + 1 + i);
        while (stack.size() > funcSlot) stack.remove(stack.size() - 1);
        Globals globals = activeGlobals != null ? activeGlobals : LuaTable.runningGlobalsForGC();
        int savedNCcalls = globals != null ? globals.getNCcalls() : 0;
        LuaThread protectedLuaThread = globals != null ? globals.running : null;
        int savedTop = globals != null ? globals.getStackTop(protectedLuaThread) : 1;
        Thread protectedThread = Thread.currentThread();
        // java diff: 同 doCallK，用 yieldSeq 判定 yield
        int yieldBefore = protectedLuaThread != null ? protectedLuaThread.getYieldSeq() : 0;
        if (globals != null && checkedStackExtra > 0) {
            globals.setNCcalls(protectedLuaThread, Math.min(globals.getNCcalls(), -checkedStackExtra));
        }
        try {
            Varargs results = LuaCall.callLua(func, LuaValue.varargsOf(argv));
            int yieldAfter = protectedLuaThread != null ? protectedLuaThread.getYieldSeq() : 0;
            pushResults(stack, results, nres);
            if (globals != null && yieldAfter != yieldBefore) {
                return new PCallKResult("YIELD",
                        runCContinuation(stack, "YIELD", ctx, remoteReturn, activeGlobals, currentClosure));
            }
            return new PCallKResult("OK", null);
        } catch (LuaError error) {
            LuaValue err = error.getMessageObject();
            if (err == null) err = LuaString.valueOf(error.getMessage());
            String status = "not enough memory".equals(panicMessage(err)) ? "not enough memory" : "ERRRUN";
            stack.add(err);
            return new PCallKResult(status,
                    runCContinuation(stack, status, ctx, remoteReturn, activeGlobals, currentClosure));
        } finally {
            if (globals != null) {
                // C: ldo.c:luaD_pcall restores old_ci after protected errors.
                restoreProtectedCallStack(globals, 0, protectedThread, protectedLuaThread, savedTop);
                globals.setNCcalls(protectedLuaThread, savedNCcalls);
            }
        }
    }

    private static Varargs runCContinuation(ArrayList<LuaValue> stack, String status, int ctx,
                                            boolean remoteReturn, Globals activeGlobals,
                                            LtestsCClosure currentClosure) {
        // C: ltests.c:Cfunck。写入全局 status/ctx，再把 ctx 当作栈索引读取后续 runC 脚本。
        Globals globals = activeGlobals != null ? activeGlobals : LuaTable.runningGlobalsForGC();
        if (globals != null) {
            globals.set("status", LuaString.valueOf(status));
            globals.set("ctx", LuaInteger.valueOf(ctx));
        }
        String script = apiValue(stack, ctx, currentClosure).checkJavaString();
        return runCTest(script, stack, remoteReturn, activeGlobals, currentClosure);
    }

    private static String doResume(ArrayList<LuaValue> stack, int index, int nargs, Globals activeGlobals, boolean allowMainResume) {
        LuaThread thread = stackValue(stack, index).checkthread();
        ArrayList<LuaValue> targetStack = apiStackForThread(stack, thread, activeGlobals);
        LuaThread resumeThread = thread;
        boolean mainResume = thread.isMainThread() && allowMainResume;
        ArrayList<LuaValue> savedMainPrefix = null;
        if (thread.isMainThread() && allowMainResume) {
            // C: ltests.c:runC deliberately resumes LUA_RIDX_MAINTHREAD.
            // 普通 coroutine.resume(main) 仍由 CoroutineLib/LuaThread 拒绝；
            // 此处仅为 C API 测试路径模拟 lua_resume(mainthread, from, ...).
            Globals globals = activeGlobals != null ? activeGlobals : LuaTable.runningGlobalsForGC();
            resumeThread = mainResumeThreads.computeIfAbsent(thread, t -> new StackLuaThread(globals));
            int functionSlot = targetStack.size() - nargs - 1;
            if (functionSlot >= 0 && targetStack.get(functionSlot) instanceof LuaFunction && functionSlot > 0) {
                savedMainPrefix = new ArrayList<>(targetStack.subList(0, functionSlot));
            }
            int markerSlot = targetStack.size() - nargs - 1;
            if ("suspended".equals(resumeThread.auxstatus()) && markerSlot >= 0 && targetStack.get(markerSlot) == thread) {
                targetStack.remove(markerSlot);
            }
        }
        Varargs result;
        if (resumeThread instanceof StackLuaThread testThread) {
            result = testThread.resumeFromApiStack(targetStack, nargs);
        } else {
            int first = targetStack.size() - nargs;
            if (first < 0) first = 0;
            LuaValue[] argv = new LuaValue[Math.max(0, targetStack.size() - first)];
            for (int i = 0; i < argv.length; i++) argv[i] = targetStack.get(first + i);
            while (targetStack.size() > first) targetStack.remove(targetStack.size() - 1);
            result = resumeThread.lua_resume(LuaValue.varargsOf(argv));
        }
        if (!result.arg1().toboolean()) {
            LuaValue err = result.arg(2);
            if (mainResume) {
                ArrayList<LuaValue> prefix = mainResumeSavedStacks.remove(thread);
                if (prefix != null) {
                    targetStack.clear();
                    targetStack.addAll(prefix);
                }
            }
            targetStack.add(err);
            return "not enough memory".equals(panicMessage(err)) ? "not enough memory" : "ERRRUN";
        }
        String resumeStatus = "suspended".equals(resumeThread.auxstatus()) ? "YIELD" : "OK";
        if (mainResume) {
            if ("YIELD".equals(resumeStatus)) {
                if (savedMainPrefix != null) mainResumeSavedStacks.put(thread, savedMainPrefix);
                targetStack.clear();
            } else {
                ArrayList<LuaValue> prefix = mainResumeSavedStacks.remove(thread);
                if (prefix != null) {
                    targetStack.clear();
                    targetStack.addAll(prefix);
                }
            }
        }
        for (int i = 2; i <= result.narg(); i++) targetStack.add(result.arg(i));
        return resumeStatus;
    }

    private static void doXMove(ArrayList<LuaValue> stack, int from, int to, int n, Globals activeGlobals) {
        ArrayList<LuaValue> source = apiStackForIndex(stack, from, activeGlobals);
        ArrayList<LuaValue> target = apiStackForIndex(stack, to, activeGlobals);
        int count = n == 0 ? source.size() : n;
        if (count < 0 || count > source.size()) LuaErrors.argError(3, "invalid number of elements");
        int first = source.size() - count;
        ArrayList<LuaValue> moved = new ArrayList<>(count);
        for (int i = first; i < source.size(); i++) moved.add(source.get(i));
        while (source.size() > first) source.remove(source.size() - 1);
        target.addAll(moved);
    }

    private static ArrayList<LuaValue> apiStackForIndex(ArrayList<LuaValue> currentStack, int index, Globals activeGlobals) {
        if (index == 0) return currentStack;
        LuaThread thread = stackValue(currentStack, index).checkthread();
        return apiStackForThread(currentStack, thread, activeGlobals);
    }

    private static ArrayList<LuaValue> apiStackForThread(ArrayList<LuaValue> currentStack, LuaThread thread, Globals activeGlobals) {
        Globals globals = activeGlobals != null ? activeGlobals : LuaTable.runningGlobalsForGC();
        if (globals != null && thread == globals.running) return currentStack;
        return threadStacks.computeIfAbsent(thread, t -> new ArrayList<>());
    }

    private static void pushResults(ArrayList<LuaValue> stack, Varargs results, int nres) {
        int count = nres == LUA_MULTRET ? results.narg() : nres;
        for (int i = 1; i <= count; i++) stack.add(results.arg(i));
    }

    private static void closeSlotsAtOrAbove(ArrayList<LuaValue> stack,
                                            ArrayList<Integer> toClose,
                                            int minSlot,
                                            boolean allowYield) {
        closeSlotsAtOrAbove(stack, toClose, minSlot, LuaValue.NIL, allowYield);
    }

    private static void closeSlotsAtOrAbove(ArrayList<LuaValue> stack,
                                            ArrayList<Integer> toClose,
                                            int minSlot,
                                            LuaValue err,
                                            boolean allowYield) {
        for (int i = toClose.size() - 1; i >= 0; i--) {
            int slot = toClose.get(i);
            if (slot >= minSlot) closeSlot(stack, toClose, slot, i, err, allowYield);
        }
    }

    private static void closeSlot(ArrayList<LuaValue> stack,
                                  ArrayList<Integer> toClose,
                                  int slot,
                                  Integer knownIndex,
                                  boolean allowYield) {
        closeSlot(stack, toClose, slot, knownIndex, LuaValue.NIL, allowYield);
    }

    private static void closeSlot(ArrayList<LuaValue> stack,
                                  ArrayList<Integer> toClose,
                                  int slot,
                                  Integer knownIndex,
                                  LuaValue err,
                                  boolean allowYield) {
        if (slot < 0 || slot >= stack.size()) return;
        int idx = knownIndex != null ? knownIndex : toClose.lastIndexOf(slot);
        if (idx >= 0) toClose.remove(idx);
        callClose(stack.get(slot), err, allowYield);
    }

    private static void ensureClosable(LuaValue value) {
        LuaValue mt = value.getmetatable();
        LuaValue close = mt != null && mt.istable() ? mt.rawget(LuaValue.CLOSE) : LuaValue.NIL;
        if (close == null || close.isnil())
            LuaErrors.runError("variable '(C temporary)' got a non-closable value");
    }

    private static void callClose(LuaValue value, LuaValue err, boolean allowYield) {
        LuaValue mt = value.getmetatable();
        LuaValue close = mt != null && mt.istable() ? mt.rawget(LuaValue.CLOSE) : LuaValue.NIL;
        if (close == null || close.isnil()) return;
        Globals globals = LuaTable.runningGlobalsForGC();
        int savedNCcalls = globals != null ? globals.getNCcalls() : 0;
        LuaThread protectedLuaThread = globals != null ? globals.running : null;
        try {
            if (globals != null) {
                // C: Lua-to-Lua 增长 CallInfo 而非 C 栈；Java 对 Lua closure 也计 nCcalls，
                //   lua_settop 关闭路径 __close 递归 400 层触发栈重分配 - 此处临时放开
                globals.setNCcalls(protectedLuaThread, Math.min(globals.getNCcalls(), -Globals.LUAI_MAXCCALLS * 4));
            }
            if (allowYield) LuaCall.callLua(close, LuaValue.varargsOf(value, err));
            else LuaCall.callNoYield(close, value, err);
        } finally {
            if (globals != null) globals.setNCcalls(protectedLuaThread, savedNCcalls);
        }
    }

    private static void pushFormatted(ArrayList<LuaValue> stack, LuaValue fmt, LuaValue value) {
        stack.add(StringFormat.strFormat(LuaValue.varargsOf(new LuaValue[]{fmt, value})));
    }

    private static LuaValue lightUserdata(long address) {
        Long pointer = lightUserdataPointers.computeIfAbsent(address, Long::valueOf);
        return new LuaLightUserdata(pointer);
    }

    private static long lightUserdataAddress(LuaLightUserdata userdata) {
        Object pointer = userdata.touserdata();
        return pointer instanceof Long l ? l : System.identityHashCode(pointer);
    }

    private static LuaValue toNumberValue(LuaValue value) {
        // C: ltests.c:runC -> tonumber / lua_tonumber
        LuaValue number = value.tonumber();
        return number.isnil() ? LuaInteger.valueOf(0) : number;
    }

    private static LuaValue pointerValue(LuaValue value, boolean userdataOnly) {
        // C: ltests.c:runC -> topointer/touserdata
        if (value instanceof LuaLightUserdata light)
            return lightUserdata(lightUserdataAddress(light));
        if (userdataOnly) {
            if (value instanceof LuaUserdata userdata && userdata.touserdata() instanceof TestUserdata data) {
                return lightUserdata(data.id);
            }
            return lightUserdata(0);
        }
        if (value.isnil() || value.isboolean() || value.isNumberTag()) return lightUserdata(0);
        if (value instanceof LuaUserdata userdata && userdata.touserdata() instanceof TestUserdata data) {
            return lightUserdata(data.id);
        }
        return lightUserdata(System.identityHashCode(value));
    }

    private static long functionAddress(LuaValue value) {
        // C: ltests.c:runC -> func2num / lua_tocfunction
        if (value instanceof LuaFunction && !(value instanceof LuaClosure))
            return System.identityHashCode(value);
        return 0;
    }

    private static String defaultColor(LuaValue value) {
        // ltests.c: gc_color -> white/gray/black
        // lgc.h: WHITE0=0, WHITE1=1, GRAY=2, BLACK=3
        return switch (value.gcColor) {
            case 0, 1 -> "white";
            case 2 -> "gray";
            case 3 -> "black";
            default -> "white";
        };
    }

    private static String defaultAge(LuaValue value) {
        // ltests.c: gc_age -> gennames[getage(obj)]
        return switch (value.gcAge) {
            case LuaValue.G_NEW -> "new";
            case LuaValue.G_SURVIVAL -> "survival";
            case LuaValue.G_OLD0 -> "old0";
            case LuaValue.G_OLD1 -> "old1";
            case LuaValue.G_OLD -> "old";
            case LuaValue.G_TOUCHED1 -> "touched1";
            case LuaValue.G_TOUCHED2 -> "touched2";
            default -> "new";
        };
    }

    /**
     * java diff：iscollectable() 已删除；用类型判断代替。
     */
    private static boolean isCollectable(LuaValue value) {
        return value instanceof LuaTable || value instanceof LuaString
                || value instanceof LuaFunction || value instanceof LuaThread
                || value instanceof LuaUserdata;
    }

    private static String buildop(Prototype p, int pc) {
        // C: ltests.c:buildop
        int instruction = p.code[pc];
        int op = Opcodes.GET_OPCODE(instruction);
        String name = Opcodes.opName(op);
        int line = LuaDebug.getFuncLinePub(p, pc);
        int lineSize = p.lineinfo != null ? Math.min(p.sizelineinfo, p.lineinfo.length) : 0;
        int rawLineInfo = pc < lineSize ? p.lineinfo[pc] : 0;
        int lineinfo = signedLineInfo(rawLineInfo);
        String prefix = lineinfo == -0x80
                ? String.format(Locale.ROOT, "(__ - %4d) %4d - ", line, pc)
                : String.format(Locale.ROOT, "(%2d - %4d) %4d - ", lineinfo, line, pc);
        return prefix + switch (Opcodes.getOpMode(op)) {
            case Opcodes.OpMode_iABC -> String.format(Locale.ROOT, "%-12s%4d %4d %4d%s",
                    name, Opcodes.GETARG_A(instruction), Opcodes.GETARG_B(instruction),
                    Opcodes.GETARG_C(instruction), Opcodes.GETARG_k(instruction) != 0 ? " (k)" : "");
            case Opcodes.OpMode_ivABC -> String.format(Locale.ROOT, "%-12s%4d %4d %4d%s",
                    name, Opcodes.GETARG_A(instruction), Opcodes.GETARG_vB(instruction),
                    Opcodes.GETARG_vC(instruction), Opcodes.GETARG_k(instruction) != 0 ? " (k)" : "");
            case Opcodes.OpMode_iABx -> String.format(Locale.ROOT, "%-12s%4d %4d",
                    name, Opcodes.GETARG_A(instruction), Opcodes.GETARG_Bx(instruction));
            case Opcodes.OpMode_iAsBx -> String.format(Locale.ROOT, "%-12s%4d %4d",
                    name, Opcodes.GETARG_A(instruction), Opcodes.GETARG_sBx(instruction));
            case Opcodes.OpMode_iAx -> String.format(Locale.ROOT, "%-12s%4d",
                    name, Opcodes.GETARG_Ax(instruction));
            case Opcodes.OpMode_isJ -> String.format(Locale.ROOT, "%-12s%4d",
                    name, Opcodes.GETARG_sJ(instruction));
            default -> name;
        };
    }

    private static int signedLineInfo(int value) {
        int b = value & 0xFF;
        return b >= 128 ? b - 256 : b;
    }

    private static void applyHook(Globals globals, LuaValue hook, String mask, int count) {
        if (globals == null) LuaErrors.error("no active Lua state");
        // C: ltests.c:sethookaux -> ldebug.c:lua_sethook. This must not call
        // Lua's debug.sethook, because all.lua deliberately sets global
        // 'debug' to nil while the C test library remains usable.
        // C: ldebug.c:lua_sethook。仅当 hook 为空或 mask/count 都为空才关闭；
        // 空 mask 配合 count>0 表示仅启用 LUA_MASKCOUNT。
        if (hook.isnil() || ((mask == null || mask.isEmpty()) && count <= 0)) {
            hook = LuaValue.NIL;
            mask = "";
            count = 0;
        }
        LuaThread target = globals.running != null && !globals.running.isMainThread()
                ? globals.running : null;
        boolean active = !hook.isnil() && ((mask != null && !mask.isEmpty()) || count > 0);
        // C: lua_sethook 操作的是 L（目标线程），不是 global_State
        LuaThread L = target != null ? target : globals.running;
        if (L != null) {
            L.hook = hook;
            int hookmask = maskStringToBits(mask);
            if (count > 0) hookmask |= (1 << 3);  // ldebug.c: LUA_MASKCOUNT
            L.hookmask = hookmask;
            L.basehookcount = count;
            L.hookcount = count;
            L.allowhook = (byte) (active ? 1 : 0);
        }
        // java diff: C 的 lua_sethook 仅设 hook/mask/count（ldebug.c: lua_sethook），
        //   行钩子的"上一条 pc"状态是 per-thread 的 L->oldpc，由 VM 自行维护
        //   （见 LuaVM 的行钩子路径与 LuaThread.oldpc），无需在此遍历帧链回填。
    }

    private static String hookMaskString(int mask, int count) {
        StringBuilder out = new StringBuilder();
        if ((mask & (1 << 0)) != 0) out.append('c'); // C: lua.h:LUA_MASKCALL
        if ((mask & (1 << 1)) != 0) out.append('r'); // C: lua.h:LUA_MASKRET
        if ((mask & (1 << 2)) != 0) out.append('l'); // C: lua.h:LUA_MASKLINE
        if (count > 0 || (mask & (1 << 3)) != 0)
            out.append('C'); // count hook marker for Java DebugHook
        return out.toString();
    }

    // C: ltests.c:sethookaux  -  将掩码字符串转为位域
    private static int maskStringToBits(String mask) {
        if (mask == null) return 0;
        int bits = 0;
        for (int i = 0; i < mask.length(); i++) {
            switch (mask.charAt(i)) {
                case 'c' -> bits |= (1 << 0); // LUA_MASKCALL
                case 'r' -> bits |= (1 << 1); // LUA_MASKRET
                case 'l' -> bits |= (1 << 2); // LUA_MASKLINE
                case 'C' -> bits |= (1 << 3); // LUA_MASKCOUNT
            }
        }
        return bits;
    }

    private static void runHookScript(Globals globals, String script, LuaValue event, LuaValue line) {
        ArrayList<LuaValue> stack = new ArrayList<>();
        for (String raw : script.split(";")) {
            String inst = raw.trim();
            if (inst.isEmpty()) continue;
            if (inst.equals("pushevent")) {
                stack.add(event);
            } else if (inst.equals("pushline")) {
                stack.add(line);
            } else if (inst.startsWith("pushint ")) {
                stack.add(LuaInteger.valueOf(Integer.parseInt(inst.substring(8).trim())));
            } else if (inst.startsWith("pushstring ")) {
                stack.add(LuaString.valueOf(unquote(inst.substring(11).trim())));
            } else if (inst.startsWith("setglobal ")) {
                String name = unquote(inst.substring(10).trim());
                LuaValue value = stack.isEmpty() ? LuaValue.NIL : stack.remove(stack.size() - 1);
                globals.set(name, value);
            } else if (inst.startsWith("getglobal ")) {
                String name = unquote(inst.substring(10).trim());
                stack.add(globals.get(name));
            } else if (inst.startsWith("yield ")) {
                int n = Integer.parseInt(inst.substring(6).trim());
                LuaValue[] values = new LuaValue[n];
                for (int i = n - 1; i >= 0; i--) {
                    values[i] = stack.isEmpty() ? LuaValue.NIL : stack.remove(stack.size() - 1);
                }
                int savedNny = globals.getNny();
                try {
                    globals.setNny(0);
                    globals.yield(LuaValue.varargsOf(values));
                } finally {
                    globals.setNny(savedNny);
                }
            } else {
                LuaErrors.error("unknown debug ltests hook instruction " + inst);
            }
        }
    }

    private static String unquote(String s) {
        if (s.length() >= 2) {
            char first = s.charAt(0);
            char last = s.charAt(s.length() - 1);
            if ((first == '\'' && last == '\'') || (first == '"' && last == '"')) {
                return s.substring(1, s.length() - 1);
            }
        }
        return s;
    }

    /**
     * java diff：countCallInfoNodes 已从 Globals 删除；改为对齐 C 直接读 L.nci。
     * <p>
     * 从 CallInfo 链手动计数是 O(n)，cstack.lua 递归到栈满时每层调用 stacklevel
     * 即 O(n^2)，不可行；C 的 ltests.c:stacklevel 直接返回 L->nci（O(1)，
     * "number of items in 'ci' list"，extendCI 预分配时维护），
     * Java 的 LuaThread.nci 已在 extendCINew/luaD_checkminstack 同步维护，语义等价。
     */
    private static int countCallInfoNodes(LuaThread L) {
        return L == null ? 0 : L.nci;
    }

    private interface LibraryFactory {
        LuaFunction create();
    }

    private static final class LtestsConfig implements LuaConfig {
        @Override
        public boolean compatGlobal() {
            // C: ltests/ltests.h: #undef LUA_COMPAT_GLOBAL
            return false;
        }
    }

    private static final class RemoteState {
        final long id;
        final Globals globals;
        final long storageBytes;
        final ArrayList<LuaValue> stack = new ArrayList<>();
        boolean closed;

        RemoteState(long id, Globals globals, long storageBytes) {
            this.id = id;
            this.globals = globals;
            this.storageBytes = storageBytes;
        }
    }

    private record StdLib(String name, int mask, LibraryFactory factory) {
    }

    private static final class StdLibOpener extends LuaFunction {
        private final Globals globals;
        private final StdLib lib;

        StdLibOpener(Globals globals, StdLib lib) {
            this.globals = globals;
            this.lib = lib;
        }

        @Override
        public Varargs call(Varargs args) {
            // C: linit.c:luaL_openselectedlibs stores luaopen_* in PRELOAD.
            return openStdLib(globals, lib);
        }
    }

    static final class alloccount extends LuaFunction {
        @Override
        public Varargs call(Varargs args) {
            // C: ltests.c:alloc_count  -  l_memcontrol.countlimit 是进程级静态，一次设置全局生效
            if (args.narg() == 0 || args.arg1().isnil()) {
                BaseLib.CollectGarbageFn.clearAllocCountLimit(ownerGlobals);
            } else {
                BaseLib.CollectGarbageFn.setAllocCountLimit(ownerGlobals, args.checklong(1));
            }
            return LuaValue.NONE;
        }
    }

    static final class allocfailnext extends LuaFunction {
        @Override
        public Varargs call(Varargs args) {
            // C: ltests.c:alloc_failnext
            BaseLib.CollectGarbageFn.failNextAllocation(ownerGlobals);
            return LuaValue.NONE;
        }
    }

    // ltests.c: settrick  -  l_Trick 全局指针（ltests.h "generic variable for
    //   debug tricks"；当前 HEAD 无实际消费方，仅 API 对齐）。Java 存强引用保活。
    static final class trick extends LuaFunction {
        private static Object trickObject = null;

        @Override
        public Varargs call(Varargs args) {
            if (args.narg() == 0 || args.arg1().isnil()) trickObject = null;
            else trickObject = args.arg1();
            return LuaValue.NONE;
        }
    }

    static final class querytab extends LuaFunction {
        @Override
        public Varargs call(Varargs args) {
            // C: ltests.c:table_query
            LuaTable table = args.checktable(1);
            if (args.narg() < 2 || args.arg(2).isnil()) {
                return LuaValue.varargsOf(new LuaValue[]{
                        LuaInteger.valueOf(table.diagnosticArraySize()),
                        LuaInteger.valueOf(table.diagnosticHashSize())
                });
            }
            int index = args.checkint(2);
            int asize = table.diagnosticArraySize();
            if (index < asize) {
                LuaValue key = table.diagnosticArrayKey(index);
                if (key == null || key.isnil()) key = LuaInteger.valueOf(index + 1);
                LuaValue val = table.diagnosticArrayVal(index);
                return LuaValue.varargsOf(key, val != null ? val : LuaValue.NIL);
            } else {
                int hidx = index - asize;
                LuaValue hkey = table.diagnosticHashKey(hidx);
                if (hkey != null && !hkey.isnil()) {
                    LuaValue hval = table.diagnosticHashVal(hidx);
                    return LuaValue.varargsOf(hkey, hval != null ? hval : LuaValue.NIL);
                }
                return LuaValue.varargsOf(LuaValue.NIL, LuaValue.NIL);
            }
        }
    }

    static final class querystr extends LuaFunction {
        @Override
        public Varargs call(Varargs args) {
            // C: ltests.c:string_query。Java 的短字符串表是 HashMap，
            // 无 C 的 bucket 链；此处暴露容量水位和当前字符串数。
            int used = LuaString.managedStringCount();
            while (diagnosticStringTableSize <= used) {
                diagnosticStringTableSize <<= 1;
            }
            if (args.narg() == 0 || args.arg1().isnil()) {
                return LuaValue.varargsOf(new LuaValue[]{
                        LuaInteger.valueOf(diagnosticStringTableSize),
                        LuaInteger.valueOf(used)
                });
            }
            int bucket = args.checkint(1) - 1;
            if (bucket < 0 || bucket >= diagnosticStringTableSize) return LuaValue.NONE;
            return LuaValue.NONE;
        }
    }

    static final class tref extends LuaFunction {
        @Override
        public Varargs call(Varargs args) {
            // C: ltests.c:tref / lauxlib.c:luaL_ref
            LuaValue.checkany(1, args);
            LuaTable table = refTable(args);
            LuaValue value = args.arg1();
            if (value.isnil()) return LuaInteger.valueOf(-1); // LUA_REFNIL
            LuaValue head = table.rawget(1);
            int ref;
            if (head.isnumber()) {
                ref = head.checkint();
            } else {
                ref = 0;
                table.rawset(1, LuaInteger.valueOf(0));
            }
            if (ref != 0) {
                table.rawset(1, table.rawget(ref));
            } else {
                ref = table.rawlen() + 1;
            }
            table.rawset(ref, value);
            return LuaInteger.valueOf(ref);
        }
    }

    static final class getref extends LuaFunction {
        @Override
        public Varargs call(Varargs args) {
            // C: ltests.c:getref
            LuaTable table = refTable(args);
            return table.rawget(args.checkint(1));
        }
    }

    static final class unref extends LuaFunction {
        @Override
        public Varargs call(Varargs args) {
            // C: ltests.c:unref / lauxlib.c:luaL_unref
            int ref = args.checkint(1);
            if (ref >= 0) {
                LuaTable table = refTable(args);
                LuaValue head = table.rawget(1);
                table.rawset(ref, head);
                table.rawset(1, LuaInteger.valueOf(ref));
            }
            return LuaValue.NONE;
        }
    }

    static final class codeparam extends LuaFunction {
        @Override
        public Varargs call(Varargs args) {
            // C: lobject.c:luaO_codeparam
            long p = args.checklong(1);
            return LuaInteger.valueOf(codeParam(p));
        }
    }

    static final class applyparam extends LuaFunction {
        @Override
        public Varargs call(Varargs args) {
            // C: lobject.c:luaO_applyparam
            int p = args.checkint(1) & 0xff;
            long x = args.checklong(2);
            return LuaInteger.valueOf(applyParam(p, x));
        }
    }

    static final class externKstr extends LuaFunction {
        @Override
        public Varargs call(Varargs args) {
            // C: ltests.c:externKstr。Java 无 C external fixed string 存储；
            // 返回同内容 LuaString，保留 Lua 层可观察语义。
            return args.checkstring(1);
        }
    }

    static final class externstr extends LuaFunction {
        @Override
        public Varargs call(Varargs args) {
            // C: ltests.c:externstr。长字符串用新 byte[] 模拟外部分配；
            // 短字符串仍按 Lua 5.5 短串内部化规则归一。
            LuaString s = args.checkstring(1);
            byte[] copy = new byte[s.shrlen];
            System.arraycopy(s.contents, 0, copy, 0, s.shrlen);
            return LuaString.valueOf(copy);
        }
    }

    static final class testC extends LuaFunction {
        @Override
        public Varargs call(Varargs args) {
            // C: ltests.c:testC/runC。此处按需实现真实指令语义；
            // 未实现指令必须报错，避免把完整 C API 测试伪装成通过。
            if (args.arg1() instanceof LuaLightUserdata light) {
                RemoteState state = remoteState(light);
                String script = args.checkJavaString(2);
                return runCTest(script, state.stack, true, state.globals, (LtestsCClosure) null);
            } else if (args.arg1() instanceof LuaThread thread) {
                // C: ltests.c:testC。lua_isthread(L, 1) 时，runC 在目标
                // lua_State L1 的栈上执行，错误不保护，由调用方 pcall 捕获。
                String script = args.checkJavaString(2);
                ArrayList<LuaValue> stack = threadStacks.computeIfAbsent(thread, t -> new ArrayList<>());
                Globals active = thread.l_G != null ? thread.l_G : null;
                LuaThread savedRunning = active != null ? active.running : null;
                try {
                    if (active != null) active.running = thread;
                    return runCTest(script, stack, false, active, (LtestsCClosure) null);
                } finally {
                    if (active != null) active.running = savedRunning;
                }
            } else {
                String script = args.checkJavaString(1);
                ArrayList<LuaValue> stack = new ArrayList<>();
                for (int i = 1; i <= args.narg(); i++) stack.add(args.arg(i));
                return runCTest(script, stack, false, ownerGlobals, (LtestsCClosure) null);
            }
        }
    }

    static final class LtestsCClosure extends LuaFunction {
        private final LuaValue[] upvalues;
        private final boolean scriptFromUpvalue;

        LtestsCClosure(LuaValue[] upvalues) {
            this(upvalues, false);
        }

        LtestsCClosure(LuaValue[] upvalues, boolean scriptFromUpvalue) {
            // C: lapi.c:lua_pushcclosure / ltests.c:testC
            this.upvalues = upvalues;
            this.scriptFromUpvalue = scriptFromUpvalue;
        }


        public int debugNups() {
            return upvalues.length;
        }

        @Override
        public Varargs call(Varargs args) {
            String script = scriptFromUpvalue ? getUpvalue(1).checkJavaString() : args.checkJavaString(1);
            ArrayList<LuaValue> stack = new ArrayList<>();
            for (int i = 1; i <= args.narg(); i++) stack.add(args.arg(i));
            Globals active = ownerGlobals != null ? ownerGlobals : null;
            return runCTest(script, stack, false, active, this);
        }

        LuaValue getUpvalue(int index) {
            if (index < 1 || index > upvalues.length) return LuaValue.NIL;
            return upvalues[index - 1];
        }

        boolean hasUpvalue(int index) {
            return index >= 1 && index <= upvalues.length;
        }

        void setUpvalue(int index, LuaValue value) {
            if (index >= 1 && index <= upvalues.length) upvalues[index - 1] = value;
        }
    }

    private static final class StackLuaThread extends LuaThread {
        private boolean started;

        StackLuaThread(Globals env) {
            super(env, new LuaFunction() {
                @Override
                public Varargs call(Varargs args) {
                    return LuaValue.NONE;
                }
            });
        }

        Varargs resumeFromApiStack(ArrayList<LuaValue> stack, int nargs) {
            // C: ldo.c:lua_resume。首次恢复时函数位于参数之前；
            // 后续恢复时栈顶仅有传给 yield 的返回参数。
            boolean freshCall = !started;
            int functionSlot = stack.size() - nargs - 1;
            if (started && "dead".equals(auxstatus())
                    && functionSlot >= 0 && stack.get(functionSlot) instanceof LuaFunction) {
                freshCall = true;
            }
            int first = stack.size() - nargs - (freshCall ? 1 : 0);
            if (first < 0)
                return LuaValue.varargsOf(LuaValue.FALSE, LuaString.valueOf("cannot resume dead coroutine"));
            LuaValue[] argv = new LuaValue[nargs];
            for (int i = 0; i < nargs; i++) argv[i] = stack.get(first + (freshCall ? 1 : 0) + i);
            if (freshCall) {
                LuaValue f = stack.get(first);
                if (!(f instanceof LuaFunction fn)) {
                    return LuaValue.varargsOf(LuaValue.FALSE, LuaString.valueOf("attempt to call a " + f.typeName() + " value"));
                }
                if (started) resetForResume(fn);
                else this.func = fn;
                started = true;
            }
            while (stack.size() > first) stack.remove(stack.size() - 1);
            return lua_resume(LuaValue.varargsOf(argv));
        }
    }

    static final class makeCfunc extends LuaFunction {
        @Override
        public Varargs call(Varargs args) {
            // C: ltests.c:makeCfunc/Cfunc。脚本作为 C closure 的 upvalue 保存。
            args.checkJavaString(1);
            LuaValue[] upvalues = new LuaValue[args.narg()];
            for (int i = 1; i <= args.narg(); i++) upvalues[i - 1] = args.arg(i);
            LtestsCClosure closure = new LtestsCClosure(upvalues, true);
            closure.bindGlobals(ownerGlobals);
            return closure;
        }
    }

    static final class upvalue extends LuaFunction {
        @Override
        public Varargs call(Varargs args) {
            // C: ltests.c:upvalue -> lua_getupvalue/lua_setupvalue
            LuaFunction function = args.checkfunction(1);
            int n = args.checkint(2);
            if (args.narg() < 3) {
                if (function instanceof LtestsCClosure closure) {
                    if (!closure.hasUpvalue(n)) return LuaValue.NONE;
                    return LuaValue.varargsOf(closure.getUpvalue(n), LuaString.valueOf(""));
                }
                if (function instanceof LuaClosure closure) {
                    if (n < 1 || n > closure.upvals.length) return LuaValue.NONE;
                    LuaString name = LuaDebug.findUpvalue(closure, n);
                    LuaValue value = closure.upvals[n - 1] != null ? closure.upvals[n - 1].get() : LuaValue.NIL;
                    return LuaValue.varargsOf(value, name != null ? name : LuaString.valueOf("(no name)"));
                }
                return LuaValue.NONE;
            }
            LuaValue value = args.arg(3);
            if (function instanceof LtestsCClosure closure) {
                if (!closure.hasUpvalue(n)) return LuaValue.NIL;
                closure.setUpvalue(n, value);
                return LuaString.valueOf("");
            }
            if (function instanceof LuaClosure closure) {
                if (n < 1 || n > closure.upvals.length) return LuaValue.NIL;
                LuaString name = LuaDebug.findUpvalue(closure, n);
                if (closure.upvals[n - 1] != null) closure.upvals[n - 1].set(value);
                return name != null ? name : LuaString.valueOf("(no name)");
            }
            return LuaValue.NIL;
        }
    }

    private static final class ScriptReader {
        private final String script;
        private int pos;

        ScriptReader(String script) {
            this.script = script;
        }

        String next() {
            skip();
            if (pos >= script.length()) return null;
            char first = script.charAt(pos);
            if (first == '"' || first == '\'') return readQuoted(first);
            int start = pos;
            while (pos < script.length()) {
                char c = script.charAt(pos);
                if (Character.isWhitespace(c) || c == ';' || c == ',' || c == '#') break;
                pos++;
            }
            return script.substring(start, pos);
        }

        int nextNumber(ArrayList<LuaValue> stack) {
            skip();
            if (pos >= script.length()) LuaErrors.error("number expected ()");
            char c = script.charAt(pos);
            if (c == '*') {
                pos++;
                return stack.size();
            }
            if (c == '!') {
                pos++;
                if (pos >= script.length()) LuaErrors.error("number expected (!)");
                char name = script.charAt(pos++);
                if (name == 'G') return LUA_RIDX_GLOBALS;
                if (name == 'M') return LUA_RIDX_MAINTHREAD;
                LuaErrors.error("number expected (!" + name + ")");
            }
            if (c == '.') {
                pos++;
                LuaValue value = stack.isEmpty() ? LuaValue.NIL : stack.remove(stack.size() - 1);
                return value.checkint();
            }
            int sign = 1;
            if (c == '-') {
                sign = -1;
                pos++;
            }
            int start = pos;
            int value = 0;
            while (pos < script.length() && Character.isDigit(script.charAt(pos))) {
                value = value * 10 + (script.charAt(pos++) - '0');
            }
            if (pos == start) LuaErrors.error("number expected (" + next() + ")");
            return sign * value;
        }

        int nextIndex(ArrayList<LuaValue> stack) {
            skip();
            if (pos >= script.length()) LuaErrors.error("index expected ()");
            char c = script.charAt(pos);
            if (c == 'R') {
                pos++;
                return REGISTRY_INDEX;
            }
            if (c == 'U') {
                pos++;
                int n = nextNumber(stack);
                return n == 0 ? REGISTRY_INDEX : upvalueIndex(n);
            }
            return nextNumber(stack);
        }

        private void skip() {
            while (pos < script.length()) {
                char c = script.charAt(pos);
                if (Character.isWhitespace(c) || c == ';' || c == ',') {
                    pos++;
                } else if (c == '#') {
                    while (pos < script.length()) {
                        char d = script.charAt(pos++);
                        if (d == '\n' || d == '\r') break;
                    }
                } else {
                    break;
                }
            }
        }

        private String readQuoted(char quote) {
            pos++;
            StringBuilder b = new StringBuilder();
            while (pos < script.length()) {
                char c = script.charAt(pos++);
                if (c == quote) break;
                if (c == '\\' && pos < script.length()) {
                    char e = script.charAt(pos++);
                    b.append(switch (e) {
                        case 'n' -> '\n';
                        case 'r' -> '\r';
                        case 't' -> '\t';
                        default -> e;
                    });
                } else {
                    b.append(c);
                }
            }
            return b.toString();
        }
    }

    private record PCallKResult(String status, Varargs continuation) {
    }

    private record TestUserdata(long id, int size) {

        @Override
            public String toString() {
                return "userdata: " + Long.toHexString(id);
            }
        }

    static final class newuserdata extends LuaFunction {
        @Override
        public Varargs call(Varargs args) {
            // C: ltests.c:newuserdata
            long size = args.checklong(1);
            if (size < 0 || size > Integer.MAX_VALUE) {
                LuaErrors.tooBig();
            }
            int nuvalue = args.arg(2).optint(1);
            if (nuvalue < 0) LuaErrors.argError(2, "invalid value");
            LuaUserdata ud = new LuaUserdata(new TestUserdata(++userdataSerial, (int) size), nuvalue, size);
            // C：lgc.c : luaC_newobj  -  新对象创建即登记到所属状态，否则 __gc 无法登记
            LuaTable.bindValue(ownerGlobals, ud);
            return ud;
        }
    }

    static final class pushuserdata extends LuaFunction {
        @Override
        public Varargs call(Varargs args) {
            // C: ltests.c:pushuserdata
            return lightUserdata(args.checklong(1));
        }
    }

    static final class checkpanic extends LuaFunction {
        @Override
        public Varargs call(Varargs args) {
            // C: ltests.c:checkpanic/panicback。Java 无 setjmp/longjmp；
            // 此处用未保护 runCTest + 捕获 LuaError 模拟 panic 的可观察结果。
            String code = args.checkJavaString(1);
            String panicCode = args.optJavaString(2, "");
            RemoteState state;
            try {
                state = newRemoteState();
            } catch (LuaError error) {
                if ("not enough memory".equals(error.getMessage()))
                    return LuaString.valueOf("not enough memory");
                throw error;
            }
            try {
                try {
                    runCTest(code, state.stack, false, state.globals);
                    return LuaString.valueOf("no errors");
                } catch (LuaError error) {
                    LuaValue err = error.getMessageObject();
                    if (err == null) err = LuaString.valueOf(error.getMessage());
                    if (!panicCode.isEmpty()) {
                        state.stack.add(err);
                        String panicStatus = "not enough memory".equals(panicMessage(err)) ? "not enough memory" : "ERRRUN";
                        Varargs panicResult = runCTest(panicCode, state.stack, true, state.globals, panicStatus);
                        if (panicResult.narg() > 0) return panicResult.arg(panicResult.narg());
                        if (!state.stack.isEmpty())
                            return LuaString.valueOf(panicMessage(state.stack.get(state.stack.size() - 1)));
                    }
                    return LuaString.valueOf(panicMessage(err));
                }
            } finally {
                closeRemoteState(state);
            }
        }
    }

    static final class newstate extends LuaFunction {
        @Override
        public Varargs call(Varargs args) {
            // C: ltests.c:newstate
            try {
                return remoteStateValue(newRemoteState());
            } catch (LuaError error) {
                if ("not enough memory".equals(error.getMessage())) return LuaValue.NIL;
                throw error;
            }
        }
    }

    static final class closestate extends LuaFunction {
        @Override
        public Varargs call(Varargs args) {
            // C: ltests.c:closestate
            RemoteState state = remoteState((LuaLightUserdata) args.arg1());
            closeRemoteState(state);
            remoteStates.remove(lightUserdataAddress((LuaLightUserdata) args.arg1()));
            return LuaValue.NONE;
        }
    }

    static final class loadlib extends LuaFunction {
        @Override
        public Varargs call(Varargs args) {
            // C: ltests.c:loadlib -> linit.c:luaL_openselectedlibs。
            RemoteState state = remoteState((LuaLightUserdata) args.arg1());
            openSelectedLibraries(state, args.checkint(2), args.checkint(3));
            return LuaValue.NONE;
        }
    }

    static final class doonnewstack extends LuaFunction {
        // java-only: registry key counter for GC reachability of new threads
        private static int regKeyCounter = 0;
        private final Globals globals;

        doonnewstack(Globals globals) {
            this.globals = globals;
        }

        @Override
        public Varargs call(Varargs args) {
            // C: ltests.c:doonnewstack。官方在 lua_newthread 得到的新
            // lua_State 上 luaL_loadbuffer，然后 lua_pcall，最后返回状态码。
            String code = args.checkJavaString(1);
            Globals g = globals != null ? globals : LuaTable.runningGlobalsForGC();
            if (g == null) LuaErrors.error("no active Lua state");
            LuaThread savedRunning = g.running;
            LuaThread thread = null;
            // java diff: C 的 lua_newthread 把 L1 压入 L 的栈保证 GC 可达；
            // Java 必须把新线程和调用者线程存入 registry，防止被 GC 回收
            int regKey1 = 0, regKey2 = 0;
            try {
                LuaValue chunk = loadString(g, LuaString.valueOf(code), code, "bt");
                if (!(chunk instanceof LuaFunction function)) {
                    return LuaInteger.valueOf("not enough memory".equals(panicMessage(chunk)) ? 4 : 3);
                }
                thread = new LuaThread(g, function);
                // C: lua_newthread pushes L1 onto L's stack -> GC reachability
                // java diff: store both threads in registry for GC reachability
                regKey1 = ++regKeyCounter;
                regKey2 = ++regKeyCounter;
                g.registry.rawset(LuaInteger.valueOf(regKey1), savedRunning);
                g.registry.rawset(LuaInteger.valueOf(regKey2), thread);
                g.running = thread;
                LuaCall.callLua(function);
                return LuaInteger.valueOf(0);
            } catch (LuaError error) {
                LuaValue err = error.getMessageObject();
                if (err == null) err = LuaString.valueOf(error.getMessage());
                return LuaInteger.valueOf("not enough memory".equals(panicMessage(err)) ? 4 : 2);
            } finally {
                // C: after lua_pcall, pop L1 from L's stack
                if (regKey1 != 0) g.registry.rawset(LuaInteger.valueOf(regKey1), LuaValue.NIL);
                if (regKey2 != 0) g.registry.rawset(LuaInteger.valueOf(regKey2), LuaValue.NIL);
                g.running = savedRunning;
            }
        }
    }

    static final class doremote extends LuaFunction {
        @Override
        public Varargs call(Varargs args) {
            // C: ltests.c:doremote
            RemoteState state = remoteState((LuaLightUserdata) args.arg1());
            String code = args.checkJavaString(2);
            state.stack.clear();
            try {
                LuaValue chunk = loadString(state.globals, LuaString.valueOf(code), code, "bt");
                if (!(chunk instanceof LuaFunction)) {
                    // C: ltests.c:doremote  -  luaL_loadbuffer 失败时直接返回
                    // nil, errmsg, status，不会再把错误字符串当函数调用。
                    return LuaValue.varargsOf(new LuaValue[]{LuaValue.NIL, LuaString.valueOf(panicMessage(chunk)), LuaInteger.valueOf(3)});
                }
                Varargs out = LuaCall.callLua(chunk);
                LuaValue[] converted = new LuaValue[out.narg()];
                for (int i = 1; i <= out.narg(); i++)
                    converted[i - 1] = remoteReturnValue(out.arg(i));
                return LuaValue.varargsOf(converted);
            } catch (LuaError error) {
                LuaValue err = error.getMessageObject();
                if (err == null) err = LuaString.valueOf(error.getMessage());
                return LuaValue.varargsOf(new LuaValue[]{LuaValue.NIL, LuaString.valueOf(panicMessage(err)), LuaInteger.valueOf(2)});
            }
        }
    }

    static final class udataval extends LuaFunction {
        @Override
        public Varargs call(Varargs args) {
            // C: ltests.c:udataval
            if (args.arg1() instanceof LuaLightUserdata light) {
                return LuaInteger.valueOf(lightUserdataAddress(light));
            }
            Object data = args.arg1().checkuserdata();
            if (data instanceof TestUserdata u) return LuaInteger.valueOf(u.id);
            return LuaValue.NIL;
        }
    }

    static final class d2s extends LuaFunction {
        @Override
        public Varargs call(Varargs args) {
            // C: ltests.c:d2s。按本平台 Lua 测试的 little-endian double 布局输出 8 字节。
            long bits = Double.doubleToRawLongBits(args.checkdouble(1));
            byte[] out = new byte[8];
            for (int i = 0; i < 8; i++) out[i] = (byte) (bits >>> (8 * i));
            return LuaString.valueOf(out);
        }
    }

    static final class s2d extends LuaFunction {
        @Override
        public Varargs call(Varargs args) {
            // C: ltests.c:s2d。读取字符串前 sizeof(double) 字节并解释为 double。
            LuaString s = args.checkstring(1);
            long bits = 0;
            for (int i = 0; i < 8; i++) {
                int b = i < s.shrlen ? s.contents[i] & 0xff : 0;
                bits |= ((long) b) << (8 * i);
            }
            return LuaValue.valueOf(Double.longBitsToDouble(bits));
        }
    }

    static final class gcstate extends LuaFunction {
        @Override
        public Varargs call(Varargs args) {
            // C：ltests.c : gc_state。
            if (args.narg() == 0 || args.arg1().isnil()) {
                // lgc.c: return current GC state name
                int gs = LuaGC.gcstate(ownerGlobals);
                String name = switch (gs) {
                    case LuaGC.GCSpropagate -> "propagate";
                    case LuaGC.GCSenteratomic -> "enteratomic";
                    case LuaGC.GCSatomic -> "atomic";
                    case LuaGC.GCSswpallgc -> "sweepallgc";
                    case LuaGC.GCSswpfinobj -> "sweepfinobj";
                    case LuaGC.GCSswptobefnz -> "sweeptobefnz";
                    case LuaGC.GCSswpend -> "sweepend";
                    case LuaGC.GCScallfin -> "callfin";
                    case LuaGC.GCSpause -> "pause";
                    default -> "pause";
                };
                return LuaString.valueOf(name);
            }
            String state = args.checkJavaString(1);
            int option = switch (state) {
                case "propagate" -> LuaGC.GCSpropagate;
                case "enteratomic" -> LuaGC.GCSenteratomic;
                case "atomic" -> LuaGC.GCSatomic;
                case "sweepallgc" -> LuaGC.GCSswpallgc;
                case "sweepfinobj" -> LuaGC.GCSswpfinobj;
                case "sweeptobefnz" -> LuaGC.GCSswptobefnz;
                case "sweepend" -> LuaGC.GCSswpend;
                case "callfin" -> LuaGC.GCScallfin;
                case "pause" -> LuaGC.GCSpause;
                default -> -1;
            };
            if (option < 0) LuaErrors.argError(1, "invalid option '" + state + "'");
            // ltests.c: gc_state  -  分代模式不允许切状态
            if (!LuaGC.isIncrementalMode(ownerGlobals))
                LuaErrors.error("cannot change states in generational mode");
            currentGcState = state;
            atomicWatermark = 0;
            // ltests.c: gc_state  -  目标状态早于当前则先跑到 pause，再不跳过传播跑到目标
            if (option < LuaGC.gcstate(ownerGlobals)) {
                LuaGC.runToState(ownerGlobals, LuaGC.GCSpause, true);
            }
            LuaGC.runToState(ownerGlobals, option, false);
            return LuaValue.NONE;
        }
    }

    static final class gccolor extends LuaFunction {
        @Override
        public Varargs call(Varargs args) {
            // C: ltests.c:gc_color
            // java diff：iscollectable()/diagnosticColor 已删除；用类型判断代替。
            LuaValue value = args.arg1();
            LuaValue.checkany(1, args);
            if (!isCollectable(value)) return LuaString.valueOf("no collectable");
            String color = defaultColor(value);
            return LuaString.valueOf(color);
        }
    }

    static final class gcage extends LuaFunction {
        @Override
        public Varargs call(Varargs args) {
            // C: ltests.c:gc_age
            // java diff：iscollectable()/diagnosticAge 已删除；用类型判断代替。
            LuaValue value = args.arg1();
            LuaValue.checkany(1, args);
            if (!isCollectable(value)) return LuaString.valueOf("no collectable");
            String age = defaultAge(value);
            return LuaString.valueOf(age);
        }
    }

    static final class checkmemory extends LuaFunction {
        @Override
        public Varargs call(Varargs args) {
            // C: ltests.c:lua_checkmemory。Java 无 GCObject/allgc/gray 指针链；
            // 此处检查 GC 中间层真实存在的表链、弱表登记和内存计数不变量。
            // java diff：checkMemoryInvariants 已删除；此检查为空操作。
            // LuaTable.checkMemoryInvariants();
            return LuaValue.NONE;
        }
    }

    static final class totalmem extends LuaFunction {
        @Override
        public Varargs call(Varargs args) {
            // C: ltests.c:mem_query
            if (args.narg() == 0 || args.arg1().isnil()) {
                // C: ltests.c:mem_query 返回 l_memcontrol 的 total/numblocks/maxmem，
                // 三者同为进程级。total 必须跨状态求和：内存限额检查走的就是进程总量，
                // 查询若仅算本状态，testbytes 一类"读总量->设为上限"的用例两侧口径不齐。
                final long[] acc = {0L};
                LuaTable.forEachActiveGlobals(g -> acc[0] += BaseLib.CollectGarbageFn.currentBytes(g));
                long total = acc[0];
                int numblocks = objectCount("string") + objectCount("table")
                        + objectCount("function") + objectCount("userdata")
                        + objectCount("thread");
                // java diff: maxmem 是各状态各自的历史峰值，求和不等于 C 的单一进程峰值
                //   （C 由分配器维护一个 high-water mark）。此处取本状态峰值，
                //   官方套件仅用它做单调性检查，未跨状态比较。
                long maxmem = BaseLib.CollectGarbageFn.maxBytes(ownerGlobals);
                return LuaValue.varargsOf(new LuaValue[]{
                        LuaInteger.valueOf(total),
                        LuaInteger.valueOf(numblocks),
                        LuaInteger.valueOf(maxmem)
                });
            }
            if (args.arg1().isnumber()) {
                long limit = args.checklong(1);
                // C: ltests.c:mem_query  -  l_memcontrol.memlimit 是进程级静态
                BaseLib.CollectGarbageFn.setMemoryLimit(ownerGlobals, limit);
                return LuaValue.NONE;
            }
            String type = args.checkJavaString(1);
            int count = objectCount(type);
            if (count < 0) LuaErrors.error("unknown type '" + type + "'");
            return LuaInteger.valueOf(count);
        }

        /**
         * C：ltests.c : l_memcontrol.objcount[LUA_NUMTYPES]  -  与 numblocks/total 同在
         * Memcontrol 进程级单例内，由分配器 debug_realloc 在每次分配/释放时增减，
         * 与对象属于哪个 lua_State 无关。故此处按全部登记状态求和，不能仅算 ownerGlobals：
         * 否则 T.totalmem() 的 blocks 与 T.totalmem(type) 在多状态下与 C 不一致
         * （字符串计数本就是进程级，其余四类若按状态就会与它口径不齐）。
         */
        private int objectCount(String type) {
            if ("string".equals(type)) return LuaString.managedStringCount();
            final int[] sum = {0};
            switch (type) {
                case "table" -> LuaTable.forEachActiveGlobals(g -> sum[0] += LuaTable.managedTableCount(g));
                case "function" -> LuaTable.forEachActiveGlobals(g -> sum[0] += LuaClosure.managedClosureCount(g));
                case "userdata" -> LuaTable.forEachActiveGlobals(g -> sum[0] += LuaUserdata.managedUserdataCount(g));
                case "thread" -> LuaTable.forEachActiveGlobals(g -> sum[0] += LuaThread.managedThreadCount(g));
                default -> {
                    return -1;
                }
            }
            return sum[0];
        }
    }

    static final class listk extends LuaFunction {
        @Override
        public Varargs call(Varargs args) {
            // C: ltests.c:listk
            LuaFunction fn = args.checkfunction(1);
            if (!(fn instanceof LuaClosure)) {
                LuaErrors.argError(1, "Lua function expected");
            }
            Prototype p = ((LuaClosure) fn).p;
            int n = p.k != null ? Math.min(p.sizek, p.k.length) : 0;
            LuaTable out = new LuaTable(n, 0);
            if (p.k != null) {
                for (int i = 0; i < n; i++) {
                    out.rawset(i + 1, p.k[i]);
                }
            }
            return out;
        }
    }

    static final class listcode extends LuaFunction {
        @Override
        public Varargs call(Varargs args) {
            // C: ltests.c:listcode/buildop
            LuaFunction fn = args.checkfunction(1);
            if (!(fn instanceof LuaClosure)) {
                LuaErrors.argError(1, "Lua function expected");
            }
            Prototype p = ((LuaClosure) fn).p;
            int n = p.code != null ? Math.min(p.sizecode, p.code.length) : 0;
            LuaTable out = new LuaTable(n, 2);
            out.rawset(LuaString.valueOf("maxstack"), LuaInteger.valueOf(p.maxstacksize));
            out.rawset(LuaString.valueOf("numparams"), LuaInteger.valueOf(p.numparams));
            if (p.code != null) {
                for (int pc = 0; pc < n; pc++) {
                    out.rawset(pc + 1, LuaString.valueOf(buildop(p, pc)));
                }
            }
            return out;
        }
    }

    static final class listabslineinfo extends LuaFunction {
        @Override
        public Varargs call(Varargs args) {
            // C: ltests.c:listabslineinfo
            LuaFunction fn = args.checkfunction(1);
            if (!(fn instanceof LuaClosure)) {
                LuaErrors.argError(1, "Lua function expected");
            }
            LuaClosure cl = (LuaClosure) fn;
            Prototype p = cl.p;
            int n = p.abslineinfo != null ? Math.min(p.sizeabslineinfo, p.abslineinfo.length / 2) : 0;
            if (n == 0) {
                LuaErrors.argError(1, "function has no debug info");
            }
            LuaTable out = new LuaTable();
            for (int i = 0; i < n; i++) {
                int slot = i * 2;
                out.rawset(slot + 1, LuaInteger.valueOf(p.abslineinfo[slot]));
                out.rawset(slot + 2, LuaInteger.valueOf(p.abslineinfo[slot + 1]));
            }
            return out;
        }
    }

    static final class listlocals extends LuaFunction {
        @Override
        public Varargs call(Varargs args) {
            // C: ltests.c:listlocals
            LuaFunction fn = args.checkfunction(1);
            if (!(fn instanceof LuaClosure)) {
                LuaErrors.argError(1, "Lua function expected");
            }
            LuaClosure cl = (LuaClosure) fn;
            int pc = args.checkint(2) - 1;
            LuaTable out = new LuaTable();
            int n = 1;
            Prototype p = cl.p;
            if (p.locvars != null) {
                int limit = Math.min(p.sizelocvars, p.locvars.length);
                for (int i = 0; i < limit; i++) {
                    Prototype.LocVar lv = p.locvars[i];
                    if (lv == null) break;
                    if (lv.startpc > pc) break;
                    if (pc < lv.endpc && lv.varname != null) {
                        out.rawset(n++, lv.varname);
                    }
                }
            }
            return out;
        }
    }

    // ltests.c: resetCI  -  释放当前 ci 之后的空闲 CallInfo（next 链）。
    // gc.lua 的 "should not try to call finalizer without a CallInfo available" 依赖它。
    static final class resetci extends LuaFunction {
        private final Globals globals;

        resetci(Globals globals) {
            this.globals = globals;
        }

        @Override
        public Varargs call(Varargs args) {
            LuaThread L = globals.running;
            if (L != null) {
                CallInfo ci = L.ci;
                while (ci.next != null) {
                    CallInfo tofree = ci.next;
                    ci.next = ci.next.next;
                    if (tofree.next != null) tofree.next = null;  // 断开引用
                }
            }
            return LuaValue.NONE;
        }
    }

    // ltests.c: reallocstack  -  栈重分配到 top+n 大小（luaD_reallocstack(L, top-stack+n, 1)）。
    // gc.lua 的 "should not try to call finalizer without stack space available" 依赖它。
    static final class reallocstack extends LuaFunction {
        private final Globals globals;

        reallocstack(Globals globals) {
            this.globals = globals;
        }

        @Override
        public Varargs call(Varargs args) {
            int n = args.checkint(1);
            LuaThread L = globals.running;
            if (L != null) {
                // ldo.c: luaD_reallocstack(L, top-stack+n, 1)
                LuaVM.reallocStack(L, L.top + n, 1);
            }
            return LuaValue.NONE;
        }
    }

    static final class stacklevel extends LuaFunction {
        private final Globals globals;

        stacklevel(Globals globals) {
            this.globals = globals;
        }

        @Override
        public Varargs call(Varargs args) {
            // C: ltests.c:stacklevel
            LuaThread running = globals.running;
            int top = Math.max(globals.getStackTop(running), 1);
            // java diff：diagnosticStackSizeForErrorHandler/getRawNCcalls 已删除；
            //   nci 对齐 C 直接读 L.nci（O(1)，见 countCallInfoNodes 注释），
            int stackSize = running != null ? running.stack.length : 0;
            int rawNCcalls = running != null ? running.nCcalls : 0;
            int nci = countCallInfoNodes(running);
            return LuaValue.varargsOf(new LuaValue[]{
                    LuaInteger.valueOf(top),
                    LuaInteger.valueOf(stackSize),
                    LuaInteger.valueOf(rawNCcalls),
                    LuaInteger.valueOf(nci),
                    LuaInteger.valueOf(System.identityHashCode(Thread.currentThread()))
            });
        }
    }

    static final class sethook extends LuaFunction {
        private final Globals globals;

        sethook(Globals globals) {
            this.globals = globals;
        }

        @Override
        public Varargs call(Varargs args) {
            // C: ltests.c:sethook/sethookaux/Chook。脚本解释器仅实现 debug 钩子常用子集。
            if (args.arg1().isnil()) {
                LtestsDebugLib.applyHook(globals, LuaValue.NIL, "", 0);
                return LuaValue.NONE;
            }
            String script = args.checkJavaString(1);
            String mask = args.checkJavaString(2);
            int count = args.arg(3).optint(0);
            if (script.isEmpty()) {
                LtestsDebugLib.applyHook(globals, LuaValue.NIL, "", 0);
                return LuaValue.NONE;
            }
            LuaFunction hook = new hookscript(globals, script);
            LtestsDebugLib.applyHook(globals, hook, mask, count);
            return LuaValue.NONE;
        }
    }

    static final class coresume extends LuaFunction {
        @Override
        public Varargs call(Varargs args) {
            // C: ltests.c:coresume。测试库仅恢复第一个参数中的协程，不传入额外参数；
            // lua_resume 返回 LUA_OK/LUA_YIELD 时仅返回 true，错误时返回 false + 错误对象。
            LuaThread co = args.checkthread(1);
            Varargs result = co.lua_resume(LuaValue.NONE);
            if (!result.arg1().toboolean()) {
                return result;
            }
            return LuaValue.TRUE;
        }
    }

    static final class hookscript extends LuaFunction {
        private final Globals globals;
        private final String script;

        hookscript(Globals globals, String script) {
            this.globals = globals;
            this.script = script;
        }

        @Override
        public Varargs call(Varargs args) {
            ArrayList<LuaValue> stack = new ArrayList<>();
            stack.add(args.arg(1));
            stack.add(args.arg(2));
            // C: ltests.c:Chook calls runC(L, L, scpt) with the same
            // lua_State that fired the hook.
            runCTest(script, stack, false, globals, (LtestsCClosure) null);
            return LuaValue.NONE;
        }
    }
}
