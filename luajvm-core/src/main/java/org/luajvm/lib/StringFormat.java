// ref: lstrlib.c (str_format)
// diff: StringBuilder替代luaL_Buffer; String.format替代sprintf; %g/%G需手动去尾随零; %e/%E指数补零至3位; %a/%A特殊值处理; ByteBuffer替代memcpy; ByteArrayOutputStream替代luaL_Buffer
package org.luajvm.lib;

import org.luajvm.core.LuaError;
import org.luajvm.core.LuaFloat;
import org.luajvm.core.LuaInteger;
import org.luajvm.core.LuaString;
import org.luajvm.core.LuaValue;
import org.luajvm.core.Varargs;
import org.luajvm.vm.LuaCall;

import java.io.ByteArrayOutputStream;
import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import org.luajvm.core.LuaErrors;

public final class StringFormat {
    // java-only: A/B 开关 - -Dluajvm.fmtopt=false 禁用 format 优化（基线对照用），默认开启
    // 优化内容：批量纯文本追加（latin-1 decode + arraycopy 替代逐字符 append）+
    // 内联 width 解析（直接累加替代 Integer.parseInt(substring)）+ 批量 %s 追加
    static final boolean FMT_OPT =
            Boolean.parseBoolean(System.getProperty("luajvm.fmtopt", "true"));
    // java-only: A/B 开关 - -Dluajvm.fmtbytebuf=true 启用 ByteBuf 路径，默认 OFF
    // （默认走 StringBuilder 路径，其已含全部正确性修复）
    static final boolean FMT_BYTEBUF =
            Boolean.parseBoolean(System.getProperty("luajvm.fmtbytebuf", "false"));
    // java-only: latin-1 charset 常量，避免每次调用 StandardCharsets.ISO_8859_1 的字段访问
    private static final Charset LATIN1 = StandardCharsets.ISO_8859_1;
    // java-only: 直写大写十六进制到 buffer，等价 Long.toHexString(n).toUpperCase()，
    // 但省掉中间 String + 二次 toUpperCase 分配（%X 无修饰符快路径用）。
    private static final char[] HEX_UPPER = "0123456789ABCDEF".toCharArray();
    private static final int KNOP = 0, KINT = 1, KUINT = 2, KFLOAT = 3, KNUMBER = 4,
            KDOUBLE = 5, KCHAR = 6, KSTRING = 7, KZSTR = 8, KPADDING = 9, KPADDALIGN = 10;
    private static final int NB = 8;
    private static final int SZINT = 8;
    private static final byte MC = (byte) 0xFF;
    private static final byte PAD = 0;

    private StringFormat() {
    }

    // lstrlib.c: strFormat
    public static LuaValue strFormat(Varargs args) {
        return FMT_BYTEBUF ? strFormatBB(args) : strFormatSB(args);
    }

