// java-only: 纯 Java 实现的绑定包装标记
package org.luajvm.bind;

/**
 * 标记"函数体是纯 Java、不含 Lua 字节码"的绑定包装。
 *
 * <p>实现者：{@link JavaMethod}、{@code JavaMethod.Overload}、
 * {@code JavaMethod.JavaOOMethod}、{@link JavaConstructor}、
 * {@code JavaConstructor.Overload}。
 *
 * <p><b>用途</b>：调这些对象时完整 Lua 调用协议（{@code prepCallInfo}、栈拷贝、
 * {@code callOnStack} 试探、Varargs 打包、{@code poscall}、结果打包退栈）全是净开销，
 * 可以走 {@code LuaCall.callJavaBinding} 直调。判定必须精确到"纯 Java 实现"这一点：
 * 只按 {@code instanceof LuaFunction} 判会把 {@code LuaClosure}（真正的 Lua 函数）
 * 也算进来，那样就绕过了 Lua 函数必须的帧建立，是语义错误。
 *
 * <p>Java 特有：C 无对应。
 */
interface JavaBinding {
}
