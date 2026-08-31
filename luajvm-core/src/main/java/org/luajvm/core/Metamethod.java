// ref: ltm.c
// diff: luaT_callTM/luaT_trybinTM/luaT_callorderTM在LuaVM.java中实现（栈操作版本）
// diff: 缺少maskflags缓存掩码和gfasttm; ordinal()对应LuaTable.flags位号
package org.luajvm.core;

public enum Metamethod {
    INDEX("__index"), NEWINDEX("__newindex"), GC("__gc"), MODE("__mode"),
    LEN("__len"), EQ("__eq"), ADD("__add"), SUB("__sub"), MUL("__mul"),
    MOD("__mod"), POW("__pow"), DIV("__div"), IDIV("__idiv"),
    BAND("__band"), BOR("__bor"), BXOR("__bxor"), SHL("__shl"), SHR("__shr"),
    UNM("__unm"), BNOT("__bnot"), LT("__lt"), LE("__le"),
    CONCAT("__concat"), CALL("__call"), CLOSE("__close");

    // ltm.c: tmname
    public final LuaString tag;

    Metamethod(String t) {
        tag = LuaString.newStr(t);
    }


    // ==================================================================
    // ltm.c  -  元方法查找与调用
    // ==================================================================

    // ltm.c: luaT_gettm
    public static LuaValue getTm(LuaTable events, Metamethod event, LuaString ename) {
        // C: luaH_Hgetshortstr —— 元方法名恒为驻留短串，走身份比较专路（不经 normalizeKey）
        LuaValue tm = events.fastGetShortStr(ename);
        if (tm == null) {  // C: notm(tm)  -  no tag method?
            events.flags |= (byte) (1 << event.ordinal());  // cache this fact
            return null;
        }
        return tm;
    }

    // ltm.c: luaT_gettmbyobj
    // java diff: C用ttype分派(LUA_TTABLE/LUA_TUSERDATA/default); Java统一用getmetatable()
    public static LuaValue getTmByObj(LuaThread L, LuaValue o, Metamethod event) {
        // C：ttype 分派 - 表/userdata 用自身元表，其余取 G(L)->mt[ttype(o)]。
        // 有 L 时直接用 L.l_G，不经登记表解析（既是 C 的语义，也省一次查找）。
        LuaValue mt = (L != null && L.l_G != null && !(o instanceof LuaTable)
                && !(o instanceof LuaUserdata))
                ? L.l_G.typeMetatable(o.type())
                : o.getmetatable();
        if (mt == null) return null;
        if (mt instanceof LuaTable table) return getTm(table, event, event.tag);
        LuaValue tm = mt.rawget(event.tag);
        return (tm != null && !tm.isnil()) ? tm : null;
    }

    // ltm.c: luaT_gettmbyobj
    // ltm.h: fasttm  -  先查 flags 缓存再查找
    // java diff: C 的 fasttm 是宏，Java 用普通方法查找
    public LuaValue lookup(LuaValue v) {
        LuaValue mt = v.getmetatable();
        if (mt == null) return null;
        if (mt instanceof LuaTable table) {
            // ltm.h: : if (cast_byte(mt->flags & (1<<tm))) return NULL
            if ((table.flags & (1 << ordinal())) != 0) return null;
            LuaValue r = table.fastGetShortStr(tag);
            if (r == null || r.isnil()) {
                // ltm.h: : 无元方法 -> 缓存缺失
                table.flags |= (byte) (1 << ordinal());
                return null;
            }
            return r;
        }
        LuaValue fn = mt.rawget(tag);
        return (fn != null && !fn.isnil()) ? fn : null;
    }

    // ==================================================================
    // ltm.c  -  元方法调用函数（栈操作版本，在LuaVM.java中实现）
    // luaT_callTM    -> LuaVM: 无独立函数，luaV_finishset中用callTMres替代
    // luaT_callTMres -> LuaVM.callTMres
    // callbinTM      -> LuaVM.callbinTM
    // luaT_trybinTM  -> LuaVM.tryBinTM
    // luaT_tryconcatTM -> LuaVM.concatSharedStack中内联
    // luaT_trybinassocTM -> LuaVM.tryBinAssocTM
    // luaT_trybiniTM -> LuaVM.tryBiniTM
    // luaT_callorderTM -> LuaVM.callOrderTM
    // luaT_callorderiTM -> OP_LTI/OP_LEI/OP_GTI/OP_GEI中内联
    // ==================================================================
}
