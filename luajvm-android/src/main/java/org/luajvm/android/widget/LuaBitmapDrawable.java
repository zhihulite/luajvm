package org.luajvm.android.widget;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.drawable.Drawable;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.luajvm.android.api.LuaContext;
import org.luajvm.android.runtime.LuaConfig;
import org.luajvm.android.util.LuaBitmap;
import org.luajvm.android.api.LuaGcable;
import com.bumptech.glide.Glide;
import com.bumptech.glide.request.target.CustomTarget;
import com.bumptech.glide.request.transition.Transition;

public class LuaBitmapDrawable extends Drawable implements LuaGcable {

    public static final int MATRIX = 0;
    public static final int FIT_XY = 1;
    public static final int FIT_START = 2;
    public static final int FIT_CENTER = 3;
    public static final int FIT_END = 4;
    public static final int CENTER = 5;
    public static final int CENTER_CROP = 6;
    public static final int CENTER_INSIDE = 7;

    private final Context mContext;
    private Drawable mRealDrawable;
    private Drawable mErrorDrawable;
    private NineBitmapDrawable mNineBitmapDrawable;

    private int mScaleType = FIT_XY;
    private int mFillColor;
    private boolean mIsGced;
    private final Rect mDestRect = new Rect();

    // ==================== 构造方法 ====================

    public LuaBitmapDrawable(LuaContext context, String path, Drawable def) {
        mContext = context.getContext();
        // def 必须先于 resolveAndLoad：空路径等同步失败路径也要拿到错误图
        if (def != null) mErrorDrawable = def;
        context.regGc(this);
        resolveAndLoad(context, path);
    }

    public LuaBitmapDrawable(LuaContext context, String path) {
        mContext = context.getContext();
        context.regGc(this);
        resolveAndLoad(context, path);
    }

    public LuaBitmapDrawable(Context context, String path) {
        mContext = context;
        load(path);
    }

    public LuaBitmapDrawable(String path) {
        mContext = null;
        load(path);
    }

    // ==================== 加载逻辑 ====================

    private void resolveAndLoad(LuaContext luaContext, String path) {
        if (path == null || path.isEmpty()) {
            mRealDrawable = mErrorDrawable;
            return;
        }
        if (!path.startsWith("/") && !isNetworkUrl(path)) {
            path = luaContext.getLuaPath(path);
        }
        load(path);
    }

    private void load(String path) {
        if (path == null || path.isEmpty()) {
            mRealDrawable = mErrorDrawable;
            return;
        }
        // [.9 必须在 Glide 之前判]原始 .9.png 的黑边标记对 Glide 只是普通像素 ——
        //   它会"加载成功"但把黑边画出来、且不拉伸。所以不能等 onLoadFailed 兜底，
        //   要按文件名先分流（aapt 编译过的 .9 走的是资源路径，不经这里）。
        if (isNinePatchPath(path) && tryNinePatch(path)) return;
        if (mContext == null) {
            // 无 Context 用不了 Glide，且上面的 .9 分流已经试过了。
            mRealDrawable = mErrorDrawable;
            return;
        }

        Glide.with(mContext)
                .load(LuaBitmap.toGlideModel(path))
                .into(new CustomTarget<Drawable>() {
                    @Override
                    public void onResourceReady(@NonNull Drawable resource, @Nullable Transition<? super Drawable> transition) {
                        mRealDrawable = resource;
                        invalidateSelf();
                    }

                    @Override
                    public void onLoadFailed(@Nullable Drawable errorDrawable) {
                        // 兜底：Glide 失败的图仍可能是没带 .9 后缀的九宫格图。
                        //   NineBitmapDrawable 用 BitmapFactory 解本地文件、不经 Glide
                        //   （主线程调 submit().get() 必抛背景线程断言），此路真的可能成功。
                        if (!tryNinePatch(path)) {
                            mRealDrawable = errorDrawable != null ? errorDrawable : mErrorDrawable;
                            invalidateSelf();
                        }
                    }

                    @Override
                    public void onLoadCleared(@Nullable Drawable placeholder) {
                        mRealDrawable = null;
                        invalidateSelf();
                    }
                });
    }

    /** 文件名是否是 {@code *.9.png} 形态（大小写不敏感）。 */
    private static boolean isNinePatchPath(String path) {
        int queryIndex = path.indexOf('?');
        String pathWithoutQuery = queryIndex > 0 ? path.substring(0, queryIndex) : path;
        return pathWithoutQuery.regionMatches(true, pathWithoutQuery.length() - 6, ".9.png", 0, 6);
    }

