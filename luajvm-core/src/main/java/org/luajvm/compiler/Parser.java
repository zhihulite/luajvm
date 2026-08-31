// ref: lparser.c
// diff: CodeGen委托代码生成; ls.fs/ls.dyd共享引用; ArrayList+Arrays.copyOf代替luaM_growvector; markupval用传入fs（this.fs 此刻已指向嵌套函数）; Vardesc.ridx/var_vidx用int（lu_byte 溢出）; Lua5.5 global声明; 递归深度保护
package org.luajvm.compiler;

import static org.luajvm.compiler.SyntaxNodes.BlockCnt;
import static org.luajvm.compiler.SyntaxNodes.FuncState;
import static org.luajvm.compiler.SyntaxNodes.GDKCONST;
import static org.luajvm.compiler.SyntaxNodes.GDKREG;
import static org.luajvm.compiler.SyntaxNodes.Labeldesc;
import static org.luajvm.compiler.SyntaxNodes.Labellist;
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
import static org.luajvm.compiler.SyntaxNodes.OPR_NOBINOPR;
import static org.luajvm.compiler.SyntaxNodes.OPR_NOT;
import static org.luajvm.compiler.SyntaxNodes.OPR_NOUNOPR;
import static org.luajvm.compiler.SyntaxNodes.OPR_OR;
import static org.luajvm.compiler.SyntaxNodes.OPR_POW;
import static org.luajvm.compiler.SyntaxNodes.OPR_SHL;
import static org.luajvm.compiler.SyntaxNodes.OPR_SHR;
import static org.luajvm.compiler.SyntaxNodes.OPR_SUB;
import static org.luajvm.compiler.SyntaxNodes.RDKCONST;
import static org.luajvm.compiler.SyntaxNodes.RDKCTC;
import static org.luajvm.compiler.SyntaxNodes.RDKTOCLOSE;
import static org.luajvm.compiler.SyntaxNodes.RDKVAVAR;
import static org.luajvm.compiler.SyntaxNodes.VCALL;
import static org.luajvm.compiler.SyntaxNodes.VCONST;
import static org.luajvm.compiler.SyntaxNodes.VDKREG;
import static org.luajvm.compiler.SyntaxNodes.VFALSE;
import static org.luajvm.compiler.SyntaxNodes.VGLOBAL;
import static org.luajvm.compiler.SyntaxNodes.VINDEXED;
import static org.luajvm.compiler.SyntaxNodes.VINDEXI;
import static org.luajvm.compiler.SyntaxNodes.VINDEXSTR;
import static org.luajvm.compiler.SyntaxNodes.VINDEXUP;
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
import static org.luajvm.compiler.SyntaxNodes.VVOID;
import static org.luajvm.compiler.SyntaxNodes.Vardesc;
import static org.luajvm.compiler.SyntaxNodes.expdesc;

import org.luajvm.core.LuaString;
import org.luajvm.core.LuaValue;
import org.luajvm.core.Prototype;

import java.io.Reader;
import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.concurrent.atomic.AtomicLong;

public final class Parser {
    static final int[][] PRIORITY = {
            {10, 10}, {10, 10}, // + -
            {11, 11}, {11, 11}, // * %
            {14, 13},           // ^ (right assoc)
            {11, 11}, {11, 11}, // / //
            {6, 6}, {4, 4}, {5, 5}, // & | ~
            {7, 7}, {7, 7},     // << >>
            {9, 8},              // .. (right assoc)
            {3, 3}, {3, 3}, {3, 3}, // == < <=
            {3, 3}, {3, 3}, {3, 3}, // ~= > >=
            {2, 2}, {1, 1}      // and or
    };
    static final int UNARY_PRIORITY = 12;
    // java-only: FuncState ThreadLocal 自由列表池，对齐 C 的栈分配零堆开销：
    //   嵌套函数经 prev 指针成栈，close_func 后归还，池上限 16；
    //   不参与 luaM 记账（仅 Prototype 记账）
    // 临时插桩：统计真实负载里的编译次数与累计耗时（验证预编译的覆盖率上界）
    public static final boolean COUNT_COMPILE = Boolean.getBoolean("luajvm.countcompile");
    public static final AtomicLong COMPILE_CALLS =
            new AtomicLong();
    public static final AtomicLong COMPILE_NANOS =
            new AtomicLong();
    public static final AtomicLong COMPILE_BYTES =
            new AtomicLong();

    private static final ThreadLocal<ArrayDeque<FuncState>> FS_POOL =
            ThreadLocal.withInitial(ArrayDeque::new);
    private static final int FS_POOL_MAX = 16;
    // java-only: A/B 开关 - 复用 Lexer.POOL_ENABLED，-Dluajvm.poollex=false 禁用
    private static final boolean POOL_ENABLED = Lexer.POOL_ENABLED;
    private final Lexer ls;

    private Parser(Reader r, String src) {
        this(r, src, Lexer.COMPAT_GLOBAL);
    }

    // lparser.c: luaY_parser
    private Parser(Reader r, String src, boolean compatGlobal) {
        // java-only: 从 ThreadLocal 池获取 Lexer，对齐 C 的 LexState 栈分配语义
        this.ls = Lexer.obtain(r, src, compatGlobal);
    }

    // java-only: 直接字节源构造器
    private Parser(byte[] data, int offset, int length, boolean pendingNl, String src, boolean compatGlobal) {
        // java-only: 从 ThreadLocal 池获取 Lexer，对齐 C 的 LexState 栈分配语义
        this.ls = Lexer.obtain(data, offset, length, pendingNl, src, compatGlobal);
    }

    // java-only: 从池获取 FuncState。若池为空则 new。
    private static FuncState obtainFuncState() {
        if (POOL_ENABLED) {
            FuncState fs = FS_POOL.get().pollFirst();
            if (fs != null) return fs;
        }
        return new FuncState();
    }

    // java-only: 归还 FuncState 到池。reset 清除所有字段（含 KCache HashMap），
    //   Prototype 引用 (f) 被清除 - Prototype 由调用方在归还前保存到父 Proto.p。
    private static void releaseFuncState(FuncState fs) {
        fs.reset();
        if (POOL_ENABLED) {
            ArrayDeque<FuncState> pool = FS_POOL.get();
            if (pool.size() < FS_POOL_MAX) {
                pool.addFirst(fs);
            }
        }
    }

    public static Prototype parse(Reader r, String src) {
        return parse(r, src, Lexer.COMPAT_GLOBAL);
    }

    // ===============================================================
    // 错误处理与 token 工具 (C: lparser.c)
    // ===============================================================

    // lparser.c: luaY_parser
    public static Prototype parse(Reader r, String src, boolean compatGlobal) {
        long __t0 = COUNT_COMPILE ? System.nanoTime() : 0L;
        try {
        Parser p = new Parser(r, src, compatGlobal);
        // java-only: 从 ThreadLocal 池获取 mainFs，对齐 C 的 FuncState 栈分配语义
        FuncState mainFs = obtainFuncState();
        mainFs.f = new Prototype();
        try {
            p.mainfunc(p.ls, mainFs);
            return mainFs.f;
        } finally {
            // java-only: 归还 FuncState + Lexer 到池，对齐 C 的栈释放。
            //   mainFs.f (Prototype) 已被 mainfunc 完成并由 return 捕获引用，
            //   reset() 清除 fs.f 字段不影响已返回的 Prototype 对象。
            releaseFuncState(mainFs);
            p.ls.release();
        }
        } finally {
            if (COUNT_COMPILE) {
                COMPILE_CALLS.incrementAndGet();
                COMPILE_NANOS.addAndGet(System.nanoTime() - __t0);
            }
        }
    }

    // java-only: 直接字节源 parse 重载 - 跳过 ByteChunkReader 分配 + 虚方法 dispatch
    public static Prototype parse(byte[] data, int offset, int length, boolean pendingNl, String src, boolean compatGlobal) {
        long __t0 = COUNT_COMPILE ? System.nanoTime() : 0L;
        if (COUNT_COMPILE) COMPILE_BYTES.addAndGet(length);
        try {
        Parser p = new Parser(data, offset, length, pendingNl, src, compatGlobal);
        // java-only: 从 ThreadLocal 池获取 mainFs，对齐 C 的 FuncState 栈分配语义
        FuncState mainFs = obtainFuncState();
        mainFs.f = new Prototype();
        try {
            p.mainfunc(p.ls, mainFs);
            return mainFs.f;
        } finally {
            // java-only: 归还 FuncState + Lexer 到池，对齐 C 的栈释放。
            //   mainFs.f (Prototype) 已被 mainfunc 完成并由 return 捕获引用，
            //   reset() 清除 fs.f 字段不影响已返回的 Prototype 对象。
            releaseFuncState(mainFs);
            p.ls.release();
        }
        } finally {
            if (COUNT_COMPILE) {
                COMPILE_CALLS.incrementAndGet();
                COMPILE_NANOS.addAndGet(System.nanoTime() - __t0);
            }
        }
    }

    // lparser.c: errorlimit
    private static void errorlimit(FuncState fs, int limit, String what) {
        String where = fs.f.linedefined == 0 ? "main function" : "function at line " + fs.f.linedefined;
        throw fs.ls.syntaxError("too many " + what + " (limit is " + limit + ") in " + where);
    }

    // lparser.c: luaY_checklimit
    static void checkLimit(FuncState fs, int v, int l, String what) {
        if (v > l) errorlimit(fs, l, what);
    }

    // lparser.c: hasmultret
    private static boolean hasmultret(int k) {
        return k == VCALL || k == VVARARG;
    }

    // lparser.c: vkisvar
    private static boolean vkisvar(int k) {
        return k >= VLOCAL && k <= VINDEXSTR;
    }

    private static boolean vkisindexed(int k) {
        return k >= VINDEXED && k <= VINDEXSTR;
    }

    // lparser.c: error_expected
    private void errorExpected(Lexer ls, int token) {
        throw ls.syntaxError(Lexer.tokenName(token) + " expected");
    }

    // ===============================================================
    // 表达式描述辅助 (C: lparser.c)
    // ===============================================================

    // lparser.c: testnext
    private int testnext(Lexer ls, int c) {
        if (ls.t.token == c) {
            ls.next();
            return 1;
        }
        return 0;
    }

