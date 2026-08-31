// java-only: 调用追踪 SPI —— 让 core/vm 无需反向 import lib.DebugLib
package org.luajvm.spi;

import org.luajvm.core.LuaFunction;

/**
 * 宿主侧调用追踪钩子（{@code DebugLib.CALLS}/{@code TRACE} 开关驱动）。
 *
 * <p>Java 特有：C 无对应 —— C 的调试追踪走 {@code lua_sethook} 那一套（已由
 * {@link org.luajvm.core.Globals#hookResolver} 覆盖）。本接口是 Java 侧额外的
 * 宿主级 pcall 进出计数，只在 {@code debug} 库装载后才非空。
 *
 * <p>抽成接口的理由同 {@link BaseLibrary}：{@code Globals} 需要一个字段指向它，
 * 字段类型若写 {@code lib.DebugLib} 会让 {@code core} 反向依赖 {@code lib}。
 */
public interface DebugTracer {

    /** 进入受保护调用（pcall/xpcall）时调用。 */
    void onCall(LuaFunction f);

    /** 离开受保护调用时调用。 */
    void onReturn();
}
