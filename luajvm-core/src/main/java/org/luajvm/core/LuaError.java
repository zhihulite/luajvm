// ref: ldo.c (longjmp/luaD_throw/luaG_errormsg)
// diff: C用longjmp传播错误，CallInfo链在longjmp后仍存活；Java用RuntimeException，finally块会在xpcall的catch前弹出DebugFrame，因此创建错误时快照CallInfo视图(savedStack)
// java diff: C中luaG_errormsg在luaD_throw之前调用message handler；Java无法在构造器中调用handler（handler出错时无法longjmp），所以handler调用延迟到XpcallFn的catch块
package org.luajvm.core;


import java.util.ArrayList;

public class LuaError extends RuntimeException {
    // java-only: A/B开关 - -Dluajvm.lazysnapshot=false 禁用惰性快照（基线对照），默认开启
    private static final boolean LAZY_SNAPSHOT =
            Boolean.parseBoolean(System.getProperty("luajvm.lazysnapshot", "true"));
    // luaD_throw  -  Lua侧错误对象
    public final LuaValue luaError;
    // lua_Debug.currentline  -  错误级别
    public final int level;
    // java-only
    private final LuaValue rawLuaError;
    // luaG_addinfo  -  "source:line: "前缀添加后设为true，防止外层帧重复添加
    public boolean hasSourceInfo;
    // java: C中CallInfo在longjmp后仍存活；Java需显式快照
    public ArrayList<Globals.DebugFrame> savedStack;
    // java-only: 惰性快照状态 - throw时捕获的L和ci，延迟到需要traceback时才快照
    //   （对齐C的longjmp语义：CI链在longjmp后仍存活，traceback时直接遍历；安全性论证见 ensureSnapshot）
    LuaThread throwL;
    CallInfo throwCi;

    // ldo.c: luaD_throw
    public LuaError(String msg) {
        this(msg, LuaString.newStr(msg), 1);
    }

    // ldo.c: luaD_throw
    public LuaError(LuaValue msg) {
        this(msg.toJavaString(), msg, 1, msg, null);
    }

    // ldo.c: luaD_throw
    public LuaError(String msg, int level) {
        this(msg, LuaString.newStr(msg), level);
    }

    // ldo.c: luaD_throw
    public LuaError(LuaValue msg, int level) {
        this(msg.toJavaString(), msg, level, msg, null);
    }

    private LuaError(String msg, int level, LuaValue rawLuaError) {
        this(msg, LuaString.newStr(msg), level, rawLuaError, null);
    }

    // ldo.c: luaD_throw
    // java:
    public LuaError(String msg, Throwable t) {
        this(msg, LuaString.newStr(msg), 1, t);
    }

    // ldo.c: luaD_throw
    public LuaError(Exception e) {
        this(e.getMessage(), LuaString.newStr(e.getMessage()), 1, e);
    }

    private LuaError(String javaMessage, LuaValue luaError, int level) {
        this(javaMessage, luaError, level, luaError, null);
    }

    private LuaError(String javaMessage, LuaValue luaError, int level, Throwable cause) {
        this(javaMessage, luaError, level, luaError, cause);
    }

    private LuaError(String javaMessage, LuaValue luaError, int level, LuaValue rawLuaError, Throwable cause) {
        this(javaMessage, luaError, level, rawLuaError, cause, true);
    }

    // luaD_throw/luaD_rawrunprotected  -  核心构造器
    // java: 关闭JVM栈采集，Lua调试栈由savedStack保存
    private LuaError(String javaMessage, LuaValue luaError, int level, LuaValue rawLuaError, Throwable cause, boolean snapshotStack) {
        super(javaMessage, cause, true, false);
        this.luaError = luaError;
        this.rawLuaError = rawLuaError;
        this.level = level;
        if (snapshotStack) {
            if (LAZY_SNAPSHOT) {
                // java-only: 惰性快照 - 只捕获throw时的L和ci引用（两次字段赋值，零分配），
                // 延迟到ensureSnapshot()被调用时才做完整的DebugFrame链快照。
                // 大多数被pcall捕获的错误永远不会需要traceback，省去DebugFrame分配。
                captureThrowState();
                setPendingError();
            } else {
                saveStack();
                setPendingError();
            }
        }
    }