    // lparser.c: check
    private void check(Lexer ls, int c) {
        if (ls.t.token != c) errorExpected(ls, c);
    }

    // ===============================================================
    // 变量 / 作用域管理 (lparser.c)
    // ===============================================================

    // lparser.c: checknext
    private void checknext(Lexer ls, int c) {
        check(ls, c);
        ls.next();
    }

    // lparser.c: check_match
    private void checkMatch(Lexer ls, int what, int who, int where) {
        if (testnext(ls, what) == 0) {
            if (where == ls.linenumber())
                errorExpected(ls, what);
            else
                throw ls.syntaxError(Lexer.tokenName(what) + " expected (to close " + Lexer.tokenName(who) + " at line " + where + ")");
        }
    }

    // lparser.c: str_checkname
    private LuaString strCheckname(Lexer ls) {
        check(ls, Lexer.TK_NAME);
        LuaString ts = ls.t.ts;
        ls.next();
        return ts;
    }

    // lparser.c: init_exp
    private void initExp(expdesc e, int k, int i) {
        e.t = Opcodes.NO_JUMP;
        e.f = Opcodes.NO_JUMP;
        e.k = k;
        e.info = i;
    }

    // lparser.c: codestring
    private void codestring(expdesc e, LuaString s) {
        e.t = Opcodes.NO_JUMP;
        e.f = Opcodes.NO_JUMP;
        e.k = VKSTR;
        e.strval = s;
    }

    // lparser.c: new_varkind
    private int newVarkind(Lexer ls, LuaString name, int kind) {
        FuncState fs = ls.fs;

        Vardesc vd = new Vardesc();
        vd.kind = kind;
        vd.name = name;
        vd.pidx = -1;
        int insertAt = ls.dyd.actvarN;
        if (insertAt < ls.dyd.actvar().size()) {
            ls.dyd.actvar().set(insertAt, vd);  // 覆盖过期条目
        } else {
            ls.dyd.actvar().add(vd);
        }
        ls.dyd.actvarN++;
        return ls.dyd.actvarN - 1 - fs.firstlocal;
    }

    // lparser.c: new_localvar
    private int newLocalvar(Lexer ls, LuaString name) {

        return newVarkind(ls, name, VDKREG);
    }

    // lparser.c: getlocalvardesc
    // java-only: 边界安全检查（C不做）
    private Vardesc getlocalvardesc(FuncState fs, int vidx) {
        int idx = fs.firstlocal + vidx;
        if (idx < 0 || idx >= ls.dyd.actvar().size()) {
            Vardesc dummy = new Vardesc();
            dummy.kind = VDKREG;
            dummy.name = LuaString.newStr("?");
            dummy.pidx = -1;  // 无调试信息
            return dummy;
        }
        return ls.dyd.actvar().get(idx);
    }

    // lparser.c: registerlocalvar
    private int registerlocalvar(Lexer ls, FuncState fs, LuaString name) {
        Prototype f = fs.f;
        int oldsize = f.locvars.length;
        if (fs.ndebugvars >= oldsize) {
            f.locvars = Arrays.copyOf(f.locvars, Math.max(oldsize * 2, 4));
        }
        Prototype.LocVar lv = new Prototype.LocVar();
        lv.varname = name;
        lv.startpc = fs.pc;
        f.locvars[fs.ndebugvars] = lv;
        return fs.ndebugvars++;
    }

    // lparser.c: localdebuginfo
    private Prototype.LocVar localdebuginfo(FuncState fs, int vidx) {
        Vardesc vd = getlocalvardesc(fs, vidx);
        if (vd.pidx < 0 || vd.pidx >= fs.f.locvars.length) return null;
        Prototype.LocVar lv = fs.f.locvars[vd.pidx];
        if (lv == null || lv.startpc < 0) return null;
        return lv;
    }

    // lparser.c: adjustlocalvars
    private void adjustlocalvars(Lexer ls, int nvars) {

        FuncState fs = ls.fs;
        int reglevel = nVarStack(fs);
        for (int i = 0; i < nvars; i++) {
            int vidx = fs.nactvar++;
            Vardesc var = getlocalvardesc(fs, vidx);
            if (SyntaxNodes.varinreg(var)) {
                var.ridx = reglevel++;
                var.pidx = registerlocalvar(ls, fs, var.name);
                fs.f.locvars[var.pidx].ridx = var.ridx;
                checkLimit(fs, reglevel, 200, "local variables");
            } else {
                var.ridx = -1;
                var.pidx = -1;
            }
        }
    }

    // lparser.c: removevars
    private void removevars(FuncState fs, int tolevel) {


        fs.ls.dyd.actvarN -= (fs.nactvar - tolevel);
        while (fs.nactvar > tolevel) {
            fs.nactvar--;

            Vardesc vd = getlocalvardesc(fs, fs.nactvar);
            if (vd != null && vd.pidx >= 0 && vd.pidx < fs.f.locvars.length) {
                Prototype.LocVar lv = fs.f.locvars[vd.pidx];
                if (lv != null) lv.endpc = fs.pc;
            }
        }
    }

    // lparser.c: searchupvalue
    private int searchupvalue(FuncState fs, LuaString name) {

        for (int i = 0; i < fs.nups; i++) {
            if (fs.f.upvalues[i].name.equals(name)) return i;
        }
        return -1;
    }

    // lparser.c: allocupvalue
    private Prototype.Upvaldesc allocupvalue(FuncState fs) {

        checkLimit(fs, fs.nups + 1, 255, "upvalues");
        if (fs.nups >= fs.f.upvalues.length) {
            fs.f.upvalues = Arrays.copyOf(fs.f.upvalues, Math.max(fs.nups * 2, 4));
        }
        Prototype.Upvaldesc up = new Prototype.Upvaldesc();
        fs.f.upvalues[fs.nups++] = up;
        return up;
    }

    // lparser.c: newupvalue
    private int newupvalue(FuncState fs, LuaString name, expdesc v) {


        int idx = fs.nups;
        Prototype.Upvaldesc up = allocupvalue(fs);
        up.name = name;
        FuncState prev = fs.prev;
        if (v.k == VLOCAL) {
            up.instack = true;
            up.idx = v.var_ridx;

            Vardesc vd = getlocalvardesc(prev, v.var_vidx);
            up.kind = vd.kind;
        } else {

            up.instack = false;
            up.idx = v.info;
            up.kind = prev.f.upvalues[v.info].kind;
        }
        return idx;
    }

    // lparser.c: searchvar
    private int searchvar(FuncState fs, LuaString n, expdesc var) {

        for (int i = fs.nactvar - 1; i >= 0; i--) {
            Vardesc vd = getlocalvardesc(fs, i);
            if (SyntaxNodes.varglobal(vd)) {
                if (vd.name == null) {
                    if (var.info < 0)
                        var.info = fs.firstlocal + i;
                } else {
                    if (vd.name.equals(n)) {
                        initExp(var, VGLOBAL, fs.firstlocal + i);
                        return VGLOBAL;
                    } else if (var.info == -1)
                        var.info = -2;
                }
            } else if (vd.name != null && vd.name.equals(n)) {
                if (vd.kind == RDKCTC) {
                    initExp(var, VCONST, fs.firstlocal + i);
                } else {
                    initVar(fs, var, i);
                    if (vd.kind == RDKVAVAR) var.k = VVARGVAR;
                }
                return var.k;
            }
        }
        return -1;
    }

    // lparser.c: singlevaraux
    private void singlevaraux(FuncState fs, LuaString n, expdesc var, int base) {
        int v = searchvar(fs, n, var);
        if (v >= 0) {
            // java-only: markupval 用传入 fs——this.fs 此刻已指向嵌套函数的 FuncState
            if (base == 0 && var.k == VVARGVAR) {
                CodeGen.needvatab(fs.f);
                var.k = VLOCAL;
            }
            if (base == 0 && var.k == VLOCAL) markupval(fs, var.var_vidx);
        } else {
            int idx = searchupvalue(fs, n);
            if (idx < 0) {
                if (fs.prev != null) singlevaraux(fs.prev, n, var, 0);
                if (var.k == VLOCAL || var.k == VUPVAL)
                    idx = newupvalue(fs, n, var);
                else return;
            }
            initExp(var, VUPVAL, idx);
        }
    }

    // lparser.c: singlevar
    private void singlevar(Lexer ls, expdesc var) {
        // 入口取 ls->linenumber（名字 token 所在行，保证 not-declared 报错行与名字一致）
        int line = ls.linenumber();
        LuaString n = strCheckname(ls);
        buildvar(ls, n, var, line);
    }

    // lparser.c: buildvar
    private void buildvar(Lexer ls, LuaString varname, expdesc var, int line) {
        FuncState fs = ls.fs;
        initExp(var, VGLOBAL, -1);
        singlevaraux(fs, varname, var, 1);
        if (var.k == VGLOBAL) {
            int info = var.info;
            if (info == -2)
                throw ls.syntaxErrorAtLine("variable '" + varname + "' not declared", line);
            buildglobal(ls, varname, var);
            if (info != -1 && info < ls.dyd.actvar().size()) {
                Vardesc vd = ls.dyd.actvar().get(info);
                if (vd.kind == GDKCONST)
                    var.ind_ro = true;
            }
        }
    }

    // lparser.c: buildglobal
    private void buildglobal(Lexer ls, LuaString varname, expdesc var) {
        FuncState fs = ls.fs;
        expdesc key = new expdesc();
        initExp(var, VGLOBAL, -1);
        singlevaraux(fs, Lexer.ENVN, var, 1);  // 获取 _ENV
        if (var.k == VGLOBAL)
            throw ls.syntaxError("_ENV is global when accessing variable '" + varname + "'");
        CodeGen.exp2AnyRegup(fs, var);  // _ENV 可能是常量
        codestring(key, varname);  // 键为变量名
        CodeGen.indexed(fs, var, key);  // 'var' 表示 _ENV[varname]
    }

