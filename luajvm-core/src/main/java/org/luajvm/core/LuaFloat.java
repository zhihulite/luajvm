// ref: lobject.h (LUA_VNUMFLT)
// diff: C的fltvalue(o)宏直接访问TValue.value_.n，Java用private final double v; NAN/POSINF/NEGINF为Java特有
package org.luajvm.core;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;

public final class LuaFloat extends LuaNumber {
    // java-only
    public static final LuaFloat NAN = new LuaFloat(Double.NaN);
    // java-only
    public static final LuaFloat POSINF = new LuaFloat(Double.POSITIVE_INFINITY);
    // java-only
    public static final LuaFloat NEGINF = new LuaFloat(Double.NEGATIVE_INFINITY);
    // java diff: C's fltvalue(o) macro directly accesses TValue.value_.n;
    // Java uses public field for direct access from LuaVM fast paths (like LuaInteger.v)
    public final double v;

    public LuaFloat(double d) {
        super(LUA_VNUMFLT);
        v = d;
    }

    // lapi.c: lua_pushnumber
    public static LuaFloat valueOf(double d) {
        return new LuaFloat(d);
    }

    // lobject.c tostringbuffFloat —— %.15g 首试，round-trip 失败再 %.17g；纯整数字符形态补 ".0"。
    // java diff: Java Formatter %g 基于最短表示，给不出 %.17g 第 17 位精确数字，
    // 故走 cFormatG 精确实现 C 的 glibc 舍入语义
    private static String floatFormat(double n) {
        String s = cFormatG(n, 15, false);
        double check;
        try {
            check = Double.parseDouble(s);
        } catch (Exception e) {
            check = Double.NaN;
        }
        if (check != n) {
            s = cFormatG(n, 17, false);
        }
        // lobject.c: buff[strspn(buff, "-0123456789")] == '\0' —— 纯整数形态
        for (int i = 0; ; i++) {
            char c = s.charAt(i);
            if (!((c >= '0' && c <= '9') || c == '-')) {
                if (i == s.length()) s = s + ".0";
                break;
            }
            if (i == s.length() - 1) {  // 走到尾仍是数字 → 整数形态
                    s = s + ".0";
                    break;
            }
        }
        return s;
    }

    // java-only: C printf %.Ng 的精确实现（glibc 语义：按二进制精确值舍入，
    // HALF_EVEN，g 形态选择 x<-4||x>=p 用 e 形态，默认剥尾零）
    public static String cFormatG(double d, int precision, boolean keepTrailingZeros) {
        if (Double.isNaN(d)) return "nan";
        if (Double.isInfinite(d)) return d > 0 ? "inf" : "-inf";
        boolean neg = Double.doubleToRawLongBits(d) < 0;
        double a = Math.abs(d);
        if (a == 0.0) return neg ? "-0" : "0";
        BigDecimal bd = new BigDecimal(a)
                .round(new MathContext(precision, RoundingMode.HALF_EVEN));
        String digits = bd.unscaledValue().toString();
        // 首位十进制指数：value = unscaled × 10^-scale（BigDecimal 语义）
        int x = digits.length() - bd.scale() - 1;
        if (x < -4 || x >= precision) {
            // e 形态：d.ddd…（p-1 位小数，可剥尾零）e±XX（指数至少 2 位）
            StringBuilder sb = new StringBuilder();
            if (neg) sb.append('-');
            sb.append(digits.charAt(0));
            if (precision > 1) {
                String frac = digits.substring(1);
                if (!keepTrailingZeros) {
                    int end = frac.length();
                    while (end > 0 && frac.charAt(end - 1) == '0') end--;
                    frac = frac.substring(0, end);
                } else {
                    while (frac.length() < precision - 1) frac = frac + "0";
                }
                if (!frac.isEmpty()) sb.append('.').append(frac);
            }
            sb.append('e');
            sb.append(x >= 0 ? '+' : '-');
            int ax = Math.abs(x);
            if (ax < 10) sb.append('0');
            sb.append(ax);
            return sb.toString();
        }
        // f 形态：p 位有效数字 → 小数位 = p-1-x
        BigDecimal fixed = bd.setScale(Math.max(0, precision - 1 - x),
                RoundingMode.HALF_EVEN);
        String s = fixed.toPlainString();
        if (!keepTrailingZeros) {
            int dot = s.indexOf('.');
            if (dot >= 0) {
                int end = s.length();
                while (end > dot + 1 && s.charAt(end - 1) == '0') end--;
                if (end == dot + 1) end = dot;
                s = s.substring(0, end);
            }
        }
        return neg ? "-" + s : s;
    }

