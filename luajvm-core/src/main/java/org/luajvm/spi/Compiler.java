// java-only: 编译器SPI接口
package org.luajvm.spi;

import org.luajvm.core.LuaValue;

import java.io.InputStream;

/**
 * 编译器 SPI。
 *
 * <p>Java 特有：C 无对应。C 在内部完成编译，Java 允许替换编译器实现。
 */
@FunctionalInterface
public interface Compiler {
    LuaValue compile(InputStream source, String chunkname, String mode, LuaValue env);
}
