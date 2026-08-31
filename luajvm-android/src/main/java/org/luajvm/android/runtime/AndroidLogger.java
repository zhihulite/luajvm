package org.luajvm.android.runtime;

import android.util.Log;

import org.luajvm.spi.Logger;

/**
 * Android 平台日志实现，桥接到 Android Logcat。
 *
 * <p>实现 {@link org.luajvm.spi.Logger} SPI，由 {@code LuaApplication.onCreate()} 通过
 * {@link org.luajvm.spi.Loggers#setLogger(Logger)} 注入。
 */
public class AndroidLogger implements Logger {

    /** 四个出口无条件共用的 logcat tag（形参 tag 只拼进消息前缀） */
    private static final String LOGCAT_TAG = "LuaJVM";

    @Override
    public void d(String tag, String msg) {
        Log.d(LOGCAT_TAG, prefixed(tag, msg));
    }

    @Override
    public void i(String tag, String msg) {
        Log.i(LOGCAT_TAG, prefixed(tag, msg));
    }

    @Override
    public void w(String tag, String msg, Throwable t) {
        if (t != null) {
            Log.w(LOGCAT_TAG, prefixed(tag, msg), t);
        } else {
            Log.w(LOGCAT_TAG, prefixed(tag, msg));
        }
    }

    @Override
    public void e(String tag, String msg, Throwable t) {
        if (t != null) {
            Log.e(LOGCAT_TAG, prefixed(tag, msg), t);
        } else {
            Log.e(LOGCAT_TAG, prefixed(tag, msg));
        }
    }

    private static String prefixed(String tag, String msg) {
        return "[" + tag + "] " + msg;
    }
}
