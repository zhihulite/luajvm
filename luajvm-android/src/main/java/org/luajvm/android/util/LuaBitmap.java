package org.luajvm.android.util;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.drawable.Drawable;
import android.os.Looper;
import android.widget.ImageView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.luajvm.android.runtime.LuaConfig;
import com.bumptech.glide.Glide;
import com.bumptech.glide.load.model.GlideUrl;
import com.bumptech.glide.load.model.LazyHeaders;
import com.bumptech.glide.request.FutureTarget;
import com.bumptech.glide.request.RequestOptions;
import com.bumptech.glide.request.target.CustomTarget;
import com.bumptech.glide.request.transition.Transition;

import java.io.File;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * 基于 Glide 的图片加载工具类
 */
public final class LuaBitmap {

    private static final ConcurrentHashMap<String, String> sHeaders = new ConcurrentHashMap<>();
    private static final ExecutorService sExecutor = Executors.newCachedThreadPool();

    // ==================== 请求头配置 ====================

    private LuaBitmap() {
        throw new UnsupportedOperationException("Cannot instantiate utility class");
    }

    public static void setHeader(String key, String value) {
        sHeaders.put(key, value);
    }

    public static void setHeaders(@Nullable Map<String, String> headers) {
        sHeaders.clear();
        if (headers != null) {
            sHeaders.putAll(headers);
        }
    }

    public static void removeHeader(String key) {
        sHeaders.remove(key);
    }

    // ==================== 加载到 ImageView ====================

    public static void clearHeaders() {
        sHeaders.clear();
    }

    public static void load(@NonNull Context context, @NonNull String url, @NonNull ImageView imageView) {
        load(context, url, imageView, null);
    }

    public static void load(@NonNull Context context, @NonNull String url, @NonNull ImageView imageView,
                            @Nullable RequestOptions options) {
        var builder = Glide.with(context).load(toGlideModel(url));
        if (options != null) builder.apply(options);
        builder.into(imageView);
    }

    public static void load(@NonNull Context context, @NonNull File file, @NonNull ImageView imageView) {
        Glide.with(context).load(file).into(imageView);
    }

    // ==================== 获取 Bitmap ====================

    public static void load(@NonNull Context context, int resourceId, @NonNull ImageView imageView) {
        Glide.with(context).load(resourceId).into(imageView);
    }

    public static void getBitmap(@NonNull Context context, @NonNull String url, @NonNull BitmapCallback callback) {
        Glide.with(context)
                .asBitmap()
                .load(toGlideModel(url))
                .into(new CustomTarget<Bitmap>() {
                    @Override
                    public void onResourceReady(@NonNull Bitmap resource, @Nullable Transition<? super Bitmap> transition) {
                        callback.onSuccess(resource);
                    }

                    @Override
                    public void onLoadFailed(@Nullable Drawable errorDrawable) {
                        callback.onFailed(new Exception("Failed to load bitmap: " + url));
                    }

                    @Override
                    public void onLoadCleared(@Nullable Drawable placeholder) {
                        callback.onFailed(new Exception("Load cleared: " + url));
                    }
                });
    }

