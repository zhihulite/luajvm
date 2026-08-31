// java-only: Java 方法反射绑定
package org.luajvm.bind;

import org.luajvm.core.LuaErrors;
import org.luajvm.core.LuaFunction;
import org.luajvm.core.LuaValue;
import org.luajvm.core.Varargs;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;

/**
 * 包装 Java 方法的 LuaValue。
 *
 * <p>Java 特有：C 无对应。参数转换/打分/错误协议见基类 {@link ExecutableBinding}。
 */
class JavaMethod extends ExecutableBinding {

    final Method method;
    private final Class<?> returnType;

    private JavaMethod(Method m) {
        super(m);
        this.method = m;
        this.returnType = m.getReturnType();
    }

    /**
     * java-only：按 ClassLoader 可回收性分层的缓存（语义与理由见
     * {@link ExecutableBinding#EXECUTABLES}）。自定义 loader 的类改由
     * {@code JavaClass.methodMap}（实例字段，按 {@code Globals} 缓存）承载 ⇒
     * 每类每状态仍仅构造一次，丢弃 {@code Globals} 即全部释放。
     */
    static JavaMethod forMethod(Method m) {
        boolean cache = BindCaches.cacheable(m.getDeclaringClass());
        if (cache) {
            JavaMethod hit = (JavaMethod) EXECUTABLES.get(m);
            if (hit != null) return hit;
        }
        JavaMethod j;
        try {
            j = new JavaMethod(m);
        } catch (NoClassDefFoundError ignored) {
            return null;   // 签名引用了缺失的类；调用点会跳过
        }
        if (cache) EXECUTABLES.put(m, j);
        return j;
    }

    /** 判据与实现见 {@link BindCaches#cacheable}（三处共享的公共设施）。 */
    static boolean cacheable(Class<?> c) {
        return BindCaches.cacheable(c);
    }

    static LuaFunction forMethods(JavaMethod[] m) {
        return new Overload(m);
    }

    @Override
    public Varargs call(Varargs args) {
        // 1-arg (instance only, no method args) -> 避开 subargs(2) 分配。
        //   subargs(2) 对 narg()>1 会 new LuaValue[]，NONE 是单例常量零分配。
        //   Android ART 无标量替换，收益大于 JVM。
        if (args.narg() == 1) return invokeJavaMethod(args.arg1(), LuaValue.NONE);
        return invokeJavaMethod(args.arg1(), args.subargs(2));
    }

    public Varargs invokeJavaMethod(LuaValue obj, Varargs args) {
        if (params.varargs == null && params.fixedargs.length != args.narg())
            throw new IllegalArgumentException(method.toString());
        Object instance = obj.checkuserdata();
        // convertArgs 的数组**不含** receiver（Method.invoke 单独收 instance），
        //   0 参方法复用 EMPTY_ARGS 常量。
        Object[] a = MemberSupport.convertArgs(params, args);
        try {
            if (returnType == Void.TYPE) {
                InvokeSupport.invokeVoid(method, instance, a);
                return obj;
            }
            return Coercion.toLua(InvokeSupport.invoke(method, instance, a));
        } catch (InvocationTargetException e) {
            LuaErrors.error(method + " " + e.getTargetException());
        } catch (Throwable e) {
            return LuaErrors.error("coercion error " + method + " " + e);
        }
        return LuaValue.NIL;
    }

    /** 静态方法路径（无 receiver）：子类不使用 invokeConverted 协议，桩实现上抛。 */
    @Override
    Varargs invoke(Object[] a) throws Throwable {
        throw new UnsupportedOperationException("method invoke goes through invokeJavaMethod");
    }

    // 打分跳过 arg1（receiver） - Overload 匹配时 args 含 receiver（冒号调用或
    //   JavaOOMethod 插入），方法签名仅含实际参数；不减则 narg 恒比 fixedargs 多 1，
    //   无 varargs 的方法全部判 UNCOERCIBLE（"no coercible public method"）。
    @Override
    int score(Varargs args) {
        return MemberSupport.score(params, args.subargs(2));
    }

    @Override
    public String toJavaString() {
        return "JavaMethod{\n  " + method + "\n}";
    }

