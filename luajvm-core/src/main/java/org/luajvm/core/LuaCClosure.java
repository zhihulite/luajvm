// ref: lobject.h (CClosure), lfunc.c:luaF_newCclosure, lapi.c:lua_pushcclosure
// diff: Java没有lua_CFunction函数指针；C函数体通过LuaFunction子类/override承载，upvalue槽位按CClosure保存。
package org.luajvm.core;

import java.util.Arrays;

public abstract class LuaCClosure extends LuaFunction {
    // CClosure.upvalue
    public final LuaValue[] upvalue;
    // java: 对应lapi.c:lua_upvalueid返回的&f->upvalue[n-1]
    private final Object[] upvalueid;

    // lfunc.c: luaF_newCclosure  -  create new C closure with nupvalues upvalues
    // C: luaC_newobj(L, LUA_VCCL, sizeCclosure(n))
    // java diff: C用luaC_newobj走frealloc；Java中CClosure的内存由JVM GC管理
    protected LuaCClosure(int nupvalues) {
        super(LUA_VCCL | BIT_ISCOLLECTABLE);
        this.upvalue = new LuaValue[nupvalues];
        Arrays.fill(this.upvalue, LuaValue.NIL);
        this.upvalueid = new Object[nupvalues];
        for (int i = 0; i < nupvalues; i++) this.upvalueid[i] = new Object();
    }

    // lobject.h: ClosureHeader.nupvalues
    @Override
    public int nupvalues() {
        return upvalue.length;
    }

    // lapi.c: aux_upvalue
    public LuaValue upvalue(int n) {
        return n >= 1 && n <= upvalue.length ? upvalue[n - 1] : LuaValue.NIL;
    }

    // lapi.c: lua_setupvalue
    public boolean setupvalue(int n, LuaValue value) {
        if (n < 1 || n > upvalue.length) return false;
        upvalue[n - 1] = value;
        return true;
    }

    // lapi.c: lua_upvalueid
    public Object upvalueid(int n) {
        return n >= 1 && n <= upvalueid.length ? upvalueid[n - 1] : null;
    }

    // lgc.c: traverseCclosure  -  标记 CClosure 的全部 upvalue
    // java diff: C 直接 markvalue(&cl->upvalue[i])；Java 经 LuaFunction.gcRefs() 交给
    //   LuaTable.propagateOne 的 LUA_VCCL 分支遍历。不覆写则该分支拿到默认空数组
    //   （NOVALS）⇒ upvalue 里的对象在标记阶段完全不可见，被 sweep 摘除并提前终结。
    @Override
    public LuaValue[] gcRefs() {
        return upvalue;
    }
}
