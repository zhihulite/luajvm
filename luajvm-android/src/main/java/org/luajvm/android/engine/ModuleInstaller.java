package org.luajvm.android.engine;

/**
 * Lua 标准模块注册器。引擎本身不依赖任何 lib 模块（保持 engine 层纯净），
 * 由宿主层（BaseDelegate）在 init 序列的注册点注入并安装全部标准模块。
 *
 * <p>调用时序见 {@code LuaEngine.init}：init.lua 环境初始化之后、宿主对象
 * JavaCall.set 之前。
 */
public interface ModuleInstaller {

    /**
     * 把标准模块装进引擎的 Globals。
     */
    void installModules(LuaEngine engine);
}
