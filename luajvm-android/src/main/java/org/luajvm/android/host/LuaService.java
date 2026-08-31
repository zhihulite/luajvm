package org.luajvm.android.host;

import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.os.Binder;
import android.os.IBinder;

import org.luajvm.android.api.LuaHost;
import org.luajvm.android.api.LuaHostDelegate;

/**
 * Lua 服务：委托 {@link ServiceDelegate}，本类只做 Service 生命周期转发。
 */
@SuppressWarnings("unused")
public class LuaService extends Service implements LuaHost {
    private static final String sHostName = "service";
    // volatile：onCreate（主线程）写、外部线程经 getService() 读
    private static volatile LuaService sInstance;
    private static String sLuaPath = "service.lua";
    private final ServiceDelegate mDelegate = new ServiceDelegate(this, sHostName);

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

    public static LuaService getInstance() {
        return sInstance;
    }

    public static void setEnabled(Context context) {
        setEnabled(context, null);
    }

    /** 只设脚本路径不拉起 Service：bind 路径用它，避免后台 startService 的 ISE 连带回滚路径。 */
    public static void setLuaPath(String luaPath) {
        if (luaPath != null) sLuaPath = luaPath;
    }

    /**
     * API 26+ 后台限制：app 不在前台时 startService 抛 IllegalStateException——
     *   后台拉起需要前台服务（须自带通知，本方法不擅自创建）；
     *   调用方 BaseDelegate 捕获该异常转 sendError。
     *   startService 失败时回滚 sLuaPath：不回滚的话下一次 bindService 或系统重启
     *   Service 会跑成这次没启起来的那个脚本。
     */
    public static void setEnabled(Context context, String luaPath) {
        String previous = sLuaPath;
        if (luaPath != null) sLuaPath = luaPath;
        try {
            context.startService(new Intent(context, LuaService.class));
        } catch (RuntimeException e) {
            sLuaPath = previous;
            throw e;
        }
    }

    public static void setDisabled(Context context) {
        context.stopService(new Intent(context, LuaService.class));
    }

    // ==================== 生命周期 ====================

    @Override
    public void onCreate() {
        super.onCreate();
        sInstance = this;
        mDelegate.init(sLuaPath, new Object[0]);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        mDelegate.runFunc("onStartCommand", intent, flags, startId);
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        mDelegate.destroy();
        sInstance = null;
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return new LuaBinder();
    }

    public boolean runBooleanFunc(String name, Object... args) {
        return mDelegate.runBooleanFunc(name, args);
    }


    public ServiceDelegate getDelegate() {
        return mDelegate;
    }

    // ==================== Binder ====================

    public class LuaBinder extends Binder {
        public LuaService getService() {
            return LuaService.this;
        }
    }
}
