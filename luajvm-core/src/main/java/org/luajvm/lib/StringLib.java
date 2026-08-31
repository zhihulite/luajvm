// ref: lstrlib.c
// diff: 模式匹配拆分到StringPattern; 格式化拆分到StringFormat; CHAR_TABLE代替ctype; byte[]代替const char*
package org.luajvm.lib;

import org.luajvm.core.BinaryOp;
import org.luajvm.core.Globals;
import org.luajvm.core.LuaCClosure;
import org.luajvm.core.LuaClosure;
import org.luajvm.core.LuaErrors;
import org.luajvm.core.LuaFloat;
import org.luajvm.core.LuaFunction;
import org.luajvm.core.LuaInteger;
import org.luajvm.core.LuaString;
import org.luajvm.core.LuaTable;
import org.luajvm.core.LuaThread;
import org.luajvm.core.LuaUserdata;
import org.luajvm.core.LuaValue;
import org.luajvm.core.UnaryOp;
import org.luajvm.core.Varargs;
import org.luajvm.vm.LuaArith;
import org.luajvm.vm.LuaCall;
import org.luajvm.vm.LuaChunk;

public class StringLib extends LuaFunction {

    static final byte[] CHAR_TABLE;
    private static final int MASK_ALPHA = 0x01, MASK_LOWERCASE = 0x02, MASK_UPPERCASE = 0x04;
    private static final int MASK_DIGIT = 0x08, MASK_PUNCT = 0x10, MASK_SPACE = 0x20;

    static {
        CHAR_TABLE = new byte[256];
        for (int i = 0; i < 128; i++) {
            char c = (char) i;
            CHAR_TABLE[i] = (byte) ((Character.isDigit(c) ? MASK_DIGIT : 0)
                    | (Character.isLowerCase(c) ? MASK_LOWERCASE : 0)
                    | (Character.isUpperCase(c) ? MASK_UPPERCASE : 0)
                    | ((c < ' ' || c == 0x7F) ? 0x40 : 0));
            if ((c >= 'a' && c <= 'f') || (c >= 'A' && c <= 'F') || (c >= '0' && c <= '9'))
                CHAR_TABLE[i] |= (byte) 0x80;
            if ((c >= '!' && c <= '/') || (c >= ':' && c <= '@') || (c >= '[' && c <= '`') || (c >= '{' && c <= '~'))
                CHAR_TABLE[i] |= MASK_PUNCT;
            if ((CHAR_TABLE[i] & (MASK_LOWERCASE | MASK_UPPERCASE)) != 0)
                CHAR_TABLE[i] |= MASK_ALPHA;
        }
        CHAR_TABLE[' '] = MASK_SPACE;
        CHAR_TABLE['\r'] |= MASK_SPACE;
        CHAR_TABLE['\n'] |= MASK_SPACE;
        CHAR_TABLE['\t'] |= MASK_SPACE;
        CHAR_TABLE[0x0B] |= MASK_SPACE;
        CHAR_TABLE['\f'] |= MASK_SPACE;
    }

    public StringLib() {
    }

    // lstrlib.c: posrelat
    static int posrelat(int pos, int len) {
        return (pos >= 0) ? pos : len + pos + 1;
    }

    // lstrlib.c: posrelat  -  C 用 lua_Integer（64 位）保住巨值下标，避免 32 位截断把
    // ("abc"):byte(9223372036854775807) 误判成倒数第一个字符（截断成 -1）。返回 long，
    // 由调用方对串长 len 做边界判定后再收窄，超长下标自然落到越界（返回 nil/空串）。
    static long posrelat(long pos, int len) {
        return (pos >= 0) ? pos : (long) len + pos + 1;
    }

    // lauxlib.c: luaL_getmetafield  -  从对象元表取元字段
    // java diff: C 把结果压到 Lua 栈并返回类型标签；Java 返回 LuaValue（未找到返 null）
    // java diff: C 用 lua_getmetatable + lua_rawget；Java 用 getmetatable + rawget
    private static LuaValue getMetaField(LuaValue obj, LuaString event) {
        LuaValue mt = obj.getmetatable();
        if (mt == null) return null;
        // C: lua_pushstring(L, event); tt = lua_rawget(L, -2)
        if (mt instanceof LuaTable table) {
            LuaValue tm = table.rawget(event);
            return tm.isnil() ? null : tm;
        }
        LuaValue tm = mt.rawget(event);
        return (tm == null || tm.isnil()) ? null : tm;
    }

    // lstrlib.c: tonum  -  检查参数是否为数字或数字字符串
    // java diff: C 把数字压到 Lua 栈；Java 返回 LuaValue（不可强转返 nil）
    private static LuaValue tonum(LuaValue arg) {
        if (arg.type() == LuaValue.TNUMBER) return arg;
        return arg.tonumber();
    }

    // lstrlib.c: trymt  -  try second operand's metamethod
    // java diff: C 用 lua_settop/lua_insert/lua_call；Java 用 LuaCall.callNoYield
    private static Varargs trymt(LuaValue a, LuaValue b, LuaString mtkey, String opname) {
        if (b.type() == LuaValue.TSTRING)
            LuaErrors.runErrorWithInfo("attempt to " + opname + " a '" + a.typeName() + "' with a '" + b.typeName() + "'");
        LuaValue tm = getMetaField(b, mtkey);
        // java diff: C 用 lua_call/lua_settop 直接栈操作；Java 走 2-arg callNoYield
        //   直接栈快路径（对齐 lstrlib.c lua_call），零 Varargs 分配。
        if (tm != null) return LuaCall.callNoYield(tm, a, b);
        LuaErrors.runErrorWithInfo("attempt to " + opname + " a '" + a.typeName() + "' with a '" + b.typeName() + "'");
        return LuaValue.NIL;
    }

