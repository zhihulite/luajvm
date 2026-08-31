// ref: lmathlib.c
// diff: java.lang.Math 代替 C 数学库；frexp/ldexp 用 Double.doubleToLongBits 位操作；xoshiro256** 代替 rand()；randomseed 双种子；pushnumint 实现 lua_numbertointeger 语义
package org.luajvm.lib;

import org.luajvm.core.LuaBoolean;
import org.luajvm.core.LuaErrors;
import org.luajvm.core.LuaFloat;
import org.luajvm.core.LuaFunction;
import org.luajvm.core.LuaInteger;
import org.luajvm.core.LuaNumber;
import org.luajvm.core.LuaString;
import org.luajvm.core.LuaTable;
import org.luajvm.core.LuaThread;
import org.luajvm.core.LuaValue;
import org.luajvm.core.Varargs;
import org.luajvm.vm.LuaCompare;

public class MathLib extends LuaFunction {

    public MathLib() {
    }

    // java-only: pure numeric less-than (no metamethods), for MaxFn/MinFn callOnStack
    private static boolean numlt(LuaValue a, LuaValue b) {
        if (a instanceof LuaInteger ai && b instanceof LuaInteger bi) return ai.v < bi.v;
        return a.todouble() < b.todouble();
    }

    // lmathlib.c: pushnumint
    private static LuaValue pushnumint(double d) {
        if (Double.isNaN(d) || Double.isInfinite(d) || d != Math.floor(d))
            return LuaFloat.valueOf(d);
        if (d < (double) Long.MIN_VALUE || d >= -(double) Long.MIN_VALUE)
            return LuaFloat.valueOf(d);
        return LuaInteger.valueOf((long) d);
    }

    // lmathlib.c: luaopen_math
    @Override
    public Varargs call(Varargs args) {
        LuaValue modname = args.arg1();
        LuaValue env = args.arg(2);
        LuaTable math = new LuaTable(0, 30);
        math.set("abs", new AbsFn());
        math.set("acos", new AcosFn());
        math.set("asin", new AsinFn());
        math.set("atan", new atan());
        math.set("ceil", new CeilFn());
        math.set("cos", new CosFn());
        math.set("deg", new DegFn());
        math.set("exp", new ExpFn());
        math.set("floor", new FloorFn());
        math.set("fmod", new FmodFn());
        math.set("frexp", new FrexpFn());
        math.set("huge", LuaFloat.POSINF);
        math.set("log", new LogFn());
        math.set("tointeger", new ToIntegerFn());
        math.set("type", new MathTypeFn());
        math.set("ult", new UltFn());
        math.set("ldexp", new LdexpFn());
        math.set("max", new MaxFn());
        math.set("maxinteger", LuaInteger.valueOf(Long.MAX_VALUE));
        math.set("min", new MinFn());
        math.set("mininteger", LuaInteger.valueOf(Long.MIN_VALUE));
        math.set("modf", new ModfFn());

        math.set("pi", LuaFloat.valueOf(Math.PI));
        RandomFn r;
        math.set("random", r = new RandomFn());
        math.set("randomseed", new RandomSeedFn(r));
        math.set("rad", new RadFn());
        math.set("sin", new SinFn());
        math.set("sqrt", new SqrtFn());
        math.set("tan", new TanFn());
        env.set("math", math);
        if (!env.get("package").isnil()) env.get("package").get("loaded").set("math", math);
        return math;
    }

    // UnaryOp
    abstract protected static class UnaryOp extends LuaFunction {
        @Override
        public Varargs call(Varargs args) {

            return valueOf(call(args.checkdouble(1)));
        }

        // java-only: callOnStack  -  lmathlib.c math_unary  -  一元数学运算（abs/ceil/sqrt/log/exp 等）
        @Override
        public int callOnStack(LuaThread L, int func, int narg) {
            if (narg < 1) return -1;
            LuaValue v = L.stack[func + 1];
            // 仅真数值类型走快路径：isnumber() 对数字字符串亦为 true，但 LuaString
            // 不覆写 todouble()（继承默认返回 0）⇒ math.sqrt("16") 会算成 0。返回 -1 让
            // 通用 call(args) 路径的 checkdouble 正确做字符串→数字强转（对齐 C 的 luaL_checknumber）。
            if (v == null || !v.isNumberTag()) return -1;
            double d = v.todouble();
            L.stack[L.top++] = LuaFloat.valueOf(call(d));
            return 1;
        }

