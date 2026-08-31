// ref: lcode.c
// diff: KCache去重(非线性扫描); expdesc需copyFrom; foldV1/foldV2/foldRes复用; Arrays.copyOf+MIN_JAVA_PARSE_ARRAY; SETARG写回; freereg检查REG_IS_TEMPORARY; TESTSET用NO_REG(255); fillidxk重置ind_ro; luaK_finish裁剪数组
package org.luajvm.compiler;

import static org.luajvm.compiler.Opcodes.CREATE_ABC;
import static org.luajvm.compiler.Opcodes.CREATE_ABx;
import static org.luajvm.compiler.Opcodes.CREATE_Ax;
import static org.luajvm.compiler.Opcodes.CREATE_sJ;
import static org.luajvm.compiler.Opcodes.CREATE_vABCk;
import static org.luajvm.compiler.Opcodes.GETARG_A;
import static org.luajvm.compiler.Opcodes.GETARG_B;
import static org.luajvm.compiler.Opcodes.GETARG_C;
import static org.luajvm.compiler.Opcodes.GETARG_k;
import static org.luajvm.compiler.Opcodes.GET_OPCODE;
import static org.luajvm.compiler.Opcodes.MAXARG_B;
import static org.luajvm.compiler.Opcodes.MAXARG_Bx;
import static org.luajvm.compiler.Opcodes.MAXARG_sBx;
import static org.luajvm.compiler.Opcodes.MAXARG_vC;
import static org.luajvm.compiler.Opcodes.MAXINDEXRK;
import static org.luajvm.compiler.Opcodes.MAX_FSTACK;
import static org.luajvm.compiler.Opcodes.NO_JUMP;
import static org.luajvm.compiler.Opcodes.NO_REG;
import static org.luajvm.compiler.Opcodes.OFFSET_sBx;
import static org.luajvm.compiler.Opcodes.OFFSET_sC;
import static org.luajvm.compiler.Opcodes.OP_ADD;
import static org.luajvm.compiler.Opcodes.OP_ADDI;
import static org.luajvm.compiler.Opcodes.OP_ADDK;
import static org.luajvm.compiler.Opcodes.OP_CONCAT;
import static org.luajvm.compiler.Opcodes.OP_EQ;
import static org.luajvm.compiler.Opcodes.OP_EQI;
import static org.luajvm.compiler.Opcodes.OP_EQK;
import static org.luajvm.compiler.Opcodes.OP_EXTRAARG;
import static org.luajvm.compiler.Opcodes.OP_GETFIELD;
import static org.luajvm.compiler.Opcodes.OP_GETI;
import static org.luajvm.compiler.Opcodes.OP_GETTABLE;
import static org.luajvm.compiler.Opcodes.OP_GETTABUP;
import static org.luajvm.compiler.Opcodes.OP_GETUPVAL;
import static org.luajvm.compiler.Opcodes.OP_GETVARG;
import static org.luajvm.compiler.Opcodes.OP_GTI;
import static org.luajvm.compiler.Opcodes.OP_JMP;
import static org.luajvm.compiler.Opcodes.OP_LFALSESKIP;
import static org.luajvm.compiler.Opcodes.OP_LOADF;
import static org.luajvm.compiler.Opcodes.OP_LOADFALSE;
import static org.luajvm.compiler.Opcodes.OP_LOADI;
import static org.luajvm.compiler.Opcodes.OP_LOADK;
import static org.luajvm.compiler.Opcodes.OP_LOADKX;
import static org.luajvm.compiler.Opcodes.OP_LOADNIL;
import static org.luajvm.compiler.Opcodes.OP_LOADTRUE;
import static org.luajvm.compiler.Opcodes.OP_LT;
import static org.luajvm.compiler.Opcodes.OP_LTI;
import static org.luajvm.compiler.Opcodes.OP_MMBIN;
import static org.luajvm.compiler.Opcodes.OP_MMBINI;
import static org.luajvm.compiler.Opcodes.OP_MMBINK;
import static org.luajvm.compiler.Opcodes.OP_MOVE;
import static org.luajvm.compiler.Opcodes.OP_NEWTABLE;
import static org.luajvm.compiler.Opcodes.OP_NOT;
import static org.luajvm.compiler.Opcodes.OP_RETURN;
import static org.luajvm.compiler.Opcodes.OP_RETURN0;
import static org.luajvm.compiler.Opcodes.OP_RETURN1;
import static org.luajvm.compiler.Opcodes.OP_SELF;
import static org.luajvm.compiler.Opcodes.OP_SETFIELD;
import static org.luajvm.compiler.Opcodes.OP_SETI;
import static org.luajvm.compiler.Opcodes.OP_SETLIST;
import static org.luajvm.compiler.Opcodes.OP_SETTABLE;
import static org.luajvm.compiler.Opcodes.OP_SETTABUP;
import static org.luajvm.compiler.Opcodes.OP_SETUPVAL;
import static org.luajvm.compiler.Opcodes.OP_SHLI;
import static org.luajvm.compiler.Opcodes.OP_SHRI;
import static org.luajvm.compiler.Opcodes.OP_TAILCALL;
import static org.luajvm.compiler.Opcodes.OP_TEST;
import static org.luajvm.compiler.Opcodes.OP_TESTSET;
import static org.luajvm.compiler.Opcodes.OP_UNM;
import static org.luajvm.compiler.Opcodes.OP_VARARG;
import static org.luajvm.compiler.Opcodes.SETARG_A;
import static org.luajvm.compiler.Opcodes.SETARG_B;
import static org.luajvm.compiler.Opcodes.SETARG_C;
import static org.luajvm.compiler.Opcodes.SETARG_k;
import static org.luajvm.compiler.Opcodes.SET_OPCODE;
import static org.luajvm.compiler.Opcodes.testTMode;
import static org.luajvm.compiler.SyntaxNodes.FuncState;
import static org.luajvm.compiler.SyntaxNodes.OPR_ADD;
import static org.luajvm.compiler.SyntaxNodes.OPR_AND;
import static org.luajvm.compiler.SyntaxNodes.OPR_BAND;
import static org.luajvm.compiler.SyntaxNodes.OPR_BNOT;
import static org.luajvm.compiler.SyntaxNodes.OPR_BOR;
import static org.luajvm.compiler.SyntaxNodes.OPR_BXOR;
import static org.luajvm.compiler.SyntaxNodes.OPR_CONCAT;
import static org.luajvm.compiler.SyntaxNodes.OPR_DIV;
import static org.luajvm.compiler.SyntaxNodes.OPR_EQ;
import static org.luajvm.compiler.SyntaxNodes.OPR_GE;
import static org.luajvm.compiler.SyntaxNodes.OPR_GT;
import static org.luajvm.compiler.SyntaxNodes.OPR_IDIV;
import static org.luajvm.compiler.SyntaxNodes.OPR_LE;
import static org.luajvm.compiler.SyntaxNodes.OPR_LEN;
import static org.luajvm.compiler.SyntaxNodes.OPR_LT;
import static org.luajvm.compiler.SyntaxNodes.OPR_MINUS;
import static org.luajvm.compiler.SyntaxNodes.OPR_MOD;
import static org.luajvm.compiler.SyntaxNodes.OPR_MUL;
import static org.luajvm.compiler.SyntaxNodes.OPR_NE;
import static org.luajvm.compiler.SyntaxNodes.OPR_NOT;
import static org.luajvm.compiler.SyntaxNodes.OPR_OR;
import static org.luajvm.compiler.SyntaxNodes.OPR_POW;
import static org.luajvm.compiler.SyntaxNodes.OPR_SHL;
import static org.luajvm.compiler.SyntaxNodes.OPR_SHR;
import static org.luajvm.compiler.SyntaxNodes.OPR_SUB;
import static org.luajvm.compiler.SyntaxNodes.VCALL;
import static org.luajvm.compiler.SyntaxNodes.VCONST;
import static org.luajvm.compiler.SyntaxNodes.VFALSE;
import static org.luajvm.compiler.SyntaxNodes.VINDEXED;
import static org.luajvm.compiler.SyntaxNodes.VINDEXI;
import static org.luajvm.compiler.SyntaxNodes.VINDEXSTR;
import static org.luajvm.compiler.SyntaxNodes.VINDEXUP;
import static org.luajvm.compiler.SyntaxNodes.VJMP;
import static org.luajvm.compiler.SyntaxNodes.VK;
import static org.luajvm.compiler.SyntaxNodes.VKFLT;
import static org.luajvm.compiler.SyntaxNodes.VKINT;
import static org.luajvm.compiler.SyntaxNodes.VKSTR;
import static org.luajvm.compiler.SyntaxNodes.VLOCAL;
import static org.luajvm.compiler.SyntaxNodes.VNIL;
import static org.luajvm.compiler.SyntaxNodes.VNONRELOC;
import static org.luajvm.compiler.SyntaxNodes.VRELOC;
import static org.luajvm.compiler.SyntaxNodes.VTRUE;
import static org.luajvm.compiler.SyntaxNodes.VUPVAL;
import static org.luajvm.compiler.SyntaxNodes.VVARARG;
import static org.luajvm.compiler.SyntaxNodes.VVARGIND;
import static org.luajvm.compiler.SyntaxNodes.VVARGVAR;
import static org.luajvm.compiler.SyntaxNodes.Vardesc;
import static org.luajvm.compiler.SyntaxNodes.expdesc;