    @Override
    public Varargs call(Varargs args) {
        LuaValue modname = args.arg1();
        LuaValue env = args.arg(2);
        LuaTable string = new LuaTable();
        Globals globals = env.checkglobals();
        string.set("byte", new ByteFn());
        string.set("char", new CharFn());
        string.set("dump", new DumpFn());
        string.set("find", new FindFn());
        string.set("format", new FormatFn());
        string.set("gmatch", new GmatchFn());
        string.set("gsub", new GsubFn());
        string.set("len", new LenFn());
        string.set("lower", new LowerFn());
        string.set("match", new MatchFn());
        string.set("rep", new RepFn());
        string.set("reverse", new ReverseFn());
        string.set("sub", new SubFn());
        string.set("upper", new UpperFn());
        string.set("toutf8", new ToUtf8Fn());
        string.set("pack", new PackFn());
        string.set("packsize", new PackSizeFn());
        string.set("unpack", new UnpackFn());
        env.set("string", string);
        if (!env.get("package").isnil()) env.get("package").get("loaded").set("string", string);
        // C：lstate.h G(L)->mt[LUA_TSTRING] 按状态存储；Java 对齐为按 Globals 存储字符串元表。
        Globals gs = env.checkglobals();
        if (gs.typeMetatable(LuaValue.TSTRING) == null) {
            LuaTable mt = LuaValue.tableOf();
            mt.rawset(LuaValue.ADD, new ArithFn(BinaryOp.ADD));
            mt.rawset(LuaValue.SUB, new ArithFn(BinaryOp.SUB));
            mt.rawset(LuaValue.MUL, new ArithFn(BinaryOp.MUL));
            mt.rawset(LuaValue.MOD, new ArithFn(BinaryOp.MOD));
            mt.rawset(LuaValue.POW, new ArithFn(BinaryOp.POW));
            mt.rawset(LuaValue.DIV, new ArithFn(BinaryOp.DIV));
            mt.rawset(LuaValue.IDIV, new ArithFn(BinaryOp.IDIV));
            mt.rawset(LuaValue.UNM, new ArithUnmFn());
            mt.rawset(LuaValue.INDEX, string);
            gs.setTypeMetatable(LuaValue.TSTRING, mt);
        }
        return string;
    }

    // lstrlib.c: str_byte
    // lauxlib.c: luaL_optinteger —— nil 用默认值；无整数表示的浮点走 checklong 报错
    private static long optIntArg(Varargs args, int idx, long def) {
        return LuaErrors.optLong(args, idx, def);
    }

    // llimits.h: l_castU2s 可表示判定（fabs ≤ 2^63 边界由 Math.rint 精确处理）
    private static boolean hasIntForm(double d) {
        return d == Math.rint(d) && !Double.isInfinite(d)
                && (Math.abs(d) < 9.223372036854776E18);
    }

    static class ByteFn extends LuaFunction {
        @Override
        public Varargs call(Varargs args) {
            LuaString s = LuaErrors.checkStr(args, 1);
            int l = s.rawlen();
            // 下标用 64 位保住巨值（对齐 C lua_Integer），再对串长做边界判定后收窄为 int。
            // lstrlib.c: str_byte —— luaL_optinteger：无整数表示的浮点报错
            //（byte('abc',1.5) 不再静默截断成 97）
            long posiL = posrelat(optIntArg(args, 2, 1), l);
            long poseL = posrelat(optIntArg(args, 3, posiL), l);
            if (posiL < 1) posiL = 1;
            if (poseL > l) poseL = l;
            if (posiL > poseL) return LuaValue.NONE;
            int posi = (int) posiL, pose = (int) poseL;   // 已夹到 [1,l]，收窄安全
            int n = pose - posi + 1;
            LuaValue[] v = new LuaValue[n];
            for (int i = 0; i < n; i++) v[i] = LuaInteger.valueOf(s.luaByte(posi + i - 1) & 0xFF);
            return varargsOf(v);
        }

        // java-only: callOnStack  -  lstrlib.c str_byte  -  返回字符的字节值
        @Override
        public int callOnStack(LuaThread L, int func, int narg) {
            if (narg < 1) return -1;
            LuaValue v1 = L.stack[func + 1];
            if (v1 == null || !v1.isstring()) return -1;
            LuaString s = (LuaString) v1;
            int l = s.rawlen();
            // 下标用 64 位保住巨值（对齐 C lua_Integer），夹到 [1,l] 后再收窄为 int。
            long posiL, poseL;
            if (narg >= 2) {
                LuaValue v2 = L.stack[func + 2];
                if (v2 == null || !v2.isNumberTag()) return -1;
                if (v2.isfloat() && !hasIntForm(v2.todouble())) return -2;  // 交给 call() 报错
                posiL = posrelat(v2.tolong(), l);
            } else {
                posiL = 1;
            }
            if (narg >= 3) {
                LuaValue v3 = L.stack[func + 3];
                if (v3 == null || !v3.isNumberTag()) return -1;
                if (v3.isfloat() && !hasIntForm(v3.todouble())) return -2;
                poseL = posrelat(v3.tolong(), l);
            } else {
                poseL = posiL;
            }
            if (posiL < 1) posiL = 1;
            if (poseL > l) poseL = l;
            if (posiL > poseL) return 0;
            int posi = (int) posiL, pose = (int) poseL;   // 已夹到 [1,l]，收窄安全
            int n = pose - posi + 1;
            // lstrlib.c: luaL_checkstack(L, n, "string slice too long")
            if (L.top + n > 1000000) {
                // luaL_checkstack 的消息形态（"stack overflow (..." 前缀）
                LuaErrors.error("stack overflow (string slice too long)");
            }
            for (int i = 0; i < n; i++) {
                L.stack[L.top + i] = LuaInteger.valueOf(s.luaByte(posi + i - 1) & 0xFF);
            }
            L.top += n;
            return n;
        }
    }

