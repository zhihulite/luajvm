package org.luajvm.android.api;

import android.content.Context;

import java.util.ArrayList;

/**
 * 宿主的 Android 侧能力：上下文、屏幕度量、消息/错误上报、GC 登记。
 *
 * <p>从 {@code LuaContext} 拆出的四个角色之一。
 *
 * <p><b>屏幕度量由这里统一提供</b>：Service 类宿主
 * （{@code LuaService} / {@code LuaWallpaperService} 等）没有窗口，取不到自己的尺寸，
 * 只能由 delegate 从 {@code LuaEngine} 拿全局度量——转发统一走 delegate，
 * 不逐宿主手写。
 */
public interface LuaAndroidHost {

    /** 宿主的 Android 上下文（Activity 本身 / Service 本身）。 */
    Context getContext();

    /** 屏幕宽度（px）。 */
    int getWidth();

    /** 屏幕高度（px）。 */
    int getHeight();

    /** 屏幕密度。 */
    float getDensity();

    /** 给用户看的普通消息。 */
    void sendMsg(String msg);

    /** 给用户看的错误（宿主决定是弹框还是打日志）。 */
    void sendError(String title, Exception error);

    /** 登记一个需要在宿主销毁时释放的对象。 */
    void regGc(LuaGcable obj);

    /** 供 {@code luajava.bindClass} 查类用的 ClassLoader 链。 */
    ArrayList<ClassLoader> getClassLoaders();
}