    public static Bitmap getBitmapSync(@NonNull Context context, @NonNull String url) throws Exception {
        // 已在后台线程则直接加载
        if (Looper.myLooper() != Looper.getMainLooper()) {
            return loadWithFuture(context, url);
        }

        // 主线程同步阻塞被压到 ANR 阈值以下（LuaConfig.mainThreadWaitMs）：同步语义是
        // API 契约不能改，至少把风险喊出来——Lua 侧应改用异步 loadbitmap 回调形态
        long wait = LuaConfig.mainThreadWaitMs(LuaConfig.getHttpTimeout());
        LuaConfig.logWarn("LuaBitmap.getBitmapSync on main thread; blocks up to "
                + wait + "ms (capped below the ANR threshold)");
        Future<Bitmap> future = sExecutor.submit(() -> loadWithFuture(context, url));

        try {
            return future.get(wait, TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            future.cancel(true);
            throw new Exception("Bitmap loading timeout: " + url, e);
        } catch (ExecutionException e) {
            throw new Exception("Failed to load bitmap: " + url, e.getCause());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new Exception("Interrupted: " + url, e);
        }
    }

    private static Bitmap loadWithFuture(Context context, String url) throws Exception {
        FutureTarget<Bitmap> target = Glide.with(context.getApplicationContext())
                .asBitmap()
                .load(toGlideModel(url))
                .submit();

        try {
            return target.get(LuaConfig.getHttpTimeout(), TimeUnit.MILLISECONDS);
        } catch (Exception e) {
            target.cancel(true);
            throw e;
        }
    }

    // ==================== 预加载与缓存 ====================

    public static void preload(@NonNull Context context, @NonNull String url) {
        Glide.with(context).load(toGlideModel(url)).preload();
    }

    public static void preload(@NonNull Context context, @NonNull String url, int width, int height) {
        Glide.with(context).load(toGlideModel(url)).preload(width, height);
    }

    public static void downloadOnly(@NonNull Context context, @NonNull String url) {
        Glide.with(context).downloadOnly().load(toGlideModel(url)).preload();
    }

    public static void clearMemoryCache(@NonNull Context context) {
        Glide.get(context).clearMemory();
    }

    public static void clearDiskCache(@NonNull Context context) {
        // Glide 的 clearDiskCache 必须在后台线程执行
        new Thread(() -> Glide.get(context).clearDiskCache()).start();
    }

    public static void clearAllCache(@NonNull Context context) {
        clearMemoryCache(context);
        clearDiskCache(context);
    }

    // ==================== 请求生命周期 ====================

    public static void pauseRequests(@NonNull Context context) {
        Glide.with(context).pauseRequests();
    }

    public static void resumeRequests(@NonNull Context context) {
        Glide.with(context).resumeRequests();
    }

    // ==================== 内部方法 ====================

    /**
     * 远程判据：只有 http/https 能带请求头。
     *
     * <p>本地路径必须判掉：{@code GlideUrl} 构造后会走 {@code new URL(path)}，
     * 对 {@code /sdcard/a.png} 这类无 scheme 的路径抛 MalformedURLException。
     */
    public static boolean isRemote(@Nullable String path) {
        if (path == null) return false;
        return path.regionMatches(true, 0, "http://", 0, 7)
                || path.regionMatches(true, 0, "https://", 0, 8);
    }

    /**
     * 该路径是否会被包装以携带请求头（设过请求头 且 远程 URL）。
     *
     * <p>与 {@link #toGlideModel} 同一判据，但不构造任何 Glide 对象 ——
     * JVM 单元测试里 Glide 的 {@code LazyHeaders} 类初始化会抛
     * {@code RuntimeException("Stub!")}（Glide 编译自 android stub jar），
     * 故门禁用本谓词取证判据，不去碰返回值类型。
     */
    public static boolean wrapsHeaders(@Nullable String path) {
        return !sHeaders.isEmpty() && isRemote(path);
    }

    /**
     * 取 Glide 加载模型：远程 URL 且设过请求头时包装成 {@link GlideUrl} 携带请求头，
     * 其余情况原样返回。
     *
     * <p><b>所有远程加载点都必须经此方法</b> —— 直接 {@code load(url)} 会静默丢掉
     * {@link #setHeader} 设的请求头，表现为「部分入口能过鉴权、另一些 401」。
     * 由 BitmapHeaderContractTest 扫源码钉住。
     *
     * <p>无请求头时返回原字符串而非 GlideUrl：后者会换掉 Glide 的缓存键，
     * 使同一图片在设 / 未设请求头两态下各存一份磁盘缓存。
     */
    public static Object toGlideModel(String path) {
        if (!wrapsHeaders(path)) return path;
        var builder = new LazyHeaders.Builder();
        for (Map.Entry<String, String> entry : sHeaders.entrySet()) {
            builder.addHeader(entry.getKey(), entry.getValue());
        }
        return new GlideUrl(path, builder.build());
    }

    // ==================== 回调接口 ====================

    public interface BitmapCallback {
        void onSuccess(@NonNull Bitmap bitmap);

        void onFailed(@NonNull Exception e);
    }
}
