// ref: llex.c
// diff: Token独立类代替SemInfo联合体; switch/length分派关键字; 手动解析十六进制浮点数; HashMap代替anchorstr; ls.fs/ls.dyd移至Lexer消除this.fs歧义; StringBuilder代替MBuffer; save()用ISO-8859-1保留0-255字节
package org.luajvm.compiler;

import org.luajvm.core.LuaError;
import org.luajvm.core.LuaErrors;
import org.luajvm.core.LuaString;
import org.luajvm.core.LuaValue;

import java.io.IOException;
import java.io.Reader;
import java.math.BigInteger;
import java.util.HashMap;
import java.util.Map;

public final class Lexer {

    // ===============================================================
    // 兼容标志（来自 luaconf.h）
    // ===============================================================

    // LUA_COMPAT_GLOBAL
    public static final boolean COMPAT_GLOBAL = true;

    // ===============================================================
    // Token 常量 (C: llex.h:enum RESERVED)
    // 单字符 token 用其字符码表示；多字符与关键字从 FIRST_RESERVED (UCHAR_MAX+1) 起。
    // ===============================================================

    public static final int FIRST_RESERVED = 256;
    public static final int EOZ = -1;

    // ORDER RESERVED  -  必须与 KEYWORDS[] 顺序一致
    public static final int
            TK_AND = FIRST_RESERVED, TK_BREAK = FIRST_RESERVED + 1,
            TK_DO = FIRST_RESERVED + 2, TK_ELSE = FIRST_RESERVED + 3,
            TK_ELSEIF = FIRST_RESERVED + 4, TK_END = FIRST_RESERVED + 5,
            TK_FALSE = FIRST_RESERVED + 6, TK_FOR = FIRST_RESERVED + 7,
            TK_FUNCTION = FIRST_RESERVED + 8,
            TK_GLOBAL = FIRST_RESERVED + 9,  // Lua 5.5 "global" (compat)
            TK_GOTO = FIRST_RESERVED + 10, TK_IF = FIRST_RESERVED + 11,
            TK_IN = FIRST_RESERVED + 12, TK_LOCAL = FIRST_RESERVED + 13,
            TK_NIL = FIRST_RESERVED + 14, TK_NOT = FIRST_RESERVED + 15,
            TK_OR = FIRST_RESERVED + 16, TK_REPEAT = FIRST_RESERVED + 17,
            TK_RETURN = FIRST_RESERVED + 18, TK_THEN = FIRST_RESERVED + 19,
            TK_TRUE = FIRST_RESERVED + 20, TK_UNTIL = FIRST_RESERVED + 21,
            TK_WHILE = FIRST_RESERVED + 22;

    // 多字符符号
    public static final int
            TK_IDIV = FIRST_RESERVED + 23,   // //
            TK_CONCAT = FIRST_RESERVED + 24,   // ..
            TK_DOTS = FIRST_RESERVED + 25,   // ...
            TK_EQ = FIRST_RESERVED + 26,   // ==
            TK_GE = FIRST_RESERVED + 27,   // >=
            TK_LE = FIRST_RESERVED + 28,   // <=
            TK_NE = FIRST_RESERVED + 29,   // ~=
            TK_SHL = FIRST_RESERVED + 30,   // <<
            TK_SHR = FIRST_RESERVED + 31,   // >>
            TK_DBCOLON = FIRST_RESERVED + 32,   // ::
            TK_EOS = FIRST_RESERVED + 33;   // 流结束

    // 字面量 token（llex 返回的伪 token）
    public static final int
            TK_FLT = FIRST_RESERVED + 34,
            TK_INT = FIRST_RESERVED + 35,
            TK_NAME = FIRST_RESERVED + 36,
            TK_STRING = FIRST_RESERVED + 37;

    // NUM_RESERVED
    public static final int NUM_RESERVED = TK_WHILE - FIRST_RESERVED + 1;

    // ===============================================================
    // 关键字表  -  ORDER RESERVED
    // ===============================================================
    // ls->brkn / ls->glbn / ls->envn
    // java diff: C 存 per-LexState 指针；Java 用 static final 免掉每 Lexer 3 次
    //   LuaString.newStr 哈希查表。LuaString 全局驻留，
    //   故 static final 与 per-instance 语义逐位等价。
    public static final LuaString BRKN = LuaString.newStr("break");

    // ===============================================================
    // Token 数据
    // ===============================================================
    public static final LuaString GLBN = LuaString.newStr("global");

    // ===============================================================
    // 词法分析器状态
    // ===============================================================
    public static final LuaString ENVN = LuaString.newStr("_ENV");
    // java-only: ThreadLocal 单槽对象池（对齐 C 的 LexState 栈分配零堆开销）：
    //   跨 load() 复用 Lexer（含 StringBuilder/Tokenx2/Dyndata）；可重入安全
    //   （obtain/release 严格配对）；不参与 luaM 记账。
    // java-only: A/B 开关 - 默认 true，-Dluajvm.poollex=false 禁用
    static final boolean POOL_ENABLED =
            System.getProperty("luajvm.poollex") == null ||
                    Boolean.parseBoolean(System.getProperty("luajvm.poollex"));
    private static final String[] KEYWORDS = {
            "and", "break", "do", "else", "elseif",
            "end", "false", "for", "function", "global",
            "goto", "if", "in", "local", "nil", "not", "or",
            "repeat", "return", "then", "true", "until", "while"
    };
    private static final ThreadLocal<Lexer> POOL = new ThreadLocal<>();
    private static final int LUA_IDSIZE = 60;
    private static final int MAX_SRC = LUA_IDSIZE - 1;  // 59
    public final Token t = new Token();
    public final Token lookahead = new Token();
    private final StringBuilder buff = new StringBuilder(256);
    public int recdepth = 0;
    // ls->fs
    // java-only: ls.fs消除this.fs歧义——嵌套函数内 this.fs 已指向内层，须用传入的 fs
    public SyntaxNodes.FuncState fs;
    // ls->dyd
    public SyntaxNodes.Dyndata dyd;
    // java-only: reader 为 null 时走 srcData 直接字节访问快路径（消除 per-char
    //   虚方法 dispatch + IOException 检查）。ByteChunkReader 的逻辑内联到 nextChar。
    // java-only: reader/srcData/srcEnd/compatGlobal/source/shortSource 非 final，以支持
    //   ThreadLocal 对象池化（对齐 C 的 LexState 栈分配语义，跨 load() 复用）。
    private Reader reader;
    // java-only: 直接字节源（reader==null 时生效），避免 ByteChunkReader 对象分配
    private byte[] srcData;
    private int srcEnd;
    private int srcPos;
    private boolean pendingNewline;
    private boolean compatGlobal;
    private int current;
    private int linenumber = 1, lastline = 1;
    private String source;
    private int longStringLine;
    // java-only: per-Lexer 串表仅用于长字符串(>40字符)去重 - 短字符串由
    //   LuaString.valueOf 全局驻留(shortStrings 表)，无需 per-Lexer 去重。
    //   对齐 C 的 luaX_newstring(ls->h)，但跳过短串的 HashMap 操作。
    // java-only: 惰性初始化 - 多数 load() 调用无长字符串，省 HashMap 分配。
    private Map<LuaString, LuaString> scannerStrings;
    private String shortSource;

