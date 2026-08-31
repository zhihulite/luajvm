package org.luajvm.android.widget;

import android.content.res.Resources;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ColorFilter;
import android.graphics.NinePatch;
import android.graphics.Rect;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.NinePatchDrawable;
import android.os.Looper;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.bumptech.glide.Glide;

import org.luajvm.android.LuaApplication;
import org.luajvm.android.util.LuaBitmap;
import org.luajvm.android.api.LuaGcable;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;

/**
 * 九宫格图片 Drawable：读**未经 aapt 编译的原始 {@code .9.png}**，
 * 扫黑边标记合成 {@code npTc} chunk，交平台 {@link NinePatchDrawable} 绘制。
 *
 * <p><b>为什么需要它</b>：Android 只认经 aapt 编译过的 {@code .9}（编译后带 {@code npTc}
 * chunk）。而 Lua 脚本是运行期从脚本目录/网络拿图的，那些 {@code .9.png} 从未过 aapt ——
 * 黑边标记对 {@code BitmapFactory} 只是普通像素，直接画会把黑边画出来、也不会拉伸。
 *
 * <p><b>合成 chunk 交给平台而不自己逐格绘制</b>：真实的 {@code .9} 允许左/上边线有
 * **多段**黑边，手写九宫格只支持单段拉伸区会画错；且平台
 * {@code NinePatchDrawable} 走 native {@link NinePatch}。多段拉伸、padding、
 * {@code getIntrinsic*} 全部由平台负责。
 *
 * <p><b>chunk 布局（平台 {@code Res_png_9patch} 的序列化格式）</b>：
 * <pre>
 * int8  wasDeserialized = 1
 * int8  numXDivs, numYDivs, numColors
 * int32 xDivsOffset, yDivsOffset          运行期不读，填 0
 * int32 paddingLeft, paddingRight, paddingTop, paddingBottom
 * int32 colorsOffset                      同上，填 0
 * int32[numXDivs] xDivs                   成对：[起,止)
 * int32[numYDivs] yDivs
 * int32[numColors] colors                 全填 NO_COLOR = 用 bitmap 内容绘制
 * </pre>
 *
 * <p><b>平台行为约束</b>：
 * <ul>
 *   <li>字节序必须是 {@link ByteOrder#nativeOrder()}；{@code NinePatch.isNinePatchChunk}
 *       判不了这一点 —— native 与 BIG_ENDIAN 两种它都返回 true（只校验字节数与
 *       div 数自洽，不看 int 的值），真正的判据是绘制结果的像素。</li>
 *   <li>{@code numColors} 必须等于 {@code (numXDivs/2+1) * (numYDivs/2+1)}，
 *       否则 {@code isNinePatchChunk} 直接判 false。</li>
 * </ul>
 */
public class NineBitmapDrawable extends Drawable implements LuaGcable {

    /** chunk 里表示"该格用 bitmap 内容绘制"的哨兵色（平台约定）。 */
    private static final int NO_COLOR = 0x00000001;

    private final NinePatchDrawable mDelegate;
    private final Bitmap mBitmap;
    private final Rect mPadding;
    /** mBitmap 是否由本类创建：只有裁边拷贝的那条路能 recycle，外部传入的不能。 */
    private final boolean mOwnsBitmap;
    private boolean mIsGced;

    /** 从路径读（本地走 BitmapFactory，远程走 Glide）。不是合法 {@code .9} 时抛异常。 */
    public NineBitmapDrawable(String path) throws Exception {
        // 自己 decode 出来的原图是临时的，裁边拷贝完立即 recycle，免得每张 .9
        //   在布局期多留一份等大位图等 GC。
        this(decodeSourceBitmap(path), true);
    }

    /** 从含 1px 黑边标记的原始 bitmap 构造。原图归调用方所有，本类不 recycle 它。 */
    public NineBitmapDrawable(Bitmap bitmap) {
        this(bitmap, false);
    }

    private NineBitmapDrawable(Bitmap source, boolean recycleSource) {
        if (source == null || source.getWidth() < 3 || source.getHeight() < 3) {
            if (recycleSource && source != null) source.recycle();
            throw new IllegalArgumentException("not a .9 bitmap: too small");
        }
        try {
            int[] xDivs = scanEdge(source, true);
            int[] yDivs = scanEdge(source, false);
            if (xDivs.length == 0 && yDivs.length == 0) {
                // 一条黑边都没有 ⇒ 不是 .9，交给调用方回落普通图（LuaBitmapDrawable 会兜住）
                throw new IllegalArgumentException("not a .9 bitmap: no stretch marker");
            }
            mPadding = scanPadding(source);
            mBitmap = Bitmap.createBitmap(source, 1, 1,
                    source.getWidth() - 2, source.getHeight() - 2);
            mOwnsBitmap = true;
            byte[] chunk = buildChunk(xDivs, yDivs, mPadding);
            if (!NinePatch.isNinePatchChunk(chunk)) {
                throw new IllegalStateException("synthesized chunk rejected by platform");
            }
            mDelegate = new NinePatchDrawable(Resources.getSystem(), mBitmap, chunk, mPadding, null);
        } finally {
            // 裁边拷贝已完成（或已失败），原图无论如何都不再被引用
            if (recycleSource && !source.isRecycled()) source.recycle();
        }
    }

