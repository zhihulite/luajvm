package org.luajvm.android.widget;

import android.annotation.SuppressLint;
import android.content.Context;
import android.graphics.Bitmap;
import android.net.Uri;
import android.net.http.SslError;
import android.os.Build;
import android.os.Message;
import android.view.KeyEvent;
import android.view.View;
import android.webkit.ConsoleMessage;
import android.webkit.GeolocationPermissions;
import android.webkit.HttpAuthHandler;
import android.webkit.JavascriptInterface;
import android.webkit.JsPromptResult;
import android.webkit.JsResult;
import android.webkit.PermissionRequest;
import android.webkit.RenderProcessGoneDetail;
import android.webkit.SslErrorHandler;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebChromeClient.CustomViewCallback;
import android.webkit.WebChromeClient.FileChooserParams;
import android.webkit.WebResourceResponse;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import androidx.annotation.RequiresApi;

import org.luajvm.android.api.LuaContext;
import org.luajvm.android.api.LuaGcable;

/**
 * 直通 {@link WebView}：除了 JS 桥注入与 GC 契约不添加任何行为，
 * 未覆写的设置/导航/下载等全部按 WebView 默认语义走，由 Lua 侧经
 * {@code setWebViewClient}/{@code setWebChromeClient}（Creator 委托式）自行装配。
 */
@SuppressWarnings("unused")
@SuppressLint("ViewConstructor")
public class LuaWebView extends WebView implements LuaGcable {

    private boolean mIsGced;

    public LuaWebView(Context context) {
        super(context);
    }

    public LuaWebView(LuaContext context) {
        this(context.getContext());
        context.regGc(this);
    }

    // ==================== JS 桥注入 ====================

    /**
     * 注入单通道业务桥（名字自定）；先摘同名旧实例，换桥不残留。
     * 传 {@code null} 只摘不装。
     */
    @SuppressLint("AddJavascriptInterface")
    public void setJsInterface(JsInterface object, String name) {
        removeJavascriptInterface(name);
        if (object != null) {
            addJavascriptInterface(new JsObjectWrapper(object), name);
        }
    }

    // ==================== Creator 装配入口 ====================

    /**
     * 注入 Lua 侧 client（Creator 委托式）；传 {@code null} 退回 WebView 默认语义。
     */
    public void setWebViewClient(Creator creator) {
        setWebViewClient(creator == null ? new WebViewClient() : new ClientAdapter(creator));
    }

    /**
     * 注入 Lua 侧 chrome（Creator 委托式）：进度/标题/图标/JS 对话框/CustomView/文件选择。
     * 传 {@code null} 退回 WebChromeClient 默认语义。
     */
    public void setWebChromeClient(ChromeCreator creator) {
        setWebChromeClient(creator == null ? new WebChromeClient() : new ChromeAdapter(creator));
    }

