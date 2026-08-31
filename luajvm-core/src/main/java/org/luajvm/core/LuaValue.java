// ref: lobject.h (TValue)
// diff: OO 子类 + tt_ tag; 单一 LuaNil; 无 VSHRSTR/VLNGSTR 分离; LuaFunction/LuaClosure 代替 VLCL/VLCF/VCCL; TOSTRING/METATABLE 为 Java 特有
package org.luajvm.core;

public abstract class LuaValue extends Varargs {

    // LUA_T* 常量
    public static final int TNONE = -1, TNIL = 0, TBOOLEAN = 1, TLIGHTUSERDATA = 2,
            TNUMBER = 3, TSTRING = 4, TTABLE = 5, TFUNCTION = 6, TUSERDATA = 7, TTHREAD = 8;

    // makevariant tag variant 常量
    public static final int
            LUA_VNIL = 0x00,  // makevariant(TNIL, 0)
            LUA_VFALSE = 0x01,  // makevariant(TBOOLEAN, 0)
            LUA_VTRUE = 0x11,  // makevariant(TBOOLEAN, 1)
            LUA_VNUMINT = 0x03,  // makevariant(TNUMBER, 0)
            LUA_VNUMFLT = 0x13,  // makevariant(TNUMBER, 1)
            LUA_VSHRSTR = 0x04,  // makevariant(TSTRING, 0)
            LUA_VLNGSTR = 0x14,  // makevariant(TSTRING, 1)
            LUA_VTABLE = 0x05,  // makevariant(TTABLE, 0)
            LUA_VLCL = 0x06,  // makevariant(TFUNCTION, 0)  -  Lua 闭包
            LUA_VLCF = 0x16,  // makevariant(TFUNCTION, 1)  -  轻 C 函数
            LUA_VCCL = 0x26,  // makevariant(TFUNCTION, 2)  -  C 闭包
            LUA_VUSERDATA = 0x07,  // makevariant(TUSERDATA, 0)
            LUA_VLIGHTUD = 0x02,  // makevariant(TLIGHTUSERDATA, 0)
            LUA_VTHREAD = 0x08;  // makevariant(TTHREAD, 0)

    // BIT_ISCOLLECTABLE
    public static final int BIT_ISCOLLECTABLE = 1 << 6;

