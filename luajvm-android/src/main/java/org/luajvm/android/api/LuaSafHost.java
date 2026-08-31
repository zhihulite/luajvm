package org.luajvm.android.api;

import org.luajvm.core.LuaFunction;

/**
 * SAF（Storage Access Framework）宿主能力接口。
 *
 * <p>只有 Activity 宿主（{@code LuaActivity}）实现——整套流程依赖系统的文件
 * 选择器回调，Service 类宿主拿不到选择器。{@code lib/saf} 通过本接口解耦对
 * 具体宿主类的依赖，引擎侧注册条件即 {@code instanceof LuaSafHost}。
 */
public interface LuaSafHost extends LuaAndroidHost, LuaSharedData {

    /**
     * 请求用户选择文档树（对应 ACTION_OPEN_DOCUMENT_TREE）。
     */
    @CallLuaFunction(value = CallLuaFunction.Thread.MAIN,
            note = "Lua 主线程进入；回调经 onActivityResult 主线程派发")
    void openDocumentTree(LuaFunction callback);

    /**
     * 请求用户选择文档（对应 ACTION_OPEN_DOCUMENT）。
     */
    @CallLuaFunction(value = CallLuaFunction.Thread.MAIN,
            note = "Lua 主线程进入；回调经 onActivityResult 主线程派发")
    void getDocument(String mime, LuaFunction callback);

    /**
     * 请求用户创建文档（对应 ACTION_CREATE_DOCUMENT）。
     */
    @CallLuaFunction(value = CallLuaFunction.Thread.MAIN,
            note = "Lua 主线程进入；回调经 onActivityResult 主线程派发")
    void createDocument(String mime, String name, LuaFunction callback);
}
