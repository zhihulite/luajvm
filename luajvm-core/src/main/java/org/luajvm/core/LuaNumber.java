// ref: lobject.h (LUA_TNUMBER)
// diff: C用VNUMINT/VNUMFLT tag variant区分整数/浮点，Java用LuaInteger/LuaFloat子类
package org.luajvm.core;

import org.luajvm.vm.LuaCall;

public abstract class LuaNumber extends LuaValue {

    protected LuaNumber(int tt_) {
        super(tt_);
    }

    // lobject.h: ttype
    @Override
    public int type() {
        return TNUMBER;
    }

    // ltm.h: ttypename
    @Override
    public String typeName() {
        return "number";
    }

    // lapi.c: lua_getmetatable
    @Override
    public LuaValue getmetatable() {
        // C：ltm.c : luaT_gettmbyobj  -  基础类型元表存于 G(L)->mt[t]
        Globals g = LuaStates.owner();
        return g == null ? null : g.typeMetatable(LuaValue.TNUMBER);
    }

    // lapi.c: lua_setmetatable
    @Override
    public LuaValue setmetatable(LuaValue mt) {
        // C：lapi.c : lua_setmetatable  -  写 G(L)->mt[t]
        Globals g = LuaStates.owner();
        if (g != null) g.setTypeMetatable(LuaValue.TNUMBER, mt);
        return this;
    }

    // lauxlib.c: luaL_checknumber
    @Override
    public LuaNumber checknumber() {
        return this;
    }

    @Override
    public LuaNumber checknumber(String m) {
        return this;
    }

    // lobject.h: luaV_tonumber
    @Override
    public LuaValue tonumber() {
        return this;
    }

    // lvm.c: luaV_objlen (default branch)  -  number 也有 __len metamethod
    //   （sort.lua "unpack with non-tables" 给 number 设 __len/__index）
    @Override
    public LuaValue len() {
        LuaValue mt = getmetatable();
        if (mt != null && !mt.isnil()) {
            LuaValue mm = mt.rawget(LuaValue.LEN);
            if (!mm.isnil()) {
                return LuaCall.invoke(mm, this).arg1();
            }
        }
        return LuaErrors.error("attempt to get length of a " + typeName() + " value");
    }
}
