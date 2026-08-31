// java-only: 多返回值容器，C用栈传递多返回值
package org.luajvm.core;

public abstract class Varargs {
    public static Varargs of(LuaValue[] v) {
        return switch (v.length) {
            case 0 -> LuaValue.NONE;
            case 1 -> v[0];
            default -> new VarargsArray(v, LuaValue.NONE);
        };
    }

    public static Varargs of(LuaValue a, Varargs b) {
        return b.narg() == 0 ? a : new VarargsPair(a, b);
    }

    public static Varargs of(LuaValue a, LuaValue b) {
        return new VarargsPair(a, b);
    }

    public static Varargs of(LuaValue a, LuaValue b, LuaValue c) {
        return new VarargsPair(a, new VarargsPair(b, c));
    }

    public abstract LuaValue arg(int i);

    public abstract int narg();

    public abstract LuaValue arg1();

    public Varargs subargs(int s) {
        if (s <= 1) return this;
        int n = narg();
        if (s > n) return LuaValue.NONE;
        int len = n - s + 1;
        LuaValue[] arr = new LuaValue[len];
        for (int i = 0; i < len; i++) arr[i] = arg(s + i);
        return of(arr);
    }


    // java-only: 从 arg(1) 起复制 n 个值到 dest 的 off 偏移处
    public void copyTo(LuaValue[] dest, int off, int n) {
        for (int i = 0; i < n; i++) {
            dest[off + i] = arg(i + 1);
        }
    }


    // check
    public int checkint(int i) {
        LuaValue v = arg(i);
        // 只对[真数值类型]直取：数字字符串 isnumber() 亦为真，但 LuaString 不覆写
        // checkint，直取会落到 typeerror（math.floor("7") 报错，C 返回 7）。
        // 收窄为 isNumberTag() 使数字字符串走下方 tonumber() 分支，对齐 C 的隐式强转。
        if (v.isNumberTag()) return v.checkint();
        if (v.isstring()) {
            LuaValue n = v.tonumber();
            if (!n.isnil()) return n.checkint();
        }
        LuaErrors.typeError(i, this, "number");
        return 0;
    }

    public long checklong(int i) {
        LuaValue v = arg(i);
        // 同 checkint：数字字符串须走下面的 tonumber() 分支（见其注释）。
        if (v.isNumberTag()) return v.checklong();
        if (v.isstring()) {
            LuaValue n = v.tonumber();
            if (!n.isnil()) return n.checklong();
        }
        LuaErrors.typeError(i, this, "number");
        return 0;
    }

    public double checkdouble(int i) {
        LuaValue v = arg(i);
        // 同 checkint：数字字符串须走下面的 tonumber() 分支（见其注释）。
        if (v.isNumberTag()) return v.checkdouble();
        if (v.isstring()) {
            LuaValue n = v.tonumber();
            if (!n.isnil()) return n.checkdouble();
        }
        LuaErrors.typeError(i, this, "number");
        return 0;
    }

    public LuaString checkstring(int i) {
        LuaValue v = arg(i);
        if (v instanceof LuaString s) return s;
        if (v instanceof LuaNumber) return v.strValue();
        LuaErrors.typeError(i, this, "string");
        return null;
    }

    public LuaTable checktable(int i) {
        LuaValue v = arg(i);
        if (v instanceof LuaTable t) return t;
        LuaErrors.typeError(i, this, "table");
        return null;
    }

    public LuaValue checkNotNil(int i) {
        LuaValue v = arg(i);
        if (v.isnil()) LuaErrors.argError(i, "value expected");
        return v;
    }

    public LuaFunction checkfunction() {
        return arg1().checkfunction();
    }

    public LuaFunction checkfunction(int i) {
        LuaValue v = arg(i);
        if (v instanceof LuaFunction f) return f;
        LuaErrors.typeError(i, this, "function");
        return null;
    }

    public LuaValue checkvalue(int i) {
        if (narg() < i) LuaErrors.argError(i, "value expected");
        return arg(i);
    }

    public LuaThread checkthread(int i) {
        LuaValue v = arg(i);
        if (v instanceof LuaThread t) return t;
        LuaErrors.typeError(i, this, "thread");
        return null;
    }

    public Object checkuserdata() {
        return arg1().checkuserdata();
    }

    public String checkJavaString(int i) {
        return arg(i).checkJavaString();
    }

    public String optJavaString(int i, String d) {
        return arg(i).isnil() ? d : arg(i).toJavaString();
    }

    public LuaValue optvalue(int i, LuaValue d) {
        return arg(i).isnil() ? d : arg(i);
    }

    public boolean argcheck(boolean c, int i, String msg) {
        if (!c) LuaErrors.argError(i, msg);
        return c;
    }

    // opt
    public boolean optboolean(int i, boolean d) {
        return arg(i).optboolean(d);
    }

    public double optdouble(int i, double d) {
        return arg(i).optdouble(d);
    }

