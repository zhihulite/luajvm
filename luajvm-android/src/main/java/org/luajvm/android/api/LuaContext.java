package org.luajvm.android.api;

/**
 * Android 平台的 Lua 上下文接口 —— 四个角色接口的**聚合**，自身不声明方法：
 * {@link LuaScriptHost}（脚本执行）、{@link LuaPaths}（路径解析）、
 * {@link LuaSharedData}（跨宿主共享数据）、{@link LuaAndroidHost}（Android 侧能力）。
 * 对外 API 不变，Lua 侧 {@code activity.xxx} / {@code service.xxx} 全部照旧。
 *
 * <p><b>实现方选择</b>：
 * <ul>
 *   <li>宿主（Activity / Service / …）实现 {@link LuaHost}，不要直接实现本接口；</li>
 *   <li>承载实现的一侧（{@code BaseDelegate}）实现 {@link LuaHostDelegate}；</li>
 *   <li>只需要"能跑 Lua"的辅助类（{@code LuaEngine} / {@code LuaLayout} 等）
 *       才直接实现本接口。</li>
 * </ul>
 */
public interface LuaContext extends LuaScriptHost, LuaPaths, LuaSharedData, LuaAndroidHost {
}