    // lparser.c: adjust_assign
    private void adjustAssign(Lexer ls, int nvars, int nexps, expdesc e) {
        FuncState fs = ls.fs;
        int needed = nvars - nexps;
        CodeGen.checkStack(fs, needed);
        if (hasmultret(e.k)) {
            int extra = needed + 1;
            if (extra < 0) extra = 0;
            CodeGen.setReturns(fs, e, extra);
        } else {
            if (e.k != VVOID) CodeGen.exp2NextReg(fs, e);
            if (needed > 0) CodeGen.codeNil(fs, fs.freereg, needed);
        }
        if (needed > 0) CodeGen.reserveRegs(fs, needed);
        else fs.freereg = fs.freereg + needed;
    }

    // ===============================================================
    // 标签 / goto (lparser.c)
    // ===============================================================

    // lparser.c: luaY_nvarstack
    private int nVarStack(FuncState fs) {

        int level = 0;
        for (int i = fs.nactvar - 1; i >= 0; i--) {
            Vardesc vd = getlocalvardesc(fs, i);
            if (SyntaxNodes.varinreg(vd) && vd.ridx >= level) level = vd.ridx + 1;
        }
        return level;
    }

    // lparser.c: markupval
    private void markupval(FuncState fs, int level) {

        BlockCnt bl = fs.bl;
        while (bl != null && bl.nactvar > level) bl = bl.previous;
        if (bl != null) {
            bl.upval = 1;
            fs.needclose = 1;
        }
    }

    // lparser.c: init_var
    private void initVar(FuncState fs, expdesc e, int vidx) {
        e.t = Opcodes.NO_JUMP;
        e.f = Opcodes.NO_JUMP;
        e.k = VLOCAL;
        e.var_vidx = vidx;
        e.var_ridx = getlocalvardesc(fs, vidx).ridx;
    }

    // lparser.c: check_readonly
    private void checkReadonly(Lexer ls, expdesc e) {
        FuncState fs = ls.fs;
        LuaString varname = null;
        switch (e.k) {
            case VCONST:
                if (e.info >= 0 && e.info < ls.dyd.actvar().size()) {
                    varname = ls.dyd.actvar().get(e.info).name;
                }
                break;
            case VLOCAL:
            case VVARGVAR: {
                Vardesc vd = getlocalvardesc(fs, e.var_vidx);
                if (vd.kind != VDKREG) varname = vd.name;
                break;
            }
            case VUPVAL: {
                if (e.info >= 0 && e.info < fs.f.upvalues.length && fs.f.upvalues[e.info] != null) {
                    Prototype.Upvaldesc up = fs.f.upvalues[e.info];
                    if (up.kind != VDKREG) varname = up.name;
                }
                break;
            }
            case VVARGIND:
                // 标记函数需要可变参数表
                CodeGen.needvatab(fs.f);
                e.k = VINDEXED;
                // FALLTHROUGH
            case VINDEXUP:
            case VINDEXSTR:
            case VINDEXED: {
                if (e.ind_ro && e.ind_keystr >= 0 && e.ind_keystr < fs.nk) {
                    LuaValue kv = fs.f.k[e.ind_keystr];
                    if (kv instanceof LuaString) varname = (LuaString) kv;
                }
                break;
            }
            case VINDEXI:
                return;  // 整数索引不可能是只读
            default:
                return;  // VVOID / VNIL / 常量  -  无需检查
        }
        if (varname != null) {
            CodeGen.semError(ls, "attempt to assign to const variable '" + varname + "'");
        }
    }

    // lparser.c: newlabelentry
    private int newlabelentry(Lexer ls, Labellist l, LuaString name, int line, int pc) {
        FuncState fs = ls.fs;

        Labeldesc ld = new Labeldesc(name, pc, line, fs.nactvar, (byte) 0);
        l.arr().add(ld);
        return l.arr().size() - 1;
    }

    // lparser.c: newgotoentry
    private int newgotoentry(Lexer ls, LuaString name, int line) {
        FuncState fs = ls.fs;
        int pc = CodeGen.codeJump(fs);  // 创建跳转
        // CLOSE 占位符  -  标记为死指令（C 使用 OP_CLOSE B=1 标记死指令）
        CodeGen.codeABC(fs, Opcodes.OP_CLOSE, 0, 1, 0, 0);
        return newlabelentry(ls, ls.dyd.gt, name, line, pc);
    }

    // lparser.c: createlabel
    private void createlabel(Lexer ls, LuaString name, int line, int last) {
        FuncState fs = ls.fs;
        int l = newlabelentry(ls, ls.dyd.label, name, line, CodeGen.getLabel(fs));
        if (last != 0) {
            // label 是最后的无操作语句：假设局部变量已出作用域
            ls.dyd.label.arr().get(l).nactvar = fs.bl.nactvar;
        }
    }

    // lparser.c: closegoto
    private void closegoto(Lexer ls, int g, Labeldesc label, int bup) {
        FuncState fs = ls.fs;
        Labeldesc gt = ls.dyd.gt.arr().get(g);
        if (gt.nactvar < label.nactvar) {
            // goto 跳入某变量的作用域  -  报错
            jumpscopeerror(ls, gt);
        }
        if (gt.close != 0 || (label.nactvar < gt.nactvar && bup != 0)) {
            // 需要 CLOSE：交换跳转和 CLOSE 占位符
            int stklevel = reglevel(fs, label.nactvar);
            // 将跳转移到 CLOSE 位置 (pc+1)
            fs.f.code[gt.pc + 1] = fs.f.code[gt.pc];
            // 将 CLOSE 放在原始位置
            fs.f.code[gt.pc] = Opcodes.CREATE_ABC(Opcodes.OP_CLOSE, stklevel, 0, 0, 0);
            gt.pc++;  // 现在指向跳转指令
        }
        CodeGen.patchList(fs, gt.pc, label.pc);  // goto 跳转到标签
        ls.dyd.gt.arr().remove(g);  // 从待处理列表中移除
    }

    // ===============================================================
    // 块 / 作用域管理 (lparser.c)
    // ===============================================================

    // lparser.c: jumpscopeerror
    private void jumpscopeerror(Lexer ls, Labeldesc gt) {
        FuncState fs = ls.fs;
        String varname = "*";
        int idx = fs.firstlocal + gt.nactvar;
        if (idx < ls.dyd.actvar().size()) {
            Vardesc vd = ls.dyd.actvar().get(idx);
            if (vd != null && vd.name != null) varname = vd.name.toJavaString();
        }
        CodeGen.semError(ls, "<goto " + gt.name + "> at line " + gt.line
                + " jumps into the scope of '" + varname + "'");
    }

    // lparser.c: findlabel
    private Labeldesc findlabel(Lexer ls, LuaString name, int ilb) {
        for (; ilb < ls.dyd.label.arr().size(); ilb++) {
            Labeldesc lb = ls.dyd.label.arr().get(ilb);
            if (lb.name.equals(name)) return lb;
        }
        return null;
    }

    // lparser.c: solvegotos
    private void solvegotos(FuncState fs, BlockCnt bl) {

        Lexer ls = fs.ls;
        int outlevel = reglevel(fs, bl.nactvar);  // 块外级别
        int igt = bl.firstgoto;
        while (igt < ls.dyd.gt.arr().size()) {
            Labeldesc gt = ls.dyd.gt.arr().get(igt);
            Labeldesc lb = findlabel(ls, gt.name, bl.firstlabel);
            if (lb != null) {
                // 在此块中找到匹配的 label  -  关闭并移除 goto
                closegoto(ls, igt, lb, bl.upval);
                // closegoto 移除了 igt 处的元素，所以不递增 igt
            } else {
                // 无匹配：将 goto 导出到外层块
                // 如果块有 upvalue 且 goto 逃出作用域，标记为需要 close
                if (bl.upval != 0 && reglevel(fs, gt.nactvar) > outlevel) {
                    gt.close = 1;
                }
                gt.nactvar = bl.nactvar;  // 修正为外层块的级别
                igt++;
            }
        }
        // 移除局部 label（此块的 label）
        while (ls.dyd.label.arr().size() > bl.firstlabel) {
            ls.dyd.label.arr().remove(ls.dyd.label.arr().size() - 1);
        }
    }

    // lparser.c: undefgoto
    private void undefgoto(Lexer ls, Labeldesc gt) {
        // break 在创建时检查，不会未定义
        CodeGen.semError(ls, "no visible label '" + gt.name + "' for <goto> at line " + gt.line);
    }

    // lparser.c: enterblock
    private void enterblock(FuncState fs, BlockCnt bl, byte isloop) {

        bl.isloop = isloop;
        bl.nactvar = fs.nactvar;
        bl.firstlabel = ls.dyd.label.arr().size();
        bl.firstgoto = ls.dyd.gt.arr().size();
        bl.upval = 0;
        // 从外层块继承 'insidetbc'
        bl.insidetbc = (fs.bl != null && fs.bl.insidetbc);
        bl.previous = fs.bl;
        fs.bl = bl;
    }

    // ===============================================================
    // 函数体 (lparser.c)
    // ===============================================================

    // lparser.c: leaveblock
    private void leaveblock(FuncState fs) {
        BlockCnt bl = fs.bl;
        int stklevel = reglevel(fs, bl.nactvar);  // 块外级别
        // 若块有上值且非顶层，发射 OP_CLOSE
        if (bl.previous != null && bl.upval != 0) {
            CodeGen.codeABC(fs, Opcodes.OP_CLOSE, stklevel, 0, 0, 0);
        }
        fs.freereg = stklevel;
        removevars(fs, bl.nactvar);
        // 修复待处理的 break：如果 isloop == 2，创建 "break" label
        if (bl.isloop == 2) {
            createlabel(fs.ls, Lexer.BRKN, 0, 0);
        }
        solvegotos(fs, bl);
        // 检查顶层（最后一个块）的未定义 goto
        if (bl.previous == null) {
            if (bl.firstgoto < ls.dyd.gt.arr().size()) {
                undefgoto(ls, ls.dyd.gt.arr().get(bl.firstgoto));
            }
        }
        fs.bl = bl.previous;
    }

    // lparser.c: reglevel
    private int reglevel(FuncState fs, int nvar) {
        while (nvar-- > 0) {
            int idx = fs.firstlocal + nvar;
            if (idx < 0 || idx >= ls.dyd.actvar().size()) break;  // 安全：变量已移除
            Vardesc vd = ls.dyd.actvar().get(idx);
            if (SyntaxNodes.varinreg(vd)) return vd.ridx + 1;
        }
        return 0;
    }