    static class Overload extends LuaFunction implements JavaBinding {
        final JavaMethod[] methods;
        // 单态内联缓存 - 按参数 LuaValue 类型缓存最佳重载。
        //   Lua->Java 调用中参数类型通常稳定（同一调用点传同类型对象），
        //   命中时跳过线性扫描与打分，直接调最佳 JavaMethod。
        // key 用 args.arg(2..n).getClass()（LuaValue 子类或 JavaObject），
        //   对 userdata 取 .getClass()（实例 Class）。null = 缓存空。
        // volatile：无同步下多线程读到的可能是旧值，但 best 会重算，仅作声明性可见性
        private volatile Class<?>[] icKey;
        private volatile JavaMethod icBest;

        Overload(JavaMethod[] methods) {
            this.methods = methods;
        }

        // 提取 LuaValue 的 Class 作 IC key。
        //   userdata -> 实例 Class；LuaInteger/LuaString/LuaFloat/LuaBoolean -> 自身 Class；
        //   nil/none -> null（不可缓存，nil 可匹配多个重载）。
        private static Class<?> classOf(LuaValue v) {
            int tt = v.type();
            if (tt == LuaValue.TUSERDATA) {
                Object ud = v.touserdata();  // LuaUserdata.udatamem，可能 null
                return ud != null ? ud.getClass() : null;
            }
            // Lua 原生值类型用 LuaValue 子类 Class 作 key
            return (tt == LuaValue.TNIL || tt == LuaValue.TNONE) ? null : v.getClass();
        }

        @Override
        public Varargs call(Varargs args) {
            int n = args.narg();
            // 单态 IC 检查（仅 n>=2，即至少 1 个方法参数）
            Class<?>[] key = icKey;
            JavaMethod best = icBest;
            if (n >= 2 && key != null && key.length == n - 1) {
                boolean match = true;
                for (int i = 0; i < key.length; i++) {
                    LuaValue v = args.arg(i + 2);
                    Class<?> c = classOf(v);
                    if (c != key[i]) {
                        match = false;
                        break;
                    }
                }
                if (match) {
                    return best.invokeJavaMethod(args.arg1(),
                            n == 2 ? args.arg(2) : args.subargs(2));
                }
            }
            best = null;
            int score = Coercion.SCORE_UNCOERCIBLE;
            for (JavaMethod m : methods) {
                int s = m.score(args);
                if (s < score) {
                    score = s;
                    best = m;
                    if (score == 0) break;
                }
            }
            if (best == null) LuaErrors.error("no coercible public method\n" + this);
            // 更新 IC（仅对可识别类型的参数缓存）
            if (n >= 2) {
                Class<?>[] newKey = new Class<?>[n - 1];
                boolean cacheable = true;
                for (int i = 0; i < newKey.length; i++) {
                    Class<?> c = classOf(args.arg(i + 2));
                    if (c == null) {
                        cacheable = false;
                        break;
                    }
                    newKey[i] = c;
                }
                if (cacheable) {
                    icKey = newKey;
                    icBest = best;
                }
            }
            return best.invokeJavaMethod(args.arg1(),
                    n == 2 ? args.arg(2) : args.subargs(2));
        }

        @Override
        public String toJavaString() {
            StringBuilder buf = new StringBuilder("JavaMethod{\n");
            for (JavaMethod m : methods) buf.append("  ").append(m.method).append("\n");
            return buf.append("}").toString();
        }
    }

    /**
     * 绑定实例的方法包装，用于 OOP 风格调用。
     */
    public static class JavaOOMethod extends LuaFunction implements JavaBinding {
        private final JavaObject mObj;
        private final LuaValue mMethod;

        public JavaOOMethod(JavaObject obj, LuaValue m) {
            mObj = obj;
            mMethod = m;
        }

        @Override
        public Varargs call(Varargs args) {
            // 同时支持冒号调用 obj:method(a,b)（arg1==mObj，跳过）与
            //   点调用 obj.method(a,b)（无 receiver，args 即方法参数）。
            //   Overload 重载包装须把 receiver 插入 arg1（JavaMethod/Overload 假定 receiver 在 arg1）。
            if (args.narg() > 0 && args.arg1() == mObj) {
                args = args.subargs(2);
            }
            if (mMethod instanceof JavaMethod jm) return jm.invokeJavaMethod(mObj, args);
            return ((LuaFunction) mMethod).call(Varargs.of(mObj, args));
        }

        @Override
        public int type() {
            return mMethod.type();
        }

        @Override
        public String typeName() {
            return mMethod.typeName();
        }

        @Override
        public String toJavaString() {
            return mMethod.toJavaString();
        }
    }
}