    // lua.h: LUA_MULTRET
    public static final int LUA_MULTRET = -1;
    // java diff: C 布尔/整数内联在 TValue；Java 必须装箱
    // java-only: 便捷常量
    public static final LuaBoolean TRUE = LuaBoolean._TRUE;
    public static final LuaBoolean FALSE = LuaBoolean._FALSE;
    // java diff: ZERO/ONE/MINUSONE  -  C 整数内联在 TValue.value_.i 无需分配，Java 必须装箱为 LuaInteger
    public static final LuaNumber ZERO = LuaInteger.valueOf(0);
    public static final LuaNumber ONE = LuaInteger.valueOf(1);
    public static final LuaNumber MINUSONE = LuaInteger.valueOf(-1);
    // TMS 元方法标签
    public static final LuaString INDEX = Metamethod.INDEX.tag;
    public static final LuaString NEWINDEX = Metamethod.NEWINDEX.tag;
    public static final LuaString CALL = Metamethod.CALL.tag;
    public static final LuaString MODE = Metamethod.MODE.tag;
    // java-only: C 的 TMS 枚举中无 TM_METATABLE
    public static final LuaString METATABLE = LuaString.newStr("__metatable");
    // java-only: __pairs/__ipairs 非 TMS 事件（Lua 5.5 由 luaL 层处理），供 JavaCollection 自定义
    public static final LuaString PAIRS = LuaString.newStr("__pairs");
    public static final LuaString IPAIRS = LuaString.newStr("__ipairs");
    public static final LuaString ADD = Metamethod.ADD.tag;
    public static final LuaString SUB = Metamethod.SUB.tag;
    public static final LuaString DIV = Metamethod.DIV.tag;
    public static final LuaString MUL = Metamethod.MUL.tag;
    public static final LuaString POW = Metamethod.POW.tag;
    public static final LuaString MOD = Metamethod.MOD.tag;
    public static final LuaString IDIV = Metamethod.IDIV.tag;
    public static final LuaString UNM = Metamethod.UNM.tag;
    public static final LuaString LEN = Metamethod.LEN.tag;
    public static final LuaString EQ = Metamethod.EQ.tag;
    public static final LuaString LT = Metamethod.LT.tag;
    public static final LuaString LE = Metamethod.LE.tag;
    public static final LuaString CONCAT = Metamethod.CONCAT.tag;
    // java-only: C 的 TMS 枚举中无 TM_TOSTRING
    public static final LuaString TOSTRING = LuaString.newStr("__tostring");
    public static final LuaString BAND = Metamethod.BAND.tag;
    public static final LuaString BOR = Metamethod.BOR.tag;
    public static final LuaString BXOR = Metamethod.BXOR.tag;
    public static final LuaString SHL = Metamethod.SHL.tag;
    public static final LuaString SHR = Metamethod.SHR.tag;
    public static final LuaString BNOT = Metamethod.BNOT.tag;
    public static final LuaString GC = Metamethod.GC.tag;
    public static final LuaString CLOSE = Metamethod.CLOSE.tag;
    public static final LuaValue NIL = LuaNil._NIL;
    public static final LuaValue NONE = _None._NONE;
    // java-only: NOVALS
    public static final LuaValue[] NOVALS = {};
    // lstate.h: GCObject age constants (G_NEW=0 .. G_TOUCHED2=6)
    // java-only
    public static final int G_NEW = 0, G_SURVIVAL = 1, G_OLD0 = 2, G_OLD1 = 3, G_OLD = 4, G_TOUCHED1 = 5, G_TOUCHED2 = 6;
    // lobject.h: tt_
    public final int tt_;
    // java-only: 可收集对象的 GC 年龄；默认 G_NEW
    public byte gcAge = G_NEW;
    // lgc.h: WHITE0BIT=3, WHITE1BIT=4, BLACKBIT=5
    // java diff: C 用 'marked' 的位域；Java 用 gcColor 字节
    // gcColor: 0=WHITE0, 1=WHITE1, 2=GRAY, 3=BLACK; 默认 WHITE0
    public byte gcColor = 0;
    // lgc.c: getgclist  -  侵入式灰列表 next 指针（对齐 C GCObject.gclist）
    // java-only: 替代 ArrayDeque 做零开销 LIFO push/pop；非 GRAY 对象 gclist 必须为 null
    public LuaValue gclist = null;

    // C：lgc.c : sweeplist 的 makewhite  -  置为当前白并脱离灰链
    // 白对象必须不在任何灰链中，否则再次入链会覆盖 gclist 截断原链（单归属违规）
    public final void makeWhite(byte cw) {
        gcColor = cw;
        gclist = null;
    }

    protected LuaValue(int tt_) {
        this.tt_ = tt_;
    }


    // ltm.c: luaT_objtypename
    // java diff: C/Java 均每次查 __name（无缓存）
    public static String objTypeName(LuaValue o) {
        if (o != null && (o.istable() || o.isuserdata())) {
            LuaValue mt = o.getmetatable();
            if (mt instanceof LuaTable table) {
                LuaValue name = table.fastGetShortStr(LuaString.newStr("__name"));
                if (name != null && name instanceof LuaString s) return s.toJavaString();
            } else if (mt != null) {
                LuaValue name = mt.rawget(LuaString.newStr("__name"));
                if (name instanceof LuaString s) return s.toJavaString();
            }
        }
        return o == null ? "nil" : o.typeName();
    }

    // lauxlib.c: luaL_checkany
    public static LuaValue checkany(int i, Varargs args) {
        if (args.narg() < i) LuaErrors.argError(i, "value expected");
        return args.arg(i);
    }

