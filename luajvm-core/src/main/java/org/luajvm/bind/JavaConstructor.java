// java-only: Java构造器反射绑定
package org.luajvm.bind;

import org.luajvm.core.LuaErrors;
import org.luajvm.core.LuaFunction;
import org.luajvm.core.LuaValue;
import org.luajvm.core.Varargs;
import org.luajvm.vm.LuaCall;

import java.lang.reflect.Constructor;

/**
 * 包装 Java 构造器的 LuaValue。
 *
 * <p>Java 特有：C 无对应。参数转换/打分/错误协议见基类 {@link ExecutableBinding}；
 * 构造无 receiver（打分不跳 arg1）且产物须包成 JavaObject。
 */
class JavaConstructor extends ExecutableBinding {
    final Constructor<?> constructor;

    private JavaConstructor(Constructor<?> c) {
        super(c);
        this.constructor = c;
    }

    /**
     * java-only：按 ClassLoader 可回收性分层（语义与理由见
     * {@link ExecutableBinding#EXECUTABLES}）。
     *
     * <p>键 {@code Constructor} 强引用声明类 => 强引用其 loader；表只增不减，
     * 故自定义 loader 的类不得进表，否则永久钉死。值的 {@code constructor} 字段
     * 就是键本身，{@code WeakHashMap} 同样救不了。
     */
    static JavaConstructor forConstructor(Constructor<?> c) {
        boolean cache = BindCaches.cacheable(c.getDeclaringClass());
        if (cache) {
            JavaConstructor hit = (JavaConstructor) EXECUTABLES.get(c);
            if (hit != null) return hit;
        }
        JavaConstructor j = new JavaConstructor(c);
        if (cache) EXECUTABLES.put(c, j);
        return j;
    }

    public static LuaValue forConstructors(JavaConstructor[] array) {
        return new Overload(array);
    }

    @Override
    public Varargs call(Varargs args) {
        return invokeConverted(args);
    }

    @Override
    Varargs invoke(Object[] a) throws Throwable {
        return new JavaObject(InvokeSupport.construct(constructor, a));
    }

    // 构造调用无 receiver，全量打分（与 JavaMethod 的 subargs(2) 差异）
    @Override
    int score(Varargs args) {
        return MemberSupport.score(params, args);
    }

    @Override
    public String toJavaString() {
        return "JavaConstructor{\n  " + constructor + "\n}";
    }

    static class Overload extends LuaFunction implements JavaBinding {
        final JavaConstructor[] constructors;
        // 单态内联缓存 - 语义同 JavaMethod.Overload（构造器参数类型同样稳定）。
        // volatile：无同步下多线程读到的可能是旧值，但 best 会重算，仅作声明性可见性
        private volatile Class<?>[] icKey;
        private volatile JavaConstructor icBest;

        public Overload(JavaConstructor[] c) {
            this.constructors = c;
        }

        // 提取 LuaValue 的 Class 作 IC key（语义同 JavaMethod.Overload.classOf）。
        private static Class<?> classOf(LuaValue v) {
            int tt = v.type();
            if (tt == LuaValue.TUSERDATA) {
                Object ud = v.touserdata();
                return ud != null ? ud.getClass() : null;
            }
            return (tt == LuaValue.TNIL || tt == LuaValue.TNONE) ? null : v.getClass();
        }

        @Override
        public Varargs call(Varargs args) {
            int n = args.narg();
            Class<?>[] key = icKey;
            JavaConstructor best = icBest;
            if (n >= 1 && key != null && key.length == n) {
                boolean match = true;
                for (int i = 0; i < key.length; i++) {
                    Class<?> c = classOf(args.arg(i + 1));
                    if (c != key[i]) {
                        match = false;
                        break;
                    }
                }
                if (match) return best.call(args);
            }
            best = null;
            int score = Coercion.SCORE_UNCOERCIBLE;
            for (JavaConstructor c : constructors) {
                int s = c.score(args);
                if (s < score) {
                    score = s;
                    best = c;
                    if (score == 0) break;
                }
            }
            if (best == null) LuaErrors.error("no coercible public constructor\n" + this);
            // 更新 IC（仅对可识别类型的参数缓存）
            if (n >= 1) {
                Class<?>[] newKey = new Class<?>[n];
                boolean cacheable = true;
                for (int i = 0; i < newKey.length; i++) {
                    Class<?> c = classOf(args.arg(i + 1));
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
            // best 必然是 JavaConstructor（Java 实现的 LuaFunction），完整 Lua 帧是净开销
            if (InvokeSupport.FAST_CALL) return LuaCall.callJavaBinding(best, args);
            return LuaCall.callLua(best, args);
        }

        @Override
        public String toJavaString() {
            StringBuilder buf = new StringBuilder("JavaConstructor{\n");
            for (JavaConstructor c : constructors)
                buf.append("  ").append(c.constructor).append("\n");
            return buf.append("}").toString();
        }
    }
}