    // lstrlib.c: str_char
    static class CharFn extends LuaFunction {
        @Override
        public Varargs call(Varargs args) {
            int n = args.narg();
            byte[] bytes = new byte[n];
            for (int i = 0; i < n; i++) {

                // lstrlib.c: str_char —— luaL_checkinteger + 范围检查（带 #N 包装）
                long c = LuaErrors.checkLong(args, i + 1);
                if (c < 0 || c > 255) LuaErrors.argError(i + 1, "value out of range");
                bytes[i] = (byte) c;
            }
            return LuaString.newLstr(bytes, 0, bytes.length);
        }

        // java-only: callOnStack  -  lstrlib.c str_char  -  将字节值转换为字符
        @Override
        public int callOnStack(LuaThread L, int func, int narg) {
            if (narg < 1) return -1;
            byte[] bytes = new byte[narg];
            for (int i = 0; i < narg; i++) {
                LuaValue v = L.stack[func + 1 + i];
                if (v == null || !v.isNumberTag()) return -1;
                if (v.isfloat() && !hasIntForm(v.todouble())) return -2;
                long c = v.tolong();
                if (c < 0 || c > 255) return -2;
                bytes[i] = (byte) c;
            }
            L.stack[L.top++] = LuaString.newLstr(bytes, 0, bytes.length);
            return 1;
        }
    }

    // lstrlib.c: str_dump
    static class DumpFn extends LuaFunction {
        @Override
        public Varargs call(Varargs args) {
            // lstrlib.c: str_dump —— lua_toboolean（''、'x'、数字都为真，optboolean
            // 只认 boolean）；非 Lua 函数统一 "Lua function expected"（带 #N）
            LuaValue fv = args.arg(1);
            if (!(fv instanceof LuaClosure))
                LuaErrors.argError(1, "Lua function expected");
            LuaFunction func = (LuaFunction) fv;
            boolean strip = args.arg(2).toboolean();  // lua_toboolean：nil/false 外皆真
            byte[] dumped = LuaChunk.dump(((LuaClosure) func).p, strip);
            return LuaString.newLstr(dumped, 0, dumped.length);
        }
    }

    // lstrlib.c: str_find
    static class FindFn extends LuaFunction {
        @Override
        public Varargs call(Varargs args) {
            int[] result = StringPattern.strFind(args);
            if (result == null) return LuaValue.NIL;
            LuaString s = LuaErrors.checkStr(args, 1);
            LuaValue[] v;
            if (result.length == 2) {
                v = new LuaValue[]{LuaInteger.valueOf(result[0]), LuaInteger.valueOf(result[1])};
            } else {
                int nCaps = (result.length - 2) / 2;
                v = new LuaValue[2 + nCaps];
                v[0] = LuaInteger.valueOf(result[0]);
                v[1] = LuaInteger.valueOf(result[1]);
                for (int i = 0; i < nCaps; i++) {
                    int cs = result[2 + i * 2];
                    int ce = result[2 + i * 2 + 1];
                    if (ce < 0) {
                        v[2 + i] = LuaInteger.valueOf(cs);
                    } else {
                        v[2 + i] = LuaString.newLstr(s.contents, cs - 1, ce - cs + 1);
                    }
                }
            }
            return varargsOf(v);
        }

        // java-only: callOnStack  -  lstrlib.c str_find  -  把结果压栈
        @Override
        public int callOnStack(LuaThread L, int func, int narg) {
            LuaValue s1 = L.stack[func + 1];
            if (s1 == null) s1 = LuaValue.NIL;
            LuaValue s2 = L.stack[func + 2];
            if (s2 == null) s2 = LuaValue.NIL;
            LuaValue s3 = narg >= 3 ? L.stack[func + 3] : LuaValue.NIL;
            if (s3 == null) s3 = LuaValue.NIL;
            LuaValue s4 = narg >= 4 ? L.stack[func + 4] : LuaValue.NIL;
            if (s4 == null) s4 = LuaValue.NIL;
            int[] result = StringPattern.strFind(s1, s2, s3, s4);
            if (result == null) {
                L.stack[L.top++] = LuaValue.NIL;
                return 1;
            }
            int top = L.top;
            if (result.length == 2) {
                // java-only: 无捕获时跳过 checkstring（对齐 C push_captures level==0 时不访问源串）
                L.stack[top] = LuaInteger.valueOf(result[0]);
                L.stack[top + 1] = LuaInteger.valueOf(result[1]);
                L.top = top + 2;
                return 2;
            } else {
                LuaString s = s1.checkstring();  // 仅捕获分支需要源串构造子串
                int nCaps = (result.length - 2) / 2;
                L.stack[top] = LuaInteger.valueOf(result[0]);
                L.stack[top + 1] = LuaInteger.valueOf(result[1]);
                for (int i = 0; i < nCaps; i++) {
                    int cs = result[2 + i * 2];
                    int ce = result[2 + i * 2 + 1];
                    if (ce < 0) {
                        L.stack[top + 2 + i] = LuaInteger.valueOf(cs);
                    } else {
                        L.stack[top + 2 + i] = LuaString.newLstr(s.contents, cs - 1, ce - cs + 1);
                    }
                }
                L.top = top + 2 + nCaps;
                return 2 + nCaps;
            }
        }
    }

    // lstrlib.c: str_match
    static class MatchFn extends LuaFunction {
        @Override
        public Varargs call(Varargs args) {
            LuaString s = LuaErrors.checkStr(args, 1);
            int[] result = StringPattern.strMatch(args);
            if (result == null) return LuaValue.NIL;

            if (result.length == 0) {
                // 无捕获：需要重新匹配获取起止位置
                int[] findResult = StringPattern.strFind(args.arg(1), args.arg(2), args.arg(3), LuaValue.FALSE);
                if (findResult == null) return LuaValue.NIL;
                int start = findResult[0] - 1;
                int end = findResult[1];
                return LuaString.newLstr(s.contents, start, end - start);
            } else {
                // 有捕获：每对 [start, end]；位置捕获用 end=-1
                LuaValue[] v = new LuaValue[result.length / 2];
                for (int i = 0; i < result.length; i += 2) {
                    int start = result[i] - 1;
                    int end = result[i + 1];
                    if (end < 0) {
                        v[i / 2] = LuaInteger.valueOf(result[i]);
                    } else {
                        v[i / 2] = LuaString.newLstr(s.contents, start, end - start);
                    }
                }
                return varargsOf(v);
            }
        }

