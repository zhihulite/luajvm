// ref: lutf8lib.c
// diff: byte[]代替const char*; encodeUTF8手动实现(Java String不适合无效序列); isCont/isContByte重复(C只有iscont)
package org.luajvm.lib;

import org.luajvm.core.LuaErrors;
import org.luajvm.core.LuaFunction;
import org.luajvm.core.LuaInteger;
import org.luajvm.core.Globals;
import org.luajvm.core.LuaString;
import org.luajvm.core.LuaTable;
import org.luajvm.core.LuaValue;
import org.luajvm.core.Varargs;

import java.util.Arrays;

public class Utf8Lib extends LuaFunction {

    public Utf8Lib() {
    }


    // lutf8lib.c: lutf8_encode
    private static int encodeUTF8(int cp, byte[] buf, int off) {
        if (cp < 0x80) {
            buf[off] = (byte) cp;
            return 1;
        }
        if (cp < 0x800) {
            buf[off] = (byte) (0xC0 | (cp >> 6));
            buf[off + 1] = (byte) (0x80 | (cp & 0x3F));
            return 2;
        }
        if (cp < 0x10000) {
            buf[off] = (byte) (0xE0 | (cp >> 12));
            buf[off + 1] = (byte) (0x80 | ((cp >> 6) & 0x3F));
            buf[off + 2] = (byte) (0x80 | (cp & 0x3F));
            return 3;
        }
        if (cp < 0x200000) {
            buf[off] = (byte) (0xF0 | (cp >> 18));
            buf[off + 1] = (byte) (0x80 | ((cp >> 12) & 0x3F));
            buf[off + 2] = (byte) (0x80 | ((cp >> 6) & 0x3F));
            buf[off + 3] = (byte) (0x80 | (cp & 0x3F));
            return 4;
        }
        if (cp < 0x4000000) {
            buf[off] = (byte) (0xF8 | (cp >> 24));
            buf[off + 1] = (byte) (0x80 | ((cp >> 18) & 0x3F));
            buf[off + 2] = (byte) (0x80 | ((cp >> 12) & 0x3F));
            buf[off + 3] = (byte) (0x80 | ((cp >> 6) & 0x3F));
            buf[off + 4] = (byte) (0x80 | (cp & 0x3F));
            return 5;
        }
        buf[off] = (byte) (0xFC | (cp >> 30));
        buf[off + 1] = (byte) (0x80 | ((cp >> 24) & 0x3F));
        buf[off + 2] = (byte) (0x80 | ((cp >> 18) & 0x3F));
        buf[off + 3] = (byte) (0x80 | ((cp >> 12) & 0x3F));
        buf[off + 4] = (byte) (0x80 | ((cp >> 6) & 0x3F));
        buf[off + 5] = (byte) (0x80 | (cp & 0x3F));
        return 6;
    }

    // lutf8lib.c: u_posrelat
    static long uPosrelat(long pos, int len) {
        if (pos >= 0) return pos;
        else if (-pos > len) return 0;
        else return (long) len + pos + 1;
    }

    // lutf8lib.c: iscont
    private static boolean isContByte(byte b) {
        int c = b & 0xFF;
        return c >= 0x80 && c <= 0xBF;
    }