import org.luajvm.core.LuaFloat;
import org.luajvm.core.LuaInteger;
import org.luajvm.core.LuaString;
import org.luajvm.core.LuaValue;
import org.luajvm.core.Prototype;
import org.luajvm.vm.LuaArith;

import java.util.Arrays;

public final class CodeGen {
    // lcode.c: luaK_prefix
    private static final expdesc PREFIX_ZERO = new expdesc();
    private static final int
            LUA_OPADD = 0, LUA_OPSUB = 1, LUA_OPMUL = 2, LUA_OPMOD = 3,
            LUA_OPPOW = 4, LUA_OPDIV = 5, LUA_OPIDIV = 6,
            LUA_OPBAND = 7, LUA_OPBOR = 8, LUA_OPBXOR = 9,
            LUA_OPSHL = 10, LUA_OPSHR = 11, LUA_OPUNM = 12, LUA_OPBNOT = 13;
    private static final int LIMLINEDIFF = 0x80;
    private static final int ABSLINEINFO = 0x80;

    // ===========================================================
    // 常量和表达式辅助
    // ===========================================================
    // ldebug.h —— MAXIWTHABS 默认 128（ltests 构建覆盖为 3）
    private static final int MAXIWTHABS = 128;
    // MINSIZEARRAY
    private static final int C_MINSIZEARRAY = 4;
    // java-only: Arrays.copyOf总是复制; 初始缓冲区取较大值(32)减少扩容拷贝,
    //   保持与luaM_growvector相同的增长/收缩行为
    private static final int MIN_JAVA_PARSE_ARRAY = 32;
    // abslineinfo打包存储{pc,line}对, 4个C条目=8个int
    private static final int MIN_ABSLINEINFO_SIZE = C_MINSIZEARRAY * 2;

    // ===========================================================
    // 跳转操作
    // ===========================================================
    // lvm.c: luaV_flttointeger
    private static final int F2Ieq = 0;
    private static final long NO_SCNUMBER = Long.MIN_VALUE;
    private static final long SCNUMBER_FLOAT_FLAG = 1L << 32;
    private static final int MAXSTACK = MAX_FSTACK;
    private static final int MAXARG_C = Opcodes.MAXARG_C;
    // TMS枚举  -  与C: ltm.h 1:1对齐; binopr2TM(opr)=(opr-OPR_ADD)+TM_ADD
    private static final int
            TM_INDEX = 0, TM_NEWINDEX = 1, TM_GC = 2, TM_MODE = 3, TM_LEN = 4, TM_EQ = 5,
            TM_ADD = 6, TM_SUB = 7, TM_MUL = 8, TM_MOD = 9,
            TM_POW = 10, TM_DIV = 11, TM_IDIV = 12,
            TM_BAND = 13, TM_BOR = 14, TM_BXOR = 15,
            TM_SHL = 16, TM_SHR = 17,
            TM_UNM = 18, TM_BNOT = 19, TM_LT = 20, TM_LE = 21,
            TM_CONCAT = 22, TM_CALL = 23, TM_CLOSE = 24;
    // java diff: C 用局部 out-param；Java 改用 ThreadLocal，防并发 Globals 编译时互相覆盖
    private static final ThreadLocal<long[]> fltToIntegerOut =
            ThreadLocal.withInitial(() -> new long[1]);

    static {
        PREFIX_ZERO.init(VKINT, 0);
        PREFIX_ZERO.ival = 0;
    }

    private CodeGen() {
    }

    // lcode.c: setivalue
    private static void setIValue(expdesc v, long x) {
        v.k = VKINT;
        v.ival = x;
        v.nval = 0;
        v.strval = null;
        v.info = 0;
        v.ind_t = 0;
        v.ind_idx = 0;
        v.ind_ro = false;
        v.ind_keystr = -1;
        v.var_ridx = 0;
        v.var_vidx = 0;
        v.t = NO_JUMP;
        v.f = NO_JUMP;
    }

    // lcode.c: setfltvalue
    private static void setFltValue(expdesc v, double x) {
        v.k = VKFLT;
        v.ival = 0;
        v.nval = x;
        v.strval = null;
        v.info = 0;
        v.ind_t = 0;
        v.ind_idx = 0;
        v.ind_ro = false;
        v.ind_keystr = -1;
        v.var_ridx = 0;
        v.var_vidx = 0;
        v.t = NO_JUMP;
        v.f = NO_JUMP;
    }

    // lcode.c: tonumeral
    private static boolean tonumeral(expdesc e, expdesc v) {
        if (hasjumps(e))
            return false;
        switch (e.k) {
            case VKINT:
                if (v != null) setIValue(v, e.ival);
                return true;
            case VKFLT:
                if (v != null) setFltValue(v, e.nval);
                return true;
            default:
                return false;
        }
    }

    // lcode.c: tonumeral
    private static boolean tonumeral(expdesc e) {
        return tonumeral(e, null);
    }

    // lcode.c: previousinstruction
    private static int previousinstruction(FuncState fs) {
        if (fs.pc > fs.lasttarget)
            return fs.f.code[fs.pc - 1];
        else
            return ~0;  // ~0: GET_OPCODE返回0x7F (OP_EXTRAARG), 非LOADNIL  -  窥孔未命中是安全的
    }

    // lcode.c: codeNil
    public static void codeNil(FuncState fs, int from, int n) {
        int l = from + n - 1;
        int previous = previousinstruction(fs);
        if (GET_OPCODE(previous) == OP_LOADNIL) {
            int pfrom = GETARG_A(previous);
            int pl = pfrom + GETARG_B(previous);
            if ((pfrom <= from && from <= pl + 1) ||
                    (from <= pfrom && pfrom <= l + 1)) {
                if (pfrom < from) from = pfrom;
                if (pl > l) l = pl;
                int idx = fs.pc - 1;
                int inst = fs.f.code[idx];
                inst = SETARG_A(inst, from);
                inst = SETARG_B(inst, l - from);
                fs.f.code[idx] = inst;
                return;
            }
        }
        codeABCk(fs, OP_LOADNIL, from, n - 1, 0, 0);
    }

    // ===========================================================
    // 行号信息
    // ===========================================================

    // lcode.c: getjump
    private static int getjump(FuncState fs, int pc) {
        int inst = fs.f.code[pc];
        if (GET_OPCODE(inst) != OP_JMP) return Opcodes.NO_JUMP;
        int offset = Opcodes.GETARG_sJ(inst);
        if (offset == Opcodes.NO_JUMP) return Opcodes.NO_JUMP;
        return (pc + 1) + offset;
    }

    // lcode.c: fixjump
    static void fixjump(FuncState fs, int pc, int dest) {
        int jmp = fs.f.code[pc];
        int offset = dest - (pc + 1);
        lua_assert(dest != Opcodes.NO_JUMP);
        if (!(-Opcodes.OFFSET_sJ <= offset && offset <= Opcodes.MAXARG_sJ - Opcodes.OFFSET_sJ))
            throw fs.ls.syntaxError("control structure too long");
        lua_assert(Opcodes.GET_OPCODE(jmp) == OP_JMP);
        fs.f.code[pc] = Opcodes.SETARG_sJ(jmp, offset);
    }

    // lcode.c: codeConcat
    public static int codeConcat(FuncState fs, int l1, int l2) {
        if (l2 == Opcodes.NO_JUMP) return l1;
        if (l1 == Opcodes.NO_JUMP) return l2;
        int l = l1;
        int next;
        while ((next = getjump(fs, l)) != Opcodes.NO_JUMP) {
            l = next;
        }
        fixjump(fs, l, l2);
        return l1;
    }

    // lcode.c: codeJump
    public static int codeJump(FuncState fs) {
        return codesJ(fs, OP_JMP, Opcodes.NO_JUMP, 0);
    }

    // lcode.c: codeRet
    public static int codeRet(FuncState fs, int first, int nret) {
        int op = (nret == 0) ? OP_RETURN0 : (nret == 1) ? OP_RETURN1 : OP_RETURN;
        // lcode.c: luaK_ret  -  恒执行（nret==-1 时 nret+1==0 <= MAXARG_B 恒真）
        Parser.checkLimit(fs, nret + 1, MAXARG_B, "returns");
        return codeABCk(fs, op, first, nret + 1, 0, 0);
    }

    // lcode.c: condjump
    private static int condjump(FuncState fs, int op, int a, int b, int c, int k) {
        codeABCk(fs, op, a, b, c, k);
        return codeJump(fs);
    }

    // lcode.c: getLabel
    public static int getLabel(FuncState fs) {
        fs.lasttarget = fs.pc;
        return fs.pc;
    }

    // lcode.c: getjumpcontrol
    private static int getjumpcontrol(FuncState fs, int pc) {
        int pi = pc - 1;
        // java-only: testTMode经静态导入, 按opmodes表的T位识别全部比较opcode
        if (pi >= 0 && testTMode(GET_OPCODE(fs.f.code[pi])))
            return pi;
        else
            return pc;
    }

    // lcode.c: patchtestreg
    private static boolean patchtestreg(FuncState fs, int node, int reg) {
        int i = getjumpcontrol(fs, node);
        int pi = fs.f.code[i];
        if (GET_OPCODE(pi) != OP_TESTSET)
            return false;
        if (reg != Opcodes.NO_REG && reg != GETARG_B(pi))
            fs.f.code[i] = SETARG_A(pi, reg);
        else {
            int pc = fs.f.code[i];
            fs.f.code[i] = CREATE_ABC(OP_TEST, GETARG_B(pc), 0, 0, GETARG_k(pc));
        }
        return true;
    }

