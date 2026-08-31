// ref: lopcodes.h
// diff: CREATE_sJ接收已加OFFSET_sJ的sj(C接收原始值); CREATE_ABCk等价CREATE_ABC; opmode用静态方法(C用宏); opName用switch(C用数组)
package org.luajvm.compiler;

public final class Opcodes {
    // -- OpCode (C: lopcodes.h:OpCode) --
    public static final int
            OP_MOVE = 0, OP_LOADI = 1, OP_LOADF = 2, OP_LOADK = 3, OP_LOADKX = 4,
            OP_LOADFALSE = 5, OP_LFALSESKIP = 6, OP_LOADTRUE = 7, OP_LOADNIL = 8,
            OP_GETUPVAL = 9, OP_SETUPVAL = 10, OP_GETTABUP = 11, OP_GETTABLE = 12,
            OP_GETI = 13, OP_GETFIELD = 14, OP_SETTABUP = 15, OP_SETTABLE = 16,
            OP_SETI = 17, OP_SETFIELD = 18, OP_NEWTABLE = 19, OP_SELF = 20,
            OP_ADDI = 21, OP_ADDK = 22, OP_SUBK = 23, OP_MULK = 24, OP_MODK = 25,
            OP_POWK = 26, OP_DIVK = 27, OP_IDIVK = 28,
            OP_BANDK = 29, OP_BORK = 30, OP_BXORK = 31, OP_SHLI = 32, OP_SHRI = 33,
            OP_ADD = 34, OP_SUB = 35, OP_MUL = 36, OP_MOD = 37, OP_POW = 38,
            OP_DIV = 39, OP_IDIV = 40, OP_BAND = 41, OP_BOR = 42, OP_BXOR = 43, OP_SHL = 44, OP_SHR = 45,
            OP_MMBIN = 46, OP_MMBINI = 47, OP_MMBINK = 48,
            OP_UNM = 49, OP_BNOT = 50, OP_NOT = 51, OP_LEN = 52,
            OP_CONCAT = 53, OP_CLOSE = 54, OP_TBC = 55, OP_JMP = 56,
            OP_EQ = 57, OP_LT = 58, OP_LE = 59, OP_EQK = 60, OP_EQI = 61,
            OP_LTI = 62, OP_LEI = 63, OP_GTI = 64, OP_GEI = 65,
            OP_TEST = 66, OP_TESTSET = 67,
            OP_CALL = 68, OP_TAILCALL = 69, OP_RETURN = 70, OP_RETURN0 = 71, OP_RETURN1 = 72,
            OP_FORLOOP = 73, OP_FORPREP = 74, OP_TFORPREP = 75, OP_TFORCALL = 76, OP_TFORLOOP = 77,
            OP_SETLIST = 78, OP_CLOSURE = 79, OP_VARARG = 80, OP_GETVARG = 81,
            OP_ERRNNIL = 82, OP_VARARGPREP = 83, OP_EXTRAARG = 84;
    public static final int NUM_OPCODES = 85;
    // -- TMS (C: ltm.h:TMS) --
    public static final int
            TM_INDEX = 0, TM_NEWINDEX = 1, TM_GC = 2, TM_MODE = 3, TM_LEN = 4, TM_EQ = 5,
            TM_ADD = 6, TM_SUB = 7, TM_MUL = 8, TM_MOD = 9,
            TM_POW = 10, TM_DIV = 11, TM_IDIV = 12,
            TM_BAND = 13, TM_BOR = 14, TM_BXOR = 15,
            TM_SHL = 16, TM_SHR = 17,
            TM_UNM = 18, TM_BNOT = 19, TM_LT = 20, TM_LE = 21,
            TM_CONCAT = 22, TM_CALL = 23, TM_CLOSE = 24;
    // TM_N
    public static final int TM_N = 25;
    // -- SIZE_*/POS_* (C: lopcodes.h) --
    public static final int SIZE_OP = 7, SIZE_A = 8, SIZE_k = 1, SIZE_B = 8, SIZE_C = 8;
    public static final int SIZE_vB = 6, SIZE_vC = 10;
    public static final int SIZE_Bx = SIZE_C + SIZE_B + 1;
    public static final int SIZE_Ax = SIZE_Bx + SIZE_A;
    public static final int MAXARG_Ax = (1 << SIZE_Ax) - 1;
    public static final int SIZE_sJ = SIZE_Bx + SIZE_A;
    public static final int MAXARG_sJ = (1 << SIZE_sJ) - 1;
    public static final int OFFSET_sJ = (MAXARG_sJ >> 1);
    public static final int POS_OP = 0;
    public static final int POS_A = POS_OP + SIZE_OP;
    public static final int POS_k = POS_A + SIZE_A;
    public static final int POS_B = POS_k + 1;
    public static final int POS_C = POS_B + SIZE_B;
    public static final int POS_vB = POS_k + 1;
    public static final int POS_vC = POS_vB + SIZE_vB;
    public static final int POS_Bx = POS_k;                     // java-only: overlaps k+B+C
    public static final int POS_Ax = POS_A;
    public static final int POS_sJ = POS_A;
    // -- MAXARG_*/OFFSET_* (C: lopcodes.h) --
    public static final int MAXARG_A = (1 << SIZE_A) - 1;
    public static final int MAXARG_B = (1 << SIZE_B) - 1;
    public static final int MAXARG_C = (1 << SIZE_C) - 1;
    public static final int MAXARG_vB = (1 << SIZE_vB) - 1;
    public static final int MAXARG_vC = (1 << SIZE_vC) - 1;
    public static final int MAXARG_Bx = (1 << SIZE_Bx) - 1;
    public static final int MAXARG_sBx = MAXARG_Bx;
    public static final int OFFSET_sBx = (MAXARG_Bx >> 1);
    public static final int OFFSET_sC = (MAXARG_C >> 1);
    // NO_REG
    public static final int NO_REG = MAXARG_A;
    // MAX_FSTACK
    public static final int MAX_FSTACK = MAXARG_A;
    // MAXINDEXRK  -  lopcodes.h 默认 MAXARG_B；ltests.h 覆盖为 1（压缩 K 索引范围以暴露 bug）
    // java diff: C 编译期宏 -> Java 系统属性（-Dluajvm.maxindexrk）。
    //   决定 isKstr/exp2K 是否把常量编进 argC，即选 GETFIELD/SETFIELD 还是 GETTABLE/SETTABLE，
    //   故带 ltests 编译出的字节码与生产版不同（code.lua 校验指令序列）。
    public static final int MAXINDEXRK = Integer.getInteger("luajvm.maxindexrk", MAXARG_B);
    // NO_JUMP
    public static final int NO_JUMP = -1;
    // OpMode (C: lopcodes.h:OpMode)
    public static final int
            OpMode_iABC = 0,
            OpMode_ivABC = 1,
            OpMode_iABx = 2,
            OpMode_iAsBx = 3,
            OpMode_iAx = 4,
            OpMode_isJ = 5;
    // lopcodes.h: luaP_opmodes
    public static final int[] opModes = {
            /*          MM OT IT T  A  mode          opcode            */
            opmode(0, 0, 0, 0, 1, OpMode_iABC)  /*  0 OP_MOVE       */
            , opmode(0, 0, 0, 0, 1, OpMode_iAsBx)  /*  1 OP_LOADI      */
            , opmode(0, 0, 0, 0, 1, OpMode_iAsBx)  /*  2 OP_LOADF      */
            , opmode(0, 0, 0, 0, 1, OpMode_iABx)  /*  3 OP_LOADK      */
            , opmode(0, 0, 0, 0, 1, OpMode_iABx)  /*  4 OP_LOADKX     */
            , opmode(0, 0, 0, 0, 1, OpMode_iABC)  /*  5 OP_LOADFALSE  */
            , opmode(0, 0, 0, 0, 1, OpMode_iABC)  /*  6 OP_LFALSESKIP */
            , opmode(0, 0, 0, 0, 1, OpMode_iABC)  /*  7 OP_LOADTRUE   */
            , opmode(0, 0, 0, 0, 1, OpMode_iABC)  /*  8 OP_LOADNIL    */
            , opmode(0, 0, 0, 0, 1, OpMode_iABC)  /*  9 OP_GETUPVAL   */
            , opmode(0, 0, 0, 0, 0, OpMode_iABC)  /* 10 OP_SETUPVAL   */
            , opmode(0, 0, 0, 0, 1, OpMode_iABC)  /* 11 OP_GETTABUP   */
            , opmode(0, 0, 0, 0, 1, OpMode_iABC)  /* 12 OP_GETTABLE   */
            , opmode(0, 0, 0, 0, 1, OpMode_iABC)  /* 13 OP_GETI       */
            , opmode(0, 0, 0, 0, 1, OpMode_iABC)  /* 14 OP_GETFIELD   */
            , opmode(0, 0, 0, 0, 0, OpMode_iABC)  /* 15 OP_SETTABUP   */
            , opmode(0, 0, 0, 0, 0, OpMode_iABC)  /* 16 OP_SETTABLE   */
            , opmode(0, 0, 0, 0, 0, OpMode_iABC)  /* 17 OP_SETI       */
            , opmode(0, 0, 0, 0, 0, OpMode_iABC)  /* 18 OP_SETFIELD   */
            , opmode(0, 0, 0, 0, 1, OpMode_ivABC)  /* 19 OP_NEWTABLE   */
            , opmode(0, 0, 0, 0, 1, OpMode_iABC)  /* 20 OP_SELF       */
            , opmode(0, 0, 0, 0, 1, OpMode_iABC)  /* 21 OP_ADDI       */
            , opmode(0, 0, 0, 0, 1, OpMode_iABC)  /* 22 OP_ADDK       */
            , opmode(0, 0, 0, 0, 1, OpMode_iABC)  /* 23 OP_SUBK       */
            , opmode(0, 0, 0, 0, 1, OpMode_iABC)  /* 24 OP_MULK       */
            , opmode(0, 0, 0, 0, 1, OpMode_iABC)  /* 25 OP_MODK       */
            , opmode(0, 0, 0, 0, 1, OpMode_iABC)  /* 26 OP_POWK       */
            , opmode(0, 0, 0, 0, 1, OpMode_iABC)  /* 27 OP_DIVK       */
            , opmode(0, 0, 0, 0, 1, OpMode_iABC)  /* 28 OP_IDIVK      */
            , opmode(0, 0, 0, 0, 1, OpMode_iABC)  /* 29 OP_BANDK      */
            , opmode(0, 0, 0, 0, 1, OpMode_iABC)  /* 30 OP_BORK       */
            , opmode(0, 0, 0, 0, 1, OpMode_iABC)  /* 31 OP_BXORK      */
            , opmode(0, 0, 0, 0, 1, OpMode_iABC)  /* 32 OP_SHLI       */
            , opmode(0, 0, 0, 0, 1, OpMode_iABC)  /* 33 OP_SHRI       */
            , opmode(0, 0, 0, 0, 1, OpMode_iABC)  /* 34 OP_ADD        */
            , opmode(0, 0, 0, 0, 1, OpMode_iABC)  /* 35 OP_SUB        */
            , opmode(0, 0, 0, 0, 1, OpMode_iABC)  /* 36 OP_MUL        */
            , opmode(0, 0, 0, 0, 1, OpMode_iABC)  /* 37 OP_MOD        */
            , opmode(0, 0, 0, 0, 1, OpMode_iABC)  /* 38 OP_POW        */
            , opmode(0, 0, 0, 0, 1, OpMode_iABC)  /* 39 OP_DIV        */
            , opmode(0, 0, 0, 0, 1, OpMode_iABC)  /* 40 OP_IDIV       */
            , opmode(0, 0, 0, 0, 1, OpMode_iABC)  /* 41 OP_BAND       */
            , opmode(0, 0, 0, 0, 1, OpMode_iABC)  /* 42 OP_BOR        */
            , opmode(0, 0, 0, 0, 1, OpMode_iABC)  /* 43 OP_BXOR       */
            , opmode(0, 0, 0, 0, 1, OpMode_iABC)  /* 44 OP_SHL        */
            , opmode(0, 0, 0, 0, 1, OpMode_iABC)  /* 45 OP_SHR        */
            , opmode(1, 0, 0, 0, 0, OpMode_iABC)  /* 46 OP_MMBIN      */
            , opmode(1, 0, 0, 0, 0, OpMode_iABC)  /* 47 OP_MMBINI     */
            , opmode(1, 0, 0, 0, 0, OpMode_iABC)  /* 48 OP_MMBINK     */
            , opmode(0, 0, 0, 0, 1, OpMode_iABC)  /* 49 OP_UNM        */
            , opmode(0, 0, 0, 0, 1, OpMode_iABC)  /* 50 OP_BNOT       */
            , opmode(0, 0, 0, 0, 1, OpMode_iABC)  /* 51 OP_NOT        */
            , opmode(0, 0, 0, 0, 1, OpMode_iABC)  /* 52 OP_LEN        */
            , opmode(0, 0, 0, 0, 1, OpMode_iABC)  /* 53 OP_CONCAT     */
            , opmode(0, 0, 0, 0, 0, OpMode_iABC)  /* 54 OP_CLOSE      */
            , opmode(0, 0, 0, 0, 0, OpMode_iABC)  /* 55 OP_TBC        */
            , opmode(0, 0, 0, 0, 0, OpMode_isJ)  /* 56 OP_JMP        */
            , opmode(0, 0, 0, 1, 0, OpMode_iABC)  /* 57 OP_EQ         */
            , opmode(0, 0, 0, 1, 0, OpMode_iABC)  /* 58 OP_LT         */
            , opmode(0, 0, 0, 1, 0, OpMode_iABC)  /* 59 OP_LE         */
            , opmode(0, 0, 0, 1, 0, OpMode_iABC)  /* 60 OP_EQK        */
            , opmode(0, 0, 0, 1, 0, OpMode_iABC)  /* 61 OP_EQI        */
            , opmode(0, 0, 0, 1, 0, OpMode_iABC)  /* 62 OP_LTI        */
            , opmode(0, 0, 0, 1, 0, OpMode_iABC)  /* 63 OP_LEI        */
            , opmode(0, 0, 0, 1, 0, OpMode_iABC)  /* 64 OP_GTI        */
            , opmode(0, 0, 0, 1, 0, OpMode_iABC)  /* 65 OP_GEI        */
            , opmode(0, 0, 0, 1, 0, OpMode_iABC)  /* 66 OP_TEST       */
            , opmode(0, 0, 0, 1, 1, OpMode_iABC)  /* 67 OP_TESTSET    */
            , opmode(0, 1, 1, 0, 1, OpMode_iABC)  /* 68 OP_CALL       */
            , opmode(0, 1, 1, 0, 1, OpMode_iABC)  /* 69 OP_TAILCALL   */
            , opmode(0, 0, 1, 0, 0, OpMode_iABC)  /* 70 OP_RETURN     */
            , opmode(0, 0, 0, 0, 0, OpMode_iABC)  /* 71 OP_RETURN0    */
            , opmode(0, 0, 0, 0, 0, OpMode_iABC)  /* 72 OP_RETURN1    */
            , opmode(0, 0, 0, 0, 1, OpMode_iABx)  /* 73 OP_FORLOOP    */
            , opmode(0, 0, 0, 0, 1, OpMode_iABx)  /* 74 OP_FORPREP    */
            , opmode(0, 0, 0, 0, 0, OpMode_iABx)  /* 75 OP_TFORPREP   */
            , opmode(0, 0, 0, 0, 0, OpMode_iABC)  /* 76 OP_TFORCALL   */
            , opmode(0, 0, 0, 0, 1, OpMode_iABx)  /* 77 OP_TFORLOOP   */
            , opmode(0, 0, 1, 0, 0, OpMode_ivABC)  /* 78 OP_SETLIST    */
            , opmode(0, 0, 0, 0, 1, OpMode_iABx)  /* 79 OP_CLOSURE    */
            , opmode(0, 1, 0, 0, 1, OpMode_iABC)  /* 80 OP_VARARG     */
            , opmode(0, 0, 0, 0, 1, OpMode_iABC)  /* 81 OP_GETVARG    */
            , opmode(0, 0, 0, 0, 0, OpMode_iABx)  /* 82 OP_ERRNNIL    */
            , opmode(0, 0, 0, 0, 0, OpMode_iABC)  /* 83 OP_VARARGPREP */
            , opmode(0, 0, 0, 0, 0, OpMode_iAx)  /* 84 OP_EXTRAARG   */
    };