    // ==================== WebView / GC ====================

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (keyCode == KeyEvent.KEYCODE_BACK && canGoBack()) {
            goBack();
            return true;
        }
        return super.onKeyDown(keyCode, event);
    }

    @Override
    public void gc() {
        destroy();
        mIsGced = true;
    }

    @Override
    public boolean isGc() {
        return mIsGced;
    }

    // ==================== 契约（Lua 侧 createProxy 的目标） ====================

    /**
     * 单通道 JS 桥：页面 JS 经 {@code window.<name>.execute(payload)} 调宿主，
     * 只有一个字符串入参、一个字符串返回值。action 分发由业务方在 payload 里自行约定。
     */
    public interface JsInterface {
        @JavascriptInterface
        String execute(String payload);
    }

    public interface Creator {
        void doUpdateVisitedHistory(WebView view, String url, boolean isReload);

        void onFormResubmission(WebView view, Message dontResend, Message resend);

        void onLoadResource(WebView view, String url);

        void onPageFinished(WebView view, String url);

        void onPageStarted(WebView view, String url, Bitmap favicon);

        void onReceivedError(WebView view, int errorCode, String description, String failingUrl);

        void onReceivedHttpAuthRequest(WebView view, HttpAuthHandler handler, String host, String realm);

        void onReceivedLoginRequest(WebView view, String realm, String account, String args);

        void onReceivedSslError(WebView view, SslErrorHandler handler, SslError error);

        void onScaleChanged(WebView view, float oldScale, float newScale);

        void onUnhandledKeyEvent(WebView view, KeyEvent event);

        WebResourceResponse shouldInterceptRequest(WebView view, String url);

        boolean shouldOverrideKeyEvent(WebView view, KeyEvent event);

        boolean shouldOverrideUrlLoading(WebView view, String url);

        // 渲染进程崩溃回调 (Android O+)
        @RequiresApi(api = Build.VERSION_CODES.O)
        boolean onRenderProcessGone(WebView view, RenderProcessGoneDetail detail);
    }

    public interface ChromeCreator {
        void onProgressChanged(WebView view, int newProgress);

        void onReceivedTitle(WebView view, String title);

        void onReceivedIcon(WebView view, Bitmap icon);

        void onReceivedTouchIconUrl(WebView view, String url, boolean precomposed);

        void onShowCustomView(View view, CustomViewCallback callback);

        void onShowCustomView(View view, int requestedOrientation, CustomViewCallback callback);

        void onHideCustomView();

        boolean onCreateWindow(WebView view, boolean isDialog, boolean isUserGesture, Message resultMsg);

        void onRequestFocus(WebView view);

        void onCloseWindow(WebView window);

        boolean onJsAlert(WebView view, String url, String message, JsResult result);

        boolean onJsConfirm(WebView view, String url, String message, JsResult result);

        boolean onJsPrompt(WebView view, String url, String message, String defaultValue, JsPromptResult result);

        boolean onJsBeforeUnload(WebView view, String url, String message, JsResult result);

        void onGeolocationPermissionsShowPrompt(String origin, GeolocationPermissions.Callback callback);

        void onGeolocationPermissionsHidePrompt();

        void onPermissionRequest(PermissionRequest request);

        void onPermissionRequestCanceled(PermissionRequest request);

        boolean onConsoleMessage(ConsoleMessage consoleMessage);

        Bitmap getDefaultVideoPoster();

        View getVideoLoadingProgressView();

        void getVisitedHistory(ValueCallback<String[]> callback);

        boolean onShowFileChooser(WebView webView, ValueCallback<Uri[]> filePathCallback, FileChooserParams fileChooserParams);
    }

    // ==================== 装配适配层（creator 恒非空，逐条直委托） ====================

    public static class ClientAdapter extends WebViewClient {
        private final Creator creator;

        public ClientAdapter(Creator creator) {
            this.creator = creator;
        }

        @Override
        public void doUpdateVisitedHistory(WebView view, String url, boolean isReload) {
            creator.doUpdateVisitedHistory(view, url, isReload);
        }

        @Override
        public void onFormResubmission(WebView view, Message dontResend, Message resend) {
            creator.onFormResubmission(view, dontResend, resend);
        }

        @Override
        public void onLoadResource(WebView view, String url) {
            creator.onLoadResource(view, url);
        }

        @Override
        public void onPageFinished(WebView view, String url) {
            creator.onPageFinished(view, url);
        }

        @Override
        public void onPageStarted(WebView view, String url, Bitmap favicon) {
            creator.onPageStarted(view, url, favicon);
        }

        @Override
        public void onReceivedError(WebView view, int errorCode, String description, String failingUrl) {
            creator.onReceivedError(view, errorCode, description, failingUrl);
        }

        @Override
        public void onReceivedHttpAuthRequest(WebView view, HttpAuthHandler handler, String host, String realm) {
            creator.onReceivedHttpAuthRequest(view, handler, host, realm);
        }

        @Override
        public void onReceivedLoginRequest(WebView view, String realm, String account, String args) {
            creator.onReceivedLoginRequest(view, realm, account, args);
        }

        @Override
        public void onReceivedSslError(WebView view, SslErrorHandler handler, SslError error) {
            creator.onReceivedSslError(view, handler, error);
        }

        @Override
        public void onScaleChanged(WebView view, float oldScale, float newScale) {
            creator.onScaleChanged(view, oldScale, newScale);
        }

        @Override
        public void onUnhandledKeyEvent(WebView view, KeyEvent event) {
            creator.onUnhandledKeyEvent(view, event);
        }

        @Override
        public WebResourceResponse shouldInterceptRequest(WebView view, String url) {
            return creator.shouldInterceptRequest(view, url);
        }

        @Override
        public boolean shouldOverrideKeyEvent(WebView view, KeyEvent event) {
            return creator.shouldOverrideKeyEvent(view, event);
        }

        @Override
        public boolean shouldOverrideUrlLoading(WebView view, String url) {
            return creator.shouldOverrideUrlLoading(view, url);
        }

        @RequiresApi(api = Build.VERSION_CODES.O)
        @Override
        public boolean onRenderProcessGone(WebView view, RenderProcessGoneDetail detail) {
            return creator.onRenderProcessGone(view, detail);
        }
    }

    public static class ChromeAdapter extends WebChromeClient {
        private final ChromeCreator creator;

        public ChromeAdapter(ChromeCreator creator) {
            this.creator = creator;
        }

        @Override
        public void onProgressChanged(WebView view, int newProgress) {
            creator.onProgressChanged(view, newProgress);
        }

        @Override
        public void onReceivedTitle(WebView view, String title) {
            creator.onReceivedTitle(view, title);
        }

        @Override
        public void onReceivedIcon(WebView view, Bitmap icon) {
            creator.onReceivedIcon(view, icon);
        }

        @Override
        public void onReceivedTouchIconUrl(WebView view, String url, boolean precomposed) {
            creator.onReceivedTouchIconUrl(view, url, precomposed);
        }

        @Override
        public void onShowCustomView(View view, CustomViewCallback callback) {
            creator.onShowCustomView(view, callback);
        }

        @Override
        public void onShowCustomView(View view, int requestedOrientation, CustomViewCallback callback) {
            creator.onShowCustomView(view, requestedOrientation, callback);
        }

        @Override
        public void onHideCustomView() {
            creator.onHideCustomView();
        }

        @Override
        public boolean onCreateWindow(WebView view, boolean isDialog, boolean isUserGesture, Message resultMsg) {
            return creator.onCreateWindow(view, isDialog, isUserGesture, resultMsg);
        }

        @Override
        public void onRequestFocus(WebView view) {
            creator.onRequestFocus(view);
        }

        @Override
        public void onCloseWindow(WebView window) {
            creator.onCloseWindow(window);
        }

        @Override
        public boolean onJsAlert(WebView view, String url, String message, JsResult result) {
            return creator.onJsAlert(view, url, message, result);
        }

        @Override
        public boolean onJsConfirm(WebView view, String url, String message, JsResult result) {
            return creator.onJsConfirm(view, url, message, result);
        }

        @Override
        public boolean onJsPrompt(WebView view, String url, String message, String defaultValue, JsPromptResult result) {
            return creator.onJsPrompt(view, url, message, defaultValue, result);
        }

        @Override
        public boolean onJsBeforeUnload(WebView view, String url, String message, JsResult result) {
            return creator.onJsBeforeUnload(view, url, message, result);
        }

        @Override
        public void onGeolocationPermissionsShowPrompt(String origin, GeolocationPermissions.Callback callback) {
            creator.onGeolocationPermissionsShowPrompt(origin, callback);
        }

        @Override
        public void onGeolocationPermissionsHidePrompt() {
            creator.onGeolocationPermissionsHidePrompt();
        }

        @Override
        public void onPermissionRequest(PermissionRequest request) {
            creator.onPermissionRequest(request);
        }

        @Override
        public void onPermissionRequestCanceled(PermissionRequest request) {
            creator.onPermissionRequestCanceled(request);
        }

        @Override
        public boolean onConsoleMessage(ConsoleMessage consoleMessage) {
            return creator.onConsoleMessage(consoleMessage);
        }

        @Override
        public Bitmap getDefaultVideoPoster() {
            return creator.getDefaultVideoPoster();
        }

        @Override
        public View getVideoLoadingProgressView() {
            return creator.getVideoLoadingProgressView();
        }

        @Override
        public void getVisitedHistory(ValueCallback<String[]> callback) {
            creator.getVisitedHistory(callback);
        }

        @Override
        public boolean onShowFileChooser(WebView webView, ValueCallback<Uri[]> filePathCallback, FileChooserParams fileChooserParams) {
            return creator.onShowFileChooser(webView, filePathCallback, fileChooserParams);
        }
    }

    // ==================== 内部 ====================

    /** 真正注入给 WebView 的对象：注解必须落在实体类上，动态代理的接口注解不算。 */
    private record JsObjectWrapper(JsInterface jsInterface) {
        @JavascriptInterface
        public String execute(String payload) {
            String result = jsInterface.execute(payload);
            return result != null ? result : "";
        }
    }
}
