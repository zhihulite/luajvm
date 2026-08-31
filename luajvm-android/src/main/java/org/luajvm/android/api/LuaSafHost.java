package org.luajvm.android.api;

import org.luajvm.core.LuaFunction;

/**
 * SAF（Storage Access Framework）宿主能力接口。
 *
 * <p>只有 Activity 宿主实现——整套流程依赖系统的文件选择器回调，Service 类宿主
 * 拿不到选择器。{@code lib/saf} 通过本接口解耦对具体宿主类的依赖，引擎侧注册
 * 条件即 {@code instanceof LuaSafHost}。
 */
public interface LuaSafHost extends LuaAndroidHost, LuaSharedData {

    /**
     * 承载选择器流程实现的 delegate；宿主唯一必须自己给出的方法。
     */
    SafDelegate getSafDelegate();

    /** 选择器流程实现，由 Activity 宿主侧的 delegate 承载。 */
    interface SafDelegate {

        /**
         * 请求用户选择文档树（对应 ACTION_OPEN_DOCUMENT_TREE）。
         */
        @CallLuaFunction(value = CallLuaFunction.Thread.MAIN,
                note = "经 onActivityResult 主线程派发")
        void openDocumentTree(LuaFunction callback);

        /**
         * 请求用户选择文档（对应 ACTION_OPEN_DOCUMENT）。
         */
        @CallLuaFunction(value = CallLuaFunction.Thread.MAIN,
                note = "经 onActivityResult 主线程派发")
        void getDocument(String mime, LuaFunction callback);

        /**
         * 请求用户创建文档（对应 ACTION_CREATE_DOCUMENT）。
         */
        @CallLuaFunction(value = CallLuaFunction.Thread.MAIN,
                note = "经 onActivityResult 主线程派发")
        void createDocument(String mime, String name, LuaFunction callback);
    }
}