    // ===========================================================
    // 指令发射
    // ===========================================================

    // lcode.c: removevalues
    private static void removevalues(FuncState fs, int list) {
        for (; list != Opcodes.NO_JUMP; list = getjump(fs, list)) {
            patchtestreg(fs, list, Opcodes.NO_REG);
        }
    }

    // lcode.c: patchlistaux
    private static void patchlistaux(FuncState fs, int list, int vtarget, int reg, int dtarget) {
        while (list != Opcodes.NO_JUMP) {
            int next = getjump(fs, list);
            if (patchtestreg(fs, list, reg))
                fixjump(fs, list, vtarget);
            else
                fixjump(fs, list, dtarget);
            list = next;
        }
    }

    // lcode.c: patchList
    public static void patchList(FuncState fs, int list, int target) {
        patchlistaux(fs, list, target, Opcodes.NO_REG, target);
    }

    // lcode.c: patchToHere
    public static void patchToHere(FuncState fs, int list) {
        fs.lasttarget = fs.pc;
        patchList(fs, list, fs.pc);
    }

    // lcode.c: savelineinfo
    private static void savelineinfo(FuncState fs, Prototype f, int line) {
        int linedif = line - fs.previousline;
        int pc = fs.pc - 1;
        if (Math.abs(linedif) >= LIMLINEDIFF || fs.iwthabs++ >= MAXIWTHABS) {
            int slot = fs.nabslineinfo * 2;
            if (slot + 1 >= f.abslineinfo.length) {
                f.abslineinfo = Arrays.copyOf(f.abslineinfo, Math.max(f.abslineinfo.length * 2, MIN_ABSLINEINFO_SIZE));
            }
            f.abslineinfo[slot] = pc;
            f.abslineinfo[slot + 1] = line;
            fs.nabslineinfo++;
            linedif = ABSLINEINFO;
            fs.iwthabs = 1;
        }
        if (pc >= f.lineinfo.length) {
            int newsize = Math.max(f.lineinfo.length * 2, MIN_JAVA_PARSE_ARRAY);
            f.lineinfo = Arrays.copyOf(f.lineinfo, newsize);
        }
        f.lineinfo[pc] = linedif & 0xFF;
        fs.previousline = line;
    }

    // lcode.c: removelastlineinfo
    private static void removelastlineinfo(FuncState fs) {
        Prototype f = fs.f;
        int pc = fs.pc - 1;
        if (pc < 0 || pc >= f.lineinfo.length) return;
        if (f.lineinfo[pc] != ABSLINEINFO) {
            int delta = f.lineinfo[pc];
            if (delta >= 128) delta -= 256;
            fs.previousline -= delta;
            fs.iwthabs--;
        } else {
            lua_assert(fs.nabslineinfo > 0);
            fs.nabslineinfo--;
            fs.iwthabs = (byte) (MAXIWTHABS + 1);
        }
    }

    // lcode.c: removelastinstruction
    private static void removelastinstruction(FuncState fs) {
        removelastlineinfo(fs);
        fs.pc--;
    }

    // lcode.c: code
    static int code(FuncState fs, int i) {
        Prototype f = fs.f;
        if (f.code.length <= fs.pc) {
            int newsize = Math.max(f.code.length * 2, MIN_JAVA_PARSE_ARRAY);
            f.code = Arrays.copyOf(f.code, newsize);
            f.lineinfo = Arrays.copyOf(f.lineinfo, newsize);
        }
        f.code[fs.pc] = i;
        fs.pc++;
        savelineinfo(fs, fs.f, fs.ls.lastline());
        return fs.pc - 1;
    }

    // ===========================================================
    // 栈管理
    // ===========================================================

    // lcode.c: codeABCk
    static int codeABCk(FuncState fs, int o, int a, int b, int c, int k) {
        lua_assert(GET_OPCODE(o) != OP_EXTRAARG);
        return code(fs, CREATE_ABC(o, a, b, c, k));
    }

    // lcode.c: codevABCk
    static int codevABCk(FuncState fs, int o, int a, int b, int c, int k) {
        return code(fs, CREATE_vABCk(o, a, b, c, k));
    }

    // lcode.c: codeABx
    static int codeABx(FuncState fs, int o, int a, int bc) {
        // codeABx不校验操作码; OP_CLOSURE/OP_LOADK/OP_GETVARG共用
        return code(fs, CREATE_ABx(o, a, bc));
    }

    // lcode.c: codeAsBx
    static int codeAsBx(FuncState fs, int o, int a, int bc) {
        return code(fs, CREATE_ABx(o, a, bc + OFFSET_sBx));
    }

    // lcode.c: codesJ
    static int codesJ(FuncState fs, int o, int sj, int k) {
        return code(fs, CREATE_sJ(o, sj, k));
    }

    // lcode.c: codeextraarg
    private static void codeextraarg(FuncState fs, int ax) {
        code(fs, CREATE_Ax(OP_EXTRAARG, ax));
    }

    // lcode.c: codeK
    private static int codeK(FuncState fs, int reg, int idx) {
        if (idx <= MAXARG_Bx)
            return codeABx(fs, OP_LOADK, reg, idx);
        else {
            codeABx(fs, OP_LOADKX, reg, 0);
            codeextraarg(fs, idx);
            return fs.pc - 1;
        }
    }

    // lcode.c: checkStack
    public static void checkStack(FuncState fs, int n) {
        int newstack = fs.freereg + n;
        if (newstack > fs.f.maxstacksize) {
            Parser.checkLimit(fs, newstack, MAXSTACK, "registers");
            fs.f.maxstacksize = newstack;
        }
    }

    // lcode.c: reserveRegs
    public static void reserveRegs(FuncState fs, int n) {
        checkStack(fs, n);
        fs.freereg += n;
    }

    private static void freereg(FuncState fs) {
        fs.freereg--;
    }

    // ===========================================================
    // 常量池
    // ===========================================================

    // lcode.c: freereg
    private static void freereg(FuncState fs, int reg) {
        // java-only: freereg检查REG_IS_TEMPORARY
        if (reg < 0 || !REG_IS_TEMPORARY(fs, reg)) return;
        fs.freereg--;
        lua_assert(fs.freereg == reg);
    }

    // REG_IS_TEMPORARY
    private static boolean REG_IS_TEMPORARY(FuncState fs, int reg) {
        // java-only: 用nvarstack()而非nactvar (tobe-closed变量可能不在寄存器)
        return reg >= nvarstack(fs);
    }

    // lcode.c: freeregs
    private static void freeexps(FuncState fs, int r1, int r2) {
        // 先释放较高寄存器, 让freereg==reg-1对两者都成立
        if (r1 > r2) {
            freereg(fs, r1);
            freereg(fs, r2);
        } else {
            freereg(fs, r2);
            freereg(fs, r1);
        }
    }

    // lcode.c: freeexps
    private static void freeexps(FuncState fs, expdesc e1, expdesc e2) {
        int r1 = (e1.k == VNONRELOC) ? e1.info : -1;
        int r2 = (e2.k == VNONRELOC) ? e2.info : -1;
        freeexps(fs, r1, r2);
    }

    // lcode.c: freeexp_reg
    public static void freeexp(FuncState fs, int reg) {
        // java-only: freeexp检查freereg==reg+1且REG_IS_TEMPORARY
        if (fs.freereg == reg + 1 && REG_IS_TEMPORARY(fs, reg)) {
            fs.freereg--;
        }
    }

    // lcode.c: freeexp
    public static void freeexp(FuncState fs, expdesc e) {
        if (e.k == VNONRELOC) freeexp(fs, e.info);
    }


    // lcode.c: addk
    private static int addk(FuncState fs, LuaValue key, LuaValue v) {
        // java-only: KCache去重 (C的addk线性扫描对大常量池O(n^2))
        Prototype f = fs.f;
        if (fs.nk + 1 > f.k.length)
            f.k = Arrays.copyOf(f.k, Math.max(f.k.length * 2, MIN_JAVA_PARSE_ARRAY));
        f.k[fs.nk] = v;
        return fs.nk++;
    }

    // lcode.c: stringK
    static int stringK(FuncState fs, LuaString s) {
        if (s == null) {
            throw new RuntimeException("stringK: null string");
        }
        int cached = fs.kcache.getStringIndex(s, fs.f.k);
        if (cached >= 0) return cached;
        int idx = addk(fs, s, s);
        fs.kcache.putString(s, idx);
        return idx;
    }

    // lcode.c: luaK_intK
    static int intConst(FuncState fs, long n) {
        LuaValue k = LuaInteger.valueOf(n);
        int cached = fs.kcache.getIntegerIndex(n, fs.f.k);
        if (cached >= 0) return cached;
        int idx = addk(fs, k, k);
        fs.kcache.putInteger(n, idx);
        return idx;
    }