    // lutf8lib.c: luaopen_utf8
    @Override
    public Varargs call(Varargs args) {
        LuaValue modname = args.arg1();
        LuaValue env = args.arg(2);
        LuaTable utf8 = new LuaTable();
        utf8.set("char", new _char());
        utf8.set("codepoint", new _code());
        utf8.set("codes", new codes());
        utf8.set("len", new len());
        utf8.set("offset", new offset());
        // lutf8lib.c: charpattern
        // java diff: 模式串里的 backslash-u-00XX 转义是[字节值]字面量（C 源码里是 \x00-\x7F
        //   等原始字节），必须用 newStrLatin1（Latin-1 1:1 字节映射）；UTF-8 的 newStr 会把
        //   U+00C2 等二次编码成 0xC3 0x82，破坏模式串（utf8.lua 断言失败）。
        utf8.set("charpattern", LuaString.newStrLatin1("[\u0000-\u007F\u00C2-\u00FD][\u0080-\u00BF]*"));
        env.set("utf8", utf8);
        if (!env.get("package").isnil()) env.get("package").get("loaded").set("utf8", utf8);
        // C：lstate.h : G(L)->mt[LUA_TSTRING]  -  按状态存储，不可跨状态共享
        Globals g = env.checkglobals();
        if (g.typeMetatable(LuaValue.TSTRING) == null)
            g.setTypeMetatable(LuaValue.TSTRING, LuaValue.tableOf(LuaValue.INDEX, utf8));
        return utf8;
    }

    // lutf8lib.c: str_utfchar
    static final class _char extends LuaFunction {
        public Varargs call(Varargs args) {
            int n = args.narg();
            byte[] result = new byte[Math.max(n, 0) * 6];
            int off = 0;
            byte[] buf = new byte[6];
            for (int i = 1; i <= n; i++) {
                long cp = LuaErrors.checkLong(args, i);

                if (cp < 0 || cp > 0x7FFFFFFF) LuaErrors.argError(i, "value out of range");
                int len = encodeUTF8((int) cp, buf, 0);
                System.arraycopy(buf, 0, result, off, len);
                off += len;
            }
            if (off != result.length) result = Arrays.copyOf(result, off);
            return LuaString.newLstr(result, 0, result.length);
        }
    }

    // lutf8lib.c: codepoint
    static final class _code extends LuaFunction {
        public Varargs call(Varargs args) {
            LuaString s = args.checkstring(1);
            int len = s.rawlen();
            long posi = uPosrelat(args.arg(2).isnil() ? 1 : LuaErrors.checkLong(args, 2), len);
            long pose = uPosrelat(args.arg(3).isnil() ? posi : LuaErrors.checkLong(args, 3), len);
            boolean lax = args.arg(4).toboolean();

            if (posi < 1) LuaErrors.argError(2, "out of bounds");

            if (pose > len) LuaErrors.argError(3, "out of bounds");
            if (posi > pose) return LuaValue.NONE;
            if (pose - posi >= Integer.MAX_VALUE) LuaErrors.error("string slice too long");
            LuaValue[] results = new LuaValue[(int) (pose - posi) + 1];
            int nresults = 0;
            int p = (int) posi - 1;
            int end = (int) pose;
            int limit = len;
            while (p < end) {
                int[] cpLen = new int[1];
                int cp = Utf8Support.utf8Decode(s.contents, p, limit, cpLen, !lax);
                if (cp < 0) LuaErrors.error("invalid UTF-8 code");
                results[nresults++] = LuaInteger.valueOf(cp);
                p += cpLen[0];
            }
            if (nresults != results.length) results = Arrays.copyOf(results, nresults);
            return varargsOf(results);
        }
    }

    // lutf8lib.c: iter_codes
    static final class codes extends LuaFunction {
        public Varargs call(Varargs args) {
            LuaString s = args.checkstring(1);
            boolean lax = args.arg(2).toboolean();

            if (s.rawlen() > 0) {
                int firstByte = s.contents[0] & 0xFF;
                if (firstByte >= 0x80 && firstByte <= 0xBF)
                    LuaErrors.error("invalid UTF-8 code");
            }

            return varargsOf(new iter_aux(lax), s, LuaInteger.valueOf(0));
        }
    }

    // lutf8lib.c: iter_aux
    static final class iter_aux extends LuaFunction {
        final boolean lax;

        iter_aux(boolean lax) {
            this.lax = lax;
        }

