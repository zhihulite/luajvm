package org.luajvm.android.api;

import android.net.Uri;

import java.io.File;
import java.io.FileNotFoundException;

/**
 * 宿主 delegate 对外暴露的契约：{@link LuaContext} 之外，再加一批**所有宿主都在转发**
 * 的 Android 交互能力。
 *
 * <p><b>单独成接口的原因</b>：这些方法只有"有 delegate 的
 * 宿主"才有意义，而 {@code LuaEngine} / {@code LuaLayout} 这类只需要"能跑 Lua"的辅助类
 * 也实现 {@code LuaContext} —— 塞进去会逼它们实现一堆用不上的方法。
 *
 * <p><b>放在 {@code .api} 而非直接用 {@code BaseDelegate} 类型</b>：
 * {@link LuaHost} 的 {@code getDelegate()} 返回接口而不是 {@code .engine} 里的具体类，
 * {@code .api} 就不必反向依赖 {@code .engine}。
 */
public interface LuaHostDelegate extends LuaContext {

    /** 调 Lua 侧全局函数并取返回值（{@link LuaScriptHost#call} 的取值版本）。 */
    Object runFunc(String name, Object... args);

    void newActivity(String path) throws FileNotFoundException;

    void newActivity(String path, Object[] args) throws FileNotFoundException;

    void newActivity(int requestCode, String path) throws FileNotFoundException;

    void newActivity(int requestCode, String path, Object[] args) throws FileNotFoundException;

    void newActivity(int requestCode, String path, Object[] args, boolean newDocument)
            throws FileNotFoundException;

    /** 经 FileProvider 换一个可外传的 content:// Uri。 */
    Uri getUriForFile(File file);

    String getPathFromUri(Uri uri);

    void installApk(String path);

    void openFile(String path);

    void shareFile(String path);
}