    // lcode.c: luaK_numberK
    static int numberK(FuncState fs, double r) {
        LuaValue o = LuaFloat.valueOf(r);
        if (r == 0) {
            if (fs.kcache.zeroFloatIdx >= 0) return fs.kcache.zeroFloatIdx;
            return fs.kcache.zeroFloatIdx = addk(fs, o, o);
        }
        double q = Math.scalb(1.0, -52);
        double key = r * (1.0 + q);
        if (!fltToInteger(key, F2Ieq)) {
            long keyBits = Double.doubleToLongBits(key);
            int cached = fs.kcache.getFloatIndex(keyBits, o.todouble(), fs.f.k);
            if (cached >= 0) {
                return cached;
            } else {
                int idx = addk(fs, o, o);
                fs.kcache.putFloat(keyBits, idx);
                return idx;
            }
        }
        return addk(fs, o, o);
    }

    // lcode.c: boolF
    private static int boolF(FuncState fs) {
        if (fs.kcache.falseIdx >= 0) return fs.kcache.falseIdx;
        return fs.kcache.falseIdx = addk(fs, LuaValue.FALSE, LuaValue.FALSE);
    }

    // lcode.c: boolT
    private static int boolT(FuncState fs) {
        if (fs.kcache.trueIdx >= 0) return fs.kcache.trueIdx;
        return fs.kcache.trueIdx = addk(fs, LuaValue.TRUE, LuaValue.TRUE);
    }

    // lcode.c: nilK
    private static int nilK(FuncState fs) {
        if (fs.kcache.nilIdx >= 0) return fs.kcache.nilIdx;
        return fs.kcache.nilIdx = addk(fs, LuaValue.NIL, LuaValue.NIL);
    }

    // lcode.c: fitsBx
    private static boolean fitsBx(long i) {
        return -OFFSET_sBx <= i && i <= MAXARG_sBx - OFFSET_sBx;
    }

    // ===========================================================
    // 表达式转换
    // ===========================================================

    // lcode.c: fitsC
    private static boolean fitsC(long i) {
        return -OFFSET_sC <= i && i <= MAXARG_C - OFFSET_sC;
    }

    // lcode.c: int2sC
    private static int int2sC(int i) {
        return i + OFFSET_sC;
    }

    // lcode.c: luaK_int
    static void intK(FuncState fs, int reg, long i) {
        if (-OFFSET_sBx <= i && i <= MAXARG_sBx - OFFSET_sBx)
            codeAsBx(fs, OP_LOADI, reg, (int) i);
        else
            codeK(fs, reg, intConst(fs, i));
    }

    // lcode.c: luaK_float
    static void flt(FuncState fs, int reg, double r) {
        long i;
        if (fltToInteger(r, F2Ieq) && fitsBx(i = fltToInteger(r, F2Ieq) ? (long) r : 0))
            codeAsBx(fs, OP_LOADF, reg, (int) i);
        else
            codeK(fs, reg, numberK(fs, r));
    }

    private static boolean fltToInteger(double f, int mode) {
        if (f != f) return false;
        double i = Math.floor(f);
        if (i < Long.MIN_VALUE || i > Long.MAX_VALUE) return false;
        long result = (long) i;
        if (mode == 0 && f != (double) result) return false;
        fltToIntegerOut.get()[0] = result;
        return true;
    }

    // lcode.c: setReturns
    public static void setReturns(FuncState fs, expdesc e, int nresults) {
        // 用SETARG_C(而非CREATE_ABC), 保持A/B/k不变
        Parser.checkLimit(fs, nresults + 1, MAXARG_C, "multiple results");
        int pc = e.info;
        int inst = fs.f.code[pc];
        if (e.k == VCALL) {
            fs.f.code[pc] = SETARG_C(inst, nresults + 1);
        } else {
            lua_assert(e.k == VVARARG);
            inst = SETARG_C(inst, nresults + 1);
            inst = SETARG_A(inst, fs.freereg);
            fs.f.code[pc] = inst;
            reserveRegs(fs, 1);
        }
    }

    // lcode.c: str2K
    private static int str2K(FuncState fs, expdesc e) {
        lua_assert(e.k == VKSTR);
        e.info = stringK(fs, e.strval);
        e.k = VK;
        return e.info;
    }

    // lcode.c: setOneRet
    public static void setOneRet(FuncState fs, expdesc e) {

        if (e.k == VCALL) {
            lua_assert(GETARG_C(fs.f.code[e.info]) == 2);
            e.k = VNONRELOC;
            e.info = GETARG_A(fs.f.code[e.info]);
        } else if (e.k == VVARARG) {
            int pc = e.info;
            fs.f.code[pc] = SETARG_C(fs.f.code[pc], 2);
            e.k = VRELOC;
        }
    }

    // lcode.c: needvatab
    static void needvatab(Prototype f) {
        f.flag |= Prototype.PF_VATAB;
    }

    // lcode.c: luaK_vapar2local
    public static void vapar2local(FuncState fs, expdesc var) {
        needvatab(fs.f);
        var.k = VLOCAL;
    }

    // ===========================================================
    // exp2reg 及相关
    // ===========================================================

    // lcode.c: constdesc2val
    private static LuaValue constdesc2val(FuncState fs, expdesc e) {
        switch (e.k) {
            case VNIL:
                return LuaValue.NIL;
            case VTRUE:
                return LuaValue.TRUE;
            case VFALSE:
                return LuaValue.FALSE;
            case VKINT:
                return LuaInteger.valueOf(e.ival);
            case VKFLT:
                return LuaFloat.valueOf(e.nval);
            case VKSTR:
                return e.strval;
            case VK:
                return fs.f.k[e.info];
            case VCONST:
                return const2val(fs, e);
            default:
                throw new AssertionError("invalid compile-time constant kind: " + e.k);
        }
    }

    // lcode.c: const2val
    private static LuaValue const2val(FuncState fs, expdesc e) {
        lua_assert(e.k == VCONST);
        return constdesc2val(fs, fs.ls.dyd.actvar().get(e.info).ctc);
    }

    // lcode.c: dischargeVars
    public static void dischargeVars(FuncState fs, expdesc e) {

        switch (e.k) {
            case VCONST: {
                e.copyFrom(fs.ls.dyd.actvar().get(e.info).ctc);
                break;
            }
            case VVARGVAR: {
                vapar2local(fs, e);
            }  // FALLTHROUGH
            case VLOCAL: {
                int temp = e.var_ridx;
                e.info = temp;
                e.k = VNONRELOC;
                break;
            }
            case VUPVAL: {
                e.info = codeABCk(fs, OP_GETUPVAL, 0, e.info, 0, 0);
                e.k = VRELOC;
                break;
            }
            case VINDEXUP: {
                e.info = codeABCk(fs, OP_GETTABUP, 0, e.ind_t, e.ind_idx, 0);
                e.k = VRELOC;
                break;
            }
            case VINDEXI: {
                freereg(fs, e.ind_t);
                e.info = codeABCk(fs, OP_GETI, 0, e.ind_t, e.ind_idx, 0);
                e.k = VRELOC;
                break;
            }
            case VINDEXSTR: {
                freereg(fs, e.ind_t);
                e.info = codeABCk(fs, OP_GETFIELD, 0, e.ind_t, e.ind_idx, 0);
                e.k = VRELOC;
                break;
            }
            case VINDEXED: {
                freeexps(fs, e.ind_t, e.ind_idx);
                e.info = codeABCk(fs, OP_GETTABLE, 0, e.ind_t, e.ind_idx, 0);
                e.k = VRELOC;
                break;
            }
            case VVARGIND: {
                freeexps(fs, e.ind_t, e.ind_idx);
                e.info = codeABCk(fs, OP_GETVARG, 0, e.ind_t, e.ind_idx, 0);
                e.k = VRELOC;
                break;
            }
            case VVARARG:
            case VCALL: {
                setOneRet(fs, e);
                break;
            }
            default:
                break;
        }
    }

    // lcode.c: discharge2reg
    private static void discharge2reg(FuncState fs, expdesc e, int reg) {
        dischargeVars(fs, e);
        switch (e.k) {
            case VNIL:
                codeNil(fs, reg, 1);
                break;
            case VFALSE:
                codeABCk(fs, OP_LOADFALSE, reg, 0, 0, 0);
                break;
            case VTRUE:
                codeABCk(fs, OP_LOADTRUE, reg, 0, 0, 0);
                break;
            case VK:
                codeK(fs, reg, e.info);
                break;
            case VKFLT:
                flt(fs, reg, e.nval);
                break;
            case VKINT:
                intK(fs, reg, e.ival);
                break;
            case VKSTR:
                e.info = stringK(fs, e.strval);
                e.k = VK;
                codeK(fs, reg, e.info);
                break;
            case VRELOC:
                fs.f.code[e.info] = SETARG_A(fs.f.code[e.info], reg);
                break;
            case VNONRELOC:
                if (reg != e.info)
                    codeABCk(fs, OP_MOVE, reg, e.info, 0, 0);
                break;
            default:
                lua_assert(e.k == VJMP);
                return;
        }
        e.info = reg;
        e.k = VNONRELOC;
    }

    // lcode.c: discharge2anyreg
    private static void discharge2anyreg(FuncState fs, expdesc e) {
        if (e.k != VNONRELOC) {
            reserveRegs(fs, 1);
            discharge2reg(fs, e, fs.freereg - 1);
        }
    }

    // lcode.c: code_loadbool
    private static int codeLoadBool(FuncState fs, int A, int op) {
        getLabel(fs);
        return codeABCk(fs, op, A, 0, 0, 0);
    }