    // lstrlib.c: strFormat  -  StringBuilder 路径（默认路径，含 FMT_OPT 微优化）
    private static LuaValue strFormatSB(Varargs args) {
        LuaString fmt = args.checkstring(1);
        int fmtLen = fmt.rawlen();
        // 预分配：格式化结果几乎总不短于 fmt（%d/%s/%f 展开后更长），
        // 用 fmtLen + fmtLen/2 + 16 一步到位，避免 StringBuilder 从默认容量 16
        // 反复扩容(每次 Arrays.copyOf 一个新 byte[]) - strFormat 是高频分配点。
        StringBuilder result = new StringBuilder(fmtLen + (fmtLen >> 1) + 16);
        int arg = 2;
        int i = 0;
        // spec 是每个 % 转换的临时收集器（'%'+flags+width+prec），用完即弃。
        // 提到循环外用 setLength(0) 复用，避免每个转换 new 一个 StringBuilder。
        StringBuilder spec = new StringBuilder(24);
        while (i < fmtLen) {
            int c = fmt.contents[i] & 0xFF;
            if (c != '%') {
                // java-only (FMT_OPT): 批量追加连续非'%'字节。
                // 对齐 C 的 luaL_addlstring(&b, strfrmt, runLen)（C 用 memcpy 一次拷贝）。
                // 批量路径用 new String(byte[], off, len, ISO_8859_1)（JDK 9+ latin-1 = Arrays.copyOfRange
                // intrinsify）+ SB.append(String)（System.arraycopy intrinsify）。对长纯文本段
                //（错误消息、debug 信息）收益明显。
                if (FMT_OPT) {
                    int start = i;
                    i++;
                    while (i < fmtLen && (fmt.contents[i] & 0xFF) != '%') i++;
                    int runLen = i - start;
                    if (runLen >= 4) {
                        result.append(new String(fmt.contents, start, runLen, LATIN1));
                    } else {
                        for (int j = start; j < i; j++)
                            result.append((char) (fmt.contents[j] & 0xFF));
                    }
                } else {
                    result.append((char) c);
                    i++;
                }
                continue;
            }
            i++;
            if (i < fmtLen && fmt.contents[i] == '%') {
                result.append('%');
                i++;
                continue;
            }

            spec.setLength(0);
            spec.append('%');

            while (i < fmtLen) {
                int ch = fmt.contents[i] & 0xFF;
                if (ch == '-' || ch == '+' || ch == ' ' || ch == '#' || ch == '0') {
                    spec.append((char) ch);
                    i++;
                } else break;
            }

            int width = -1;
            int widthDigits = 0;
            while (i < fmtLen) {
                int ch = fmt.contents[i] & 0xFF;
                if (ch >= '0' && ch <= '9') {
                    spec.append((char) ch);
                    i++;
                    if (FMT_OPT) {
                        // java diff (FMT_OPT): 直接累加数字，避免 Integer.parseInt(spec.substring(...))
                        // 的子串分配 + 解析开销。语义等价：width 最多 2 位数字，范围 [0, 99]。
                        if (widthDigits == 0) width = 0;
                        if (widthDigits < 2) {
                            width = width * 10 + (ch - '0');
                            widthDigits++;
                        }
                    } else {
                        if (widthDigits < 2) widthDigits++;
                    }
                } else break;
            }
            if (!FMT_OPT && widthDigits > 0) {
                width = Integer.parseInt(spec.substring(spec.length() - widthDigits));
            }

            int precision = -1;
            // lstrlib.c: getformat —— getformat 的 strspn 集含 '.'：宽度后的 [0-9.] 连续段
            // 整体收进 spec（%.5.5d 才能走到 validateFormatSpec 报专用错误）；
            // precision 取第一个点后 ≤2 位，多余段只收集不累计
            int precDigits = 0;
            while (i < fmtLen) {
                int ch = fmt.contents[i] & 0xFF;
                if (ch == '.') {
                    spec.append('.');
                    i++;
                    if (precision < 0) {
                        precision = 0;
                        precDigits = 0;
                    }
                } else if (ch >= '0' && ch <= '9') {
                    spec.append((char) ch);
                    i++;
                    if (precision >= 0 && precDigits < 2) {
                        precision = precision * 10 + (ch - '0');
                        precDigits++;
                    }
                } else break;
            }
            int conv = i < fmtLen ? (fmt.contents[i] & 0xFF) : -1;
            i++;

            // lstrlib.c: getformat —— too-long 与 no value 都先于 conv 有效性检查
            if (spec.length() >= 22) {
                throw LuaErrors.errorObject("invalid format (too long)");
            }
            if (arg > args.narg()) {
                throw LuaErrors.errorObject(LuaErrors.argErrorMessage(arg, "no value"));
            }
            switch (conv) {
                case 'd':
                case 'i':
                case 'u':
                case 'o':
                case 'x':
                case 'X': {
                    // lstrlib.c: str_format —— checkinteger 先于 checkformat
                    long n = LuaErrors.checkLong(args, arg); arg++;
                    validateFormatSpec(spec, conv);
                    if (precision < 0 && spec.length() == 1) {
                        // 无修饰符快路径（%d/%x 等 format 热点，零 Formatter 分配）
                        if (conv == 'd' || conv == 'i') result.append(n);
                        else if (conv == 'u') result.append(Long.toUnsignedString(n));
                        else if (conv == 'o') result.append(Long.toOctalString(n));
                        else if (conv == 'x') result.append(Long.toHexString(n));
                        else appendHexUpper(result, n);  // 'X'
                        break;
                    }
                    result.append(sprintfInt(n, conv, flagsOf(spec), width, precision));
                    break;
                }
                case 'f':
                case 'e':
                case 'E':
                case 'g':
                case 'G': {
                    // lstrlib.c: str_format —— checknumber 先于 checkformat
                    double d = LuaErrors.checkDouble(args, arg); arg++;
                    validateFormatSpec(spec, conv);
                    result.append(sprintfFloat(d, conv, flagsOf(spec), width, precision));
                    break;
                }
                case 'a':
                case 'A': {
                    // lstrlib.c: str_format —— checkformat 先于 checknumber
                    validateFormatSpec(spec, conv);
                    double d = LuaErrors.checkDouble(args, arg); arg++;
                    // lua55-debug 基线（ltests.h #undef lua_number2strx ⇒ num2straux 回退）：
                    // 带任意修饰符的 %a/%A 报错；AGENTS 规定 C 行为对照以 lua55-debug 为准
                    if (spec.length() > 1) {
                        throw LuaErrors.errorObject("modifiers for format %a/%A not implemented");
                    }
                    result.append(formatHexFloat(d, conv == 'A'));
                    break;
                }
                case 'c': {
                    // lstrlib.c: str_format —— checkformat 先于 checkinteger；%c 截断 unsigned char
                    validateFormatSpec(spec, conv);
                    int c2 = (int) LuaErrors.checkLong(args, arg) & 0xFF; arg++;
                    if (spec.length() == 1) {
                        result.append((char) c2);
                        break;
                    }
                    result.append(padToWidth(String.valueOf((char) c2), width,
                            spec.indexOf("-") >= 0));
                    break;
                }
                case 'q': {
                    // lstrlib.c: str_format —— %q 不走 checkformat；修饰符专用错误在前
                    if (spec.length() > 1) {
                        throw LuaErrors.errorObject("specifier '%q' cannot have modifiers");
                    }
                    result.append(literalString(args.arg(arg), arg));
                    arg++;
                    break;
                }
                case 's': {
                    int argNo = arg;
                    LuaValue v = args.arg(arg++);
                    LuaValue ts;
                    LuaValue h = v.metaTag(LuaValue.TOSTRING);
                    if (!h.isnil()) {
                        ts = LuaCall.invokeNoYield(h, v).arg1();
                        if (!ts.isstring()) {
                            throw LuaErrors.errorObject("'__tostring' must return a string");
                        }
                    } else {
                        ts = tolstringValue(v);
                    }
                    LuaString ls = ts.checkstring();
                    if (spec.length() == 1) {
                        // 无修饰符快路径：直接逐字节 append（对齐 C 的 memcpy），%s 是热点
                        byte[] b = ls.contents;
                        int len = ls.shrlen;
                        if (FMT_OPT && len >= 4) {
                            result.append(new String(b, 0, len, LATIN1));
                        } else {
                            for (int j = 0; j < len; j++) result.append((char) (b[j] & 0xFF));
                        }
                        break;
                    }
                    // lstrlib.c: str_format —— zeros 检查（含 #N）先于 checkformat
                    for (int j = 0; j < ls.shrlen; j++) {
                        if (ls.contents[j] == 0) {
                            throw LuaErrors.errorObject(LuaErrors.argErrorMessage(
                                    argNo, "string contains zeros"));
                        }
                    }
                    validateFormatSpec(spec, conv);
                    if (precision < 0 && ls.shrlen >= 100) {
                        // lstrlib.c: str_format —— 无精度且长串直接整串（绕过宽度）
                        result.append(ls.toJavaString());
                        break;
                    }
                    result.append(sprintfStr(ls.toJavaString(), width, precision,
                            spec.indexOf("-") >= 0));
                    break;
                }
                case 'p': {
                    validateFormatSpec(spec, conv);
                    LuaValue v = args.arg(arg++);
                    String pstr;
                    int tt = v.type();
                    if (tt == LuaValue.TNIL || tt == LuaValue.TBOOLEAN || tt == LuaValue.TNUMBER) {
                        pstr = "(null)";
                    } else {
                        pstr = "0x" + Integer.toHexString(System.identityHashCode(v));
                    }
                    result.append(padToWidth(pstr, width, spec.indexOf("-") >= 0));
                    break;
                }
                case '%':
                    result.append('%');
                    break;
                default: {
                    // lstrlib.c: str_format —— form 含 conv 字符；fmt 末尾缺 conv 时无尾字符
                    throw LuaErrors.errorObject("invalid conversion '" + spec
                            + (conv == -1 ? "" : String.valueOf((char) conv))
                            + "' to 'format'");
                }
            }
        }
        // 直接从 StringBuilder(CharSequence) 建 byte[]，省掉 toString() 的中间 String
        // 及其 char[] 拷贝（strFormat 是高频路径，result 每次都新建）。
        if (FMT_OPT && result.length() > 40) {
            // java-only (FMT_OPT): 长结果串（>40 字节，即 LUAI_MAXSHORTLEN）走
            // newStrLatin1 —— s.getBytes(ISO_8859_1) 一次 arraycopy（intrinsify）填 byte[]，
            // 对齐 C 的 luaL_pushresult memcpy。短串（<=40）两者等价，不切换。
            // java diff: 必须用 Latin-1 版 - result 存的是格式化字节的 1:1 char（0-255），
            //   用 UTF-8 的 newStr(String) 会对 0x80-0xFF 字节二次编码（C2/80 序列）造成乱码。
            return LuaString.newStrLatin1(result.toString());
        }
        return LuaString.valueOfLatin1(result);
    }

