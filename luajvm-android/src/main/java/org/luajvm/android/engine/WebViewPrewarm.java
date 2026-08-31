// java-only: WebView/Chromium provider 的首帧后异步预启动（只对 Android 有意义）
package org.luajvm.android.engine;

import android.app.Activity;
import android.content.Context;
import android.util.Log;
import android.view.View;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 在**首帧提交之后**异步预启动 WebView 的 Chromium provider，把它从"第一次打开
 * 含 WebView 的页面"那条点击路径上挪走。
 *
 * <p><b>机制</b>：provider + native library + browser startup 是一次性成本，
 * 谁先碰 WebView 谁付——预启动把它挪出"第一次打开含 WebView 的页面"那条点击路径，
 * "首开慢、重开快"的主要来源即此。
 *
 * <p><b>必须走 {@code androidx.webkit} 的异步 {@code startUpWebView}</b>：
 * 平台 API（{@code CookieManager.getInstance()}）立即返回、不触发 provider 初始化，
 * 作预启动入口无效。
 *
 * <p><b>三条保真</b>：
 * <ul>
 *   <li><b>首帧之后</b>才触发（{@code post} 到 decor view 的下一帧），不抢首帧的 CPU；</li>
 *   <li>{@link AtomicBoolean} 去重，**进程内只做一次**（多个 Activity 同时启动也不会
 *       重复付）；</li>
 *   <li>类型化调用放在单独的 {@link WebViewProviderStarter} 里、只在 {@code try} 内触碰：
 *       宿主若显式 {@code exclude} 掉 {@code androidx.webkit}，这里捕获
 *       {@link Throwable}（含 {@code NoClassDefFoundError}）后静默跳过，
 *       页面照常用普通 {@code WebView} 构造。</li>
 * </ul>
 *
 * <p><b>开关</b>：{@code -Dluajvm.webviewprewarm=false} 关闭（Android 上经
 * {@link DebugProps} 从 {@code files/luajvm.props} 注入），同一份 APK 可切开关。
 *
 * <p>Java 特有：C 无对应（WebView 是 Android 平台组件）。
 */
public final class WebViewPrewarm {
    static final String TAG = "LuajvmWebViewPrewarm";

    /** 进程内只做一次。 */
    private static final AtomicBoolean PREWARM_STARTED = new AtomicBoolean(false);

    /** 开关读一次即固定；{@code DebugProps} 必须在本类初始化前注入完毕。 */
    static final boolean ENABLED = enabledByProp();

    /** 未设置视为开（{@code -Dluajvm.webviewprewarm=false} 才关）。 */
    private static boolean enabledByProp() {
        var p = System.getProperty("luajvm.webviewprewarm");
        return p == null || Boolean.parseBoolean(p);
    }

    private WebViewPrewarm() {
    }

    /**
     * 挂到 {@code activity} 的首帧之后触发一次预启动。
     *
     * <p>可重复调用（每个 Activity 的 {@code onCreate} 都可以调），
     * 实际工作由 {@link #PREWARM_STARTED} 保证只做一次。
     */
    public static void scheduleAfterFirstFrame(Activity activity) {
        if (!ENABLED || activity == null || PREWARM_STARTED.get()) return;
        View decor = activity.getWindow() != null ? activity.getWindow().getDecorView() : null;
        if (decor == null) return;
        Context appCtx = activity.getApplicationContext();
        // post 到下一帧：onCreate 里直接跑会挤占首帧。
        decor.post(() -> prewarmNow(appCtx));
    }

    /**
     * 立即预启动（仍然去重、仍然受开关控制），供"已经知道马上要用 WebView"的调用方。
     *
     * <p>{@link #scheduleAfterFirstFrame} 覆盖的是"任何页面打开"这个通用时机；
     * 业务侧若在更早的确定时刻就知道要用（例如即将进入含 WebView 的列表），
     * 可以直接调这个入口，不必等下一帧。
     *
     * <p>本身**不阻塞调用线程**：{@code startUpWebView} 是异步 API，
     * provider 初始化跑在它自己的后台 executor 上。
     */
    public static void prewarmNow(Context context) {
        if (!ENABLED || context == null) return;
        if (!PREWARM_STARTED.compareAndSet(false, true)) return;
        try {
            // [必须隔一层]类型化的 androidx 引用集中在 WebViewProviderStarter 里，
            //   宿主 exclude 掉依赖时在这里被 NoClassDefFoundError 兜住。
            WebViewProviderStarter.start(context.getApplicationContext());
        } catch (Throwable t) {
            Log.i(TAG, "prewarm skipped: " + t);
        }
    }
}