    // lparser.c: block_follow
    private boolean blockFollow(Lexer ls, int withuntil) {

        int t = ls.t.token;
        return t == Lexer.TK_ELSE || t == Lexer.TK_ELSEIF || t == Lexer.TK_END
                || t == Lexer.TK_EOS || (withuntil != 0 && t == Lexer.TK_UNTIL);
    }

    // lparser.c: block
    private void block(Lexer ls) {
        FuncState fs = ls.fs;
        BlockCnt bl = new BlockCnt();
        enterblock(fs, bl, (byte) 0);
        statlist(ls);
        leaveblock(fs);
    }

    // lparser.c: addprototype
    private Prototype addprototype(Lexer ls) {

        FuncState fs = ls.fs;
        Prototype f = fs.f;
        if (fs.np >= f.p.length) {
            int newsize = Math.max(f.p.length * 2, 4);
            f.p = Arrays.copyOf(f.p, newsize);
        }
        Prototype clp = new Prototype();
        f.p[fs.np++] = clp;
        return clp;
    }

    // lparser.c: codeclosure
    private void codeclosure(Lexer ls, expdesc v) {

        FuncState fs = ls.fs.prev;
        int pc = CodeGen.codeABx(fs, Opcodes.OP_CLOSURE, 0, fs.np - 1);
        initExp(v, VRELOC, pc);
        CodeGen.exp2NextReg(fs, v);
    }

    // ===============================================================
    // 语句列表与语句分派 (lparser.c)
    // ===============================================================

    // lparser.c: open_func
    private void openFunc(Lexer ls, FuncState fs, BlockCnt bl) {

        Prototype f = fs.f;
        fs.prev = ls.fs;
        fs.ls = ls;
        ls.fs = fs;
        fs.pc = 0;
        fs.previousline = f.linedefined;
        fs.iwthabs = 0;
        fs.lasttarget = 0;
        fs.freereg = 0;
        fs.nk = 0;
        fs.nabslineinfo = 0;
        fs.np = 0;
        fs.nups = 0;
        fs.ndebugvars = 0;
        fs.nactvar = 0;
        fs.needclose = 0;
        fs.firstlocal = ls.dyd.actvarN;
        fs.firstlabel = ls.dyd.label.arr().size();
        fs.bl = null;
        f.source = LuaString.newStr(ls.source());
        f.maxstacksize = 2;
        enterblock(fs, bl, (byte) 0);
    }

    // lparser.c: close_func
    private void closeFunc(Lexer ls) {

        FuncState fs = ls.fs;
        Prototype f = fs.f;
        CodeGen.codeRet(fs, nVarStack(fs), 0);
        leaveblock(fs);
        CodeGen.finish(fs);
        // luaM_shrinkvector(... f->sizelocvars, fs->ndebugvars ...)
        f.sizelocvars = fs.ndebugvars;
        f.locvars = Arrays.copyOf(f.locvars, fs.ndebugvars);
        // luaM_shrinkvector(... f->sizeupvalues, fs->nups ...)
        f.sizeupvalues = fs.nups;
        f.upvalues = Arrays.copyOf(f.upvalues, fs.nups);
        // lobject.h: luaF_newproto  -  所有 size 字段设置后提交内存记账
        f.commitProtoMem();
        ls.fs = fs.prev;
    }

    // ===============================================================
    // if (lparser.c)
    // ===============================================================

    // lparser.c: body
    private void body(Lexer ls, expdesc e, int ismethod, int line) {
        // java-only: 从 ThreadLocal 池获取 FuncState，对齐 C 的 FuncState 栈分配语义（lparser.c:body）
        FuncState newFs = obtainFuncState();
        // dyd 在 ls（LexState）上，不在 FuncState 上。无需复制。
        newFs.f = addprototype(ls);
        newFs.f.linedefined = line;
        BlockCnt bl = new BlockCnt();
        openFunc(ls, newFs, bl);
        // _ENV 上值由内层函数经 singlevaraux -> newupvalue 按需创建；
        // 此处不预分配——instack=true/idx=0 仅主函数有效（_ENV 经 vararg
        // 在寄存器 0），内层函数会误指父函数栈槽 0。
        checknext(ls, '(');
        if (ismethod != 0) {
            newLocalvar(ls, LuaString.newStr("self"));
            adjustlocalvars(ls, 1);
        }
        parlist(ls);
        checknext(ls, ')');
        statlist(ls);
        ls.fs.f.lastlinedefined = ls.linenumber();
        checkMatch(ls, Lexer.TK_END, Lexer.TK_FUNCTION, line);
        codeclosure(ls, e);
        closeFunc(ls);
        // java-only: 归还 FuncState 到池，对齐 C 的栈释放。Prototype 已由
        //   addprototype 添加到父 Proto.p，reset() 清除 fs.f 引用是安全的。
        releaseFuncState(newFs);
    }

    // lparser.c: parlist
    private void parlist(Lexer ls) {
        FuncState fs = ls.fs;
        int nparams = 0;
        int isvararg = 0;
        if (ls.t.token != ')') {
            for (; ; ) {
                if (ls.t.token == Lexer.TK_NAME) {
                    newLocalvar(ls, strCheckname(ls));
                    nparams++;
                } else if (ls.t.token == Lexer.TK_DOTS) {
                    isvararg = 1;
                    ls.next();  // 跳过 '...'
                    // Lua 5.5：'...NAME' 创建命名可变参数（RDKVAVAR）
                    // if (ls->t.token == TK_NAME) new_varkind(...)
                    if (ls.t.token == Lexer.TK_NAME) {
                        newVarkind(ls, strCheckname(ls), RDKVAVAR);
                    } else {
                        // 纯 '...'  -  必须创建变量条目以便 adjustlocalvars(1) 可以激活它
                        newLocalvar(ls, LuaString.newStr("(vararg table)"));
                    }
                    break;
                } else throw ls.syntaxError("<name> or '...' expected");
                if (ls.t.token != ',') break;
                ls.next();
            }
        }
        // lparser.c 顺序：adjustlocalvars(nparams); f->numparams = fs->nactvar;
        //   if (varargk) { setvararg(fs); adjustlocalvars(1); } luaK_reserveregs(fs, fs->nactvar)
        adjustlocalvars(ls, nparams);
        // f->numparams = cast_byte(fs->nactvar)：含 self（方法）+参数，不含 vararg 参数
        fs.f.numparams = (byte) fs.nactvar;
        if (isvararg != 0) {
            setvararg(fs);
            // 激活可变参数变量
            adjustlocalvars(ls, 1);
        }
        // 为参数保留寄存器
        CodeGen.reserveRegs(fs, fs.nactvar);
    }

    // lparser.c: statlist
    private void statlist(Lexer ls) {
        // block_follow(ls, 1) 使 TK_UNTIL 终止 statlist（但不终止作用域）；
        // 传 0 会把 'until' 当语句解析，repeat...until 报 "<name> expected"
        while (!blockFollow(ls, 1)) {
            if (ls.t.token == Lexer.TK_RETURN) {
                statement(ls);
                return;
            }
            statement(ls);
        }
    }

    // ===============================================================
    // while (lparser.c)
    // ===============================================================

    // lparser.c: statement
    private void statement(Lexer ls) {
        FuncState fs = ls.fs;
        // C: lparser.c:statement  -  使用当前词法行号；lookahead 后 lastline 可能已经落后一行。
        int line = ls.linenumber();
        ls.recdepth++;
        if (ls.recdepth > 200) throw ls.syntaxError("C stack overflow");
        // LUA_COMPAT_GLOBAL  -  检查当前 token 是否为 "global"（作为 NAME）
        // 且 lookahead 是 '<'、NAME、'*' 或 FUNCTION -> 视为全局声明语句。
        if (Lexer.COMPAT_GLOBAL && ls.t.token == Lexer.TK_NAME && ls.t.ts != null && ls.t.ts.equals(Lexer.GLBN)) {
            int lk = ls.lookahead();
            if (lk == '<' || lk == Lexer.TK_NAME || lk == '*' || lk == Lexer.TK_FUNCTION) {
                globalstatfunc(ls, line);
                fs.freereg = nVarStack(fs);
                ls.recdepth--;
                return;
            }
        }
        switch (ls.t.token) {
            case ';':
                ls.next();
                break;
            case Lexer.TK_IF:
                ifstat(ls, line);
                break;
            case Lexer.TK_WHILE:
                whilestat(ls, line);
                break;
            case Lexer.TK_DO: {
                ls.next();
                block(ls);
                checkMatch(ls, Lexer.TK_END, Lexer.TK_DO, line);
                break;
            }
            case Lexer.TK_FOR:
                forstat(ls, line);
                break;
            case Lexer.TK_REPEAT:
                repeatstat(ls, line);
                break;
            case Lexer.TK_FUNCTION:
                funcstat(ls, line);
                break;
            case Lexer.TK_LOCAL: {
                ls.next();
                if (testnext(ls, Lexer.TK_FUNCTION) != 0) localfunc(ls);
                else localstat(ls);
                break;
            }
            case Lexer.TK_GLOBAL:
                globalstatfunc(ls, line);
                break;
            case Lexer.TK_DBCOLON: {
                ls.next();
                LuaString name = strCheckname(ls);
                labelstat(ls, name, line);
                break;
            }
            case Lexer.TK_RETURN: {
                ls.next();
                retstat(ls);
                break;
            }
            case Lexer.TK_BREAK:
                breakstat(ls, line);
                break;
            case Lexer.TK_GOTO: {
                ls.next();
                gotostat(ls, line);
                break;
            }
            default:
                exprstat(ls);
                break;
        }
        fs.freereg = nVarStack(fs);
        ls.recdepth--;
    }

    // ===============================================================
    // repeat/until（lparser.c）
    // ===============================================================

    // lparser.c: test_then_block
    private void testThenBlock(Lexer ls, int[] escapelist) {
        FuncState fs = ls.fs;
        ls.next();
        int condexit = cond(ls);
        // lparser.c: test_then_block checknext(ls, TK_THEN) —— 任何非 THEN 一律 "'then' expected"
        if (ls.t.token != Lexer.TK_THEN) {
            errorExpected(ls, Lexer.TK_THEN);
        }
        ls.next();
        block(ls);
        if (ls.t.token == Lexer.TK_ELSE || ls.t.token == Lexer.TK_ELSEIF) {
            escapelist[0] = CodeGen.codeConcat(fs, escapelist[0], CodeGen.codeJump(fs));
        }
        CodeGen.patchToHere(fs, condexit);
    }

