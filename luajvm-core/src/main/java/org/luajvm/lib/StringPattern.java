// ref: lstrlib.c (match)
// diff: MatchState 内部类替代栈分配 struct; byte[]+偏移替代 const char*; 异常替代 longjmp; while+continue 模拟 goto; int[] 替代 capture 数组; strFindAux 合并 str_find_aux 与 push_captures
package org.luajvm.lib;

import org.luajvm.core.LuaError;
import org.luajvm.core.LuaErrors;
import org.luajvm.core.Globals;
import org.luajvm.core.LuaInteger;
import org.luajvm.core.LuaString;
import org.luajvm.core.LuaThread;
import org.luajvm.core.LuaValue;
import org.luajvm.core.Varargs;
import org.luajvm.vm.LuaCall;
import org.luajvm.vm.LuaIndex;

import java.io.ByteArrayOutputStream;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;

public final class StringPattern {
    public static final int MAX_CAPTURES = 32;
    // -- 常量 --
    static final int CAP_UNFINISHED = -1;
    static final int CAP_POSITION = -2;
    static final int MAXCCALLS = 200;
    private static final int L_ESC = '%';
    private static final LuaString SPECIALS = LuaString.newStr("^$*+?.([%-");
    // java-only: SPECIALS_TABLE 查表判 magic 字节（O(1)/字节）；对齐 C 的
    // strpbrk(p, SPECIALS) 语义 - p 中任一字节命中 SPECIALS 即返回
    private static final boolean[] SPECIALS_TABLE;
    // java-only: ThreadLocal 池化 MatchState（对齐 C 的栈分配零堆开销）。双池：
    //   MS_FIND_POOL 单实例（strFindAux 不调用户代码、不重入）；
    //   MS_GSUB_FREELIST 栈式 free-list（strGsub 经 addValue 调用户函数可重入）
    private static final ThreadLocal<MatchState> MS_FIND_POOL =
            ThreadLocal.withInitial(() -> new MatchState());
    private static final ThreadLocal<ArrayDeque<MatchState>> MS_GSUB_FREELIST =
            ThreadLocal.withInitial(ArrayDeque::new);
    // java-only: A/B 开关  -  -Dluajvm.mspool=false 禁用池化（基线对照用），默认开启
    private static final boolean MS_POOL_ENABLED =
            Boolean.parseBoolean(System.getProperty("luajvm.mspool", "true"));
    // lstrlib.c: lmemfind
    // java diff: C 用 memchr+memcmp（SIMD）；Java 三阶段混合：前 PROBE_LEN 逐字节探测
    //   （零 String 分配）→ 长串 String.indexOf（HotSpot intrinsic，对齐 memchr）+ 逐字节
    //   比对（对齐 memcmp）→ 短串纯逐字节。返回值与 C 一致（0-based / -1）
    private static final Charset ISO_8859_1 = StandardCharsets.ISO_8859_1;
    // java-only: PROBE_LEN  -  阶段 1 逐字节探测的最大位置数。
    //   16 是经验值：足以覆盖常见的早期匹配（如 "the quick..." 中的 "the" 在 pos 0），
    //   且无匹配时把逐字节扫描限制在很小的窗口，快速回退到 SIMD 路径。
    private static final int PROBE_LEN = 16;
    // java-only: SIMD_THRESHOLD  -  阶段 2 String.indexOf(int) 启用的最小源串长度。
    //   32 字节：阈值处 SIMD 扫描的收益开始超过 String 创建（allocation +
    //   arraycopy）的固定开销，故小于阈值走逐字节、大于走 indexOf。
    private static final int SIMD_THRESHOLD = 32;

    static {
        boolean[] t = new boolean[256];
        for (int i = 0; i < t.length; i++) t[i] = false;
        // ^ $ * + ? . ( [ % -
        t['^'] = true;
        t['$'] = true;
        t['*'] = true;
        t['+'] = true;
        t['?'] = true;
        t['.'] = true;
        t['('] = true;
        t['['] = true;
        t['%'] = true;
        t['-'] = true;
        SPECIALS_TABLE = t;
    }

    private StringPattern() {
    }

    // java-only: strFindAux 专用 - 单实例，无需 try/finally（strFindAux 不重入）
    private static MatchState acquireFindMS() {
        return MS_POOL_ENABLED ? MS_FIND_POOL.get() : new MatchState();
    }

    // java-only: strGsub 专用 - free-list，可重入安全
    private static MatchState acquireGsubMS() {
        if (!MS_POOL_ENABLED) return new MatchState();
        ArrayDeque<MatchState> pool = MS_GSUB_FREELIST.get();
        MatchState ms = pool.pollLast();
        return (ms != null) ? ms : new MatchState();
    }

    // java-only: gsub free-list 容量上限，对齐 Parser.FS_POOL_MAX。
    //   不设上限则高水位等于峰值 gsub 嵌套深度（回调再调 gsub 时外层未归还），
    //   而 ThreadLocal 随线程存活、永不回落。超出上限的实例交由 JVM 回收。
    private static final int MS_GSUB_POOL_MAX = 16;