    /**
     * 读原始 bitmap。
     *
     * <p><b>本地路径必须用 {@link BitmapFactory}，不能用 Glide 的
     * {@code submit().get()}</b> —— 后者内部 {@code Util.assertBackgroundThread()}，
     * 在主线程直接抛 {@code IllegalArgumentException("You must call this method on a
     * background thread")}。而 {@code LuaBitmapDrawable} 是在**布局期（主线程）**构造的，
     * 本地路径走 Glide 必然失败且异常被 {@code tryNinePatch} 捕获，表现为"什么都没发生"。
     *
     * <p>网络路径仍走 Glide（享受其缓存），但**必须由调用方保证在后台线程**；
     * 主线程传 http(s) 路径会抛，由调用方决定回落。
     */
    private static Bitmap decodeSourceBitmap(String path) throws Exception {
        boolean remote = path.startsWith("http://") || path.startsWith("https://");
        if (!remote) {
            // 先 bounds 再按目标（屏幕）采样：全尺寸主线程解码是布局期卡顿源。
            //   图不大于屏幕时 sample 恒 1，逐位对齐旧行为；超大图采样会把 1px 黑边
            //   平均掉而判为"不是 .9"，回落普通图（超大 .9 本身病态，可接受）
            BitmapFactory.Options bounds = new BitmapFactory.Options();
            bounds.inJustDecodeBounds = true;
            BitmapFactory.decodeFile(path, bounds);
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
                // 解不出来就是"这不是一张图/不是 .9" —— 抛 IllegalArgumentException 而不是
                //   包成 Exception：调用方（LuaBitmapDrawable.tryNinePatch）靠这个类型区分
                //   "预期的不匹配"（只记一行 info）与"意外故障"（要打堆栈）。
                throw new IllegalArgumentException("decodeFile returned null: " + path);
            }
            BitmapFactory.Options opts = new BitmapFactory.Options();
            opts.inSampleSize = sampleSizeForScreen(bounds);
            Bitmap sourceBitmap = BitmapFactory.decodeFile(path, opts);
            if (sourceBitmap == null) {
                throw new IllegalArgumentException("decodeFile returned null: " + path);
            }
            return sourceBitmap;
        }
        // 主线程走远程必被 Glide 的背景线程断言拒掉：先判掉，抛调用方按"预期不匹配"
        //   只记一行的 IllegalArgumentException，别白发一次请求再打整条堆栈。
        if (Looper.myLooper() == Looper.getMainLooper()) {
            throw new IllegalArgumentException(
                    "remote .9 needs a background thread: " + path);
        }
        try {
            return Glide.with(LuaApplication.getInstance())
                    .asBitmap()
                    .load(LuaBitmap.toGlideModel(path))
                    .submit()
                    .get();
        } catch (Exception e) {
            // 远程加载失败属意外（网络/线程），保留原因链
            throw new Exception("Failed to load NineBitmapDrawable: " + path, e);
        }
    }

    // Glide 式降采样：每次减半、两维都不低于屏幕（不足即停），九宫格不会画得超过屏幕
    private static int sampleSizeForScreen(BitmapFactory.Options bounds) {
        var dm = Resources.getSystem().getDisplayMetrics();
        int w = bounds.outWidth, h = bounds.outHeight, sample = 1;
        // 任一轴仍不小于屏幕就继续降：用 && 会让 8000x600 这类极端长宽比整轴判失败、
        //   原尺寸解码，恰好放过最容易 OOM 的形态
        while (w / (sample * 2) >= dm.widthPixels || h / (sample * 2) >= dm.heightPixels) {
            sample *= 2;
        }
        return sample;
    }

    // ==================== 黑边扫描 ====================

    /**
     * 扫一条边线，返回**成对**的 div 数组，坐标已减去 1px 边（对齐裁边后的 bitmap）。
     *
     * @param horizontal {@code true} 扫上边线得 x 方向拉伸区，{@code false} 扫左边线得 y 方向
     */
    private static int[] scanEdge(Bitmap bitmap, boolean horizontal) {
        int edgeLength = horizontal ? bitmap.getWidth() : bitmap.getHeight();
        var divs = new ArrayList<Integer>();
        boolean inRun = false;
        for (int i = 1; i < edgeLength - 1; i++) {
            boolean marked = isMark(horizontal ? bitmap.getPixel(i, 0) : bitmap.getPixel(0, i));
            if (marked && !inRun) {
                divs.add(i - 1);
                inRun = true;
            } else if (!marked && inRun) {
                divs.add(i - 1);
                inRun = false;
            }
        }
        if (inRun) divs.add(edgeLength - 2);
        // 手写拆箱，不走 Stream：布局期不引入装箱/管道开销
        int[] out = new int[divs.size()];
        for (int i = 0; i < out.length; i++) out[i] = divs.get(i);
        return out;
    }

    /** 扫右/下边线得内容区 padding；没标记时四边为 0。 */
    private static Rect scanPadding(Bitmap bitmap) {
        int width = bitmap.getWidth(), height = bitmap.getHeight();
        Rect padding = new Rect(0, 0, 0, 0);
        int firstMarkedPixel = -1, lastMarkedPixel = -1;
        for (int x = 1; x < width - 1; x++) {
            if (isMark(bitmap.getPixel(x, height - 1))) {
                if (firstMarkedPixel < 0) firstMarkedPixel = x;
                lastMarkedPixel = x;
            }
        }
        if (firstMarkedPixel >= 0) {
            padding.left = firstMarkedPixel - 1;
            padding.right = (width - 2) - lastMarkedPixel;
        }
        firstMarkedPixel = -1;
        lastMarkedPixel = -1;
        for (int y = 1; y < height - 1; y++) {
            if (isMark(bitmap.getPixel(width - 1, y))) {
                if (firstMarkedPixel < 0) firstMarkedPixel = y;
                lastMarkedPixel = y;
            }
        }
        if (firstMarkedPixel >= 0) {
            padding.top = firstMarkedPixel - 1;
            padding.bottom = (height - 2) - lastMarkedPixel;
        }
        return padding;
    }

    /** 标记像素判据：不透明的纯黑。 */
    private static boolean isMark(int px) {
        return Color.alpha(px) != 0 && (px | 0xFF000000) == 0xFF000000;
    }

    /** 见类注释里的 chunk 布局说明。字节序必须 nativeOrder。 */
    private static byte[] buildChunk(int[] xDivs, int[] yDivs, Rect padding) {
        int numColors = (xDivs.length / 2 + 1) * (yDivs.length / 2 + 1);
        int size = 4 + 4 * 2 + 4 * 4 + 4 + 4 * (xDivs.length + yDivs.length + numColors);
        ByteBuffer bb = ByteBuffer.allocate(size).order(ByteOrder.nativeOrder());
        bb.put((byte) 1);
        bb.put((byte) xDivs.length);
        bb.put((byte) yDivs.length);
        bb.put((byte) numColors);
        bb.putInt(0);
        bb.putInt(0);
        bb.putInt(padding.left);
        bb.putInt(padding.right);
        bb.putInt(padding.top);
        bb.putInt(padding.bottom);
        bb.putInt(0);
        for (int v : xDivs) bb.putInt(v);
        for (int v : yDivs) bb.putInt(v);
        for (int i = 0; i < numColors; i++) bb.putInt(NO_COLOR);
        return bb.array();
    }

    // ==================== Drawable ====================

    @Override
    public void draw(@NonNull Canvas canvas) {
        if (mIsGced) return;
        mDelegate.draw(canvas);
    }

    @Override
    protected void onBoundsChange(@NonNull Rect bounds) {
        mDelegate.setBounds(bounds);
    }

    @Override
    public int getIntrinsicWidth() {
        return mDelegate.getIntrinsicWidth();
    }

    @Override
    public int getIntrinsicHeight() {
        return mDelegate.getIntrinsicHeight();
    }

    @Override
    public boolean getPadding(@NonNull Rect padding) {
        return mDelegate.getPadding(padding);
    }

    @Override
    public void setAlpha(int alpha) {
        mDelegate.setAlpha(alpha);
    }

    @Override
    public void setColorFilter(@Nullable ColorFilter filter) {
        mDelegate.setColorFilter(filter);
    }

    @Override
    @SuppressWarnings("deprecation")
    public int getOpacity() {
        return mDelegate.getOpacity();
    }

    // ==================== LuaGcable ====================

    @Override
    public void gc() {
        // 只回收本类创建的裁边拷贝；外部传入的 bitmap 被回收后继续绘制即 native 崩溃
        if (mOwnsBitmap && !mBitmap.isRecycled()) mBitmap.recycle();
        mIsGced = true;
    }

    @Override
    public boolean isGc() {
        return mIsGced;
    }
}