    // ===============================================================
    // for (lparser.c)
    // ===============================================================

    // lparser.c: ifstat
    private void ifstat(Lexer ls, int line) {
        FuncState fs = ls.fs;
        int[] escapelist = new int[]{Opcodes.NO_JUMP};
        testThenBlock(ls, escapelist);
        while (ls.t.token == Lexer.TK_ELSEIF)
            testThenBlock(ls, escapelist);
        if (ls.t.token == Lexer.TK_ELSE) {
            ls.next();
            block(ls);
        }
        checkMatch(ls, Lexer.TK_END, Lexer.TK_IF, line);
        CodeGen.patchToHere(fs, escapelist[0]);
    }

    // lparser.c: cond
    private int cond(Lexer ls) {
        FuncState fs = ls.fs;
        // 读取条件表达式，调用 luaK_goiftrue，返回 v.f
        expdesc v = new expdesc();
        expr(ls, v);
        if (v.k == VNIL) v.k = VFALSE;  // 此处所有 'falses' 等价
        // goIfTrue 产生控制流模式（CMP; JMP exit），条件为假时跳出循环体；
        // 若只产布尔值模式（LOADTRUE）则循环条件没有退出跳转
        CodeGen.goIfTrue(fs, v);
        return v.f;
    }

    // lparser.c: whilestat
    private void whilestat(Lexer ls, int line) {
        FuncState fs = ls.fs;
        ls.next();  // 跳过 WHILE
        int whileinit = CodeGen.getLabel(fs);
        int condexit = cond(ls);
        BlockCnt bl = new BlockCnt();
        enterblock(fs, bl, BlockCnt.LOOP);  // isloop=1
        checknext(ls, Lexer.TK_DO);
        block(ls);
        // luaK_jumpto(fs, whileinit) = luaK_patchlist(luaK_jump(), whileinit)
        CodeGen.patchList(fs, CodeGen.codeJump(fs), whileinit);
        checkMatch(ls, Lexer.TK_END, Lexer.TK_WHILE, line);
        leaveblock(fs);
        CodeGen.patchToHere(fs, condexit);  // 条件为假时结束循环
    }

    // lparser.c: repeatstat
    private void repeatstat(Lexer ls, int line) {
        FuncState fs = ls.fs;

        int condexit;
        int repeat_init = CodeGen.getLabel(fs);  // 循环起始
        BlockCnt bl1 = new BlockCnt(), bl2 = new BlockCnt();
        enterblock(fs, bl1, BlockCnt.LOOP);  // 循环块（isloop=1）
        enterblock(fs, bl2, (byte) 0);  // 作用域块
        ls.next();  // 跳过 REPEAT
        statlist(ls);
        checkMatch(ls, Lexer.TK_UNTIL, Lexer.TK_REPEAT, line);
        condexit = cond(ls);  // 读取条件（在作用域块内）
        if (bl2.upval != 0) {  // 有上值？
            int exit = CodeGen.codeJump(fs);  // 正常退出必须跳过修正
            CodeGen.patchToHere(fs, condexit);  // 重复必须关闭上值
            CodeGen.codeABC(fs, Opcodes.OP_CLOSE, reglevel(fs, bl2.nactvar), 0, 0, 0);
            condexit = CodeGen.codeJump(fs);  // 关闭上值后重复
            CodeGen.patchToHere(fs, exit);  // 正常退出到达此处
        }
        CodeGen.patchList(fs, condexit, repeat_init);  // 关闭循环
        // 作用域 leaveblock 必须在 patchList 之后：repeat-until 的额外 close
        //   指令仍编码在变量作用域内，endpc 覆盖它 ->
        //   debug.getlocal 在 close 执行时仍能取到变量名
        leaveblock(fs);  // 结束作用域
        leaveblock(fs);  // 结束循环
    }

    // lparser.c: forstat
    private void forstat(Lexer ls, int line) {
        FuncState fs = ls.fs;

        BlockCnt bl = new BlockCnt();
        enterblock(fs, bl, BlockCnt.LOOP);
        ls.next();  // 跳过 'for'
        LuaString varname = strCheckname(ls);  // 第一个变量名
        switch (ls.t.token) {
            case '=':
                fornum(ls, varname, line);
                break;
            case ',':
            case Lexer.TK_IN:
                forlist(ls, varname);
                break;
            default:
                throw ls.syntaxError("'=' or 'in' expected");
        }
        checkMatch(ls, Lexer.TK_END, Lexer.TK_FOR, line);
        leaveblock(fs);
    }

    // lparser.c: fornum
    private void fornum(Lexer ls, LuaString varname, int line) {
        FuncState fs = ls.fs;
        int base = fs.freereg;
        newLocalvar(ls, LuaString.newStr("(for state)"));
        newLocalvar(ls, LuaString.newStr("(for state)"));
        newVarkind(ls, varname, RDKCONST);  // 控制变量
        checknext(ls, '=');
        exp1(ls);
        checknext(ls, ',');
        exp1(ls);
        if (testnext(ls, ',') != 0) {
            exp1(ls);
        } else {
            CodeGen.intK(fs, fs.freereg, 1);
            CodeGen.reserveRegs(fs, 1);
        }
        adjustlocalvars(ls, 2);
        forbody(ls, base, line, 1, 0);
    }

    // ===============================================================
    // 函数语句 (lparser.c)
    // ===============================================================

    // lparser.c: forbody
    private void forbody(Lexer ls, int base, int line, int nvars, int isgen) {
        FuncState fs = ls.fs;
        checknext(ls, Lexer.TK_DO);
        int prep = CodeGen.codeABx(fs, isgen == 0 ? Opcodes.OP_FORPREP : Opcodes.OP_TFORPREP, base, 0);
        fs.freereg--;
        BlockCnt bl = new BlockCnt();
        enterblock(fs, bl, (byte) 0);
        adjustlocalvars(ls, nvars);
        CodeGen.reserveRegs(fs, nvars);
        block(ls);
        leaveblock(fs);
        fixforjump(fs, prep, CodeGen.getLabel(fs), 0);
        if (isgen != 0) {
            CodeGen.codeABCk(fs, Opcodes.OP_TFORCALL, base, 0, nvars, 0);
            CodeGen.fixline(fs, line);
        }
        int endfor = CodeGen.codeABx(fs, isgen == 0 ? Opcodes.OP_FORLOOP : Opcodes.OP_TFORLOOP, base, 0);
        fixforjump(fs, endfor, prep + 1, 1);
        CodeGen.fixline(fs, line);
    }

    // lparser.c: fixforjump
    private void fixforjump(FuncState fs, int pc, int dest, int back) {

        // VM 主循环 dispatch 先 pc++ 再按 Bx 跳：FORPREP new_pc = (pc+1)+Bx，FORLOOP new_pc = (pc+1)-Bx。
        // 故 Bx = dest-(pc+1)，back 时取负。
        int offset = dest - (pc + 1);
        if (back != 0) offset = -offset;
        if (offset > Opcodes.MAXARG_Bx)
            throw fs.ls.syntaxError("control structure too long");
        int inst = fs.f.code[pc];
        fs.f.code[pc] = Opcodes.SETARG_Bx(inst, offset);
    }

    // ===============================================================
    // 局部变量 (lparser.c)
    // ===============================================================

    // lparser.c: exp1
    private void exp1(Lexer ls) {
        FuncState fs = ls.fs;
        expdesc e = new expdesc();
        expr(ls, e);
        CodeGen.exp2NextReg(fs, e);
    }

    // lparser.c: forlist
    private void forlist(Lexer ls, LuaString firstvar) {

        FuncState fs = ls.fs;
        int nvars = 4;  // 函数、状态、关闭变量、控制变量
        int line;
        int base = fs.freereg;
        // 创建内部变量
        newLocalvar(ls, LuaString.newStr("(for state)"));  // 迭代器函数
        newLocalvar(ls, LuaString.newStr("(for state)"));  // 状态
        newLocalvar(ls, LuaString.newStr("(for state)"));  // 关闭变量（交换后）
        newVarkind(ls, firstvar, RDKCONST);  // 控制变量
        // 其他声明的变量
        while (testnext(ls, ',') != 0) {
            newLocalvar(ls, strCheckname(ls));
            nvars++;
        }
        checknext(ls, Lexer.TK_IN);
        line = ls.linenumber();
        expdesc e = new expdesc();
        int nexps = explist(ls, e);
        adjustAssign(ls, 4, nexps, e);
        adjustlocalvars(ls, 3);  // 开始内部变量的作用域（函数、状态、关闭变量）
        marktobeclosed(fs);  // 关闭变量必须在循环退出时关闭
        CodeGen.checkStack(fs, 2);  // 调用迭代器的额外空间
        forbody(ls, base, line, nvars - 3, 1);
    }

    // lparser.c: funcname
    private int funcname(Lexer ls, expdesc v) {
        int ismethod = 0;
        singlevar(ls, v);
        while (ls.t.token == '.') fieldsel(ls, v);
        if (ls.t.token == ':') {
            ismethod = 1;
            fieldsel(ls, v);
        }
        return ismethod;
    }

    // lparser.c: funcstat
    private void funcstat(Lexer ls, int line) {
        FuncState fs = ls.fs;
        ls.next();  // 跳过 FUNCTION
        expdesc v = new expdesc();
        expdesc b = new expdesc();
        int ismethod = funcname(ls, v);
        checkReadonly(ls, v);
        body(ls, b, ismethod, line);
        CodeGen.storeVar(fs, v, b);
        CodeGen.fixline(fs, line);  // 定义"发生在"第一行
    }

    // lparser.c: localfunc
    private void localfunc(Lexer ls) {
        FuncState fs = ls.fs;

        // 新局部变量的 ridx 等于当前 freereg，所以 body() 发出的
        // 闭包自然落在变量的寄存器中  -  不需要 storevar。
        expdesc b = new expdesc();
        int fvar = fs.nactvar;
        newLocalvar(ls, strCheckname(ls));
        adjustlocalvars(ls, 1);
        body(ls, b, 0, ls.linenumber());
        // 调试信息：变量作用域在函数体编译后开始。
        Prototype.LocVar lv = localdebuginfo(fs, fvar);
        if (lv != null) lv.startpc = fs.pc;
    }

