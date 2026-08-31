package org.luajvm.android.runtime;

import org.luajvm.spi.CoroutineThreads;

/**
 * Android 协程线程实现：平台线程。
 *
 * <p>ART 不实现虚拟线程（JEP 444），本实现经 META-INF/services 注入，
 * AAR 装配即生效——core 无需平台探测。
 */
public class AndroidCoroutineThreads implements CoroutineThreads {

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