        abstract protected double call(double d);
    }

    // BinaryOp
    abstract protected static class BinaryOp extends LuaFunction {
        @Override
        public Varargs call(Varargs args) {

            return valueOf(call(args.checkdouble(1), args.checkdouble(2)));
        }

        // java-only: callOnStack  -  lmathlib.c math_binary  -  二元数学运算（fmod 等）
        @Override
        public int callOnStack(LuaThread L, int func, int narg) {
            if (narg < 2) return -1;
            LuaValue v1 = L.stack[func + 1];
            LuaValue v2 = L.stack[func + 2];
            // 同 UnaryOp：仅真数值类型走快路径，数字字符串回落通用路径强转。
            if (v1 == null || !v1.isNumberTag() || v2 == null || !v2.isNumberTag()) return -1;
            double x = v1.todouble();
            double y = v2.todouble();
            L.stack[L.top++] = LuaFloat.valueOf(call(x, y));
            return 1;
        }

        abstract protected double call(double x, double y);
    }

    // lmathlib.c: math_abs
    static final class AbsFn extends LuaFunction {
        @Override
        public Varargs call(Varargs args) {
            LuaValue arg = args.arg1();
            if (arg.isinteger()) {
                long n = arg.tolong();
                if (n < 0) n = -n;
                return LuaInteger.valueOf(n);
            }
            return LuaFloat.valueOf(Math.abs(args.checkdouble(1)));
        }

        // java-only: callOnStack  -  lmathlib.c math_abs  -  绝对值
        @Override
        public int callOnStack(LuaThread L, int func, int narg) {
            if (narg < 1) return -1;
            LuaValue v = L.stack[func + 1];
            if (v == null) return -1;
            // 仅真数值类型走快路径（数字字符串回落通用路径由 checknumber 强转）
            if (v.isinteger()) {
                long n = ((LuaInteger) v).v;
                L.stack[L.top] = (n < 0) ? LuaInteger.valueOf(-n) : v;
            } else if (v instanceof LuaFloat f) {
                L.stack[L.top] = LuaFloat.valueOf(Math.abs(f.v));
            } else {
                return -1;
            }
            L.top++;
            return 1;
        }
    }

    // lmathlib.c: math_ceil
    static final class CeilFn extends LuaFunction {
        @Override
        public Varargs call(Varargs args) {
            LuaValue arg = args.arg1();
            if (arg.isinteger()) return arg;
            double d = Math.ceil(args.checkdouble(1));
            return pushnumint(d);
        }

        // java-only: callOnStack  -  lmathlib.c math_ceil  -  向上取整
        @Override
        public int callOnStack(LuaThread L, int func, int narg) {
            if (narg < 1) return -1;
            LuaValue v = L.stack[func + 1];
            if (v == null) return -1;
            if (v.isinteger()) {
                L.stack[L.top] = v;
            } else if (v instanceof LuaFloat f) {
                L.stack[L.top] = pushnumint(Math.ceil(f.v));
            } else {
                return -1;
            }
            L.top++;
            return 1;
        }

    }

    // lmathlib.c: math_acos
    static final class AcosFn extends UnaryOp {
        @Override
        protected double call(double d) {
            return Math.acos(d);
        }
    }

    // lmathlib.c: math_asin
    static final class AsinFn extends UnaryOp {
        @Override
        protected double call(double d) {
            return Math.asin(d);
        }
    }

    // lmathlib.c: math_atan
    static final class atan extends LuaFunction {
        @Override
        public Varargs call(Varargs args) {
            double y = args.checkdouble(1);
            double x = (args.narg() < 2 || args.isnil(2)) ? 1.0 : args.checkdouble(2);
            return valueOf(Math.atan2(y, x));
        }