    private Opcodes() {
    }

    // -- CREATE_* (C: lopcodes.h) --

    // lopcodes.h: int2sC
    public static int int2sC(int i) {
        return i + OFFSET_sC;
    }


    // CREATE_ABC
    public static int CREATE_ABC(int o, int a, int b, int c, int k) {
        return (o & 0x7F)
                | ((a & 0xFF) << POS_A)
                | ((b & 0xFF) << POS_B)
                | ((c & 0xFF) << POS_C)
                | ((k & 1) << POS_k);
    }


    // CREATE_vABCk
    public static int CREATE_vABCk(int o, int a, int b, int c, int k) {
        return (o & 0x7F)
                | ((a & 0xFF) << POS_A)
                | ((b & 0x3F) << POS_vB)
                | ((c & 0x3FF) << POS_vC)
                | ((k & 1) << POS_k);
    }


    // CREATE_Ax
    public static int CREATE_Ax(int o, int a) {
        return (o & 0x7F) | ((a & 0x1FFFFFF) << POS_Ax);
    }

    // CREATE_sJ
    public static int CREATE_sJ(int o, int sj, int k) {
        return (o & 0x7F)
                | (((sj + OFFSET_sJ) & 0x1FFFFFF) << POS_sJ)
                | ((k & 1) << POS_k);
    }

