// ref: lvm.c
// diff: C 的 luaV_concat 是栈操作（连接 total 个栈上值）, Java 是双操作数（多值由 LuaVM.concatSharedStack 处理）
// diff: C 的 tostring 宏会把数字转为字符串(luaO_tostring), Java 的 canConcatFast 仅检查 String/Number
// diff: C 用 luaT_tryconcatTM 处理 __concat, Java 内联调 Metamethod.CONCAT.lookup
package org.luajvm.vm;

import org.luajvm.core.LuaErrors;
import org.luajvm.core.LuaNumber;
import org.luajvm.core.LuaString;
import org.luajvm.core.LuaValue;
import org.luajvm.core.Metamethod;

public final class LuaConcat {
    private LuaConcat() {
    }

    // lvm.c: tostring  -  check whether value can be converted to string
    // C: ttisstring(o) || (cvt2str(o) && (luaO_tostring(L, o), 1))
    // java diff: C 调 luaO_tostring 把数字转为字符串, Java 仅检查 String/Number（Lua 5.5 已废弃数字->字符串强制转换）
    public static boolean canConcatFast(LuaValue v) {
        return v instanceof LuaString || v instanceof LuaNumber;
    }

    // java: toConcatString
    public static LuaString toConcatString(LuaValue v) {
        return canConcatFast(v) ? v.strValue() : null;
    }

    // lvm.c: luaV_concat  -  main concatenation operation (dual-operand version)
    // C: 栈操作，连接 total 个值，用 copy2buff+luaS_newlstr/luaS_createlngstrobj
    // java diff: C 用 isemptystr 跳过空串, Java 同样有（sb.shrlen==0/sa.shrlen==0）
    public static LuaValue concat(LuaValue a, LuaValue b) {
        LuaString sa = toConcatString(a), sb = toConcatString(b);
        if (sa != null && sb != null) {
            // lvm.c: copy2buff
            if (sb.shrlen == 0) return sa;
            if (sa.shrlen == 0) return sb;
            if (sa.shrlen > Integer.MAX_VALUE - sb.shrlen)
                LuaErrors.runErrorWithInfo("string length overflow");
            byte[] bc = new byte[sa.shrlen + sb.shrlen];
            System.arraycopy(sa.contents, 0, bc, 0, sa.shrlen);
            System.arraycopy(sb.contents, 0, bc, sa.shrlen, sb.shrlen);
            return LuaString.valueOfOwned(bc);
        }
        // ltm.c: callbinTM
        LuaValue h = Metamethod.CONCAT.lookup(a);
        if (h == null) h = Metamethod.CONCAT.lookup(b);
        // ltm.c: callbinTM  -  luaD_callnoyield(L, func, 1)
        // java diff: C calls luaD_callnoyield directly on stack; Java uses callOnStack2to1 to avoid Varargs
        if (h != null) {
            return LuaCall.callOnStack2to1(h, a, b);
        }
        // ldebug.c: luaG_concaterror
        LuaErrors.concatError(a, b);
        return LuaValue.NIL;
    }

}