    // java-only
    public LuaValue getMessageObject() {
        return luaError;
    }


    // ldo.c: luaD_throw; ldebug.c: lua_getstack/lua_getinfo
    // java: C用longjmp保留CallInfo链；Java异常展开前保存同一CallInfo链的调试视图，避免另起一套函数名解析。
    // java diff: Java异常展开前保存调用链快照，主线程也需要保存（xpcall截断ci后traceback仍需完整链）
    private void saveStack() {
        try {
            Globals g = throwL != null ? throwL.l_G : LuaStates.executingOwner();
            if (g != null) {
                LuaThread running = g.running;
                if (running != null) {
                    savedStack = g.snapshotCallInfoChain(running);
                }
            }
        } catch (StackOverflowError ignored) {
            // java diff: 栈溢出时无法保存调用链快照，C用longjmp不受此影响
        }
    }

    // java-only: 惰性快照 - throw时只捕获L和ci引用（零分配）
    // CI链在Java异常展开期间不被修改（安全性论证见 ensureSnapshot）
    private void captureThrowState() {
        try {
            Globals g = throwL != null ? throwL.l_G : LuaStates.executingOwner();
            if (g != null) {
                LuaThread running = g.running;
                if (running != null) {
                    throwL = running;
                    throwCi = running.ci;
                }
            }
        } catch (StackOverflowError ignored) {
            // java diff: 栈溢出时无法捕获ci引用
        }
    }

    // java-only: 惰性快照 - 在需要traceback时才从throwCi做完整DebugFrame链快照
    // 调用点：DebugHook.traceback / LuaVM.closeUpvals / LuaErrors.appendMessageSuffix /
    //         LuaThread.runCoroutine catch
    // 安全性论证：throwCi在throw到ensureSnapshot之间不被修改/重用 -
    //   1) Java异常展开不触发Lua代码 -> 不调用extendCI -> 不重用CI对象
    //   2) xpcall handler帧在throwCi之上添加（new_ci.previous=throwCi），不修改throwCi及其previous链
    //   3) pcall/xpcall的ci恢复（L.ci=oldCi）只改L.ci，不改throwCi
    public void ensureSnapshot() {
        if (savedStack != null) return;  // 已快照（eager或之前的lazy）
        if (throwCi == null || throwL == null)
            return;  // 无throw状态（eager快照模式，或错误在无running线程时创建，throwCi从未设置）
        try {
            Globals g = throwL != null ? throwL.l_G : LuaStates.executingOwner();
            if (g != null) {
                savedStack = g.snapshotCallInfoChainFromCi(throwL, throwCi);
            }
        } catch (StackOverflowError ignored) {
            // java diff: 栈溢出时无法快照
        } finally {
            // 清除引用 - ci之后可能被重用，防止后续ensureSnapshot产生错误快照
            throwCi = null;
            throwL = null;
        }
    }

    // java-only: 清除惰性快照状态 - 在error handler完成后调用，防止throwCi引用泄漏
    // 调用点：BaseLib.XpcallFn finally / BaseLib.callMessageHandler catch
    public void clearThrowState() {
        throwCi = null;
        throwL = null;
    }

    // ldebug.c: luaG_errormsg  -  设置pendingError让traceback能找到savedStack
    // java diff: C中luaG_errormsg在luaD_throw之前调用handler；Java的handler调用延迟到
    //   XpcallFn的catch块（见文件头 java diff）。此处只设置pendingError，
    //   让traceback通过findLuaError找到savedStack
    private void setPendingError() {
        try {
            Globals g = throwL != null ? throwL.l_G : LuaTable.runningGlobalsForGC();
            if (g == null || g.running == null) return;
            LuaThread L = g.running;
            if (L.errfuncRef == null) return;
            L.pendingError = this;
        } catch (StackOverflowError ignored) {
            // java diff: 栈溢出时无法设置pendingError
        }
    }

}
