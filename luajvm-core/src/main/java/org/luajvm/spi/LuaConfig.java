// java-only: Lua配置SPI
package org.luajvm.spi;

/**
 * Lua 5.5 运行时配置 SPI。
 *
 * <p>Java 特有：C 无对应。C 通过 luaconf.h 编译期宏配置，Java 用接口默认方法。
 */
public interface LuaConfig {
    default boolean compatGlobal() {
        // C: luaconf.h 默认定义 LUA_COMPAT_GLOBAL
        return true;
    }

}
