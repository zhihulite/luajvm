package org.luajvm.android.host;

import static android.accessibilityservice.AccessibilityServiceInfo.FLAG_REQUEST_TOUCH_EXPLORATION_MODE;
import static android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK;
import static android.view.accessibility.AccessibilityNodeInfo.ACTION_COLLAPSE;
import static android.view.accessibility.AccessibilityNodeInfo.ACTION_COPY;
import static android.view.accessibility.AccessibilityNodeInfo.ACTION_CUT;
import static android.view.accessibility.AccessibilityNodeInfo.ACTION_DISMISS;
import static android.view.accessibility.AccessibilityNodeInfo.ACTION_EXPAND;
import static android.view.accessibility.AccessibilityNodeInfo.ACTION_FOCUS;
import static android.view.accessibility.AccessibilityNodeInfo.ACTION_LONG_CLICK;
import static android.view.accessibility.AccessibilityNodeInfo.ACTION_PASTE;
import static android.view.accessibility.AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD;
import static android.view.accessibility.AccessibilityNodeInfo.ACTION_SCROLL_FORWARD;
import static android.view.accessibility.AccessibilityNodeInfo.ACTION_SELECT;
import static android.view.accessibility.AccessibilityNodeInfo.ACTION_SET_TEXT;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.AccessibilityServiceInfo;
import android.accessibilityservice.GestureDescription;
import android.app.Activity;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.hardware.HardwareBuffer;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.media.Image;
import android.media.ImageReader;
import android.media.projection.MediaProjection;
import android.media.projection.MediaProjectionManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.text.TextUtils;
import android.util.DisplayMetrics;
import android.view.Display;
import android.view.KeyEvent;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.RequiresApi;

