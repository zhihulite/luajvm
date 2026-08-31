// ref: lvm.c
// diff: instanceof 分派非 ttypetag switch; long 溢出回绕与 C unsigned 一致
// diff: C 用 op_arith/op_arithf 宏(intarith/numarith)分派, Java 用 switch/方法分派
// diff: C 的 luaV_mod 用 l_castS2U 检查异号, Java 用 (m^b)<0 功能等价（Java long 有符号）
package org.luajvm.vm;

import org.luajvm.core.BinaryOp;
import org.luajvm.core.LuaErrors;
import org.luajvm.core.LuaFloat;
import org.luajvm.core.LuaInteger;
import org.luajvm.core.LuaNumber;
import org.luajvm.core.LuaValue;
import org.luajvm.core.UnaryOp;

public final class LuaArith {
    private static final int NBITS = 64;

    private LuaArith() {
    }

    // lvm.c: tonumberns
    private static LuaNumber toNumberNS(LuaValue v) {
        if (v instanceof LuaNumber n) return n;
        return null;
    }

    // lvm.c: tointegerns
    private static Long toIntegerNS(LuaValue v) {
        if (v instanceof LuaInteger i) return i.v;
        if (v instanceof LuaFloat f) {
            double d = f.todouble();
            if (!canToIntegerNS(d)) return null;
            return (long) d;
        }
        // lvm.c: luaV_tointegerns —— 无字符串强转（vanilla 5.5.1 的位运算不接受
        // 数字字符串；官方 bitwise.lua 靠 require"bwcoercion" 在字符串元表上装 __band 通过）。
        // 此处强转会绕过 C 的报错路径并抢跑用户 __band/__bnot。
        return null;
    }

    // tonumberns (double)
    private static Double toDoubleNS(LuaValue v) {
        if (v instanceof LuaNumber n) return n.todouble();
        return null;
    }

    // lvm.c: op_arith
    public static LuaValue apply(BinaryOp op, LuaValue a, LuaValue b) {
        return switch (op) {
            case ADD -> add(a, b);
            case SUB -> sub(a, b);
            case MUL -> mul(a, b);
            case DIV -> div(a, b);
            case MOD -> mod(a, b);
            case POW -> pow(a, b);
            case IDIV -> idiv(a, b);
            case BAND -> band(a, b);
            case BOR -> bor(a, b);
            case BXOR -> bxor(a, b);
            case SHL -> shl(a, b);
            case SHR -> shr(a, b);
            case CONCAT -> LuaConcat.concat(a, b);
        };
    }

    // lvm.c: op_arithI

    // op_arith  -  一元
    public static LuaValue apply(UnaryOp op, LuaValue v) {
        return switch (op) {
            case UNM -> unm(v);
            case BNOT -> bnot(v);
            case NOT -> v.not();
            case LEN -> v.len();
        };
    }

    // op_arith  -  加
    private static LuaValue add(LuaValue a, LuaValue b) {
        if (a instanceof LuaInteger ia && b instanceof LuaInteger ib)
            return LuaInteger.valueOf(ia.v + ib.v);
        LuaNumber na = toNumberNS(a), nb = toNumberNS(b);
        if (na != null && nb != null) {
            if (na instanceof LuaInteger ia2 && nb instanceof LuaInteger ib2)
                return LuaInteger.valueOf(ia2.v + ib2.v);
            return LuaFloat.valueOf(na.todouble() + nb.todouble());
        }
        return null;
    }

    // op_arith  -  减
    private static LuaValue sub(LuaValue a, LuaValue b) {
        if (a instanceof LuaInteger ia && b instanceof LuaInteger ib)
            return LuaInteger.valueOf(ia.v - ib.v);
        LuaNumber na = toNumberNS(a), nb = toNumberNS(b);
        if (na != null && nb != null) {
            if (na instanceof LuaInteger ia2 && nb instanceof LuaInteger ib2)
                return LuaInteger.valueOf(ia2.v - ib2.v);
            return LuaFloat.valueOf(na.todouble() - nb.todouble());
        }
        return null;
    }

