// ref: ltablib.c
// diff: 内部类闭包代替 luaL_Reg；geti/seti 复刻 lua_geti/lua_seti 语义；LuaBuffer 代替 luaL_Buffer；int 索引代替 ptrdiff_t
package org.luajvm.lib;

import org.luajvm.core.Globals;
import org.luajvm.core.LuaError;
import org.luajvm.core.LuaErrors;
import org.luajvm.core.LuaFunction;
import org.luajvm.core.LuaGC;
import org.luajvm.core.LuaInteger;
import org.luajvm.core.LuaString;
import org.luajvm.core.LuaTable;
import org.luajvm.core.LuaThread;
import org.luajvm.core.LuaValue;
import org.luajvm.core.Metamethod;
import org.luajvm.core.Varargs;
import org.luajvm.vm.LuaCall;
import org.luajvm.vm.LuaCompare;
import org.luajvm.vm.LuaIndex;
import org.luajvm.vm.LuaVM;

public class TableLib extends LuaFunction {
    private static final int LUA_MAXSTACK_UNPACK_RESULTS = 999990;
    private static final int TAB_R = 1;
    private static final int TAB_W = 2;
    private static final int TAB_L = 4;
    private static final int TAB_RW = TAB_R | TAB_W;

    // ltablib.c: checktab
    private static LuaValue checktab(Varargs args, int arg, int what) {
        LuaValue value = args.arg(arg);
        if (value.istable()) return value;
        LuaValue mt = value.getmetatable();
        if (mt != null && !mt.isnil()
                && (((what & TAB_R) == 0) || hasField(mt, LuaValue.INDEX))
                && (((what & TAB_W) == 0) || hasField(mt, LuaValue.NEWINDEX))
                // ltablib.c: 字符串无 __len 也有长度
                && (((what & TAB_L) == 0) || value.isstring() || hasField(mt, LuaValue.LEN))) {
            return value;
        }
        LuaErrors.typeError(arg, args, "table");
        return LuaValue.NIL;
    }

    // ltablib.c: hasField
    private static boolean hasField(LuaValue table, LuaValue key) {
        LuaValue value = table.rawget(key);
        return value != null && !value.isnil();
    }


    // ltablib.c: indexMetamethod
    private static LuaValue indexMetamethod(LuaTable table) {
        LuaValue mt = table.getmetatable();
        if (mt == null || mt.isnil()) return null;
        if (mt instanceof LuaTable mtTable) {
            LuaValue mm = mtTable.hashGet(Metamethod.INDEX.tag);
            return mm != null && !mm.isnil() ? mm : null;
        }
        LuaValue mm = mt.rawget(LuaValue.INDEX);
        return mm != null && !mm.isnil() ? mm : null;
    }

    // ltablib.c: newindexMetamethod
    private static LuaValue newindexMetamethod(LuaTable table) {
        LuaValue mt = table.getmetatable();
        if (mt == null || mt.isnil()) return null;
        if (mt instanceof LuaTable mtTable) {
            LuaValue mm = mtTable.hashGet(Metamethod.NEWINDEX.tag);
            return mm != null && !mm.isnil() ? mm : null;
        }
        LuaValue mm = mt.rawget(LuaValue.NEWINDEX);
        return mm != null && !mm.isnil() ? mm : null;
    }

    // ltablib.c: luaV_fastgeti
    private static LuaValue geti(LuaValue t, long idx) {
        if (idx >= Integer.MIN_VALUE && idx <= Integer.MAX_VALUE) {
            return geti(t, (int) idx);
        }
        if (t instanceof LuaTable table) {
            LuaValue v = table.getInt((int) idx);
            if (!v.isnil() || indexMetamethod(table) == null) return v;
        }
        return LuaIndex.finishGeti(t, idx);
    }

    // geti (IdxT)
    private static LuaValue geti(LuaValue t, int idx) {
        if (t instanceof LuaTable table) {
            LuaValue v = table.getInt(idx);
            if (!v.isnil() || indexMetamethod(table) == null) return v;
        }
        return LuaIndex.finishGeti(t, idx);
    }