        public Varargs call(Varargs args) {
            LuaString s = args.checkstring(1);
            int len = s.rawlen();

            long n = LuaErrors.checkLong(args, 2);

            if (Long.compareUnsigned(n, len) < 0) {
                while (Long.compareUnsigned(n, len) < 0 && isContByte(s.contents[(int) n])) n++;
            }

            if (Long.compareUnsigned(n, len) >= 0) return LuaValue.NONE;
            int[] cpLen = new int[1];
            int cp = Utf8Support.utf8Decode(s.contents, (int) n, len, cpLen, !lax);
            if (cp < 0) LuaErrors.error("invalid UTF-8 code");

            int nextPos = (int) n + cpLen[0];
            if (nextPos < len && isContByte(s.contents[nextPos]))
                LuaErrors.error("invalid UTF-8 code");

            return varargsOf(LuaInteger.valueOf(n + 1), LuaInteger.valueOf(cp));
        }
    }

    // lutf8lib.c: utflen
    static final class len extends LuaFunction {
        @Override
        public Varargs call(Varargs args) {
            LuaValue arg = args.arg1();
            if (arg.isnil()) return LuaValue.NONE;
            LuaString s = arg.checkstring();
            int len = s.rawlen();

            long posi = uPosrelat(args.arg(2).isnil() ? 1 : LuaErrors.checkLong(args, 2), len);
            long posj = uPosrelat(args.arg(3).isnil() ? -1 : LuaErrors.checkLong(args, 3), len);
            boolean lax = args.arg(4).toboolean();

            if (posi < 1 || posi > len + 1) LuaErrors.argError(2, "initial position out of bounds");
            posi--;

            posj--;
            if (posj >= len) LuaErrors.argError(3, "final position out of bounds");
            long n = 0;
            while (posi <= posj) {
                if (posi >= len) break;
                int[] cpLen = new int[1];
                int cp = Utf8Support.utf8Decode(s.contents, (int) posi, len, cpLen, !lax);
                if (cp < 0) {
                    return varargsOf(LuaValue.NIL, LuaInteger.valueOf(posi + 1));
                }
                posi += cpLen[0];
                n++;
            }
            return varargsOf(LuaInteger.valueOf(n), LuaValue.NIL);
        }
    }

    // lutf8lib.c: byteoffset
    static final class offset extends LuaFunction {
        public Varargs call(Varargs args) {
            LuaString s = args.checkstring(1);
            int len = s.rawlen();
            long n = LuaErrors.checkLong(args, 2);

            long posiDefault = (n >= 0) ? 1 : len + 1;
            long posi = args.arg(3).isnil() ? posiDefault : uPosrelat(LuaErrors.checkLong(args, 3), len);

            if (posi < 1 || posi > len + 1) {
                LuaErrors.argError(3, "position out of bounds");
            }
            posi--;
            byte[] sb = s.contents;

            if (n == 0) {
                while (posi > 0 && posi < len && isContByte(sb[(int) posi])) posi--;
            } else {
                if (posi < len && isContByte(sb[(int) posi])) {
                    LuaErrors.error("initial position is a continuation byte");
                }
                if (n < 0) {
                    while (n < 0 && posi > 0) {
                        do {
                            posi--;
                        } while (posi > 0 && isContByte(sb[(int) posi]));
                        n++;
                    }
                } else {
                    n--;
                    while (n > 0 && posi < len) {
                        do {
                            posi++;
                        } while (posi < len && isContByte(sb[(int) posi]));
                        n--;
                    }
                }
            }
            if (n != 0) return LuaValue.NIL;
            int startPos = (int) posi;

            if (startPos < len && (sb[startPos] & 0x80) != 0) {
                if (isContByte(sb[startPos])) {
                    LuaErrors.error("initial position is a continuation byte");
                }
                int lastP = startPos;
                while (lastP + 1 < len && isContByte(sb[lastP + 1])) lastP++;
                return varargsOf(LuaInteger.valueOf(startPos + 1), LuaInteger.valueOf(lastP + 1));
            }

            return varargsOf(LuaInteger.valueOf(startPos + 1), LuaInteger.valueOf(startPos + 1));
        }
    }
}
