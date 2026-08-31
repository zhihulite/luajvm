// java-only: androidx.webkit 的类型化预启动调用（与 WebViewPrewarm 分开，便于缺依赖时兜住）。
//     本类 package-private，不外溢出包。
package org.luajvm.android.engine;

import android.content.Context;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.webkit.WebViewCompat;
import androidx.webkit.WebViewOutcomeReceiver;
import androidx.webkit.WebViewStartUpConfig;
import androidx.webkit.WebViewStartUpResult;
import androidx.webkit.WebViewStartupException;

import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * java-only：真正发起 {@code WebViewCompat.startUpWebView} 的那一层。
 *
 * <p><b>单独成类的原因</b>：本类直接 {@code import androidx.webkit.*}。宿主若把
 * {@code androidx.webkit} 排除掉，加载本类会 {@code NoClassDefFoundError} ——
 * 由 {@link WebViewPrewarm} 在调用点 {@code catch (Throwable)} 兜住。
 *
 * <p>类型化调用对准 {@code startUpWebView} 的真实签名（三参、{@code Context}
 * 在最前），编译期即可挡住签名漂移。
 *
 * <p>线程模型：{@code WebViewStartUpConfig} 收一个后台 executor，androidx 用它跑
 * provider 初始化；结果回调里 {@code shutdown()} 它，**不留常驻线程**。
 */
final class WebViewProviderStarter {

    private WebViewProviderStarter() {
    }

    static void start(@NonNull Context appCtx) {
        ExecutorService executor = Executors.newSingleThreadExecutor(r -> {
            var t = new Thread(r, "luajvm-webview-prewarm");
            t.setDaemon(true);   // 预启动没跑完也不该挡住进程退出
            return t;
        });
        long startNanos = System.nanoTime();
        try {
            WebViewStartUpConfig config = new WebViewStartUpConfig.Builder(executor).build();
            WebViewCompat.startUpWebView(appCtx, config, new WebViewOutcomeReceiver<>() {
                @Override
                public void onResult(@NonNull WebViewStartUpResult result) {
                    Log.i(WebViewPrewarm.TAG, String.format(Locale.ROOT, "provider ready in %.1fms",
                            (System.nanoTime() - startNanos) / 1e6));
                    executor.shutdown();
                }

                @Override
                public void onError(@NonNull WebViewStartupException error) {
                    Log.i(WebViewPrewarm.TAG, "provider start failed: " + error.getMessage());
                    executor.shutdown();
                }
            });
            Log.i(WebViewPrewarm.TAG, String.format(Locale.ROOT, "startUpWebView issued in %.1fms",
                    (System.nanoTime() - startNanos) / 1e6));
        } catch (RuntimeException e) {
            // WebView 被禁用/provider 不可用等：不阻断页面，照常用普通 WebView 构造。
            Log.i(WebViewPrewarm.TAG, "startUpWebView threw: " + e);
            executor.shutdown();
        }
    }
}