    // op_arith  -  乘
    private static LuaValue mul(LuaValue a, LuaValue b) {
        if (a instanceof LuaInteger ia && b instanceof LuaInteger ib)
            return LuaInteger.valueOf(ia.v * ib.v);
        LuaNumber na = toNumberNS(a), nb = toNumberNS(b);
        if (na != null && nb != null) {
            if (na instanceof LuaInteger ia2 && nb instanceof LuaInteger ib2)
                return LuaInteger.valueOf(ia2.v * ib2.v);
            return LuaFloat.valueOf(na.todouble() * nb.todouble());
        }
        return null;
    }

    // op_arithf  -  除
    private static LuaValue div(LuaValue a, LuaValue b) {
        Double da = toDoubleNS(a), db = toDoubleNS(b);
        if (da != null && db != null) return LuaFloat.valueOf(da / db);
        return null;
    }

    // lvm.c: luaV_mod  -  带符号修正的取模
    // C: if (l_castS2U(m) + l_castS2U(b)) != 0) m += b; 用无符号溢出检查符号
    // java diff: Java 用 (m^b)<0 检查异号，功能等价（Java long 有符号）
    // java diff: C 的 m%0 由 C 运行时处理，Java 显式检查 ib.v==0
    static LuaValue mod(LuaValue a, LuaValue b) {
        if (a instanceof LuaInteger ia && b instanceof LuaInteger ib) {
            if (ib.v == 0) LuaErrors.runErrorWithInfo("attempt to perform 'n%0'");
            if (ib.v == -1) return LuaInteger.valueOf(0);  // m % -1 == 0，免溢出
            long m = ia.v % ib.v;
            if (m != 0 && (m ^ ib.v) < 0) m += ib.v;
            return LuaInteger.valueOf(m);
        }
        Double da = toDoubleNS(a), db = toDoubleNS(b);
        if (da != null && db != null) {
            double r = da % db;
            if ((r > 0 && db < 0) || (r < 0 && db > 0)) r += db;
            return LuaFloat.valueOf(r);
        }
        return null;
    }

    // lvm.c: luaV_idiv  -  向下取整语义的整数除法
    // C: intarith(L, OP_IDIV, v1, v2) -> luaV_div
    // java diff: C 用 luaV_div 宏，Java 内联，功能等价
    static LuaValue idiv(LuaValue a, LuaValue b) {
        if (a instanceof LuaInteger ia && b instanceof LuaInteger ib) {
            if (ib.v == 0) LuaErrors.runErrorWithInfo("attempt to divide by zero");
            if (ib.v == -1) {
                return LuaInteger.valueOf(-ia.v);
            }
            long q = ia.v / ib.v;
            if ((ia.v % ib.v != 0) && ((ia.v ^ ib.v) < 0)) q--;
            return LuaInteger.valueOf(q);
        }
        Double da = toDoubleNS(a), db = toDoubleNS(b);
        if (da != null && db != null) {
            return LuaFloat.valueOf(Math.floor(da / db));
        }
        return null;
    }

    // op_arithf  -  幂
    private static LuaValue pow(LuaValue a, LuaValue b) {
        Double da = toDoubleNS(a), db = toDoubleNS(b);
        if (da != null && db != null) return LuaFloat.valueOf(Math.pow(da, db));
        return null;
    }

    // op_arith  -  band
    private static LuaValue band(LuaValue a, LuaValue b) {
        Long la = toIntegerNS(a), lb = toIntegerNS(b);
        if (la != null && lb != null) return LuaInteger.valueOf(la & lb);
        return null;
    }

    // op_arith  -  bor
    private static LuaValue bor(LuaValue a, LuaValue b) {
        Long la = toIntegerNS(a), lb = toIntegerNS(b);
        if (la != null && lb != null) return LuaInteger.valueOf(la | lb);
        return null;
    }

    // op_arith  -  bxor
    private static LuaValue bxor(LuaValue a, LuaValue b) {
        Long la = toIntegerNS(a), lb = toIntegerNS(b);
        if (la != null && lb != null) return LuaInteger.valueOf(la ^ lb);
        return null;
    }

    // lvm.c: luaV_shiftl
    private static LuaValue shl(LuaValue a, LuaValue b) {
        Long la = toIntegerNS(a), lb = toIntegerNS(b);
        if (la == null || lb == null) return null;
        return LuaInteger.valueOf(shiftLeft(la, lb));
    }