        // java-only: callOnStack  -  lstrlib.c str_match  -  把结果压栈
        @Override
        public int callOnStack(LuaThread L, int func, int narg) {
            LuaValue s1 = L.stack[func + 1];
            if (s1 == null) s1 = LuaValue.NIL;
            LuaValue s2 = L.stack[func + 2];
            if (s2 == null) s2 = LuaValue.NIL;
            LuaValue s3 = narg >= 3 ? L.stack[func + 3] : LuaValue.NIL;
            if (s3 == null) s3 = LuaValue.NIL;
            LuaString s = s1.checkstring();
            int[] result = StringPattern.strMatch(s1, s2, s3);
            if (result == null) {
                L.stack[L.top++] = LuaValue.NIL;
                return 1;
            }
            int top = L.top;
            if (result.length == 0) {
                int[] findResult = StringPattern.strFind(s1, s2, s3, LuaValue.FALSE);
                if (findResult == null) {
                    L.stack[L.top++] = LuaValue.NIL;
                    return 1;
                }
                int start = findResult[0] - 1;
                int end = findResult[1];
                L.stack[top] = LuaString.newLstr(s.contents, start, end - start);
                L.top = top + 1;
                return 1;
            } else {
                int nCaps = result.length / 2;
                for (int i = 0; i < result.length; i += 2) {
                    int start = result[i] - 1;
                    int end = result[i + 1];
                    if (end < 0) {
                        L.stack[top + i / 2] = LuaInteger.valueOf(result[i]);
                    } else {
                        L.stack[top + i / 2] = LuaString.newLstr(s.contents, start, end - start);
                    }
                }
                L.top = top + nCaps;
                return nCaps;
            }
        }
    }

    // lstrlib.c: str_format
    static class FormatFn extends LuaFunction {
        @Override
        public Varargs call(Varargs args) {
            return StringFormat.strFormat(args);
        }

        // java-only: callOnStack  -  lstrlib.c:str_format  -  StackVarargs 零拷贝栈读
        //   （对齐 C 的 str_format 直接读 L->stack[func+1..]），消除 precallC 回退的
        //   VarargsPair/LuaValue[] 分配。strFormat 恒返回 1 值，直接压栈。
        @Override
        public int callOnStack(LuaThread L, int func, int narg) {
            if (!LuaFunction.LIB_CALLONSTACK) return -1;
            if (narg < 1) return -1;
            // arg1（格式串）必须是字符串  -  回退 Varargs 路径以得到类型错误消息
            LuaValue fmtVal = L.stack[func + 1];
            if (fmtVal == null || !(fmtVal instanceof LuaString)) return -1;
            // java-only: StackVarargs 直接从 L.stack 读取，零拷贝
            Varargs args = new Varargs.StackVarargs(L, func, narg);
            LuaValue result = StringFormat.strFormat(args);
            L.stack[L.top++] = result;
            return 1;
        }
    }

    // lstrlib.c: str_gsub
    static class GsubFn extends LuaFunction {
        @Override
        public Varargs call(Varargs args) {
            return StringPattern.strGsub(ownerGlobals, args.arg(1), args.arg(2), args.arg(3), args.arg(4));
        }

        // java-only: callOnStack  -  lstrlib.c:str_gsub  -  直接从栈读 4 个独立 LuaValue 参数
        //   （对齐 C 的 str_gsub 从栈读参），消除 precallC 回退的多次 VarargsPair 分配。
        //   结果恒为 2 值（替换后字符串 + 替换次数），直接压栈。
        @Override
        public int callOnStack(LuaThread L, int func, int narg) {
            if (!LuaFunction.LIB_CALLONSTACK) return -1;
            if (narg < 1) return -1;
            LuaValue a1 = L.stack[func + 1];
            if (a1 == null) return -1;
            LuaValue a2 = narg >= 2 ? L.stack[func + 2] : LuaValue.NIL;
            LuaValue a3 = narg >= 3 ? L.stack[func + 3] : LuaValue.NIL;
            LuaValue a4 = narg >= 4 ? L.stack[func + 4] : LuaValue.NIL;
            if (a2 == null || a3 == null) return -1;
            Varargs result = StringPattern.strGsub(L.l_G, a1, a2, a3, a4);
            L.stack[L.top] = result.arg1();
            L.stack[L.top + 1] = result.arg(2);
            L.top += 2;
            return 2;
        }
    }

    // lstrlib.c: str_gmatch
    static class GmatchFn extends LuaFunction {
        @Override
        public Varargs call(Varargs args) {
            LuaString s = LuaErrors.checkStr(args, 1);
            LuaString p = LuaErrors.checkStr(args, 2);
            int slen = s.rawlen();
            // lstrlib.c: gmatch —— luaL_optinteger(L,3,1)：类型错报 number expected；
            // 64 位巨值 > slen+1 时迭代器为空
            int initPos = 1;
            if (args.narg() > 2 && !args.arg(3).isnil()) {
                LuaValue iv = args.arg(3);
                if (!iv.isnumber())
                    LuaErrors.argError(3, "number expected, got " + iv.typeName());
                long v = iv.checklong();
                if (v < 0) v = slen + v + 1;
                initPos = v > slen + 1 ? Integer.MAX_VALUE : (int) Math.max(v, 1);
            }
            if (initPos < 1) initPos = 1;
            // lstrlib.c: prepstate  -  创建一次 MatchState，后续 gmatch_aux 复用（对齐 C 的 GMatchState.ms）
            StringPattern.MatchState ms = new StringPattern.MatchState(s.contents, slen, p.contents, p.shrlen);
            GMatchState gm = new GMatchState(initPos, -1, slen, ms);
            LuaUserdata ud = LuaUserdata.userdataOf(gm, 0);
            return new GmatchAuxFn(ownerGlobals, s, p, ud);
        }

