// java-only: 成员访问辅助
package org.luajvm.bind;

import static org.luajvm.bind.Coercion.SCORE_UNCOERCIBLE;

import org.luajvm.core.LuaValue;
import org.luajvm.core.Varargs;

import java.lang.reflect.Array;

/**
 * JavaMethod/JavaConstructor 共享的参数强制转换逻辑。
 *
 * <p>Java 特有：C 无对应。评分（{@link #score}）与转换（{@link #convertArgs}）经
 * {@link #varargsMode} 共用同一判据，二者不会对同一调用选出不同的填充方式。
 */
final class MemberSupport {
    static final int METHOD_MODIFIERS_VARARGS = 0x80;
    // 零参数方法复用空数组，避免每次 new Object[0]。
    //   JVM C2 可标量替换空数组，Android ART 不能 - Android 收益更大。
    //   Method.invoke 接受 Object[]，复用常量安全（Method.invoke 不修改入参数组）。
    static final Object[] EMPTY_ARGS = new Object[0];

    private MemberSupport() {
    }

    /**
     * varargs 槽位的填充方式。
     *
     * <p>优先级语义：多个独立参数逐元素填充 &gt; 单 table 展开为元素 &gt;
     * 单值整体作为数组（userdata 数组直传）&gt; 单值作为唯一元素。table 不做
     * "整体转数组"——那会让 table 隐式转 List 的重载（如 {@code ConcatAdapter(List)}）
     * 抢走本应展开匹配的 varargs 重载（{@code ConcatAdapter(Adapter...)}）。
     */
    enum VarargsMode {
        /** 无 varargs 形参，或实参不多于固定形参（varargs 收空数组）。 */
        NONE,
        /** 多个独立实参逐元素填充。 */
        MULTI_FILL,
        /** 单个 table 实参展开为元素（{@code Foo({a, b})} ≡ {@code Foo(a, b)}）。 */
        TABLE_SPREAD,
        /** 单值整体作为数组传入（userdata 是数组类型）。 */
        ARRAY_WHOLE,
        /** 单值作为 varargs 的唯一元素。 */
        SINGLE_ELEMENT
    }

    /** 判定本次调用的 varargs 填充方式；score 与 convertArgs 共用。 */
    static VarargsMode varargsMode(ParamInfo p, Varargs args) {
        if (p.varargs == null) return VarargsMode.NONE;
        int extra = args.narg() - p.fixedargs.length;
        if (extra <= 0) return VarargsMode.NONE;
        if (extra > 1) return VarargsMode.MULTI_FILL;
        LuaValue v = args.arg(args.narg());
        if (v.istable()) return VarargsMode.TABLE_SPREAD;
        return p.varargs.score(v) < p.vararg2.score(v)
                ? VarargsMode.ARRAY_WHOLE : VarargsMode.SINGLE_ELEMENT;
    }

    static int score(ParamInfo p, Varargs args) {
        int n = args.narg();
        int s = 0;
        if (n < p.fixedargs.length) return SCORE_UNCOERCIBLE;
        VarargsMode mode = varargsMode(p, args);
        // 参数个数超出固定形参的罚分：varargs 调用须让位于精确个数的固定参数重载
        //   （append("x") 不得被 append(Object...) 抢走）。TABLE_SPREAD 例外：
        //   table 元素与 varargs 元素形参逐个精确匹配，匹配质量由元素分数量化；
        //   再叠加个数罚分会让 table 隐式转 List/Collection 的重载（10 分）反超。
        if (n > p.fixedargs.length && mode != VarargsMode.TABLE_SPREAD) {
            s = Coercion.SCORE_WRONG_TYPE;
        }
        for (int j = 0; j < p.fixedargs.length; j++) {
            s += p.fixedargs[j].score(args.arg(j + 1));
            if (s > SCORE_UNCOERCIBLE) return s;
        }
        if (mode == VarargsMode.NONE) return s;
        return switch (mode) {
            case MULTI_FILL -> {
                for (int k = p.fixedargs.length; k < n; k++) {
                    s += p.vararg2.score(args.arg(k + 1));
                    if (s > SCORE_UNCOERCIBLE) yield s;
                }
                yield s;
            }
            case TABLE_SPREAD -> {
                LuaValue t = args.arg(p.fixedargs.length + 1);
                int len = t.length();
                for (int k = 0; k < len; k++) {
                    s += p.vararg2.score(t.get(k + 1));
                    if (s > SCORE_UNCOERCIBLE) yield s;
                }
                yield s;
            }
            case ARRAY_WHOLE -> s + p.varargs.score(args.arg(n));
            case SINGLE_ELEMENT -> s + p.vararg2.score(args.arg(n));
            default -> s;
        };
    }

