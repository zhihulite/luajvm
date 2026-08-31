// java-only: 日志SPI接口
package org.luajvm.spi;

/**
 * 日志 SPI。
 *
 * <p>Java 特有：C 无对应。C 用 fprintf(stderr,...)，Java 用可替换日志接口。
 * 默认实现为 no-op；宿主可在启动时用 {@link Loggers#setLogger(Logger)} 注入平台实现
 * （如 Android 的 Logcat 桥接）。
 */
public interface Logger {
    void d(String tag, String msg);

    void i(String tag, String msg);

    void w(String tag, String msg, Throwable t);

    void e(String tag, String msg, Throwable t);
}