        // java-only: callOnStack  -  lstrlib.c:str_gmatch  -  直接从栈读参数，消除 VarargsPair 分配。
        //   快路径处理 string + string + (int|nil)；非 string 参数回退走通用转换/报错。
        //   结果恒为 1 值（迭代器函数），直接压栈。
        @Override
        public int callOnStack(LuaThread L, int func, int narg) {
            if (!LuaFunction.LIB_CALLONSTACK) return -1;
            if (narg < 2) return -1;
            LuaValue v1 = L.stack[func + 1];
            LuaValue v2 = L.stack[func + 2];
            if (v1 == null || !(v1 instanceof LuaString s)) return -1;
            if (v2 == null || !(v2 instanceof LuaString p)) return -1;
            int slen = s.rawlen();
            int initPos = 1;
            if (narg > 2) {
                LuaValue v3 = L.stack[func + 3];
                if (v3 == null) return -1;
                if (v3.isnil()) { /* initPos stays 1 */ } else if (v3.isinteger()) {
                    // lstrlib.c: gmatch —— 64 位 init：巨值 > slen+1 时空迭代
                    long v3l = v3.tolong();
                    initPos = v3l > slen + 1 ? Integer.MAX_VALUE : (int) v3l;
                } else return -1;  // 非整数，走通用路径 optint 转换/报错
            }
            if (initPos < 0) initPos = slen + initPos + 1;
            if (initPos < 1) initPos = 1;
            StringPattern.MatchState ms = new StringPattern.MatchState(s.contents, slen, p.contents, p.shrlen);
            GMatchState gm = new GMatchState(initPos, -1, slen, ms);
            LuaUserdata ud = LuaUserdata.userdataOf(gm, 0);
            L.stack[L.top++] = new GmatchAuxFn(L.l_G, s, p, ud);
            return 1;
        }
    }

    // lstrlib.c: GMatchState
    // java diff: 嵌入 MatchState（对齐 C 的 GMatchState.ms，C 在 gmatch() 中 prepstate 一次复用）
    static final class GMatchState {
        final int srclen;
        final StringPattern.MatchState ms;
        int src;
        int lastmatch;

        GMatchState(int src, int lastmatch, int srclen, StringPattern.MatchState ms) {
            this.src = src;
            this.lastmatch = lastmatch;
            this.srclen = srclen;
            this.ms = ms;
        }
    }

    // lstrlib.c: gmatch_aux
    // java diff: 复用 GMatchState.ms（对齐 C 的 gmatch_aux 复用 ms）
    static class GmatchAuxFn extends LuaCClosure {
        // lstrlib.c: C 闭包在 allgc 上每周期被 sweep 重新染白，上值每周期重标。
        // java diff: LuaFunction 须经 bindGlobals 登记进 allFunctions 才会被重新染白；
        //   不登记则首次标黑后永久为黑 -> upvalue 子图漏标被回收。凡覆写 gcRefs() 都必须登记。
        GmatchAuxFn(Globals g, LuaValue s, LuaValue pattern, LuaUserdata state) {
            super(3);
            upvalue[0] = s;
            upvalue[1] = pattern;
            upvalue[2] = state;
            bindGlobals(g);
        }

        @Override
        public Varargs call(Varargs a) {
            GMatchState gm = (GMatchState) upvalue[2].checkuserdata(GMatchState.class);
            LuaString ss = upvalue[0].checkstring();
            StringPattern.MatchState ms = gm.ms;
            while (gm.src <= gm.srclen + 1) {
                int[] findResult = StringPattern.gmatchNext(ms, gm.src, gm.lastmatch);
                if (findResult == null) return LuaValue.NIL;
                int start = findResult[0];
                int end = findResult[1];
                // end == gm.lastmatch 时 gmatchNext 内部已跳过（对齐 C 的 e != gm->lastmatch）
                gm.lastmatch = end;
                gm.src = (end < start) ? start + 1 : end + 1;
                if (findResult.length <= 2) {
                    return LuaString.newLstr(ss.contents, start - 1, end - start + 1);
                }
                int nCaps = (findResult.length - 2) / 2;
                LuaValue[] captures = new LuaValue[nCaps];
                for (int i = 0; i < nCaps; i++) {
                    int cs = findResult[2 + i * 2];
                    int ce = findResult[2 + i * 2 + 1];
                    if (ce < 0) {
                        captures[i] = LuaInteger.valueOf(cs);
                    } else {
                        captures[i] = LuaString.newLstr(ss.contents, cs - 1, ce - cs + 1);
                    }
                }
                return LuaValue.varargsOf(captures);
            }
            return LuaValue.NIL;
        }
    }

    // lstrlib.c: str_len
    static class LenFn extends LuaFunction {
        @Override
        public Varargs call(Varargs args) {
            // lauxlib.c luaL_checklstring 形态（bad argument #1 to 'len' ...）
            return LuaInteger.valueOf(LuaErrors.checkStr(args, 1).rawlen());
        }

        // java-only: callOnStack  -  lstrlib.c str_len  -  返回字符串长度
        @Override
        public int callOnStack(LuaThread L, int func, int narg) {
            if (narg < 1) return -1;
            LuaValue arg = L.stack[func + 1];
            if (arg == null || !arg.isstring()) return -1;
            L.stack[L.top++] = LuaInteger.valueOf(arg.rawlen());
            return 1;
        }
    }

