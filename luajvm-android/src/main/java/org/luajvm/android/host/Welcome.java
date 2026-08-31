package org.luajvm.android.host;

import android.annotation.SuppressLint;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.os.SystemClock;
import android.view.Gravity;
import android.view.View;

import androidx.appcompat.app.AppCompatActivity;

import org.luajvm.android.LuaApplication;
import org.luajvm.android.api.LuaHost;
import org.luajvm.android.api.LuaHostDelegate;
import org.luajvm.android.runtime.LuaConfig;
import org.luajvm.android.engine.LuaEngine;
import org.luajvm.android.util.AssetInstaller;
import org.luajvm.android.util.LuaUtil;

import com.google.android.material.textview.MaterialTextView;

import java.io.File;

/**
 * 欢迎/启动页 Activity，负责版本检测、资源解压、执行 welcome.lua。
 */
public class Welcome extends AppCompatActivity implements LuaHost {

    private final BaseDelegate mDelegate = new BaseDelegate(this,
            new LuaEngine(this, this, getHostName())) {
    };

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
    private String mVersionName;
    private String mOldVersionName;
    private boolean mVersionChanged;
    private long mCreatedAtElapsed;

    private long elapsedSinceCreate() {
        return SystemClock.uptimeMillis() - mCreatedAtElapsed;
    }

    // ==================== 可覆盖的配置方法 ====================

    /**
     * 版本更新后跳转的目标 Activity
     */
    protected Class<?> getTargetActivity() {
        return Main.class;
    }

    /**
     * 欢迎脚本文件名
     */
    protected String getWelcomeScriptName() {
        return "welcome.lua";
    }

    /**
     * 宿主名称
     */
    protected String getHostName() {
        return "welcome";
    }

    /**
     * 资源解压完成回调
     */
    protected void onUpdateExtracted() {
    }

    /**
     * 欢迎页最小展示时长（毫秒）。assets 已就绪也至少展示此时长再进入主界面，
     * 保证欢迎页动画可见；返回 0 则就绪即走。
     */
    protected long getMinDisplayTimeMillis() {
        return 0;
    }

    // ==================== 生命周期 ====================

    @Override
    public void onCreate(Bundle savedInstanceState) {
        mCreatedAtElapsed = SystemClock.uptimeMillis();
        super.onCreate(savedInstanceState);
        setContentView(createContentView());

        long minDisplay = getMinDisplayTimeMillis();
        LuaApplication app = (LuaApplication) getApplication();
        if (app.isAssetsReady() && elapsedSinceCreate() >= minDisplay) {
            continueStartup();
            return;
        }
        // 等待 assets 就绪与最小展示时长（两者取较长），期间欢迎页保持可见
        new Thread(() -> {
            app.awaitAssets();
            long remain = minDisplay - elapsedSinceCreate();
            if (remain > 0) {
                try {
                    Thread.sleep(remain);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
            runOnUiThread(() -> {
                if (!isDestroyed()) continueStartup();
            });
        }).start();
    }

    /** assets 就绪后的启动流程：版本检测、welcome 脚本或跳转主界面。 */
    private void continueStartup() {
        detectVersionChangeAndExtract();

        String welcomePath = mDelegate.getLuaPath(getWelcomeScriptName());
        if (new File(welcomePath).exists()) {
            mDelegate.init(welcomePath, new Object[]{mVersionName, mOldVersionName});
        } else {
            startMainActivity();
        }
    }

    /**
     * 创建默认视图
     */
    @SuppressLint("SetTextI18n")
    protected View createContentView() {
        var poweredByText = new MaterialTextView(this);
        poweredByText.setText("Powered by LuaJVM");
        poweredByText.setTextColor(0xff888888);
        poweredByText.setGravity(Gravity.TOP);
        return poweredByText;
    }

    // ==================== 版本检测 & 资源解压 ====================

    private void detectVersionChangeAndExtract() {
        try {
            var pm = getPackageManager();
            // getPackageInfo(String,int) API 33 起废弃，新平台走 PackageInfoFlags 重载
            var pkgInfo = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
                    ? pm.getPackageInfo(getPackageName(), PackageManager.PackageInfoFlags.of(0))
                    : pm.getPackageInfo(getPackageName(), 0);
            var prefs = getSharedPreferences("appInfo", Context.MODE_PRIVATE);

            long apkUpdateTime = pkgInfo.lastUpdateTime;
            long savedUpdateTime = prefs.getLong("lastUpdateTime", 0);

            mVersionName = pkgInfo.versionName;
            mOldVersionName = prefs.getString("versionName", "");

            if (!mVersionName.equals(mOldVersionName)) {
                prefs.edit().putString("versionName", mVersionName).apply();
                mVersionChanged = true;
            }

            if (savedUpdateTime != apkUpdateTime) {
                prefs.edit().putLong("lastUpdateTime", apkUpdateTime).apply();
                var app = (LuaApplication) getApplication();
                unzipFromApk("assets/", new File(app.getLocalDir()));
                onUpdateExtracted();
            } else {
                //  pm clear 等场景 apkUpdateTime 不变但 filesDir 为空，
                //   Activity fallbackPath（如 main.lua）缺失会走 default view。
                //   故关键文件缺失时强制解压，避免主页面无法进入。
                var app = (LuaApplication) getApplication();
                var mainLua = new File(app.getLocalDir(), "main.lua");
                if (!LuaUtil.luaEntryExists(mainLua)) {
                    unzipFromApk("assets/", new File(app.getLocalDir()));
                    onUpdateExtracted();
                }
            }
        } catch (PackageManager.NameNotFoundException e) {
            LuaConfig.logError("detectVersionChangeAndExtract", e);
        }
    }

    /**
     * 从 APK 中解压指定目录（核心在 util.AssetInstaller；Welcome 路径给 .dex 加只读位）
     */
    protected void unzipFromApk(String entryPrefix, File destDir) {
        try {
            var orphans = AssetInstaller.extract(this, entryPrefix, destDir, true);
            if (!orphans.isEmpty()) {
                LuaConfig.logWarn("install dir wiped on update: " + orphans.size()
                        + " non-asset file(s) removed under " + destDir
                        + " " + orphans + " — persist script data under getLuaExtDir()");
            }
        } catch (Exception e) {
            LuaConfig.logError("unzipFromApk", e);
        }
    }

    // ==================== 跳转主界面 ====================

    /**
     * 启动目标 Activity
     */
    public void startMainActivity() {
        Intent intent = new Intent(this, getTargetActivity());
        if (mVersionChanged) {
            intent.putExtra("isVersionChanged", true);
            intent.putExtra("newVersionName", mVersionName);
            intent.putExtra("oldVersionName", mOldVersionName);
        }
        startActivity(intent);
        finish();
    }

    @Override
    protected void onDestroy() {
        mDelegate.destroy();
        super.onDestroy();
    }
}
