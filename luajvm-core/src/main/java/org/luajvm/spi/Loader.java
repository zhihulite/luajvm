// java-only: 加载器SPI接口
package org.luajvm.spi;

import org.luajvm.core.Globals;
import org.luajvm.core.LuaValue;
import org.luajvm.core.Prototype;

/**
 * Prototype 加载器接口。
 *
 * <p>Java 特有：C 无对应。C 直接加载二进制块，Java 允许替换加载逻辑。
 */
public interface Loader {
    LuaValue load(Prototype p, Globals env);
}