    // lstrlib.c: strFormat  -  ByteBuf 路径（byte-oriented，对齐 C 的 luaL_Buffer）
    // java diff: ByteBuf 替代 StringBuilder 累积结果：batch text/%s 直写 byte[]，
    // toByteArray() 一次拷贝建 LuaString（对齐 luaL_pushresult）；数字快路径
    // writeLong/writeHex/writeOctal 直转入 buffer，免 Long.toString String 分配。
    private static LuaValue strFormatBB(Varargs args) {
        LuaString fmt = args.checkstring(1);
        int fmtLen = fmt.rawlen();
        ByteBuf result = new ByteBuf(fmtLen + (fmtLen >> 1) + 16);
        // spec 是每个 % 转换的临时收集器（'%'+flags+width+prec），用完即弃。
        // 提到循环外用 setLength(0) 复用，避免每个转换 new 一个 StringBuilder。
        StringBuilder spec = new StringBuilder(24);
        int arg = 2;
        int i = 0;
        while (i < fmtLen) {
            int c = fmt.contents[i] & 0xFF;
            if (c != '%') {
                // java-only: batch text 直写 fmt.contents，零分配（对齐 C 的 luaL_addlstring memcpy）。
                int start = i;
                i++;
                while (i < fmtLen && (fmt.contents[i] & 0xFF) != '%') i++;
                result.write(fmt.contents, start, i - start);
                continue;
            }
            i++;
            if (i < fmtLen && fmt.contents[i] == '%') {
                result.write('%');
                i++;
                continue;
            }

            spec.setLength(0);
            spec.append('%');
            while (i < fmtLen) {
                int ch = fmt.contents[i] & 0xFF;
                if (ch == '-' || ch == '+' || ch == ' ' || ch == '#' || ch == '0') {
                    spec.append((char) ch);
                    i++;
                } else break;
            }
            int width = -1, widthDigits = 0;
            while (i < fmtLen) {
                int ch = fmt.contents[i] & 0xFF;
                if (ch >= '0' && ch <= '9') {
                    spec.append((char) ch);
                    i++;
                    if (widthDigits == 0) width = 0;
                    if (widthDigits < 2) {
                        width = width * 10 + (ch - '0');
                        widthDigits++;
                    }
                } else break;
            }
            int precision = -1;
            // lstrlib.c: getformat —— getformat 的 strspn 集含 '.'：宽度后的 [0-9.] 连续段
            // 整体收进 spec（%.5.5d 才能走到 validateFormatSpec 报专用错误）；
            // precision 取第一个点后 ≤2 位，多余段只收集不累计
            int precDigits = 0;
            while (i < fmtLen) {
                int ch = fmt.contents[i] & 0xFF;
                if (ch == '.') {
                    spec.append('.');
                    i++;
                    if (precision < 0) {
                        precision = 0;
                        precDigits = 0;
                    }
                } else if (ch >= '0' && ch <= '9') {
                    spec.append((char) ch);
                    i++;
                    if (precision >= 0 && precDigits < 2) {
                        precision = precision * 10 + (ch - '0');
                        precDigits++;
                    }
                } else break;
            }
            int conv = i < fmtLen ? (fmt.contents[i] & 0xFF) : -1;
            i++;
            // lstrlib.c: getformat —— too-long 与 no value 先于 conv 有效性（同 SB 路径）
            if (spec.length() >= 22) throw LuaErrors.errorObject("invalid format (too long)");
            if (arg > args.narg()) {
                throw LuaErrors.errorObject(LuaErrors.argErrorMessage(arg, "no value"));
            }
            switch (conv) {
                case 'd':
                case 'i':
                case 'u':
                case 'o':
                case 'x':
                case 'X': {
                    long n = LuaErrors.checkLong(args, arg); arg++;  // checkinteger 先于 checkformat
                    validateFormatSpec(spec, conv);
                    if (precision < 0 && spec.length() == 1) {
                        // 无修饰符快路径：直接转字节入 buffer（零分配）
                        if (conv == 'd' || conv == 'i') result.writeLong(n);
                        else if (conv == 'u') result.writeStr(Long.toUnsignedString(n));
                        else if (conv == 'o') result.writeOctal(n);
                        else if (conv == 'x') result.writeHexLower(n);
                        else result.writeHexUpper(n);  // 'X'
                        break;
                    }
                    result.writeStr(sprintfInt(n, conv, flagsOf(spec), width, precision));
                    break;
                }
                case 'f':
                case 'e':
                case 'E':
                case 'g':
                case 'G': {
                    double d = LuaErrors.checkDouble(args, arg); arg++;  // checknumber 先于 checkformat
                    validateFormatSpec(spec, conv);
                    result.writeStr(sprintfFloat(d, conv, flagsOf(spec), width, precision));
                    break;
                }
                case 'a':
                case 'A': {
                    validateFormatSpec(spec, conv);
                    double d = LuaErrors.checkDouble(args, arg); arg++;
                    // lua55-debug 基线：带修饰符的 %a/%A 报错（同 SB 路径）
                    if (spec.length() > 1) {
                        throw LuaErrors.errorObject("modifiers for format %a/%A not implemented");
                    }
                    result.writeStr(formatHexFloat(d, conv == 'A'));
                    break;
                }
                case 'c': {
                    validateFormatSpec(spec, conv);
                    int c2 = (int) LuaErrors.checkLong(args, arg) & 0xFF; arg++;
                    if (spec.length() == 1) {
                        result.write(c2);
                    } else {
                        result.writeStr(padToWidth(String.valueOf((char) c2), width,
                                spec.indexOf("-") >= 0));
                    }
                    break;
                }
                case 'q': {
                    if (spec.length() > 1) {
                        throw LuaErrors.errorObject("specifier '%q' cannot have modifiers");
                    }
                    result.writeStr(literalString(args.arg(arg), arg));
                    arg++;
                    break;
                }
                case 's': {
                    int argNo = arg;
                    LuaValue v = args.arg(arg++);
                    LuaValue ts;
                    LuaValue h = v.metaTag(LuaValue.TOSTRING);
                    if (!h.isnil()) {
                        ts = LuaCall.invokeNoYield(h, v).arg1();
                        if (!ts.isstring()) throw LuaErrors.errorObject("'__tostring' must return a string");
                    } else ts = tolstringValue(v);
                    LuaString ls = ts.checkstring();
                    if (spec.length() == 1) {
                        // 无修饰符快路径：直写 ls.contents（零分配，对齐 memcpy）
                        result.write(ls.contents, 0, ls.shrlen);
                    } else {
                        for (int j = 0; j < ls.shrlen; j++) {
                            if (ls.contents[j] == 0) {
                                throw LuaErrors.errorObject(LuaErrors.argErrorMessage(
                                        argNo, "string contains zeros"));
                            }
                        }
                        validateFormatSpec(spec, conv);
                        if (precision < 0 && ls.shrlen >= 100) {
                            result.writeStr(ls.toJavaString());
                        } else {
                            result.writeStr(sprintfStr(ls.toJavaString(), width, precision,
                                    spec.indexOf("-") >= 0));
                        }
                    }
                    break;
                }
                case 'p': {
                    validateFormatSpec(spec, conv);
                    LuaValue v = args.arg(arg++);
                    String pstr;
                    int tt = v.type();
                    if (tt == LuaValue.TNIL || tt == LuaValue.TBOOLEAN || tt == LuaValue.TNUMBER)
                        pstr = "(null)";
                    else pstr = "0x" + Integer.toHexString(System.identityHashCode(v));
                    result.writeStr(padToWidth(pstr, width, spec.indexOf("-") >= 0));
                    break;
                }
                case '%':
                    result.write('%');
                    break;
                default:
                    throw LuaErrors.errorObject("invalid conversion '" + spec
                            + (conv == -1 ? "" : String.valueOf((char) conv))
                            + "' to 'format'");
            }
        }
        // 最终：ByteBuf -> byte[] 一次拷贝（对齐 C 的 luaL_pushresult）。
        byte[] b = result.toByteArray();
        return LuaString.newLstr(b, 0, b.length);
    }

    // lstrlib.c: tolstringValue
    private static LuaString tolstringValue(LuaValue v) {
        // 字符串先短路：isstring 放在 isnumber 之前，避免每个 %s 字符串实参
        // 白跑一遍 scannumber 逐字节扫描（数字串还额外 new 一个 LuaNumber）。
        // 语义不变：LuaString 在两个分支下都返回自身。
        if (v.isstring()) return v.checkstring();
        if (v.isnumber()) return LuaString.newStr(v.toJavaString());
        if (v.isstring()) return v.checkstring();
        if (v.isboolean()) return LuaString.newStr(Boolean.toString(v.toboolean()));
        if (v.isnil()) return LuaString.newStr("nil");
        String kind = LuaValue.objTypeName(v);
        return LuaString.newStr(kind + ": 0x" + Integer.toHexString(System.identityHashCode(v)));
    }

    // ═══════════ C printf 语义的共享冷路径（SB/BB 两路径共用，消灭双路径漂移）═══════════
    // 仅在「有修饰符或精度」时调用；无修饰符快路径留在各路径内（见各处 perf 注释）。
    // 全部按 glibc/POSIX 语义实现，期望值经 lua55-debug 对拍（format_align.lua）。

