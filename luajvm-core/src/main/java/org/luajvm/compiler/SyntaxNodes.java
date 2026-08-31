// ref: lparser.h/lobject.h
// diff: expdesc用类非栈上结构体需copyFrom; Vardesc.ridx用int（C 的 lu_byte 寄存器索引超 127 会溢出）; Vardesc.ctc用expdesc非TValue; KCache类型化HashMap; FuncState.kcache/foldV1/foldV2/foldRes为Java特有优化; Dyndata.actvar用ArrayList
package org.luajvm.compiler;

import org.luajvm.core.LuaString;
import org.luajvm.core.LuaValue;
import org.luajvm.core.Prototype;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;

public final class SyntaxNodes {
    // -- expkind (C: lparser.h:expkind) --
    public static final int
            VVOID = 0, VNIL = 1, VTRUE = 2, VFALSE = 3, VK = 4,
            VKFLT = 5, VKINT = 6, VKSTR = 7, VNONRELOC = 8,
            VLOCAL = 9, VVARGVAR = 10, VGLOBAL = 11, VUPVAL = 12, VCONST = 13,
            VINDEXED = 14, VVARGIND = 15, VINDEXUP = 16, VINDEXI = 17, VINDEXSTR = 18,
            VJMP = 19, VRELOC = 20, VCALL = 21, VVARARG = 22;
    // -- Vardesc.kind (C: lparser.h:Vardesc.kind) --
    public static final int
            VDKREG = 0, RDKCONST = 1, RDKVAVAR = 2, RDKTOCLOSE = 3, RDKCTC = 4,
            GDKREG = 5, GDKCONST = 6;
    // -- BinOpr/UnOpr (C: lparser.h) --
    public static final int
            OPR_ADD = 0, OPR_SUB = 1, OPR_MUL = 2, OPR_MOD = 3,
            OPR_POW = 4, OPR_DIV = 5, OPR_IDIV = 6,
            OPR_BAND = 7, OPR_BOR = 8, OPR_BXOR = 9,
            OPR_SHL = 10, OPR_SHR = 11, OPR_CONCAT = 12,
            OPR_NE = 13, OPR_EQ = 14, OPR_LT = 15, OPR_LE = 16,
            OPR_GT = 17, OPR_GE = 18, OPR_AND = 19, OPR_OR = 20,
            OPR_NOBINOPR = 21,
            OPR_MINUS = 0, OPR_BNOT = 1, OPR_NOT = 2, OPR_LEN = 3,
            OPR_NOUNOPR = -1;

    private SyntaxNodes() {
    }

    // lparser.h/lobject.h: vkisvar
    public static boolean vkisvar(int k) {
        return k >= VLOCAL && k <= VINDEXSTR;
    }

    // lparser.h/lobject.h: vkisindexed
    public static boolean vkisindexed(int k) {
        return k >= VINDEXED && k <= VINDEXSTR;
    }

    // lparser.h/lobject.h: varinreg
    public static boolean varinreg(Vardesc v) {
        return v.kind <= RDKTOCLOSE;
    }

    // lparser.h/lobject.h: varglobal
    public static boolean varglobal(Vardesc v) {
        return v.kind >= GDKREG;
    }

    // -- expdesc (C: lparser.h:expdesc) --
    public static final class expdesc {
        public int k;
        public long ival;
        public double nval;
        public LuaString strval;
        public int info;
        public int ind_t;
        public int ind_idx;
        public boolean ind_ro;
        public int ind_keystr = -1;
        public int var_ridx;  // java-only: int替代 C 的 lu_byte——寄存器索引超 127 会溢出
        public int var_vidx;
        public int t = Opcodes.NO_JUMP;
        public int f = Opcodes.NO_JUMP;

        // lparser.c: init_exp
        public void init(int kind, int i) {
            this.k = kind;
            this.info = i;
            this.t = Opcodes.NO_JUMP;
            this.f = Opcodes.NO_JUMP;
        }

        // lparser.h/lobject.h: copyFrom
        public void copyFrom(expdesc o) {
            this.k = o.k;
            this.ival = o.ival;
            this.nval = o.nval;
            this.strval = o.strval;
            this.info = o.info;
            this.ind_t = o.ind_t;
            this.ind_idx = o.ind_idx;
            this.ind_ro = o.ind_ro;
            this.ind_keystr = o.ind_keystr;
            this.var_ridx = o.var_ridx;
            this.var_vidx = o.var_vidx;
            this.t = o.t;
            this.f = o.f;
        }
    }