    // lstrlib.c: str_lower
    static class LowerFn extends LuaFunction {
        @Override
        public Varargs call(Varargs args) {
            // lauxlib.c: luaL_checklstring —— 库入口形态，消息为
            //   "bad argument #1 to 'string.lower' (string expected, got X)"。
            //   值级 LuaErrors.checkStr(args, 1) 只产生裸 "string expected, got X"（缺 argerror 包装）。
            LuaString s = LuaErrors.checkStr(args, 1);
            byte[] b = new byte[s.rawlen()];
            // lstrlib.c: str_lower —— "C" locale 的 tolower（仅 ASCII）；Unicode 映射会让
            // 0xC0-0xDE 变小写、0xFF 长成 U+0178 截断成 'x'——与 C 分叉
            for (int i = 0; i < b.length; i++) { int c = s.luaByte(i); b[i] = (byte) (c >= 'A' && c <= 'Z' ? c + 32 : c); }
            return LuaString.newLstr(b, 0, b.length);
        }

        // java-only: callOnStack  -  lstrlib.c str_lower  -  返回小写字符串
        @Override
        public int callOnStack(LuaThread L, int func, int narg) {
            if (narg < 1) return -1;
            LuaValue arg = L.stack[func + 1];
            if (arg == null || !arg.isstring()) return -1;
            LuaString s = (LuaString) arg;
            byte[] b = new byte[s.rawlen()];
            // lstrlib.c: str_lower —— "C" locale 的 tolower（仅 ASCII）；Unicode 映射会让
            // 0xC0-0xDE 变小写、0xFF 长成 U+0178 截断成 'x'——与 C 分叉
            for (int i = 0; i < b.length; i++) { int c = s.luaByte(i); b[i] = (byte) (c >= 'A' && c <= 'Z' ? c + 32 : c); }
            L.stack[L.top++] = LuaString.newLstr(b, 0, b.length);
            return 1;
        }
    }

    // lstrlib.c: str_upper
    static class UpperFn extends LuaFunction {
        @Override
        public Varargs call(Varargs args) {
            // lauxlib.c: luaL_checklstring —— 同 LowerFn，须走库入口形态带 argerror 包装
            LuaString s = LuaErrors.checkStr(args, 1);
            byte[] b = new byte[s.rawlen()];
            // lstrlib.c: str_upper  -  "C" locale 的 toupper（仅 ASCII）
            for (int i = 0; i < b.length; i++) { int c = s.luaByte(i); b[i] = (byte) (c >= 'a' && c <= 'z' ? c - 32 : c); }
            return LuaString.newLstr(b, 0, b.length);
        }

        // java-only: callOnStack  -  lstrlib.c str_upper  -  返回大写字符串
        @Override
        public int callOnStack(LuaThread L, int func, int narg) {
            if (narg < 1) return -1;
            LuaValue arg = L.stack[func + 1];
            if (arg == null || !arg.isstring()) return -1;
            LuaString s = (LuaString) arg;
            byte[] b = new byte[s.rawlen()];
            // lstrlib.c: str_upper  -  "C" locale 的 toupper（仅 ASCII）
            for (int i = 0; i < b.length; i++) { int c = s.luaByte(i); b[i] = (byte) (c >= 'a' && c <= 'z' ? c - 32 : c); }
            L.stack[L.top++] = LuaString.newLstr(b, 0, b.length);
            return 1;
        }
    }

    // lstrlib.c: str_rep
    // java diff: callOnStack 支持 separator 参数（对齐 C 的 luaL_optlstring(L,3,"",...)）
    // java-only: -Dluajvm.repopt=true|false（默认开） -  A/B 测试运行时开关
    static class RepFn extends LuaFunction {
        // java-only: rep 快路径优化的运行时开关（默认开）
        private static final boolean REP_OPT =
                Boolean.parseBoolean(System.getProperty("luajvm.repopt", "true"));
        // java-only: 无分隔符情形的倍增策略  -  O(log n) 次 arraycopy
        // 而非 O(n)。-Dluajvm.repdouble=false 禁用（A/B 测试）
        private static final boolean REP_DOUBLE =
                Boolean.parseBoolean(System.getProperty("luajvm.repdouble", "true"));

        @Override
        public Varargs call(Varargs args) {
            LuaString s = LuaErrors.checkStr(args, 1);
            long n = LuaErrors.checkLong(args, 2);
            // lstrlib.c:  -  sep 默认 ""（空串）；nil -> null（视为空）
            LuaString sep = args.arg(3).isnil() ? null : LuaErrors.checkStr(args, 3);

            int slen = s.rawlen();
            int seplen = sep != null ? sep.rawlen() : 0;
            // lstrlib.c:  -  C 加 (len | lsep) == 0：两空串直接返回空串
            if (n <= 0 || (slen | seplen) == 0) return LuaString.newStr("");

            // lstrlib.c:  -  溢出检查（C: len > MAX_SIZE-lsep || (len+lsep) > MAX_SIZE/n）
            // java diff: C 用 MAX_SIZE（~(size_t)0>>1）；Java 用 Integer.MAX_VALUE（最大数组大小）
            // java diff: 简单比较（对齐 C），不用 Math.multiplyExact+try/catch 以免快路径异常开销
            if (slen > Integer.MAX_VALUE - seplen ||
                    ((long) slen + seplen) > Integer.MAX_VALUE / n) {
                LuaErrors.error("resulting string too large");
                return LuaValue.NIL;
            }
            // lstrlib.c:  -  totallen = n*(len+lsep) - lsep
            long totalL = n * ((long) slen + seplen) - seplen;
            if (totalL > Integer.MAX_VALUE || totalL < 0) {
                LuaErrors.error("resulting string too large");
                return LuaValue.NIL;
            }

            int total = (int) totalL;
            byte[] b = new byte[total];
            int nInt = (int) n;  // safe: overflow check guarantees n <= MAX_VALUE when slen+seplen > 0
            // java-only: 无分隔符情形用倍增策略  -  O(log n) 次 arraycopy
            // 对比 C 的 O(n) 循环。同一数组内自拷贝安全（memmove 语义）
            if (REP_DOUBLE && seplen == 0) {
                System.arraycopy(s.contents, 0, b, 0, slen);
                int written = slen;
                int remaining = total - slen;
                while (remaining > 0) {
                    int copySize = written < remaining ? written : remaining;
                    System.arraycopy(b, 0, b, written, copySize);
                    written += copySize;
                    remaining -= copySize;
                }
            } else {
                // lstrlib.c:  -  前 n-1 次拷贝（后跟分隔符）
                int pos = 0;
                for (int i = 1; i < nInt; i++) {
                    System.arraycopy(s.contents, 0, b, pos, slen);
                    pos += slen;
                    if (seplen > 0) {  // lstrlib.c:  -  empty memcpy is not that cheap
                        System.arraycopy(sep.contents, 0, b, pos, seplen);
                        pos += seplen;
                    }
                }
                // lstrlib.c:  -  last copy without separator
                System.arraycopy(s.contents, 0, b, pos, slen);
            }
            return LuaString.newLstr(b, 0, total);
        }

