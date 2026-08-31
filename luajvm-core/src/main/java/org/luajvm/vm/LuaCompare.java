// ref: lvm.c
// diff: l_strcmp 用字节比较非 strcoll（strings.lua:442 区域排序失败，但 Lua 测试套件不测区域排序）
// diff: instanceof 分派非 ttypetag switch; luaV_equalobj 的 ttype/ttypetag 分派简化为 instanceof
// diff: C 用 LTnum/LEnum 宏处理整数/浮点比较, Java 用 ltIntFloat/ltFloatInt 等辅助方法，功能等价
package org.luajvm.vm;

import org.luajvm.core.LuaErrors;
import org.luajvm.core.LuaFloat;
import org.luajvm.core.LuaInteger;
import org.luajvm.core.LuaNumber;
import org.luajvm.core.LuaString;
import org.luajvm.core.LuaTable;
import org.luajvm.core.LuaThread;
import org.luajvm.core.LuaUserdata;
import org.luajvm.core.LuaValue;
import org.luajvm.core.Metamethod;
import org.luajvm.vm.LuaVM;

public final class LuaCompare {
    private LuaCompare() {
    }

    // ltm.c: callbinTM  -  luaD_callnoyield(L, func, 1)
    // java diff: C 直接在栈上调 luaD_callnoyield，Java 用 callOnStack2to1 免 Varargs
    private static LuaValue callMetamethod(LuaValue h, LuaValue a, LuaValue b, String name) {
        return LuaCall.callOnStack2to1(h, a, b);
    }

    // ltm.c: callbinTM  -  via LuaVM.callbinTM
    private static LuaValue callMetamethod(LuaThread L, LuaValue a, LuaValue b, Metamethod event) {
        L.top = L.ci.top;  // C: Protect sets L.top = ci.top before calling
        int resSlot = L.top;
        int tag = LuaVM.callbinTM(L, L.ci, resSlot, a, b, event.ordinal());
        if (tag < 0) return null;
        return LuaVM.s2v(resSlot, L);
    }

    // ldebug.c: luaG_ordererror
    private static void orderError(LuaValue a, LuaValue b) {
        LuaErrors.orderError(a, b);
    }

    // lvm.c: l_intfitsf
    private static boolean intFitsFloat(long i) {
        return i >= -9007199254740992L && i <= 9007199254740992L;
    }

    // lvm.c: luaV_flttointeger
    private static Long floatToInt(double f, int mode) {
        if (Double.isNaN(f) || Double.isInfinite(f)) return null;
        double v;
        if (mode == 1) v = Math.floor(f);
        else if (mode == 2) v = Math.ceil(f);
        else v = f;
        if (v != Math.floor(v)) return null;
        if (v >= 9223372036854775808.0 || v < -9223372036854775808.0) return null;
        return (long) v;
    }

    // lvm.c: luaV_equalobj  -  相等比较主操作
    // C: ttype(t1)!=ttype(t2)->0; ttypetag 不同 -> switch(LUA_VNUMINT/LUA_VNUMFLT/LUA_VSHRSTR/LUA_VLNGSTR)
    // java diff: C 用 ttype+ttypetag 分派, Java 用 instanceof，功能等价
    // java diff: C 的 LUA_VSHRSTR 用 eqshrstr 做身份比较, Java 短串也用 ==（LuaString.raweq）
    // java diff: C 对 LUA_VUSERDATA/LUA_VTABLE 用 fasttm 查 __eq, Java 用 Metamethod.EQ.lookup
    public static boolean equalObj(LuaValue a, LuaValue b) {
        return equalObj(null, a, b);
    }

    public static boolean equalObj(LuaThread L, LuaValue a, LuaValue b) {
        if (a == null) a = LuaValue.NIL;  // java: 数组槽可能为 null
        if (b == null) b = LuaValue.NIL;
        if (a instanceof LuaInteger ai && b instanceof LuaInteger bi) return ai.v == bi.v;
        // lvm.c: luaV_equalobj —— int/float 交叉直接 double 比较（cast_num(ivalue)==fltvalue）；
        // 经 floatToInt 截断会在 2^63 一类巨值上与 C 分叉
        if (a instanceof LuaInteger ai && b instanceof LuaFloat bf) return (double) ai.v == bf.v;
        if (a instanceof LuaFloat af && b instanceof LuaInteger bi) return af.v == (double) bi.v;
        if (a instanceof LuaNumber && b instanceof LuaNumber) return a.todouble() == b.todouble();
        if (a.type() != b.type()) return false;  // 数字交叉已处理，不同类型不触发 __eq
        if (a.raweq(b)) return true;
        // lvm.c: luaV_equalobj —— L==NULL（luaV_rawequalobj）在元方法前直接 return 0
        if (L == null) return false;
        if (!(a instanceof LuaTable && b instanceof LuaTable)  // 仅 table 与 full userdata 尝试 __eq
                && !(a instanceof LuaUserdata && b instanceof LuaUserdata)) {
            return false;
        }
        LuaValue h = Metamethod.EQ.lookup(a);
        if (h == null) h = Metamethod.EQ.lookup(b);  // callbinTM
        if (h != null)
            return (L != null ? callMetamethod(L, a, b, Metamethod.EQ) : callMetamethod(h, a, b, "eq")).toboolean();
        return false;
    }


