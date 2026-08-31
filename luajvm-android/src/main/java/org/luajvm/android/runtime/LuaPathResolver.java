package org.luajvm.android.runtime;

import android.content.Context;


import java.io.File;
import java.util.List;

/**
 * Lua 路径解析器，统一管理 luaDir/rootDir/extDir 等路径。
 */
public class LuaPathResolver {

    private final Context mApplication;
    private String mLuaDir;
    private String mRootDir;
    private String mExtDir;

    /** 判定「这里是项目根」的目录标志名 */
    private static final List<String> ROOT_MARKERS = List.of("files", "assets");

    /** findRoot 最多向上查找的层数 */
    private static final int MAX_UP_LEVELS = 10;

    public LuaPathResolver(Context context) {
        mApplication = context;
    }

    /**
     * 从路径向上查找项目根目录
     */
    public static String findRoot(String startPath) {
        String path = startPath;
        while (path.length() > 1 && path.endsWith("/")) {
            path = path.substring(0, path.length() - 1);
        }

        for (int i = 0; i < MAX_UP_LEVELS; i++) {
            for (String dir : ROOT_MARKERS) {
                if (path.endsWith("/" + dir)) {
                    return path;
                }
            }
            int lastSlash = path.lastIndexOf('/');
            if (lastSlash <= 0) break;
            path = path.substring(0, lastSlash);
        }

        return startPath;
    }

    /**
     * 构建 Lua require 搜索路径。
     *
     * <p><b>模板顺序：{@code ?.lua} 必须在 {@code ?.luac} 之前</b>，两者都要有：
     * <ul>
     *   <li><b>源码在（预编译就地生成 .luac）</b>：{@code searchpath} 先命中 {@code foo.lua}，
     *       随后 {@code BaseLib.loadFile} 的兄弟文件探测（{@code -Dluajvm.luac=true}）改读
     *       {@code foo.luac}  -  既拿到字节码的加载速度，chunkname 又仍是
     *       {@code @foo.lua}，traceback 文本与走源码时逐字一致。</li>
     *   <li><b>仅发字节码（APK 里剥掉 .lua 省体积）</b>：{@code ?.lua} 落空后由
     *       {@code ?.luac} 兜住，否则 {@code require} 直接失败。此时 chunkname 是
     *       {@code @foo.luac}，traceback 显示 .luac 后缀  -  剥源码的固有代价。</li>
     * </ul>
     *
     * <p><b>core 的 {@code PackageLib.defaultLuaPath()} 不能加 {@code ?.luac}，本方法可以</b>：
     * 官方测试 {@code attrib.lua} 断言 {@code require} 失败消息逐条列出
     * {@code package.path} 每个模板展开后的路径，改 C 默认路径破坏与 C 的逐字对齐。
     * 本方法是 Android 专有装配（经 {@code LuaEngine} 写入 {@code package.path}），
     * 不在官方套件覆盖范围内。

     */
    public static String buildLuaPath(String dir) {
        return String.join(";", dir + "/?.lua", dir + "/?/init.lua",
                dir + "/?.luac", dir + "/?/init.luac");
    }

    public String getLuaDir() {
        if (mLuaDir == null) {
            mLuaDir = mApplication.getFilesDir().getAbsolutePath();
        }
        return mLuaDir;
    }

    // ==================== luaDir ====================
    public void setLuaDir(String luaDir) {
        mLuaDir = luaDir;
    }

    public String getLuaDir(String subDir) {
        return new File(getLuaDir(), subDir).getAbsolutePath();
    }

    public String getRootDir() {
        if (mRootDir == null) {
            String searchPath = (mLuaDir != null) ? mLuaDir : mApplication.getFilesDir().getAbsolutePath();
            mRootDir = findRoot(searchPath);
        }
        return mRootDir;
    }

    // ==================== rootDir ====================
    public void setRootDir(String rootDir) {
        mRootDir = rootDir;
    }

    /**
     * 用户数据目录（共享存储上的 {@code LuaJVM/}）。
     *
     * <p>存储根从 {@code getExternalFilesDir} 反推：它返回
     * {@code <root>/Android/data/<pkg>/files}，截去该后缀即得 {@code <root>}，
     * 与 {@code Environment.getExternalStorageDirectory()}（API 29 deprecated）同值。
     * 取不到（无外置存储/未挂载）时回落应用私有外部目录，仍给出可写路径。
     */
    public String getExtDir() {
        if (mExtDir != null) return mExtDir;
        File extRoot = new File(externalStorageRoot(), "LuaJVM");
        if (!extRoot.exists()) extRoot.mkdirs();
        mExtDir = extRoot.getAbsolutePath();
        return mExtDir;
    }

    /** 共享存储根；拿不到时返回应用私有外部目录。 */
    private File externalStorageRoot() {
        File appFiles = mApplication.getExternalFilesDir(null);
        if (appFiles != null) {
            // 逐段上溯到 <root>：files -> <pkg> -> data -> Android -> <root>
            File p = appFiles;
            for (int i = 0; i < 4 && p != null; i++) p = p.getParentFile();
            if (p != null && p.isDirectory()) return p;
            return appFiles;
        }
        return mApplication.getFilesDir();
    }

    // ==================== extDir ====================
    public void setExtDir(String extDir) {
        mExtDir = extDir;
    }

    public String getExtDir(String subDir) {
        File sub = new File(getExtDir(), subDir);
        if (!sub.exists()) sub.mkdirs();
        return sub.getAbsolutePath();
    }

    // ==================== 路径组合 ====================
    public String getLuaPath(String path) {
        return new File(getLuaDir(), path).getAbsolutePath();
    }

    public String getLuaPath(String dir, String name) {
        return new File(getLuaDir(dir), name).getAbsolutePath();
    }

    public String getExtPath(String path) {
        return new File(getExtDir(), path).getAbsolutePath();
    }

    public String getExtPath(String dir, String name) {
        return new File(getExtDir(dir), name).getAbsolutePath();
    }
}