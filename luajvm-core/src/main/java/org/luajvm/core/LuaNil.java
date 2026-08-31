// ref: lobject.h (LUA_VNIL)
// diff: C有VNIL/VEMPTY/VABSTKEY/VNOTABLE四种nil变体，Java只有单一LuaNil
package org.luajvm.core;

public class LuaNil extends LuaValue {
    static final LuaNil _NIL = new LuaNil();

    LuaNil() {
        super(LUA_VNIL);
    }

    // lobject.h: ttype
    @Override
    public int type() {
        return TNIL;
    }

    // ltm.h: ttypename
    @Override
    public String typeName() {
        return "nil";
    }

    @Override
    public String toJavaString() {
        return "nil";
    }

    // lobject.h: l_isfalse
    @Override
    public boolean toboolean() {
        return false;
    }

    // lobject.h: ttisnil
    @Override
    public boolean isnil() {
        return true;
    }

    // java-only
    @Override
    public boolean equals(Object o) {
        return o instanceof LuaNil;
    }

    // lobject.h: luaV_rawequalobj
    @Override
    public boolean raweq(LuaValue r) {
        return r != null && r.isnil();
    }

    // lobject.h: luaL_optvalue
    @Override
    public LuaValue optvalue(LuaValue d) {
        return d;
    }

    // java-only nil 始终返回默认值
    @Override
    public boolean optboolean(boolean d) {
        return d;
    }

    // lapi.c: lua_getmetatable
    @Override
    public LuaValue getmetatable() {
        // C：ltm.c : luaT_gettmbyobj  -  基础类型元表存于 G(L)->mt[t]
        Globals g = LuaStates.owner();
        return g == null ? null : g.typeMetatable(LuaValue.TNIL);
    }

    // lapi.c: lua_setmetatable
    @Override
    public LuaValue setmetatable(LuaValue mt) {
        // C：lapi.c : lua_setmetatable  -  写 G(L)->mt[t]
        Globals g = LuaStates.owner();
        if (g != null) g.setTypeMetatable(LuaValue.TNIL, mt);
        return this;
    }
}