import org.luajvm.android.api.LuaHost;
import org.luajvm.android.api.LuaHostDelegate;
import org.luajvm.android.runtime.LuaLog;
import java.nio.ByteBuffer;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Lua 无障碍服务  -  集成手势模拟与屏幕截图。
 * <p>
 * 手势模拟需要 API 24+ 并在服务配置中声明 {@code android:canPerformGestures="true"}。
 * 屏幕截图：API 31+ 使用内置 takeScreenshot，无需额外组件；
 * API 24-30 使用 MediaProjection，需在已有 Activity 中调用
 * {@link #requestProjection(Activity, int)} 请求权限。
 * <pre>{@code
 * // 在 LuaActivity 中：
 * LuaAccessibilityService.requestProjection(this, 100);
 *
 * // onActivityResult 中：
 * if (requestCode == 100) {
 *     LuaAccessibilityService.onProjectionResult(resultCode, data);
 * }
 * }</pre>
 */
@SuppressWarnings("unused")
public class LuaAccessibilityService extends AccessibilityService implements LuaHost {

    private static final String sHostName = "accessibility";
    // java-only: 进程级静态池，服务实例销毁不 shutdown（单线程低危，跨实例常驻复用）
    private static final ExecutorService sCaptureExecutor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "Screenshot-Worker");
        t.setPriority(Thread.NORM_PRIORITY - 1);
        return t;
    });
    private static volatile LuaAccessibilityService sInstance;
    private static String sLuaPath = "accessibility.lua";
    /**
     * MediaProjection 权限 Intent，由 Activity 授权后设置
     */
    private static volatile Intent sProjectionData;
    private final ServiceDelegate mDelegate = new ServiceDelegate(this, sHostName);

    /**
     * 承载 {@link LuaHost} 全部默认实现的 delegate。
     *
     * <p>这是本类为了当 Lua 宿主唯一必须给出的方法 —— {@code LuaHost} 的
     * {@code default} 方法把 30 个转发全部接过去了。
     */
    @Override
    public LuaHostDelegate getLuaDelegate() {
        return mDelegate;
    }
    private final Handler mMainHandler = new Handler(Looper.getMainLooper());

    // ==================== 单例 & 生命周期 ====================

    @Nullable
    public static LuaAccessibilityService getInstance() {
        return sInstance;
    }

    public static void setEnabled(Context context) {
        setEnabled(context, null);
    }

    public static void setEnabled(Context context, String luaPath) {
        if (luaPath != null) sLuaPath = luaPath;
        ComponentName cn = new ComponentName(context, LuaAccessibilityService.class);
        context.getPackageManager().setComponentEnabledSetting(cn,
                PackageManager.COMPONENT_ENABLED_STATE_ENABLED, PackageManager.DONT_KILL_APP);
        context.startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS));
    }

    public static void setDisabled(Context context) {
        ComponentName cn = new ComponentName(context, LuaAccessibilityService.class);
        context.getPackageManager().setComponentEnabledSetting(cn,
                PackageManager.COMPONENT_ENABLED_STATE_DISABLED, PackageManager.DONT_KILL_APP);
        if (sInstance != null) sInstance.stopSelf();
    }

    @RequiresApi(Build.VERSION_CODES.LOLLIPOP)
    public static void requestProjection(@NonNull Activity activity, int requestCode) {
        MediaProjectionManager mgr = (MediaProjectionManager)
                activity.getSystemService(Context.MEDIA_PROJECTION_SERVICE);
        if (mgr != null) {
            activity.startActivityForResult(mgr.createScreenCaptureIntent(), requestCode);
        }
    }

    public static void onProjectionResult(int resultCode, @Nullable Intent data) {
        sProjectionData = resultCode == Activity.RESULT_OK && data != null ? data : null;
    }

    // ==================== 无障碍事件 ====================

    public static boolean hasProjectionData() {
        return sProjectionData != null;
    }

    @NonNull
    private static Bitmap imageToBitmap(@NonNull Image image) {
        int width = image.getWidth();
        int height = image.getHeight();
        Image.Plane[] planes = image.getPlanes();
        ByteBuffer buffer = planes[0].getBuffer();
        int pixelStride = planes[0].getPixelStride();
        int rowStride = planes[0].getRowStride();
        int rowPadding = rowStride - pixelStride * width;

        Bitmap raw = Bitmap.createBitmap(
                width + rowPadding / pixelStride, height, Bitmap.Config.ARGB_8888);
        buffer.rewind();
        raw.copyPixelsFromBuffer(buffer);
        if (rowPadding == 0) return raw;

        Bitmap cropped = Bitmap.createBitmap(raw, 0, 0, width, height);
        raw.recycle();
        return cropped;
    }

    @Override
    public void onCreate() {
        super.onCreate();
        sInstance = this;
        mDelegate.init(sLuaPath, new Object[0]);
    }

    @Override
    public void onDestroy() {
        mDelegate.destroy();
        sInstance = null;
        // 授权 Intent 属服务生命周期：销毁即失效，避免下次会话复用已失效的 projection 凭据
        sProjectionData = null;
        super.onDestroy();
    }

    // ==================== 服务配置 ====================

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        // 与 default 分支互斥：runBooleanFunc 已对每个事件调过 onAccessibilityEvent，
        //   未匹配事件再落 default 会二次执行（如 TYPE_WINDOW_CONTENT_CHANGED）
        if (mDelegate.runBooleanFunc("onAccessibilityEvent", event)) return;
        switch (event.getEventType()) {
            case AccessibilityEvent.TYPE_VIEW_CLICKED -> mDelegate.runFunc("onViewClicked", event);
            case AccessibilityEvent.TYPE_VIEW_LONG_CLICKED ->
                    mDelegate.runFunc("onViewLongClicked", event);
            case AccessibilityEvent.TYPE_VIEW_SELECTED ->
                    mDelegate.runFunc("onViewSelected", event);
            case AccessibilityEvent.TYPE_VIEW_FOCUSED -> mDelegate.runFunc("onViewFocused", event);
            case AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED ->
                    mDelegate.runFunc("onViewTextChanged", event);
            case AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED ->
                    mDelegate.runFunc("onWindowStateChanged", event);
            case AccessibilityEvent.TYPE_NOTIFICATION_STATE_CHANGED ->
                    mDelegate.runFunc("onNotificationStateChanged", event);
            case AccessibilityEvent.TYPE_VIEW_HOVER_ENTER ->
                    mDelegate.runFunc("onViewHoverEnter", event);
            case AccessibilityEvent.TYPE_VIEW_HOVER_EXIT ->
                    mDelegate.runFunc("onViewHoverExit", event);
            default -> { }
        }
    }

    // ==================== 全局操作 ====================

    @Override
    public void onInterrupt() {
        mDelegate.runFunc("onInterrupt");
    }

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        mDelegate.runFunc("onServiceConnected");
    }

    @Override
    public boolean onKeyEvent(KeyEvent event) {
        if (mDelegate.runBooleanFunc("onKeyEvent", event)) return true;
        return super.onKeyEvent(event);
    }

    public void setTouchMode(boolean enable) {
        AccessibilityServiceInfo info = getServiceInfo();
        if (info == null) return;
        if (enable) info.flags |= FLAG_REQUEST_TOUCH_EXPLORATION_MODE;
        else info.flags &= ~FLAG_REQUEST_TOUCH_EXPLORATION_MODE;
        setServiceInfo(info);
    }

    public void toHome() {
        performGlobalAction(GLOBAL_ACTION_HOME);
    }

    public void toRecents() {
        performGlobalAction(GLOBAL_ACTION_RECENTS);
    }

    public void toNotifications() {
        performGlobalAction(GLOBAL_ACTION_NOTIFICATIONS);
    }

    // ==================== 节点操作 ====================

    public void toQuickSettings() {
        performGlobalAction(GLOBAL_ACTION_QUICK_SETTINGS);
    }

    /**
     * API 26+
     */
    public void toPowerDialog() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            performGlobalAction(GLOBAL_ACTION_POWER_DIALOG);
        }
    }

    /**
     * API 28+
     */
    public void lockScreen() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            performGlobalAction(GLOBAL_ACTION_LOCK_SCREEN);
        }
    }

    /**
     * API 24+
     */
    public void toSplitScreen() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            performGlobalAction(GLOBAL_ACTION_TOGGLE_SPLIT_SCREEN);
        }
    }

    private boolean act(@Nullable AccessibilityNodeInfo node, int action) {
        return node != null && node.performAction(action);
    }

    private boolean act(@Nullable AccessibilityNodeInfo node, int action, @NonNull Bundle args) {
        return node != null && node.performAction(action, args);
    }

    public boolean click(AccessibilityNodeInfo node) {
        return act(node, ACTION_CLICK);
    }

    public boolean longClick(AccessibilityNodeInfo node) {
        return act(node, ACTION_LONG_CLICK);
    }

    public boolean focus(AccessibilityNodeInfo node) {
        return act(node, ACTION_FOCUS);
    }

    public boolean clearFocus(AccessibilityNodeInfo node) {
        return act(node, AccessibilityNodeInfo.ACTION_CLEAR_FOCUS);
    }

    public boolean select(AccessibilityNodeInfo node) {
        return act(node, ACTION_SELECT);
    }

    public boolean clearSelection(AccessibilityNodeInfo node) {
        return act(node, AccessibilityNodeInfo.ACTION_CLEAR_SELECTION);
    }

    public boolean scrollForward(AccessibilityNodeInfo node) {
        return act(node, ACTION_SCROLL_FORWARD);
    }

    public boolean scrollBackward(AccessibilityNodeInfo node) {
        return act(node, ACTION_SCROLL_BACKWARD);
    }

    public boolean copy(AccessibilityNodeInfo node) {
        return act(node, ACTION_COPY);
    }

    public boolean paste(AccessibilityNodeInfo node) {
        return act(node, ACTION_PASTE);
    }

    public boolean cut(AccessibilityNodeInfo node) {
        return act(node, ACTION_CUT);
    }

    /**
     * 展开
     */
    public boolean expand(AccessibilityNodeInfo node) {
        return act(node, ACTION_EXPAND);
    }

    /**
     * 折叠
     */
    public boolean collapse(AccessibilityNodeInfo node) {
        return act(node, ACTION_COLLAPSE);
    }

    /**
     * 关闭
     */
    public boolean dismiss(AccessibilityNodeInfo node) {
        return act(node, ACTION_DISMISS);
    }

    /**
     * 向上滚动
     */
    @RequiresApi(Build.VERSION_CODES.M)
    public boolean scrollUp(AccessibilityNodeInfo node) {
        if (node == null) return false;
        return node.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_UP.getId());
    }

    /**
     * 向下滚动
     */
    @RequiresApi(Build.VERSION_CODES.M)
    public boolean scrollDown(AccessibilityNodeInfo node) {
        if (node == null) return false;
        return node.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_DOWN.getId());
    }

    // ==================== 节点查询 ====================

    /**
     * 向左滚动
     */
    @RequiresApi(Build.VERSION_CODES.M)
    public boolean scrollLeft(AccessibilityNodeInfo node) {
        if (node == null) return false;
        return node.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_LEFT.getId());
    }

    /**
     * 向右滚动
     */
    @RequiresApi(Build.VERSION_CODES.M)
    public boolean scrollRight(AccessibilityNodeInfo node) {
        if (node == null) return false;
        return node.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_RIGHT.getId());
    }

    public boolean setText(AccessibilityNodeInfo node, CharSequence text) {
        if (node == null) return false;
        Bundle args = new Bundle();
        args.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text);
        return node.performAction(ACTION_SET_TEXT, args);
    }

    public boolean setSelection(AccessibilityNodeInfo node, int start, int end) {
        if (node == null) return false;
        Bundle args = new Bundle();
        args.putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_START_INT, start);
        args.putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_END_INT, end);
        return node.performAction(AccessibilityNodeInfo.ACTION_SET_SELECTION, args);
    }

    /**
     * 当前活跃窗口根节点
     */
    @Nullable
    public AccessibilityNodeInfo getRootNode() {
        return getRootInActiveWindow();
    }

    /**
     * 按文本查找节点（首个匹配）
     */
    @Nullable
    public AccessibilityNodeInfo findNodeByText(@NonNull String text) {
        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root == null) return null;
        List<AccessibilityNodeInfo> nodes = root.findAccessibilityNodeInfosByText(text);
        return nodes.isEmpty() ? null : nodes.get(0);
    }

    /**
     * 按文本查找所有节点
     */
    @Nullable
    public List<AccessibilityNodeInfo> findNodesByText(@NonNull String text) {
        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root == null) return null;
        return root.findAccessibilityNodeInfosByText(text);
    }

    /**
     * 按视图 ID 查找节点（首个匹配，API 18+）
     */
    @Nullable
    public AccessibilityNodeInfo findNodeById(@NonNull String id) {
        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root == null) return null;
        List<AccessibilityNodeInfo> nodes = root.findAccessibilityNodeInfosByViewId(id);
        return nodes.isEmpty() ? null : nodes.get(0);
    }

    /**
     * 按视图 ID 查找所有节点（API 18+）
     */
    @Nullable
    public List<AccessibilityNodeInfo> findNodesById(@NonNull String id) {
        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root == null) return null;
        return root.findAccessibilityNodeInfosByViewId(id);
    }

    // ==================== 手势模拟（API 24+） ====================

    /**
     * 节点可读文本：contentDescription -> text -> hintText(API 26+)
     */
    @Nullable
    public String getNodeText(@Nullable AccessibilityNodeInfo node) {
        if (node == null) return null;
        CharSequence cd = node.getContentDescription();
        if (!TextUtils.isEmpty(cd)) return cd.toString();
        CharSequence text = node.getText();
        if (!TextUtils.isEmpty(text)) return text.toString();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            CharSequence hint = node.getHintText();
            if (!TextUtils.isEmpty(hint)) return hint.toString();
        }
        return null;
    }

    /**
     * 事件可读文本：contentDescription -> text[0]
     */
    @Nullable
    public String getEventText(@Nullable AccessibilityEvent event) {
        if (event == null) return null;
        CharSequence cd = event.getContentDescription();
        if (!TextUtils.isEmpty(cd)) return cd.toString();
        List<CharSequence> text = event.getText();
        if (!text.isEmpty()) return text.get(0).toString();
        return null;
    }

    /**
     * 节点在屏幕上的矩形边界
     */
    @Nullable
    public Rect getNodeBounds(@Nullable AccessibilityNodeInfo node) {
        if (node == null) return null;
        Rect bounds = new Rect();
        node.getBoundsInScreen(bounds);
        return bounds;
    }

    /**
     * 节点中心点坐标 [x, y]
     */
    @Nullable
    public int[] getNodeCenter(@Nullable AccessibilityNodeInfo node) {
        Rect bounds = getNodeBounds(node);
        if (bounds == null) return null;
        return new int[]{bounds.centerX(), bounds.centerY()};
    }

    @RequiresApi(Build.VERSION_CODES.N)
    public boolean tap(int x, int y) {
        return gesture(buildClick(x, y));
    }

    @RequiresApi(Build.VERSION_CODES.N)
    public boolean press(int x, int y) {
        return press(x, y, 500);
    }

    @RequiresApi(Build.VERSION_CODES.N)
    public boolean press(int x, int y, long duration) {
        return gesture(buildPress(x, y, duration));
    }

    @RequiresApi(Build.VERSION_CODES.N)
    public boolean swipe(int x1, int y1, int x2, int y2) {
        return swipe(x1, y1, x2, y2, 300);
    }

    @RequiresApi(Build.VERSION_CODES.N)
    public boolean swipe(int x1, int y1, int x2, int y2, long duration) {
        return gesture(buildSwipe(x1, y1, x2, y2, duration));
    }

    // ==================== 手势构建 ====================

    @RequiresApi(Build.VERSION_CODES.N)
    public boolean swipe(@NonNull Path path, long duration) {
        return gesture(buildStroke(path, duration));
    }

    @RequiresApi(Build.VERSION_CODES.N)
    public boolean pinch(int cx, int cy, int startDist, int endDist, long duration) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) return false;
        int halfStart = startDist / 2;
        int halfEnd = endDist / 2;

        Path finger1 = new Path();
        finger1.moveTo(cx - halfStart, cy);
        finger1.lineTo(cx - halfEnd, cy);

        Path finger2 = new Path();
        finger2.moveTo(cx + halfStart, cy);
        finger2.lineTo(cx + halfEnd, cy);

        GestureDescription.StrokeDescription stroke1 =
                new GestureDescription.StrokeDescription(finger1, 0, duration);
        GestureDescription.StrokeDescription stroke2 =
                new GestureDescription.StrokeDescription(finger2, 0, duration);
        GestureDescription.Builder builder = new GestureDescription.Builder();
        builder.addStroke(stroke1);
        builder.addStroke(stroke2);
        return dispatchGesture(builder.build(), null, null);
    }

    @RequiresApi(Build.VERSION_CODES.N)
    public boolean gesture(@NonNull GestureDescription gesture) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) return false;
        return dispatchGesture(gesture, null, null);
    }

    @RequiresApi(Build.VERSION_CODES.N)
    public boolean gesture(@NonNull GestureDescription gesture,
                           @Nullable GestureResultCallback callback,
                           @Nullable Handler handler) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) return false;
        return dispatchGesture(gesture, callback, handler);
    }

    // ==================== 屏幕截图 ====================

    @NonNull
    @RequiresApi(Build.VERSION_CODES.N)
    public GestureDescription buildClick(int x, int y) {
        Path path = new Path();
        path.moveTo(x, y);
        return buildStroke(path, 50);
    }

    @NonNull
    @RequiresApi(Build.VERSION_CODES.N)
    public GestureDescription buildPress(int x, int y, long duration) {
        Path path = new Path();
        path.moveTo(x, y);
        return buildStroke(path, duration);
    }

    @NonNull
    @RequiresApi(Build.VERSION_CODES.N)
    public GestureDescription buildSwipe(int x1, int y1, int x2, int y2, long duration) {
        Path path = new Path();
        path.moveTo(x1, y1);
        path.lineTo(x2, y2);
        return buildStroke(path, duration);
    }

    @NonNull
    @RequiresApi(Build.VERSION_CODES.N)
    public GestureDescription buildStroke(@NonNull Path path, long duration) {
        GestureDescription.StrokeDescription stroke =
                new GestureDescription.StrokeDescription(path, 0, duration);
        GestureDescription.Builder builder = new GestureDescription.Builder();
        builder.addStroke(stroke);
        return builder.build();
    }

    public void captureScreen(@NonNull ScreenshotCallback callback) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            captureViaTakeScreenshot(callback);
        } else {
            captureViaMediaProjection(callback);
        }
    }

    /**
     * 主线程投递 Lua 回调：post 出去的 lambda 已不在原来的 try/catch 里，
     * Lua 回调抛错会从主线程 Looper 逃出杀掉进程，故在此就地兜住转 onError。
     */
    private void postSuccess(@NonNull ScreenshotCallback callback, Bitmap bitmap) {
        mMainHandler.post(() -> {
            try {
                callback.onSuccess(bitmap);
            } catch (Exception e) {
                LuaLog.getInstance().addError("Screenshot", e);
                postErrorNow(callback, Objects.requireNonNullElse(e.getMessage(), e.toString()));
            }
        });
    }

    private void postError(@NonNull ScreenshotCallback callback, String msg) {
        mMainHandler.post(() -> postErrorNow(callback, msg));
    }

    private void postErrorNow(@NonNull ScreenshotCallback callback, String msg) {
        try {
            callback.onError(msg);
        } catch (Exception e) {
            LuaLog.getInstance().addError("Screenshot", e);
        }
    }

    @RequiresApi(Build.VERSION_CODES.S)
    private void captureViaTakeScreenshot(@NonNull ScreenshotCallback callback) {
        // java diff: 31+ 原生回调跑在 sCaptureExecutor 工作线程，与 <31 路径统一 post 主线程，
        //   Lua 侧 ScreenshotCallback 触 View 不再依赖后台线程
        takeScreenshot(Display.DEFAULT_DISPLAY, sCaptureExecutor,
                new TakeScreenshotCallback() {
                    @Override
                    public void onSuccess(@NonNull ScreenshotResult screenshotResult) {
                        try (HardwareBuffer hb = screenshotResult.getHardwareBuffer()) {
                            Bitmap hwBitmap = Bitmap.wrapHardwareBuffer(hb, screenshotResult.getColorSpace());
                            if (hwBitmap == null) {
                                postError(callback, "wrapHardwareBuffer returned null");
                                return;
                            }
                            Bitmap software = hwBitmap.copy(Bitmap.Config.ARGB_8888, false);
                            if (software != null) {
                                postSuccess(callback, software);
                                hwBitmap.recycle();
                            } else {
                                postSuccess(callback, hwBitmap);
                            }
                        } catch (Exception e) {
                            LuaLog.getInstance().addError("Screenshot", e);
                            String msg = Objects.requireNonNullElse(e.getMessage(), e.toString());
                            postError(callback, msg);
                        }
                    }

                    @Override
                    public void onFailure(int errorCode) {
                        postError(callback, "takeScreenshot failed: " + errorCode);
                    }
                });
    }

    private void captureViaMediaProjection(@NonNull ScreenshotCallback callback) {
        if (sProjectionData == null) {
            postErrorNow(callback, "No projection permission, call requestProjection() in Activity first");
            return;
        }

        sCaptureExecutor.execute(() -> {
            MediaProjection projection = null;
            VirtualDisplay display = null;
            ImageReader reader = null;

            try {
                WindowManager wm = (WindowManager) getSystemService(Context.WINDOW_SERVICE);
                DisplayMetrics metrics = new DisplayMetrics();
                wm.getDefaultDisplay().getRealMetrics(metrics);
                int w = metrics.widthPixels, h = metrics.heightPixels, densityDpi = metrics.densityDpi;

                reader = ImageReader.newInstance(w, h, PixelFormat.RGBA_8888, 1);
                MediaProjectionManager mgr = (MediaProjectionManager)
                        getSystemService(Context.MEDIA_PROJECTION_SERVICE);
                if (mgr == null) {
                    postError(callback, "MediaProjectionManager unavailable");
                    return;
                }

                projection = mgr.getMediaProjection(Activity.RESULT_OK, sProjectionData);
                if (projection == null) {
                    postError(callback, "MediaProjection is null, permission may be revoked");
                    return;
                }

                display = projection.createVirtualDisplay("screenshot",
                        w, h, densityDpi,
                        DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                        reader.getSurface(), null, null);

                Image image = null;
                for (int i = 0; i < 40; i++) {
                    try {
                        Thread.sleep(5);
                    } catch (InterruptedException ignored) {
                    }
                    image = reader.acquireLatestImage();
                    if (image != null) break;
                }

                if (image == null) {
                    postError(callback, "Failed to acquire screen image");
                    return;
                }

                Bitmap bitmap = imageToBitmap(image);
                image.close();
                postSuccess(callback, bitmap);

            } catch (Exception e) {
                LuaLog.getInstance().addError("Screenshot", e);
                postError(callback, Objects.requireNonNullElse(e.getMessage(), e.toString()));
            } finally {
                if (display != null) display.release();
                if (reader != null) reader.close();
                if (projection != null) projection.stop();
            }
        });
    }


    // ==================== 委托方法 ====================

    public boolean runBooleanFunc(String name, Object... args) {
        return mDelegate.runBooleanFunc(name, args);
    }

    public interface ScreenshotCallback {
        void onSuccess(@NonNull Bitmap bitmap);

        void onError(@NonNull String msg);
    }
}