    /** 从 '%'+flags+width[.prec] 里只取 flag 字符段（宽度数字不是 '0' flag）。 */
    private static String flagsOf(CharSequence spec) {
        int i = 1;
        while (i < spec.length()) {
            char c = spec.charAt(i);
            if (c == '-' || c == '+' || c == ' ' || c == '#' || c == '0') i++;
            else break;
        }
        return spec.toString().substring(1, i);
    }

    private static String padToWidth(String s, int width, boolean leftAlign) {
        if (width <= s.length() || width < 0) return s;
        int pad = width - s.length();
        return leftAlign ? s + " ".repeat(pad) : " ".repeat(pad) + s;
    }

    // lstrlib.c: str_format intcase —— lld/llu/llo/llx 全修饰符
    private static String sprintfInt(long n, int conv, String flags, int width, int precision) {
        boolean minus = flags.indexOf('-') >= 0;
        boolean plus = flags.indexOf('+') >= 0;      // 仅 d/i（flags 表已按 conv 过滤）
        boolean space = flags.indexOf(' ') >= 0;
        boolean alt = flags.indexOf('#') >= 0;
        boolean zero = flags.indexOf('0') >= 0;
        boolean neg = (conv == 'd' || conv == 'i') && n < 0;
        String digits;
        if (conv == 'd' || conv == 'i') {
            digits = neg ? Long.toString(n).substring(1) : Long.toString(n);
        } else if (conv == 'u') {
            digits = Long.toUnsignedString(n);
        } else if (conv == 'o') {
            digits = Long.toOctalString(n);
        } else if (conv == 'x') {
            digits = Long.toHexString(n);
        } else {
            digits = Long.toHexString(n).toUpperCase();  // 'X'
        }
        // C：precision==0 且值为 0 → 数字部分为空（glibc）；alt-o 例外（下行先提精度）
        if (precision == 0 && n == 0 && !(alt && conv == 'o')) digits = "";
        // POSIX：'#' with o —— 必要时把精度提高到首位为 0（glibc %#.0o of 0 → "0"）
        if (alt && conv == 'o' && precision < digits.length() + 1) precision = digits.length() + 1;
        if (precision > digits.length()) {
            digits = "0".repeat(precision - digits.length()) + digits;
        }
        String sign = neg ? "-" : (plus ? "+" : (space ? " " : ""));
        String prefix = sign;
        // '#' with x/X：值为 0 不加前缀（C printf）
        if (alt && conv == 'x' && n != 0) prefix += "0x";
        else if (alt && conv == 'X' && n != 0) prefix += "0X";
        // '0' flag：无 precision 时零填充（prefix 与 digits 之间）；有 precision 或 '-' 时忽略
        if (zero && precision < 0 && !minus && width > prefix.length() + digits.length()) {
            digits = "0".repeat(width - prefix.length() - digits.length()) + digits;
        }
        return padToWidth(prefix + digits, width, minus);
    }

    // lstrlib.c: str_format —— inf/nan 缩写、half-even 舍入（BigDecimal 精确二进制值）、全修饰符
    private static String sprintfFloat(double d, int conv, String flags, int width, int precision) {
        boolean minus = flags.indexOf('-') >= 0;
        boolean plus = flags.indexOf('+') >= 0;
        boolean space = flags.indexOf(' ') >= 0;
        boolean alt = flags.indexOf('#') >= 0;
        boolean zero = flags.indexOf('0') >= 0;
        boolean upper = conv == 'E' || conv == 'G';
        if (Double.isNaN(d) || Double.isInfinite(d)) {
            // C sprintf 缩写形态；'+'/' ' 与 width 生效；'0' 对 inf/nan 用空格（glibc）
            String s;
            if (Double.isNaN(d)) s = "nan";
            else s = d > 0 ? "inf" : "-inf";
            if (upper) s = s.toUpperCase();
            if (!s.startsWith("-")) s = plus ? "+" + s : (space ? " " + s : s);
            return padToWidth(s, width, minus);
        }
        String body;
        if (conv == 'g' || conv == 'G') {
            // C %.Ng 精确实现（keepZeros='#'：glibc %#g 保留到 precision 位）
            body = LuaFloat.cFormatG(d,
                    precision < 0 ? 6 : Math.max(precision, 1), alt);
            if (upper) body = body.toUpperCase();
        } else if (conv == 'e' || conv == 'E') {
            // C %.Ne 精确实现（BigDecimal.setScale 对小指数值会归零，不可用）
            body = LuaFloat.cFormatE(d, precision < 0 ? 6 : precision, upper);
            if (alt && body.indexOf('.') < 0) {  // glibc %#.0e -> "1.e+00"
                int eIdx = body.indexOf(upper ? 'E' : 'e');
                body = body.substring(0, eIdx) + "." + body.substring(eIdx);
            }
        } else {
            int p = precision < 0 ? 6 : precision;
            BigDecimal bd = new BigDecimal(d)
                    .setScale(p, RoundingMode.HALF_EVEN);
            body = sciOrPlain(bd, conv, p, alt);
            if (conv == 'E') body = body.toUpperCase();
        }
        String sign = body.startsWith("-") ? "" : (plus ? "+" : (space ? " " : ""));
        String signed = sign + body;
        // java diff 注：'0' flag 被 precision 抑制的规则只属于整数转换（sprintf）；
        // 浮点（f/e/g）0 填充与 precision 并存（"%+#014.0f" -> "+000000000100."）
        if (zero && !minus && width > signed.length()) {
            // '0' 填充在符号与数字之间
            int idx = signed.length() - body.length();
            signed = signed.substring(0, idx)
                    + "0".repeat(width - signed.length()) + signed.substring(idx);
        }
        return padToWidth(signed, width, minus);
    }

    /** %f（plain 形态）与 %e/%E（科学形态）共用：bd 已按 p 位小数 HALF_EVEN 舍入。 */
    private static String sciOrPlain(BigDecimal bd, int conv, int p, boolean alt) {
        if (conv == 'f' || conv == 'F') {
            String s = bd.toPlainString();  // 含 '-'；p==0 无 '.'
            // glibc %#.0f → "1."（强制小数点）
            if (alt && p == 0 && !s.endsWith(".")) s = s + ".";
            return s;
        }
        boolean neg = bd.signum() < 0;
        String plain = bd.abs().toPlainString();
        int dot = plain.indexOf('.');
        int intLen = dot < 0 ? plain.length() : dot;
        String digits = plain.replace(".", "");
        int f = 0;
        while (f < digits.length() - 1 && digits.charAt(f) == '0') f++;
        int exp;
        if (bd.signum() == 0) {
            exp = 0;
        } else {
            exp = intLen - 1 - f;
        }
        // 有效数字第一位 + p 位小数（右补零）
        StringBuilder sb = new StringBuilder();
        if (neg) sb.append('-');
        sb.append(digits.charAt(f));
        if (p > 0) {
            sb.append('.');
            for (int i = 1; i <= p; i++) {
                int pos = f + i;
                sb.append(pos < digits.length() ? digits.charAt(pos) : '0');
            }
        } else if (alt) {
            sb.append('.');  // glibc %#.0e → "1.e+00"
        }
        sb.append('e');
        sb.append(exp >= 0 ? '+' : '-');
        int a = Math.abs(exp);
        if (a < 10) sb.append('0');  // C99：指数至少 2 位
        sb.append(a);
        return sb.toString();
    }

    /** lstrlib.c: str_format 经 glibc %g —— P 位有效数字、f/e 形态按 X 阈值选择、非 '#' 去尾零。 */
    private static String formatG(double d, int precision, boolean alt, boolean upper) {
        int p = precision == 0 ? 1 : (precision < 0 ? 6 : precision);
        BigDecimal bd = new BigDecimal(d)
                .round(new MathContext(p, RoundingMode.HALF_EVEN));
        int x = bd.signum() == 0 ? 0 : bd.precision() - bd.scale() - 1;
        String body;
        if (x < -4 || x >= p) {
            BigDecimal e = bd.setScale(p - 1, RoundingMode.HALF_EVEN);
            body = sciOrPlain(e, 'e', p - 1, alt);
            if (!alt) body = stripGZerosKeepExp(body);
        } else {
            BigDecimal f = bd.setScale(p - 1 - x, RoundingMode.HALF_EVEN);
            body = f.toPlainString();
            if (!alt) body = stripGZerosKeepExp(body);
        }
        return upper ? body.toUpperCase() : body;
    }

