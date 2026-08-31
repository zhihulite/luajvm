// java-only: Java <-> Lua 双向调用的便捷封装（bind 层互操作）
package org.luajvm.bind;

import org.luajvm.core.LuaFunction;
import org.luajvm.core.Globals;
import org.luajvm.core.LuaString;
import org.luajvm.core.LuaTable;
import org.luajvm.core.LuaUserdata;
import org.luajvm.core.LuaValue;
import org.luajvm.core.Varargs;
import org.luajvm.vm.LuaCall;

/**
 * Java &lt;-&gt; Lua 边界的一次性调用工具（bind 层）。
 *
 * <p>以引擎原语 {@link Coercion} + {@link LuaCall} 实现。核心路径：
 * Java 参数 -> {@link Coercion#toLua} -> {@link LuaCall#invoke} -> 结果
 * {@link Coercion#toJava} 转回。
 */
public final class JavaCall {

    private JavaCall() {
    }

    /**
     * Java 参数经 Coercion 转 Lua，调用后把首个返回值转回 Java Object。
     * 非函数或无返回值时返回 null。
     */
    public static Object call(LuaValue func, Object... args) {
        if (func == null || !func.isfunction()) return null;
        int n = args != null ? args.length : 0;
        LuaValue[] luaArgs = new LuaValue[n];
        for (int i = 0; i < n; i++) {
            luaArgs[i] = Coercion.toLua(args[i]);
        }
        Varargs result = dispatch(func, Varargs.of(luaArgs));
        if (result.narg() == 0 || result.arg1().isnil()) return null;
        return Coercion.toJava(result.arg1(), Object.class);
    }

    /**
     * 调用无参 Lua 函数，返回 Java Object。
     */
    public static Object call(LuaFunction func) {
        return call((LuaValue) func);
    }

    /**
     * 调用 Lua 函数，Java 参数经 Coercion 转 Lua，返回 LuaValue 首个结果（无则 NIL）。
     */
    public static LuaValue callLua(LuaValue fn, Object... args) {
        if (fn == null || !fn.isfunction()) return LuaValue.NIL;
        int n = args != null ? args.length : 0;
        LuaValue[] luaArgs = new LuaValue[n];
        for (int i = 0; i < n; i++) {
            luaArgs[i] = Coercion.toLua(args[i]);
        }
        Varargs result = dispatch(fn, Varargs.of(luaArgs));
        return result.narg() == 0 ? LuaValue.NIL : result.arg1();
    }

    /**
     * java-only：目标为纯 Java 绑定时走 {@code LuaCall.callJavaBinding} 直调，
     * 省掉一层 Lua 调用帧；其余目标保持完整 {@code LuaCall.invoke} 协议。
     * 判定为何必须精确到 {@code JavaBinding}，见 {@link JavaBinding}。
     */
    private static Varargs dispatch(LuaValue fn, Varargs args) {
        if (InvokeSupport.FAST_CALL && fn instanceof JavaBinding && fn instanceof LuaFunction f) {
            return LuaCall.callJavaBinding(f, args);
        }
        return LuaCall.invoke(fn, args);
    }

    /**
     * java-only：构造 Java 对象（等价 Lua 侧 {@code Cls(args...)}），供宿主 Java 代码
     * （{@code LuaLayout} 的 View 构造等）使用。
     *
     * <p>相对 {@code LuaCall.call(javaClass, args...)} 省掉的是
     * {@code precall -> tryfuncTM}（{@code __call} 元方法查找 + 栈移位）
     * {@code -> precallC(JavaCallFn) -> subargs(2)} 这一整层。
     * 只有元表与 {@code __call} 转发都未被改写时才走直调
     * （{@link JavaObject#hasDefaultJavaCall}），否则回到完整元方法协议，
     * 保证用户自定义 {@code __call} 仍生效。
     */
    public static LuaValue construct(LuaValue javaClass, LuaValue... args) {
        Varargs packed = args == null || args.length == 0 ? LuaValue.NONE : Varargs.of(args);
        if (InvokeSupport.FAST_CALL && javaClass instanceof JavaObject jo
                && jo.hasDefaultJavaCall()) {
            return jo.call(packed).arg1();
        }
        return LuaCall.callLua(javaClass, packed).arg1();
    }

    /**
     * java-only：以已经是 {@link LuaValue} 的参数调用绑定成员（不做 Java->Lua coercion）。
     *
     * <p>等价于 {@code LuaCall.call(fn, args...)}，但当 {@code fn} 是
     * {@link JavaBinding}（{@code JavaObject.getJavaMethod} 的返回值等）时走直调，
     * 省掉一层 Lua 调用帧；其余目标行为与 {@code LuaCall.call} 完全一致。
     */
    public static LuaValue invokeMember(LuaValue fn, LuaValue... args) {
        Varargs packed = args == null || args.length == 0 ? LuaValue.NONE : Varargs.of(args);
        if (InvokeSupport.FAST_CALL && fn instanceof JavaBinding && fn instanceof LuaFunction f) {
            return LuaCall.callJavaBinding(f, packed).arg1();
        }
        return LuaCall.callLua(fn, packed).arg1();
    }

    /**
     * 把 Java 值转 Lua 后写入 target 的字符串键字段。
     * target 为 null 或不可写时静默忽略。
     */
    public static void set(LuaValue target, String key, Object value) {
        if (target == null) return;
        try {
            LuaValue luaValue = Coercion.toLua(ownerOf(target), value);
            if (target instanceof LuaTable t) {
                t.set(key, luaValue);
            } else {
                target.rawset(key, luaValue);
            }
        } catch (RuntimeException ignored) {
        }
    }

    /**
     * java-only：目标值所属的 {@link Globals}。
     *
     * <p>{@link Coercion#toLua(Globals, Object)} 处理 {@code Class} 时需要精确状态：
     * 当前运行状态推断。Android 每个 Activity 各建一个 Globals，第二个 Activity 尚未
     * 进入 Lua 执行区时该推断会选中第一个状态；随后写入目标表会因 userdata 跨状态而
     * 被拒绝。目标表/对象已经携带精确归属，Java 边界写入应优先使用它。
     */
    private static Globals ownerOf(LuaValue target) {
        if (target instanceof LuaTable table) return table.ownerGlobals;
        if (target instanceof LuaUserdata userdata) return userdata.owner();
        return null;
    }

    /**
     * 对 Java 对象包装设置字符串键属性，值经 Coercion 转换。不可写时静默忽略。
     */
    public static void setField(LuaValue target, String key, Object value) {
        if (target == null) return;
        try {
            target.set(LuaString.newStr(key), Coercion.toLua(value));
        } catch (RuntimeException ignored) {
        }
    }
}