    // -- Vardesc (C: lparser.h:Vardesc) --
    public static final class Vardesc {
        public int kind;
        public int ridx;  // java-only: int替代 C 的 lu_byte——寄存器索引超 127 会溢出
        public int pidx;
        public LuaString name;
        // java-only: ctc用expdesc非TValue，生成Proto.k时转换
        public expdesc ctc;
    }

    // -- Labeldesc (C: lparser.h:Labeldesc) --
    public static final class Labeldesc {
        public LuaString name;
        public int pc, line;
        public short nactvar;
        public byte close;

        public Labeldesc() {
        }

        public Labeldesc(LuaString name, int pc, int line, short nactvar, byte close) {
            this.name = name;
            this.pc = pc;
            this.line = line;
            this.nactvar = nactvar;
            this.close = close;
        }
    }

    // -- Labellist (C: lparser.h:Labellist) --
    public static final class Labellist {
        // java-only: 惰性初始化 - 多数短片段无 goto/label，省 ArrayList 分配
        public List<Labeldesc> arr;

        public List<Labeldesc> arr() {
            if (arr == null) arr = new ArrayList<>();
            return arr;
        }
    }

    // -- Dyndata (C: lparser.h:Dyndata) --
    public static final class Dyndata {
        public final Labellist gt = new Labellist();
        public final Labellist label = new Labellist();
        // java-only: 惰性初始化 - 多数短片段仅少量局部变量，省 ArrayList 分配
        public List<Vardesc> actvar;
        public int actvarN = 0;  // java-only: ArrayList+逻辑大小(C用动态数组+n计数器)

        public List<Vardesc> actvar() {
            if (actvar == null) actvar = new ArrayList<>();
            return actvar;
        }
    }

    // -- FuncState (C: lparser.h:FuncState) --
    public static final class FuncState {
        // java-only: kcache类型化表避免Object key分派
        public final KCache kcache = new KCache();
        public Prototype f;
        public FuncState prev;
        public Lexer ls;
        public BlockCnt bl;
        public int pc, lasttarget, previousline;
        public int nk, np, nabslineinfo;
        public int firstlocal, firstlabel;
        public short ndebugvars, nactvar;
        public int nups;
        public byte iwthabs, needclose;
        public int freereg;
        // java-only: foldV1/foldV2/foldRes复用避免常量折叠热路径分配
        // java-only: 惰性初始化 - 无常量折叠的片段(如 load("return 1"))不分配 expdesc
        public expdesc foldV1;
        public expdesc foldV2;
        public expdesc foldRes;

        public FuncState() {
        }

        // java-only: 重置所有字段以支持跨 parse() 复用（对齐 C 的 FuncState 栈分配语义）。
        //   清空 kcache 的 HashMap（保留实例供下次复用），foldV1/V2/Res 置 null（惰性重建）。
        //   清除 Prototype 引用 (f) - Prototype 是 tracked 对象，不参与池化。
        public void reset() {
            f = null;
            prev = null;
            ls = null;
            bl = null;
            pc = 0;
            lasttarget = 0;
            previousline = 0;
            nk = 0;
            np = 0;
            nabslineinfo = 0;
            firstlocal = 0;
            firstlabel = 0;
            ndebugvars = 0;
            nactvar = 0;
            nups = 0;
            iwthabs = 0;
            needclose = 0;
            freereg = 0;
            // java-only: kcache 三张表按高水位回收。FuncState 经 Parser.FS_POOL 复用，
            //   而 HashMap 容量只增不减：编译过常量极多的函数后，长大的表会随池流到后续小函数并长期滞留。
            //   故超阈值时置 null 交回惰性重建；阈值内仍 clear() 复用。
            kcache.strings = recycle(kcache.strings);
            kcache.integers = recycle(kcache.integers);
            kcache.floats = recycle(kcache.floats);
            kcache.falseIdx = -1;
            kcache.trueIdx = -1;
            kcache.nilIdx = -1;
            kcache.zeroFloatIdx = -1;
            foldV1 = null;
            foldV2 = null;
            foldRes = null;
        }
    }