    // lcode.c: need_value
    private static boolean needValue(FuncState fs, int list) {
        for (; list != Opcodes.NO_JUMP; list = getjump(fs, list)) {
            int i = fs.f.code[getjumpcontrol(fs, list)];
            if (GET_OPCODE(i) != OP_TESTSET)
                return true;
        }
        return false;
    }

    // lcode.c: exp2reg
    static void exp2reg(FuncState fs, expdesc e, int reg) {
        discharge2reg(fs, e, reg);
        if (e.k == VJMP)
            e.t = codeConcat(fs, e.t, e.info);
        if (hasjumps(e)) {
            int final_;
            int p_f = Opcodes.NO_JUMP;
            int p_t = Opcodes.NO_JUMP;
            if (needValue(fs, e.t) || needValue(fs, e.f)) {
                int fj = (e.k == VJMP) ? Opcodes.NO_JUMP : codeJump(fs);
                p_f = codeLoadBool(fs, reg, OP_LFALSESKIP);
                p_t = codeLoadBool(fs, reg, OP_LOADTRUE);
                patchToHere(fs, fj);
            }
            final_ = getLabel(fs);
            patchlistaux(fs, e.f, final_, reg, p_f);
            patchlistaux(fs, e.t, final_, reg, p_t);
        }
        e.f = Opcodes.NO_JUMP;
        e.t = Opcodes.NO_JUMP;
        e.info = reg;
        e.k = VNONRELOC;
    }

    // lcode.c: hasjumps
    private static boolean hasjumps(expdesc e) {
        return e.t != Opcodes.NO_JUMP || e.f != Opcodes.NO_JUMP;
    }

    // ===========================================================
    // K 优化
    // ===========================================================

    // java-only
    public static boolean hasjumpsPublic(expdesc e) {
        return hasjumps(e);
    }

    // lcode.c: exp2NextReg
    public static void exp2NextReg(FuncState fs, expdesc e) {
        dischargeVars(fs, e);
        freeexp(fs, e);
        reserveRegs(fs, 1);
        exp2reg(fs, e, fs.freereg - 1);
    }

    // lcode.c: exp2AnyReg
    public static int exp2AnyReg(FuncState fs, expdesc e) {
        dischargeVars(fs, e);
        if (e.k == VNONRELOC) {
            if (!hasjumps(e))
                return e.info;
            if (e.info >= nvarstack(fs)) {
                exp2reg(fs, e, e.info);
                return e.info;
            }
        }
        exp2NextReg(fs, e);
        return e.info;
    }

    // ===========================================================
    // 变量存储
    // ===========================================================

    // lcode.c: exp2AnyRegup
    public static void exp2AnyRegup(FuncState fs, expdesc e) {
        if ((e.k != VUPVAL && e.k != VVARGVAR) || hasjumps(e))
            exp2AnyReg(fs, e);
    }

    // ===========================================================
    // 条件
    // ===========================================================

    // lcode.c: exp2Val
    public static void exp2Val(FuncState fs, expdesc e) {
        if (e.k == VJMP || hasjumps(e))
            exp2AnyReg(fs, e);
        else
            dischargeVars(fs, e);
    }

    // lcode.c: luaK_exp2K
    private static boolean exp2K(FuncState fs, expdesc e) {
        if (!hasjumps(e)) {
            int info;
            switch (e.k) {
                case VTRUE:
                    info = boolT(fs);
                    break;
                case VFALSE:
                    info = boolF(fs);
                    break;
                case VNIL:
                    info = nilK(fs);
                    break;
                case VKINT:
                    info = intConst(fs, e.ival);
                    break;
                case VKFLT:
                    info = numberK(fs, e.nval);
                    break;
                case VKSTR:
                    info = stringK(fs, e.strval);
                    break;
                case VK:
                    info = e.info;
                    break;
                default:
                    return false;
            }
            if (info <= MAXINDEXRK) {
                e.k = VK;
                e.info = info;
                return true;
            }
        }
        return false;
    }

    // lcode.c: exp2RK
    private static boolean exp2RK(FuncState fs, expdesc e) {
        if (exp2K(fs, e))
            return true;
        exp2AnyReg(fs, e);
        return false;
    }

    // lcode.c: codeABRK
    private static void codeABRK(FuncState fs, int op, int A, int B, expdesc ec) {
        int k = exp2RK(fs, ec) ? 1 : 0;
        codeABCk(fs, op, A, B, ec.info, k);
    }

    // lcode.c: storeVar
    public static void storeVar(FuncState fs, expdesc var, expdesc ex) {
        switch (var.k) {
            case VLOCAL: {
                freeexp(fs, ex);
                exp2reg(fs, ex, var.var_ridx);
                return;
            }
            case VUPVAL: {
                int e = exp2AnyReg(fs, ex);
                codeABCk(fs, OP_SETUPVAL, e, var.info, 0, 0);
                break;
            }
            case VINDEXUP: {
                codeABRK(fs, OP_SETTABUP, var.ind_t, var.ind_idx, ex);
                break;
            }
            case VINDEXI: {
                codeABRK(fs, OP_SETI, var.ind_t, var.ind_idx, ex);
                break;
            }
            case VINDEXSTR: {
                codeABRK(fs, OP_SETFIELD, var.ind_t, var.ind_idx, ex);
                break;
            }
            case VVARGIND: {
                needvatab(fs.f);
                codeABRK(fs, OP_SETTABLE, var.ind_t, var.ind_idx, ex);
                break;
            }
            case VINDEXED: {
                codeABRK(fs, OP_SETTABLE, var.ind_t, var.ind_idx, ex);
                break;
            }
            default:
                lua_assert(false);
        }
        freeexp(fs, ex);
    }

    // ===========================================================
    // 常量辅助
    // ===========================================================

    // lcode.c: negatecondition
    private static void negatecondition(FuncState fs, expdesc e) {
        int pc = getjumpcontrol(fs, e.info);
        int i = fs.f.code[pc];
        lua_assert(testTMode(GET_OPCODE(i)) && GET_OPCODE(i) != OP_TESTSET && GET_OPCODE(i) != OP_TEST);
        fs.f.code[pc] = SETARG_k(i, GETARG_k(i) ^ 1);
    }

    // lcode.c: jumponcond
    private static int jumponcond(FuncState fs, expdesc e, int cond) {
        if (e.k == VRELOC) {
            int ie = fs.f.code[e.info];
            if (GET_OPCODE(ie) == OP_NOT) {
                int nb = GETARG_B(ie);
                freeexp(fs, e);
                removelastinstruction(fs);
                return condjump(fs, OP_TEST, nb, 0, 0, cond == 0 ? 1 : 0);
            }
        }
        discharge2anyreg(fs, e);
        freeexp(fs, e);
        // java-only: TESTSET的A字段用NO_REG(255)而非NO_JUMP(-1)
        return condjump(fs, OP_TESTSET, NO_REG, e.info, 0, cond);
    }

    // lcode.c: goIfTrue
    public static void goIfTrue(FuncState fs, expdesc e) {
        int pc;
        dischargeVars(fs, e);
        switch (e.k) {
            case VJMP:
                negatecondition(fs, e);
                pc = e.info;
                break;
            case VK:
            case VKFLT:
            case VKINT:
            case VKSTR:
            case VTRUE:
                pc = Opcodes.NO_JUMP;
                break;
            default:
                pc = jumponcond(fs, e, 0);
                break;
        }
        e.f = codeConcat(fs, e.f, pc);
        patchToHere(fs, e.t);
        e.t = Opcodes.NO_JUMP;
    }

    // lcode.c: goIfFalse
    private static void goIfFalse(FuncState fs, expdesc e) {
        int pc;
        dischargeVars(fs, e);
        switch (e.k) {
            case VJMP:
                pc = e.info;
                break;
            case VNIL:
            case VFALSE:
                pc = Opcodes.NO_JUMP;
                break;
            default:
                pc = jumponcond(fs, e, 1);
                break;
        }
        e.t = codeConcat(fs, e.t, pc);
        patchToHere(fs, e.f);
        e.f = Opcodes.NO_JUMP;
    }

    // lcode.c: codenot
    private static void codenot(FuncState fs, expdesc e) {
        switch (e.k) {
            case VNIL:
            case VFALSE:
                e.k = VTRUE;
                break;
            case VK:
            case VKFLT:
            case VKINT:
            case VKSTR:
            case VTRUE:
                e.k = VFALSE;
                break;
            case VJMP:
                negatecondition(fs, e);
                break;
            case VRELOC:
            case VNONRELOC:
                discharge2anyreg(fs, e);
                freeexp(fs, e);
                e.info = codeABCk(fs, OP_NOT, 0, e.info, 0, 0);
                e.k = VRELOC;
                break;
            default:
                lua_assert(false);
        }
        int temp = e.f;
        e.f = e.t;
        e.t = temp;
        removevalues(fs, e.f);
        removevalues(fs, e.t);
    }

    // lcode.c: isKstr  -  "short literal string"
    private static boolean isKstr(FuncState fs, expdesc e) {
        // lcode.c: ttisshrstring —— K 操作数里的字符串键必须短串（VM 的字段访问走
        // luaH_getshortstr；长串进 K 会在原生 Lua 上执行为 nil/断言崩溃，
        // 破坏 string.dump 互操作——审计 HIGH）
        return (e.k == VK && !hasjumps(e) && e.info <= MAXINDEXRK
                && fs.f.k[e.info] instanceof LuaString
                && fs.f.k[e.info].tt_ == LuaValue.LUA_VSHRSTR);
    }

