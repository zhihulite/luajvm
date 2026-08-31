package org.luajvm.android.api;

import android.content.Context;
import android.net.Uri;

import org.luajvm.core.Globals;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Map;

/**
 * 宿主（Activity / Service / …）实现的接口：把 {@link LuaContext} 与
 * {@link LuaHostDelegate} 的全部方法**默认转发**给 {@link #getLuaDelegate()}，
 * 宿主只需给出那一个方法。
 *
 * <p><b>统一 default 转发</b>：逐宿主手写转发容易把 {@code getHeight()} 复制成
 * {@code getWidth()} —— Lua 读 {@code activity.height} 拿到的就是宽度；
 * default 统一转发后宿主零手写，这类复制粘贴错误结构上不可能再出现。
 *
 * <p><b>Lua 可见 API 不变</b>：{@code JavaObject.get} 靠 {@code Class.getMethods()}
 * 解析属性与方法，它**包含继承来的 default 方法**，所以 {@code activity.height} /
 * {@code service.luaDir} 这些写法一字不用改。
 *
 * <p><b>必须守住的坑（门禁只覆盖一半）</b>：若宿主的**父类**恰好有同签名方法，
 * 父类会静默盖掉接口 default（JLS 的类优先规则），转发悄悄失效。门禁
 * {@code AndroidStaticGatesTest.hostContract} 只读宿主自己 class 的方法表，断言的是
 * "宿主不得再手写转发"；**父类遮蔽这一条并没有机械覆盖**，给宿主换基类或升
 * androidx 版本时要人工核对下面这 41 个 default。
 */
public interface LuaHost extends LuaHostDelegate {

    /**
     * 承载全部实现的 delegate；这是宿主唯一必须自己给出的方法。
     *
     * <p><b>名字不能叫 {@code getDelegate}</b>：{@code AppCompatActivity} 已有一个
     * {@code getDelegate()}（返回 {@code AppCompatDelegate}），同名不同返回类型编译不过。
     * 这正是上面"父类会盖掉接口 default"那条坑。
     *
     * <p><b>不要 {@code return this}</b>：{@code LuaHost} 本身就是 {@code LuaHostDelegate}，
     * 这么写编译通过、然后 41 个 default 全变成自调用，Lua 第一次读 {@code activity.width}
     * 就 StackOverflowError。必须返回真正承载实现的那个对象（如 {@code BaseDelegate} 子类）。
     */
    LuaHostDelegate getLuaDelegate();

    // ---- LuaScriptHost ----
    @Override
    default Globals getLuaState() {
        return getLuaDelegate().getLuaState();
    }

    @Override
    default Object doFile(String path, Object... args) {
        return getLuaDelegate().doFile(path, args);
    }

    @Override
    default void call(String func, Object... args) {
        getLuaDelegate().call(func, args);
    }

    @Override
    default void set(String name, Object value) {
        getLuaDelegate().set(name, value);
    }

    // ---- LuaPaths ----
    @Override
    default InputStream findResource(String filename) {
        return getLuaDelegate().findResource(filename);
    }

    @Override
    default String findFile(String filename) {
        return getLuaDelegate().findFile(filename);
    }

    @Override
    default String getRootDir() {
        return getLuaDelegate().getRootDir();
    }

    @Override
    default String getLuaDir() {
        return getLuaDelegate().getLuaDir();
    }

    @Override
    default String getLuaDir(String dir) {
        return getLuaDelegate().getLuaDir(dir);
    }

    @Override
    default String getLuaPath() {
        return getLuaDelegate().getLuaPath();
    }

    @Override
    default String getLuaPath(String path) {
        return getLuaDelegate().getLuaPath(path);
    }

    @Override
    default String getLuaPath(String dir, String name) {
        return getLuaDelegate().getLuaPath(dir, name);
    }

    @Override
    default String getLuaExtDir() {
        return getLuaDelegate().getLuaExtDir();
    }

    @Override
    default String getLuaExtDir(String dir) {
        return getLuaDelegate().getLuaExtDir(dir);
    }

    @Override
    default void setLuaExtDir(String dir) {
        getLuaDelegate().setLuaExtDir(dir);
    }

    @Override
    default String getLuaExtPath(String path) {
        return getLuaDelegate().getLuaExtPath(path);
    }

    @Override
    default String getLuaExtPath(String dir, String name) {
        return getLuaDelegate().getLuaExtPath(dir, name);
    }

    // ---- LuaSharedData ----
    @Override
    default Map<String, Object> getGlobalData() {
        return getLuaDelegate().getGlobalData();
    }

    @Override
    default Map<String, ?> getSharedData() {
        return getLuaDelegate().getSharedData();
    }

    @Override
    default Object getSharedData(String key) {
        return getLuaDelegate().getSharedData(key);
    }

    @Override
    default Object getSharedData(String key, Object defaultValue) {
        return getLuaDelegate().getSharedData(key, defaultValue);
    }

    @Override
    default boolean setSharedData(String key, Object value) {
        return getLuaDelegate().setSharedData(key, value);
    }

    // ---- LuaAndroidHost ----
    @Override
    default Context getContext() {
        return getLuaDelegate().getContext();
    }

    @Override
    default int getWidth() {
        return getLuaDelegate().getWidth();
    }

    @Override
    default int getHeight() {
        return getLuaDelegate().getHeight();
    }

    @Override
    default float getDensity() {
        return getLuaDelegate().getDensity();
    }

    @Override
    default void sendMsg(String msg) {
        getLuaDelegate().sendMsg(msg);
    }

    @Override
    default void sendError(String title, Exception error) {
        getLuaDelegate().sendError(title, error);
    }

    @Override
    default void regGc(LuaGcable gcable) {
        getLuaDelegate().regGc(gcable);
    }

    @Override
    default ArrayList<ClassLoader> getClassLoaders() {
        return getLuaDelegate().getClassLoaders();
    }

    // ---- LuaHostDelegate 追加的那批 ----
    @Override
    default Object runFunc(String name, Object... args) {
        return getLuaDelegate().runFunc(name, args);
    }

    @Override
    default void newActivity(String path) throws FileNotFoundException {
        getLuaDelegate().newActivity(path);
    }

    @Override
    default void newActivity(String path, Object[] args) throws FileNotFoundException {
        getLuaDelegate().newActivity(path, args);
    }

    @Override
    default void newActivity(int requestCode, String path) throws FileNotFoundException {
        getLuaDelegate().newActivity(requestCode, path);
    }

    @Override
    default void newActivity(int requestCode, String path, Object[] args)
            throws FileNotFoundException {
        getLuaDelegate().newActivity(requestCode, path, args);
    }

    @Override
    default void newActivity(int requestCode, String path, Object[] args, boolean newDocument)
            throws FileNotFoundException {
        getLuaDelegate().newActivity(requestCode, path, args, newDocument);
    }

    @Override
    default Uri getUriForFile(File file) {
        return getLuaDelegate().getUriForFile(file);
    }

    @Override
    default String getPathFromUri(Uri uri) {
        return getLuaDelegate().getPathFromUri(uri);
    }

    @Override
    default void installApk(String path) {
        getLuaDelegate().installApk(path);
    }

    @Override
    default void openFile(String path) {
        getLuaDelegate().openFile(path);
    }

    @Override
    default void shareFile(String path) {
        getLuaDelegate().shareFile(path);
    }
}
