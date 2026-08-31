package org.luajvm.android.host;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.provider.Settings;
import android.service.notification.NotificationListenerService;
import android.service.notification.StatusBarNotification;
import android.speech.tts.TextToSpeech;
import android.speech.tts.UtteranceProgressListener;
import android.text.TextUtils;

import org.luajvm.android.api.LuaHost;
import org.luajvm.android.api.LuaHostDelegate;
import org.luajvm.android.runtime.LuaConfig;

/**
 * Lua 通知监听服务。
 */
@SuppressWarnings("unused")
public class LuaNotificationListenerService extends NotificationListenerService implements LuaHost {
    private static final String sHostName = "notification";
    // volatile：onCreate（主线程）写、外部线程经 getService() 读
    private static volatile LuaNotificationListenerService sInstance;
    // 通道连接态：sInstance 活着不代表通道还连着（用户撤权/系统重连都会断开）
    private static volatile boolean sConnected;
    private static String sLuaPath = "notification.lua";
    private final ServiceDelegate mDelegate = new ServiceDelegate(this, sHostName);
    private TextToSpeech mTts;

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

    // ==================== 单例 & 开关 ====================

    public static LuaNotificationListenerService getInstance() {
        return sInstance;
    }

    public static void setEnabled(Context context) {
        setEnabled(context, null);
    }

    public static void setEnabled(Context context, String luaPath) {
        if (luaPath != null) sLuaPath = luaPath;
        ComponentName component = new ComponentName(context, LuaNotificationListenerService.class);
        PackageManager pm = context.getPackageManager();
        pm.setComponentEnabledSetting(component,
                PackageManager.COMPONENT_ENABLED_STATE_ENABLED,
                PackageManager.DONT_KILL_APP);
        context.startActivity(new Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS));
    }

    public static void setDisabled(Context context) {
        ComponentName component = new ComponentName(context, LuaNotificationListenerService.class);
        context.getPackageManager().setComponentEnabledSetting(component,
                PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
                PackageManager.DONT_KILL_APP);
        if (sInstance != null) sInstance.stopSelf();
    }

    // ==================== 生命周期 ====================

    @Override
    public void onCreate() {
        super.onCreate();
        sInstance = this;
        mDelegate.init(sLuaPath, new Object[0]);
        initTts();
    }

    @Override
    public void onDestroy() {
        if (mTts != null) mTts.shutdown();
        mDelegate.destroy();
        sConnected = false;
        sInstance = null;
        super.onDestroy();
    }

    // ==================== TTS ====================

    // 两参构造由框架选默认引擎，等价于显式读 TTS_DEFAULT_SYNTH 再传三参构造
    private void initTts() {
        if (mTts != null) mTts.shutdown();
        mTts = new TextToSpeech(this, status -> LuaConfig.log("TTS init: " + status));
        mTts.setOnUtteranceProgressListener(new UtteranceProgressListener() {
            @Override
            public void onStart(String utteranceId) {
                mDelegate.runFunc("onTTSStart", utteranceId);
            }

            @Override
            public void onDone(String utteranceId) {
                mDelegate.runFunc("onTTSDone", utteranceId);
            }

            @Override
            public void onError(String utteranceId) {
                mDelegate.runFunc("onTTSError", utteranceId);
            }
        });
    }

    public void speak(String text) {
        if (TextUtils.isEmpty(text)) return;
        mTts.speak(text, TextToSpeech.QUEUE_FLUSH, new Bundle(), "");
    }

    public void stop() {
        mTts.stop();
    }

    public boolean isSpeaking() {
        return mTts.isSpeaking();
    }

    // ==================== 通知事件 ====================

    @Override
    public void onNotificationPosted(StatusBarNotification sbn) {
        mDelegate.runFunc("onNotificationPosted", sbn);
    }

    @Override
    public void onNotificationRemoved(StatusBarNotification sbn) {
        mDelegate.runFunc("onNotificationRemoved", sbn);
    }

    @Override
    public void onListenerConnected() {
        super.onListenerConnected();
        sConnected = true;
        mDelegate.runFunc("onListenerConnected");
    }

    @Override
    public void onListenerDisconnected() {
        super.onListenerDisconnected();
        sConnected = false;
        mDelegate.runFunc("onListenerDisconnected");
        // sInstance 只归 onCreate/onDestroy 管：系统重连时服务仍存活，置空会让
        //   getInstance() 恒 null。断开期间由 sConnected 挡住 NLS 调用
    }

    /**
     * 通知监听通道是否处于已连接状态。断开期间 getActiveNotifications 一类调用会抛
     * SecurityException，Lua 侧应先判本方法再调。
     */
    public static boolean isConnected() {
        return sConnected;
    }

    // ==================== 便捷方法 ====================

}
