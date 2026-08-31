// ref: lopcodes.h (BinaryOp)
// diff: C 用 OP_* 常量；Java 用枚举统一二元运算码
package org.luajvm.core;

public enum BinaryOp {ADD, SUB, MUL, DIV, MOD, POW, IDIV, BAND, BOR, BXOR, SHL, SHR, CONCAT}
