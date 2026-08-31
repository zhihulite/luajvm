package org.luajvm.android.host;

import android.app.WallpaperManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.service.wallpaper.WallpaperService;
import android.view.SurfaceHolder;

import org.luajvm.android.api.LuaHost;
import org.luajvm.android.api.LuaHostDelegate;

/**
 * Lua 壁纸服务。
 */
@SuppressWarnings("unused")
public class LuaWallpaperService extends WallpaperService implements LuaHost {

    private static final String sHostName = "wallpaper";
    // volatile：onCreate（主线程）写、外部线程经 getService() 读
    private static volatile LuaWallpaperService sInstance;
    private static String sLuaPath = "wallpaper.lua";

    private final ServiceDelegate mDelegate = new ServiceDelegate(this, sHostName);
    private SurfaceHolder mHolder;

    /**
     * 承载 {@link LuaHost} 全部默认实现的 delegate。
     *
     * <p>{@code LuaHost} 的 {@code default} 方法把转发都接了过去，
     * 宿主必须显式给出的只剩本方法与 {@link #onScriptError}。
     */
    @Override
    public LuaHostDelegate getLuaDelegate() {
        return mDelegate;
    }

    @Override
    public void onScriptError(String tag, Exception error) {
        // 错误已由引擎统一进 LuaLog，本宿主无界面，无实例级呈现
    }

    // ==================== 单例 & 开关 ====================

    public static LuaWallpaperService getInstance() {
        return sInstance;
    }

    public static void setEnabled(Context context) {
        setEnabled(context, null);
    }

    public static void setEnabled(Context context, String luaPath) {
        if (luaPath != null) sLuaPath = luaPath;
        ComponentName component = new ComponentName(context, LuaWallpaperService.class);
        context.getPackageManager().setComponentEnabledSetting(component,
                PackageManager.COMPONENT_ENABLED_STATE_ENABLED, PackageManager.DONT_KILL_APP);
        context.startActivity(new Intent(WallpaperManager.ACTION_CHANGE_LIVE_WALLPAPER)
                .putExtra(WallpaperManager.EXTRA_LIVE_WALLPAPER_COMPONENT, component));
    }

    public static void setDisabled(Context context) {
        ComponentName component = new ComponentName(context, LuaWallpaperService.class);
        context.getPackageManager().setComponentEnabledSetting(component,
                PackageManager.COMPONENT_ENABLED_STATE_DISABLED, PackageManager.DONT_KILL_APP);
        if (sInstance != null) sInstance.stopSelf();
    }

    public SurfaceHolder getHolder() {
        return mHolder;
    }

    // ==================== 生命周期 ====================

    @Override
    public void onCreate() {
        super.onCreate();
        sInstance = this;
        mDelegate.init(sLuaPath, new Object[0]);
    }

    @Override
    public Engine onCreateEngine() {
        return new LuaWallpaperEngine();
    }

    @Override
    public void onDestroy() {
        mDelegate.destroy();
        sInstance = null;
        super.onDestroy();
    }

    public boolean runBooleanFunc(String name, Object... args) {
        return mDelegate.runBooleanFunc(name, args);
    }


    // ==================== 壁纸引擎 ====================

    private class LuaWallpaperEngine extends Engine {
        @Override
        public void onVisibilityChanged(boolean visible) {
            mDelegate.runFunc("onVisibilityChanged", visible);
        }

        @Override
        public void onSurfaceCreated(SurfaceHolder holder) {
            super.onSurfaceCreated(holder);
            mHolder = holder;
            mDelegate.runFunc("onSurfaceCreated", holder);
        }

        @Override
        public void onSurfaceDestroyed(SurfaceHolder holder) {
            super.onSurfaceDestroyed(holder);
            mDelegate.runFunc("onSurfaceDestroyed", holder);
            mHolder = null;
        }

        @Override
        public void onSurfaceChanged(SurfaceHolder holder, int format, int width, int height) {
            super.onSurfaceChanged(holder, format, width, height);
            mHolder = holder;
            mDelegate.runFunc("onSurfaceChanged", holder, format, width, height);
        }

        @Override
        public void onSurfaceRedrawNeeded(SurfaceHolder holder) {
            super.onSurfaceRedrawNeeded(holder);
            mHolder = holder;
            mDelegate.runFunc("onSurfaceRedrawNeeded", holder);
        }
    }
}