    // shiftLeft (shr)
    private static LuaValue shr(LuaValue a, LuaValue b) {
        Long la = toIntegerNS(a), lb = toIntegerNS(b);
        if (la == null || lb == null) return null;
        if (lb == Long.MIN_VALUE) {
            return LuaInteger.valueOf(shiftLeft(la, lb));
        }
        return LuaInteger.valueOf(shiftLeft(la, -lb));
    }

    // lvm.c: luaV_shiftl  -  负值右移
    // C: #define shiftLeft(v, y) ... 右移用无符号，左移用有符号
    // java diff: Java 用 >>> 无符号右移，等价于 C 的 unsigned shift
    public static long shiftLeft(long x, long y) {
        if (y < 0) {
            if (y <= -NBITS) return 0;
            return x >>> (int) (-y);
        } else {
            if (y >= NBITS) return 0;
            return x << (int) y;
        }
    }


    // lvm.c: luaV_tointegerns
    public static boolean canToIntegerNS(double d) {
        if (Double.isInfinite(d) || Double.isNaN(d)) return false;
        if (d != Math.floor(d)) return false;
        return d >= -9223372036854775808.0 && d < 9223372036854775808.0;
    }

    // lobject.c: intarith
    public static long rawIntArith(int op, long v1, long v2) {
        return switch (op) {
            case 0 -> v1 + v2;                    // LUA_OPADD
            case 1 -> v1 - v2;                    // LUA_OPSUB
            case 2 -> v1 * v2;                    // LUA_OPMUL
            case 3 -> intMod(v1, v2);             // LUA_OPMOD
            case 6 -> intIDiv(v1, v2);            // LUA_OPIDIV
            case 7 -> v1 & v2;                    // LUA_OPBAND
            case 8 -> v1 | v2;                    // LUA_OPBOR
            case 9 -> v1 ^ v2;                    // LUA_OPBXOR
            case 10 -> shiftLeft(v1, v2);       // LUA_OPSHL
            case 11 -> v2 == Long.MIN_VALUE ? shiftLeft(v1, v2) : shiftLeft(v1, -v2); // LUA_OPSHR
            case 12 -> -v1;                       // LUA_OPUNM
            case 13 -> ~v1;                       // LUA_OPBNOT
            default -> throw new AssertionError("invalid integer arithmetic op: " + op);
        };
    }

    // lobject.c: numarith
    public static double rawNumArith(int op, double v1, double v2) {
        return switch (op) {
            case 0 -> v1 + v2;                    // LUA_OPADD
            case 1 -> v1 - v2;                    // LUA_OPSUB
            case 2 -> v1 * v2;                    // LUA_OPMUL
            case 3 -> floatMod(v1, v2);           // LUA_OPMOD
            case 4 -> Math.pow(v1, v2);           // LUA_OPPOW
            case 5 -> v1 / v2;                    // LUA_OPDIV
            case 6 -> Math.floor(v1 / v2);        // LUA_OPIDIV
            case 12 -> -v1;                       // LUA_OPUNM
            default -> throw new AssertionError("invalid float arithmetic op: " + op);
        };
    }

    private static long intMod(long a, long b) {
        if (b == -1) return 0;
        long m = a % b;
        if (m != 0 && (m ^ b) < 0) m += b;
        return m;
    }

    private static long intIDiv(long a, long b) {
        if (b == -1) return -a;
        long q = a / b;
        if ((a % b != 0) && ((a ^ b) < 0)) q--;
        return q;
    }

    // lvm.c: luai_nummod
    private static double floatMod(double a, double b) {
        double m = a % b;
        if ((m > 0 && b < 0) || (m < 0 && b > 0)) m += b;
        return m;
    }

    // op_arith  -  unm
    private static LuaValue unm(LuaValue v) {
        if (v instanceof LuaInteger i) return LuaInteger.valueOf(-i.v);
        if (v instanceof LuaFloat f) return LuaFloat.valueOf(-f.todouble());
        return null;
    }

    // op_arith  -  bnot
    private static LuaValue bnot(LuaValue v) {
        Long l = toIntegerNS(v);
        if (l == null) return null;
        return LuaInteger.valueOf(~l);
    }
}
