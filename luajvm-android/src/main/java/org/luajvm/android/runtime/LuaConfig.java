package org.luajvm.android.runtime;

import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.concurrent.Callable;

/**
 * 框架全局配置中心，管理 debug 开关、日志标签、HTTP 超时等。
 */
public final class LuaConfig {

    private static final String DEFAULT_TAG = "LuaJVM";
    private static final int MIN_TIMEOUT = 1000;
    private static final int MAX_TIMEOUT = 30000;
    private static final int MIN_POOL_SIZE = 1;
    private static final int MAX_POOL_SIZE = 32;

    private static volatile boolean sDebug = false;
    private static volatile String sTag = DEFAULT_TAG;
    private static volatile int sHttpTimeout = 6000;
    private static volatile int sIoPoolSize = Math.max(2, Runtime.getRuntime().availableProcessors());
    // 默认 false 走平台默认信任：HttpCore 在首次请求时惰性读此旋钮，逐连接装配 trust-all factory
    private static volatile boolean sSslTrustAll = false;
    private static volatile LogLevel sLogLevel = LogLevel.DEBUG;

    /**
     * 主线程同步等待上限：系统的 ANR 阈值是 5000ms，默认 httpTimeout 6000ms 加 500ms 余量
     * 已是 6500ms——不封顶时主线程同步请求/解码必然越过 ANR。util 与 lib 两层的
     * 同步路径（LuaBitmap.getBitmapSync / SyncHttp）共用本值。
     */
    public static final long MAIN_THREAD_WAIT_CAP_MS = 4500;

    /** 等待上限公式：httpTimeout + 500ms 余量，封顶 {@link #MAIN_THREAD_WAIT_CAP_MS}。 */
    public static long mainThreadWaitMs(int httpTimeoutMs) {
        return Math.min(httpTimeoutMs + 500L, MAIN_THREAD_WAIT_CAP_MS);
    }

    private LuaConfig() {
    }

    // ==================== 基础配置 ====================

    public static boolean isDebug() {
        return sDebug;
    }

    public static void setDebug(boolean debug) {
        if (sDebug != debug) {
            sDebug = debug;
            LuaLog.getInstance().setDebug(debug);
            log("Debug mode " + (debug ? "enabled" : "disabled"));
        }
    }

    @NonNull
    public static String getTag() {
        return sTag;
    }

    public static void setTag(@NonNull String tag) {
        sTag = !tag.isEmpty() ? tag : DEFAULT_TAG;
    }

    // ==================== 网络配置 ====================

    public static int getHttpTimeout() {
        return sHttpTimeout;
    }

    public static void setHttpTimeout(int timeoutMs) {
        sHttpTimeout = clamp(timeoutMs, MIN_TIMEOUT, MAX_TIMEOUT);
    }

    public static boolean isSslTrustAll() {
        return sSslTrustAll;
    }

    public static void setSslTrustAll(boolean trustAll) {
        sSslTrustAll = trustAll;
    }

    // ==================== 线程池配置 ====================

    public static int getIoPoolSize() {
        return sIoPoolSize;
    }

    public static void setIoPoolSize(int size) {
        sIoPoolSize = clamp(size, MIN_POOL_SIZE, MAX_POOL_SIZE);
    }

    // ==================== 日志配置 ====================

    public static LogLevel getLogLevel() {
        return sLogLevel;
    }

    public static void setLogLevel(LogLevel level) {
        sLogLevel = level != null ? level : LogLevel.DEBUG;
    }

    public static void log(String msg) {
        log(LogLevel.DEBUG, msg, null);
    }

    // ==================== 日志输出 ====================

    public static void log(String msg, Throwable t) {
        log(LogLevel.DEBUG, msg, t);
    }

    public static void logInfo(String msg) {
        log(LogLevel.INFO, msg, null);
    }

    public static void logInfo(String msg, Throwable t) {
        log(LogLevel.INFO, msg, t);
    }

    public static void logWarn(String msg) {
        log(LogLevel.WARN, msg, null);
    }

    public static void logWarn(String msg, Throwable t) {
        log(LogLevel.WARN, msg, t);
    }

    public static void logError(String msg) {
        log(LogLevel.ERROR, msg, null);
    }

    public static void logError(String msg, Throwable t) {
        log(LogLevel.ERROR, msg, t);
    }

    private static void log(@NonNull LogLevel level, @Nullable String msg, @Nullable Throwable t) {
        if (sLogLevel.priority > level.priority) return;

        String text = msg != null ? msg : "";
        if (t != null && level.priority >= LogLevel.WARN.priority) {
            text += "\n" + Log.getStackTraceString(t);
        }

        switch (level) {
            case DEBUG -> {
                if (sDebug) Log.d(sTag, text);
            }
            case INFO -> Log.i(sTag, text);
            case WARN -> Log.w(sTag, text);
            case ERROR -> Log.e(sTag, text);
        }
    }

    /**
     * 安全执行 Runnable
     */
    public static void runSafely(@Nullable Runnable action, @NonNull String tag) {
        if (action == null) return;
        try {
            action.run();
        } catch (Exception e) {
            logError(tag, e);
        }
    }

    // ==================== 安全执行 ====================

    /**
     * 安全执行带返回值的操作，失败返回 null
     */
    @Nullable
    public static <T> T runSafely(@Nullable Callable<T> action, @NonNull String tag) {
        if (action == null) return null;
        try {
            return action.call();
        } catch (Exception e) {
            logError(tag, e);
            return null;
        }
    }

    /**
     * 安全执行，带默认值
     */
    @Nullable
    public static <T> T runSafely(@Nullable Callable<T> action, @Nullable T defaultValue, @NonNull String tag) {
        T result = runSafely(action, tag);
        return result != null ? result : defaultValue;
    }

    public static void dumpConfig() {
        if (!sDebug) return;
        String config = "=== LuaConfig ===\n"
                + "  debug: " + sDebug + "\n"
                + "  tag: " + sTag + "\n"
                + "  httpTimeout: " + sHttpTimeout + "\n"
                + "  ioPoolSize: " + sIoPoolSize + "\n"
                + "  sslTrustAll: " + sSslTrustAll + "\n"
                + "  logLevel: " + sLogLevel;
        Log.d(sTag, config);
    }

    // ==================== 调试辅助 ====================

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    // ==================== 内部方法 ====================

    public enum LogLevel {
        DEBUG(Log.DEBUG),
        INFO(Log.INFO),
        WARN(Log.WARN),
        ERROR(Log.ERROR),
        // 门槛语义：log() 用 `sLogLevel.priority > level.priority` 判丢弃，所以"全关"必须是
        //   最高门槛。写 -1 反而比任何级别都低 ⇒ 设成 NONE 会放行全部日志，与语义相反。
        NONE(Integer.MAX_VALUE);

        final int priority;

        LogLevel(int priority) {
            this.priority = priority;
        }
    }
}