    private static String stripGZerosKeepExp(String s) {
        int eIdx = s.indexOf('e');
        if (eIdx < 0) eIdx = s.indexOf('E');
        String mant = eIdx >= 0 ? s.substring(0, eIdx) : s;
        String exp = eIdx >= 0 ? s.substring(eIdx) : "";
        int dotIdx = mant.indexOf('.');
        if (dotIdx >= 0) {
            int end = mant.length();
            while (end > dotIdx + 1 && mant.charAt(end - 1) == '0') end--;
            if (end == dotIdx + 1) end = dotIdx;
            mant = mant.substring(0, end);
        }
        return mant + exp;
    }

    // lstrlib.c: str_format 's' with modifiers —— 精度截断 + 宽度（C flags 表只允许 '-'）
    private static String sprintfStr(String s, int width, int precision, boolean leftAlign) {
        if (precision >= 0 && s.length() > precision) s = s.substring(0, precision);
        return padToWidth(s, width, leftAlign);
    }

    // lstrlib.c: addliteral —— 严格 lua_type 分派（字符串不做数字强转：
    // C 的 addliteral 按严格类型 switch，"%q" of "5" 是带引号字符串）
    private static String literalString(LuaValue v, int argIdx) {
        if (v.isinteger()) {
            long n = v.tolong();
            return n == Long.MIN_VALUE ? "0x" + Long.toHexString(n) : Long.toString(n);
        }
        if (v.type() == LuaValue.TNUMBER) {  // 严格浮点（LuaString.isnumber 是强转语义）
            double d = v.todouble();
            if (Double.isInfinite(d)) return d > 0 ? "1e9999" : "-1e9999";
            if (Double.isNaN(d)) return "(0/0)";
            return formatHexFloat(d, false);
        }
        if (v.isstring()) {
            LuaString ls = v.checkstring();
            byte[] b = ls.contents;
            StringBuilder sb = new StringBuilder(ls.shrlen + 2);
            sb.append('"');
            for (int j = 0; j < ls.shrlen; j++) {
                int cb = b[j] & 0xFF;
                if (cb == '"' || cb == '\\' || cb == '\n') {
                    sb.append('\\').append((char) cb);
                } else if (cb < 32 || cb == 127) {
                    boolean nextIsDigit = (j + 1 < ls.shrlen)
                            && (b[j + 1] & 0xFF) >= '0' && (b[j + 1] & 0xFF) <= '9';
                    if (nextIsDigit) {
                        String d = Integer.toString(cb);
                        while (d.length() < 3) d = "0" + d;
                        sb.append('\\').append(d);
                    } else {
                        sb.append('\\').append(cb);
                    }
                } else {
                    sb.append((char) cb);
                }
            }
            sb.append('"');
            return sb.toString();
        }
        if (v.isnil()) return "nil";
        if (v.isboolean()) return Boolean.toString(v.toboolean());
        throw LuaErrors.errorObject(LuaErrors.argErrorMessage(
                argIdx, "value has no literal form"));
    }

    private static void appendHexUpper(StringBuilder out, long n) {
        if (n == 0) {
            out.append('0');
            return;
        }
        // 无符号 64 位：最多 16 个十六进制位，先算起始非零位
        int start = 0;
        while (start < 16 && ((n >>> ((15 - start) * 4)) & 0xF) == 0) start++;
        for (int i = start; i < 16; i++) {
            out.append(HEX_UPPER[(int) ((n >>> ((15 - i) * 4)) & 0xF)]);
        }
    }

    // java-only: 后处理 Java String.format("%a") 输出，对齐 C printf 格式。
    // Java: "0x1.0p0" -> C: "0x1p+0"（去 .0 尾数 + 加 + 号）
    // Java: "0x1.8p1" -> C: "0x1.8p+1"（保留尾数 + 加 + 号）
    private static String fixHexFloatExponent(String s) {
        // 找 'p' 或 'P'（指数分隔符）
        int pIdx = s.indexOf('p');
        if (pIdx < 0) pIdx = s.indexOf('P');
        if (pIdx < 0) return s;
        // 检查指数部分是否已有符号
        int expStart = pIdx + 1;
        boolean hasSign = expStart < s.length() && (s.charAt(expStart) == '+' || s.charAt(expStart) == '-');
        // 去掉 ".0" 尾数（当且仅当小数点后全是 0 直到 p/P）
        int dotIdx = s.indexOf('.');
        String significand = s.substring(0, pIdx);
        if (dotIdx >= 0) {
            String frac = s.substring(dotIdx + 1, pIdx);
            // 如果小数部分全是 0，去掉 .0...
            boolean allZero = true;
            for (int i = 0; i < frac.length(); i++) {
                if (frac.charAt(i) != '0') {
                    allZero = false;
                    break;
                }
            }
            if (allZero) significand = s.substring(0, dotIdx);
        }
        String exp = s.substring(expStart);
        String sign = hasSign ? "" : "+";
        char pCh = s.charAt(pIdx);
        return significand + pCh + sign + exp;
    }

    // lstrlib.c: formatHexFloat
    private static String formatHexFloat(double d, boolean upper) {
        if (Double.isNaN(d)) return upper ? "NAN" : "nan";
        if (Double.isInfinite(d)) {
            if (d < 0) return upper ? "-INF" : "-inf";
            return upper ? "INF" : "inf";
        }
        if (d == 0.0) {
            return (Double.doubleToRawLongBits(d) < 0)
                    ? (upper ? "-0X0P+0" : "-0x0p+0")
                    : (upper ? "0X0P+0" : "0x0p+0");
        }
        long bits = Double.doubleToRawLongBits(d);
        boolean neg = (bits < 0);
        int exp2 = (int) ((bits >> 52) & 0x7FF) - 1023;
        long frac = bits & ((1L << 52) - 1);
        StringBuilder sb = new StringBuilder();
        if (neg) sb.append('-');
        sb.append(upper ? "0X" : "0x");
        // C 输出为 0x{leading_bit_hex}{remaining_bits_hex}p{exp2}，正规数隐含前导位 '1'。
        // lstrlib.c: quotefloat —— 次正规数按指数域 e==0 判定（按 exp2==-1022 判会误捕
        // 最小正规数且漏掉真次正规）：尾数最高位左移到 bit52 规格化为前导 1，指数 = -1022-(52-k)。
        int leadingBit;
        int pExp;
        long fracMask;
        int eField = (int) ((bits >> 52) & 0x7FF);
        if (eField == 0) {
            int k = 51 - (Long.numberOfLeadingZeros(frac) - 12);  // MSB 位置（bit51..bit0）
            frac <<= 52 - k;
            exp2 = -1022 - (52 - k);
        }
        leadingBit = 1;
        pExp = exp2;
        fracMask = (1L << 52) - 1;
        frac &= fracMask;
        // 将 52 位尾数转换为 13 个十六进制数字（每位 4 bit）。
        String hexFrac = Long.toHexString(frac).toLowerCase();
        while (hexFrac.length() < 13) hexFrac = "0" + hexFrac;
        // java diff: 去掉全部尾随零（C printf 当尾数为 0 时不输出小数点，如 "0x1p+0"）；
        // 若保留 ".0"（"至少保留一位"）会输出 "0x1.0p+0"，与 C 不符。
        while (hexFrac.endsWith("0")) hexFrac = hexFrac.substring(0, hexFrac.length() - 1);
        sb.append(leadingBit);
        if (!hexFrac.isEmpty()) sb.append('.').append(hexFrac);
        sb.append(upper ? 'P' : 'p');
        if (pExp >= 0) sb.append('+').append(pExp);
        else sb.append(pExp);
        return upper ? sb.toString().toUpperCase() : sb.toString();
    }