    // -- GETARG_*/SETARG_* (C: lopcodes.h) --
    // GET_OPCODE
    public static int GET_OPCODE(int i) {
        return i & 0x7F;
    }

    // SET_OPCODE
    public static int SET_OPCODE(int i, int op) {
        return (i & ~0x7F) | (op & 0x7F);
    }

    // -- A 字段 --
    // CREATE_ABx
    public static int CREATE_ABx(int o, int a, int bc) {
        return (o & 0x7F)
                | ((a & 0xFF) << POS_A)
                | ((bc & 0x1FFFF) << POS_Bx);
    }

    // GETARG_A
    public static int GETARG_A(int i) {
        return (i >>> POS_A) & 0xFF;
    }

    // SETARG_A
    public static int SETARG_A(int i, int a) {
        return (i & ~(0xFF << POS_A)) | ((a & 0xFF) << POS_A);
    }

    // -- k 字段 --
    // GETARG_k
    public static int GETARG_k(int i) {
        return (i >>> POS_k) & 1;
    }

    // SETARG_k
    public static int SETARG_k(int i, int k) {
        return (i & ~(1 << POS_k)) | ((k & 1) << POS_k);
    }


    // -- B / vB 字段 --
    // GETARG_B
    public static int GETARG_B(int i) {
        return (i >>> POS_B) & 0xFF;
    }