        // java-only: callOnStack  -  lmathlib.c math_atan  -  反正切
        @Override
        public int callOnStack(LuaThread L, int func, int narg) {
            if (narg < 1) return -1;
            LuaValue v1 = L.stack[func + 1];
            if (v1 == null || !v1.isNumberTag()) return -1;
            double y = v1.todouble();
            double x = 1.0;
            if (narg >= 2) {
                LuaValue v2 = L.stack[func + 2];
                if (v2 == null) return -1;
                if (!v2.isnil()) {
                    if (!v2.isNumberTag()) return -1;
                    x = v2.todouble();
                }
            }
            L.stack[L.top] = valueOf(Math.atan2(y, x));
            L.top++;
            return 1;
        }
    }

    // lmathlib.c: math_cos
    static final class CosFn extends UnaryOp {
        @Override
        protected double call(double d) {
            return Math.cos(d);
        }
    }

    // lmathlib.c: math_deg
    static final class DegFn extends UnaryOp {
        @Override
        protected double call(double d) {
            return Math.toDegrees(d);
        }
    }

    // lmathlib.c: math_floor
    static final class FloorFn extends LuaFunction {
        @Override
        public Varargs call(Varargs args) {
            LuaValue arg = args.arg1();
            if (arg.isinteger()) return arg;
            double d = Math.floor(args.checkdouble(1));
            return pushnumint(d);
        }

        // java-only: callOnStack  -  lmathlib.c math_floor  -  向下取整
        @Override
        public int callOnStack(LuaThread L, int func, int narg) {
            if (narg < 1) return -1;
            LuaValue arg = L.stack[func + 1];
            if (arg == null) return -1;
            if (arg.isinteger()) {
                L.stack[L.top] = arg;
                L.top++;
                return 1;
            }
            // 仅真数值类型走快路径。isnumber() 对数字字符串也返回 true，但 LuaString
            // 不覆写 todouble()（继承默认 0），会把 math.floor("7") 算成 0（C 返回 7）。
            // 数字字符串返回 -1 回落通用 call(args) 路径，由 checkdouble 做隐式强转。
            if (!arg.isNumberTag()) return -1;
            double d = Math.floor(arg.todouble());
            L.stack[L.top] = pushnumint(d);
            L.top++;
            return 1;
        }
    }

    // lmathlib.c: math_rad
    static final class RadFn extends UnaryOp {
        @Override
        protected double call(double d) {
            return Math.toRadians(d);
        }
    }

    // lmathlib.c: math_sin
    static final class SinFn extends UnaryOp {
        @Override
        protected double call(double d) {
            return Math.sin(d);
        }
    }

    // lmathlib.c: math_sqrt
    static final class SqrtFn extends UnaryOp {
        @Override
        protected double call(double d) {
            return Math.sqrt(d);
        }
    }

    // lmathlib.c: math_tan
    static final class TanFn extends UnaryOp {
        @Override
        protected double call(double d) {
            return Math.tan(d);
        }
    }

    // lmathlib.c: math_exp
    static final class ExpFn extends UnaryOp {
        @Override
        protected double call(double d) {
            return Math.exp(d);
        }
    }

    // lmathlib.c: math_fmod
    static final class FmodFn extends LuaFunction {
        @Override
        public Varargs call(Varargs args) {
            LuaValue xv = args.arg1();
            LuaValue yv = args.arg(2);

            if (xv instanceof LuaInteger && yv instanceof LuaInteger) {
                long d = yv.tolong();
                if (d == 0) LuaErrors.argError(2, "zero");
                if (d == -1) return LuaInteger.valueOf(0);
                return valueOf(xv.tolong() % d);
            }
            return valueOf(args.checkdouble(1) % args.checkdouble(2));
        }
    }

    // lmathlib.c: math_ldexp
    static final class LdexpFn extends BinaryOp {
        @Override
        protected double call(double x, double y) {
            return x * Double.longBitsToDouble((((long) y) + 1023) << 52);
        }
    }