    // ltablib.c: luaV_fastseti
    private static void seti(LuaValue t, long idx, LuaValue v) {
        if (idx >= Integer.MIN_VALUE && idx <= Integer.MAX_VALUE) {
            seti(t, (int) idx, v);
            return;
        }
        if (t instanceof LuaTable table) {
            LuaValue old = table.getInt(idx);
            if (!old.isnil() || newindexMetamethod(table) == null) {
                table.setEntry(LuaInteger.valueOf(idx), v);
                return;
            }
        }
        LuaIndex.finishSeti(t, idx, v);
    }

    // seti (IdxT)
    private static void seti(LuaValue t, int idx, LuaValue v) {
        if (t instanceof LuaTable table) {
            LuaValue old = table.getInt(idx);
            if (!old.isnil() || newindexMetamethod(table) == null) {
                table.setInt(idx, v);
                return;
            }
        }
        LuaIndex.finishSeti(t, idx, v);
    }

    // ltablib.c: aux_getn
    private static long auxGetN(Varargs args, int arg, int what) {
        return luaLLen(checktab(args, arg, what | TAB_L));
    }

    // lauxlib.c: luaL_len
    static long luaLLen(LuaValue t) {
        LuaValue r = t.len();
        if (r.isinteger()) return r.checklong();
        // 尝试转换为整数
        if (r.isnumber()) {
            return r.checklong();
        }
        LuaErrors.error("object length is not an integer");
        return 0;
    }

    // ltablib.c: luaopen_table
    @Override
    public Varargs call(Varargs args) {
        LuaValue modname = args.arg1();
        LuaValue env = args.arg(2);
        LuaTable table = new LuaTable();
        table.set("concat", new ConcatFn());
        table.set("insert", new InsertFn());
        table.set("pack", new PackFn());
        table.set("unpack", new UnpackFn());
        table.set("remove", new RemoveFn());
        table.set("move", new MoveFn());
        table.set("sort", new SortFn());
        table.set("create", new CreateFn());
        table.set("clear", new ClearFn());
        env.set("table", table);
        if (!env.get("package").isnil()) env.get("package").get("loaded").set("table", table);
        return table;
    }

    // ltablib.c: tconcat
    static class ConcatFn extends LuaFunction {
        public Varargs call(Varargs args) {
            LuaValue t = checktab(args, 1, TAB_R | TAB_L);
            LuaString sepStr = args.arg(2).isnil() ? LuaString.newStr("") : LuaErrors.checkStr(args, 2);
            byte[] sep = sepStr.contents;
            int sepOff = 0;
            int sepLen = sepStr.shrlen;
            long i = args.optlong(3, 1);

            long last = args.arg(4).isnil() ? luaLLen(t) : LuaErrors.checkLong(args, 4);
            LuaBuffer buffer = new LuaBuffer(ownerGlobals);
            try {

                for (; i < last; i++) {
                    LuaValue v = geti(t, i);
                    // ltablib.c: : lua_isstring = ttisstring || cvt2str
                    if (!v.isstring() && !v.isnumber())
                        LuaErrors.error("invalid value (" + v.typeName() + ") at index " + i + " in table for 'concat'");
                    LuaString ls = v.strValue();
                    buffer.add(ls.contents, 0, ls.shrlen);
                    if (sepLen > 0) buffer.add(sep, sepOff, sepLen);
                }
                if (i == last) {
                    LuaValue v = geti(t, i);
                    if (!v.isstring() && !v.isnumber())
                        LuaErrors.error("invalid value (" + v.typeName() + ") at index " + i + " in table for 'concat'");
                    LuaString ls = v.strValue();
                    buffer.add(ls.contents, 0, ls.shrlen);
                }
                return buffer.result();
            } finally {
                buffer.close();
            }
        }

