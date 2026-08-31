// ref: lstate.h (CallInfo)
// diff: u联合体扁平化为独立字段; u2联合体扁平化; savedpc用int索引; func/top用int索引; 直接存closure/thread引用
package org.luajvm.core;

import java.util.function.BiFunction;

public final class CallInfo {
    // callstatus位域常量
    // bits 0-7: 期望结果数+1
    public static final int CIST_NRESULTS = 0xff;
    // bits 8-11: __call 元方法调用次数
    public static final int CIST_CCMT = 8;
    public static final int MAX_CCMT = 0xf << CIST_CCMT;
    // bits 12-14: 恢复状态（协程关闭 tbc 时保留错误状态）
    public static final int CIST_RECST = 12;
    // bit 15: 正在执行 C 函数
    public static final int CIST_C = 1 << (CIST_RECST + 3);
    // bit 16: 新鲜的 luaV_execute 帧
    public static final int CIST_FRESH = CIST_C << 1;
    // bit 17: 正在关闭 tbc 变量
    public static final int CIST_CLSRET = CIST_FRESH << 1;
    // bit 18: 有 tbc 变量需要关闭
    public static final int CIST_TBC = CIST_CLSRET << 1;
    // bit 19: allowhook 的原始值
    public static final int CIST_OAH = CIST_TBC << 1;
    // bit 20: 正在运行 debug hook
    public static final int CIST_HOOKED = CIST_OAH << 1;
    // bit 21: 可 yield 的 pcall
    public static final int CIST_YPCALL = CIST_HOOKED << 1;
    // bit 22: 尾调用
    public static final int CIST_TAIL = CIST_YPCALL << 1;
    // bit 23: 上次 hook 调用 yield 了
    public static final int CIST_HOOKYIELD = CIST_TAIL << 1;
    // bit 24: 函数"调用"了 finalizer
    public static final int CIST_FIN = CIST_HOOKYIELD << 1;
    // StkIdRel func
    public int func;
    // StkIdRel top
    public int top;
    // CallInfo *previous, *next
    public CallInfo previous;
    public CallInfo next;
    // u.l.savedpc
    public int savedpc;
    // u.l.trap
    public boolean trap;
    // u.l.nextraargs
    public int nextraargs;
    // lstate.h: u.c.k  -  java diff: BiFunction 替代 lua_KFunction 函数指针
    public BiFunction<LuaThread, LuaValue, Integer> k;
    // u.c.old_errfunc
    public int old_errfunc;
    // u.c.ctx
    public LuaValue ctx;
    // lstate.h: funcidx (java diff: C 无独立字段)
    public int funcidx;
    // u2.nyield
    public int nyield;
    // u2.nres
    public int nres;
    // callstatus位域
    public int callstatus;

    // get_nresults(cs)
    public static int getNResults(int cs) {
        return (cs & CIST_NRESULTS) - 1;
    }

    // ci_func(ci): 从共享栈取闭包
    public static LuaClosure ciFunc(LuaThread L, CallInfo ci) {
        LuaValue v = L.stack[ci.func];
        return v instanceof LuaClosure ? (LuaClosure) v : null;
    }

    // ldebug.h: ci_func  -  VM 主循环专用裸转型版本（对齐 C 宏零开销语义）：
    //   luaV_execute 主循环内 ci 必为 Lua 闭包帧，裸转型安全，免运行时检查
    public static LuaClosure ciFuncLua(LuaThread L, CallInfo ci) {
        return (LuaClosure) L.stack[ci.func];
    }

    // savepc(ci)
    public static void savepc(CallInfo ci, int pc) {
        ci.savedpc = pc;
    }

    // currentpc(ci)
    public static int currentpc(CallInfo ci) {
        return ci.savedpc - 1;
    }

    // lstate.h: get_nresults (实例版)
    public int getNResults() {
        return getNResults(callstatus);
    }


    // isLua(ci)
    public boolean isLua() {
        return (callstatus & CIST_C) == 0;
    }

    // isLuacode(ci)
    public boolean isLuacode() {
        return (callstatus & (CIST_C | CIST_HOOKED)) == 0;
    }


    // lstate.h: base (macro)  -  java diff: 方法替代宏
    public int base() {
        return func + 1;
    }
}
