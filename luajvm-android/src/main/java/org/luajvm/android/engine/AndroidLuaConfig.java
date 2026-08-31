package org.luajvm.android.engine;

import org.luajvm.spi.LuaConfig;

/**
 * Android 平台的 Lua 运行时配置，经 SPI 注入 luajvm-core。
 *
 * <p>由 {@code META-INF/services/org.luajvm.spi.LuaConfig} 注册，
 * {@code LuaPlatform.bareGlobals} 用 {@code ServiceLoader} 发现后写入 {@code Globals.config}。
 * 宿主无需显式调用；创建 Globals 之后直接改写 {@code globals.config} 可覆盖本实现。
 *
 * <p>C 用 luaconf.h 的编译期宏配置，Android 上没有重编内核这一步，故落成运行时开关。
 */
public class AndroidLuaConfig implements LuaConfig {

    // C: luaconf.h LUA_COMPAT_GLOBAL 默认定义 ⇒ 默认 true
    private static volatile boolean sCompatGlobal = true;

    /**
     * 是否兼容 5.4 及更早的全局变量写法（{@code global} 不作关键字）。
     */
    public static boolean isCompatGlobal() {
        return sCompatGlobal;
    }

    /**
     * 切换 {@code global} 关键字兼容性。
     *
     * <p>只影响此后编译的 chunk：已编译的 Prototype 和已缓存的 .luac 不受影响，
     * 所以必须在加载任何脚本之前设置。传 false 时 {@code global} 成为保留字，
     * 把它当变量名的旧脚本会在编译期报语法错误。
     */
    public static void setCompatGlobal(boolean compatGlobal) {
        sCompatGlobal = compatGlobal;
    }

    @Override
    public boolean compatGlobal() {
        return sCompatGlobal;
    }
}