        // java-only: callOnStack（ltablib.c: tconcat）—— 直接从栈读参数，消除 precallC 的
        //   VarargsPair（narg>=2）/VarargsArray（narg>3）分配。快路径仅处理 LuaTable +
        //   无元表 + sep 为 string/nil + i/last 为 integer/nil；其余回退 call(Varargs) 走通用路径。
        @Override
        public int callOnStack(LuaThread L, int func, int narg) {
            if (!LuaFunction.LIB_CALLONSTACK) return -1;
            if (narg < 1 || narg > 4) return -1;
            LuaValue tv = L.stack[func + 1];
            if (tv == null || !(tv instanceof LuaTable t)) return -1;
            if (t.metatable != null) return -1;  // 有元表须走 checktab/geti 元方法路径
            // arg2: sep（可选，默认 ""）
            byte[] sep = null;
            int sepOff = 0, sepLen = 0;
            if (narg >= 2) {
                LuaValue sv = L.stack[func + 2];
                if (sv == null) return -1;
                if (sv.isnil()) { /* sep stays null */ } else if (sv instanceof LuaString ls) {
                    sep = ls.contents;
                    sepLen = ls.shrlen;
                } else return -1;  // 非字符串，走通用路径 luaL_optlstring 报错
            }
            // arg3: i（可选，默认 1）
            long i = 1;
            if (narg >= 3) {
                LuaValue iv = L.stack[func + 3];
                if (iv == null) return -1;
                if (iv.isnil()) { /* i stays 1 */ } else if (iv.isinteger()) {
                    i = iv.tolong();
                } else return -1;  // 非整数，走通用路径 luaL_optinteger 报错/转换
            }
            // arg4: last（可选，默认 luaLLen(t)）
            long last = t.rawlen();  // 无元表 ⇒ rawlen == luaLLen
            if (narg >= 4) {
                LuaValue lv = L.stack[func + 4];
                if (lv == null) return -1;
                if (lv.isnil()) { /* last stays rawlen */ } else if (lv.isinteger()) {
                    last = lv.tolong();
                } else return -1;  // 非整数，走通用路径
            }
            LuaBuffer buffer = new LuaBuffer(L.l_G);
            try {
                for (; i < last; i++) {
                    LuaValue v = geti(t, i);
                    if (!v.isstring() && !v.isnumber())
                        LuaErrors.error("invalid value (" + v.typeName() + ") at index " + i + " in table for 'concat'");
                    LuaString ls = v.strValue();
                    buffer.add(ls.contents, 0, ls.shrlen);
                    if (sepLen > 0) buffer.add(sep, sepOff, sepLen);
                }
                if (i == last) {
                    LuaValue v = geti(t, i);
                    if (!v.isstring() && !v.isnumber())
                        LuaErrors.error("invalid value (" + v.typeName() + ") at index " + i + " in table for 'concat'");
                    LuaString ls = v.strValue();
                    buffer.add(ls.contents, 0, ls.shrlen);
                }
                L.stack[L.top++] = buffer.result();
                return 1;
            } finally {
                buffer.close();
            }
        }
    }

    // ltablib.c: luaL_Buffer
    // java-only: 无法复用 C 的栈上 UBox，仅保留可观察语义
    private static final class LuaBuffer {

        private static final int LUAL_BUFFERSIZE = 23;
        private byte[] bytes = new byte[LUAL_BUFFERSIZE];
        private final Globals globals;
        private int n = 0;
        private int trackedSize = 0;

        LuaBuffer(Globals globals) {
            this.globals = globals;
        }

        void add(byte[] src, int off, int len) {
            if (len <= 0) return;
            prepare(len);
            System.arraycopy(src, off, bytes, n, len);
            n += len;
        }

        // ltablib.c: luaL_pushresult
        // java diff: C 经 lua_pushexternalstring 复用缓冲内存做最终字符串。
        // Java 不能直接引用缓冲，故先释放缓冲的跟踪内存，再创建最终字符串 -
        // 任意时刻跟踪内存要么是缓冲要么是最终字符串，不会并存（对齐 C 的 pushresult 复用缓冲）。
        LuaString result() {
            if (trackedSize > 0) {
                if (globals != null) LuaGC.free(globals, trackedSize);
                trackedSize = 0;
            }
            // 字节数组大小恰好匹配则直接复用，否则裁剪到精确大小
            // （类似 C 的 resizebox 到内容大小）
            if (bytes.length == n) {
                return LuaString.valueOfOwned(bytes, n);
            }
            byte[] out = new byte[n];
            System.arraycopy(bytes, 0, out, 0, n);
            return LuaString.valueOfOwned(out);
        }

        void close() {
            if (trackedSize > 0) {
                if (globals != null) LuaGC.free(globals, trackedSize);
                trackedSize = 0;
            }
        }

