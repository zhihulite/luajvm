// ref: ldump.c / lundump.c
// diff: OutputStream/InputStream 替代 ZIO, ArrayList 替代 luaM_reallocvector, RuntimeException 替代 longjmp
package org.luajvm.vm;
import org.luajvm.core.LuaFloat;
import org.luajvm.core.LuaError;
import org.luajvm.core.LuaErrors;
import org.luajvm.core.LuaInteger;
import org.luajvm.core.LuaString;
import org.luajvm.core.LuaValue;
import org.luajvm.core.Prototype;

import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.List;

public final class LuaChunk {
    // -- 常量（lundump.h）--
    public static final byte[] LUA_SIGNATURE = {0x1B, 'L', 'u', 'a'};
    public static final byte LUAC_VERSION = 0x55;
    public static final byte LUAC_FORMAT = 0;
    public static final byte[] LUAC_DATA = {0x19, (byte) 0x93, '\r', '\n', 0x1A, '\n'};
    public static final int LUAC_INT = -0x5678;
    public static final int LUAC_INST = 0x12345678;
    public static final long LUAC_INT_LONG = -0x5678L;
    public static final double LUAC_NUM = -370.5;
    // -- 常量类型标签（lobject.h）--
    // makevariant(t, v) = t | (v << 4)
    private static final int LUA_VNIL = 0;   // makevariant(0, 0)
    private static final int LUA_VFALSE = 1;   // makevariant(1, 0)
    private static final int LUA_VTRUE = 17;  // makevariant(1, 1) = 1 | (1<<4)
    private static final int LUA_VNUMINT = 3;   // makevariant(3, 0)
    private static final int LUA_VNUMFLT = 19;  // makevariant(3, 1) = 3 | (1<<4)
    private static final int LUA_VSHRSTR = 4;   // makevariant(4, 0)
    private static final int LUA_VLNGSTR = 20;  // makevariant(4, 1) = 4 | (1<<4)
    // java-only: dump/undump 诊断开关（-Dluachunk.debug=true）。
    //   必须 static final：系统属性运行期可变，C2 无法常量折叠也无法提出循环；
    //   static final 后 if (DEBUG) 整块被判死代码消除，关时零残留、方法体可内联。
    private static final boolean DEBUG = Boolean.getBoolean("luachunk.debug");

    private LuaChunk() {
    }

    // ===============================================================
    // DUMP  -  Prototype -> byte[]
    // ===============================================================