    // GETARG_vB
    public static int GETARG_vB(int i) {
        return (i >>> POS_vB) & 0x3F;
    }


    // SETARG_B
    public static int SETARG_B(int i, int b) {
        return (i & ~(0xFF << POS_B)) | ((b & 0xFF) << POS_B);
    }


    // -- C / vC 字段 --
    // GETARG_C
    public static int GETARG_C(int i) {
        return (i >>> POS_C) & 0xFF;
    }

    // GETARG_vC
    public static int GETARG_vC(int i) {
        return (i >>> POS_vC) & 0x3FF;
    }


    // SETARG_C
    public static int SETARG_C(int i, int c) {
        return (i & ~(0xFF << POS_C)) | ((c & 0xFF) << POS_C);
    }


    // -- Bx / sBx 字段 --
    // GETARG_Bx
    public static int GETARG_Bx(int i) {
        return (i >>> POS_Bx) & 0x1FFFF;
    }

    // GETARG_sBx
    public static int GETARG_sBx(int i) {
        return GETARG_Bx(i) - OFFSET_sBx;
    }

    // SETARG_Bx
    public static int SETARG_Bx(int i, int bx) {
        return (i & ~(0x1FFFF << POS_Bx)) | ((bx & 0x1FFFF) << POS_Bx);
    }