    // lcode.c: isKint
    private static boolean isKint(expdesc e) {
        return (e.k == VKINT && !hasjumps(e));
    }

    // lcode.c: isCint
    private static boolean isCint(expdesc e) {
        return isKint(e) && 0 <= e.ival && e.ival <= MAXARG_C;
    }

    // lcode.c: isSCint
    private static boolean isSCint(expdesc e) {
        return isKint(e) && fitsC(e.ival);
    }

    // ===========================================================
    // SELF 和索引
    // ===========================================================

    // lcode.c: isSCnumber
    private static long isSCnumber(expdesc e) {
        long i;
        boolean isfloat = false;
        if (e.k == VKINT) {
            i = e.ival;
        } else if (e.k == VKFLT) {
            if (!fltToInteger(e.nval, F2Ieq)) return NO_SCNUMBER;
            i = fltToIntegerOut.get()[0];
            isfloat = true;
        } else {
            return NO_SCNUMBER;
        }
        if (!hasjumps(e) && fitsC(i)) {
            return (int2sC((int) i) & 0xffffffffL) | (isfloat ? SCNUMBER_FLOAT_FLAG : 0L);
        }
        return NO_SCNUMBER;
    }

    // lcode.c: scnumberValue
    private static int scnumberValue(long scinfo) {
        return (int) scinfo;
    }

    // lcode.c: scnumberIsFloat
    private static boolean scnumberIsFloat(long scinfo) {
        return (scinfo & SCNUMBER_FLOAT_FLAG) != 0;
    }

    // ===========================================================
    // 常量折叠
    // ===========================================================

    // lcode.c: luaK_self
    public static void self(FuncState fs, expdesc e, expdesc key) {
        int ereg;
        exp2AnyReg(fs, e);
        ereg = e.info;
        freeexp(fs, e);
        e.info = fs.freereg;
        e.k = VNONRELOC;
        reserveRegs(fs, 2);
        lua_assert(key.k == VKSTR);
        // lcode.c: luaK_self —— 方法名必须短串才可用 OP_SELF 的 K 操作数
        //（同 isKstr 语义；长串走 move+gettable 寄存器路径）
        if (key.strval.tt_ == LuaValue.LUA_VSHRSTR && exp2RK(fs, key)) {
            codeABCk(fs, OP_SELF, e.info, ereg, key.info, 0);
        } else {
            int kreg = exp2AnyReg(fs, key);
            codeABCk(fs, OP_MOVE, e.info + 1, ereg, 0, 0);
            codeABCk(fs, OP_GETTABLE, e.info, ereg, kreg, 0);
        }
        freeexp(fs, key);
    }

    // lcode.c: fillidxk
    private static void fillidxk(expdesc t, int idx, int k) {
        t.ind_idx = idx;
        t.k = k;
        t.ind_ro = false;  // java-only: fillidxk 重置 ind_ro——否则 global <const> * 后 _G.x 被错误拒绝
    }

    // lcode.c: indexed
    public static void indexed(FuncState fs, expdesc t, expdesc k) {
        // 先把VKSTR键转为VK, 好让isKstr()能识别
        int keystr = -1;  // k[]中字符串索引 (供check_readonly用)
        if (k.k == VKSTR) {
            keystr = str2K(fs, k);
        }
        lua_assert(!hasjumps(t) && (t.k == VLOCAL || t.k == VVARGVAR || t.k == VNONRELOC || t.k == VUPVAL));
        if (t.k == VUPVAL && !isKstr(fs, k))
            exp2AnyReg(fs, t);
        if (t.k == VUPVAL) {
            int temp = t.info;
            t.ind_t = temp;
            lua_assert(isKstr(fs, k));
            fillidxk(t, k.info, VINDEXUP);
        } else if (t.k == VVARGVAR) {
            int kreg = exp2AnyReg(fs, k);
            int vreg = t.var_ridx;
            t.ind_t = vreg;
            fillidxk(t, kreg, VVARGIND);
        } else {
            // lcode.c: 不直接赋值（C 中 union 成员可能重叠）
            int temp = (t.k == VLOCAL) ? t.var_ridx : t.info;
            t.ind_t = temp;
            if (isKstr(fs, k))
                fillidxk(t, k.info, VINDEXSTR);
            else if (isCint(k))
                fillidxk(t, (int) k.ival, VINDEXI);
            else
                fillidxk(t, exp2AnyReg(fs, k), VINDEXED);
        }
        t.ind_keystr = keystr;
    }

    // lcode.c: validop
    private static boolean validop(int op, expdesc v1, expdesc v2) {
        switch (op) {
            case LUA_OPBAND:
            case LUA_OPBOR:
            case LUA_OPBXOR:
            case LUA_OPSHL:
            case LUA_OPSHR:
            case LUA_OPBNOT:
                return canToIntegerNS(v1) && canToIntegerNS(v2);
            case LUA_OPDIV:
            case LUA_OPIDIV:
            case LUA_OPMOD:
                return nvalue(v2) != 0;
            default:
                return true;
        }
    }

    // lcode.c: constfolding
    private static boolean constfolding(FuncState fs, int op, expdesc e1, expdesc e2) {

        // java-only: 惰性初始化 foldV1/foldV2/foldRes
        if (fs.foldV1 == null) {
            fs.foldV1 = new expdesc();
            fs.foldV2 = new expdesc();
            fs.foldRes = new expdesc();
        }
        expdesc v1 = fs.foldV1;
        expdesc v2 = fs.foldV2;
        expdesc res = fs.foldRes;
        if (!tonumeral(e1, v1) || !tonumeral(e2, v2) || !validop(op, v1, v2))
            return false;
        if (!rawarith(fs, op, v1, v2, res))
            return false;
        if (res.k == VKINT) {
            setIValue(e1, res.ival);
            return true;
        }
        return setFoldedFloat(e1, res.nval);
    }

    // lcode.c: setFoldedFloat
    private static boolean setFoldedFloat(expdesc e, double n) {
        // constfolding不折叠NaN或0.0 (避开-0.0问题)
        if (Double.isNaN(n) || n == 0)
            return false;
        setFltValue(e, n);
        return true;
    }

    // lcode.c: nvalue
    private static double nvalue(expdesc v) {
        return v.k == VKINT ? (double) v.ival : v.nval;
    }

    // lcode.c: canToIntegerNS
    private static boolean canToIntegerNS(expdesc v) {
        if (v.k == VKINT) return true;
        double n = v.nval;
        return LuaArith.canToIntegerNS(n);
    }

    // ===========================================================
    // 运算符转换
    // ===========================================================

    // lcode.c: toIntegerNS
    private static long toIntegerNS(expdesc v) {
        return v.k == VKINT ? v.ival : (long) v.nval;
    }

    // lobject.c: luaO_rawarith
    private static boolean rawarith(FuncState fs, int op, expdesc p1, expdesc p2, expdesc res) {

        switch (op) {
            case LUA_OPBAND:
            case LUA_OPBOR:
            case LUA_OPBXOR:
            case LUA_OPSHL:
            case LUA_OPSHR:
            case LUA_OPBNOT:
                if (canToIntegerNS(p1) && canToIntegerNS(p2)) {
                    setIValue(res, LuaArith.rawIntArith(op, toIntegerNS(p1), toIntegerNS(p2)));
                    return true;
                }
                return false;
            case LUA_OPDIV:
            case LUA_OPPOW:
                setFltValue(res, LuaArith.rawNumArith(op, nvalue(p1), nvalue(p2)));
                return true;
            default:
                if (p1.k == VKINT && p2.k == VKINT)
                    setIValue(res, LuaArith.rawIntArith(op, p1.ival, p2.ival));
                else
                    setFltValue(res, LuaArith.rawNumArith(op, nvalue(p1), nvalue(p2)));
                return true;
        }
    }

    // lcode.c: foldbinop
    private static boolean foldbinop(int op) {
        // 仅允许OPR_ADD..OPR_SHR; 比较运算必须走codeeq/codeorder
        return OPR_ADD <= op && op <= OPR_SHR;
    }

    // ===========================================================
    // 一元/二元代码生成
    // ===========================================================

    // lcode.c: binopr2op
    private static int binopr2op(int opr, int baser, int base) {
        return (opr - baser) + base;
    }

    // lcode.c: unopr2op
    private static int unopr2op(int opr) {
        return (opr - OPR_MINUS) + OP_UNM;
    }

    // lcode.c: binopr2TM
    private static int binopr2TM(int opr) {
        // 仅对OPR_ADD..OPR_SHR有效
        lua_assert(OPR_ADD <= opr && opr <= OPR_SHR);
        return (opr - OPR_ADD) + TM_ADD;
    }

    // lcode.c: codeunexpval
    private static void codeunexpval(FuncState fs, int op, expdesc e, int line) {
        int r = exp2AnyReg(fs, e);
        freeexp(fs, e);
        e.info = codeABCk(fs, op, 0, r, 0, 0);
        e.k = VRELOC;
        fixline(fs, line);
    }