        private void prepare(int sz) {
            if (bytes.length - n >= sz) return;
            int newSize = newBufferSize(sz);
            int delta = newSize - trackedSize;
            if (delta > 0 && globals != null) LuaGC.checkMemory(globals, delta);
            byte[] newBytes = new byte[newSize];
            System.arraycopy(bytes, 0, newBytes, 0, n);
            bytes = newBytes;
            if (delta > 0 && globals != null) LuaGC.commitRealloc(globals, trackedSize, newSize);
            else if (delta < 0 && globals != null) LuaGC.free(globals, -delta);
            trackedSize = newSize;
        }

        private int newBufferSize(int sz) {
            long need = (long) n + sz + 1;
            if (need > Integer.MAX_VALUE) LuaErrors.error("resulting string too large");
            long newSize = bytes.length;
            if (newSize <= Integer.MAX_VALUE / 3L * 2L) newSize += newSize >> 1;
            if (newSize < need) newSize = need;
            if (newSize > Integer.MAX_VALUE) LuaErrors.error("resulting string too large");
            return (int) newSize;
        }
    }

    // ltablib.c: tinsert
    static class InsertFn extends LuaFunction {
        public Varargs call(Varargs args) {
            int narg = args.narg();
            // -- 快路径：table.insert(t, v) 追加到无元表普通表的末尾 --
            // metatable==null ⇒ checktab 字段检查/#t 装箱拆箱/seti 探旧值均多余，
            // pos = rawlen()+1 直接写，语义与通用路径逐位等价。
            if (narg == 2 && args.arg(1) instanceof LuaTable tbl && tbl.metatable == null) {
                tbl.setInt(tbl.rawlen() + 1, args.arg(2));
                return LuaValue.NONE;
            }
            LuaValue t = checktab(args, 1, TAB_RW | TAB_L);
            long pos;
            LuaValue value;

            long e = auxGetN(args, 1, TAB_RW) + 1;
            if (narg == 2) {
                pos = e;
                value = args.arg(2);
            } else if (narg == 3) {
                pos = LuaErrors.checkLong(args, 2);

                if (!(pos - 1 >= 0 && pos - 1 < e))
                    LuaErrors.argError(2, "position out of bounds");

                for (long i = e; i > pos; i--)
                    seti(t, i, geti(t, i - 1));
                value = args.arg(3);
            } else {
                return LuaErrors.error("wrong number of arguments to 'insert'");
            }

            seti(t, pos, value);
            return LuaValue.NONE;
        }

        // java-only: callOnStack（ltablib.c: tinsert）—— 直接从栈读参数，消除 precallC 的
        //   VarargsPair（2 参）/VarargsArray（3 参）分配。仅处理 LuaTable + 无元表；
        //   其余回退 call(Varargs) 走通用路径。
        @Override
        public int callOnStack(LuaThread L, int func, int narg) {
            if (narg < 2 || narg > 3) return -1;
            LuaValue tv = L.stack[func + 1];
            if (tv == null || !(tv instanceof LuaTable t)) return -1;
            if (t.metatable != null) return -1;  // 有元表须走 checktab 路径
            int size = t.rawlen();  // 无元表时 #t = rawlen
            if (narg == 2) {
                // table.insert(t, v)  -  追加到末尾
                LuaValue v = L.stack[func + 2];
                if (v == null) return -1;
                t.setInt(size + 1, v);
                return 0;  // 无返回值
            }
            // narg == 3: table.insert(t, pos, v)
            LuaValue pv = L.stack[func + 2];
            if (pv == null || !pv.isinteger()) return -1;
            long pos = pv.tolong();
            long e = size + 1;
            if (!(pos - 1 >= 0 && pos - 1 < e)) return -1;  // 越界，走通用路径报错
            // 向后平移元素：t[e] = t[e-1], ..., t[pos+1] = t[pos]
            // 无元表 ⇒ 直接用 getInt/setInt，跳过 geti/seti 的元方法检查
            for (long i = e; i > pos; i--)
                t.setInt((int) i, t.getInt((int) (i - 1)));
            t.setInt((int) pos, L.stack[func + 3]);
            return 0;
        }
    }

    // ltablib.c: tpack
    static class PackFn extends LuaFunction {
        public Varargs call(Varargs args) {
            LuaTable t = LuaValue.tableOf();
            for (int i = 1; i <= args.narg(); i++) t.setEntry(LuaInteger.valueOf(i), args.arg(i));
            t.set("n", LuaInteger.valueOf(args.narg()));
            return t;
        }
    }