    // lparser.c: localstat
    private void localstat(Lexer ls) {

        FuncState fs = ls.fs;
        int toclose = -1;  // to-be-closed 变量的索引（如果有）
        int vidx = 0;  // 最后一个变量的索引
        int nvars = 0;
        int nexps;
        expdesc e = new expdesc();
        // 获取前缀属性（如果有）；默认是普通局部变量
        int defkind = getvarattribute(ls, VDKREG);
        do {  // 对每个变量
            LuaString vname = strCheckname(ls);  // 获取名称
            int kind = getvarattribute(ls, defkind);  // 后缀属性
            vidx = newVarkind(ls, vname, kind);  // 预声明
            if (kind == RDKTOCLOSE) {  // to-be-closed？
                if (toclose != -1)  // 已有一个？
                    CodeGen.semError(ls, "multiple to-be-closed variables in local list");
                toclose = fs.nactvar + nvars;
            }
            nvars++;
        } while (testnext(ls, ',') != 0);
        if (testnext(ls, '=') != 0) {  // 初始化？
            nexps = explist(ls, e);
        } else {
            e.k = VVOID;
            nexps = 0;
        }
        Vardesc var = getlocalvardesc(fs, vidx);  // 获取最后一个变量
        if (nvars == nexps &&  // 无需调整？
                var.kind == RDKCONST &&  // 最后一个变量是 const？
                exp2Const(e, var)) {  // 编译期常量？
            var.kind = RDKCTC;  // 变量是编译期常量
            adjustlocalvars(ls, nvars - 1);  // 排除最后一个变量
            fs.nactvar++;  // 但计入
        } else {
            adjustAssign(ls, nvars, nexps, e);
            adjustlocalvars(ls, nvars);
        }
        checktoclose(fs, toclose);
    }

    // lparser.c: getvarattribute
    private int getvarattribute(Lexer ls, int df) {
        if (testnext(ls, '<') != 0) {
            LuaString ts = strCheckname(ls);
            String attr = ts.toJavaString();
            checknext(ls, '>');
            if (attr.equals("const")) {
                return RDKCONST;  // 只读变量
            } else if (attr.equals("close")) {
                return RDKTOCLOSE;  // to-be-closed 变量
            } else {
                CodeGen.semError(ls, "unknown attribute '" + attr + "'");
            }
        }
        return df;  // 返回默认值
    }

    // ===============================================================
    // return (lparser.c)
    // ===============================================================

    // lparser.c: checktoclose
    private void checktoclose(FuncState fs, int level) {
        if (level != -1) {
            marktobeclosed(fs);
            CodeGen.codeABC(fs, Opcodes.OP_TBC, reglevel(fs, level), 0, 0, 0);
        }
    }

    // ===============================================================
    // 2  -  全局声明 (lparser.c)  -  Lua 5.5
    // ===============================================================

    // lparser.c: marktobeclosed
    private void marktobeclosed(FuncState fs) {
        BlockCnt bl = fs.bl;
        bl.upval = 1;
        bl.insidetbc = true;
        fs.needclose = 1;
    }

    // lcode.c: luaK_exp2const
    private boolean exp2Const(expdesc e, Vardesc var) {
        FuncState fs = ls.fs;
        if (CodeGen.hasjumpsPublic(e)) return false;  // 非常量
        expdesc c = new expdesc();
        switch (e.k) {
            case VFALSE:
                initExp(c, VFALSE, 0);
                var.ctc = c;
                return true;
            case VTRUE:
                initExp(c, VTRUE, 0);
                var.ctc = c;
                return true;
            case VNIL:
                initExp(c, VNIL, 0);
                var.ctc = c;
                return true;
            case VKSTR:
                c.copyFrom(e);
                var.ctc = c;
                return true;
            case VCONST:
                c.copyFrom(fs.ls.dyd.actvar().get(e.info).ctc);
                var.ctc = c;
                return true;
            default:
                // 尝试转换为数字 (VKINT / VKFLT)
                if (e.k == VKINT) {
                    c.copyFrom(e);
                    var.ctc = c;
                    return true;
                }
                if (e.k == VKFLT) {
                    c.copyFrom(e);
                    var.ctc = c;
                    return true;
                }
                return false;
        }
    }

    // lparser.c: explist
    private int explist(Lexer ls, expdesc v) {
        FuncState fs = ls.fs;
        int n = 1;
        expr(ls, v);
        while (ls.t.token == ',') {
            ls.next();
            CodeGen.exp2NextReg(fs, v);
            expr(ls, v);
            n++;
        }
        return n;
    }

    // lparser.c: retstat
    private void retstat(Lexer ls) {
        FuncState fs = ls.fs;
        int first = nVarStack(fs);
        int nret = 0;
        if (!blockFollow(ls, 1) && ls.t.token != ';') {
            expdesc e = new expdesc();
            nret = explist(ls, e);
            if (hasmultret(e.k)) {
                CodeGen.setReturns(fs, e, -1);
                // 尾调用：返回表达式是单返回值 VCALL 且不在 to-be-closed
                // 块中时，OP_CALL 转 OP_TAILCALL，VM 重用当前帧
                if (e.k == VCALL && nret == 1
                        && (fs.bl == null || !fs.bl.insidetbc)) {
                    int inst = fs.f.code[e.info];
                    fs.f.code[e.info] = Opcodes.SET_OPCODE(inst, Opcodes.OP_TAILCALL);
                }
                nret = -1;  // LUA_MULTRET
            } else {
                if (nret == 1) {
                    first = CodeGen.exp2AnyReg(fs, e);
                } else {
                    CodeGen.exp2NextReg(fs, e);
                }
            }
        }
        CodeGen.codeRet(fs, first, nret);
        if (ls.t.token == ';') ls.next();
    }

    // lparser.c: getglobalattribute
    private int getglobalattribute(Lexer ls, int df) {
        int kind = getvarattribute(ls, df);
        switch (kind) {
            case RDKTOCLOSE:
                CodeGen.semError(ls, "global variables cannot be to-be-closed");
            case RDKCONST:
                return GDKCONST;  // 调整全局变量的类型
            default:
                return kind;
        }
    }

    // lparser.c: checkglobal
    private void checkglobal(Lexer ls, LuaString varname, int line) {
        FuncState fs = ls.fs;
        expdesc var = new expdesc();
        buildglobal(ls, varname, var);  // 在 'var' 中创建全局变量
        int k = var.ind_keystr;  // 全局名称在 k[] 中的索引
        codeCheckGlobal(fs, var, k, line);
    }

    // lcode.c: luaK_codecheckglobal
    private void codeCheckGlobal(FuncState fs, expdesc var, int k, int line) {
        CodeGen.exp2AnyReg(fs, var);
        CodeGen.fixline(fs, line);
        k = (k >= Opcodes.MAXARG_Bx) ? 0 : k + 1;
        CodeGen.codeABx(fs, Opcodes.OP_ERRNNIL, var.info, k);
        CodeGen.fixline(fs, line);
        CodeGen.freeexp(fs, var);
    }

    // lparser.c: initglobal
    private void initglobal(Lexer ls, int nvars, int firstidx, int n, int line) {
        if (n == nvars) {  // 遍历完所有变量？
            expdesc e = new expdesc();
            int nexps = explist(ls, e);  // 读取表达式列表
            adjustAssign(ls, nvars, nexps, e);
        } else {  // 处理变量 'n'
            FuncState fs = ls.fs;
            Prototype f = fs.f;
            expdesc var = new expdesc();
            LuaString varname = getlocalvardesc(fs, firstidx + n).name;
            buildglobal(ls, varname, var);  // 在 'var' 中创建全局变量
            ls.recdepth++;
            if (ls.recdepth > 200) throw ls.syntaxError("C stack overflow");
            initglobal(ls, nvars, firstidx, n + 1, line);
            ls.recdepth--;
            checkglobal(ls, varname, line);
            storevartop(fs, var);  // 将栈顶寄存器存入变量
        }
    }

    // ===============================================================
    // break / goto（lparser.c）
    // ===============================================================

    // lparser.c: globalnames
    private void globalnames(Lexer ls, int defkind) {
        FuncState fs = ls.fs;
        int nvars = 0;
        int lastidx;
        do {  // 对每个名称
            LuaString vname = strCheckname(ls);
            int kind = getglobalattribute(ls, defkind);
            lastidx = newVarkind(ls, vname, kind);  // 预声明
            nvars++;
        } while (testnext(ls, ',') != 0);
        if (testnext(ls, '=') != 0) {  // 初始化？
            initglobal(ls, nvars, lastidx - nvars + 1, 0, ls.linenumber());
        }
        fs.nactvar += nvars;  // 激活声明
    }

    // lparser.c: globalstat
    private void globalstat(Lexer ls) {
        FuncState fs = ls.fs;
        int defkind = getglobalattribute(ls, GDKREG);
        if (testnext(ls, '*') != 0) {
            // 用 NULL 作为名称表示 '*' 条目
            newVarkind(ls, null, defkind);
            fs.nactvar++;  // 激活声明
        } else {
            // lparser.c: globalnames —— 'none' 只是普通 NAME
            //（官方测试惯用法 global none 即声明名为 none 的全局）
            globalnames(ls, defkind);
        }
    }

    // lparser.c: globalfunc
    private void globalfunc(Lexer ls, int line) {
        FuncState fs = ls.fs;
        expdesc var = new expdesc();
        expdesc b = new expdesc();
        LuaString fname = strCheckname(ls);
        newVarkind(ls, fname, GDKREG);  // 声明全局变量
        fs.nactvar++;  // 进入其作用域
        buildglobal(ls, fname, var);
        body(ls, b, 0, ls.linenumber());  // 编译并在 'b' 中返回闭包
        checkglobal(ls, fname, line);
        CodeGen.storeVar(fs, var, b);
        CodeGen.fixline(fs, line);
    }