    // java-only: 空构造器 - 仅初始化可复用的 final 字段（buff/dyd/t/lookahead），
    //   source 相关字段由 resetSource 设置。供池化实例创建用。
    private Lexer() {
        reader = null;
        srcData = null;
        srcEnd = 0;
        srcPos = 0;
        pendingNewline = false;
        compatGlobal = COMPAT_GLOBAL;
        source = "?";
        shortSource = "?";
        // buff, t, lookahead 已在字段声明处初始化（final，复用）
        this.dyd = new SyntaxNodes.Dyndata();
    }

    // llex.c: luaX_new
    public Lexer(Reader r, String src) {
        this(r, src, COMPAT_GLOBAL);
    }

    // llex.c: luaX_new
    public Lexer(Reader r, String src, boolean compatGlobal) {
        this();
        resetSource(r, src, compatGlobal);
    }

    // java-only: 直接字节源构造器 - 跳过 ByteChunkReader 中间层，nextChar() 直读
    //   byte[]，消除 per-char 虚方法 dispatch + IOException 检查。
    //   语义等价于 new Lexer(new ByteChunkReader(data, prefix), src, compatGlobal)。
    public Lexer(byte[] data, int offset, int length, boolean pendingNl, String src, boolean compatGlobal) {
        this();
        resetSource(data, offset, length, pendingNl, src, compatGlobal);
    }

    // java-only: 从池获取 Lexer（字节源热路径）。池为空（首次或重入）则 new。
    //   对齐 C 的 luaX_setinput 语义 - 复用已存在的 LexState 实例。
    static Lexer obtain(byte[] data, int offset, int length, boolean pendingNl, String src, boolean compatGlobal) {
        if (POOL_ENABLED) {
            Lexer ls = POOL.get();
            if (ls != null) {
                POOL.set(null);
                ls.resetSource(data, offset, length, pendingNl, src, compatGlobal);
                return ls;
            }
        }
        return new Lexer(data, offset, length, pendingNl, src, compatGlobal);
    }

    // java-only: 从池获取 Lexer（Reader 路径）。
    static Lexer obtain(Reader r, String src, boolean compatGlobal) {
        if (POOL_ENABLED) {
            Lexer ls = POOL.get();
            if (ls != null) {
                POOL.set(null);
                ls.resetSource(r, src, compatGlobal);
                return ls;
            }
        }
        return new Lexer(r, src, compatGlobal);
    }

    // lobject.c: luaO_chunkid
    private static String chunkId(String source) {
        if (source == null) return "?";
        if (!source.isEmpty() && source.charAt(0) == '=') {
            String s = source.substring(1);
            if (s.length() <= MAX_SRC) return s;
            return s.substring(0, MAX_SRC);
        } else if (!source.isEmpty() && source.charAt(0) == '@') {
            String s = source.substring(1);
            if (s.length() <= MAX_SRC) return s;
            return "..." + s.substring(s.length() - (MAX_SRC - 3));
        } else {
            int nl = source.indexOf('\n');
            boolean hasNewline = nl >= 0;
            String s = hasNewline ? source.substring(0, nl) : source;
            int maxLen = MAX_SRC - "[string \"...\"]".length();
            // lobject.c: srclen >= bufflen 即截断（恰 45 字符也加 "..."；> 会漏等值边界）
            if (hasNewline || s.length() >= maxLen) {
                if (s.length() >= maxLen) s = s.substring(0, maxLen);
                s = s + "...";
            }
            String result = "[string \"" + s + "\"]";
            if (result.length() > MAX_SRC) result = result.substring(0, MAX_SRC);
            return result;
        }
    }

