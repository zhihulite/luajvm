package org.luajvm.android.api;

import java.io.InputStream;

/**
 * Lua 脚本与资源的路径解析：{@code package.path} 的装配基础。
 *
 * <p>{@link LuaContext} 的四个角色之一（另见 {@link LuaScriptHost}、
 * {@link LuaSharedData}、{@link LuaAndroidHost}）。
 *
 * <p>三套目录的分工：
 * <ul>
 *   <li><b>luaDir</b> —— 宿主自己的脚本目录（{@code files/} 下解包出来的那份）；</li>
 *   <li><b>luaExtDir</b> —— 外部扩展目录，可由脚本改（{@link #setLuaExtDir}）；</li>
 *   <li><b>rootDir</b> —— 上面两者的公共根。</li>
 * </ul>
 */
public interface LuaPaths {

    /** 按名字取资源流（先查 luaDir，再回落 assets）。 */
    InputStream findResource(String filename);

    /**
     * 按名字解析脚本路径。实际契约（LuaEngine.findFile）：绝对路径原样返回；
     * 相对路径经 LuaPathResolver 拼出 luaDir 下的绝对路径——解析失败抛
     * {@link org.luajvm.core.LuaError} 而非返回 null。
     */
    String findFile(String filename);

    String getRootDir();

    String getLuaDir();

    String getLuaDir(String dir);

    String getLuaPath();

    String getLuaPath(String path);

    String getLuaPath(String dir, String name);

    String getLuaExtDir();

    String getLuaExtDir(String dir);

    void setLuaExtDir(String dir);

    String getLuaExtPath(String path);

    String getLuaExtPath(String dir, String name);
}