    // lmathlib.c: math_frexp
    static class FrexpFn extends LuaFunction {
        @Override
        public Varargs call(Varargs args) {
            double x = args.checkdouble(1);
            if (x == 0) return varargsOf(LuaValue.ZERO, LuaValue.ZERO);
            if (Double.isInfinite(x) || Double.isNaN(x))
                return varargsOf(valueOf(x), LuaValue.valueOf(-1));
            long bits = Double.doubleToLongBits(x);
            double m = ((bits & (~(-1L << 52))) + (1L << 52)) * ((bits >= 0) ? (.5 / (1L << 52)) : (-.5 / (1L << 52)));
            double e = (((int) (bits >> 52)) & 0x7ff) - 1022;
            return varargsOf(valueOf(m), valueOf(e));
        }
    }

    // lmathlib.c: math_max
    static class MaxFn extends LuaFunction {
        @Override
        public Varargs call(Varargs args) {
            LuaValue m = args.checkNotNil(1);
            for (int i = 2, n = args.narg(); i <= n; ++i) {
                LuaValue v = args.checkNotNil(i);
                if (LuaCompare.lessThan(m, v)) m = v;
            }
            return m;
        }

        // java-only: callOnStack  -  lmathlib.c math_max  -  返回最大值
        @Override
        public int callOnStack(LuaThread L, int func, int narg) {
            if (narg < 1) return -1;
            LuaValue vmax = L.stack[func + 1];
            // 仅真数值类型走快路径：数字字符串下 numlt 会按串比较（"9"<"3" 为 false），
            // 与 C 的强转语义分叉 ⇒ 回落通用路径。
            if (vmax == null || !vmax.isNumberTag()) return -1;
            for (int i = 2; i <= narg; i++) {
                LuaValue v = L.stack[func + i];
                if (v == null || !v.isNumberTag()) return -1;
                if (numlt(vmax, v)) vmax = v;
            }
            L.stack[L.top] = vmax;
            L.top++;
            return 1;
        }

    }

    // lmathlib.c: math_min
    static class MinFn extends LuaFunction {
        @Override
        public Varargs call(Varargs args) {
            LuaValue m = args.checkNotNil(1);
            for (int i = 2, n = args.narg(); i <= n; ++i) {
                LuaValue v = args.checkNotNil(i);
                if (LuaCompare.lessThan(v, m)) m = v;
            }
            return m;
        }

        // java-only: callOnStack  -  lmathlib.c math_min  -  返回最小值
        @Override
        public int callOnStack(LuaThread L, int func, int narg) {
            if (narg < 1) return -1;
            LuaValue vmin = L.stack[func + 1];
            // 同 MaxFn：仅真数值类型走快路径。
            if (vmin == null || !vmin.isNumberTag()) return -1;
            for (int i = 2; i <= narg; i++) {
                LuaValue v = L.stack[func + i];
                if (v == null || !v.isNumberTag()) return -1;
                if (numlt(v, vmin)) vmin = v;
            }
            L.stack[L.top] = vmin;
            L.top++;
            return 1;
        }

    }

    // lmathlib.c: math_modf
    static class ModfFn extends LuaFunction {
        @Override
        public Varargs call(Varargs args) {
            LuaValue n = args.arg1();

            if (n instanceof LuaInteger) return varargsOf(n, valueOf(0.0));
            double x = args.checkdouble(1);

            double intPart = (x > 0) ? Math.floor(x) : Math.ceil(x);

            double fracPart = x == intPart ? 0.0 : x - intPart;
            return varargsOf(valueOf(intPart), valueOf(fracPart));
        }