    /**
     * java-only：kcache 表的池化回收阈值（条目数）。
     *
     * <p>超过此 size 的表在 {@code FuncState.reset()} 时丢弃而非 clear()，避免长大的
     * Node[] 随池化的 FuncState 长期滞留。512 条目对应约 1024 槽 Node[]（约 4KB），
     * 远大于常规函数的常量数（多数 &lt; 32），故常规路径仍走免分配的 clear()。
     */
    private static final int KCACHE_RECYCLE_MAX = 512;

    // java-only: A/B 开关 - -Dluajvm.kcacherecycle=false 时一律 clear()、不按高水位丢弃。
    private static final boolean KCACHE_RECYCLE =
            System.getProperty("luajvm.kcacherecycle") == null ||
                    Boolean.parseBoolean(System.getProperty("luajvm.kcacherecycle"));

    /** java-only：表过大则丢弃（返回 null 交由惰性初始化重建），否则清空复用。 */
    private static <K> HashMap<K, Integer> recycle(HashMap<K, Integer> m) {
        if (m == null) return null;
        if (KCACHE_RECYCLE && m.size() > KCACHE_RECYCLE_MAX) return null;
        m.clear();
        return m;
    }

    public static final class KCache {
        // java-only: 惰性初始化 - 多数短 load() 片段仅 1-2 种常量类型，
        //   省 1-2 个 HashMap 分配/函数。对齐 C 的 KCache 按需查表语义。
        public HashMap<LuaString, Integer> strings;
        public HashMap<Long, Integer> integers;
        public HashMap<Double, Integer> floats;
        public int falseIdx = -1;
        public int trueIdx = -1;
        public int nilIdx = -1;
        public int zeroFloatIdx = -1;

        // lparser.h/lobject.h: getStringIndex
        public int getStringIndex(LuaString key, LuaValue[] constants) {
            if (strings == null) return -1;
            Integer cached = strings.get(key);
            if (cached == null) return -1;
            int idx = cached;
            LuaValue v = idx >= 0 && idx < constants.length ? constants[idx] : null;
            return v != null && v.raweq(key) ? idx : -1;
        }

        // lparser.h/lobject.h: putString
        public void putString(LuaString key, int idx) {
            if (strings == null) strings = new HashMap<>();
            strings.put(key, idx);
        }

        // lparser.h/lobject.h: getIntegerIndex
        public int getIntegerIndex(long key, LuaValue[] constants) {
            if (integers == null) return -1;
            Integer cached = integers.get(key);
            if (cached == null) return -1;
            int idx = cached;
            LuaValue v = idx >= 0 && idx < constants.length ? constants[idx] : null;
            return v != null && v.isinteger() && v.tolong() == key ? idx : -1;
        }

        // lparser.h/lobject.h: putInteger
        public void putInteger(long key, int idx) {
            if (integers == null) integers = new HashMap<>();
            integers.put(key, idx);
        }

        // lparser.h/lobject.h: getFloatIndex
        public int getFloatIndex(long keyBits, double value, LuaValue[] constants) {
            if (floats == null) return -1;
            Double key = Double.longBitsToDouble(keyBits);
            Integer cached = floats.get(key);
            if (cached == null) return -1;
            int idx = cached;
            LuaValue v = idx >= 0 && idx < constants.length ? constants[idx] : null;
            // 严格标签校验（同 getIntegerIndex 的 isinteger）：isnumber() 对数字字符串亦为
            //   true，但 LuaString 不覆写 todouble()（继承返回 0），桶位被字符串常量占据时
            //   0.0 之类浮点会错误复用字符串槽位，指令加载出字符串而非数值
            if (v != null && v.isfloat()) {
                double dv = v.todouble();
                if (Double.doubleToLongBits(dv) == Double.doubleToLongBits(value)) return idx;
            }
            return -1;
        }

        // lparser.h/lobject.h: putFloat
        public void putFloat(long keyBits, int idx) {
            if (floats == null) floats = new HashMap<>();
            floats.put(Double.longBitsToDouble(keyBits), idx);
        }
    }

    // -- BlockCnt (C: lparser.h:BlockCnt) --
    public static final class BlockCnt {
        public static final byte LOOP = 1;
        public BlockCnt previous;
        public int firstlabel, firstgoto;
        public short nactvar;
        public byte upval;
        public byte isloop;
        public boolean insidetbc;


    }
}
