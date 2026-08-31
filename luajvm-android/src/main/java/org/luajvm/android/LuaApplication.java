package org.luajvm.android;

import android.app.Application;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Looper;
import android.util.Log;

import androidx.preference.PreferenceManager;

import org.luajvm.android.runtime.AndroidLogger;
import org.luajvm.android.runtime.LuaConfig;
import org.luajvm.android.util.AssetInstaller;
import org.luajvm.android.util.CrashHandler;
import org.luajvm.android.util.LuaUtil;

import org.luajvm.core.LuaTable;
import org.luajvm.spi.Loggers;

import java.io.File;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import org.luajvm.android.runtime.LuaLog;

/**
 * Lua 应用基类，管理全局状态、通知渠道和崩溃处理。
 */
public class LuaApplication extends Application {

    // 跨线程读写（Lua 主线程 + task/thread IO 池）——ConcurrentHashMap 保迭代与写安全
    private static final Map<String, Object> sGlobalData = new ConcurrentHashMap<>();

    private static LuaApplication sInstance;
    private String mLocalDir;
    // assets 就绪门闩：解压在后台线程进行，主线程不阻塞（首个界面的动画可渲染）；
    //   其余宿主入口（Activity/Service 的脚本初始化）经 awaitAssets() 等待就绪
    private final CountDownLatch mAssetsLatch = new CountDownLatch(1);

    public static LuaApplication getInstance() {
        return sInstance;
    }

    @Override
    public void onCreate() {
        super.onCreate();
        sInstance = this;

        // 注入 Android Logcat 日志实现（默认 no-op，引擎 Loggers.get() 走这里）
        Loggers.setLogger(new AndroidLogger());

        mLocalDir = getFilesDir().getAbsolutePath();

        // 清理 dex 缓存
        File dexDir = getExternalFilesDir("dexfiles");
        if (dexDir != null) {
            LuaUtil.rmDir(dexDir);
        }

        CrashHandler.getInstance().init(this);
        createNotificationChannel();
        extractAssetsIfNeeded();
    }

    /**
     * assets 是否已就绪（无需解压或后台解压已完成）。
     */
    public boolean isAssetsReady() {
        return mAssetsLatch.getCount() == 0;
    }

    /**
     * 阻塞等待 assets 就绪。供未经 Welcome 的宿主入口（Service 冷启动、task 恢复的
     * 业务 Activity）在解析脚本路径前调用。
     */
    public void awaitAssets() {
        try {
            mAssetsLatch.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /**
     * 解压 APK 内 assets/ 到 localDir（首次安装、APK 更新或关键文件缺失）。解压在
     * 后台线程执行：主线程不阻塞，首个界面的首帧与动画可在解压期间渲染；解压期间
     * 启动的其余宿主经 {@link #awaitAssets()} 等待。仅按 lastUpdateTime 判定在
     * pm clear 后失效（lastTime 不变但 filesDir 为空），故缺失时强制解压。
     */
    private void extractAssetsIfNeeded() {
        File mainLua = new File(mLocalDir, "main.lua");
        // 只打字节码的包里没有 .lua，判据须连 .luac 一起看
        boolean needExtract = !LuaUtil.luaEntryExists(mainLua);
        if (!needExtract) {
            try {
                var sp = getSharedPreferences("appInfo", 0);
                var pkgInfo = getPackageManager().getPackageInfo(getPackageName(), 0);
                long lastTime = pkgInfo.lastUpdateTime;
                long oldLastTime = sp.getLong("lastUpdateTime", 0);
                needExtract = (oldLastTime != lastTime);
            } catch (PackageManager.NameNotFoundException e) {
                needExtract = true;
            }
        }
        if (!needExtract) {
            mAssetsLatch.countDown();
            return;
        }

        Thread extractor = new Thread(() -> {
            try {
                // 脚本调试产物目录（getDir("lua")/getDir("lib")）的内容随 APK 更新失效，
                // 与安装区一起在更新解压时清空
                LuaUtil.rmDir(getDir("lua", MODE_PRIVATE));
                LuaUtil.rmDir(getDir("lib", MODE_PRIVATE));
                unzipFromApk("assets/", new File(mLocalDir));
                writeLastUpdateTime();
            } finally {
                mAssetsLatch.countDown();
            }
        }, "assets-extract");
        extractor.start();
    }

    private void writeLastUpdateTime() {
        try {
            var sp = getSharedPreferences("appInfo", 0);
            var pkgInfo = getPackageManager().getPackageInfo(getPackageName(), 0);
            // 只写 lastUpdateTime：versionName 由 Welcome 检测变更后再写。
            // Application 先写会让 Welcome 永远读到「新值==旧值」，版本变更逻辑失效。
            sp.edit()
                    .putLong("lastUpdateTime", pkgInfo.lastUpdateTime)
                    .apply();
        } catch (PackageManager.NameNotFoundException ignored) {
        }
    }

    private void unzipFromApk(String entryPrefix, File destDir) {
        try {
            var orphans = AssetInstaller.extract(this, entryPrefix, destDir, false);
            if (!orphans.isEmpty()) {
                // 安装区每次解压前整目录清空，非 assets 产物随之消失。语义如此（用户数据
                // 归 getLuaExtDir），但静默消失会让脚本作者无从定位，故列出来。
                LuaConfig.logWarn("install dir wiped " + orphans.size()
                        + " non-asset file(s); persist under getLuaExtDir() instead: " + orphans);
            }
        } catch (Exception e) {
            Log.e("LuaApplication", "unzipFromApk failed", e);
            // 落进应用内日志（Lua 侧 log 列表可见）——否则失败只剩 logcat 一条，
            // 界面按 fallback 空白启动却无任何 Lua 可见线索
            LuaLog.getInstance()
                    .addError("extractAssets", new RuntimeException("unzipFromApk failed: " + e, e));
        }
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                    "lua_service_channel",
                    "Lua 服务",
                    NotificationManager.IMPORTANCE_DEFAULT);
            channel.setDescription("Lua 后台服务通知");
            channel.setSound(null, null);

            NotificationManager manager = getSystemService(NotificationManager.class);
            if (manager != null) {
                manager.createNotificationChannel(channel);
            }
        }
    }

