// ref: lobject.h (UpVal) / lfunc.c
// diff: parentStack+slot 替代 TValue* 指针；UpVal 非 GC 对象（C 是 GCObject 带 marked） -
// diff: luaF_closeupval 的 nw2black+barrier(L,uv,slot) 简化为 barrier(value)
package org.luajvm.core;

public final class UpVal {
    // lfunc.c: luaF_newtbcupval  -  tbc 标记
    // java diff: C 的 tbclist 是栈上 StkId 的 delta 链表，Java 用 UpVal.tbc 标记 + L.tbclist 整数
    public byte tbc;
    // lobject.h: UpVal.v  -  开放时指向栈槽，闭合时指向 u.value
    // java diff: C 用 TValue* 指针 + union，Java 用 parentStack+slot 模拟指针
    private LuaValue[] parentStack;  // 开放: 指向父栈; 闭合: null
    private int slot;                // 开放: 栈槽索引
    // lobject.h: UpVal.u.value  -  闭合后保存的值
    private LuaValue value;

    // lfunc.c: newupval  -  创建开放upvalue
    // java diff: C用luaC_newobj+双向链表插入；Java只创建对象，链表由LuaThread.openupval管理
    public UpVal(LuaValue[] parentStack, int slot) {
        this.parentStack = parentStack;
        this.slot = slot;
        this.value = LuaValue.NIL;
    }

    // lfunc.c: luaF_initupvals  -  创建闭合upvalue
    // java diff: C用luaC_newobj(L, LUA_VUPVAL, sizeof(UpVal))+luaC_objbarrier；
    // Java中UpVal不是GC对象，不需要luaC_newobj/luaC_objbarrier
    public UpVal(LuaValue value) {
        this.parentStack = null;
        this.value = value;
    }

    // lfunc.c: luaF_initupvals  -  创建闭合upvalue的工厂方法
    public static UpVal closedOf(LuaValue value) {
        return new UpVal(value);
    }

    // lobject.h: uplevel  -  获取upvalue当前值
    // C: *uv->v.p (开放时读栈槽，闭合时读u.value)
    public LuaValue get() {
        return parentStack != null ? parentStack[slot] : value;
    }

    // lfunc.c: luaF_close / luaV_finishset (写入路径)
    // C: *uv->v.p = value (开放时写栈槽，闭合时写u.value)
    public void set(LuaValue v) {
        if (parentStack != null) {
            parentStack[slot] = v;
        } else this.value = v;
    }

    // lfunc.c: luaF_closeupval  -  关闭开放 upvalue
    // java diff: UpVal 非 GC 对象 - barrier(L,uv,slot) 简化为 barrier(value)
    // java-only: 仅 needRepropagate=true（存在 BLACK 闭包）时触发 barrier -
    //   false 时所有对象 WHITE，markRoots->propagateOne 直接标记 upvalue 值，
    //   barrier 冗余且会破坏 fullGC 的 repropagateAll 跳过优化
    public void closeUpval() {
        if (parentStack != null) {
            this.value = parentStack[slot];
            this.parentStack = null;
        }
    }

    // java-only: 栈扩容后重新绑定开放 upvalue 的数组引用（C 用 StkId 指针 realloc 自动更新）
    public void rebindStack(LuaValue[] oldStack, LuaValue[] newStack) {
        if (parentStack == oldStack) parentStack = newStack;
    }

    // lfunc.h: upisopen  -  (up)->v.p != &(up)->u.value
    public boolean upisopen() {
        return parentStack != null;
    }

    // ldebug.c: getupvalname  -  用共享栈数组+槽索引模拟 TValue* 比较
    public boolean isOpenAt(LuaValue[] stack, int stackSlot) {
        return parentStack == stack && slot == stackSlot;
    }

    // lfunc.h: uplevel  -  返回栈槽索引（C 返回 StkId 指针）
    public int slot() {
        return slot;
    }
}
