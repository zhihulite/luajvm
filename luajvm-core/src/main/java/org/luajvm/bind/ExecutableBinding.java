// java-only: 可执行反射成员的公共基类
package org.luajvm.bind;

import org.luajvm.core.LuaErrors;
import org.luajvm.core.LuaFunction;
import org.luajvm.core.LuaValue;
import org.luajvm.core.Varargs;

import java.lang.reflect.Executable;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Member;
import java.util.concurrent.ConcurrentHashMap;
import java.util.Map;

/**
 * "可执行反射成员"（Method/Constructor）的公共骨架：参数描述、打分、调用错误协议、
 * 分层缓存模式与重载内联缓存。
 *
 * <p>Java 特有：C 无对应。Method 子类带 receiver（invokeJavaMethod 单独收 instance），
 * Constructor 子类无 receiver（invoke(Object[]) 直转）。
 */
abstract class ExecutableBinding extends LuaFunction implements JavaBinding {
    /**
     * java-only：进程级包装缓存，只收 loader 与进程同寿的类（判据与理由见
     * {@link BindCaches#cacheable}）。
     *
     * <p>键（Method/Constructor）强引用声明类、声明类强引用其 {@code ClassLoader}，
     * 而本表只增不减 —— 收下自定义 loader 的类会让该 loader 及其全部类永久无法回收
     * （Android 热重载/插件化每次新建 loader ⇒ 内存单调增长）。
     *
     * <p><b>不能改用 {@code WeakHashMap}</b>：值的 {@code member} 字段就是键本身，
     * 而 {@code WeakHashMap} 的 entry 对值持强引用 ⇒ 键恒可达、永不回收。
     */
    static final Map<Member, ExecutableBinding> EXECUTABLES = new ConcurrentHashMap<>();

    /** 被包装的 Method/Constructor。 */
    final Executable member;
    final MemberSupport.ParamInfo params;

    ExecutableBinding(Executable m) {
        this.member = m;
        this.params = new MemberSupport.ParamInfo(m.getParameterTypes(), m.getModifiers());
        try {
            if (!m.isAccessible()) m.setAccessible(true);
        } catch (Exception ignored) {
        }
    }

    /** 分层缓存取回/登记。自定义 loader 的类不进进程级表（判据见 BindCaches）。 */
    static <E extends ExecutableBinding> E forExecutable(E created, Class<?> declaringClass) {
        boolean cache = BindCaches.cacheable(declaringClass);
        if (cache) {
            @SuppressWarnings("unchecked")
            E hit = (E) EXECUTABLES.get(created.member);
            if (hit != null) return hit;
        }
        if (cache) EXECUTABLES.put(created.member, created);
        return created;
    }

    /** 打分。方法重载打分跳过 arg1（receiver），构造器无 receiver 全量打分。 */
    abstract int score(Varargs args);

    /**
     * 无 receiver 的统一调用协议（构造器用）：参数个数硬校验 + 转换 + 执行。
     * 异常协议：目标异常（InvocationTargetException 的 cause）带成员签名报
     * LuaError，转换类错误统一 "coercion error" 前缀。
     */
    Varargs invokeConverted(Varargs args) {
        if (params.varargs == null && params.fixedargs.length != args.narg())
            throw new IllegalArgumentException(member.toString());
        Object[] a = MemberSupport.convertArgs(params, args);
        try {
            return invoke(a);
        } catch (InvocationTargetException e) {
            LuaErrors.error(member + " " + e.getTargetException());
        } catch (Throwable e) {
            return LuaErrors.error("coercion error " + member + " " + e);
        }
        return LuaValue.NIL;
    }

    /** 执行成员（仅 invokeConverted 调用；带 receiver 的 Method 走子类自己的协议）。 */
    abstract Varargs invoke(Object[] a) throws Throwable;
}
