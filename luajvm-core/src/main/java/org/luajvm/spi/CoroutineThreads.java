package org.luajvm.spi;

/**
 * 协程线程的创建接缝。
 *
 * <p>core 的默认实现用虚拟线程（Java 21，创建/切换成本最接近 C 的 ucontext）；
 * Android 平台无虚拟线程（JEP 444 之外，ART 不实现），宿主经 SPI 注入平台线程实现
 * ——core 不含任何平台探测（vm.name 判断/系统属性开关），按装配的组合点决定行为，
 * 与 LuaConfig/LuaJavaContext 的注入模式同构。
 */
public interface CoroutineThreads {

    /** 创建并启动一个协程承载线程。 */
    Thread start(Runnable body, String name, long stackBytes);

    /** 实现是否为虚拟线程（测试与日志区分用）。 */
    default boolean isVirtual() {
        return true;
    }
}