    // -- Ax 字段 --
    // GETARG_Ax
    public static int GETARG_Ax(int i) {
        return (i >>> POS_Ax) & 0x1FFFFFF;
    }


    // -- OpMode + luaP_opmodes (C: lopcodes.h:OpMode, lopcodes.c:luaP_opmodes) --

    // -- sJ 字段（JMP）--
    // GETARG_sJ
    public static int GETARG_sJ(int i) {
        return ((i >>> POS_sJ) & 0x1FFFFFF) - OFFSET_sJ;
    }

    // SETARG_sJ
    public static int SETARG_sJ(int i, int sj) {
        return (i & ~(0x1FFFFFF << POS_sJ)) | (((sj + OFFSET_sJ) & 0x1FFFFFF) << POS_sJ);
    }

    // lopcodes.h: opmode
    // bit7:MM bit6:OT bit5:IT bit4:T bit3:A bit0-2:mode
    private static int opmode(int mm, int ot, int it, int t, int a, int m) {
        return (mm << 7) | (ot << 6) | (it << 5) | (t << 4) | (a << 3) | m;
    }

    // -- opmode属性测试 (C: lopcodes.h) --
    // lopcodes.h: getOpMode
    public static int getOpMode(int m) {
        return opModes[m] & 7;
    }

    // lopcodes.h: testAMode
    public static boolean testAMode(int m) {
        return (opModes[m] & (1 << 3)) != 0;
    }