    // ltablib.c: tunpack
    static class UnpackFn extends LuaFunction {
        // lua_checkstack 语义的 Java 版
        // java diff: C 的 lua_checkstack 返回 0 表示失败；Java 版返回 boolean，失败返回 false 而非抛异常
        // ldo.c: luaD_growstack checks needed=top+n against MAXSTACK(1000000)
        private static boolean luaCheckStackUnpack(LuaThread L, int n) {
            if (L.top + n > 1000000) return false;
            if (L.stack_last - L.top > n) return true;
            try {
                LuaVM.checkStack(L, n);
                return true;
            } catch (LuaError e) {
                return false;
            }
        }

        public Varargs call(Varargs args) {
            // ltablib.c: tunpack  -  len = aux_getn(L, 1, TAB_R)
            long len = auxGetN(args, 1, TAB_R);
            LuaValue t = args.arg(1);
            long i = args.arg(2).isnil() ? 1 : LuaErrors.checkLong(args, 2);

            long j = args.arg(3).isnil() ? len : LuaErrors.checkLong(args, 3);

            if (i > j) return LuaValue.NONE;

            long n;
            try {
                n = Math.addExact(Math.subtractExact(j, i), 1);
            } catch (ArithmeticException e) {
                LuaErrors.error("too many results to unpack");
                return LuaValue.NONE;
            }
            // ltablib.c: tunpack —— n >= INT_MAX || !lua_checkstack(L, n)
            // java diff: C 逐个结果压栈，Java 用惰性 TableRangeVarargs，但两者都必须先检查
            // lua_checkstack（C 尝试增长栈，n > 1000000 时返回 0，Java 必须同样处理）
            {
                Globals g = ownerGlobals;
                if (g != null && g.running != null) {
                    LuaThread L = g.running;
                    if (n >= Integer.MAX_VALUE || !luaCheckStackUnpack(L, (int) n)) {
                        LuaErrors.error("too many results to unpack");
                    }
                }
            }
            int count = (int) n;
            return new TableRangeVarargs(t, i, count);
        }

        // java-only: callOnStack  -  ltablib.c tunpack  -  解包表元素到栈上
        // java diff: 必须保存/恢复 L.top - luaLLen/geti 可能触发元方法，
        // 经 callLua 修改 L.top（C 的 aux_getn/luaT_gettmbyobj 用独立栈帧）
        @Override
        public int callOnStack(LuaThread L, int func, int narg) {
            if (narg < 1) return -1;
            LuaValue t = L.stack[func + 1];
            if (t == null) return -1;
            long i;
            if (narg >= 2) {
                LuaValue v2 = L.stack[func + 2];
                if (v2 == null) return -1;
                if (v2.isnil()) {
                    i = 1;
                } else if (v2.isNumberTag()) {
                    i = v2.tolong();
                } else {
                    return -1;
                }
            } else {
                i = 1;
            }
            int savedTop = L.top;
            long j;
            if (narg >= 3) {
                LuaValue v3 = L.stack[func + 3];
                if (v3 == null) return -1;
                if (v3.isnil()) {
                    j = luaLLen(t);
                } else if (v3.isNumberTag()) {
                    j = v3.tolong();
                } else {
                    return -1;
                }
            } else {
                j = luaLLen(t);
            }
            if (i > j) return 0;
            long n;
            try {
                n = Math.addExact(Math.subtractExact(j, i), 1);
            } catch (ArithmeticException e) {
                L.top = savedTop;
                LuaErrors.error("too many results to unpack");
                return -1;
            }
            if (n >= Integer.MAX_VALUE || !luaCheckStackUnpack(L, (int) n)) {
                L.top = savedTop;
                LuaErrors.error("too many results to unpack");
            }
            int count = (int) n;
            for (int k = 0; k < count; k++) {
                // ltablib.c: tunpack —— lua_geti 逐个 push（L.top 随结果推进）。
                // java diff: geti 内部（__index 元方法）以 L.top 为工作区起点，不先推进 L.top
                //   会覆盖 savedTop 处已写结果区，unpack 带 __index 时结果错乱（sort.lua:83）
                L.top = savedTop + k;
                L.stack[savedTop + k] = geti(t, i + k);
            }
            L.top = savedTop + count;
            return count;
        }