    // ======= 工厂方法 =======
    // lapi.c: lua_pushboolean
    public static LuaBoolean valueOf(boolean b) {
        return b ? TRUE : FALSE;
    }

    // lapi.c: lua_pushinteger
    public static LuaInteger valueOf(int i) {
        return LuaInteger.valueOf(i);
    }

    // java-only: valueOf(long) 重载
    public static LuaInteger valueOf(long i) {
        return LuaInteger.valueOf(i);
    }

    // lapi.c: lua_pushnumber
    public static LuaNumber valueOf(double d) {
        return LuaFloat.valueOf(d);
    }

    // lobject.h: lua_pushlstring
    public static LuaString valueOf(String s) {
        return LuaString.newStr(s);
    }

    // java-only: valueOf(byte[]) 重载
    public static LuaString valueOf(byte[] b) {
        return LuaString.newLstr(b, 0, b.length);
    }

    // lapi.c: lua_createtable
    public static LuaTable tableOf() {
        return new LuaTable();
    }

    // java-only: tableOf 便利重载
    public static LuaTable tableOf(LuaValue[] kv) {
        LuaTable t = new LuaTable();
        for (int i = 0; i < kv.length - 1; i += 2) t.setEntry(kv[i], kv[i + 1]);
        return t;
    }

    public static LuaTable tableOf(LuaValue k, LuaValue v) {
        LuaTable t = new LuaTable();
        t.setEntry(k, v);
        return t;
    }

    // lobject.h: lua_newuserdata
    public static LuaUserdata userdataOf(Object o) {
        return new LuaUserdata(o);
    }

    // ====== C API 类型检查（含强制转换语义） ======


    // java-only: varargsOf
    public static Varargs varargsOf(LuaValue[] v) {
        return Varargs.of(v);
    }

    public static Varargs varargsOf(LuaValue a, Varargs b) {
        return Varargs.of(a, b);
    }

    // java diff: C 用栈传递多返回值，Java 用 Varargs 容器
    // Varargs.of(a,b) 返回 VarargsPair 无数组，比 Varargs.of(new LuaValue[]{a,b}) 轻
    public static Varargs varargsOf(LuaValue a, LuaValue b) {
        return Varargs.of(a, b);
    }


    // ====== 值访问宏 ======

    public static Varargs varargsOf(LuaValue a, LuaValue b, LuaValue c) {
        return Varargs.of(a, b, c);
    }

    // lgc.c: objsize  -  GCmarked 跟踪的内存大小
    // java diff: C 按对象类型精确计算，Java 用近似值
    public int gcSize() {
        return 64;
    }

    // lobject.h: ttisnil
    public boolean isnil() {
        return tt_ == LUA_VNIL;
    }

    // lobject.h: ttisboolean
    public boolean isboolean() {
        return (tt_ & 0x0F) == TBOOLEAN;
    }

    // lobject.h: ttisfloat
    public boolean isfloat() {
        return tt_ == LUA_VNUMFLT;
    }

    // lobject.h: ttistable
    public boolean istable() {
        return tt_ == (LUA_VTABLE | BIT_ISCOLLECTABLE);
    }

    // lobject.h: ttisfunction
    public boolean isfunction() {
        return (tt_ & 0x0F) == TFUNCTION;
    }

    // lobject.h: ttisLclosure
    public boolean isLclosure() {
        return tt_ == (LUA_VLCL | BIT_ISCOLLECTABLE);
    }

    // lobject.h: ttislcf
    public boolean islcf() {
        return tt_ == LUA_VLCF;
    }

    // lobject.h: ttisCclosure
    public boolean isCclosure() {
        return tt_ == (LUA_VCCL | BIT_ISCOLLECTABLE);
    }

    // lobject.h: ttisfulluserdata
    public boolean isfulluserdata() {
        return tt_ == (LUA_VUSERDATA | BIT_ISCOLLECTABLE);
    }

    // ======= Java 特有方法 =======

    // lobject.h: ttislightuserdata
    public boolean islightuserdata() {
        return tt_ == LUA_VLIGHTUD;
    }

