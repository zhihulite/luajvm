// java-only: CoroThreadLeakPlatformTests 用的平台线程 SPI 实现。
//   仅存在于 test 源集（不进 main），经 src/test/platform-spi 的 ServiceLoader 注册注入
package org.luajvm.test;

import org.luajvm.spi.CoroutineThreads;

/** 平台线程实现：对齐 Android（AndroidCoroutineThreads 的 JVM 测试镜像）。 */
public class PlatformCoroutineThreads implements CoroutineThreads {

    @Override
    public Thread start(Runnable body, String name, long stackBytes) {
        Thread t = new Thread(null, body, name, stackBytes);
        t.setDaemon(true);
        t.start();
        return t;
    }

    @Override
    public boolean isVirtual() {
        return false;
    }
}