        private static final class TableRangeVarargs extends Varargs {
            private final LuaValue table;
            private final long start;
            private final int count;

            private TableRangeVarargs(LuaValue table, long start, int count) {
                this.table = table;
                this.start = start;
                this.count = count;
            }

            @Override
            public LuaValue arg(int i) {
                if (i <= 0 || i > count) return LuaValue.NIL;
                return geti(table, start + i - 1L);
            }

            @Override
            public int narg() {
                return count;
            }

            @Override
            public LuaValue arg1() {
                return count > 0 ? geti(table, start) : LuaValue.NIL;
            }


            public void copyTo(LuaValue[] dest, int off, int n) {
                int copied = Math.min(n, count);
                for (int i = 0; i < copied; i++) {
                    dest[off + i] = geti(table, start + i);
                }
                for (int i = copied; i < n; i++) {
                    dest[off + i] = LuaValue.NIL;
                }
            }
        }
    }

    // ltablib.c: tmove
    static class MoveFn extends LuaFunction {
        @Override
        public Varargs call(Varargs args) {
            LuaValue t = checktab(args, 1, TAB_R);
            long f = LuaErrors.checkLong(args, 2);
            long e = LuaErrors.checkLong(args, 3);
            long t_idx = LuaErrors.checkLong(args, 4);
            LuaValue tt = args.arg(5).isnil() ? t : checktab(args, 5, TAB_W);
            if (e >= f) {
                if (!(f > 0 || e < Long.MAX_VALUE + f))
                    LuaErrors.argError(3, "too many elements to move");
                long n = e - f + 1;
                if (!(t_idx <= Long.MAX_VALUE - n + 1))
                    LuaErrors.argError(4, "destination wrap around");
                boolean sameTable = (tt == t);
                boolean forward = (t_idx > e) || (t_idx <= f) || !sameTable;
                if (forward) {
                    for (long i = 0; i < n; i++)
                        seti(tt, t_idx + i, geti(t, f + i));
                } else {
                    for (long i = n - 1; i >= 0; i--)
                        seti(tt, t_idx + i, geti(t, f + i));
                }
            }
            return tt;
        }

        // java-only: callOnStack  -  ltablib.c tmove  -  表元素批量移动
        // 快路径：源/目标均为无元表 LuaTable 且索引在 int 范围内 ⇒ 直接 getInt/setint
        @Override
        public int callOnStack(LuaThread L, int func, int narg) {
            if (narg < 4) return -1;
            LuaValue tv = L.stack[func + 1];
            LuaValue fv = L.stack[func + 2];
            LuaValue ev = L.stack[func + 3];
            LuaValue iv = L.stack[func + 4];
            if (tv == null || fv == null || ev == null || iv == null) return -1;
            if (!(tv instanceof LuaTable t) || t.getmetatable() != null) return -1;
            // 仅真数值类型走快路径（数字字符串回落 call 路径由 checklong 强转）
            if (!fv.isNumberTag() || !ev.isNumberTag() || !iv.isNumberTag()) return -1;
            LuaTable tt = t;
            if (narg >= 5) {
                LuaValue ttv = L.stack[func + 5];
                if (ttv != null && !ttv.isnil()) {
                    if (!(ttv instanceof LuaTable) || ttv.getmetatable() != null) return -1;
                    tt = (LuaTable) ttv;
                }
            }
            long f = fv.tolong();
            long e = ev.tolong();
            long t_idx = iv.tolong();
            if (e < f) {
                L.stack[L.top++] = tt;
                return 1;
            }
            // 边界检查（对齐 call 路径的 argerror）
            if (!(f > 0 || e < Long.MAX_VALUE + f)) return -1;
            long n = e - f + 1;
            if (!(t_idx <= Long.MAX_VALUE - n + 1)) return -1;
            // 索引须在 int 范围内，否则回落 call 路径用 long 重载 seti/geti
            if (f < Integer.MIN_VALUE || f > Integer.MAX_VALUE ||
                    e < Integer.MIN_VALUE || e > Integer.MAX_VALUE ||
                    t_idx < Integer.MIN_VALUE || t_idx > Integer.MAX_VALUE) return -1;
            boolean sameTable = (tt == t);
            boolean forward = (t_idx > e) || (t_idx <= f) || !sameTable;
            int fi = (int) f, ei = (int) e, ti = (int) t_idx;
            if (forward) {
                for (int i = 0; i < n; i++)
                    tt.setInt(ti + i, t.getInt(fi + i));
            } else {
                for (long i = n - 1; i >= 0; i--)
                    tt.setInt(ti + (int) i, t.getInt(fi + (int) i));
            }
            L.stack[L.top++] = tt;
            return 1;
        }
    }