    // java-only: C printf %.Ne 的精确实现（bd 为二进制精确值；p 位小数，指数至少 2 位）
    public static String cFormatE(double d, int p, boolean upper) {
        boolean neg = Double.doubleToRawLongBits(d) < 0;
        BigDecimal bd = new BigDecimal(Math.abs(d))
                .round(new MathContext(p + 1, RoundingMode.HALF_EVEN));
        String digits = bd.unscaledValue().toString();
        int x = digits.length() - bd.scale() - 1;  // value = unscaled × 10^-scale
        StringBuilder sb = new StringBuilder();
        if (neg) sb.append('-');
        sb.append(digits.charAt(0));
        if (p > 0) {  // glibc：p==0 无小数点（%#.0e 的点由调用方 alt 补）
            String frac = digits.substring(1);
            while (frac.length() < p) frac = frac + "0";
            sb.append('.').append(frac);
        }
        sb.append(upper ? 'E' : 'e');
        sb.append(x >= 0 ? '+' : '-');
        int ax = Math.abs(x);
        if (ax < 10) sb.append('0');
        sb.append(ax);
        return sb.toString();
    }

    // java:
    private static String stripTrailingZeros(String s) {
        int ePos = -1;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == 'e' || c == 'E') {
                ePos = i;
                break;
            }
        }
        String mantissa, exponent;
        if (ePos >= 0) {
            mantissa = s.substring(0, ePos);
            exponent = s.substring(ePos);
        } else {
            mantissa = s;
            exponent = "";
        }
        int dotPos = mantissa.indexOf('.');
        if (dotPos >= 0) {
            int end = mantissa.length();
            while (end > dotPos + 1 && mantissa.charAt(end - 1) == '0') end--;
            if (end == dotPos + 1) end = dotPos;
            mantissa = mantissa.substring(0, end);
        }
        return mantissa + exponent;
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
        return (long) v;
    }

    // java-only：浮点是否有精确 long 表示（checkLong 的参数校验用）
    public static boolean hasExactLong(double d) {
        return d == Math.rint(d) && !Double.isInfinite(d)
                && d >= (double) Long.MIN_VALUE && d < -(double) Long.MIN_VALUE;
    }

    // lvm.c: luaV_flttointeger
    private long checkIntegerValid() {
        if (Double.isNaN(v) || Double.isInfinite(v) || v != Math.floor(v))
            LuaErrors.toIntError(this, this);
        if (v < (double) Long.MIN_VALUE || v >= -(double) Long.MIN_VALUE)
            LuaErrors.toIntError(this, this);
        return (long) v;
    }

    // lobject.h: l_isfalse
    @Override
    public boolean toboolean() {
        return true;
    }

    @Override
    public String toJavaString() {
        if (Double.isNaN(v)) return "nan";
        if (Double.isInfinite(v)) return v < 0 ? "-inf" : "inf";
        String s = floatFormat(v);
        if (s.indexOf('.') < 0 && s.indexOf('e') < 0 && s.indexOf('E') < 0) {
            s = s + ".0";
        }
        return s;
    }

    // lobject.h: luaV_tostring
    @Override
    public LuaString strValue() {
        return LuaString.newStr(toJavaString());
    }

    @Override
    public LuaValue tostring() {
        return LuaString.newStr(toJavaString());
    }

    // java-only
    @Override
    public boolean equals(Object o) {
        return o instanceof LuaFloat f && v == f.v;
    }

    // java-only
    @Override
    public int hashCode() {
        return Double.hashCode(v);
    }

    // lobject.h: luaV_rawequalobj
    @Override
    public boolean raweq(LuaValue r) {
        return r instanceof LuaFloat f && v == f.v;
    }

    // luaL_opt*
    @Override
    public double optdouble(double d) {
        return v;
    }

    @Override
    public int optint(int d) {
        return (int) checkIntegerValid();
    }

    @Override
    public long optlong(long d) {
        return checkIntegerValid();
    }

    // luaL_check*
    @Override
    public double checkdouble() {
        return v;
    }

    @Override
    public int checkint() {
        return (int) checkIntegerValid();
    }

    // lobject/lstrlib —— luaV_tonumber_ 的字符串化：浮点值作为字符串实参
    //（string.upper(65.0) == '65.0'；LuaInteger.java 已有同款覆写）
    @Override
    public LuaString checkstring() {
        return LuaString.newStr(Double.toString(v));
    }

    @Override
    public long checklong() {
        return checkIntegerValid();
    }

    @Override
    public LuaInteger checkinteger() {
        return LuaInteger.valueOf(checkIntegerValid());
    }
}
