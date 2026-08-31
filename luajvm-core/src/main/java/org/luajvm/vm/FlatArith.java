// java-only: 扁平算术核（免装箱） - long/double 寄存器直算，对齐 LuaVM.op_arith 快路径
package org.luajvm.vm;

import org.luajvm.core.BinaryOp;

// 值 = (long 值位, byte tag)，算术在 long/double 寄存器直算零分配。
//   复用已审计的 LuaArith.rawIntArith/rawNumArith ⇒ 与装箱路径数值逐位一致，
//   差别仅"值住数组而非堆对象"
public final class FlatArith {
    // tag 常量：对应 LUA_VNUMINT/VNUMFLT，取紧凑小整数便于 byte[] 存储；
    //   T_NIL/T_REF/T_BOOL 用于 sidecar 栈寄存器的非数值类型
    public static final byte T_NIL = 0;  // nil（无值位）
    public static final byte T_INT = 1;  // 整数：vals = long 直接存
    public static final byte T_FLT = 2;  // 浮点：vals = doubleToRawLongBits(d)
    public static final byte T_REF = 3;  // 引用（string/table/function/userdata/thread）：去 refs 边车取
    public static final byte T_BOOL = 4;  // 布尔：vals = 0 为 false，1 为 true

    private FlatArith() {
    }

    // -- 编解码 --

    public static long encFlt(double d) {
        return Double.doubleToRawLongBits(d);
    }


    public static double decFlt(long bits) {
        return Double.longBitsToDouble(bits);
    }

    // lobject.c: intarith/numarith 的 LUA_OP* 码 - BinaryOp 序数与 LUA_OP 在 DIV/MOD/POW 处不同序，必须显式映射
    private static int luaOpCode(BinaryOp op) {
        return switch (op) {
            case ADD -> 0;
            case SUB -> 1;
            case MUL -> 2;
            case MOD -> 3;
            case POW -> 4;
            case DIV -> 5;
            case IDIV -> 6;
            case BAND -> 7;
            case BOR -> 8;
            case BXOR -> 9;
            case SHL -> 10;
            case SHR -> 11;
            case CONCAT -> throw new IllegalArgumentException("CONCAT not arithmetic");
        };
    }

    // op_arith 快路径：数值操作数直算（int+int->int；含 float->float；DIV/POW 恒 float）。
    //   返回 true 为成功，false 回退元方法。位运算仅 int+int，含 float 交装箱路径
    //   由 LuaArith.apply 做浮点->整数转换
    public static boolean arith(BinaryOp op, long v1, byte t1, long v2, byte t2,
                                long[] outv, byte[] outt, int oi) {
        // DIV 与 POW 在 Lua 中恒为浮点，即便两个整数操作数
        boolean forceFloat = (op == BinaryOp.DIV || op == BinaryOp.POW);
        if (t1 == T_INT && t2 == T_INT && !forceFloat) {
            long r = LuaArith.rawIntArith(luaOpCode(op), v1, v2);
            outv[oi] = r;
            outt[oi] = T_INT;
            return true;
        }
        // 位运算非 int 操作数一律 false：对齐 op_bitwise 仅走 int 快路径，float 回退 LuaArith.apply
        if (op == BinaryOp.BAND || op == BinaryOp.BOR || op == BinaryOp.BXOR
                || op == BinaryOp.SHL || op == BinaryOp.SHR) return false;
        if ((t1 == T_INT || t1 == T_FLT) && (t2 == T_INT || t2 == T_FLT)) {
            double a = (t1 == T_INT) ? (double) v1 : decFlt(v1);
            double b = (t2 == T_INT) ? (double) v2 : decFlt(v2);
            double r = LuaArith.rawNumArith(luaOpCode(op), a, b);
            outv[oi] = encFlt(r);
            outt[oi] = T_FLT;
            return true;
        }
        return false;  // 操作数含非数值：回退装箱路径走元方法
    }
}
