package org.luajvm.android.lib;

import android.os.Looper;

import org.luajvm.android.runtime.LuaConfig;
import org.luajvm.core.LuaValue;

import java.util.Collections;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * 同步 HTTP：{@code SyncHttp.get(url)} 直接返回 {@link HttpCore.HttpResult}。
 *
 * <p><b>不是主线程安全的</b>：主线程调用会阻塞到请求完成或超时。同步返回值是已发布的
 * API 契约（脚本按返回值写），故保留同步形态，但主线程等待被压在 ANR 阈值以下
 * （见 {@link LuaConfig#MAIN_THREAD_WAIT_CAP_MS}）。UI 路径应改用异步 {@code Http}。
 */
public class SyncHttp {

    /** 把主线程的同步请求挪到别的线程执行，躲开 NetworkOnMainThreadException */
    private static final ExecutorService OFFLOAD_POOL = Executors.newCachedThreadPool();


    // ==================== 请求头 ====================

    public static Map<String, String> getDefaultHeaders() {
        return HttpCore.getDefaultHeaders();
    }

    public static void setDefaultHeaders(Map<String, String> headers) {
        HttpCore.setDefaultHeaders(headers);
    }

    // ==================== GET ====================

    public static HttpCore.HttpResult get(String url) {
        return awaitResult(HttpCore.get(url));
    }

    public static HttpCore.HttpResult get(String url, Map<String, String> headers) {
        return awaitResult(HttpCore.get(url, headers));
    }

    // ==================== HEAD ====================

    public static HttpCore.HttpResult head(String url) {
        return awaitResult(HttpCore.head(url));
    }

    public static HttpCore.HttpResult head(String url, Map<String, String> headers) {
        return awaitResult(HttpCore.head(url, headers));
    }

    // ==================== POST ====================

    public static HttpCore.HttpResult post(String url, LuaValue body) {
        return awaitResult(HttpCore.post(url, HttpCore.luaBody(body)));
    }

    public static HttpCore.HttpResult post(String url, LuaValue body, Map<String, String> headers) {
        return awaitResult(HttpCore.post(url, HttpCore.luaBody(body), headers));
    }

    // ==================== PUT ====================

    public static HttpCore.HttpResult put(String url, LuaValue body) {
        return awaitResult(HttpCore.put(url, HttpCore.luaBody(body)));
    }

    public static HttpCore.HttpResult put(String url, LuaValue body, Map<String, String> headers) {
        return awaitResult(HttpCore.put(url, HttpCore.luaBody(body), headers));
    }

    // ==================== DELETE ====================

    public static HttpCore.HttpResult delete(String url) {
        return awaitResult(HttpCore.delete(url));
    }

    public static HttpCore.HttpResult delete(String url, Map<String, String> headers) {
        return awaitResult(HttpCore.delete(url, headers));
    }

    // ==================== 上传 ====================

    public static HttpCore.HttpResult upload(String url, Map<String, String> data, Map<String, String> files) {
        return awaitResult(HttpCore.upload(url, data, files));
    }

    public static HttpCore.HttpResult upload(String url, Map<String, String> data, Map<String, String> files, Map<String, String> headers) {
        return awaitResult(HttpCore.upload(url, data, files, headers));
    }

    // ==================== 核心 ====================

    /**
     * 非主线程直接跑；主线程经 {@link #OFFLOAD_POOL} 转发后阻塞等结果
     * （等待上限见 {@link LuaConfig#MAIN_THREAD_WAIT_CAP_MS}）。
     *
     * <p>超过上限返回 {@code code=-1、text="timeout"}，与常规超时路径同形，脚本无需改写；
     * 代价是主线程上的慢请求会提前判超时（后台线程不受影响，仍用全额 {@code httpTimeout}）。
     */
    private static HttpCore.HttpResult awaitResult(HttpCore.HttpTask task) {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            return task.sync();
        }

        long wait = LuaConfig.mainThreadWaitMs(LuaConfig.getHttpTimeout());
        LuaConfig.logWarn("SyncHttp called on main thread; blocks up to " + wait
                + "ms (httpTimeout=" + LuaConfig.getHttpTimeout()
                + "ms, capped below the ANR threshold) — use async Http instead");
        var future = OFFLOAD_POOL.submit(task::sync);
        try {
            return future.get(wait, TimeUnit.MILLISECONDS);
        } catch (TimeoutException ignored) {
            future.cancel(true);
            return new HttpCore.HttpResult(-1, "timeout", null, null, Collections.emptyMap(), null);
        } catch (Exception e) {
            return new HttpCore.HttpResult(-1, "error: " + e.getMessage(), null, null, Collections.emptyMap(), null);
        }
    }

    /**
     * 包级可见供 {@code SyncHttpAnrContractTest} 取证 —— 不暴露给脚本。
     * 数值唯一来源是 {@link LuaConfig#MAIN_THREAD_WAIT_CAP_MS}。
     */
    static final long MAIN_THREAD_WAIT_CAP_MS = LuaConfig.MAIN_THREAD_WAIT_CAP_MS;

    /** 包级可见供 {@code SyncHttpAnrContractTest} 取证 —— 不暴露给脚本。 */
    static long mainThreadWaitMs(int httpTimeoutMs) {
        return LuaConfig.mainThreadWaitMs(httpTimeoutMs);
    }
}