    // ltablib.c: tremove
    static class RemoveFn extends LuaFunction {
        public Varargs call(Varargs args) {
            LuaValue t = checktab(args, 1, TAB_RW | TAB_L);

            long size = auxGetN(args, 1, TAB_RW);
            long pos = args.optlong(2, size);

            if (pos != size) {
                if (!(pos >= 1 && pos <= size + 1))
                    LuaErrors.argError(2, "position out of bounds");
            }

            LuaValue v = geti(t, pos);

            for (; pos < size; pos++)
                seti(t, pos, geti(t, pos + 1));

            seti(t, pos, LuaValue.NIL);
            return v;
        }

        // java-only: callOnStack  -  ltablib.c tremove  -  从表中移除指定位置元素
        @Override
        public int callOnStack(LuaThread L, int func, int narg) {
            if (narg < 1) return -1;
            LuaValue tv = L.stack[func + 1];
            if (tv == null || !(tv instanceof LuaTable t)) return -1;
            long size = luaLLen(t);
            long pos;
            if (narg >= 2) {
                LuaValue pv = L.stack[func + 2];
                if (pv == null || !pv.isinteger()) return -1;
                pos = pv.tolong();
            } else {
                pos = size;
            }
            if (pos != size && !(pos >= 1 && pos <= size + 1)) return -1;
            LuaValue v = geti(t, pos);
            for (; pos < size; pos++)
                seti(t, pos, geti(t, pos + 1));
            seti(t, pos, LuaValue.NIL);
            L.stack[L.top] = v;
            L.top++;
            return 1;
        }
    }

    // ltablib.c: tsort
    static class SortFn extends LuaFunction {
        private static final int SORT_RANLIMIT = 100;

        // ltablib.c: sortcomp  -  调用比较函数或用 luaV_lessthan
        // java diff: C 经栈用 lua_call，Java 用 callOnStack2to1 避免 Varargs 分配
        private static boolean sortComp(LuaValue a, LuaValue b, LuaValue comp) {
            if (comp == null || comp.isnil())
                return LuaCompare.lessThan(a, b);
            return LuaCall.callOnStack2to1(comp, a, b).toboolean();
        }

        private static void swap(LuaValue t, long i, long j) {
            LuaValue vi = geti(t, i);
            LuaValue vj = geti(t, j);
            seti(t, i, vj);
            seti(t, j, vi);
        }

        private static int choosePivot(int lo, int up, int rnd) {
            int r4 = (up - lo) / 4;
            return (rnd ^ lo ^ up) % (r4 * 2) + (lo + r4);
        }

        // ltablib.c: auxsort
        private static void auxsort(LuaValue t, int lo, int up, int rnd, LuaValue comp) {
            while (lo < up) {
                int p, n;
                if (sortComp(geti(t, up), geti(t, lo), comp))  // a[up] < a[lo]?
                    swap(t, lo, up);
                if (up - lo == 1) return;
                if (up - lo < SORT_RANLIMIT || rnd == 0)
                    p = (lo + up) / 2;
                else
                    p = choosePivot(lo, up, rnd);
                if (sortComp(geti(t, p), geti(t, lo), comp)) {  // a[p] < a[lo]?
                    swap(t, p, lo);
                } else if (sortComp(geti(t, up), geti(t, p), comp)) {  // a[up] < a[p]?
                    swap(t, p, up);
                }
                if (up - lo == 2) return;
                LuaValue pivot = geti(t, p);
                swap(t, p, up - 1);  // 现在 a[up-1] == pivot
                p = partition(t, lo, up, pivot, comp);
                if (p - lo < up - p) {
                    auxsort(t, lo, p - 1, rnd, comp);
                    n = p - lo;
                    lo = p + 1;
                } else {
                    auxsort(t, p + 1, up, rnd, comp);
                    n = up - p;
                    up = p - 1;
                }
                if ((up - lo) / 128 > n)
                    rnd = (int) (System.nanoTime() & 0x7FFFFFFF);
            }
        }