    // llex.c: l_str2int
    private static boolean parseDecimalInteger(String s, Token out) {
        if (s.isEmpty()) return false;
        long a = 0;
        final long maxBy10 = Long.MAX_VALUE / 10;
        final int maxLastDigit = (int) (Long.MAX_VALUE % 10);
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c < '0' || c > '9') return false;
            int d = c - '0';
            if (a >= maxBy10 && (a > maxBy10 || d > maxLastDigit)) return false;
            a = a * 10 + d;
        }
        out.i = a;
        return true;
    }

    // llex.c: l_str2d
    private static boolean isValidDecimalFloatSyntax(String s) {
        // C: lobject.c:luaO_str2num/l_str2d。strtod 失败仅表示"不是数字"，非异常
        // 控制流；故先用小状态机过滤测试里的坏数字。
        int i = 0;
        int n = s.length();
        boolean digit = false;
        while (i < n) {
            char c = s.charAt(i);
            if (c >= '0' && c <= '9') {
                digit = true;
                i++;
            } else {
                break;
            }
        }
        if (i < n && s.charAt(i) == '.') {
            i++;
            while (i < n) {
                char c = s.charAt(i);
                if (c >= '0' && c <= '9') {
                    digit = true;
                    i++;
                } else {
                    break;
                }
            }
        }
        if (!digit) return false;
        if (i < n && (s.charAt(i) == 'e' || s.charAt(i) == 'E')) {
            i++;
            if (i < n && (s.charAt(i) == '+' || s.charAt(i) == '-')) i++;
            boolean expdigit = false;
            while (i < n) {
                char c = s.charAt(i);
                if (c >= '0' && c <= '9') {
                    expdigit = true;
                    i++;
                } else {
                    break;
                }
            }
            if (!expdigit) return false;
        }
        return i == n;
    }

    // lobject.c: luaO_str2num
    private static double parseHexFloat(String s) {
        // C: luaO_str2num 用 strtod 解析十六进制浮点数；Java 的 Double.parseDouble
        // 不接受 "0x" 前缀（仅 Long.parseLong 接受 0x 整数）。
        // 手动解析格式 0xHHH.HHHp+/-EE：HHH 为十六进制数字，p 为二进制指数标记。
        try {
            boolean neg = false;
            int i = 0;
            if (i < s.length() && (s.charAt(i) == '+' || s.charAt(i) == '-')) {
                neg = s.charAt(i) == '-';
                i++;
            }
            if (i + 1 < s.length() && s.charAt(i) == '0' && (s.charAt(i + 1) == 'x' || s.charAt(i + 1) == 'X')) {
                i += 2;
            }
            // 用 BigInteger 累积尾数避免溢出：C 的 strtod 把大十六进制整数正确舍入到
            // 最近的 double，而 long 尾数对 >64 位字面量（如 0x13121110090807060504030201）溢出。
            BigInteger mantissa = BigInteger.ZERO;
            int fracBits = 0;
            boolean hasDigit = false;
            boolean hasFrac = false;
            // 整数部分
            while (i < s.length()) {
                char c = s.charAt(i);
                int d = hexDigit(c);
                if (d < 0) break;
                mantissa = mantissa.multiply(BigInteger.valueOf(16))
                        .add(BigInteger.valueOf(d));
                hasDigit = true;
                i++;
            }
            // 小数部分
            if (i < s.length() && s.charAt(i) == '.') {
                i++;
                hasFrac = true;
                while (i < s.length()) {
                    char c = s.charAt(i);
                    int d = hexDigit(c);
                    if (d < 0) break;
                    mantissa = mantissa.multiply(BigInteger.valueOf(16))
                            .add(BigInteger.valueOf(d));
                    fracBits += 4;
                    hasDigit = true;
                    i++;
                }
            }
            if (!hasDigit) throw new NumberFormatException("no digits");
            // 指数（二进制，由 p/P 标记）
            int exp = 0;
            if (i < s.length() && (s.charAt(i) == 'p' || s.charAt(i) == 'P')) {
                i++;
                boolean expNeg = false;
                if (i < s.length() && (s.charAt(i) == '+' || s.charAt(i) == '-')) {
                    expNeg = s.charAt(i) == '-';
                    i++;
                }
                int e = 0;
                boolean hasExp = false;
                while (i < s.length()) {
                    char c = s.charAt(i);
                    if (c < '0' || c > '9') break;
                    e = e * 10 + (c - '0');
                    hasExp = true;
                    i++;
                }
                if (!hasExp) throw new NumberFormatException("no exponent digits");
                exp = expNeg ? -e : e;
            }
            if (i != s.length()) throw new NumberFormatException("trailing chars");
            // 尾数（BigInteger）转 double 后应用二进制指数：mantissa * 2^(exp - fracBits)
            int binaryExp = exp - fracBits;
            // 对大尾数，BigInteger.doubleValue() 正确舍入到最近的 double。
            double mantissaDouble = mantissa.doubleValue();
            // lobject.c lua_strx2number（C strtod）全区间正确舍入：
            // Math.scalb 单次缩放直接舍入到最近 double，mantissa 精确可表示时
            // 整体即正确（次正规区 0x1.8p-1075 = 5e-324 也正确）
            double v = Math.scalb(mantissaDouble, binaryExp);
            return neg ? -v : v;
        } catch (NumberFormatException e) {
            throw new NumberFormatException("malformed hex float");
        }
    }

    private static boolean charsEquals(CharSequence s, String literal) {
        int n = literal.length();
        if (s.length() != n) return false;
        for (int i = 0; i < n; i++) {
            if (s.charAt(i) != literal.charAt(i)) return false;
        }
        return true;
    }

    // llex.c: hexval
    private static int hexDigit(int c) {
        if (c >= '0' && c <= '9') return c - '0';
        if (c >= 'a' && c <= 'f') return c - 'a' + 10;
        if (c >= 'A' && c <= 'F') return c - 'A' + 10;
        return -1;
    }

    // llex.c: lisxdigit
    private static boolean isxdigit(int c) {
        return hexDigit(c) >= 0;
    }

    // C: lctype.h  -  lislalpha/lislalnum（ASCII 模式，LUA_UCID 未定义）
    // 默认 C 配置下仅 [a-zA-Z_] 算字母；>= 0x80 不算。
    // llex.c: lislalpha
    private static boolean islalpha(int c) {
        return (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || c == '_';
    }

    // llex.c: lislalnum
    private static boolean islalnum(int c) {
        return islalpha(c) || (c >= '0' && c <= '9');
    }

    // llex.c: luaX_token2str
    public static String tokenName(int tok) {
        if (tok == EOZ) return "<eof>";
        if (tok < FIRST_RESERVED) {
            if (tok == '\n') return "<\\n>";
            if (tok >= 32 && tok < 127) return "'" + (char) tok + "'";
            return "'<\\" + tok + ">'";
        }
        // llex.c: luaX_token2str —— token < FIRST_RESERVED 之外也统一 '%s' 加引号
        if (tok <= TK_WHILE) return "'" + KEYWORDS[tok - FIRST_RESERVED] + "'";
        return switch (tok) {
            case TK_IDIV -> "'//'";
            case TK_CONCAT -> "'..'";
            case TK_DOTS -> "'...'";
            case TK_EQ -> "'=='";
            case TK_GE -> "'>='";
            case TK_LE -> "'<='";
            case TK_NE -> "'~='";
            case TK_SHL -> "'<<'";
            case TK_SHR -> "'>>'";
            case TK_DBCOLON -> "'::'";
            case TK_EOS -> "<eof>";
            case TK_FLT -> "<number>";
            case TK_INT -> "<integer>";
            case TK_NAME -> "<name>";
            case TK_STRING -> "<string>";
            default -> "'?'";
        };
    }

    // ===============================================================
    // 公共 API
    // ===============================================================

    public int lastline() {
        return lastline;
    }

    // llex.c: luaX_newstring
    // java diff: 用 valueOfLatin1(CharSequence) 替代 newStr(s.toString())，免掉短串路径的
    //   中间 String 分配（StringBuilder->String->LuaString 两步变一步）。
    // java diff: 必须用 Latin-1 版 - save() 把源码字节 0-255 1:1 存进 StringBuilder
    //   （char 0-255），UTF-8 的 valueOf 会对 \x80-\xff 字节二次编码破坏转义字面量。
    private LuaString newString(CharSequence s) {
        LuaString ts = LuaString.valueOfLatin1(s);
        if (ts.tt_ == LuaValue.LUA_VSHRSTR) return ts;  // 短串已全局驻留
        // 长串：per-Lexer 去重（惰性初始化 HashMap，无长串时不分配）
        if (scannerStrings == null) scannerStrings = new HashMap<>();
        LuaString old = scannerStrings.get(ts);
        if (old != null) return old;
        scannerStrings.put(ts, ts);
        return ts;
    }

    // java-only: 归还到池。清除所有可变状态，源字段由下次 obtain() 的 resetSource 重设。
    //   对齐 C 的 luaX_setinput 重置语义。parse 抛异常时调用也安全。
    void release() {
        // 清除词法状态
        buff.setLength(0);
        t.token = 0;
        t.i = 0;
        t.r = 0;
        t.ts = null;
        lookahead.token = TK_EOS;
        lookahead.i = 0;
        lookahead.r = 0;
        lookahead.ts = null;
        fs = null;
        recdepth = 0;
        linenumber = 1;
        lastline = 1;
        current = EOZ;
        longStringLine = 0;
        // 清除长字符串缓存（保留 HashMap 实例供下次复用）
        if (scannerStrings != null) scannerStrings.clear();
        // 清除 Dyndata（对齐 C 的 dyd->actvar.n = dyd->gt.n = dyd->label.n = 0）
        dyd.actvarN = 0;
        if (dyd.actvar != null) dyd.actvar.clear();
        if (dyd.gt.arr != null) dyd.gt.arr.clear();
        if (dyd.label.arr != null) dyd.label.arr.clear();
        // java-only: 必须清源引用 - 池 ThreadLocal 常驻，留着即每线程滞留整份脚本源
        //   （srcData 全部字节；reader 可能间接持有流/句柄）。两者由下次 obtain() 重设，
        //   清除不影响复用；source/shortSource 仅 parse 期供错误消息用，一并复位。
        srcData = null;
        reader = null;
        srcEnd = 0;
        srcPos = 0;
        source = "?";
        shortSource = "?";
        // 归还到池（仅池为空且池化启用时）
        if (POOL_ENABLED && POOL.get() == null) {
            POOL.set(this);
        }
    }

    // java-only: 重设字节源字段 - 对齐 C 的 luaX_setinput
    private void resetSource(byte[] data, int offset, int length, boolean pendingNl, String src, boolean compatGlobal) {
        reader = null;
        srcData = data;
        srcPos = offset;
        srcEnd = offset + length;
        pendingNewline = pendingNl;
        this.compatGlobal = compatGlobal;
        source = (src == null) ? "?" : src;
        shortSource = chunkId(source);
        linenumber = 1;
        lastline = 1;
        lookahead.token = TK_EOS;
        lookahead.ts = null;
        current = nextChar();
    }

    // ===============================================================
    // 主扫描器  -  C: llex.c:llex()
    // ===============================================================

    // java-only: 重设 Reader 源字段 - 对齐 C 的 luaX_setinput
    private void resetSource(Reader r, String src, boolean compatGlobal) {
        reader = r;
        srcData = null;
        srcEnd = 0;
        srcPos = 0;
        pendingNewline = false;
        this.compatGlobal = compatGlobal;
        source = (src == null) ? "?" : src;
        shortSource = chunkId(source);
        linenumber = 1;
        lastline = 1;
        lookahead.token = TK_EOS;
        lookahead.ts = null;
        current = nextChar();
    }

    // ===============================================================
    // 数字读取  -  C: llex.c:read_numeral()
    // ===============================================================

    // ls->linenumber
    public int linenumber() {
        return linenumber;
    }

    public String source() {
        return source;
    }


    // llex.c: luaX_next
    public void nextToken() {
        lastline = linenumber;
        if (lookahead.token != TK_EOS) {

            t.token = lookahead.token;
            t.i = lookahead.i;
            t.r = lookahead.r;
            t.ts = lookahead.ts;
            lookahead.token = TK_EOS;
            lookahead.ts = null;
        } else {

            t.token = llex();
        }
    }

    // ===============================================================
    // 长字符串读取  -  C: llex.c:read_long_string()
    // ===============================================================

    public void next() {
        nextToken();
    }

    // ===============================================================
    // 字符串读取  -  C: llex.c:read_string()
    // ===============================================================

    // llex.c: luaX_lookahead
    public int lookAhead() {
        // C 的 luaX_lookahead 不改 lastline（lastline 只由 luaX_next 推进）
        if (lookahead.token != TK_EOS) return lookahead.token;

        int savedToken = t.token;
        long savedI = t.i;
        double savedR = t.r;
        LuaString savedTs = t.ts;
        int tok = llex();

        lookahead.token = tok;
        lookahead.i = t.i;
        lookahead.r = t.r;
        lookahead.ts = t.ts;

        t.token = savedToken;
        t.i = savedI;
        t.r = savedR;
        t.ts = savedTs;
        return lookahead.token;
    }

    // ===============================================================
    // 标识符/保留字  -  C: llex.c default 分支
    // ===============================================================

    public int lookahead() {
        return lookAhead();
    }

    // llex.c: llex
    private int llex() {

        buff.setLength(0);
        for (; ; ) {
            switch (current) {
                // -- 换行 --
                case '\n':
                case '\r':
                    incline();
                    continue;

                    // -- 空格 --
                case ' ':
                case '\t':
                case 0x0B:
                case '\f':
                    current = nextChar();
                    continue;

                    // -- 注释：-- 或 --[[长注释]] --
                case '-': {
                    current = nextChar();
                    if (current != '-') return '-';
                    // 是注释
                    current = nextChar();
                    if (current == '[') {
                        int sep = skipSep();
                        buff.setLength(0);
                        if (sep >= 2) {
                            longStringLine = linenumber;  // C: llex.c int line = ls->linenumber
                            readLongString(sep, false);
                            buff.setLength(0);
                            continue;
                        }
                    }
                    // 短注释：跳到行尾
                    while (current != '\n' && current != '\r' && current != EOZ)
                        current = nextChar();

                    continue;
                }

                // -- 长字符串 [==[ ... ]==] 或单个 '[' --
                case '[': {
                    int sep = skipSep();
                    if (sep >= 2) {
                        longStringLine = linenumber;  // C: llex.c int line = ls->linenumber
                        readLongString(sep, true);
                        return TK_STRING;
                    } else if (sep == 0) {
                        throw lexerror("invalid long string delimiter", TK_STRING);
                    }
                    return '[';
                }

                // -- = 或 == --
                case '=': {
                    current = nextChar();
                    if (checkNext1('=')) return TK_EQ;
                    return '=';
                }

                // -- < <= << --
                case '<': {
                    current = nextChar();
                    if (checkNext1('=')) return TK_LE;
                    if (checkNext1('<')) return TK_SHL;
                    return '<';
                }

                // -- > >= >> --
                case '>': {
                    current = nextChar();
                    if (checkNext1('=')) return TK_GE;
                    if (checkNext1('>')) return TK_SHR;
                    return '>';
                }

                // -- / 或 // --
                case '/': {
                    current = nextChar();
                    if (checkNext1('/')) return TK_IDIV;
                    return '/';
                }

                // -- ~ 或 ~= --
                case '~': {
                    current = nextChar();
                    if (checkNext1('=')) return TK_NE;
                    return '~';
                }

                // -- : 或 :: --
                case ':': {
                    current = nextChar();
                    if (checkNext1(':')) return TK_DBCOLON;
                    return ':';
                }

                // -- 短字符串 "..." 或 '...' --
                case '"':
                case '\'':
                    readString(current);
                    return TK_STRING;

                // -- . .. ... 或以 . 开头的数字 --
                case '.': {
                    saveAndNext();
                    if (checkNext1('.')) {
                        if (checkNext1('.')) return TK_DOTS;
                        return TK_CONCAT;
                    }
                    // java diff: C 用 lisdigit（ASCII）；Character.isDigit 认全部 Unicode Nd。
                    //   字节源(0-255)恒等价；唯一 Reader 用点 compileToChunk 按 ISO_8859_1
                    //   解码，字符恒 <=0xFF，>0xFF 的 Unicode 数字不可达 ⇒ 无分叉
                    if (!Character.isDigit(current)) return '.';
                    return readNumeral();
                }

                // -- 数字 --
                case '0':
                case '1':
                case '2':
                case '3':
                case '4':
                case '5':
                case '6':
                case '7':
                case '8':
                case '9':
                    return readNumeral();

                // -- 文件结束 --
                case EOZ:
                    return TK_EOS;

                // -- 标识符或保留字 --
                default: {
                    if (islalpha(current)) {  /* 标识符或保留字？ */
                        return readName();
                    }
                    // 单字符 token
                    int c = current;
                    current = nextChar();
                    return c;
                }
            }
        }
    }

    // llex.c: read_numeral
    private int readNumeral() {
        // C: llex.c  -  不清空 buff。llex() 的 '.' 分支在调 read_numeral 前已保存 '.'，
        // 数字分支的 buff 则为空；此处清空会丢失 '.2e2' 这类数字的前导 '.'。
        char first = (char) current;
        saveAndNext();
        boolean hex = (first == '0' && checkNext2("xX"));
        String expo = hex ? "pP" : "eE";
        for (; ; ) {
            if (checkNext2(expo)) {
                checkNext2("-+");
            } else if (isxdigit(current) || current == '.') {
                saveAndNext();
            } else {
                break;
            }
        }
        if (islalpha(current)) {  /* C: lislalpha  -  数字紧贴字母？ */
            saveAndNext(); // 强制解析时出错
        }
        String s = buff.toString();
        try {
            if (hex) {
                // 去掉 "0x"/"0X" 前缀再解析
                String hexPart = s.substring(2);
                // 含 '.'、'p' 或 'P' 即浮点数
                boolean isFloat = s.indexOf('.') >= 0 || s.indexOf('p') >= 0 || s.indexOf('P') >= 0;
                if (isFloat) {
                    t.r = parseHexFloat(s);
                    return TK_FLT;
                }
                // 十六进制整数  -  C: l_str2int 用 lua_Unsigned 模运算（a = a * 16 + digit）
                // 溢出回绕，再由 l_castU2S 重解释为有符号数；Java long 算术同样回绕，结果一致。
                // 十六进制整数绝不回退为浮点数（与十进制不同）。
                // llex.c: read_numeral —— '0x' 后无十六进制位由 luaO_str2num 拒绝（"malformed number near '0x'"）
                if (hexPart.isEmpty()) throw lexerror("malformed number", TK_INT);
                long a = 0;
                for (int j = 0; j < hexPart.length(); j++) {
                    int d = hexDigit(hexPart.charAt(j));
                    if (d < 0) throw lexerror("malformed number", TK_INT);
                    a = a * 16 + d;  // 溢出时回绕
                }
                t.i = a;  // 重解释为有符号数（l_castU2S）
                return TK_INT;
            } else {
                // C: lobject.c:l_str2int  -  先试整数。溢出是正常的"不是整数"结果，非异常路径。
                if (s.indexOf('.') < 0 && s.indexOf('e') < 0 && s.indexOf('E') < 0) {
                    if (parseDecimalInteger(s, t)) {
                        return TK_INT;
                    }
                }
                if (!isValidDecimalFloatSyntax(s)) {
                    throw lexerror("malformed number", TK_FLT);
                }
                t.r = Double.parseDouble(s);
                return TK_FLT;
            }
        } catch (NumberFormatException e) {
            throw lexerror("malformed number", TK_FLT);
        }
    }

    // ===============================================================
    // 辅助方法
    // ===============================================================

    // llex.c: read_long_string
    private void readLongString(int sep, boolean saveContent) {
        // C: llex.c  -  save_and_next 保存第 2 个 '['；首个内容字符为换行则跳过；
        //   内容按 (buff+sep, len-2*sep) 提取。java diff: Java 的 skipSep 前不清 buff
        //   （llex 开头已清空），buff 含完整 [==[...]==]，txtToken 正确显示 near token。
        saveAndNext();  // C: llex.c save_and_next(ls)  -  保存第 2 个 '['
        if (current == '\n' || current == '\r') {
            // C: llex.c  -  字符串以换行开头则跳过（不存进 buff）
            incline();
        }
        for (; ; ) {
            switch (current) {
                case EOZ: {
                    // C: llex.c
                    String what = saveContent ? "string" : "comment";
                    throw lexerror("unfinished long " + what + " (starting at line " + longStringLine + ")", EOZ);
                }
                case ']': {
                    if (skipSep() == sep) {
                        saveAndNext();  // C: llex.c save_and_next(ls)  -  保存第 2 个 ']'
                        // C: llex.c  -  seminfo->ts = luaX_newstring(ls, buff+sep, len-2*sep)
                        if (saveContent) {
                            String content = buff.substring(sep, buff.length() - sep);
                            t.ts = newString(content);
                        }
                        return;
                    }
                    break;  // C: llex.c break
                }
                case '\n':
                case '\r': {
                    save('\n');  // C: llex.c save(ls, '\n')
                    incline();
                    if (!saveContent) buff.setLength(0);  // C: llex.c luaZ_resetbuffer
                    break;
                }
                default: {
                    if (saveContent) saveAndNext();  // C: llex.c save_and_next(ls)
                    else current = nextChar();       // C: llex.c next(ls)
                }
            }
        }
    }

    // llex.c: read_string
    private void readString(int del) {
        buff.setLength(0);
        // C: llex.c save_and_next(ls)  -  留分隔符供错误消息用
        save(del);
        current = nextChar();
        while (current != del && current != EOZ) {
            if (current == '\n' || current == '\r') {
                // C: llex.c lexerror(ls, "unfinished string", TK_STRING)
                throw lexerror("unfinished string", TK_STRING);
            }
            if (current == '\\') {
                // C: llex.c save_and_next(ls)  -  留 '\\' 供错误消息用
                save('\\');
                current = nextChar();
                switch (current) {
                    case 'a': {
                        int c = 7;
                        current = nextChar(); /* C: read_save */
                        buff.setLength(buff.length() - 1);
                        save(c);
                        break;
                    }
                    case 'b': {
                        int c = 8;
                        current = nextChar();
                        buff.setLength(buff.length() - 1);
                        save(c);
                        break;
                    }
                    case 'f': {
                        int c = 12;
                        current = nextChar();
                        buff.setLength(buff.length() - 1);
                        save(c);
                        break;
                    }
                    case 'n': {
                        int c = '\n';
                        current = nextChar();
                        buff.setLength(buff.length() - 1);
                        save(c);
                        break;
                    }
                    case 'r': {
                        int c = '\r';
                        current = nextChar();
                        buff.setLength(buff.length() - 1);
                        save(c);
                        break;
                    }
                    case 't': {
                        int c = '\t';
                        current = nextChar();
                        buff.setLength(buff.length() - 1);
                        save(c);
                        break;
                    }
                    case 'v': {
                        int c = 11;
                        current = nextChar();
                        buff.setLength(buff.length() - 1);
                        save(c);
                        break;
                    }
                    case '\\': {
                        int c = '\\';
                        current = nextChar();
                        buff.setLength(buff.length() - 1);
                        save(c);
                        break;
                    }
                    case '"': {
                        int c = '"';
                        current = nextChar();
                        buff.setLength(buff.length() - 1);
                        save(c);
                        break;
                    }
                    case '\'': {
                        int c = '\'';
                        current = nextChar();
                        buff.setLength(buff.length() - 1);
                        save(c);
                        break;
                    }
                    case '\n':
                    case '\r':
                        // C: llex.c inclinenumber(ls); c = '\n'; goto only_save
                        incline();
                        buff.setLength(buff.length() - 1);  // C: only_save -> luaZ_buffremove(buff, 1)
                        save('\n');
                        break;
                    case 'x': {
                        // C: llex.c case 'x': c = readhexaesc(ls); goto read_save
                        save('x');  // 存 'x' 供错误消息用
                        current = nextChar();  // 跳过 'x'
                        int c = readHexEsc();
                        // C: read_save -> luaZ_buffremove(buff, 1) 移除 '\\' 后 save(c)
                        // readHexEsc 已移除自己的 2 个十六进制数字，此处还需移除 '\\' 和 'x'
                        buff.setLength(buff.length() - 2);  // 移除 '\\' 和 'x'
                        save(c);
                        // readHexEsc 已把 current 推进到 2 个十六进制数字之后
                        break;
                    }
                    case 'u':
                        // C: llex.c case 'u': utf8esc(ls); goto no_save
                        // readUtf8Esc 清除 '\\' 及其后全部内容，并存入 UTF-8 字节
                        readUtf8Esc();
                        break;
                    case 'z': {
                        // C: llex.c  -  \z 跳过空格；luaZ_buffremove(buff, 1) 移除 '\\'
                        current = nextChar();  // 跳过 'z'
                        // lctype.c lisspace —— 仅 ' ' \t \n \v \f \r；
                        // Character.isWhitespace 对 0x1C-0x1F 误判为空白（C 保留在串内）
                        while (current != EOZ && (current == ' ' || current == '\t' || current == '\n'
                                || current == 0x0B || current == 0x0C || current == '\r')) {
                            if (current == '\n' || current == '\r') {
                                incline();
                            } else {
                                current = nextChar();
                            }
                        }
                        buff.setLength(buff.length() - 1);  // C: luaZ_buffremove(buff, 1) 移除 '\\'
                        break;
                    }
                    case EOZ:
                        // C: llex.c case EOZ: goto no_save  -  下一轮循环抛错
                        buff.setLength(buff.length() - 1);  // 移除 '\\'
                        break;
                    default:
                        if (Character.isDigit(current)) {
                            // C: llex.c esccheck + readdecesc -> only_save
                            int c = readDecEsc();
                            buff.setLength(buff.length() - 1);  // C: only_save -> luaZ_buffremove(buff, 1)
                            save(c);
                        } else {
                            // C: llex.c esccheck(ls, lisdigit(ls->current), "invalid escape sequence")
                            // C: esccheck 抛错前先把当前字符存进缓冲区
                            save(current);
                            throw lexerror("invalid escape sequence", TK_STRING);
                        }
                }
            } else {
                save(current);
                current = nextChar();
            }
        }
        if (current == EOZ) {
            // C: llex.c lexerror(ls, "unfinished string", TK_EOS)
            throw lexerror("unfinished string", EOZ);
        }
        // C: llex.c save_and_next(ls)  -  存闭合分隔符
        save(current);
        current = nextChar(); // 跳过分隔符
        // C: llex.c  -  seminfo->ts = luaX_newstring(ls, buffer+1, len-2)
        // java diff: 用 buff.subSequence 替代 buff.toString().substring，免中间 String 分配
        t.ts = newString(buff.subSequence(1, buff.length() - 1));
    }

    // llex default
    private int readName() {
        buff.setLength(0);
        do {
            saveAndNext();
        } while (islalnum(current));  /* C: lislalnum */
        int reserved = keywordToken(buff);
        if (reserved >= 0) return reserved;
        t.ts = newString(buff);
        return TK_NAME;
    }

    private int keywordToken(CharSequence s) {
        switch (s.length()) {
            case 2:
                if (charsEquals(s, "do")) return TK_DO;
                if (charsEquals(s, "if")) return TK_IF;
                if (charsEquals(s, "in")) return TK_IN;
                if (charsEquals(s, "or")) return TK_OR;
                break;
            case 3:
                if (charsEquals(s, "and")) return TK_AND;
                if (charsEquals(s, "end")) return TK_END;
                if (charsEquals(s, "for")) return TK_FOR;
                if (charsEquals(s, "nil")) return TK_NIL;
                if (charsEquals(s, "not")) return TK_NOT;
                break;
            case 4:
                if (charsEquals(s, "else")) return TK_ELSE;
                if (charsEquals(s, "goto")) return TK_GOTO;
                if (charsEquals(s, "then")) return TK_THEN;
                if (charsEquals(s, "true")) return TK_TRUE;
                break;
            case 5:
                if (charsEquals(s, "break")) return TK_BREAK;
                if (charsEquals(s, "false")) return TK_FALSE;
                if (charsEquals(s, "local")) return TK_LOCAL;
                if (charsEquals(s, "until")) return TK_UNTIL;
                if (charsEquals(s, "while")) return TK_WHILE;
                break;
            case 6:
                if (charsEquals(s, "elseif")) return TK_ELSEIF;
                if (charsEquals(s, "global") && !compatGlobal) return TK_GLOBAL;
                if (charsEquals(s, "repeat")) return TK_REPEAT;
                if (charsEquals(s, "return")) return TK_RETURN;
                break;
            case 8:
                if (charsEquals(s, "function")) return TK_FUNCTION;
                break;
            default:
                break;
        }
        return -1;
    }

    // llex.c: zgetc
    // java diff: 字节源快路径直读 byte[]；Reader 路径留给非字节源（compileToChunk 等）。
    private int nextChar() {
        if (srcData != null) {
            if (pendingNewline) {
                pendingNewline = false;
                return '\n';
            }
            return srcPos < srcEnd ? srcData[srcPos++] & 0xFF : EOZ;
        }
        try {
            int c = reader.read();
            return c < 0 ? EOZ : c;
        } catch (IOException e) {
            LuaErrors.error(e.getMessage(), e);
            return EOZ;
        }
    }

    // llex.c: inclinenumber
    // java diff: 省略 C 的 "chunk has too many lines" 检查（需 2^31 行源码，不可达）；
    //   save() 同理省略 "lexical element too long"（Java 的 StringBuilder 先撞 JVM 数组上限）。
    private void incline() {
        int old = current;
        current = nextChar();
        if ((current == '\n' || current == '\r') && current != old) {
            current = nextChar();
        }
        linenumber++;
    }

    // llex.c: save
    private void save(int c) {
        // C: save 存原始字节。Java 用 ISO-8859-1 编码让字节 0-255 1:1 落成 char
        //（UTF-8 会把 chars > 127 编成多字节序列，破坏 \x 转义字面量）
        buff.append((char) (c & 0xFF));
    }

    // llex.c: save_and_next
    private void saveAndNext() {
        buff.append((char) current);
        current = nextChar();
    }

    // llex.c: check_next1
    private boolean checkNext1(int c) {
        if (current == c) {
            current = nextChar();
            return true;
        }
        return false;
    }

    // llex.c: check_next2
    private boolean checkNext2(String set) {
        if (current == set.charAt(0) || current == set.charAt(1)) {
            saveAndNext();
            return true;
        }
        return false;
    }

    // llex.c: skip_sep
    private int skipSep() {
        int count = 0;
        int s = current;
        saveAndNext();
        while (current == '=') {
            saveAndNext();
            count++;
        }
        if (current == s) return count + 2;
        if (count == 0) return 1;
        return 0;
    }

    // llex.c: readhexaesc
    private int readHexEsc() {
        // C: llex.c:readhexaesc  -  读恰好 2 个十六进制数字，无效即报错
        // C: gethexa 调 save_and_next 把每个数字存进缓冲区供错误消息用
        int d1 = hexDigit(current);
        if (d1 < 0) {
            // C: esccheck  -  if (ls->current != EOZ) save_and_next(ls)
            if (current != EOZ) save(current);
            throw lexerror("hexadecimal digit expected", TK_STRING);
        }
        save(current);
        current = nextChar();  // C: save_and_next(ls) 第一个数字
        int d2 = hexDigit(current);
        if (d2 < 0) {
            if (current != EOZ) save(current);
            throw lexerror("hexadecimal digit expected", TK_STRING);
        }
        save(current);
        current = nextChar();  // C: save_and_next(ls) 第二个数字
        // C: luaZ_buffremove(ls->buff, 2)  -  从缓冲区移除已存的 2 个十六进制数字
        // （调用方还会移除 '\\' 和 'x'）
        buff.setLength(buff.length() - 2);
        return (d1 << 4) + d2;
    }

    // llex.c: readdecesc
    private int readDecEsc() {
        // C: llex.c:readdecesc  -  最多读 3 个十进制数字
        int r = 0;
        int i = 0;
        int startLen = buff.length();  // 记住缓冲区位置供错误消息用
        while (i < 3 && Character.isDigit(current)) {
            save(current);  // C: save_and_next 供错误消息用
            r = 10 * r + current - '0';
            current = nextChar();
            i++;
        }
        if (r > 255) {
            // C: esccheck  -  抛错前 if (ls->current != EOZ) save_and_next(ls)
            if (current != EOZ) save(current);
            throw lexerror("decimal escape too large", TK_STRING);
        }
        // C: only_save -> luaZ_buffremove(buff, 1) 移除 '\\'（由调用方完成）
        // 同时移除缓冲区中已存的数字（仅供错误消息用）
        buff.setLength(startLen);
        return r;
    }

    // llex.c: utf8esc
    private void readUtf8Esc() {
        // C: llex.c:utf8esc  -  读 backslash-u-{HHHH} 转义序列。
        // 字符先存进缓冲区供错误消息用，成功后移除；调用方已存入 '\\'，故 startLen 含它，
        // 成功时要移除 '\\' 及其后全部内容。
        int startLen = buff.length() - 1;  // -1 以涵盖调用方存的 '\\'
        save('u');
        current = nextChar();
        if (current != '{') {
            if (current != EOZ) save(current);
            throw lexerror("missing '{'", TK_STRING);
        }
        save(current);
        current = nextChar();
        long r = 0;
        int ndigits = 0;
        while (hexDigit(current) >= 0) {
            save(current);
            r = (r << 4) + hexDigit(current);
            current = nextChar();
            ndigits++;
        }
        if (ndigits == 0) {
            // llex.c: readutf8esc —— 空花括号报 hex 数字缺失（非 '}' 缺失）
            if (current != EOZ) save(current);
            throw lexerror("hexadecimal digit expected", TK_STRING);
        }
        if (r > 0x7FFFFFFFL) throw lexerror("UTF-8 value too large", TK_STRING);
        if (current != '}') {
            if (current != EOZ) save(current);
            throw lexerror("missing '}'", TK_STRING);
        }
        current = nextChar();
        // 移除缓冲区中已存的全部 backslash-u-{...} 字符（仅供错误消息用）
        buff.setLength(startLen);
        // 存入 UTF-8 编码的字节
        int ri = (int) r;
        if (r <= 0x7F) {
            save(ri);
        } else if (r <= 0x7FF) {
            save(0xC0 | (ri >> 6));
            save(0x80 | (ri & 0x3F));
        } else if (r <= 0xFFFF) {
            save(0xE0 | (ri >> 12));
            save(0x80 | ((ri >> 6) & 0x3F));
            save(0x80 | (ri & 0x3F));
        } else if (r <= 0x1FFFFF) {
            save(0xF0 | (ri >> 18));
            save(0x80 | ((ri >> 12) & 0x3F));
            save(0x80 | ((ri >> 6) & 0x3F));
            save(0x80 | (ri & 0x3F));
        } else if (r <= 0x3FFFFFF) {
            save(0xF8 | (ri >> 24));
            save(0x80 | ((ri >> 18) & 0x3F));
            save(0x80 | ((ri >> 12) & 0x3F));
            save(0x80 | ((ri >> 6) & 0x3F));
            save(0x80 | (ri & 0x3F));
        } else {
            save(0xFC | (ri >> 30));
            save(0x80 | ((ri >> 24) & 0x3F));
            save(0x80 | ((ri >> 18) & 0x3F));
            save(0x80 | ((ri >> 12) & 0x3F));
            save(0x80 | ((ri >> 6) & 0x3F));
            save(0x80 | (ri & 0x3F));
        }
    }

    // ===============================================================
    // 错误报告  -  C: llex.c:lexerror/luaX_syntaxerror/txtToken
    // ===============================================================

    // llex.c: lexerror
    private LuaError lexerror(String msg, int token, int line) {
        String full = shortSource + ":" + line + ": " + msg;
        if (token != 0) {
            full = full + " near " + txtToken(token);
        }
        return LuaErrors.errorObject(full, 1);
    }

    // llex.c: luaX_syntaxerror
    public LuaError syntaxError(String msg) {
        return lexerror(msg, t.token, linenumber);
    }

    // lcode.c: luaK_semerror
    public LuaError semError(String msg) {
        return lexerror(msg, 0, lastline);
    }


    // llex.c: luaX_syntaxerrorAtLine
    public LuaError syntaxErrorAtLine(String msg, int line) {
        return lexerror(msg, 0, line);
    }

    // llex.c: lexerror
    public LuaError lexerror(String msg, int token) {
        return lexerror(msg, token, linenumber);
    }

    // llex.c: txtToken
    private String txtToken(int token) {
        if (token == EOZ) return "<eof>";
        if (token == TK_STRING) {
            if (buff.length() > 0) return "'" + buff + "'";
            if (t.ts != null) return "'" + t.ts.toJavaString() + "'";
            return "<string>";
        }
        if (token == TK_NAME) {
            if (t.ts != null) return "'" + t.ts.toJavaString() + "'";
            // C: luaZ_buffer(ls->buff)  -  用扫描器缓冲区处理残缺 token
            if (buff.length() > 0) return "'" + buff + "'";
            return "<name>";
        }
        // C: llex.c:txtToken  -  TK_FLT/TK_INT 用 luaZ_buffer(ls->buff)
        if (token == TK_FLT || token == TK_INT) {
            if (buff.length() > 0) return "'" + buff + "'";
            return tokenName(token);
        }
        return tokenName(token);
    }

    // ===============================================================
    // Token 名称  -  C: llex.c:luaX_token2str
    // ===============================================================

    public static final class Token {
        public int token;

        public long i;

        public double r;

        public LuaString ts;

    }
}