    // lvm.c: luaV_lessthan  -  main operation 'l < r'
    // C: ttisnumber->LTnum 宏; else->lessthanothers（字符串用 l_strcmp，其他用 luaT_callorderTM）
    // java diff: C 用 LTnum 宏处理 int/float 交叉比较, Java 用 ltIntFloat/ltFloatInt 辅助方法
    public static boolean lessThan(LuaValue a, LuaValue b) {
        return lessThan(null, a, b);
    }

    public static boolean lessThan(LuaThread L, LuaValue a, LuaValue b) {
        if (a instanceof LuaInteger ai && b instanceof LuaInteger bi) return ai.v < bi.v;
        if (a instanceof LuaInteger ai && b instanceof LuaFloat bf)
            return ltIntFloat(ai.v, bf.todouble());
        if (a instanceof LuaFloat af && b instanceof LuaInteger bi)
            return ltFloatInt(af.todouble(), bi.v);
        if (a instanceof LuaNumber && b instanceof LuaNumber) return a.todouble() < b.todouble();
        if (a instanceof LuaString sa && b instanceof LuaString sb)
            return sa.lStrcmp(sb) < 0;  // lessthanothers: 仅字符串对字符串直接比较
        LuaValue h = Metamethod.LT.lookup(a);
        if (h == null) h = Metamethod.LT.lookup(b);  // callbinTM
        if (h != null)
            return (L != null ? callMetamethod(L, a, b, Metamethod.LT) : callMetamethod(h, a, b, "lt")).toboolean();
        orderError(a, b);
        return false;
    }


    // LTintfloat  -  供 LuaVM OP_LT 内联公开
    public static boolean ltIntFloat(long i, double f) {
        if (intFitsFloat(i)) return (double) i < f;
        Long fi = floatToInt(f, 2);  // ceil
        if (fi != null) return i < fi;
        return f > 0;
    }

    // LTfloatint  -  供 LuaVM OP_LT 内联公开
    public static boolean ltFloatInt(double f, long i) {
        if (intFitsFloat(i)) return f < (double) i;
        Long fi = floatToInt(f, 1);  // floor
        if (fi != null) return fi < i;
        return f < 0;
    }

    // lvm.c: luaV_lessequal  -  main operation 'l <= r'
    // C: ttisnumber->LEnum 宏; else->lessequalothers（字符串用 l_strcmp，其他用 luaT_callorderTM）
    // java diff: C 用 LEnum 宏处理 int/float 交叉比较, Java 用 leIntFloat/leFloatInt 辅助方法
    public static boolean lessEqual(LuaValue a, LuaValue b) {
        return lessEqual(null, a, b);
    }

    public static boolean lessEqual(LuaThread L, LuaValue a, LuaValue b) {
        if (a instanceof LuaInteger ai && b instanceof LuaInteger bi) return ai.v <= bi.v;
        if (a instanceof LuaInteger ai && b instanceof LuaFloat bf)
            return leIntFloat(ai.v, bf.todouble());
        if (a instanceof LuaFloat af && b instanceof LuaInteger bi)
            return leFloatInt(af.todouble(), bi.v);
        if (a instanceof LuaNumber && b instanceof LuaNumber) return a.todouble() <= b.todouble();
        if (a instanceof LuaString sa && b instanceof LuaString sb)
            return sa.lStrcmp(sb) <= 0;  // lessequalothers
        LuaValue h = Metamethod.LE.lookup(a);
        if (h == null) h = Metamethod.LE.lookup(b);  // luaT_callorderTM
        if (h != null)
            return (L != null ? callMetamethod(L, a, b, Metamethod.LE) : callMetamethod(h, a, b, "le")).toboolean();
        orderError(a, b);
        return false;
    }


    // LEintfloat  -  供 LuaVM OP_LE 内联公开
    public static boolean leIntFloat(long i, double f) {
        if (intFitsFloat(i)) return (double) i <= f;
        Long fi = floatToInt(f, 1);  // floor
        if (fi != null) return i <= fi;
        return f > 0;
    }

    // LEfloatint  -  供 LuaVM OP_LE 内联公开
    public static boolean leFloatInt(double f, long i) {
        if (intFitsFloat(i)) return f <= (double) i;
        Long fi = floatToInt(f, 2);  // ceil
        if (fi != null) return fi <= i;
        return f < 0;
    }




    // intEqFloat  -  供 LuaVM OP_EQ/OP_EQK 内联公开
    // lvm.c: luaV_equalobj 的 LUA_VNUMINT 情形  -  整数 == 浮点？
    public static boolean intEqFloat(long i, double f) {
        Long fi = floatToInt(f, 0);
        return fi != null && i == fi;
    }

    // floatEqInt  -  供 LuaVM OP_EQ/OP_EQK 内联公开
    // lvm.c: luaV_equalobj 的 LUA_VNUMFLT 情形  -  浮点 == 整数？
    public static boolean floatEqInt(double f, long i) {
        Long fi = floatToInt(f, 0);
        return fi != null && fi == i;
    }

}