        // java-only: callOnStack  -  lstrlib.c str_rep  -  基于栈的快路径
        // 全部参数在栈上且类型匹配快路径时避免 Varargs 分配；
        // 支持 separator 参数（对齐 C 的 luaL_optlstring(L,3,"")）
        @Override
        public int callOnStack(LuaThread L, int func, int narg) {
            if (!REP_OPT) return -1;
            if (narg < 2) return -1;
            LuaValue sv = L.stack[func + 1];
            LuaValue nv = L.stack[func + 2];
            if (sv == null || !sv.isstring()) return -1;
            if (nv == null) return -1;
            // 对齐 C luaL_checkinteger：接受整数或整数值浮点（如 5.0）
            long n;
            if (nv.isinteger()) {
                n = ((LuaInteger) nv).v;
            } else if (nv instanceof LuaFloat f
                    && !Double.isInfinite(f.v) && !Double.isNaN(f.v)
                    && f.v == Math.floor(f.v) && f.v == (long) f.v) {
                n = (long) f.v;
            } else {
                return -1;
            }
            // lstrlib.c:  -  sep 默认 ""（空串）；nil/缺失 -> null（视为空）
            LuaString sep = null;
            if (narg >= 3) {
                LuaValue sepv = L.stack[func + 3];
                if (sepv != null && !sepv.isnil()) {
                    if (!sepv.isstring()) return -1;  // fall back for __tostring metamethod
                    sep = (LuaString) sepv;
                }
            }
            LuaString s = (LuaString) sv;
            if (n <= 0) {
                L.stack[L.top++] = LuaString.newStr("");
                return 1;
            }
            int slen = s.rawlen();
            int seplen = sep != null ? sep.rawlen() : 0;
            // lstrlib.c:  -  溢出检查；回退到 call 以便 luaL_error 处理
            if (slen > Integer.MAX_VALUE - seplen ||
                    ((long) slen + seplen) > Integer.MAX_VALUE / n) return -1;
            // lstrlib.c:  -  totallen = n*(len+lsep) - lsep
            long totalL = n * ((long) slen + seplen) - seplen;
            if (totalL > Integer.MAX_VALUE || totalL < 0) return -1;
            if (slen == 0 && seplen == 0) {
                L.stack[L.top++] = LuaString.newStr("");
                return 1;
            }
            int total = (int) totalL;
            byte[] b = new byte[total];
            int nInt = (int) n;
            // java-only: 无分隔符情形用倍增策略  -  O(log n) 次 arraycopy
            if (REP_DOUBLE && seplen == 0) {
                System.arraycopy(s.contents, 0, b, 0, slen);
                int written = slen;
                int remaining = total - slen;
                while (remaining > 0) {
                    int copySize = written < remaining ? written : remaining;
                    System.arraycopy(b, 0, b, written, copySize);
                    written += copySize;
                    remaining -= copySize;
                }
            } else {
                // lstrlib.c:  -  前 n-1 次拷贝（带分隔符）
                int pos = 0;
                for (int i = 1; i < nInt; i++) {
                    System.arraycopy(s.contents, 0, b, pos, slen);
                    pos += slen;
                    if (seplen > 0) {
                        System.arraycopy(sep.contents, 0, b, pos, seplen);
                        pos += seplen;
                    }
                }
                // lstrlib.c:  -  last copy without separator
                System.arraycopy(s.contents, 0, b, pos, slen);
            }
            L.stack[L.top++] = LuaString.newLstr(b, 0, total);
            return 1;
        }
    }

    // lstrlib.c: str_reverse
    static class ReverseFn extends LuaFunction {
        @Override
        public Varargs call(Varargs args) {
            LuaValue arg = args.arg1();
            LuaString s = LuaErrors.checkStr(args, 1);
            int l = s.rawlen();
            byte[] b = new byte[l];
            for (int i = 0; i < l; i++) b[i] = s.contents[l - 1 - i];
            return LuaString.newLstr(b, 0, b.length);
        }

        // java-only: callOnStack  -  lstrlib.c str_reverse  -  返回反转字符串
        @Override
        public int callOnStack(LuaThread L, int func, int narg) {
            if (narg < 1) return -1;
            LuaValue arg = L.stack[func + 1];
            if (arg == null || !arg.isstring()) return -1;
            LuaString s = (LuaString) arg;
            int l = s.rawlen();
            byte[] b = new byte[l];
            for (int i = 0; i < l; i++) b[i] = s.contents[l - 1 - i];
            L.stack[L.top++] = LuaString.newLstr(b, 0, b.length);
            return 1;
        }
    }

