// java-only: 日志 SPI 的静态持有者
package org.luajvm.spi;

/**
 * 持有当前 {@link Logger} 实例。
 *
 * <p>接口不能有非 final 静态字段，故用本类持有可变的全局日志实现。
 * 默认 no-op；宿主启动时调用 {@link #setLogger(Logger)} 注入平台实现。
 */
public final class Loggers {
    private static volatile Logger sLogger = new Logger() {
        public void d(String t, String m) {
        }

        public void i(String t, String m) {
        }

        public void w(String t, String m, Throwable e) {
        }

        public void e(String t, String m, Throwable e) {
        }
    };

    private Loggers() {
    }

    public static Logger get() {
        return sLogger;
    }

    public static void setLogger(Logger logger) {
        sLogger = logger != null ? logger : new Logger() {
            public void d(String t, String m) {
            }

            public void i(String t, String m) {
            }

            public void w(String t, String m, Throwable e) {
            }

            public void e(String t, String m, Throwable e) {
            }
        };
    }
}