        // ltablib.c: partition
        private static int partition(LuaValue t, int lo, int up, LuaValue pivot, LuaValue comp) {
            int i = lo;
            int j = up - 1;
            for (; ; ) {
                do {
                    i++;
                    if (!sortComp(geti(t, i), pivot, comp)) break;
                    if (i == up - 1)
                        LuaErrors.error("invalid order function for sorting");
                } while (true);
                do {
                    j--;
                    if (!sortComp(pivot, geti(t, j), comp)) break;
                    if (j < i)
                        LuaErrors.error("invalid order function for sorting");
                } while (true);
                if (j < i) {
                    swap(t, up - 1, i);
                    return i;
                }
                swap(t, i, j);
            }
        }

        public Varargs call(Varargs args) {
            LuaValue t = checktab(args, 1, TAB_RW | TAB_L);

            long n = auxGetN(args, 1, TAB_RW);
            if (n > 1) {
                if (n >= Integer.MAX_VALUE)
                    LuaErrors.argError(1, "array too big");
                LuaValue comp = args.isnil(2) ? LuaValue.NIL : args.checkfunction(2);
                auxsort(t, 1, (int) n, 0, comp);
            }
            return LuaValue.NONE;
        }

        // java-only: callOnStack（ltablib.c: tsort）—— 直接从栈读参数，消除 precallC 的
        //   VarargsPair 分配（narg==2）。快路径仅处理 LuaTable + 无元表 + comp 为 function/nil；
        //   其余回退 call(Varargs)；结果恒为 0 值（无返回值）。
        @Override
        public int callOnStack(LuaThread L, int func, int narg) {
            if (!LuaFunction.LIB_CALLONSTACK) return -1;
            if (narg < 1 || narg > 2) return -1;
            LuaValue tv = L.stack[func + 1];
            if (tv == null || !(tv instanceof LuaTable t)) return -1;
            if (t.metatable != null) return -1;  // 有元表须走 checktab/auxGetN 元方法路径
            long n = t.rawlen();  // 无元表 ⇒ rawlen == auxGetN(TAB_L)
            if (n > 1) {
                if (n >= Integer.MAX_VALUE)
                    LuaErrors.argError(1, "array too big");
                LuaValue comp = LuaValue.NIL;
                if (narg == 2) {
                    LuaValue cv = L.stack[func + 2];
                    if (cv == null) return -1;
                    if (cv.isnil()) { /* comp stays NIL */ } else if (cv.isfunction()) {
                        comp = cv;
                    } else return -1;  // 非函数，走通用路径 checkfunction 报错
                }
                auxsort(t, 1, (int) n, 0, comp);
            }
            return 0;  // 无返回值
        }
    }

    // ltablib.c: tcreate
    static class CreateFn extends LuaFunction {
        @Override
        public Varargs call(Varargs args) {
            long sizeseq = args.checklong(1);
            long sizerest = args.arg(2).optlong(0);
            if (sizeseq < 0 || sizerest < 0 || sizeseq > Integer.MAX_VALUE || sizerest > Integer.MAX_VALUE) {
                LuaErrors.error("out of range");
            }
            if (sizeseq > 0x3FFFFFFF || sizerest > 0x3FFFFFFF) {
                LuaErrors.error("table overflow");
            }
            LuaTable t = new LuaTable((int) sizeseq, (int) sizerest);
            // C：lgc.c : luaC_newobj  -  新对象创建即登记到所属状态并完成内存记账，
            // 否则预分配的数组/哈希段不计入 collectgarbage("count")（sort.lua 的 memdiff 断言）
            if (ownerGlobals != null) LuaTable.bindValue(ownerGlobals, t);
            return t;
        }
    }

    // ltablib.c: luaB_clear（Lua 5.4+） - table.clear(t)：清空表所有键，保留元表
    static class ClearFn extends LuaFunction {
        @Override
        public Varargs call(Varargs args) {
            args.arg1().checktable().clearTable();
            return LuaValue.NIL;
        }
    }


}