    // lstrlib.c: padExponent
    private static String padExponent(String s, int minExpDigits, boolean uppercase) {
        int eIdx = s.indexOf('e');
        if (eIdx < 0) eIdx = s.indexOf('E');
        if (eIdx < 0) {
            // 无指数存在。
            if (uppercase) return s.toUpperCase();
            return s.toLowerCase();
        }
        char eCh = uppercase ? 'E' : 'e';
        int signPos = eIdx + 1;
        if (signPos >= s.length()) return s;
        char sign = s.charAt(signPos);
        int digitStart = signPos;
        if (sign == '+' || sign == '-') digitStart = signPos + 1;
        int expLen = s.length() - digitStart;
        int pad = minExpDigits - expLen;
        if (pad <= 0 && s.charAt(eIdx) == eCh) return s;
        StringBuilder sb = new StringBuilder();
        sb.append(s, 0, eIdx).append(eCh);
        if (sign == '+' || sign == '-') sb.append(sign);
        for (int k = 0; k < pad; k++) sb.append('0');
        sb.append(s, digitStart, s.length());
        return sb.toString();
    }

    // lstrlib.c: stripGZeroes
    private static String stripGZeroes(String formatted, int convLower) {
        if (!formatted.contains(".") && !formatted.contains("E") && !formatted.contains("e")) {
            return formatted;
        }
        // 查找指数（如果存在）。
        int eIdx = formatted.indexOf('E');
        if (eIdx < 0) eIdx = formatted.indexOf('e');
        String intFracPart = eIdx >= 0 ? formatted.substring(0, eIdx) : formatted;
        String expPart = eIdx >= 0 ? formatted.substring(eIdx) : "";
        // 去除尾随零。
        int dotIdx = intFracPart.indexOf('.');
        if (dotIdx >= 0) {
            int end = intFracPart.length();
            while (end > dotIdx + 1 && intFracPart.charAt(end - 1) == '0') end--;

            if (end == dotIdx + 1) end = dotIdx;
            intFracPart = intFracPart.substring(0, end);
        }
        boolean uppercase = Character.isUpperCase(convLower);
        if (uppercase) expPart = expPart.toUpperCase();
        else expPart = expPart.toLowerCase();
        return intFracPart + expPart;
    }

    // java-only
    private static boolean isDigit(int c) {
        return c >= '0' && c <= '9';
    }

    // lstrlib.c: getnum
    // java diff: return long directly instead of new long[]{a} to avoid per-call allocation
    private static long getnum(String fmt, int[] pos, long df) {
        if (pos[0] >= fmt.length() || !isDigit(fmt.charAt(pos[0]))) return df;
        long a = 0;
        while (pos[0] < fmt.length() && isDigit(fmt.charAt(pos[0])) && a <= (Long.MAX_VALUE - 9) / 10) {
            a = a * 10 + (fmt.charAt(pos[0]++) - '0');
        }
        return a;
    }

    // lstrlib.c: getnumlimit
    private static int getnumlimit(Header h, String fmt, int[] pos, int df) {
        long sz = getnum(fmt, pos, df);
        if (sz < 1 || sz > 16)
            throw LuaErrors.errorObject("integral size (" + sz + ") out of limits [1,16]");
        return (int) sz;
    }

    // lstrlib.c: getoption
    // java diff: write into reusable long[2] out param instead of returning new long[] per call
    private static void getoption(Header h, String fmt, int[] pos, long[] out) {
        if (pos[0] >= fmt.length()) {
            out[0] = KNOP;
            out[1] = 0;
            return;
        }
        int opt = fmt.charAt(pos[0]++);
        switch (opt) {
            case 'b':
                out[0] = KINT;
                out[1] = 1;
                return;
            case 'B':
                out[0] = KUINT;
                out[1] = 1;
                return;
            case 'h':
                out[0] = KINT;
                out[1] = 2;
                return;
            case 'H':
                out[0] = KUINT;
                out[1] = 2;
                return;
            case 'l':
                out[0] = KINT;
                out[1] = 8;
                return;
            case 'L':
                out[0] = KUINT;
                out[1] = 8;
                return;
            case 'j':
                out[0] = KINT;
                out[1] = 8;
                return;
            case 'J':
                out[0] = KUINT;
                out[1] = 8;
                return;
            case 'T':
                out[0] = KUINT;
                out[1] = 8;
                return;
            case 'f':
                out[0] = KFLOAT;
                out[1] = 4;
                return;
            case 'n':
                out[0] = KNUMBER;
                out[1] = 8;
                return;
            case 'd':
                out[0] = KDOUBLE;
                out[1] = 8;
                return;
            case 'i':
                out[0] = KINT;
                out[1] = getnumlimit(h, fmt, pos, 4);
                return;
            case 'I':
                out[0] = KUINT;
                out[1] = getnumlimit(h, fmt, pos, 4);
                return;
            case 's':
                out[0] = KSTRING;
                out[1] = getnumlimit(h, fmt, pos, 8);
                return;
            case 'c': {
                long r = getnum(fmt, pos, -1);
                if (r == -1) throw LuaErrors.errorObject("missing size for format option 'c'");
                out[0] = KCHAR;
                out[1] = r;
                return;
            }
            case 'z':
                out[0] = KZSTR;
                out[1] = 0;
                return;
            case 'x':
                out[0] = KPADDING;
                out[1] = 1;
                return;
            case 'X':
                out[0] = KPADDALIGN;
                out[1] = 0;
                return;
            case ' ':
                out[0] = KNOP;
                out[1] = 0;
                return;
            case '<':
                h.islittle = true;
                out[0] = KNOP;
                out[1] = 0;
                return;
            case '>':
                h.islittle = false;
                out[0] = KNOP;
                out[1] = 0;
                return;
            case '=':
                h.islittle = true;
                out[0] = KNOP;
                out[1] = 0;
                return;
            case '!': {
                h.maxalign = getnumlimit(h, fmt, pos, 8);
                out[0] = KNOP;
                out[1] = 0;
                return;
            }
            default:
                throw LuaErrors.errorObject("invalid format option '" + (char) opt + "'");
        }
    }

    // lstrlib.c: getdetails
    // java diff: write into reusable long[3] out param instead of returning new long[] per call
    private static void getdetails(Header h, long totalsize, String fmt, int[] pos, long[] out) {
        getoption(h, fmt, pos, out);
        int opt = (int) out[0];
        long size = out[1];
        long align = size;
        if (opt == KPADDALIGN) {
            if (pos[0] >= fmt.length()) throw LuaErrors.errorObject("invalid next option for option 'X'");
            getoption(h, fmt, pos, out);  // overwrites out[0], out[1] with next option
            align = out[1];
            if (out[0] == KCHAR || align == 0)
                throw LuaErrors.errorObject("invalid next option for option 'X'");
        }
        int ntoalign = 0;
        if (align > 1 && opt != KCHAR) {
            if (align > h.maxalign) align = h.maxalign;
            if ((align & (align - 1)) != 0)
                throw LuaErrors.errorObject("format asks for alignment not power of 2");
            long szmoda = totalsize & (align - 1);
            ntoalign = (int) ((align - szmoda) & (align - 1));
        }
        out[0] = opt;
        out[1] = size;
        out[2] = ntoalign;
    }