    // ldump.c: luaU_dump
    public static byte[] dump(Prototype p, boolean strip) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        // 文件头
        out.write(LUA_SIGNATURE, 0, LUA_SIGNATURE.length);
        out.write(LUAC_VERSION);
        out.write(LUAC_FORMAT);
        out.write(LUAC_DATA, 0, LUAC_DATA.length);
        writeNumInfo(out, 4, LUAC_INT);
        writeNumInfo(out, 4, LUAC_INST);
        writeNumInfo(out, 8, LUAC_INT_LONG);
        writeNumInfo(out, 8, Double.doubleToLongBits(LUAC_NUM));
        // ldump.c: dumpByte(&D, f->sizeupvalues)
        writeByte(out, p.sizeupvalues);
        // java: DumpState 须知道偏移量(41)才能对齐
        DumpState D = new DumpState(out, strip, 41);
        D.dumpFunction(p);
        return out.toByteArray();
    }

    // ldump.c: luaU_dumpByte
    private static void writeByte(ByteArrayOutputStream out, int b) {
        out.write(b & 0xFF);
    }

    // ldump.c: luaU_dumpNumber
    private static void writeNumInfo(ByteArrayOutputStream out, int size, long value) {
        writeByte(out, size);
        // 小端序
        for (int i = 0; i < size; i++) {
            writeByte(out, (int) (value >>> (i * 8)));
        }
    }

    // lundump.c: luaU_undump
    public static Prototype undump(byte[] data) {
        return undump(data, "binary string");
    }

    // lundump.c: luaU_undump 的 name 规范化
    //   '@'/'=' 前缀剥掉；以 LUA_SIGNATURE[0] 开头（load 默认把 chunk 串本身当名字）
    //   记作 "binary string"
    private static String normalizeChunkName(String name) {
        if (name == null || name.isEmpty()) return "binary string";
        char c = name.charAt(0);
        if (c == '@' || c == '=') return name.substring(1);
        if (c == (char) LUA_SIGNATURE[0]) return "binary string";
        return name;
    }

    // lundump.c: luaU_undump
    // java diff: C 从 ZIO 流式读，Java 收全量 byte[]；name 用于错误消息（S->name）
    public static Prototype undump(byte[] data, String chunkname) {
        LoadState S = new LoadState(data, normalizeChunkName(chunkname));
        S.checkHeader();
        // lundump.c: luaU_undump  -  loadByte 语义是 unsigned char（0-255）：
        // 不 & 0xFF 会让 ≥128 上值的主函数因符号扩展读成负数
        int nups = S.loadByte() & 0xFF;
        Prototype p = S.loadFunction();
        // lundump.c: luaU_undump  -  nups 不匹配 = corrupted
        if (p.upvalues.length != nups) {
            S.error("upvalue count mismatch");
        }
        return p;
    }

    // ===============================================================
    // LOAD  -  byte[] -> Prototype
    // ===============================================================

    // DumpState
    private static final class DumpState {
        final ByteArrayOutputStream out;
        final boolean strip;
        final List<LuaString> savedStrings = new ArrayList<>();
        int offset;

        DumpState(ByteArrayOutputStream out, boolean strip, int initialOffset) {
            this.out = out;
            this.strip = strip;
            this.offset = initialOffset;
        }

        // ldump.c: luaU_dumpBlock
        void dumpBlock(byte[] b) {
            out.write(b, 0, b.length);
            offset += b.length;
        }

        // ldump.c: luaU_dumpByte
        void dumpByte(int b) {
            out.write(b & 0xFF);
            offset++;
        }

        // ldump.c: luaU_dumpVarint
        void dumpVarint(long x) {
            byte[] buff = new byte[10];
            int n = 1;
            buff[9] = (byte) (x & 0x7F);
            while ((x >>>= 7) != 0) {
                buff[10 - (++n)] = (byte) ((x & 0x7F) | 0x80);
            }
            out.write(buff, 10 - n, n);
            offset += n;
        }

        // ldump.c: luaU_dumpSize
        void dumpSize(long sz) {
            dumpVarint(sz);
        }

        // ldump.c: luaU_dumpInt
        void dumpInt(int x) {
            dumpVarint(Integer.toUnsignedLong(x));
        }

        // ldump.c: dumpVector(AbsLineInfo)
        // Java 的 abslineinfo 用 {pc,line} 平铺的 int[] 保存；该调试向量
        // 必须按原始小端 int 写入，不能用 Lua 的变长整数编码。
        void dumpRawInt(int x) {
            writeByte(x);
            writeByte(x >>> 8);
            writeByte(x >>> 16);
            writeByte(x >>> 24);
        }

        // ldump.c: luaU_dumpNumber
        void dumpNumber(double x) {
            long bits = Double.doubleToLongBits(x);
            for (int i = 0; i < 8; i++) writeByte((int) (bits >>> (i * 8)));
        }

        // ldump.c: luaU_dumpInteger
        void dumpInteger(long x) {
            // Zigzag: 0->0, -1->1, 1->2, -2->3, 2->4, ...
            long cx = (x >= 0) ? (2L * x) : (2L * ~x) + 1;
            dumpVarint(cx);
        }

        // ldump.c: luaU_dumpString
        // java diff: C 用 S->h 哈希表（luaH_getstr）查已写过的串；Java 用 ArrayList 顺序
        //   raweq 扫描（O(n^2)），语义等价（内容相等即复用），仅大常量池 dump 变慢；
        //   dump 不在任何热路径上（luac 与 string.dump）。
        void dumpString(LuaString s) {
            if (s == null) {
                dumpVarint(0);
                dumpVarint(0);
                return;
            }
            // 检查已保存字符串
            for (int i = 0; i < savedStrings.size(); i++) {
                if (savedStrings.get(i).raweq(s)) {
                    dumpVarint(0);
                    dumpVarint(i + 1);
                    return;
                }
            }
            byte[] bytes = s.bytes();
            if (DEBUG) {
                System.err.println("    dumpString: len=" + bytes.length + " offset=" + offset + " preview=" + s.toJavaString().substring(0, Math.min(30, s.toJavaString().length())));
            }
            dumpSize(bytes.length + 1);
            dumpBlock(bytes);
            dumpByte(0);
            savedStrings.add(s);
        }

        // ldump.c: luaU_dumpAlign
        void dumpAlign(int align) {
            int padding = align - (offset % align);
            if (padding < align) {
                for (int i = 0; i < padding; i++) dumpByte(0);
            }
        }

        // DumpCode
        void dumpCode(Prototype f) {
            dumpInt(f.sizecode);
            if (DEBUG) {
                System.err.println("    dumpCode: sizecode=" + f.sizecode + " preAlign=" + offset);
            }
            dumpAlign(4);
            if (DEBUG) {
                System.err.println("    dumpCode: postAlign=" + offset);
            }
            for (int instr : f.code) {
                writeByte(instr);
                writeByte(instr >>> 8);
                writeByte(instr >>> 16);
                writeByte(instr >>> 24);
            }
        }


        // DumpConstants
        void dumpConstants(Prototype f) {
            LuaValue[] k = f.k;
            dumpInt(f.sizek);
            for (int i = 0; i < f.sizek; i++) {
                LuaValue v = k[i];
                int tt;
                if (v.isnil()) tt = LUA_VNIL;
                else if (v == LuaValue.FALSE) tt = LUA_VFALSE;
                else if (v == LuaValue.TRUE) tt = LUA_VTRUE;
                else if (v instanceof LuaInteger li) {
                    tt = LUA_VNUMINT;
                    dumpByte(tt);
                    dumpInteger(li.tolong());
                    continue;
                } else if (v instanceof LuaFloat lf) {
                    tt = LUA_VNUMFLT;
                    dumpByte(tt);
                    dumpNumber(lf.todouble());
                    continue;
                } else if (v instanceof LuaString ls) {
                    // ldump.c: tt = ttypetag(o) = withvariant(rawtt(o)) = rawtt & 0x3F
                    // java diff: C 的 ttypetag 掩掉 BIT_ISCOLLECTABLE；Java 的 tt_ 含它
                    tt = ls.tt_ & 0x3F;
                    dumpByte(tt);
                    dumpString(ls);
                    continue;
                } else {
                    throw new RuntimeException("cannot dump constant of type " + v.typeName());
                }
                dumpByte(tt);
            }
        }

        // DumpUpvalues
        void dumpUpvalues(Prototype f) {
            dumpInt(f.sizeupvalues);
            for (int i = 0; i < f.sizeupvalues; i++) {
                Prototype.Upvaldesc uv = f.upvalues[i];
                dumpByte(uv.instack ? 1 : 0);
                dumpByte(uv.idx);
                dumpByte(uv.kind);
            }
        }

        // DumpProtos
        void dumpProtos(Prototype f) {
            dumpInt(f.sizep);
            for (int i = 0; i < f.sizep; i++) dumpFunction(f.p[i]);
        }

        // DumpDebug
        void dumpDebug(Prototype f) {
            int n;
            // ldump.c: lineinfo
            n = strip ? 0 : f.sizelineinfo;
            dumpInt(n);
            if (!strip) {
                for (int i = 0; i < f.sizelineinfo; i++) writeByte(f.lineinfo[i]);
            }
            // ldump.c: abslineinfo
            n = strip ? 0 : f.sizeabslineinfo;
            dumpInt(n);
            if (n > 0) {
                // C: ldump.c:dumpDebug
                dumpAlign(4);
                for (int i = 0; i < n * 2; i++) dumpRawInt(f.abslineinfo[i]);
            }
            // ldump.c: locvars
            n = strip ? 0 : f.sizelocvars;
            dumpInt(n);
            if (!strip) {
                for (int i = 0; i < f.sizelocvars; i++) {
                    Prototype.LocVar lv = f.locvars[i];
                    dumpString(lv.varname);
                    dumpInt(lv.startpc);
                    dumpInt(lv.endpc);
                }
            }
            // upvalue 名
            n = strip ? 0 : f.sizeupvalues;
            dumpInt(n);
            if (!strip) {
                for (int i = 0; i < f.sizeupvalues; i++) dumpString(f.upvalues[i].name);
            }
        }

        // DumpFunction
        void dumpFunction(Prototype f) {
            if (DEBUG) {
                System.err.println("dumpFunction @" + offset + ": linedef=" + f.linedefined + " lastlinedef=" + f.lastlinedefined + " sizek=" + f.sizek + " sizep=" + f.sizep);
            }
            dumpInt(f.linedefined);
            dumpInt(f.lastlinedefined);
            dumpByte(f.numparams);
            dumpByte(f.flag);
            dumpByte(f.maxstacksize);
            dumpCode(f);
            dumpConstants(f);
            dumpUpvalues(f);
            dumpProtos(f);
            dumpString(strip ? null : f.source);
            dumpDebug(f);
        }

        // ldump.c: dumpVector/dumpBlock 的底层写入
        // diff: C 直接写内存无需跟踪偏移；Java 的 DumpState 用 offset 支撑 dumpAlign，
        //       writeByte 必须同步递增 offset，否则 dumpAlign 算错，输出与 load 端不对齐。
        private void writeByte(int b) {
            LuaChunk.writeByte(out, b);
            offset++;
        }
    }

    // LoadState
    private static final class LoadState {
        final byte[] data;
        final List<LuaString> savedStrings = new ArrayList<>();
        int pos = 0;

        // lundump.c: LoadState.name  -  错误消息前缀（已按 luaU_undump 规则规范化）
        final String name;

        LoadState(byte[] data, String name) {
            this.data = data;
            this.name = name;
        }

        // lundump.c: error  -  "%s: bad binary format (%s)"，抛 LUA_ERRSYNTAX
        void error(String why) {
            throw LuaErrors.errorObject(name + ": bad binary format (" + why + ")");
        }

        // LoadByte
        byte loadByte() {
            if (pos >= data.length) error("truncated chunk");
            return data[pos++];
        }

        // LoadBlock
        void loadBlock(byte[] buf, int size) {
            if (pos + size > data.length) error("truncated chunk");
            System.arraycopy(data, pos, buf, 0, size);
            pos += size;
        }

        // LoadAlign
        void loadAlign(int align) {
            int padding = align - (pos % align);
            if (padding < align) {
                pos += padding;
            }
        }

        // LoadVarint
        long loadVarint() {
            // lundump.c loadVarint 带 limit 溢出检查。java diff：负的 lua_Integer
            // 以 64 位无符号编码（10 组 7bit），long 的模回绕恰好还原其二进制位——
            // 上限只能按组数卡（10 组 = 70bit 上界），按值域卡会拒掉合法负整数
            long x = 0;
            int b;
            int groups = 0;
            do {
                b = loadByte() & 0xFF;
                if (++groups > 10) {
                    error("integer overflow");  // lundump.c: loadVarint
                }
                x = (x << 7) | (b & 0x7F);
            } while ((b & 0x80) != 0);
            return x;
        }

        // LoadSize
        long loadSize() {
            return loadVarint();
        }

        // LoadInt
        int loadInt() {
            return (int) loadVarint();
        }

        // LoadNumber
        double loadNumber() {
            byte[] buf = new byte[8];
            loadBlock(buf, 8);
            long bits = 0;
            for (int i = 0; i < 8; i++) bits |= ((long) (buf[i] & 0xFF)) << (i * 8);
            return Double.longBitsToDouble(bits);
        }

        // LoadInteger
        long loadInteger() {
            long cx = loadVarint();
            if ((cx & 1) != 0) return ~(cx >>> 1);
            else return cx >>> 1;
        }

        // LoadString
        LuaString loadString() {
            long size = loadSize();
            if (DEBUG) {
                System.err.println("    loadString: size=" + size + " pos=" + pos);
            }
            if (size == 0) {
                long idx = loadVarint();
                if (DEBUG) {
                    System.err.println("    loadString: saved idx=" + idx);
                }
                if (idx == 0) return null;
                if (idx - 1 < savedStrings.size()) return savedStrings.get((int) (idx - 1));
                error("invalid string index");
                return null;
            }
            size -= 1;
            byte[] buf = new byte[(int) size + 1];
            loadBlock(buf, buf.length);
            byte[] str = new byte[(int) size];
            System.arraycopy(buf, 0, str, 0, (int) size);
            LuaString s = LuaString.valueOf(str);
            savedStrings.add(s);
            return s;
        }

        // LoadCode
        int[] loadCode() {
            int n = loadInt();
            loadAlign(4);
            int[] code = new int[n];
            for (int i = 0; i < n; i++) {
                int b0 = loadByte() & 0xFF;
                int b1 = loadByte() & 0xFF;
                int b2 = loadByte() & 0xFF;
                int b3 = loadByte() & 0xFF;
                code[i] = b0 | (b1 << 8) | (b2 << 16) | (b3 << 24);
            }
            return code;
        }

        // LoadConstants
        LuaValue[] loadConstants() {
            int n = loadInt();
            if (DEBUG) {
                System.err.println("    loadConstants: n=" + n + " pos=" + pos);
            }
            LuaValue[] k = new LuaValue[n];
            for (int i = 0; i < n; i++) {
                int t = loadByte() & 0xFF;
                if (DEBUG) {
                    System.err.println("    loadConstants[" + i + "]: tag=" + t + " pos=" + pos);
                }
                switch (t) {
                    case LUA_VNIL:
                        k[i] = LuaValue.NIL;
                        break;
                    case LUA_VFALSE:
                        k[i] = LuaValue.FALSE;
                        break;
                    case LUA_VTRUE:
                        k[i] = LuaValue.TRUE;
                        break;
                    case LUA_VNUMFLT:
                        k[i] = LuaFloat.valueOf(loadNumber());
                        break;
                    case LUA_VNUMINT:
                        k[i] = LuaInteger.valueOf(loadInteger());
                        break;
                    case LUA_VSHRSTR:
                    case LUA_VLNGSTR:
                        k[i] = loadString();
                        break;
                    default:
                        error("invalid constant");  // lundump.c: loadConstants default
                }
            }
            return k;
        }

        // LoadUpvalues
        Prototype.Upvaldesc[] loadUpvalues() {
            int n = loadInt();
            Prototype.Upvaldesc[] ups = new Prototype.Upvaldesc[n];
            for (int i = 0; i < n; i++) {
                int instack = loadByte() & 0xFF;
                int idx = loadByte() & 0xFF;
                int kind = loadByte() & 0xFF;
                ups[i] = new Prototype.Upvaldesc(null, idx, instack != 0, kind);
            }
            return ups;
        }

        // LoadProtos
        Prototype[] loadProtos() {
            int n = loadInt();
            Prototype[] ps = new Prototype[n];
            for (int i = 0; i < n; i++) ps[i] = loadFunction();
            return ps;
        }

        // LoadDebug
        void loadDebug(Prototype f) {
            int n;
            // ldump.c: lineinfo
            n = loadInt();
            f.sizelineinfo = n;
            if (n > 0) {
                f.lineinfo = new int[n];
                for (int i = 0; i < n; i++) f.lineinfo[i] = loadByte() & 0xFF;
            }
            // ldump.c: abslineinfo
            n = loadInt();
            f.sizeabslineinfo = n;
            if (n > 0) {
                f.abslineinfo = new int[n * 2];
                loadAlign(4);
                for (int i = 0; i < n; i++) {
                    // C: lundump.c:loadDebug，AbsLineInfo {int pc; int line}
                    f.abslineinfo[i * 2] = loadRawInt();
                    f.abslineinfo[i * 2 + 1] = loadRawInt();
                }
            }
            // ldump.c: locvars
            n = loadInt();
            f.locvars = new Prototype.LocVar[n];
            f.sizelocvars = n;
            for (int i = 0; i < n; i++) {
                LuaString name = loadString();
                int startpc = loadInt();
                int endpc = loadInt();
                f.locvars[i] = new Prototype.LocVar();
                f.locvars[i].varname = name;
                f.locvars[i].startpc = startpc;
                f.locvars[i].endpc = endpc;
            }
            // lundump.c: loadDebug  -  n != 0 时强制取 sizeupvalues（"must be this many"）：
            //   损坏输入下按 dump 值读会让解析位置漂移
            n = loadInt();
            if (n != 0) n = f.upvalues.length;
            for (int i = 0; i < n; i++) {
                f.upvalues[i].name = loadString();
            }
        }

        // lundump.c:loadVector(AbsLineInfo)
        int loadRawInt() {
            return (loadByte() & 0xFF)
                    | ((loadByte() & 0xFF) << 8)
                    | ((loadByte() & 0xFF) << 16)
                    | ((loadByte() & 0xFF) << 24);
        }

        // LoadFunction
        Prototype loadFunction() {
            int startPos = pos;
            Prototype f = new Prototype();
            f.linedefined = loadInt();
            f.lastlinedefined = loadInt();
            f.numparams = loadByte() & 0xFF;
            f.flag = loadByte();
            f.maxstacksize = loadByte() & 0xFF;
            if (DEBUG) {
                System.err.println("loadFunction @" + startPos + ": linedef=" + f.linedefined
                        + " lastlinedef=" + f.lastlinedefined + " nparams=" + f.numparams
                        + " flag=" + f.flag + " maxstack=" + f.maxstacksize + " (pos=" + pos + ")");
            }
            f.code = loadCode();
            f.sizecode = f.code.length;
            if (DEBUG) {
                System.err.println("  after loadCode: code.len=" + f.code.length + " (pos=" + pos + ")");
            }
            f.k = loadConstants();
            f.sizek = f.k.length;
            if (DEBUG) {
                System.err.println("  after loadConstants: k.len=" + f.k.length + " (pos=" + pos + ")");
            }
            f.upvalues = loadUpvalues();
            f.sizeupvalues = f.upvalues.length;
            if (DEBUG) {
                System.err.println("  after loadUpvalues: upvals=" + f.upvalues.length + " (pos=" + pos + ")");
            }
            f.p = loadProtos();
            f.sizep = f.p.length;
            if (DEBUG) {
                System.err.println("  after loadProtos: protos=" + f.p.length + " (pos=" + pos + ")");
            }
            int sourcePos = pos;
            f.source = loadString();
            if (DEBUG) {
                System.err.println("  after loadString(source): source=" + (f.source != null ? f.source.toJavaString().substring(0, Math.min(30, f.source.toJavaString().length())) : "null") + " (pos=" + pos + " consumed=" + (pos - sourcePos) + ")");
            }
            int debugPos = pos;
            loadDebug(f);
            if (DEBUG) {
                System.err.println("  loadFunction done @" + startPos + " ended @" + pos
                        + " code.len=" + f.code.length + " k.len=" + f.k.length
                        + " upvals=" + f.upvalues.length + " protos=" + f.p.length
                        + " debugConsumed=" + (pos - debugPos));
            }
            return f;
        }

        // lundump.c: checkHeader
        void checkHeader() {
            // 签名
            for (int i = 0; i < LUA_SIGNATURE.length; i++) {
                if (loadByte() != LUA_SIGNATURE[i]) error("not a binary chunk");
            }
            // 版本+格式
            if (loadByte() != LUAC_VERSION) error("version mismatch");
            if (loadByte() != LUAC_FORMAT) error("format mismatch");
            // LUAC_DATA
            for (int i = 0; i < LUAC_DATA.length; i++) {
                if (loadByte() != LUAC_DATA[i]) error("corrupted chunk");
            }
            // 数值校验
            // lundump.c: checknum 的 tname 逐字（错误消息 "%s %s mismatch" 用它）
            checkNumInfo("int", 4, LUAC_INT);
            checkNumInfo("instruction", 4, LUAC_INST);
            checkNumInfo("Lua integer", 8, LUAC_INT_LONG);
            checkNumInfoFloat("Lua number", 8, LUAC_NUM);
        }

        // ldump.c: checkNumInfo
        void checkNumInfo(String tname, int expectedSize, long expectedValue) {
            int size = loadByte() & 0xFF;
            // lundump.c: numerror  -  "%s %s mismatch"（tname, what），无额外细节
            if (size != expectedSize) error(tname + " size mismatch");
            long v = 0;
            for (int i = 0; i < size; i++) v |= ((long) (loadByte() & 0xFF)) << (i * 8);
            // 符号扩展至 64 位
            if (size == 4) {
                int iv = (int) v;
                v = iv;
            }
            if (v != expectedValue) error(tname + " format mismatch");
        }

        // ldump.c: checkNumInfoFloat
        void checkNumInfoFloat(String tname, int expectedSize, double expectedValue) {
            int size = loadByte() & 0xFF;
            if (size != expectedSize) error(tname + " size mismatch");
            long bits = 0;
            for (int i = 0; i < size; i++) bits |= ((long) (loadByte() & 0xFF)) << (i * 8);
            double v = Double.longBitsToDouble(bits);
            if (v != expectedValue) error(tname + " format mismatch");
        }
    }
}
