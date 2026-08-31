package org.luajvm.android.api;

import org.luajvm.core.Globals;

/**
 * Lua 脚本执行能力：把值/函数送进 Lua、把 Lua 里的东西调出来。
 *
 * <p>宿主能力的四个角色接口之一。转发统一经 {@code default} 方法走 delegate，
 * 不逐宿主手写。
 *
 * <p>宿主不要直接实现本接口，实现 {@link LuaHost} —— 它用 {@code default} 方法
 * 把这些全部转发给 delegate，宿主只需给出 {@code getDelegate()}。
 */
public interface LuaScriptHost {

    /** 当前宿主的 Lua 状态机。 */
    Globals getLuaState();

    /** 执行一个 Lua 文件。 */
    Object doFile(String path, Object... args);

    /** 调用 Lua 侧的全局函数（不存在时静默忽略）。 */
    void call(String func, Object... args);

    /** 往 Lua 全局表里写一个值。 */
    void set(String name, Object value);
}