    // lstrlib.c: packint
    private static void packint(ByteArrayOutputStream b, long n, boolean islittle, int size, boolean neg) {
        byte[] buff = new byte[size];
        int i = 0;
        buff[islittle ? 0 : size - 1] = (byte) (n & MC);
        for (i = 1; i < size; i++) {
            n >>= NB;
            buff[islittle ? i : size - 1 - i] = (byte) (n & MC);
        }
        if (neg && size > SZINT) {
            for (i = SZINT; i < size; i++)
                buff[islittle ? i : size - 1 - i] = MC;
        }
        b.write(buff, 0, size);
    }


    // lstrlib.c: validateFormatSpec
    private static void validateFormatSpec(StringBuilder spec, int conv) {
        String flags;
        boolean precisionAllowed;
        switch (conv) {
            case 'a':
            case 'A':
            case 'e':
            case 'E':
            case 'f':
            case 'g':
            case 'G':
                flags = "-+#0 ";
                precisionAllowed = true;
                break;
            case 'd':
            case 'i':
                flags = "-+0 ";
                precisionAllowed = true;
                break;
            case 'u':
                flags = "-0";
                precisionAllowed = true;
                break;
            case 'o':
            case 'x':
            case 'X':
                flags = "-#0";
                precisionAllowed = true;
                break;
            case 'c':
                flags = "-";
                precisionAllowed = false;
                break;
            case 'p':
                flags = "-";
                precisionAllowed = false;
                break;
            case 's':
                flags = "-";
                precisionAllowed = true;
                break;
            default:
                flags = "";
                precisionAllowed = false;
                break;
        }
        int idx = 1;
        int specLen = spec.length();

        while (idx < specLen && flags.indexOf(spec.charAt(idx)) >= 0) idx++;

        if (idx < specLen && spec.charAt(idx) != '0') {

            if (idx < specLen && Character.isDigit(spec.charAt(idx))) {
                idx++;
                if (idx < specLen && Character.isDigit(spec.charAt(idx))) idx++;
            }

            if (precisionAllowed && idx < specLen && spec.charAt(idx) == '.') {
                idx++;
                if (idx < specLen && Character.isDigit(spec.charAt(idx))) {
                    idx++;
                    if (idx < specLen && Character.isDigit(spec.charAt(idx))) idx++;
                }
            }
        }

        if (idx != specLen) {
            throw LuaErrors.errorObject("invalid conversion specification: '" + spec + (char) conv + "'");
        }
    }

    // lstrlib.c: strPack
    public static LuaValue strPack(Varargs args) {
        String fmt = args.checkJavaString(1);
        Header h = new Header();
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        int arg = 2;
        long totalsize = 0;
        int[] pos = {0};
        long[] out = new long[3];
        while (pos[0] < fmt.length()) {
            getdetails(h, totalsize, fmt, pos, out);
            int opt = (int) out[0];
            long size = out[1];
            int ntoalign = (int) out[2];

            if (size + ntoalign > Long.MAX_VALUE - totalsize) {
                throw LuaErrors.errorObject("result too long");
            }
            totalsize += ntoalign + size;

            for (int i = 0; i < ntoalign; i++) b.write(PAD);
            switch (opt) {
                case KINT: {
                    long n = LuaErrors.checkLong(args, arg); arg++;
                    if (size < SZINT) {
                        long lim = 1L << (size * NB - 1);
                        if (n < -lim || n >= lim) throw LuaErrors.errorObject("integer overflow");
                    }
                    packint(b, n, h.islittle, (int) size, n < 0);
                    break;
                }
                case KUINT: {
                    long n = LuaErrors.checkLong(args, arg); arg++;
                    if (size < SZINT) {
                        long lim = 1L << (size * NB);
                        if (Long.compareUnsigned(n, lim) >= 0)
                            throw LuaErrors.errorObject("unsigned overflow");
                    }
                    packint(b, n, h.islittle, (int) size, false);
                    break;
                }
                case KFLOAT: {
                    float f = (float) LuaErrors.checkDouble(args, arg); arg++;
                    byte[] bytes = new byte[4];
                    ByteBuffer.wrap(bytes).order(h.islittle ? ByteOrder.LITTLE_ENDIAN : ByteOrder.BIG_ENDIAN).putFloat(f);
                    b.write(bytes, 0, 4);
                    break;
                }
                case KNUMBER:
                case KDOUBLE: {
                    double d = LuaErrors.checkDouble(args, arg); arg++;
                    byte[] bytes = new byte[8];
                    ByteBuffer.wrap(bytes).order(h.islittle ? ByteOrder.LITTLE_ENDIAN : ByteOrder.BIG_ENDIAN).putDouble(d);
                    b.write(bytes, 0, 8);
                    break;
                }
                case KCHAR: {
                    LuaString s = args.arg(arg++).checkstring();
                    if (s.shrlen > size) throw LuaErrors.errorObject("string longer than given size");
                    b.write(s.contents, 0, s.shrlen);
                    for (int i = s.shrlen; i < (int) size; i++) b.write(PAD);
                    break;
                }
                case KSTRING: {
                    LuaString s = args.arg(arg++).checkstring();
                    int len = s.shrlen;

                    if (size < SZINT && len >= (1L << (size * NB))) {
                        throw LuaErrors.errorObject("string length does not fit in given size");
                    }
                    packint(b, len, h.islittle, (int) size, false);
                    b.write(s.contents, 0, s.shrlen);
                    totalsize += len;
                    break;
                }
                case KZSTR: {
                    LuaString s = args.arg(arg++).checkstring();
                    for (int i = 0; i < s.shrlen; i++) {
                        if (s.contents[i] == 0) throw LuaErrors.errorObject("string contains zeros");
                    }
                    b.write(s.contents, 0, s.shrlen);
                    b.write(0);
                    totalsize += s.shrlen + 1;
                    break;
                }
                case KPADDING:
                    b.write(PAD);
                    break;
                case KPADDALIGN:
                case KNOP:
                    break;
            }
        }
        byte[] arr = b.toByteArray();
        return LuaString.newLstr(arr, 0, arr.length);
    }

    // lstrlib.c: strPackSize
    public static LuaValue strPackSize(Varargs args) {
        String fmt = args.checkJavaString(1);
        Header h = new Header();
        long totalsize = 0;
        int[] pos = {0};
        long[] out = new long[3];
        while (pos[0] < fmt.length()) {
            getdetails(h, totalsize, fmt, pos, out);
            int opt = (int) out[0];
            long size = out[1];
            int ntoalign = (int) out[2];
            if (opt == KSTRING || opt == KZSTR) {
                throw LuaErrors.errorObject("variable-length format");
            }
            size += ntoalign;
            // lstrlib.c: str_packsize —— 用 MAX_SIZE-size 检查溢出；
            // java diff: Java totalsize 是 long，用 Long.MAX_VALUE-size 防溢出，语义等价。
            if (totalsize > Long.MAX_VALUE - size) {
                throw LuaErrors.errorObject("format result too large");
            }
            totalsize += size;
        }
        return LuaInteger.valueOf(totalsize);
    }