    // lcode.c: finishbinexpval
    private static void finishbinexpval(FuncState fs, expdesc e1, expdesc e2,
                                        int op, int v2, int flip, int line,
                                        int mmop, int event) {
        int v1 = exp2AnyReg(fs, e1);
        int pc = codeABCk(fs, op, 0, v1, v2, 0);
        freeexps(fs, e1, e2);
        e1.info = pc;
        e1.k = VRELOC;
        fixline(fs, line);
        codeABCk(fs, mmop, v1, v2, event, flip);
        fixline(fs, line);
    }

    // lcode.c: codebinexpval
    private static void codebinexpval(FuncState fs, int opr,
                                      expdesc e1, expdesc e2, int line) {
        int op = binopr2op(opr, OPR_ADD, OP_ADD);
        int v2 = exp2AnyReg(fs, e2);
        finishbinexpval(fs, e1, e2, op, v2, 0, line, OP_MMBIN, binopr2TM(opr));
    }

    // lcode.c: codebini
    private static void codebini(FuncState fs, int op,
                                 expdesc e1, expdesc e2, int flip, int line, int event) {
        int v2 = int2sC((int) e2.ival);
        finishbinexpval(fs, e1, e2, op, v2, flip, line, OP_MMBINI, event);
    }

    // lcode.c: codebinK
    private static void codebinK(FuncState fs, int opr,
                                 expdesc e1, expdesc e2, int flip, int line) {
        int event = binopr2TM(opr);
        int v2 = e2.info;
        int op = binopr2op(opr, OPR_ADD, OP_ADDK);
        finishbinexpval(fs, e1, e2, op, v2, flip, line, OP_MMBINK, event);
    }

    // ===========================================================
    // 运算符分类
    // ===========================================================

    // lcode.c: finishbinexpneg
    private static boolean finishbinexpneg(FuncState fs, expdesc e1, expdesc e2,
                                           int op, int line, int event) {
        if (!isKint(e2))
            return false;
        long i2 = e2.ival;
        if (!(fitsC(i2) && fitsC(-i2)))
            return false;
        int v2 = (int) i2;
        finishbinexpval(fs, e1, e2, op, int2sC(-v2), 0, line, OP_MMBINI, event);
        // java-only: SETARG_B把原始b还给metamethod (a-b编码为a+(-b))
        int idx = fs.pc - 1;
        fs.f.code[idx] = SETARG_B(fs.f.code[idx], int2sC(v2));
        return true;
    }

    // lcode.c: swapexps
    private static void swapexps(expdesc e1, expdesc e2) {
        // java-only: C复制栈上结构体; Java逐字段交换, 免分配临时对象
        int k = e1.k;
        long ival = e1.ival;
        double nval = e1.nval;
        LuaString strval = e1.strval;
        int info = e1.info;
        int ind_t = e1.ind_t;
        int ind_idx = e1.ind_idx;
        boolean ind_ro = e1.ind_ro;
        int ind_keystr = e1.ind_keystr;
        int var_ridx = e1.var_ridx;
        int var_vidx = e1.var_vidx;
        int t = e1.t;
        int f = e1.f;

        e1.k = e2.k;
        e1.ival = e2.ival;
        e1.nval = e2.nval;
        e1.strval = e2.strval;
        e1.info = e2.info;
        e1.ind_t = e2.ind_t;
        e1.ind_idx = e2.ind_idx;
        e1.ind_ro = e2.ind_ro;
        e1.ind_keystr = e2.ind_keystr;
        e1.var_ridx = e2.var_ridx;
        e1.var_vidx = e2.var_vidx;
        e1.t = e2.t;
        e1.f = e2.f;

        e2.k = k;
        e2.ival = ival;
        e2.nval = nval;
        e2.strval = strval;
        e2.info = info;
        e2.ind_t = ind_t;
        e2.ind_idx = ind_idx;
        e2.ind_ro = ind_ro;
        e2.ind_keystr = ind_keystr;
        e2.var_ridx = var_ridx;
        e2.var_vidx = var_vidx;
        e2.t = t;
        e2.f = f;
    }

    // lcode.c: codebinNoK
    private static void codebinNoK(FuncState fs, int opr,
                                   expdesc e1, expdesc e2, int flip, int line) {
        if (flip != 0) swapexps(e1, e2);
        codebinexpval(fs, opr, e1, e2, line);
    }

    // lcode.c: codearith
    private static void codearith(FuncState fs, int opr,
                                  expdesc e1, expdesc e2, int flip, int line) {
        if (tonumeral(e2) && exp2K(fs, e2))
            codebinK(fs, opr, e1, e2, flip, line);
        else
            codebinNoK(fs, opr, e1, e2, flip, line);
    }

    // lcode.c: codecommutative
    private static void codecommutative(FuncState fs, int op, expdesc e1, expdesc e2, int line) {
        int flip = 0;
        if (tonumeral(e1)) {
            swapexps(e1, e2);
            flip = 1;
        }
        if (op == OPR_ADD && isSCint(e2))
            codebini(fs, OP_ADDI, e1, e2, flip, line, TM_ADD);
        else
            codearith(fs, op, e1, e2, flip, line);
    }

    // ===========================================================
    // 前缀/中缀/后缀
    // ===========================================================

    // lcode.c: codebitwise
    private static void codebitwise(FuncState fs, int opr,
                                    expdesc e1, expdesc e2, int line) {
        int flip = 0;
        if (e1.k == VKINT) {
            swapexps(e1, e2);
            flip = 1;
        }
        if (e2.k == VKINT && exp2K(fs, e2))
            codebinK(fs, opr, e1, e2, flip, line);
        else
            codebinNoK(fs, opr, e1, e2, flip, line);
    }

    // lcode.c: codeorder
    private static void codeorder(FuncState fs, int opr, expdesc e1, expdesc e2) {
        int r1, r2;
        int op;
        long scinfo = isSCnumber(e2);
        boolean isfloat = false;
        if (scinfo != NO_SCNUMBER) {
            r1 = exp2AnyReg(fs, e1);
            r2 = scnumberValue(scinfo);
            isfloat = scnumberIsFloat(scinfo);
            op = binopr2op(opr, OPR_LT, OP_LTI);
        } else if ((scinfo = isSCnumber(e1)) != NO_SCNUMBER) {
            r1 = exp2AnyReg(fs, e2);
            r2 = scnumberValue(scinfo);
            isfloat = scnumberIsFloat(scinfo);
            op = binopr2op(opr, OPR_LT, OP_GTI);
        } else {
            r1 = exp2AnyReg(fs, e1);
            r2 = exp2AnyReg(fs, e2);
            op = binopr2op(opr, OPR_LT, OP_LT);
        }
        freeexps(fs, e1, e2);
        e1.info = condjump(fs, op, r1, r2, isfloat ? 1 : 0, 1);
        e1.k = VJMP;
    }

    // lcode.c: codeeq
    private static void codeeq(FuncState fs, int opr, expdesc e1, expdesc e2) {
        int r1, r2;
        int op;
        if (e1.k != VNONRELOC) {
            lua_assert(e1.k == VK || e1.k == VKINT || e1.k == VKFLT);
            swapexps(e1, e2);
        }
        r1 = exp2AnyReg(fs, e1);
        long scinfo = isSCnumber(e2);
        boolean isfloat = false;
        if (scinfo != NO_SCNUMBER) {
            op = OP_EQI;
            r2 = scnumberValue(scinfo);
            isfloat = scnumberIsFloat(scinfo);
        } else if (exp2RK(fs, e2)) {
            op = OP_EQK;
            r2 = e2.info;
        } else {
            op = OP_EQ;
            r2 = exp2AnyReg(fs, e2);
        }
        freeexps(fs, e1, e2);
        e1.info = condjump(fs, op, r1, r2, isfloat ? 1 : 0, (opr == OPR_EQ) ? 1 : 0);
        e1.k = VJMP;
    }

    // lcode.c: luaK_prefix
    public static void prefix(FuncState fs, int opr, expdesc e, int line) {
        dischargeVars(fs, e);
        switch (opr) {
            case OPR_MINUS:
            case OPR_BNOT: {
                if (constfolding(fs, opr + LUA_OPUNM, e, PREFIX_ZERO))
                    break;
            }
            case OPR_LEN:
                codeunexpval(fs, unopr2op(opr), e, line);
                break;
            case OPR_NOT:
                codenot(fs, e);
                break;
            default:
                lua_assert(false);
        }
    }

    // ===========================================================
    // 完成和工具
    // ===========================================================

    // lcode.c: luaK_infix
    public static void infix(FuncState fs, int op, expdesc v) {
        dischargeVars(fs, v);
        switch (op) {
            case OPR_AND:
                goIfTrue(fs, v);
                break;
            case OPR_OR:
                goIfFalse(fs, v);
                break;
            case OPR_CONCAT:
                exp2NextReg(fs, v);
                break;
            case OPR_ADD:
            case OPR_SUB:
            case OPR_MUL:
            case OPR_DIV:
            case OPR_IDIV:
            case OPR_MOD:
            case OPR_POW:
            case OPR_BAND:
            case OPR_BOR:
            case OPR_BXOR:
            case OPR_SHL:
            case OPR_SHR:
                if (!tonumeral(v))
                    exp2AnyReg(fs, v);
                break;
            case OPR_EQ:
            case OPR_NE:
                if (!tonumeral(v))
                    exp2RK(fs, v);
                break;
            case OPR_LT:
            case OPR_LE:
            case OPR_GT:
            case OPR_GE:
                if (isSCnumber(v) == NO_SCNUMBER)
                    exp2AnyReg(fs, v);
                break;
            default:
                lua_assert(false);
        }
    }

