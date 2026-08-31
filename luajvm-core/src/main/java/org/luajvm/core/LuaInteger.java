// ref: lobject.h (LUA_VNUMINT)
// diff: C的ivalue(o)宏直接访问TValue.value_.i，Java用public final long v字段
// java-only: v 保持 final —— JVM 标量替换/TLAB 使短命 LuaInteger 近乎免费，
//   final 字段利于逃逸分析与内联优化。
package org.luajvm.core;

public final class LuaInteger extends LuaNumber {
    public final long v;

    LuaInteger(long i) {
        super(LUA_VNUMINT);
        v = i;
    }

    // lapi.c: lua_pushinteger  -  C 直接内联整数在 TValue.value_.i，无分配无缓存
    public static LuaInteger valueOf(int i) {
        return new LuaInteger(i);
    }

    public static LuaInteger valueOf(long i) {
        return new LuaInteger(i);
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
    // lobject.h: ttisinteger

    @Override
    public double todouble() {
        return v;
    }

    @Override
    public int toint() {
        return (int) v;
    }

    @Override
    public long tolong() {
        return v;
    }

    // lobject.h: l_isfalse
    @Override
    public boolean toboolean() {
        return true;
    }

    @Override
    public String toJavaString() {
        return Long.toString(v);
    }

    // lobject.h: luaV_tostring
    // java diff: valueOfLong 直接把数字写进 byte[]，跳过 Long.toString 中间 String（concat 数字热点）
    @Override
    public LuaString strValue() {
        return LuaString.valueOfLong(v);
    }

    @Override
    public LuaValue tostring() {
        return LuaString.valueOfLong(v);
    }

    // java-only
    @Override
    public boolean equals(Object o) {
        return o instanceof LuaInteger i && i.v == v;
    }

    // java-only
    @Override
    public int hashCode() {
        return Long.hashCode(v);
    }

    // lobject.h: luaV_rawequalobj
    @Override
    public boolean raweq(LuaValue r) {
        return r instanceof LuaInteger i && i.v == v;
    }

    // luaL_opt*
    @Override
    public boolean optboolean(boolean d) {
        return v != 0;
    }

    @Override
    public double optdouble(double d) {
        return v;
    }

    @Override
    public int optint(int d) {
        return (int) v;
    }

    @Override
    public long optlong(long d) {
        return v;
    }

    // luaL_check*
    @Override
    public double checkdouble() {
        return v;
    }

    @Override
    public int checkint() {
        return (int) v;
    }

    @Override
    public long checklong() {
        return v;
    }

    @Override
    public LuaInteger checkinteger() {
        return this;
    }

    // java:
    @Override
    public LuaString checkstring() {
        return LuaString.valueOfLong(v);
    }
}