        // java-only: callOnStack  -  lmathlib.c math_modf  -  分解整数/小数部分
        @Override
        public int callOnStack(LuaThread L, int func, int narg) {
            if (narg < 1) return -1;
            LuaValue v = L.stack[func + 1];
            if (v == null) return -1;
            if (v.isinteger()) {
                // 整数输入：整数部分=自身，小数部分=0.0
                L.stack[L.top] = v;
                L.stack[L.top + 1] = LuaFloat.valueOf(0.0);
            } else if (v instanceof LuaFloat f) {
                double x = f.v;
                double intPart = (x > 0) ? Math.floor(x) : Math.ceil(x);
                double fracPart = (x == intPart) ? 0.0 : x - intPart;
                L.stack[L.top] = valueOf(intPart);
                L.stack[L.top + 1] = LuaFloat.valueOf(fracPart);
            } else {
                return -1;
            }
            L.top += 2;
            return 2;
        }
    }

    // lmathlib.c: math_random
    static class RandomFn extends LuaFunction {
        long[] s = new long[4];

        RandomFn() {
            setseed(System.nanoTime() ^ (Runtime.getRuntime().freeMemory() << 16), 0);
        }

        static long rotl(long x, int n) {
            return (x << n) | (x >>> (64 - n));
        }

        static int unsignedCmp(long a, long b) {
            return Long.compareUnsigned(a, b);
        }

        long nextrand() {
            long state0 = s[0];
            long state1 = s[1];
            long state2 = s[2] ^ state0;
            long state3 = s[3] ^ state1;
            long res = rotl(state1 * 5, 7) * 9;
            s[0] = state0 ^ state3;
            s[1] = state1 ^ state2;
            s[2] = state2 ^ (state1 << 17);
            s[3] = rotl(state3, 45);
            return res;
        }

        double I2d(long x) {
            long sx = x >>> 11;
            double res = (double) sx * 0x1.0p-53;
            if (sx < 0) res += 1.0;
            return res;
        }

        long project(long ran, long n) {
            long lim = n;
            for (int sh = 1; (lim & (lim + 1)) != 0; sh *= 2)
                lim |= (lim >>> sh);
            while (unsignedCmp((ran &= lim), n) > 0)
                ran = nextrand();
            return ran;
        }

        void setseed(long n1, long n2) {
            s[0] = n1;
            s[1] = 0xffL;
            s[2] = n2;
            s[3] = 0;
            for (int i = 0; i < 16; i++)
                nextrand();
        }

        @Override
        public Varargs call(Varargs args) {
            int nargs = args.narg();
            long rv = nextrand();
            if (nargs == 0) {
                return valueOf(I2d(rv));
            } else if (nargs == 1) {
                long up = LuaErrors.checkLong(args, 1);
                if (up == 0) return LuaInteger.valueOf(rv);
                if (up < 1) LuaErrors.argError(1, "interval is empty");
                long n = up - 1;
                long p = project(rv, n);
                return LuaInteger.valueOf(p + 1);
            } else if (nargs == 2) {
                long low = LuaErrors.checkLong(args, 1);
                long up = LuaErrors.checkLong(args, 2);
                if (low > up) LuaErrors.argError(1, "interval is empty");
                long n = up - low;
                long p = project(rv, n);
                return LuaInteger.valueOf(p + low);
            } else {
                LuaErrors.argError(3, "wrong number of arguments");
                return LuaValue.NIL;
            }
        }

        // java-only: callOnStack  -  lmathlib.c math_random  -  生成随机数
        @Override
        public int callOnStack(LuaThread L, int func, int narg) {
            long rv = nextrand();
            if (narg == 0) {
                L.stack[L.top] = LuaFloat.valueOf(I2d(rv));
                L.top++;
                return 1;
            }
            if (narg > 2) return -1;
            LuaValue a1 = L.stack[func + 1];
            if (a1 == null || !a1.isinteger()) return -1;
            long up = a1.tolong();
            if (narg == 1) {
                if (up == 0) {
                    L.stack[L.top] = LuaInteger.valueOf(rv);
                    L.top++;
                    return 1;
                }
                if (up < 1) return -1;
                long n = up - 1;
                long p = project(rv, n);
                L.stack[L.top] = LuaInteger.valueOf(p + 1);
                L.top++;
                return 1;
            }
            LuaValue a2 = L.stack[func + 2];
            if (a2 == null || !a2.isinteger()) return -1;
            long low = a1.tolong();
            up = a2.tolong();
            if (low > up) return -1;
            long n = up - low;
            long p = project(rv, n);
            L.stack[L.top] = LuaInteger.valueOf(p + low);
            L.top++;
            return 1;
        }
    }