    // lobject.h: ttisthread
    public boolean isthread() {
        return tt_ == (LUA_VTHREAD | BIT_ISCOLLECTABLE);
    }

    // lobject.h: ttisnumber  -  java diff: 严格标签检查；isnumber 在子类为 API 可转换语义，勿合并
    public boolean isNumberTag() {
        return (tt_ & 0x0F) == TNUMBER;
    }

    // lobject.h: iscollectable
    public boolean iscollectable() {
        return (tt_ & BIT_ISCOLLECTABLE) != 0;
    }

    // lobject.h: ttisuserdata
    public boolean isuserdata() {
        return isfulluserdata() || islightuserdata();
    }

    // lua.h: lua_isnumber  -  基类为严格标签，对齐 C lua_isnumber=tonumber（非字符串值即看标签）；LuaString 覆写为可扫描
    public boolean isnumber() {
        return isNumberTag();
    }

    // lua.h: lua_isstring  -  严格标签；C 的 cvt2str 转换场景由调用方处理
    public boolean isstring() {
        return (tt_ & 0x0F) == TSTRING;
    }

    // lua.h: lua_isinteger  -  严格标签，对齐 C lua_isinteger=ttisinteger
    public boolean isinteger() {
        return tt_ == LUA_VNUMINT;
    }

    // lobject.h: ttype
    public abstract int type();

    // ltm.h: ttypename
    public abstract String typeName();

    // lobject.h: l_isfalse
    public boolean toboolean() {
        return !isnil();
    }

    public double todouble() {
        return 0;
    }

    public int toint() {
        return 0;
    }

    public long tolong() {
        return 0;
    }

    // lobject.h: luaV_tonumber
    public LuaValue tonumber() {
        return NIL;
    }

    // lobject.h: luaV_tostring
    public LuaValue tostring() {
        return NIL;
    }

    public String toJavaString() {
        return typeName() + ": " + Integer.toHexString(hashCode());
    }

    // java-only: toString
    @Override
    public String toString() {
        return toJavaString();
    }

    // lobject.h: tsvalue
    public LuaString strValue() {
        return null;
    }

    // lobject.h: getudatamem
    public Object touserdata() {
        return null;
    }

    // java-only: touserdata 带 Class 参数
    public <T> T touserdata(Class<T> c) {
        return null;
    }

    // lvm.c: luaV_objlen
    public LuaValue len() {
        return LuaErrors.error("attempt to get length of a " + typeName() + " value");
    }

    // java-only: length = rawlen 便利
    public int length() {
        return rawlen();
    }

    // l_isfalse 取反
    public LuaValue not() {
        return FALSE;
    }

    // luaL_opt*
    public boolean optboolean(boolean d) {
        return d;
    }

    public double optdouble(double d) {
        return d;
    }

    public int optint(int d) {
        return d;
    }

    public long optlong(long d) {
        return d;
    }

    public LuaValue optvalue(LuaValue d) {
        return this;
    }

    // java-only: optJavaString 返回 Java String
    public String optJavaString(String d) {
        return d;
    }

    // java-only: optuserdata 带 Class 参数
    public Object optuserdata(Class<?> c, Object d) {
        return d;
    }

    // java-only: isuserdata 带 Class 参数
    public boolean isuserdata(Class<?> c) {
        return false;
    }

    // luaL_check*
    public double checkdouble() {
        typeError("number");
        return 0;
    }

    public int checkint() {
        typeError("int");
        return 0;
    }

    public long checklong() {
        typeError("long");
        return 0;
    }

    public LuaNumber checknumber() {
        typeError("number");
        return null;
    }

    public LuaNumber checknumber(String m) {
        throw LuaErrors.errorObject(m);
    }

    public LuaInteger checkinteger() {
        typeError("integer");
        return null;
    }

    public LuaString checkstring() {
        typeError("string");
        return null;
    }

    public LuaTable checktable() {
        typeError("table");
        return null;
    }

    public LuaFunction checkfunction() {
        typeError("function");
        return null;
    }