    /**
     * 试着按九宫格解析；成功返回 {@code true}。
     *
     * <p>成败交回调用方、失败原因打进日志——失败若被吞掉，表现为"什么都没发生"，
     * 无法排查。
     */
    private boolean tryNinePatch(String path) {
        try {
            mNineBitmapDrawable = new NineBitmapDrawable(path);
            mRealDrawable = mNineBitmapDrawable;
            invalidateSelf();
            return true;
        } catch (IllegalArgumentException e) {
            // 预期情形："这张图不是 .9"（没有黑边标记 / 解不出 bitmap）。
            //   onLoadFailed 的兜底路径每张失败图都会走到这里，打堆栈会把 log 淹掉，
            //   所以只留一行。
            LuaConfig.logInfo("LuaBitmapDrawable: not a .9 (" + e.getMessage() + "): " + path);
            return false;
        } catch (Exception e) {
            // 意外情形（chunk 被平台拒、Glide 后台加载异常等）—— 这些要看堆栈。
            //   不能 catch (Exception ignored) {} 全吞掉，失败原因会被吞（如主线程调
            //   Glide submit().get() 触发背景线程断言）。
            LuaConfig.logError("LuaBitmapDrawable.ninePatch: " + path, e);
            return false;
        }
    }

    // ==================== 绘制 ====================

    @Override
    public void draw(@NonNull Canvas canvas) {
        if (mFillColor != 0) canvas.drawColor(mFillColor);

        if (mRealDrawable != null) {
            mRealDrawable.setBounds(calculateDestRect());
            mRealDrawable.draw(canvas);
        }
    }

    private Rect calculateDestRect() {
        Rect bounds = getBounds();
        int srcWidth = mRealDrawable.getIntrinsicWidth();
        int srcHeight = mRealDrawable.getIntrinsicHeight();
        if (srcWidth <= 0 || srcHeight <= 0) return bounds;

        int width = srcWidth;
        int height = srcHeight;

        switch (mScaleType) {
            case FIT_XY -> {
                width = bounds.width();
                height = bounds.height();
            }
            case MATRIX -> {
                // 原尺寸、不居中（矩阵变换由调用方自理）
            }
            case CENTER -> {
                // 不缩放，原尺寸绘制；居中对齐在下方统一处理
            }
            case CENTER_INSIDE -> {
                // 完整放入边界，不足时等比缩小、绝不放大
                float scale = Math.min(1f, Math.min(
                        (float) bounds.width() / width,
                        (float) bounds.height() / height
                ));
                width = (int) (width * scale);
                height = (int) (height * scale);
            }
            // FIT_START/FIT_CENTER/FIT_END 按小边等比缩放（对齐差异见下方 left/top 处理）；
            //   CENTER_CROP 暂未实现，走同一路径
            default -> {
                float scale = Math.min(
                        (float) bounds.width() / width,
                        (float) bounds.height() / height
                );
                width = (int) (width * scale);
                height = (int) (height * scale);
            }
        }

        int left = bounds.left;
        int top = bounds.top;
        if (mScaleType == CENTER || mScaleType == CENTER_INSIDE || mScaleType == FIT_CENTER) {
            left += (bounds.width() - width) / 2;
            top += (bounds.height() - height) / 2;
        } else if (mScaleType == FIT_END) {
            left = bounds.right - width;
            top = bounds.bottom - height;
        }
        // setBounds(Rect) 只读取四个 int，不持有引用 —— 复用 mDestRect 免去每帧分配。
        mDestRect.set(left, top, left + width, top + height);
        return mDestRect;
    }

    // ==================== 属性 ====================

    public int getWidth() {
        return mRealDrawable != null ? mRealDrawable.getIntrinsicWidth() : 0;
    }

    public int getHeight() {
        return mRealDrawable != null ? mRealDrawable.getIntrinsicHeight() : 0;
    }

    /**
     * 转发给真实 drawable —— {@code .9} 图的 padding 就是它的内容区。
     *
     * <p>不转发的话 {@code .9} 当 View 背景时 padding 被静默丢弃：Android 给 View
     * 设背景时靠 {@code getPadding} 决定内容区。
     */
    @Override
    public boolean getPadding(@NonNull Rect padding) {
        if (mRealDrawable != null) return mRealDrawable.getPadding(padding);
        return super.getPadding(padding);
    }

    @Override
    public int getIntrinsicWidth() {
        return getWidth();
    }

    @Override
    public int getIntrinsicHeight() {
        return getHeight();
    }

    public void setScaleType(int scaleType) {
        mScaleType = scaleType;
        invalidateSelf();
    }

    public void setFillColor(int fillColor) {
        mFillColor = fillColor;
        invalidateSelf();
    }

    public void setErrorDrawable(Drawable drawable) {
        mErrorDrawable = drawable;
    }

    @Override
    public void setAlpha(int alpha) {
        if (mRealDrawable != null) mRealDrawable.setAlpha(alpha);
    }

    @Override
    public void setColorFilter(ColorFilter cf) {
        if (mRealDrawable != null) mRealDrawable.setColorFilter(cf);
    }

    @Override
    public int getOpacity() {
        return PixelFormat.TRANSLUCENT;
    }

    // ==================== 生命周期 ====================

    @Override
    public void gc() {
        if (mNineBitmapDrawable != null) {
            mNineBitmapDrawable.gc();
            mNineBitmapDrawable = null;
        }
        mRealDrawable = null;
        mIsGced = true;
    }

    @Override
    public boolean isGc() {
        return mIsGced;
    }

    private static boolean isNetworkUrl(String path) {
        return path != null && (path.startsWith("http://") || path.startsWith("https://"));
    }
}