    // java-only: 归还 strGsub 的 MatchState 到 free-list
    private static void releaseGsubMS(MatchState ms) {
        if (!MS_POOL_ENABLED) return;
        // 必须先清 src/pat：free-list 由 ThreadLocal 常驻，留着引用等于每线程
        //   钉住最后一次 gsub 的主串字节数组（见 clearSubject 注释）。
        ms.clearSubject();
        ArrayDeque<MatchState> pool = MS_GSUB_FREELIST.get();
        if (pool.size() < MS_GSUB_POOL_MAX) pool.addLast(ms);
    }

    // lstrlib.c: isPositionCap
    private static boolean isPositionCap(MatchState ms, int i) {
        return ms.captureLen[i] == CAP_POSITION;
    }

    // -- 捕获辅助 --
    // lstrlib.c: check_capture
    static int checkCapture(MatchState ms, int l) {
        l -= '1';
        if (l < 0 || l >= ms.level || ms.captureLen[l] == CAP_UNFINISHED)
            throw LuaErrors.errorObject("invalid capture index %" + (l + 1));
        return l;
    }

    // lstrlib.c: captureToClose
    static int captureToClose(MatchState ms) {
        for (int i = ms.level - 1; i >= 0; i--)
            if (ms.captureLen[i] == CAP_UNFINISHED) return i;
        throw LuaErrors.errorObject("invalid pattern capture");
    }

    // -- 模式类 --
    // lstrlib.c: classend
    static int classend(MatchState ms, int p) {
        switch (ms.pat[p]) {
            case L_ESC:
                if (p + 1 >= ms.patEnd) throw LuaErrors.errorObject("malformed pattern (ends with '%')");
                return p + 2;
            case '[': {
                int i = p + 1;
                if (i < ms.patEnd && ms.pat[i] == '^') i++;
                do {
                    if (i >= ms.patEnd) throw LuaErrors.errorObject("malformed pattern (missing ']')");
                    if (ms.pat[i] == L_ESC) {
                        i++;
                        if (i >= ms.patEnd) throw LuaErrors.errorObject("malformed pattern (missing ']')");
                    }
                } while (++i < ms.patEnd && ms.pat[i] != ']');
                if (i >= ms.patEnd) throw LuaErrors.errorObject("malformed pattern (missing ']')");
                return i + 1;
            }
            default:
                return p + 1;
        }
    }

    // lstrlib.c: matchClass
    // java diff: Character.toLowerCase/isLowerCase 换成 ASCII 直接比较 - Lua pattern 类
    //   恒为 ASCII 字母（a-z 正向 / A-Z 反向），无需 Unicode 处理，省掉 JDK 方法调用开销。
    static int matchClass(int c, int cl) {
        int res;
        // java diff: ASCII toLowerCase = 大写 +32，非大写原样
        int lc = (cl >= 'A' && cl <= 'Z') ? cl + 32 : cl;
        switch (lc) {
            case 'a':
                res = isalpha(c) ? 1 : 0;
                break;
            case 'c':
                res = iscntrl(c) ? 1 : 0;
                break;
            case 'd':
                res = (c >= '0' && c <= '9') ? 1 : 0;
                break;
            case 'g':
                res = (isprint(c) && c != ' ') ? 1 : 0;
                break;
            case 'l':
                res = (c >= 'a' && c <= 'z') ? 1 : 0;
                break;
            case 'p':
                res = ispunct(c) ? 1 : 0;
                break;
            case 's':
                res = isspace(c) ? 1 : 0;
                break;
            case 'u':
                res = (c >= 'A' && c <= 'Z') ? 1 : 0;
                break;
            case 'w':
                res = (isalpha(c) || (c >= '0' && c <= '9')) ? 1 : 0;
                break;
            case 'x':
                res = hexDigit(c) >= 0 ? 1 : 0;
                break;
            case 'z':
                res = (c == 0) ? 1 : 0;
                break;
            default:
                return (cl == c) ? 1 : 0;
        }
        // java diff: ASCII isLowerCase - 小写 = 正向类，大写 = 反向类（1-res）
        return (cl >= 'a' && cl <= 'z') ? res : 1 - res;
    }