    // lcode.c: codeconcat
    private static void codeconcat(FuncState fs, expdesc e1, expdesc e2, int line) {
        int idx = fs.pc - 1;
        if (fs.pc > fs.lasttarget) {
            int ie2 = fs.f.code[idx];
            if (GET_OPCODE(ie2) == OP_CONCAT) {
                int n = GETARG_B(ie2);
                lua_assert(e1.info + 1 == GETARG_A(ie2));
                freeexp(fs, e2);
                ie2 = SETARG_A(ie2, e1.info);
                ie2 = SETARG_B(ie2, n + 1);
                fs.f.code[idx] = ie2;
                return;
            }
        }
        codeABCk(fs, OP_CONCAT, e1.info, 2, 0, 0);
        freeexp(fs, e2);
        fixline(fs, line);
    }

    // lcode.c: luaK_posfix
    public static void posfix(FuncState fs, int opr, expdesc e1, expdesc e2, int line) {
        dischargeVars(fs, e2);
        if (foldbinop(opr) && constfolding(fs, opr + LUA_OPADD, e1, e2))
            return;
        switch (opr) {
            case OPR_AND: {
                lua_assert(e1.t == Opcodes.NO_JUMP);
                e2.f = codeConcat(fs, e2.f, e1.f);
                e1.copyFrom(e2);
                break;
            }
            case OPR_OR: {
                lua_assert(e1.f == Opcodes.NO_JUMP);
                e2.t = codeConcat(fs, e2.t, e1.t);
                e1.copyFrom(e2);
                break;
            }
            case OPR_CONCAT: {
                exp2NextReg(fs, e2);
                codeconcat(fs, e1, e2, line);
                break;
            }
            case OPR_ADD:
            case OPR_MUL: {
                codecommutative(fs, opr, e1, e2, line);
                break;
            }
            case OPR_SUB: {
                if (finishbinexpneg(fs, e1, e2, OP_ADDI, line, TM_SUB))
                    break;
            }
            case OPR_DIV:
            case OPR_IDIV:
            case OPR_MOD:
            case OPR_POW: {
                codearith(fs, opr, e1, e2, 0, line);
                break;
            }
            case OPR_BAND:
            case OPR_BOR:
            case OPR_BXOR: {
                codebitwise(fs, opr, e1, e2, line);
                break;
            }
            case OPR_SHL: {
                if (isSCint(e1)) {
                    swapexps(e1, e2);
                    codebini(fs, OP_SHLI, e1, e2, 1, line, TM_SHL);
                } else if (finishbinexpneg(fs, e1, e2, OP_SHRI, line, TM_SHL)) {
                } else {
                    codebinexpval(fs, opr, e1, e2, line);
                }
                break;
            }
            case OPR_SHR: {
                if (isSCint(e2))
                    codebini(fs, OP_SHRI, e1, e2, 0, line, TM_SHR);
                else
                    codebinexpval(fs, opr, e1, e2, line);
                break;
            }
            case OPR_EQ:
            case OPR_NE: {
                codeeq(fs, opr, e1, e2);
                break;
            }
            case OPR_GT:
            case OPR_GE: {
                swapexps(e1, e2);
                opr = (opr - OPR_GT) + OPR_LT;
            }
            case OPR_LT:
            case OPR_LE: {
                codeorder(fs, opr, e1, e2);
                break;
            }
            default:
                lua_assert(false);
        }
    }

    // lcode.c: luaK_fixline
    public static void fixline(FuncState fs, int line) {
        removelastlineinfo(fs);
        savelineinfo(fs, fs.f, line);
    }

    // lcode.c: luaK_settablesize
    public static void setTableSize(FuncState fs, int pc, int ra, int asize, int hsize) {
        int inst = fs.f.code[pc];
        int extra = asize / (MAXARG_vC + 1);
        int rc = asize % (MAXARG_vC + 1);
        int k = (extra > 0) ? 1 : 0;
        hsize = (hsize != 0) ? ceilLog2(hsize) + 1 : 0;
        fs.f.code[pc] = CREATE_vABCk(OP_NEWTABLE, ra, hsize, rc, k);
        fs.f.code[pc + 1] = CREATE_Ax(OP_EXTRAARG, extra);
    }

    // lobject.c: luaO_ceillog2
    private static int ceilLog2(int x) {
        int l = 0;
        x--;
        while (x > 0) {
            l++;
            x >>= 1;
        }
        return l;
    }

    // lcode.c: luaK_setlist
    public static void setList(FuncState fs, int base, int nelems, int tostore) {
        lua_assert(tostore != 0);
        if (tostore == -1) tostore = 0;
        if (nelems <= MAXARG_vC)
            codevABCk(fs, OP_SETLIST, base, tostore, nelems, 0);
        else {
            int extra = nelems / (MAXARG_vC + 1);
            nelems %= (MAXARG_vC + 1);
            codevABCk(fs, OP_SETLIST, base, tostore, nelems, 1);
            codeextraarg(fs, extra);
        }
        fs.freereg = base + 1;
    }

    // ===========================================================
    // 工具方法
    // ===========================================================

    // lcode.c: finaltarget
    private static int finaltarget(int[] code, int i) {
        int count;
        for (count = 0; count < 100; count++) {
            int pc = code[i];
            if (GET_OPCODE(pc) != OP_JMP)
                break;
            else
                i += Opcodes.GETARG_sJ(pc) + 1;
        }
        return i;
    }

    // lcode.c: luaK_finish
    public static void finish(FuncState fs) {
        Prototype p = fs.f;
        if ((p.flag & Prototype.PF_VATAB) != 0)
            p.flag &= (byte) ~Prototype.PF_VAHID;
        for (int i = 0; i < fs.pc; i++) {
            int pc = p.code[i];
            switch (GET_OPCODE(pc)) {
                case OP_RETURN0:
                case OP_RETURN1:
                    if (!(fs.needclose != 0 || (p.flag & Prototype.PF_VAHID) != 0))
                        break;
                    p.code[i] = SET_OPCODE(pc, OP_RETURN);
                case OP_RETURN:
                case OP_TAILCALL:
                    if (fs.needclose != 0)
                        p.code[i] = SETARG_k(p.code[i], 1);
                    if ((p.flag & Prototype.PF_VAHID) != 0)
                        p.code[i] = SETARG_C(p.code[i], p.numparams + 1);
                    break;
                case OP_GETVARG:
                    if ((p.flag & Prototype.PF_VATAB) != 0)
                        p.code[i] = SET_OPCODE(pc, OP_GETTABLE);
                    break;
                case OP_VARARG:
                    if ((p.flag & Prototype.PF_VATAB) != 0)
                        p.code[i] = SETARG_k(p.code[i], 1);
                    break;
                case OP_JMP:
                    int target = finaltarget(p.code, i);
                    fixjump(fs, i, target);
                    break;
                default:
                    break;
            }
        }
        // java-only: 裁剪数组到实际大小 (保留分配容量会让VM执行垃圾MOVE)
        if (p.code.length > fs.pc) {
            p.code = Arrays.copyOf(p.code, fs.pc);
        }
        p.sizecode = fs.pc;
        if (p.lineinfo.length > fs.pc) {
            p.lineinfo = Arrays.copyOf(p.lineinfo, fs.pc);
        }
        p.sizelineinfo = fs.pc;
        int absLineSize = fs.nabslineinfo * 2;
        if (p.abslineinfo.length > absLineSize) {
            p.abslineinfo = Arrays.copyOf(p.abslineinfo, absLineSize);
        }
        p.sizeabslineinfo = fs.nabslineinfo;
        if (p.k.length > fs.nk) {
            p.k = Arrays.copyOf(p.k, fs.nk);
        }
        p.sizek = fs.nk;
        // close_func luaM_shrinkvector
        if (p.p.length > fs.np) {
            p.p = Arrays.copyOf(p.p, fs.np);
        }
        p.sizep = fs.np;
    }

    // lparser.c: luaY_nvarstack
    static int nvarstack(FuncState fs) {
        // java-only: nvarstack必须跳过非寄存器变量 (kind >= GDKREG的全局声明)
        int level = 0;
        for (int i = fs.nactvar - 1; i >= 0; i--) {
            int idx = fs.firstlocal + i;
            if (idx < 0 || fs.ls.dyd == null || idx >= fs.ls.dyd.actvar().size()) break;
            Vardesc vd = fs.ls.dyd.actvar().get(idx);
            if (SyntaxNodes.varinreg(vd) && vd.ridx >= level) level = vd.ridx + 1;
        }
        return level;
    }

    // java-only
    private static void lua_assert(boolean cond) {
        if (!cond) throw new AssertionError("codegen assertion");
    }

    // lcode.c: luaK_semerror
    public static void semError(Lexer ls, String msg) {
        throw ls.semError(msg);
    }


    // ===========================================================
    // Parser 使用的公共编码辅助方法
    // ===========================================================

    // lcode.c: luaK_codeABC
    public static int codeABC(FuncState fs, int op, int a, int b, int c, int k) {
        return codeABCk(fs, op, a, b, c, k);
    }

    // lcode.c: codeABCk
    public static int codeABC(FuncState fs, int op, int a, int b, int c) {
        return codeABCk(fs, op, a, b, c, 0);
    }

}