    /**
     * 脚本安装目录（{@code getFilesDir}）。
     *
     * <p><b>安装区语义</b>：每次解压前由 {@link AssetInstaller#extract} 整目录清空，
     * 内容完全由 APK 内 assets 决定 —— 首次安装、APK 更新、以及关键文件缺失时都会重解压。
     * 脚本写在这里的文件会在升级时消失（清空前由 extract 返回孤儿清单、调用方告警）。
     *
     * <p>要持久化的用户数据应写 {@code getLuaExtDir()}（外部存储，不受解压影响）。
     */
    public String getLocalDir() {
        return mLocalDir;
    }

    public Map<String, Object> getGlobalData() {
        return sGlobalData;
    }

    public Map<String, ?> getSharedData() {
        return PreferenceManager.getDefaultSharedPreferences(this).getAll();
    }

    public Object getSharedData(String key) {
        return readShared(PreferenceManager.getDefaultSharedPreferences(this), key);
    }

    public Object getSharedData(String key, Object defaultValue) {
        Object value = readShared(PreferenceManager.getDefaultSharedPreferences(this), key);
        return value != null ? value : defaultValue;
    }

    // 按类型直读替代 getAll() 整表复制：contains 先行排掉缺键，探测默认值不会误返回
    private static Object readShared(SharedPreferences sp, String key) {
        if (!sp.contains(key)) return null;
        try {
            return sp.getString(key, null);
        } catch (ClassCastException ignored) {
        }
        try {
            return sp.getLong(key, 0L);
        } catch (ClassCastException ignored) {
        }
        try {
            return sp.getInt(key, 0);
        } catch (ClassCastException ignored) {
        }
        try {
            return sp.getFloat(key, 0f);
        } catch (ClassCastException ignored) {
        }
        try {
            return sp.getBoolean(key, false);
        } catch (ClassCastException ignored) {
        }
        return sp.getStringSet(key, null);
    }

    public boolean setSharedData(String key, Object value) {
        SharedPreferences.Editor editor = PreferenceManager.getDefaultSharedPreferences(this).edit();

        switch (value) {
            case null -> editor.remove(key);
            case String s -> editor.putString(key, s);
            case Long l -> editor.putLong(key, l);
            case Integer i -> editor.putInt(key, i);
            case Float f -> editor.putFloat(key, f);
            // SharedPreferences 没有 double 槽位：按平台惯例降精度存 float
            //（不显式处理会落 default 返回 false）
            case Double d -> editor.putFloat(key, d.floatValue());
            case Boolean b -> editor.putBoolean(key, b);
            case LuaTable luaTable -> {
                Set<String> set = new HashSet<>();
                for (Object item : (luaTable).values()) {
                    set.add(String.valueOf(item));
                }
                editor.putStringSet(key, set);
            }
            case Set<?> set1 -> {
                @SuppressWarnings("unchecked")
                Set<String> set = (Set<String>) set1;
                editor.putStringSet(key, set);
            }
            default -> {
                return false;
            }
        }

        // 主线程走 apply（异步落盘，避开同步 IO），其余线程走 commit 以便把真实落盘
        //   结果回给 Lua —— apply 吞掉写失败，恒返回 true 会让脚本把失败当成功
        if (Looper.myLooper() == Looper.getMainLooper()) {
            editor.apply();
            return true;
        }
        return editor.commit();
    }
}