    // luaL_opt
    public int optint(int i, int d) {
        LuaValue v = arg(i);
        if (v.isnil()) return d;
        if (v.isnumber()) return v.checkint();
        if (v.isstring()) {
            LuaValue n = v.tonumber();
            if (!n.isnil()) return n.checkint();
        }
        LuaErrors.typeError(i, this, "number");
        return d;
    }

    public long optlong(int i, long d) {
        LuaValue v = arg(i);
        if (v.isnil()) return d;
        if (v.isnumber()) return v.checklong();
        if (v.isstring()) {
            LuaValue n = v.tonumber();
            if (!n.isnil()) return n.checklong();
        }
        LuaErrors.typeError(i, this, "number");
        return d;
    }


    // is
    public boolean isstring(int i) {
        return arg(i).isstring();
    }

    public boolean isnil(int i) {
        return arg(i).isnil();
    }

    public boolean isnumber(int i) {
        return arg(i).isnumber();
    }

    public boolean istable(int i) {
        return arg(i).istable();
    }

    public boolean isfunction(int i) {
        return arg(i).isfunction();
    }

    public boolean isuserdata(int i) {
        return arg(i).isuserdata();
    }

    public String toJavaString(int i) {
        return arg(i).toJavaString();
    }

    public static final class VarargsPair extends Varargs {
        private final LuaValue a;
        private final Varargs b;

        public VarargsPair(LuaValue a, Varargs b) {
            this.a = a;
            this.b = b;
        }

        // java-only: 非递归 arg 避免深层链的 StackOverflowError
        @Override
        public LuaValue arg(int i) {
            Varargs cur = this;
            while (cur instanceof VarargsPair vp) {
                if (i == 1) return vp.a;
                i--;
                cur = vp.b;
            }
            return cur.arg(i);
        }

        // java-only: 非递归 narg 避免 StackOverflowError
        @Override
        public int narg() {
            int count = 1;
            Varargs cur = b;
            while (cur instanceof VarargsPair vp) {
                count++;
                cur = vp.b;
            }
            return count + cur.narg();
        }

        public LuaValue arg1() {
            return a;
        }

        // java-only: 非递归 copyTo 避免 StackOverflowError
        @Override
        public void copyTo(LuaValue[] dest, int off, int n) {
            Varargs cur = this;
            int idx = off;
            int remaining = n;
            while (cur instanceof VarargsPair vp && remaining > 0) {
                dest[idx++] = vp.a;
                cur = vp.b;
                remaining--;
            }
            if (remaining > 0 && cur.narg() > 0) {
                cur.copyTo(dest, idx, remaining);
            }
            for (int i = 0; i < remaining && cur.narg() == 0; i++) {
                dest[idx + i] = LuaValue.NIL;
            }
        }
    }

    public static final class VarargsArray extends Varargs {
        private final LuaValue[] v;
        private final Varargs r;

        public VarargsArray(LuaValue[] v, Varargs r) {
            this.v = v;
            this.r = r;
        }

        public LuaValue arg(int i) {
            if (i >= 1 && i <= v.length) {
                LuaValue val = v[i - 1];
                return val != null ? val : LuaValue.NIL;
            }
            return r.arg(i - v.length);
        }

        public int narg() {
            return v.length + r.narg();
        }

        public LuaValue arg1() {
            return v.length > 0 ? v[0] : r.arg1();
        }
    }

    public static final class VarargsSlice extends Varargs {
        private final LuaValue[] v;
        private final int off, len;
        private final Varargs more;

        public VarargsSlice(LuaValue[] v, int off, int len, Varargs m) {
            this.v = v;
            this.off = off;
            this.len = len;
            this.more = m;
        }

        public LuaValue arg(int i) {
            return i >= 1 && i <= len ? v[off + i - 1] : more.arg(i - len);
        }

        public int narg() {
            return len + more.narg();
        }

        public LuaValue arg1() {
            return len > 0 ? v[off] : more.arg1();
        }
    }

    // java-only: StackVarargs  -  直接从 LuaThread.stack 零拷贝读参数（对齐 C 读 L->stack[func+1..]）。
    //   供库的 callOnStack override（FormatFn 等）使用，避免 VarargsPair/VarargsArray 分配；
    //   生命周期: 不得超出调用帧存活（poscall 后栈槽可能被覆写），仅在 callOnStack 内同步使用安全。
    public static final class StackVarargs extends Varargs {
        private final LuaValue[] stack;
        private final int base;  // func+1
        private final int n;

        public StackVarargs(LuaThread L, int func, int narg) {
            this.stack = L.stack;
            this.base = func + 1;
            this.n = narg;
        }

        @Override
        public LuaValue arg(int i) {
            if (i >= 1 && i <= n) {
                LuaValue v = stack[base + i - 1];
                return v != null ? v : LuaValue.NIL;
            }
            return LuaValue.NIL;
        }

        @Override
        public int narg() {
            return n;
        }

        @Override
        public LuaValue arg1() {
            if (n >= 1) {
                LuaValue v = stack[base];
                return v != null ? v : LuaValue.NIL;
            }
            return LuaValue.NIL;
        }
    }
}