    // lparser.c: globalstatfunc
    private void globalstatfunc(Lexer ls, int line) {
        ls.next();  // 跳过 'global'
        if (testnext(ls, Lexer.TK_FUNCTION) != 0) {
            globalfunc(ls, line);
        } else {
            globalstat(ls);
        }
    }

    // ===============================================================
    // 表达式语句 (lparser.c)
    // ===============================================================

    // lparser.c: breakstat
    private void breakstat(Lexer ls, int line) {
        FuncState fs = ls.fs;
        BlockCnt bl;
        for (bl = fs.bl; bl != null; bl = bl.previous) {
            if (bl.isloop != 0) break;  // 找到外层循环
        }
        if (bl == null) throw ls.syntaxError("break outside loop");
        bl.isloop = 2;  // 标记块有待处理的 break
        ls.next();  // 跳过 'break'
        newgotoentry(ls, Lexer.BRKN, line);
    }

    // lparser.c: gotostat
    private void gotostat(Lexer ls, int line) {
        LuaString name = strCheckname(ls);  // 标签名
        newgotoentry(ls, name, line);
    }

    // lparser.c: labelstat
    private void labelstat(Lexer ls, LuaString name, int line) {
        checknext(ls, Lexer.TK_DBCOLON);  // 跳过闭合的 '::'
        // 跳过其他无操作语句（; 和 ::label::）
        while (ls.t.token == ';' || ls.t.token == Lexer.TK_DBCOLON) {
            statement(ls);
        }
        checkrepeated(ls, name);
        createlabel(ls, name, line, blockFollow(ls, 0) ? 1 : 0);
    }

    // lparser.c: checkrepeated
    private void checkrepeated(Lexer ls, LuaString name) {
        FuncState fs = ls.fs;
        Labeldesc lb = findlabel(ls, name, fs.firstlabel);
        if (lb != null) {
            CodeGen.semError(ls, "label '" + name + "' already defined on line " + lb.line);
        }
    }

    // lparser.c: exprstat
    private void exprstat(Lexer ls) {
        FuncState fs = ls.fs;

        expdesc v = new expdesc();
        suffixedexp(ls, v);
        if (ls.t.token == '=' || ls.t.token == ',') {
            // 赋值
            restassign(ls, new LHSAssign(null, v), 1);
        } else {
            // stat -> func（调用语句）
            if (v.k != VCALL) throw ls.syntaxError("syntax error");
            // 调用语句使用无返回值：SETARG_C(*inst, 1)
            int pc = v.info;
            fs.f.code[pc] = Opcodes.SETARG_C(fs.f.code[pc], 1);
        }
    }

    // lparser.c: check_conflict
    private void checkConflict(Lexer ls, LHSAssign lh, expdesc v) {
        FuncState fs = ls.fs;
        int extra = fs.freereg;
        boolean conflict = false;
        for (; lh != null; lh = lh.prev) {
            expdesc prev = lh.v;
            if (vkisindexed(prev.k)) {
                if (prev.k == VINDEXUP) {
                    if (v.k == VUPVAL && prev.ind_t == v.info) {
                        conflict = true;
                        prev.k = VINDEXSTR;
                        prev.ind_t = extra;
                    }
                } else {
                    if (v.k == VLOCAL && prev.ind_t == v.var_ridx) {
                        conflict = true;
                        prev.ind_t = extra;
                    }
                    if (prev.k == VINDEXED && v.k == VLOCAL && prev.ind_idx == v.var_ridx) {
                        conflict = true;
                        prev.ind_idx = extra;
                    }
                }
            }
        }
        if (conflict) {
            if (v.k == VLOCAL) {
                CodeGen.codeABC(fs, Opcodes.OP_MOVE, extra, v.var_ridx, 0);
            } else {
                CodeGen.codeABC(fs, Opcodes.OP_GETUPVAL, extra, v.info, 0);
            }
            CodeGen.reserveRegs(fs, 1);
        }
    }

    // lparser.c: restassign
    private void restassign(Lexer ls, LHSAssign lh, int nvars) {
        FuncState fs = ls.fs;
        // restassign -> ',' suffixedexp restassign | '=' explist
        // v 是第一个变量（已由调用方解析）
        expdesc v = lh.v;
        if (!vkisvar(v.k)) throw ls.syntaxError("syntax error");
        checkReadonly(ls, v);
        if (testnext(ls, ',') != 0) {
            // restassign -> ',' suffixedexp restassign
            expdesc nv = new expdesc();
            suffixedexp(ls, nv);
            if (!vkisindexed(nv.k))
                checkConflict(ls, lh, nv);
            ls.recdepth++;
            if (ls.recdepth > 200) throw ls.syntaxError("C stack overflow");
            restassign(ls, new LHSAssign(lh, nv), nvars + 1);
            ls.recdepth--;
        } else {
            // restassign -> '=' explist
            checknext(ls, '=');
            expdesc e = new expdesc();
            int nexps = explist(ls, e);
            if (nexps != nvars) {
                adjustAssign(ls, nvars, nexps, e);
            } else {
                CodeGen.setOneRet(fs, e);
                CodeGen.storeVar(fs, v, e);
                return;  // 避免默认存储
            }
        }
        // 默认：将栈顶寄存器存入 v
        storevartop(fs, lh.v);
    }

    // ===============================================================
    // 表达式解析 (lparser.c)
    // ===============================================================

    // lparser.c: storevartop
    private void storevartop(FuncState fs, expdesc var) {
        expdesc e = new expdesc();
        initExp(e, VNONRELOC, fs.freereg - 1);
        CodeGen.storeVar(fs, var, e);
    }

    // lparser.c: expr
    private void expr(Lexer ls, expdesc v) {
        subexpr(ls, v, 0);
    }

    // lparser.c: subexpr
    private int subexpr(Lexer ls, expdesc v, int limit) {
        ls.recdepth++;
        if (ls.recdepth > 200) {
            ls.recdepth--;
            throw ls.syntaxError("C stack overflow");
        }
        try {
            FuncState fs = ls.fs;
            int uop = getunopr(ls.t.token);
            if (uop != OPR_NOUNOPR) {
                int line = ls.linenumber();
                ls.next();
                subexpr(ls, v, UNARY_PRIORITY);
                CodeGen.prefix(fs, uop, v, line);
            } else {
                simpleexp(ls, v);
            }
            int op = getbinopr(ls.t.token);
            expdesc v2 = null;
            while (op != OPR_NOBINOPR && PRIORITY[op][0] > limit) {
                // C 的 subexpr 将 expdesc v2 作为短生命周期栈局部变量。
                // Java 中复用同一对象；超出调用生命周期的 LHS 赋值描述符仍单独分配。
                if (v2 == null) v2 = new expdesc();
                int line = ls.linenumber();
                ls.next();
                CodeGen.infix(fs, op, v);
                int nextop = subexpr(ls, v2, PRIORITY[op][1]);
                CodeGen.posfix(fs, op, v, v2, line);
                op = nextop;
            }
            return op;
        } finally {
            ls.recdepth--;
        }
    }

    // ===============================================================
    // suffixedexp (lparser.c)
    // ===============================================================

    // lparser.c: simpleexp
    private void simpleexp(Lexer ls, expdesc v) {
        FuncState fs = ls.fs;
        switch (ls.t.token) {
            case Lexer.TK_FLT: {
                initExp(v, VKFLT, 0);
                v.nval = ls.t.r;
                ls.next();
                break;
            }
            case Lexer.TK_INT: {
                initExp(v, VKINT, 0);
                v.ival = ls.t.i;
                ls.next();
                break;
            }
            case Lexer.TK_STRING: {
                codestring(v, ls.t.ts);
                ls.next();
                break;
            }
            case Lexer.TK_NIL: {
                initExp(v, VNIL, 0);
                ls.next();
                break;
            }
            case Lexer.TK_TRUE: {
                initExp(v, VTRUE, 0);
                ls.next();
                break;
            }
            case Lexer.TK_FALSE: {
                initExp(v, VFALSE, 0);
                ls.next();
                break;
            }
            case Lexer.TK_DOTS: {
                if (!fs.f.isVararg())
                    throw ls.syntaxError("cannot use '...' outside a vararg function");
                initExp(v, VVARARG, CodeGen.codeABC(fs, Opcodes.OP_VARARG, 0, fs.f.numparams, 1, 0));
                ls.next();
                break;
            }
            case '{': {
                constructor(ls, v);
                return;
            }
            case Lexer.TK_FUNCTION: {
                ls.next();
                body(ls, v, 0, ls.linenumber());
                return;
            }
            default:
                suffixedexp(ls, v);
        }
    }

    // lparser.c: suffixedexp
    private void suffixedexp(Lexer ls, expdesc v) {
        FuncState fs = ls.fs;
        // suffixedexp -> primaryexp { '.' NAME | '[' exp ']' | ':' NAME funcargs | funcargs }
        primaryexp(ls, v);
        for (; ; ) {
            switch (ls.t.token) {
                case '.': { // 字段选择器
                    fieldsel(ls, v);
                    break;
                }
                case '[': { // '[' exp ']'
                    expdesc key = new expdesc();
                    CodeGen.exp2AnyRegup(fs, v);  // yindex 前确保 v 在寄存器或上值中
                    yindex(ls, key);
                    CodeGen.indexed(fs, v, key);
                    break;
                }
                case ':': { // ':' NAME funcargs
                    expdesc key = new expdesc();
                    ls.next();
                    codename(ls, key);
                    CodeGen.self(fs, v, key);
                    funcargs(ls, v, 1);  // nself=1：self 已将 self 推入 R[base+1]
                    break;
                }
                case '(':
                case Lexer.TK_STRING:
                case '{': { // 函数参数
                    CodeGen.exp2NextReg(fs, v);
                    funcargs(ls, v, 0);
                    break;
                }
                default:
                    return;
            }
        }
    }

    // lparser.c: primaryexp
    private void primaryexp(Lexer ls, expdesc v) {
        FuncState fs = ls.fs;
        // primaryexp -> NAME | '(' expr ')'
        switch (ls.t.token) {
            case '(': {
                int line = ls.linenumber();
                ls.next();
                expr(ls, v);
                checkMatch(ls, ')', '(', line);  // 匹配时前进过 ')'
                CodeGen.dischargeVars(fs, v);
                break;
            }
            case Lexer.TK_NAME: {
                singlevar(ls, v);
                break;
            }
            default:
                throw ls.syntaxError("unexpected symbol");
        }
    }

