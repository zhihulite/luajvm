// java-only: 编译器钩子SPI
package org.luajvm.spi;

/**
 * 自定义编译行为的编译器钩子。
 *
 * <p>Java 特有：C 无对应。
 */
public interface CompilerHooks {
    CompilerHooks NOOP = new CompilerHooks() {
    };
}
