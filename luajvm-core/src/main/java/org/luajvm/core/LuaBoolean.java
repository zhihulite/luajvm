// ref: lobject.h (LUA_VFALSE / LUA_VTRUE)
// diff: C用tag variant区分false/true，Java用boolean字段
package org.luajvm.core;

public final class LuaBoolean extends LuaValue {
    static final LuaBoolean _TRUE = new LuaBoolean(true), _FALSE = new LuaBoolean(false);
    public final boolean v;

    LuaBoolean(boolean b) {
        super(b ? LUA_VTRUE : LUA_VFALSE);
        v = b;
    }

    // lobject.h: ttype
    @Override
    public int type() {
        return TBOOLEAN;
    }

    // ltm.h: ttypename
    @Override
    public String typeName() {
        return "boolean";
    }

    // lobject.h: ttisboolean
    @Override
    public boolean isboolean() {
        return true;
    }

    // lobject.h: l_isfalse
    @Override
    public boolean toboolean() {
        return v;
    }

    // l_isfalse取反
    @Override
    public LuaValue not() {
        return v ? LuaValue.FALSE : LuaValue.TRUE;
    }

    // java:
    @Override
    public String toJavaString() {
        return Boolean.toString(v);
    }

    // lobject.h: luaL_optboolean
    @Override
    public boolean optboolean(boolean d) {
        return v;
    }

    // lobject.h: luaL_checkboolean
    @Override
    public boolean checkboolean() {
        return v;
    }

    // lobject.h: luaV_rawequalobj
    @Override
    public boolean raweq(LuaValue r) {
        return r.isboolean() && r.toboolean() == v;
    }

    // lapi.c: lua_getmetatable
    @Override
    public LuaValue getmetatable() {
        // C：ltm.c : luaT_gettmbyobj  -  基础类型元表存于 G(L)->mt[t]
        Globals g = LuaStates.owner();
        return g == null ? null : g.typeMetatable(LuaValue.TBOOLEAN);
    }

    // lapi.c: lua_setmetatable
    @Override
    public LuaValue setmetatable(LuaValue mt) {
        // C：lapi.c : lua_setmetatable  -  写 G(L)->mt[t]
        Globals g = LuaStates.owner();
        if (g != null) g.setTypeMetatable(LuaValue.TBOOLEAN, mt);
        return this;
    }
}