    public boolean checkboolean() {
        typeError("boolean");
        return false;
    }

    public LuaValue checkNotNil() {
        return this;
    }

    // java-only: checkglobals
    public Globals checkglobals() {
        typeError("globals");
        return null;
    }

    public LuaThread checkthread() {
        typeError("thread");
        return null;
    }

    public Object checkuserdata() {
        typeError("userdata");
        return null;
    }

    // java-only: checkuserdata 带 Class 参数
    public Object checkuserdata(Class<?> c) {
        typeError("userdata");
        return null;
    }

    // java-only: checkJavaString
    public String checkJavaString() {
        return checkstring().toJavaString();
    }

    // java-only: checkdouble 带消息
    public double checkdouble(String m) {
        throw LuaErrors.errorObject(m);
    }

    protected LuaValue typeError(String e) {
        throw LuaErrors.errorObject(e + " expected, got " + objTypeName(this));
    }

    // ======= 表操作 =======
    // ltable.c: luaH_get
    public LuaValue rawget(LuaValue k) {
        typeError("table");
        return null;
    }

    // java-only: rawget(int) 便利重载
    public LuaValue rawget(int k) {
        return rawget(LuaInteger.valueOf(k));
    }

    // ltable.c: luaH_set
    public void rawset(LuaValue k, LuaValue v) {
        typeError("table");
    }

    // java-only: rawset 便利重载
    public void rawset(int k, LuaValue v) {
        rawset(LuaInteger.valueOf(k), v);
    }

    public void rawset(int k, String v) {
        rawset(k, LuaString.newStr(v));
    }

    public void rawset(String k, LuaValue v) {
        rawset(LuaString.newStr(k), v);
    }

    public void set(String k, LuaValue v) {
        rawset(LuaString.newStr(k), v);
    }

    public void set(LuaValue k, LuaValue v) {
        rawset(k, v);
    }

    public LuaValue get(LuaValue k) {
        return rawget(k);
    }

    public LuaValue get(String k) {
        return rawget(LuaString.newStr(k));
    }

    public void set(String k, LuaFunction v) {
        rawset(LuaString.newStr(k), v);
    }

    // ltable.c: luaH_next
    public Varargs next(LuaValue i) {
        typeError("table");
        return null;
    }

    public Varargs inext(LuaValue i) {
        typeError("table");
        return null;
    }

    // lobject.h: lua_objlen
    public int rawlen() {
        typeError("table or string");
        return 0;
    }

    // lobject.h: luaV_rawequalobj
    public boolean raweq(LuaValue r) {
        return this == r;
    }

    // ======= 元表操作 =======
    // lapi.c: lua_getmetatable
    public LuaValue getmetatable() {
        return null;
    }

    // lapi.c: lua_setmetatable
    public LuaValue setmetatable(LuaValue mt) {
        return this;
    }

    // lobject.h: luaT_gettmbyobj
    public LuaValue metaTag(LuaValue tag) {
        LuaValue mt = getmetatable();
        if (mt == null) return NIL;
        LuaValue mm = mt.rawget(tag);
        return mm == null ? NIL : mm;
    }

    // java-only: get(int) 便利重载
    public LuaValue get(int k) {
        return get(LuaInteger.valueOf(k));
    }

    // ======= Varargs 接口 =======
    @Override
    public LuaValue arg(int i) {
        return i == 1 ? this : NIL;
    }

    @Override
    public int narg() {
        return 1;
    }

    @Override
    public LuaValue arg1() {
        return this;
    }

    @Override
    public Varargs subargs(int s) {
        return s <= 1 ? this : NONE;
    }

    // java-only: NONE 哨兵值（LUA_TNONE 语义）
    private static final class _None extends LuaNil {
        static final _None _NONE = new _None();

        public LuaValue arg(int i) {
            return NIL;
        }

        public int narg() {
            return 0;
        }

        public LuaValue arg1() {
            return NIL;
        }

        public String toJavaString() {
            return "none";
        }
    }

}