    // lmathlib.c: math_randomseed
    static class RandomSeedFn extends LuaFunction {
        final RandomFn rand;

        RandomSeedFn(RandomFn rand) {
            this.rand = rand;
        }

        @Override
        public Varargs call(Varargs args) {
            long n1, n2;
            if (args.isnil(1)) {
                n1 = System.nanoTime() ^ (Runtime.getRuntime().freeMemory() << 16);
                n2 = rand.nextrand();
            } else {
                n1 = LuaErrors.checkLong(args, 1);
                n2 = args.arg(2).optlong(0);
            }
            rand.setseed(n1, n2);
            return LuaValue.varargsOf(LuaInteger.valueOf(n1), LuaInteger.valueOf(n2));
        }
    }


    // lmathlib.c: math_log
    static final class LogFn extends LuaFunction {
        @Override
        public Varargs call(Varargs args) {
            double x = args.checkdouble(1);
            if (args.isnil(2)) return LuaFloat.valueOf(Math.log(x));
            double base = args.checkdouble(2);
            return LuaFloat.valueOf(Math.log(x) / Math.log(base));
        }

        // java-only: callOnStack  -  lmathlib.c math_log  -  自然对数/指定底对数
        @Override
        public int callOnStack(LuaThread L, int func, int narg) {
            if (narg < 1) return -1;
            LuaValue v1 = L.stack[func + 1];
            if (v1 == null || !v1.isNumberTag()) return -1;
            double x = v1.todouble();
            if (narg >= 2) {
                LuaValue v2 = L.stack[func + 2];
                if (v2 != null && !v2.isnil()) {
                    if (!v2.isNumberTag()) return -1;
                    double base = v2.todouble();
                    L.stack[L.top] = LuaFloat.valueOf(Math.log(x) / Math.log(base));
                } else {
                    L.stack[L.top] = LuaFloat.valueOf(Math.log(x));
                }
            } else {
                L.stack[L.top] = LuaFloat.valueOf(Math.log(x));
            }
            L.top++;
            return 1;
        }
    }

    // lmathlib.c: math_tointeger
    static final class ToIntegerFn extends LuaFunction {
        @Override
        public Varargs call(Varargs args) {
            LuaValue v = args.arg1();
            if (v.isinteger()) return v;
            if (v.type() == LuaValue.TSTRING) {
                LuaNumber n = v.checkstring().scannumber();
                if (n != null) {
                    if (n.isinteger()) return n;
                    double d = n.todouble();
                    if (d == Math.floor(d) && !Double.isInfinite(d) && d >= (double) Long.MIN_VALUE && d < -(double) Long.MIN_VALUE)
                        return LuaInteger.valueOf((long) d);
                }
                return LuaValue.NIL;
            }
            if (v.isnumber()) {
                double d = v.todouble();
                if (d == Math.floor(d) && !Double.isInfinite(d) && d >= (double) Long.MIN_VALUE && d < -(double) Long.MIN_VALUE)
                    return LuaInteger.valueOf((long) d);
            }
            return LuaValue.NIL;
        }
    }

    // lmathlib.c: math_type
    static final class MathTypeFn extends LuaFunction {
        @Override
        public Varargs call(Varargs args) {
            LuaValue v = args.arg1();

            if (v.isinteger()) return LuaString.newStr("integer");
            if (v instanceof LuaFloat) return LuaString.newStr("float");
            return LuaValue.NIL;
        }
    }

    // lmathlib.c: math_ult
    static final class UltFn extends LuaFunction {
        @Override
        public Varargs call(Varargs args) {
            long m = LuaErrors.checkLong(args, 1);
            long n = LuaErrors.checkLong(args, 2);
            return LuaBoolean.valueOf(Long.compareUnsigned(m, n) < 0);
        }
    }

}