    static Object[] convertArgs(ParamInfo p, Varargs args) {
        // 零参数快速路径 - 复用 EMPTY_ARGS 常量（理由见其声明处）。
        if (p.fixedargs.length == 0 && p.varargs == null) return EMPTY_ARGS;
        if (p.varargs == null) {
            Object[] a = new Object[p.fixedargs.length];
            for (int i = 0; i < a.length; i++)
                a[i] = p.fixedargs[i].coerce(args.arg(i + 1));
            return a;
        }
        int n = args.narg();
        Object[] a = new Object[p.fixedargs.length + 1];
        for (int i = 0; i < p.fixedargs.length; i++)
            a[i] = p.fixedargs[i].coerce(args.arg(i + 1));
        // 填充方式与 score 同判据（varargsMode），保证选中的重载按打分时的语义转换
        a[p.fixedargs.length] = switch (varargsMode(p, args)) {
            case NONE -> Array.newInstance(p.varclass, 0);
            case MULTI_FILL -> {
                int m = n - p.fixedargs.length;
                Object v = Array.newInstance(p.varclass, m);
                for (int i = p.fixedargs.length; i < n; i++)
                    Array.set(v, i - p.fixedargs.length, p.vararg2.coerce(args.arg(i + 1)));
                yield v;
            }
            case TABLE_SPREAD -> {
                LuaValue t = args.arg(p.fixedargs.length + 1);
                int len = t.length();
                Object v = Array.newInstance(p.varclass, len);
                for (int i = 0; i < len; i++)
                    Array.set(v, i, p.vararg2.coerce(t.get(i + 1)));
                yield v;
            }
            case ARRAY_WHOLE -> p.varargs.coerce(args.arg(n));
            // 单元素也须装进数组：varargs 形参在反射层面是数组形参，裸元素在 JVM
            //   与 ART 上同样被 Method.invoke 拒绝（仅异常文案不同：JVM
            //   "argument type mismatch"，ART "argument N has type X, got Y"）
            case SINGLE_ELEMENT -> {
                Object v = Array.newInstance(p.varclass, 1);
                Array.set(v, 0, p.vararg2.coerce(args.arg(n)));
                yield v;
            }
        };
        return a;
    }

    static class ParamInfo {
        final Coercion.Adapter[] fixedargs;
        final Coercion.Adapter varargs;
        final Coercion.Adapter vararg2;
        final Class<?> varclass;

        ParamInfo(Class<?>[] params, int modifiers) {
            boolean isVarargs = (modifiers & METHOD_MODIFIERS_VARARGS) != 0;
            fixedargs = new Coercion.Adapter[isVarargs ? params.length - 1 : params.length];
            for (int i = 0; i < fixedargs.length; i++)
                fixedargs[i] = Coercion.getCoercion(params[i]);
            varargs = isVarargs ? Coercion.getCoercion(params[params.length - 1]) : null;
            if (isVarargs) {
                varclass = params[params.length - 1].getComponentType();
                vararg2 = Coercion.getCoercion(varclass);
            } else {
                varclass = null;
                vararg2 = null;
            }
        }
    }
}
