package org.luajvm.android.widget;

import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.drawable.Drawable;

import androidx.annotation.NonNull;

import org.luajvm.android.api.CallLuaFunction;
import org.luajvm.android.runtime.LuaConfig;

import org.luajvm.bind.JavaCall;
import org.luajvm.core.LuaError;
import org.luajvm.core.LuaFunction;

/**
 * Lua 可绘制对象，绘制逻辑在 Lua 中自定义。
 *
 * <p>两种写法都支持：Lua 函数直接画（每帧都用 canvas/paint/this 调它），
 * 或首次调用时返回一个只收 canvas 的函数，此后每帧只调那个返回值。
 */
@SuppressWarnings("unused")
public class LuaDrawable extends Drawable {
    private final LuaFunction mDraw;
    private final Paint mPaint;
    private LuaFunction mOnDraw;

    @CallLuaFunction(value = CallLuaFunction.Thread.DRAW,
            note = "draw(Canvas) 内调用，线程由绘制方决定")
    public LuaDrawable(LuaFunction drawFunc) {
        mDraw = drawFunc;
        mPaint = new Paint();
    }

    @Override
    public void draw(@NonNull Canvas canvas) {
        try {
            if (mOnDraw == null) {
                if (JavaCall.call(mDraw, canvas, mPaint, this) instanceof LuaFunction fn) {
                    mOnDraw = fn;
                }
            }
            if (mOnDraw != null) {
                JavaCall.call(mOnDraw, canvas);
            }
        } catch (LuaError e) {
            LuaConfig.logError("LuaDrawable", e);
        }
    }

    // Drawable 契约要求这两个 setter 触发重绘，否则 Lua 侧改了 alpha/滤镜画面不动；
    //   先比对新旧值，相等时跳过以免每帧改同一个值造成重绘循环。
    @Override
    public void setAlpha(int alpha) {
        if (mPaint.getAlpha() == alpha) return;
        mPaint.setAlpha(alpha);
        invalidateSelf();
    }

    @Override
    public void setColorFilter(ColorFilter colorFilter) {
        if (mPaint.getColorFilter() == colorFilter) return;
        mPaint.setColorFilter(colorFilter);
        invalidateSelf();
    }

    @Override
    public int getOpacity() {
        return PixelFormat.TRANSLUCENT;
    }

    public Paint getPaint() {
        return mPaint;
    }
}