    // lstrlib.c: isalpha
    private static boolean isalpha(int c) {
        return (c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z');
    }

    private static boolean iscntrl(int c) {
        return c < 0x20 || c == 0x7F;
    }

    private static boolean isprint(int c) {
        return c >= 0x20 && c < 0x7F;
    }

    private static boolean isspace(int c) {
        return c == ' ' || (c >= 0x09 && c <= 0x0D);
    }

    private static boolean ispunct(int c) {
        return isprint(c) && !isalpha(c) && !(c >= '0' && c <= '9') && c != ' ';
    }

    // java-only
    // lstrlib.c: hexDigit
    private static int hexDigit(int c) {
        if (c >= '0' && c <= '9') return c - '0';
        if (c >= 'a' && c <= 'f') return c - 'a' + 10;
        if (c >= 'A' && c <= 'F') return c - 'A' + 10;
        return -1;
    }

    // lstrlib.c: matchBracketClass
    static int matchBracketClass(int c, byte[] p, int pi, int ec) {
        int sig = 1;
        if (p[pi] == '^') {
            sig = 0;
            pi++;
        }
        int i = pi;
        while (i < ec) {
            if (p[i] == L_ESC) {
                i++;
                if (matchClass(c, p[i] & 0xFF) != 0) return sig;
            } else if (i + 1 < ec && p[i + 1] == '-' && i + 2 < ec) {
                i += 2;
                if ((p[i - 2] & 0xFF) <= c && c <= (p[i] & 0xFF)) return sig;
            } else if ((p[i] & 0xFF) == c) return sig;
            i++;
        }
        return 1 - sig;
    }

    // lstrlib.c: singlematch
    static int singlematch(MatchState ms, int s, int p, int ep) {
        if (s >= ms.srcEnd) return 0;
        int c = ms.src[s] & 0xFF;
        return switch (ms.pat[p]) {
            case '.' -> 1;
            case L_ESC -> matchClass(c, ms.pat[p + 1] & 0xFF);
            case '[' -> matchBracketClass(c, ms.pat, p + 1, ep - 1);
            default -> (ms.pat[p] & 0xFF) == c ? 1 : 0;
        };
    }

    // -- 匹配辅助 --
    // lstrlib.c: matchBalance
    static int matchBalance(MatchState ms, int s, int p) {
        if (p + 1 >= ms.patEnd) throw LuaErrors.errorObject("malformed pattern (missing arguments to '%b')");
        if (s >= ms.srcEnd || ms.src[s] != ms.pat[p]) return -1;
        int b = ms.pat[p], e = ms.pat[p + 1], cont = 1;
        for (int i = s + 1; i < ms.srcEnd; i++) {
            if (ms.src[i] == e) {
                if (--cont == 0) return i + 1;
            } else if (ms.src[i] == b) cont++;
        }
        return -1;
    }

    // lstrlib.c: maxExpand
    static int maxExpand(MatchState ms, int s, int p, int ep) {
        int i = 0;
        while (s + i < ms.srcEnd && singlematch(ms, s + i, p, ep) != 0) i++;
        while (i >= 0) {
            int res = match(ms, s + i, ep + 1);
            if (res >= 0) return res;
            i--;
        }
        return -1;
    }

    // lstrlib.c: minExpand
    static int minExpand(MatchState ms, int s, int p, int ep) {
        for (; ; ) {
            int res = match(ms, s, ep + 1);
            if (res >= 0) return res;
            else if (s < ms.srcEnd && singlematch(ms, s, p, ep) != 0) s++;
            else return -1;
        }
    }

    // lstrlib.c: startCapture
    static int startCapture(MatchState ms, int s, int p, int what) {
        int level = ms.level;
        if (level >= MAX_CAPTURES) throw LuaErrors.errorObject("too many captures");
        ms.captureInit[level] = s;
        ms.captureLen[level] = what;
        ms.level = level + 1;
        int res = match(ms, s, p);
        if (res < 0) ms.level--;
        return res;
    }

    // lstrlib.c: endCapture
    static int endCapture(MatchState ms, int s, int p) {
        int l = captureToClose(ms);
        ms.captureLen[l] = s - ms.captureInit[l];
        int res = match(ms, s, p);
        if (res < 0) ms.captureLen[l] = CAP_UNFINISHED;
        return res;
    }

    // lstrlib.c: matchCapture
    static int matchCapture(MatchState ms, int s, int l) {
        l = checkCapture(ms, l);
        int len = ms.captureLen[l];
        // lstrlib.c: match_capture  -  位置捕获（len==CAP_POSITION）不可作反向引用：匹配失败（-1）。
        //   否则负长度会滑过 srcEnd 检查并返回 s-2 ⇒ find('abcdef','()%1') 给出 end<start。
        if (len == CAP_POSITION) return -1;
        if (ms.srcEnd - s < len) return -1;
        for (int i = 0; i < len; i++) {
            if (ms.src[ms.captureInit[l] + i] != ms.src[s + i]) return -1;
        }
        return s + len;
    }

    // -- 主匹配 --
    // lstrlib.c: match
    // java diff: while+continue 模拟 C 的 goto init; break outer 替代 return s; dflt 返回 long(s<<32|p)
    static int match(MatchState ms, int s, int p) {
        if (ms.matchdepth-- == 0) throw LuaErrors.errorObject("pattern too complex");
        outer:
        while (p < ms.patEnd) {
            switch (ms.pat[p]) {
                case '(' -> {
                    if (p + 1 < ms.patEnd && ms.pat[p + 1] == ')')
                        s = startCapture(ms, s, p + 2, CAP_POSITION);
                    else
                        s = startCapture(ms, s, p + 1, CAP_UNFINISHED);

                    break outer;
                }
                case ')' -> {
                    s = endCapture(ms, s, p + 1);

                    break outer;
                }
                case '$' -> {
                    if (p + 1 != ms.patEnd) {
                        long r = dflt(ms, s, p);
                        s = (int) (r >>> 32);
                        p = (int) r;
                        if (s < 0) {
                            ms.matchdepth++;
                            return -1;
                        }
                        continue;
                    }
                    ms.matchdepth++;
                    return s == ms.srcEnd ? s : -1;
                }
                case L_ESC -> {
                    if (p + 1 >= ms.patEnd) throw LuaErrors.errorObject("malformed pattern (ends with '%')");
                    switch (ms.pat[p + 1]) {
                        case 'b' -> {
                            s = matchBalance(ms, s, p + 2);
                            if (s < 0) {
                                ms.matchdepth++;
                                return -1;
                            }
                            p += 4;
                            continue;
                        }
                        case 'f' -> {
                            p += 2;
                            if (p >= ms.patEnd || ms.pat[p] != '[')
                                throw LuaErrors.errorObject("missing '[' after '%f' in pattern");
                            int ep = classend(ms, p);
                            int prev = (s == ms.srcInit) ? 0 : (ms.src[s - 1] & 0xFF);
                            int cur = (s < ms.srcEnd) ? (ms.src[s] & 0xFF) : 0;
                            if (matchBracketClass(prev, ms.pat, p + 1, ep - 1) == 0 &&
                                    matchBracketClass(cur, ms.pat, p + 1, ep - 1) != 0) {
                                p = ep;
                                continue;
                            }
                            s = -1;
                            break outer;
                        }
                        default -> {
                            // java diff: ASCII isDigit - Lua pattern 回引 %1-%9 恒为 ASCII 数字
                            if (ms.pat[p + 1] >= '0' && ms.pat[p + 1] <= '9') {
                                s = matchCapture(ms, s, ms.pat[p + 1]);
                                if (s < 0) {
                                    ms.matchdepth++;
                                    return -1;
                                }
                                p += 2;
                                continue;
                            }
                            long r = dflt(ms, s, p);
                            s = (int) (r >>> 32);
                            p = (int) r;
                            if (s < 0) {
                                ms.matchdepth++;
                                return -1;
                            }
                            continue;
                        }
                    }
                }
                default -> {
                    long r = dflt(ms, s, p);
                    s = (int) (r >>> 32);
                    p = (int) r;
                    if (s < 0) {
                        ms.matchdepth++;
                        return -1;
                    }
                    continue;
                }
            }
        }
        ms.matchdepth++;
        return s;
    }

    // lstrlib.c: dflt  -  模式类 + 可选后缀
    // java diff: 结果打包成 long（s<<32 | p）寄存器传递，C2 可把 dflt 内联进 match
    //   （字段写让结果逃逸会阻止内联）。调用方解包：s = (int)(r >>> 32); p = (int)r;
    private static long dflt(MatchState ms, int s, int p) {
        int ep = classend(ms, p);
        if (singlematch(ms, s, p, ep) == 0) {
            if (ep < ms.patEnd && (ms.pat[ep] == '*' || ms.pat[ep] == '?' || ms.pat[ep] == '-')) {
                return ((long) s << 32) | (ep + 1 & 0xFFFFFFFFL);
            } else {
                return ((long) -1 << 32) | (p & 0xFFFFFFFFL);
            }
        }
        int c = (ep < ms.patEnd) ? ms.pat[ep] : -1;
        if (c == '?') {
            int res = match(ms, s + 1, ep + 1);
            if (res >= 0) return ((long) res << 32) | (ms.patEnd & 0xFFFFFFFFL);
            else return ((long) s << 32) | (ep + 1 & 0xFFFFFFFFL);
        } else if (c == '+') {
            return ((long) maxExpand(ms, s + 1, p, ep) << 32) | (ms.patEnd & 0xFFFFFFFFL);
        } else if (c == '*') {
            return ((long) maxExpand(ms, s, p, ep) << 32) | (ms.patEnd & 0xFFFFFFFFL);
        } else if (c == '-') {
            return ((long) minExpand(ms, s, p, ep) << 32) | (ms.patEnd & 0xFFFFFFFFL);
        } else {
            return ((long) (s + 1) << 32) | (ep & 0xFFFFFFFFL);
        }
    }

    // -- Find/Match --
    // lstrlib.c: str_find_aux
    // java diff: reprepstate 复用 MatchState 免去每次循环 new+arraycopy; ArrayList<Integer> 替代 push_captures
    // java-only: 单实例池化 MatchState（MS_FIND_POOL），跨 strFindAux 调用复用 int[32]，对齐 C 的栈分配语义
    static int[] strFindAux(Varargs args, boolean isFind) {
        // lstrlib.c str_find_aux 开头的 luaL_checklstring —— 经 LuaErrors 封装带参数号/
        //   函数名/where 前缀（裸 checkstring 只给类型消息，与 C 不一致）
        LuaString s = LuaErrors.checkStr(args, 1);
        LuaString p = LuaErrors.checkStr(args, 2);
        int ls = s.rawlen();
        int lp = p.rawlen();
        // lstrlib.c: str_find_aux —— init 是 lua_Integer（64 位）：巨值 > ls+1 返回 nil、
        // 过负回落 1
        long initL = LuaErrors.optLong(args, 3, 1);
        if (initL < 0) {
            initL += ls + 1;
            if (initL < 0) initL = 0;  // posrelatI：过负回落 1（下行补 +1）
        }
        int init = (int) Math.min(initL, ls + 2);
        if (init < 1) init = 1;
        else if (init > ls + 1) return null;
        int s1 = init - 1;

        boolean plain = isFind && (!args.arg(4).isnil() && args.arg(4).toboolean()) || noSpecials(p);
        if (plain) {
            int found = lmemfind(s, s1, p);
            if (found >= 0) return new int[]{found + 1, found + lp};
            return null;
        }
        boolean anchor = lp > 0 && (p.contents[0] & 0xFF) == '^';
        int patStart = (anchor ? 1 : 0);
        // lstrlib.c: prepstate  -  直接用 s.contents，不拷贝
        // java diff: C 用 const char* 直指源串；Java 直接共享 LuaString.contents ——
        //   final byte[] 构造时已对齐到偏移 0，MatchState 只读不写 ⇒ 可直接共享
        byte[] srcBytes = s.contents;
        byte[] patBytes = p.contents;
        // java-only: 单实例池（strFindAux 不调用用户代码、不重入，无需 try/finally）
        //   C 在栈帧上分配 MatchState（零堆开销）；Java 用单实例 ThreadLocal 复用，免去每次分配 int[32]
        //   A/B 开关：-Dluajvm.mspool=false 回退到每次 new（基线对照）
        MatchState ms = acquireFindMS();
        ms.prepstate(srcBytes, ls, patBytes, lp);
        // java-only: try/finally 仅为退出时清掉 src/pat 引用——ms 是 ThreadLocal 常驻
        //   单实例，不清则每线程钉住最后一次匹配的主串字节数组。C 的 MatchState 栈分配
        //   随函数返回消失，无此问题。
        try {
            for (; ; ) {
                // lstrlib.c: reprepstate  -  每次循环仅重置 level 与 matchdepth
                ms.reprepstate();
                int res = match(ms, s1, patStart);
                if (res >= 0) {
                    for (int i = 0; i < ms.level; i++)
                        if (ms.captureLen[i] == CAP_UNFINISHED)
                            throw LuaErrors.errorObject("unfinished capture");

                    if (isFind) {
                        int nlevels = ms.level;
                        int[] r = new int[2 + nlevels * 2];
                        r[0] = s1 + 1;
                        r[1] = res;
                        for (int i = 0; i < nlevels; i++) {
                            if (isPositionCap(ms, i)) {
                                r[2 + i * 2] = ms.captureInit[i] + 1;
                                r[2 + i * 2 + 1] = -1;
                            } else {
                                r[2 + i * 2] = ms.captureInit[i] + 1;
                                r[2 + i * 2 + 1] = ms.captureInit[i] + ms.captureLen[i];
                            }
                        }
                        return r;
                    } else {
                        int nlevels = (ms.level == 0) ? 1 : ms.level;
                        int[] result = new int[nlevels * 2];
                        for (int i = 0; i < nlevels; i++) {
                            if (i >= ms.level) {
                                result[i * 2] = s1 + 1;
                                result[i * 2 + 1] = res;
                            } else if (isPositionCap(ms, i)) {
                                result[i * 2] = ms.captureInit[i] + 1;
                                result[i * 2 + 1] = -1;
                            } else {
                                result[i * 2] = ms.captureInit[i] + 1;
                                result[i * 2 + 1] = ms.captureInit[i] + ms.captureLen[i];
                            }
                        }
                        return result;
                    }
                }
                if (anchor) break;
                s1++;
                if (s1 > ls) break;
            }
            return null;
        } finally {
            ms.clearSubject();
        }
    }

    // lstrlib.c: strFind（Varargs 版：错误经 LuaErrors 封装带参数号/函数名）
    static int[] strFind(Varargs args) {
        return strFindAux(args, true);
    }

    // callOnStack 快路径：guard 已验型，包成 Varargs 委托
    static int[] strFind(LuaValue a1, LuaValue a2, LuaValue a3, LuaValue a4) {
        return strFindAux(LuaValue.varargsOf(new LuaValue[]{a1, a2, a3, a4}), true);
    }

    // lstrlib.c: strMatch
    static int[] strMatch(Varargs args) {
        return strFindAux(args, false);
    }

    static int[] strMatch(LuaValue a1, LuaValue a2, LuaValue a3) {
        return strFindAux(LuaValue.varargsOf(a1, LuaValue.varargsOf(a2, a3)), false);
    }

    // java-only
    // lstrlib.c: noSpecials  -  SPECIALS_TABLE 查表替代 indexOf（O(1)/字节 vs O(12)/字节）
    private static boolean noSpecials(LuaString p) {
        int end = p.shrlen;
        for (int i = 0; i < end; i++) {
            if (SPECIALS_TABLE[p.contents[i] & 0xFF]) return false;
        }
        return true;
    }

    private static int lmemfind(LuaString s, int s1, LuaString p) {
        int ls = s.shrlen;
        int lp = p.shrlen;
        if (lp == 0) return s1;  // 空模式匹配起始位置 (lstrlib.c)
        if (lp > ls - s1) return -1;  // 模式长于剩余字符串 (lstrlib.c)
        byte[] sc = s.contents;  // 局部变量缓存数组引用，便于 JIT 寄存器分配
        byte[] pc = p.contents;
        int first = pc[0];
        int lastStart = ls - lp;
        int searchLen = ls - s1;

        // 阶段 1: 逐字节探测前 PROBE_LEN 个位置 (对齐 C 的 memchr 在短区间上的行为)
        // 快速捕获早期匹配（如 find_plain 模式在 pos 0 命中），免去 String 创建开销
        int probeEnd = s1 + PROBE_LEN;
        if (probeEnd > lastStart + 1) probeEnd = lastStart + 1;
        int i = s1;
        while (i < probeEnd) {
            if (sc[i] == first) {
                int j = 1;
                while (j < lp && sc[i + j] == pc[j]) j++;
                if (j == lp) return i;
            }
            i++;
        }

        // 阶段 2: 长源串 SIMD 快速路径 (对齐 C 的 memchr SIMD 加速)
        // String.indexOf(int) 是 HotSpot intrinsic，内部用 SIMD (16-32 字节/周期) 找首字节
        // 找到候选后逐字节比对剩余 (对齐 C 的 memcmp)
        if (searchLen >= SIMD_THRESHOLD) {
            // java diff: C 用 memchr+memcmp；Java 用 String.indexOf(int) + 逐字节比对
            // 复用 LuaString.cachedString 免去每次调用的 String 创建开销（O(n) arraycopy）
            // s1==0 且覆盖全串时直接用缓存；否则按子串创建（边界场景）
            String srcStr = (s1 == 0 && searchLen == ls) ? s.cachedString()
                    : new String(sc, s1, searchLen, ISO_8859_1);
            int firstChar = first & 0xFF;  // ISO-8859-1: byte 0-255 <-> char 0-255
            int fromIndex = i - s1;  // 从阶段 1 停止处继续
            int searchLimit = lastStart - s1;  // 模式能放下的最后位置（相对 s1）
            while (true) {
                int idx = srcStr.indexOf(firstChar, fromIndex);
                if (idx < 0 || idx > searchLimit) return -1;
                // 比对剩余字节 (对齐 C 的 memcmp(init, s2+1, l2))
                int j = 1;
                while (j < lp && sc[s1 + idx + j] == pc[j]) j++;
                if (j == lp) return s1 + idx;
                fromIndex = idx + 1;  // 不匹配，从下一位置继续找首字节
            }
        }

        // 阶段 3: 短源串继续逐字节扫描（避免 String 创建开销）
        while (i <= lastStart) {
            if (sc[i] == first) {
                int j = 1;
                while (j < lp && sc[i + j] == pc[j]) j++;
                if (j == lp) return i;
            }
            i++;
        }
        return -1;  // not found (lstrlib.c)
    }

    // lstrlib.c: gmatch_aux  -  复用 MatchState（C 在 gmatch() 中 prepstate 一次，此处 reprepstate 复用）
    // 参数：src1Based=当前搜索起点(1-based)，lastmatch0Based=上次匹配的 0-based exclusive end(-1=无)。
    //   返回 [start_1based, end_0based_exclusive, cap1_start, cap1_end, ...] 或 null：
    //   位置捕获的 end 为 -1；无捕获时长度 = 2
    // java diff: C 的 gmatch 不剥离 ^ 锚点（^ 作字面量处理），此处对齐 C；返回 int[] 而非直接 push 到栈（调用方构造 LuaValue）
    static int[] gmatchNext(MatchState ms, int src1Based, int lastmatch0Based) {
        int ls = ms.srcEnd;
        int s1 = src1Based - 1;  // 转 0-based
        for (; s1 <= ls; s1++) {
            ms.reprepstate();
            int e = match(ms, s1, 0);  // patStart=0：不剥离 ^（对齐 C 的 gmatch_aux）
            if (e >= 0 && e != lastmatch0Based) {
                int nlevels = ms.level;
                int[] r;
                if (nlevels == 0) {
                    r = new int[2];
                } else {
                    r = new int[2 + nlevels * 2];
                    for (int i = 0; i < nlevels; i++) {
                        // lstrlib.c: get_onecapture  -  未闭合捕获须在此报错，
                        //   否则 -1 长度传到下游 newLstr 会抛 Java 层 "2 > 1"。
                        if (ms.captureLen[i] == CAP_UNFINISHED) {
                            throw LuaErrors.errorObject("unfinished capture");
                        }
                        if (isPositionCap(ms, i)) {
                            r[2 + i * 2] = ms.captureInit[i] + 1;
                            r[2 + i * 2 + 1] = -1;
                        } else {
                            r[2 + i * 2] = ms.captureInit[i] + 1;
                            r[2 + i * 2 + 1] = ms.captureInit[i] + ms.captureLen[i];
                        }
                    }
                }
                r[0] = s1 + 1;
                r[1] = e;  // 0-based exclusive end = 1-based inclusive end
                return r;
            }
        }
        return null;
    }

    // -- GSub --
    // lstrlib.c: str_gsub
    // java diff: ByteArrayOutputStream 替代 StringBuilder 以保持 UTF-8 字节完整性（C 用 luaL_Buffer 操作原始字节）
    // java diff: reprepstate 复用 MatchState 免去每次循环 new+arraycopy
    static Varargs strGsub(Globals globals, LuaValue arg1, LuaValue arg2, LuaValue arg3, LuaValue arg4) {
        // java-only: 获取 L 供 addValue 函数替换路径直接栈操作（对齐 C 的 ms->L -> lua_call）
        LuaThread L = globals != null ? globals.running : null;
        LuaString s = arg1.checkstring();
        LuaString p = arg2.checkstring();
        int ls = s.rawlen();
        int lp = p.rawlen();
        // lstrlib.c: str_gsub —— max_s 经 luaL_optinteger 先读（arg4 非法先于 arg3 报错）；
        // lua_Integer 64 位：巨值（如 2^33）不截断
        int maxS = arg4.isnil() ? ls + 1
                : (int) Math.min(arg4.checklong(), Integer.MAX_VALUE);
        // 数字替换合法（C: tr==LUA_TNUMBER 落 tostring 路径）
        if (!arg3.isstring() && !arg3.isnumber() && !arg3.isfunction() && !arg3.istable())
            LuaErrors.argError(3, "string/function/table expected, got " + arg3.typeName());
        boolean anchor = lp > 0 && (p.contents[0] & 0xFF) == '^';
        // lstrlib.c: prepstate  -  直接用 s.contents，不拷贝（同 strFindAux）
        byte[] srcBytes = s.contents;
        byte[] patBytes = p.contents;
        int patStart = 0;
        if (anchor) patStart = 1;
        // lstrlib.c: prepstate  -  只初始化一次
        // java-only: 从 free-list 池获取 MatchState
        //   free-list 可重入安全：strGsub->addValue->用户函数->string.find/gsub 时，重入调用弹出不同实例
        MatchState ms = acquireGsubMS();
        ms.prepstate(srcBytes, ls, patBytes, lp);
        try {
            ByteArrayOutputStream result = new ByteArrayOutputStream();
            int n = 0;
            int changed = 0;
            int src = 0;
            int lastmatch = -1;
            while (n < maxS) {
                // lstrlib.c: reprepstate  -  每次循环只重置level和matchdepth
                ms.reprepstate();
                int e = match(ms, src, patStart);
                if (e >= 0 && e != lastmatch) {
                    n++;
                    int c = addValue(L, result, arg3, ms, srcBytes, src, e);
                    if (c == 0) {
                        result.write(srcBytes, src, e - src);
                    }
                    changed = (c != 0 || changed != 0) ? 1 : 0;  // lstrlib.c:  -  C 用 ||（逻辑或）
                    lastmatch = e;
                    src = e;
                } else if (src < ls) {
                    result.write(srcBytes[src] & 0xFF);
                    src++;
                } else {
                    break;
                }
                if (anchor) break;
            }
            result.write(srcBytes, src, ls - src);
            if (changed == 0) return LuaValue.varargsOf(s, LuaInteger.valueOf(n));
            return LuaValue.varargsOf(LuaString.newLstr(result.toByteArray(), 0, result.size()), LuaInteger.valueOf(n));
        } finally {
            releaseGsubMS(ms);
        }
    }

    // lstrlib.c: add_value
    // java diff: ByteArrayOutputStream 替代 StringBuilder 保持 UTF-8 字节完整性
    // java diff: 函数替换路径走 invokeNoYield + Varargs 而非直接栈操作（Varargs 分配被 JIT 标量替换消除，近零成本）
    private static int addValue(LuaThread L, ByteArrayOutputStream baos, LuaValue repl, MatchState ms, byte[] srcBytes, int matchStart, int matchEnd) {
        if (repl.isstring()) {
            LuaString rs = repl.checkstring();
            addGsub(baos, rs.contents, rs.shrlen, ms, srcBytes, matchStart, matchEnd);
            return 1;
        } else if (repl.isnumber()) {
            // 数字转字符串为纯 ASCII，UTF-8 与 ISO-8859-1 等价；统一 UTF-8 保持与 toJavaString 一致
            byte[] nb = repl.toJavaString().getBytes(StandardCharsets.UTF_8);
            baos.write(nb, 0, nb.length);
            return 1;
        } else if (repl.isfunction()) {
            int nargs = ms.level;
            if (nargs == 0) nargs = 1;
            LuaValue[] args = new LuaValue[nargs];
            if (ms.level == 0) {
                args[0] = LuaString.newLstr(srcBytes, matchStart, matchEnd - matchStart);
            } else {
                for (int i = 0; i < ms.level; i++) {
                    int start = ms.captureInit[i];
                    int len = ms.captureLen[i];
                    // lstrlib.c: get_onecapture  -  仅 CAP_POSITION(-2) 转位置值；CAP_UNFINISHED(-1) 报错。
                    if (len == CAP_UNFINISHED) {
                        throw LuaErrors.errorObject("unfinished capture");
                    }
                    if (len == CAP_POSITION) {
                        args[i] = LuaInteger.valueOf(start + 1);
                    } else {
                        args[i] = LuaString.newLstr(srcBytes, start, len);
                    }
                }
            }

            LuaValue r = LuaCall.invokeNoYield(repl, LuaValue.varargsOf(args)).arg1();
            // lstrlib.c: add_value  -  false/nil 保留原文；boolean 是非法替换值。
            if (r.isboolean()) {
                if (r.toboolean()) {
                    throw LuaErrors.errorObject("invalid replacement value (a boolean)");
                }
                return 0;
            }
            if (!r.isnil()) {
                if (!r.isstring() && !r.isnumber())
                    throw LuaErrors.errorObject("invalid replacement value (a " + r.typeName() + ")");
                LuaString rs = r.strValue();
                baos.write(rs.contents, 0, rs.shrlen);
                return 1;
            }
            return 0;
        } else if (repl.istable()) {
            LuaValue key;
            if (ms.level == 0) {
                key = LuaString.newLstr(srcBytes, matchStart, matchEnd - matchStart);
            } else {
                int start = ms.captureInit[0];
                int len = ms.captureLen[0];
                if (len == CAP_UNFINISHED) {
                    throw LuaErrors.errorObject("unfinished capture");
                }
                if (len == CAP_POSITION) {
                    key = LuaInteger.valueOf(start + 1);
                } else {
                    key = LuaString.newLstr(srcBytes, start, len);
                }
            }
            LuaValue r = LuaIndex.finishGet(repl, key);
            if (r.isboolean()) {
                if (r.toboolean()) {
                    throw LuaErrors.errorObject("invalid replacement value (a boolean)");
                }
                return 0;
            }
            if (!r.isnil()) {
                if (!r.isstring() && !r.isnumber())
                    throw LuaErrors.errorObject("invalid replacement value (a " + r.typeName() + ")");
                LuaString rs = r.strValue();
                baos.write(rs.contents, 0, rs.shrlen);
                return 1;
            }
            return 0;
        } else {
            LuaString rs = repl.strValue();
            baos.write(rs.contents, 0, rs.shrlen);
            return 1;
        }
    }

    // lstrlib.c: add_s (addGsub)
    // java diff: 直接操作byte[]替代String+StringBuilder，保持UTF-8字节完整性
    private static void addGsub(ByteArrayOutputStream baos, byte[] replBytes, int replLen, MatchState ms, byte[] srcBytes, int matchStart, int matchEnd) {
        for (int i = 0; i < replLen; i++) {
            int c = replBytes[i] & 0xFF;
            if (c == '%') {
                // lstrlib.c: add_s  -  尾部孤立 '%' 是错误，不可当字面量输出。
                if (i + 1 >= replLen) {
                    throw LuaErrors.errorObject("invalid use of '%' in replacement string");
                }
                int next = replBytes[i + 1] & 0xFF;
                if (next == '%') {
                    baos.write('%');
                    i++;
                } else if (next == '0') {
                    baos.write(srcBytes, matchStart, matchEnd - matchStart);
                    i++;
                } else if (next >= '1' && next <= '9') {
                    int capIdx = next - '1';
                    if (capIdx >= ms.level) {
                        if (capIdx != 0)
                            throw LuaErrors.errorObject("invalid capture index %" + (capIdx + 1));
                        baos.write(srcBytes, matchStart, matchEnd - matchStart);
                    } else {
                        int cs = ms.captureInit[capIdx];
                        int cl = ms.captureLen[capIdx];
                        // lstrlib.c: get_onecapture —— 仅未闭合捕获报错；位置捕获在替换串
                        // 里合法（写位置数字，pm.lua:174 实测 '12o 56o'）
                        if (cl == CAP_UNFINISHED) {
                            throw LuaErrors.errorObject("unfinished capture");
                        }
                        if (cl == CAP_POSITION) {
                            byte[] posBytes = Integer.toString(cs + 1).getBytes(StandardCharsets.ISO_8859_1);
                            baos.write(posBytes, 0, posBytes.length);
                        } else {
                            baos.write(srcBytes, cs, cl);
                        }
                    }
                    i++;
                } else {
                    throw LuaErrors.errorObject("invalid use of '%' in replacement string");
                }
            } else {
                baos.write(c);
            }
        }
    }

    // -- MatchState --
    // lstrlib.c: MatchState
    // java diff: 内部类替代栈分配struct; byte[]+偏移替代const char*; 异常替代longjmp
    // java diff: dflt 结果打包成 long（s<<32 | p）寄存器传递，启用 C2 内联 dflt 到 match
    // java-only: src/pat等字段非final，支持ThreadLocal池化复用（对齐C的栈分配语义：
    //   C每次str_find_aux调用在栈帧上分配MatchState，函数返回后栈帧回收；
    //   Java用ThreadLocal缓存MatchState实例，跨调用复用int[32]数组，避免每次堆分配）
    static final class MatchState {
        final int[] captureInit = new int[MAX_CAPTURES];
        final int[] captureLen = new int[MAX_CAPTURES];
        byte[] src;
        int srcInit, srcEnd;
        byte[] pat;
        int patEnd;
        int matchdepth;
        int level;

        // java-only: 无参构造器供ThreadLocal池使用，int数组只分配一次
        MatchState() {
            this.matchdepth = MAXCCALLS;
            this.level = 0;
        }

        /**
         * java-only: 清除对主串与模式串字节数组的引用。
         *
         * <p>C 的 MatchState 是栈分配局部变量，函数返回即消失；Java 池化到 ThreadLocal 后
         * 不清则每线程钉住最后一次匹配的主串。只清引用字段，int[] 捕获数组保留供复用。
         */
        void clearSubject() {
            src = null;
            pat = null;
            srcInit = 0;
            srcEnd = 0;
            patEnd = 0;
        }

        MatchState(byte[] src, int srcLen, byte[] pat, int patLen) {
            prepstate(src, srcLen, pat, patLen);
        }

        // lstrlib.c: prepstate  -  设置src/pat指针和边界（对齐C的prepstate）
        // java diff: C 把 matchdepth 移到 reprepstate；Java 池化复用时 matchdepth 由 reprepstate 在每次匹配前重置，语义等价
        // java-only: 池化复用时通过此方法重设src/pat，避免重新分配int[32]数组
        void prepstate(byte[] src, int srcLen, byte[] pat, int patLen) {
            this.src = src;
            this.srcInit = 0;
            this.srcEnd = srcLen;
            this.pat = pat;
            this.patEnd = patLen;
        }

        // lstrlib.c: reprepstate  -  重置每次匹配变化的字段（对齐C的reprepstate）
        void reprepstate() {
            this.matchdepth = MAXCCALLS;
            this.level = 0;
        }
    }


}