    // lstrlib.c: strUnpack
    public static Varargs strUnpack(Varargs args) {
        String fmt = args.checkJavaString(1);
        LuaString s = args.checkstring(2);
        int ld = s.shrlen;
        int pos = args.arg(3).isnil() ? 1 : args.arg(3).checkint();
        pos = StringLib.posrelat(pos, ld);
        if (pos - 1 > ld) throw LuaErrors.errorObject("initial position out of string");
        Header h = new Header();
        ArrayList<LuaValue> results = new ArrayList<>();
        int[] fpos = {0};
        long[] out = new long[3];
        while (fpos[0] < fmt.length()) {
            long totalsize = pos - 1;
            getdetails(h, totalsize, fmt, fpos, out);
            int opt = (int) out[0];
            long size = out[1];
            int ntoalign = (int) out[2];

            if (ntoalign + size > ld - (pos - 1)) {
                throw LuaErrors.errorObject("data string too short");
            }
            pos += ntoalign;
            switch (opt) {
                case KINT: {
                    long n = unpackInt(s, pos - 1, (int) size, h.islittle, true);
                    results.add(LuaInteger.valueOf(n));
                    pos += size;
                    break;
                }
                case KUINT: {
                    long n = unpackInt(s, pos - 1, (int) size, h.islittle, false);
                    results.add(LuaInteger.valueOf(n));
                    pos += size;
                    break;
                }
                case KFLOAT: {
                    byte[] bytes = new byte[4];
                    System.arraycopy(s.contents, pos - 1, bytes, 0, 4);
                    float f = ByteBuffer.wrap(bytes).order(h.islittle ? ByteOrder.LITTLE_ENDIAN : ByteOrder.BIG_ENDIAN).getFloat();
                    results.add(LuaFloat.valueOf(f));
                    pos += 4;
                    break;
                }
                case KNUMBER:
                case KDOUBLE: {
                    byte[] bytes = new byte[8];
                    System.arraycopy(s.contents, pos - 1, bytes, 0, 8);
                    double d = ByteBuffer.wrap(bytes).order(h.islittle ? ByteOrder.LITTLE_ENDIAN : ByteOrder.BIG_ENDIAN).getDouble();
                    results.add(LuaFloat.valueOf(d));
                    pos += 8;
                    break;
                }
                case KCHAR: {
                    LuaString sub = LuaString.newLstr(s.contents, pos - 1, (int) size);
                    results.add(sub);
                    pos += size;
                    break;
                }
                case KSTRING: {
                    long lenL = unpackInt(s, pos - 1, (int) size, h.islittle, false);
                    int len = (int) lenL;

                    if (len > ld - (pos - 1) - size) {
                        throw LuaErrors.errorObject("data string too short");
                    }
                    pos += size;
                    LuaString sub = LuaString.newLstr(s.contents, pos - 1, len);
                    results.add(sub);
                    pos += len;
                    break;
                }
                case KZSTR: {
                    int start = pos - 1;
                    int end = start;
                    while (end < s.shrlen && s.contents[end] != 0) end++;
                    if (end >= s.shrlen) throw LuaErrors.errorObject("unfinished string for format 'z'");
                    LuaString sub = LuaString.newLstr(s.contents, start, end - start);
                    results.add(sub);
                    pos = end + 2;
                    break;
                }
                case KPADDING:
                    pos++;
                    break;
                case KPADDALIGN:
                case KNOP:
                    break;
            }
        }
        results.add(LuaInteger.valueOf(pos));
        return LuaValue.varargsOf(results.toArray(LuaValue.NOVALS));
    }

    // lstrlib.c: unpackInt
    private static long unpackInt(LuaString s, int off, int size, boolean islittle, boolean isSigned) {
        int limit = (size <= SZINT) ? size : SZINT;
        long n = 0;
        for (int i = 0; i < limit; i++) {
            int b = s.contents[off + (islittle ? i : size - 1 - i)] & 0xFF;
            n |= ((long) b) << (i * NB);
        }
        if (size < SZINT) {
            if (isSigned) {
                long mask = 1L << (size * NB - 1);
                n = ((n ^ mask) - mask);
            }
        } else if (size > SZINT) {

            int mask = (!isSigned || n >= 0) ? 0 : 0xFF;
            for (int i = limit; i < size; i++) {
                int b = s.contents[off + (islittle ? i : size - 1 - i)] & 0xFF;
                if (b != mask) {
                    throw LuaErrors.errorObject(size + "-byte integer does not fit into Lua Integer");
                }
            }
        }
        return n;
    }

    // java-only: byte-oriented growable buffer，对齐 C 的 luaL_Buffer (lauxlib.c)。
    // batch text / %s 直写 byte[] 零分配；数字快路径 writeLong/writeHex/writeOctal 直转入 buffer。
    // writeStr 用 per-char 循环（对齐 LuaString.valueOf(CharSequence) 的 (byte)(charAt & 0xFF) 语义）。
    static final class ByteBuf {
        private byte[] buf;
        private int count;

        ByteBuf(int cap) {
            buf = new byte[cap];
        }

        private void ensure(int n) {
            int need = count + n;
            if (need > buf.length) {
                int cap = buf.length << 1;
                if (cap < need) cap = need;
                buf = Arrays.copyOf(buf, cap);
            }
        }

        void write(int b) {
            ensure(1);
            buf[count++] = (byte) b;
        }

        void write(byte[] b, int off, int len) {
            if (len <= 0) return;
            ensure(len);
            System.arraycopy(b, off, buf, count, len);
            count += len;
        }

        // latin-1 String -> bytes（per-char，无 byte[] 分配；短串路径）
        void writeStr(String s) {
            int n = s.length();
            if (n == 0) return;
            ensure(n);
            for (int i = 0; i < n; i++) buf[count + i] = (byte) s.charAt(i);
            count += n;
        }

        void writeSpaces(int n) {
            if (n <= 0) return;
            ensure(n);
            for (int i = 0; i < n; i++) buf[count + i] = ' ';
            count += n;
        }

        // long -> ASCII 十进制直接入 buffer（零 String 分配，对齐 SB.append(long)）
        void writeLong(long n) {
            if (n == 0) {
                write('0');
                return;
            }
            if (n == Long.MIN_VALUE) {
                writeStr("-9223372036854775808");
                return;
            }
            boolean neg = n < 0;
            if (neg) {
                write('-');
                n = -n;
            }
            long t = n;
            int digits = 0;
            while (t > 0) {
                t /= 10;
                digits++;
            }
            ensure(digits);
            for (int i = digits - 1; i >= 0; i--) {
                buf[count + i] = (byte) ('0' + n % 10);
                n /= 10;
            }
            count += digits;
        }

        // long -> 小写十六进制（无符号 64 位，对齐 Long.toHexString），零 String 分配
        void writeHexLower(long n) {
            if (n == 0) {
                write('0');
                return;
            }
            int digits = 0;
            long t = n;
            while (t != 0) {
                t >>>= 4;
                digits++;
            }
            ensure(digits);
            for (int i = digits - 1; i >= 0; i--) {
                int d = (int) (n >>> (i * 4)) & 0xF;
                buf[count + digits - 1 - i] = (byte) (d < 10 ? '0' + d : 'a' + d - 10);
            }
            count += digits;
        }

        // long -> 大写十六进制（无符号 64 位，对齐 Long.toHexString().toUpperCase()），零 String 分配
        void writeHexUpper(long n) {
            if (n == 0) {
                write('0');
                return;
            }
            int digits = 0;
            long t = n;
            while (t != 0) {
                t >>>= 4;
                digits++;
            }
            ensure(digits);
            for (int i = digits - 1; i >= 0; i--) {
                int d = (int) (n >>> (i * 4)) & 0xF;
                buf[count + digits - 1 - i] = (byte) (d < 10 ? '0' + d : 'A' + d - 10);
            }
            count += digits;
        }

        // long -> 八进制（无符号 64 位，对齐 Long.toOctalString），零 String 分配
        void writeOctal(long n) {
            if (n == 0) {
                write('0');
                return;
            }
            int digits = 0;
            long t = n;
            while (t != 0) {
                t >>>= 3;
                digits++;
            }
            ensure(digits);
            for (int i = digits - 1; i >= 0; i--) {
                buf[count + digits - 1 - i] = (byte) ('0' + ((n >>> (i * 3)) & 7));
            }
            count += digits;
        }

        byte[] toByteArray() {
            return Arrays.copyOf(buf, count);
        }
    }

    // java-only
    private static final class Header {
        boolean islittle = true;
        int maxalign = 1;
    }
}