    // lparser.c: fieldsel
    private void fieldsel(Lexer ls, expdesc v) {
        FuncState fs = ls.fs;
        // fieldsel -> ['.' | ':'] NAME
        // 调用者（suffixedexp/funcname）尚未前进过 '.' / ':'：必须先 ls.next() 消费 '.' 再读取 NAME
        CodeGen.exp2AnyRegup(fs, v);

        ls.next();  // 跳过点号或冒号
        expdesc key = new expdesc();
        codename(ls, key);  // 读取 NAME，产生 VKSTR
        CodeGen.indexed(fs, v, key);  // luaK_indexed 将 VKSTR -> VK 通过 str2K
    }

    // lparser.c: yindex
    private void yindex(Lexer ls, expdesc v) {
        FuncState fs = ls.fs;
        // index -> '[' expr ']'
        ls.next();
        expr(ls, v);
        CodeGen.exp2Val(fs, v);
        checknext(ls, ']');  // C 使用 checknext（前进过 ']'）
    }

    // ===============================================================
    // 表构造器 (lparser.c)
    // ===============================================================

    // lparser.c: funcargs
    private void funcargs(Lexer ls, expdesc f, int nself) {
        FuncState fs = ls.fs;
        // nself = 1 若在 ':' 之后调用（方法调用）；self 已将 self 推入 R[base+1]。
        // 此时函数在 R[base]，self 在 R[base+1]，nargs 从 1 开始。
        int base = CodeGen.exp2AnyReg(fs, f);
        int nargs = nself;
        expdesc args = new expdesc();
        // lparser.c: funcargs —— 入口取 ls->linenumber（当前 token 行）：字符串/表
        // 参数的 CALL 行号即参数 token 的行；'(' 分支不再补采（C 只在入口采一次）
        int line = ls.linenumber();
        if (ls.t.token == '(') {
            ls.next();
            if (ls.t.token == ')') {
                args.k = VVOID;
            } else {
                explist(ls, args);
                if (hasmultret(args.k)) {
                    // 最后一个参数有多返回值  -  全部传给函数。
                    CodeGen.setReturns(fs, args, -1);
                    nargs = -1;  // LUA_MULTRET
                }
            }
            checkMatch(ls, ')', '(', line);
        } else if (ls.t.token == Lexer.TK_STRING) {
            codestring(args, ls.t.ts);
            ls.next();
        } else if (ls.t.token == '{') {
            constructor(ls, args);
        } else {
            throw ls.syntaxError("function arguments expected");
        }
        if (nargs != -1) {
            // 固定参数数量：将最后一个参数关闭到下一个寄存器。
            if (args.k != VVOID) {
                CodeGen.exp2NextReg(fs, args);
            }
            nargs = fs.freereg - (base + 1);
        }
        CodeGen.freeexp(fs, base);
        initExp(f, VCALL, CodeGen.codeABC(fs, Opcodes.OP_CALL, base, (nargs == -1 ? 0 : nargs + 1), 2, 0));
        CodeGen.fixline(fs, line);
        fs.freereg = base + 1;  // 调用移除函数和参数，留下 1 个结果
    }

    // lparser.c: listfield
    private void listfield(Lexer ls, ConsControl cc) {
        expr(ls, cc.v);
        cc.tostore++;
    }

    // lparser.c: recfield
    private void recfield(Lexer ls, ConsControl cc) {
        FuncState fs = ls.fs;
        int reg = fs.freereg;
        expdesc tab = new expdesc();
        expdesc key = new expdesc();
        expdesc val = new expdesc();
        if (ls.t.token == Lexer.TK_NAME) {
            codename(ls, key);
        } else {
            yindex(ls, key);
        }
        cc.nh++;
        checknext(ls, '=');
        tab.copyFrom(cc.t);
        CodeGen.indexed(fs, tab, key);
        expr(ls, val);
        CodeGen.storeVar(fs, tab, val);
        fs.freereg = reg;  // 恢复寄存器
    }

    // lparser.c: codename
    private void codename(Lexer ls, expdesc e) {
        codestring(e, strCheckname(ls));
    }

    // lparser.c: closelistfield
    private void closelistfield(FuncState fs, ConsControl cc) {
        if (cc.tostore == 0) return;
        CodeGen.exp2NextReg(fs, cc.v);
        cc.v.k = VVOID;
        if (cc.tostore >= cc.maxtostore) {
            CodeGen.setList(fs, cc.t.info, cc.na, cc.tostore);
            cc.na += cc.tostore;
            cc.tostore = 0;
        }
    }

    // lparser.c: lastlistfield
    private void lastlistfield(FuncState fs, ConsControl cc) {
        if (cc.tostore == 0) return;
        if (hasmultret(cc.v.k)) {
            CodeGen.setReturns(fs, cc.v, -1);
            CodeGen.setList(fs, cc.t.info, cc.na, -1);
            cc.na--;
        } else {
            if (cc.v.k != VVOID)
                CodeGen.exp2NextReg(fs, cc.v);
            CodeGen.setList(fs, cc.t.info, cc.na, cc.tostore);
        }
        cc.na += cc.tostore;
    }

    // lparser.c: maxtostore
    private int maxtostore(FuncState fs) {
        int free = Opcodes.MAX_FSTACK - fs.freereg;
        if (free >= 160) return free / 5;
        if (free >= 80) return 10;
        return 1;
    }

    // lparser.c: field
    private void field(Lexer ls, ConsControl cc) {
        // TK_NAME 可能是 recfield（NAME = exp）或 listfield（以 NAME 开头的表达式）。
        // 其他 token（TK_STRING、TK_INT、'{' 等）都是 listfield。
        switch (ls.t.token) {
            case Lexer.TK_NAME: {
                if (ls.lookahead() != '=') {
                    listfield(ls, cc);
                } else {
                    recfield(ls, cc);
                }
                break;
            }
            case '[': {
                recfield(ls, cc);
                break;
            }
            default: {
                listfield(ls, cc);
                break;
            }
        }
    }

    // lparser.c: constructor
    private void constructor(Lexer ls, expdesc t) {
        FuncState fs = ls.fs;
        int line = ls.linenumber();
        // luaK_codevABCk(fs, OP_NEWTABLE, 0, 0, 0, 0)  -  使用 vABCk 格式（非普通 codeABC）
        int pc = CodeGen.codevABCk(fs, Opcodes.OP_NEWTABLE, 0, 0, 0, 0);
        CodeGen.code(fs, 0);  // 额外参数的空间
        ConsControl cc = new ConsControl();
        cc.na = 0;
        cc.nh = 0;
        cc.tostore = 0;
        cc.t = t;
        initExp(t, VNONRELOC, fs.freereg);  // 表在栈顶
        CodeGen.reserveRegs(fs, 1);
        initExp(cc.v, VVOID, 0);  // 尚无值
        checknext(ls, '{');
        cc.maxtostore = maxtostore(fs);
        do {
            if (ls.t.token == '}') break;
            if (cc.v.k != VVOID) closelistfield(fs, cc);
            field(ls, cc);
            // MAX_CNST 是 INT_MAX/2  -  实际无限制。跳过检查。
        } while (testnext(ls, ',') != 0 || testnext(ls, ';') != 0);
        checkMatch(ls, '}', '{', line);
        lastlistfield(fs, cc);
        CodeGen.setTableSize(fs, pc, t.info, cc.na, cc.nh);
    }

    // ===============================================================
    // 运算符表 (lparser.c)
    // ===============================================================

    // lparser.c: getunopr
    private int getunopr(int t) {
        return switch (t) {
            case Lexer.TK_NOT -> OPR_NOT;
            case '-' -> OPR_MINUS;
            case '~' -> OPR_BNOT;
            case '#' -> OPR_LEN;
            default -> OPR_NOUNOPR;
        };
    }

    // lparser.c: getbinopr
    private int getbinopr(int t) {
        return switch (t) {
            case '+' -> OPR_ADD;
            case '-' -> OPR_SUB;
            case '*' -> OPR_MUL;
            case '%' -> OPR_MOD;
            case '^' -> OPR_POW;
            case '/' -> OPR_DIV;
            case Lexer.TK_IDIV -> OPR_IDIV;
            case '&' -> OPR_BAND;
            case '|' -> OPR_BOR;
            case '~' -> OPR_BXOR;
            case Lexer.TK_SHL -> OPR_SHL;
            case Lexer.TK_SHR -> OPR_SHR;
            case Lexer.TK_CONCAT -> OPR_CONCAT;
            case Lexer.TK_NE -> OPR_NE;
            case Lexer.TK_EQ -> OPR_EQ;
            case '<' -> OPR_LT;
            case Lexer.TK_LE -> OPR_LE;
            case '>' -> OPR_GT;
            case Lexer.TK_GE -> OPR_GE;
            case Lexer.TK_AND -> OPR_AND;
            case Lexer.TK_OR -> OPR_OR;
            default -> OPR_NOBINOPR;
        };
    }

    // lparser.c: setvararg
    private void setvararg(FuncState fs) {

        fs.f.setVararg();

        CodeGen.codeABC(fs, Opcodes.OP_VARARGPREP, 0, 0, 0, 0);
    }

    // lparser.c: mainfunc
    private void mainfunc(Lexer ls, FuncState fs) {
        BlockCnt bl = new BlockCnt();
        openFunc(ls, fs, bl);
        setvararg(fs);  // 主函数始终是可变参数
        // env = allocupvalue(fs)；填充 LUA_ENV 描述符
        Prototype.Upvaldesc env = allocupvalue(fs);
        env.name = Lexer.ENVN;
        env.idx = 0;
        env.instack = true;
        env.kind = VDKREG;
        ls.next();
        statlist(ls);
        check(ls, Lexer.TK_EOS);
        closeFunc(ls);
    }

    // ===============================================================
    // mainfunc 与入口 (lparser.c)
    // ===============================================================

    private record LHSAssign(LHSAssign prev, expdesc v) {
    }

    // ConsControl  -  表构造器解析状态跟踪
    static final class ConsControl {
        expdesc v = new expdesc();   // 最后一个列表项
        expdesc t;                    // 表描述符
        int nh, na, tostore, maxtostore;
    }
}