    // lstrlib.c: str_sub
    static class SubFn extends LuaFunction {
        @Override
        public Varargs call(Varargs args) {
            LuaString s = LuaErrors.checkStr(args, 1);
            int l = s.rawlen();

            // 下标用 64 位保住巨值（对齐 C lua_Integer），再对串长做边界判定后收窄为 int。
            long startL = posrelat(LuaErrors.checkLong(args, 2), l);
            long endL = posrelat(LuaErrors.optLong(args, 3, -1), l);
            if (startL < 1) startL = 1;
            if (endL > l) endL = l;
            if (startL > endL) return LuaString.newStr("");
            int start = (int) startL, end = (int) endL;   // 已夹到 [1,l]，收窄安全
            return LuaString.newLstr(s.contents, start - 1, end - start + 1);
        }

        // java-only: callOnStack  -  lstrlib.c str_sub  -  返回子字符串
        @Override
        public int callOnStack(LuaThread L, int func, int narg) {
            if (narg < 2) return -1;
            LuaValue sv = L.stack[func + 1];
            LuaValue startv = L.stack[func + 2];
            if (sv == null || !sv.isstring()) return -1;
            if (startv == null || !startv.isinteger()) return -1;
            LuaString s = (LuaString) sv;
            int l = s.rawlen();
            // 下标用 64 位保住巨值（对齐 C lua_Integer），避免 (int) 截断把
            // s:sub(2^32+1) 折成 1。夹到 [1,l] 后再收窄。
            long startL = posrelat(startv.tolong(), l);
            long endL;
            if (narg >= 3) {
                LuaValue endv = L.stack[func + 3];
                if (endv == null || !endv.isinteger()) return -1;
                endL = posrelat(endv.tolong(), l);
            } else {
                endL = posrelat(-1L, l);
            }
            if (startL < 1) startL = 1;
            if (endL > l) endL = l;
            // [收窄前必须先判 start>end]startL 只夹了下界，巨值起点仍可 > l；若先 (int)
            //   收窄，2^32+k 会截断成垃圾值使 start>end 判定失效、newLstr 收到越界 offset。
            //   在 long 上判空区间：此后 startL in [1,?]、endL in [?,l] 且 startL<=endL => 皆在 [1,l]，收窄安全。
            if (startL > endL) {
                L.stack[L.top] = LuaString.newStr("");
                L.top++;
                return 1;
            }
            int start = (int) startL, end = (int) endL;   // startL<=endL 且已夹到 [1,l]，收窄安全
            L.stack[L.top] = LuaString.newLstr(s.contents, start - 1, end - start + 1);
            L.top++;
            return 1;
        }
    }

    // lstrlib.c: str_toutf8
    static class ToUtf8Fn extends LuaFunction {
        @Override
        public Varargs call(Varargs args) {
            LuaValue arg = args.arg1();
            LuaString s = LuaErrors.checkStr(args, 1);
            return LuaString.newStr(s.toJavaString());
        }
    }

    // lstrlib.c: str_pack
    static class PackFn extends LuaFunction {
        @Override
        public Varargs call(Varargs args) {
            return StringFormat.strPack(args);
        }
    }

    // lstrlib.c: str_packsize
    static class PackSizeFn extends LuaFunction {
        @Override
        public Varargs call(Varargs args) {
            return StringFormat.strPackSize(args);
        }
    }

    // lstrlib.c: str_unpack
    static class UnpackFn extends LuaFunction {
        @Override
        public Varargs call(Varargs args) {
            return StringFormat.strUnpack(args);
        }
    }

    // lstrlib.c: arith  -  算术的字符串元方法
    // java diff: C 用 lua_arith；Java 用 LuaArith.apply
    static class ArithFn extends LuaFunction {
        private final BinaryOp op;
        private final LuaString mtkey; // lstrlib.c: mtname (e.g. "__add")
        private final String opname;   // lstrlib.c: mtname + 2 (e.g. "add")

        ArithFn(BinaryOp op) {
            this.op = op;
            this.mtkey = switch (op) {
                case ADD -> LuaValue.ADD;
                case SUB -> LuaValue.SUB;
                case MUL -> LuaValue.MUL;
                case DIV -> LuaValue.DIV;
                case MOD -> LuaValue.MOD;
                case POW -> LuaValue.POW;
                case IDIV -> LuaValue.IDIV;
                default -> LuaString.newStr(op.name().toLowerCase());
            };
            // lstrlib.c: trymt —— opname = mtname+2（"__sub"→"sub"）
            this.opname = switch (op) {
                case ADD -> "add";
                case SUB -> "sub";
                case MUL -> "mul";
                case DIV -> "div";
                case MOD -> "mod";
                case POW -> "pow";
                case IDIV -> "idiv";
                default -> op.name().toLowerCase();
            };
        }

        @Override
        public Varargs call(Varargs args) {
            LuaValue a = args.arg1(), b = args.arg(2);
            // lstrlib.c:  -  if (tonum(L, 1) && tonum(L, 2))
            LuaValue na = tonum(a), nb = tonum(b);
            if (!na.isnil() && !nb.isnil()) {
                // lstrlib.c:  -  lua_arith(L, op)
                LuaValue result = LuaArith.apply(op, na, nb);
                if (result != null) return result;
            }
            // lstrlib.c:  -  trymt(L, mtname, mtname + 2)
            return trymt(a, b, mtkey, opname);
        }
    }

    // lstrlib.c: arith_unm  -  一元减的字符串元方法
    // java diff: unm 无第二操作数，故 trymt 不适用
    static class ArithUnmFn extends LuaFunction {
        @Override
        public Varargs call(Varargs args) {
            LuaValue v = args.arg1();
            LuaValue nv = tonum(v);
            if (!nv.isnil()) {
                LuaValue result = LuaArith.apply(UnaryOp.UNM, nv);
                if (result != null) return result;
            }
            // lstrlib.c: trymt —— mtname+2（"__unm"→"unm"）
            LuaErrors.runErrorWithInfo("attempt to unm a '" + v.typeName() + "'");
            return LuaValue.NIL;
        }
    }


}