    // lopcodes.h: testTMode
    public static boolean testTMode(int m) {
        return (opModes[m] & (1 << 4)) != 0;
    }



    // lopcodes.h: testMMMode
    public static boolean testMMMode(int m) {
        return (opModes[m] & (1 << 7)) != 0;
    }


    // -- 调试辅助 --
    // lopcodes.h: luaP_opnames
    public static String opName(int op) {
        switch (op) {
            case OP_MOVE:
                return "MOVE";
            case OP_LOADI:
                return "LOADI";
            case OP_LOADF:
                return "LOADF";
            case OP_LOADK:
                return "LOADK";
            case OP_LOADKX:
                return "LOADKX";
            case OP_LOADFALSE:
                return "LOADFALSE";
            case OP_LFALSESKIP:
                return "LFALSESKIP";
            case OP_LOADTRUE:
                return "LOADTRUE";
            case OP_LOADNIL:
                return "LOADNIL";
            case OP_GETUPVAL:
                return "GETUPVAL";
            case OP_SETUPVAL:
                return "SETUPVAL";
            case OP_GETTABUP:
                return "GETTABUP";
            case OP_GETTABLE:
                return "GETTABLE";
            case OP_GETI:
                return "GETI";
            case OP_GETFIELD:
                return "GETFIELD";
            case OP_SETTABUP:
                return "SETTABUP";
            case OP_SETTABLE:
                return "SETTABLE";
            case OP_SETI:
                return "SETI";
            case OP_SETFIELD:
                return "SETFIELD";
            case OP_NEWTABLE:
                return "NEWTABLE";
            case OP_SELF:
                return "SELF";
            case OP_ADDI:
                return "ADDI";
            case OP_ADDK:
                return "ADDK";
            case OP_SUBK:
                return "SUBK";
            case OP_MULK:
                return "MULK";
            case OP_MODK:
                return "MODK";
            case OP_POWK:
                return "POWK";
            case OP_DIVK:
                return "DIVK";
            case OP_IDIVK:
                return "IDIVK";
            case OP_BANDK:
                return "BANDK";
            case OP_BORK:
                return "BORK";
            case OP_BXORK:
                return "BXORK";
            case OP_SHLI:
                return "SHLI";
            case OP_SHRI:
                return "SHRI";
            case OP_ADD:
                return "ADD";
            case OP_SUB:
                return "SUB";
            case OP_MUL:
                return "MUL";
            case OP_MOD:
                return "MOD";
            case OP_POW:
                return "POW";
            case OP_DIV:
                return "DIV";
            case OP_IDIV:
                return "IDIV";
            case OP_BAND:
                return "BAND";
            case OP_BOR:
                return "BOR";
            case OP_BXOR:
                return "BXOR";
            case OP_SHL:
                return "SHL";
            case OP_SHR:
                return "SHR";
            case OP_MMBIN:
                return "MMBIN";
            case OP_MMBINI:
                return "MMBINI";
            case OP_MMBINK:
                return "MMBINK";
            case OP_UNM:
                return "UNM";
            case OP_BNOT:
                return "BNOT";
            case OP_NOT:
                return "NOT";
            case OP_LEN:
                return "LEN";
            case OP_CONCAT:
                return "CONCAT";
            case OP_CLOSE:
                return "CLOSE";
            case OP_TBC:
                return "TBC";
            case OP_JMP:
                return "JMP";
            case OP_EQ:
                return "EQ";
            case OP_LT:
                return "LT";
            case OP_LE:
                return "LE";
            case OP_EQK:
                return "EQK";
            case OP_EQI:
                return "EQI";
            case OP_LTI:
                return "LTI";
            case OP_LEI:
                return "LEI";
            case OP_GTI:
                return "GTI";
            case OP_GEI:
                return "GEI";
            case OP_TEST:
                return "TEST";
            case OP_TESTSET:
                return "TESTSET";
            case OP_CALL:
                return "CALL";
            case OP_TAILCALL:
                return "TAILCALL";
            case OP_RETURN:
                return "RETURN";
            case OP_RETURN0:
                return "RETURN0";
            case OP_RETURN1:
                return "RETURN1";
            case OP_FORLOOP:
                return "FORLOOP";
            case OP_FORPREP:
                return "FORPREP";
            case OP_TFORPREP:
                return "TFORPREP";
            case OP_TFORCALL:
                return "TFORCALL";
            case OP_TFORLOOP:
                return "TFORLOOP";
            case OP_SETLIST:
                return "SETLIST";
            case OP_CLOSURE:
                return "CLOSURE";
            case OP_VARARG:
                return "VARARG";
            case OP_GETVARG:
                return "GETVARG";
            case OP_ERRNNIL:
                return "ERRNNIL";
            case OP_VARARGPREP:
                return "VARARGPREP";
            case OP_EXTRAARG:
                return "EXTRAARG";
            default:
                return "OP_" + op;
        }
    }
}
