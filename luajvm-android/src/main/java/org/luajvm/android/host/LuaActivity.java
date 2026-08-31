package org.luajvm.android.host;

import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.ServiceConnection;
import android.content.pm.ApplicationInfo;
import android.content.pm.ShortcutInfo;
import android.content.pm.ShortcutManager;
import android.content.res.Configuration;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.drawable.Icon;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.StrictMode;
import android.view.KeyEvent;
import android.view.Menu;
import android.view.MenuItem;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.ListView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityOptionsCompat;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.fragment.app.Fragment;

import org.luajvm.android.api.CallLuaFunction;
import org.luajvm.android.api.LuaHost;
import org.luajvm.android.api.LuaSafHost;
import org.luajvm.android.api.LuaHostDelegate;
import org.luajvm.android.runtime.LuaConfig;
import org.luajvm.android.widget.LuaLayout;
import org.luajvm.android.runtime.LuaLog;
import com.google.android.material.textview.MaterialTextView;

import org.luajvm.core.LuaFunction;
import org.luajvm.core.LuaTable;
import org.luajvm.core.LuaValue;

import java.io.FileNotFoundException;
import java.io.File;
import org.luajvm.android.engine.WebViewPrewarm;
import org.luajvm.android.runtime.DebugProps;

/**
 * Lua Activity 基类，完全不直接接触 LuaEngine。
 * 所有逻辑委托给 ActivityDelegate。
 */
@SuppressWarnings("unused")
public class LuaActivity extends AppCompatActivity implements LuaHost, ActivityDelegate.UICallback, LuaSafHost {

    public static final String ARG = "arg";
    public static final String DATA = "data";
    public static final String NAME = "name";

    private static final String DEFAULT_HOST_NAME = "activity";

    private final ActivityDelegate mDelegate = new ActivityDelegate(this, getHostName(), this);

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

    // volatile：showLogs 可从 Lua 的 thread/task 线程进来，初始化与 sendMsg 的刷新跨线程可见
    private volatile ArrayAdapter<String> mLogAdapter;
    private ListView mLogListView;
    private boolean mSetViewed;

    // ==================== UICallback 实现 ====================

    @Override
    public void applyDefaultView() {
        if (!mSetViewed) {
            if (mLogListView == null) initLogListView();
            setContentView(mLogListView);
            ViewCompat.setOnApplyWindowInsetsListener(findViewById(android.R.id.content), (v, insets) -> {
                var systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars());
                v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom);
                return WindowInsetsCompat.CONSUMED;
            });
        }
    }

    @Override
    public void setTitle(CharSequence title) {
        super.setTitle(title);
    }

    @Override
    public boolean isSetViewed() {
        return mSetViewed;
    }

    @Override
    public void handleError(Exception e) {
        applyDefaultView();
        // -1/0 的数值语义保持不变（错误路径 RESULT_OK、成功路径 RESULT_CANCELED 的
        //   倒置是外部可观察语义，脚本可能依赖，不改）
        setResult(RESULT_OK, new Intent().putExtra(DATA, e.toString()));
    }

    @Override
    public void handleVersionChanged(Bundle savedInstanceState) {
        Intent intent = getIntent();
        if (intent.getBooleanExtra("isVersionChanged", false) && savedInstanceState == null) {
            onVersionChanged(intent.getStringExtra("newVersionName"),
                    intent.getStringExtra("oldVersionName"));
        }
    }

    // ==================== 可覆盖的配置方法 ====================

    protected String getHostName() {
        return DEFAULT_HOST_NAME;
    }

    protected void onVersionChanged(String newVersionName, String oldVersionName) {
    }

    // ==================== 生命周期（纯转发） ====================

        /**
     * java-only：A/B 开关注入的**最早**时机。
     *
     * <p>开关是 {@code static final}、在引擎类初始化时求值一次，所以必须早于任何引擎类
     * 被触碰。{@code LuaEngine} 构造器里那次调用实测太晚（主题/EdgeToEdge 等在
     * {@code onCreate} 早期就可能已加载引擎类），{@code attachBaseContext} 是 Activity
     * 生命周期里第一个能拿到 {@code Context} 的回调。
     * 文件不存在时零影响，见 {@link org.luajvm.android.runtime.DebugProps}。
     */
    @Override
    protected void attachBaseContext(Context base) {
        DebugProps.loadOnce(base);
        super.attachBaseContext(base);
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // 线程策略放行始终装：脚本可经 bindClass 在主线程自行做 IO，平台默认的
        //   detectNetwork().penaltyDeath() 会让同一份脚本 debug 能跑、release 崩。
        //   setThreadPolicy 只作用于当前线程（主线程），不是全进程
        StrictMode.setThreadPolicy(new StrictMode.ThreadPolicy.Builder().permitAll().build());
        // VM 策略只在 debug 构建放行：非 SDK 接口调用在 release 上该由平台照常拦
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P
                && (getApplicationInfo().flags & ApplicationInfo.FLAG_DEBUGGABLE) != 0) {
            LuaConfig.runSafely(() -> {
                var vmPolicy = new StrictMode.VmPolicy.Builder().permitNonSdkApiUsage().build();
                StrictMode.setVmPolicy(vmPolicy);
            }, "setVmPolicy");
        }

        mDelegate.initEngine(savedInstanceState);
        // 首帧之后异步预启动 WebView 的 Chromium provider：把这笔一次性成本从
        //   "第一次打开含 WebView 的页面"那条点击路径上挪走。
        //   进程内只做一次、异常全吞、-Dluajvm.webviewprewarm=false 可关。
        WebViewPrewarm.scheduleAfterFirstFrame(this);
    }

    @Override
    protected void onNewIntent(@NonNull Intent intent) {
        super.onNewIntent(intent);
        mDelegate.onNewIntent(intent);
    }

    @Override
    protected void onStart() {
        super.onStart();
        mDelegate.onStart();
    }

    @Override
    protected void onResume() {
        super.onResume();
        mDelegate.onResume();
    }

    @Override
    protected void onPause() {
        super.onPause();
        mDelegate.onPause();
    }

    @Override
    protected void onStop() {
        super.onStop();
        mDelegate.onStop();
    }

    @Override
    public void onContentChanged() {
        super.onContentChanged();
        mSetViewed = true;
    }

    @Override
    protected void onDestroy() {
        mDelegate.destroyEngine();
        super.onDestroy();
    }

    @Override
    public void onConfigurationChanged(@NonNull Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        mDelegate.onRequestPermissionsResult(requestCode, permissions, grantResults);
    }

    // ==================== 视图设置 ====================

    public void setContentView(LuaTable view) {
        mSetViewed = true;
        setContentView(new LuaLayout(this).load(view, mDelegate.getLuaState()).touserdata(View.class));
    }

    public void setFragment(Fragment fragment) {
        setContentView(new View(this));
        getSupportFragmentManager().beginTransaction().replace(android.R.id.content, fragment).commit();
    }

    public void showLogs() {
        // 惰性初始化：走 setContentView(Lua) 路径时 mLogAdapter 仍为 null，直接弹会是空对话框。
        //   Lua 可从任意线程调本方法，不同步的话两次并发初始化后写者胜出，先弹出的对话框
        //   绑在孤立 adapter 上、sendMsg 再也刷不到它
        synchronized (this) {
            if (mLogAdapter == null) initLogListView();
        }
        new AlertDialog.Builder(this).setTitle("Logs")
                .setAdapter(mLogAdapter, null)
                .setPositiveButton(android.R.string.ok, null).create().show();
    }

    private void initLogListView() {
        mLogListView = new ListView(this);
        mLogListView.setFastScrollEnabled(true);
        mLogListView.setFastScrollAlwaysVisible(true);

        // newest-first：反转 adapter 视角（position 0 = 最新日志），日志底层数据仍为追加序，
        //   弹出对话框即见最新条目，无须滚动到底
        var logs = LuaLog.getInstance().getLogs();
        mLogAdapter = new ArrayAdapter<String>(this, android.R.layout.simple_list_item_1, logs) {
            @NonNull
            @Override
            public View getView(int position, View convertView, @NonNull ViewGroup parent) {
                View view = super.getView(position, convertView, parent);
                // 复用视图也须保证 selectable：convertView 池中视图可能来自未设置过
                //   selectable 的批次，仅判 null 会让复用行丢失长按复制能力
                if (view instanceof MaterialTextView tv) tv.setTextIsSelectable(true);
                return view;
            }

            @Override
            public String getItem(int position) {
                return super.getItem(super.getCount() - 1 - position);
            }

            @Override
            public long getItemId(int position) {
                return super.getItemId(super.getCount() - 1 - position);
            }
        };
        mLogListView.setAdapter(mLogAdapter);
    }

    // ==================== 菜单 ====================

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        return mDelegate.onCreateOptionsMenu(menu);
    }

    @Override
    public boolean onOptionsItemSelected(@NonNull MenuItem item) {
        boolean handled = mDelegate.onOptionsItemSelected(item);
        return handled || super.onOptionsItemSelected(item);
    }

    // ==================== 键盘/触摸事件 ====================

    @Override
    public boolean onKeyShortcut(int keyCode, KeyEvent event) {
        return mDelegate.onKeyShortcut(keyCode, event) || super.onKeyShortcut(keyCode, event);
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        return mDelegate.onKeyDown(keyCode, event) || super.onKeyDown(keyCode, event);
    }

    @Override
    public boolean onKeyUp(int keyCode, KeyEvent event) {
        return mDelegate.onKeyUp(keyCode, event) || super.onKeyUp(keyCode, event);
    }

    @Override
    public boolean onKeyLongPress(int keyCode, KeyEvent event) {
        return mDelegate.onKeyLongPress(keyCode, event) || super.onKeyLongPress(keyCode, event);
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        return mDelegate.onTouchEvent(event) || super.onTouchEvent(event);
    }

    // ==================== 广播 ====================

    public Intent registerReceiver(IntentFilter filter) {
        return mDelegate.registerReceiver(filter);
    }

    public Intent registerReceiver(IntentFilter filter, boolean exported) {
        return mDelegate.registerReceiver(filter, exported);
    }

    // ==================== Service ====================

    public boolean bindService(int flag) {
        return mDelegate.bindService(flag);
    }

    public boolean bindService(ServiceConnection conn, int flag) {
        return mDelegate.bindService(conn, flag);
    }

    public boolean bindService(String path, ServiceConnection conn, int flag) {
        return mDelegate.bindService(path, conn, flag);
    }

    public void startService(String path, Object[] arg) throws FileNotFoundException {
        mDelegate.startService(path, arg);
    }

    public boolean stopService() {
        return mDelegate.stopService();
    }

    // ==================== Activity 跳转 ====================

    public void newActivity(int req, String path, int in, int out, Object[] arg, boolean newDocument) throws FileNotFoundException {
        mDelegate.newActivity(req, path, arg, newDocument);
        // overrideActivityTransition 设的是"本 Activity 被打开时"的动画（须由目标 Activity
        //   自己调），拿它替换这里只会让新页面用系统默认动画、反而改掉本页以后的动画；
        //   in/out 是给刚启动的那个页面的，只能走 overridePendingTransition
        overridePendingTransition(in, out);
    }

    // ==================== Lua 脚本页跳转 ====================

    /**
     * 新开文档页（{@code startDocumentActivity*}）的承载 Activity 类，须是 standard
     * 启动模式：singleTask/singleInstance 会把 NEW_DOCUMENT|MULTIPLE_TASK 压制成
     * 给现有实例投 onNewIntent，页面不切换，且 setIntent 写入的脚本入口会在此后的
     * recreate 中被当作本页入口重放。宿主入口 Activity 配了 singleTask 时必须覆写
     * 本方法，指向一个 standard 模式的脚本承载类。
     *
     * <p>{@code replaceActivity*} 不走本方法：它以 NEW_TASK|CLEAR_TASK 重建宿主自身，
     * 保留宿主的主题与 onVersionChanged 等覆写。
     */
    protected Class<?> getScriptHostClass() {
        return this.getClass();
    }

    private Intent buildLuaIntent(boolean isReplace, String path, Object[] arg) {
        Intent intent = new Intent(this, isReplace ? this.getClass() : getScriptHostClass());
        String resolved = resolveLuaPath(path);
        intent.putExtra("luaPath", resolved);
        intent.putExtra(NAME, resolved);
        if (arg != null) {
            intent.putExtra(ARG, arg);
        }
        if (isReplace) {
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TASK);
        } else {
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_DOCUMENT);
            intent.addFlags(Intent.FLAG_ACTIVITY_MULTIPLE_TASK);
        }
        return intent;
    }

    /** 相对路径补全与目录入口解析：目录配 main.lua/main.luac 则拼入口，无扩展名按 .lua/.luac 存在性补全。 */
    private String resolveLuaPath(String path) {
        if (path == null || path.isEmpty()) {
            return "/";
        }
        if (path.charAt(0) != '/') {
            path = getLuaDir() + "/" + path;
        }
        File file = new File(path);
        if (file.isDirectory() && new File(path + "/main.lua").exists()) {
            path += "/main.lua";
        } else if (file.isDirectory() && new File(path + "/main.luac").exists()) {
            path += "/main.luac";
        } else if (!file.isDirectory() && !path.endsWith(".lua") && !path.endsWith(".luac")) {
            // 补全扩展名时优先 .lua —— 源码存在时 loadFile 的兄弟文件探测会自动改读
            //   同名 .luac；仅当源码确实不存在才落到 .luac
            if (!new File(path + ".lua").exists() && new File(path + ".luac").exists()) {
                path += ".luac";
            } else {
                path += ".lua";
            }
        }
        return path;
    }

    public final void startDocumentActivity(String path, Object[] arg) {
        Intent intent = buildLuaIntent(false, path, arg);
        intent.addFlags(Intent.FLAG_ACTIVITY_NO_ANIMATION);
        startActivity(intent);
    }

    public final void startDocumentActivityWithAnim(String path, Object[] arg) {
        startActivity(buildLuaIntent(false, path, arg));
    }

    public final void startDocumentActivityWithShared(String path, Object[] arg, View sharedElement, String transitionName) {
        Intent intent = buildLuaIntent(false, path, arg);
        ActivityOptionsCompat options = ActivityOptionsCompat.makeSceneTransitionAnimation(this, sharedElement, transitionName);
        startActivity(intent, options.toBundle());
    }

    public final void replaceActivity(String path, Object[] arg) {
        Intent intent = buildLuaIntent(true, path, arg);
        intent.addFlags(Intent.FLAG_ACTIVITY_NO_ANIMATION);
        startActivity(intent);
        finish();
    }

    public final void replaceActivityWithAnim(String path, Object[] arg) {
        startActivity(buildLuaIntent(true, path, arg));
        finish();
    }

    public final void replaceActivityWithShared(String path, Object[] arg, View sharedElement, String transitionName) {
        Intent intent = buildLuaIntent(true, path, arg);
        ActivityOptionsCompat options = ActivityOptionsCompat.makeSceneTransitionAnimation(this, sharedElement, transitionName);
        startActivity(intent, options.toBundle());
        finish();
    }

    // ==================== 实例状态保存（Lua 转发） ====================

    @Override
    protected void onSaveInstanceState(@NonNull Bundle outState) {
        super.onSaveInstanceState(outState);
        runFunc("onSaveInstanceState", outState);
    }

    @Override
    protected void onRestoreInstanceState(@NonNull Bundle savedInstanceState) {
        runFunc("onRestoreInstanceState", savedInstanceState);
        super.onRestoreInstanceState(savedInstanceState);
    }

    // ==================== Activity 结果回调 ====================

    @CallLuaFunction(value = CallLuaFunction.Thread.MAIN,
            note = "ACTION_OPEN_DOCUMENT_TREE 的 onActivityResult")
    public void openDocumentTree(LuaFunction function) {
        startForResult(Intent.ACTION_OPEN_DOCUMENT_TREE, function);
    }

    @CallLuaFunction(value = CallLuaFunction.Thread.MAIN,
            note = "ACTION_OPEN_DOCUMENT 的 onActivityResult")
    public void openDocument(String type, LuaFunction function) {
        startForResult(Intent.ACTION_OPEN_DOCUMENT, function, type);
    }

    @CallLuaFunction(value = CallLuaFunction.Thread.MAIN,
            note = "ACTION_GET_CONTENT 的 onActivityResult")
    public void getDocument(String type, LuaFunction function) {
        startForResult(Intent.ACTION_GET_CONTENT, function, type);
    }

    @CallLuaFunction(value = CallLuaFunction.Thread.MAIN,
            note = "ACTION_CREATE_DOCUMENT 的 onActivityResult")
    public void createDocument(String type, String name, LuaFunction function) {
        Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType(type);
        intent.putExtra(Intent.EXTRA_TITLE, name);
        int requestCode = function.hashCode();
        mDelegate.registerCallback(requestCode, function);
        startActivityForResult(intent, requestCode);
    }

    private void startForResult(String action, LuaFunction function, String... mimeTypes) {
        Intent intent = new Intent(action);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        if (mimeTypes.length > 0) intent.setType(mimeTypes[0]);
        int requestCode = function.hashCode();
        mDelegate.registerCallback(requestCode, function);
        startActivityForResult(intent, requestCode);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        mDelegate.onActivityResult(requestCode, resultCode, data);
        super.onActivityResult(requestCode, resultCode, data);
    }

    public void result(Object[] data) {
        Intent resultIntent = new Intent();
        resultIntent.putExtra(NAME, getIntent().getStringExtra(NAME));
        resultIntent.putExtra(DATA, data);
        setResult(RESULT_CANCELED, resultIntent);
        finish();
    }

    public void finish(boolean finishTask) {
        if (!finishTask) {
            super.finish();
            return;
        }
        Intent intent = getIntent();
        if (intent != null && (intent.getFlags() & Intent.FLAG_ACTIVITY_NEW_DOCUMENT) != 0)
            finishAndRemoveTask();
        else super.finish();
    }

    // ==================== 快捷方式 ====================

    public void addShortcut(String label, String text) {
        Intent intent = new Intent(Intent.ACTION_MAIN);
        intent.addCategory("android.intent.category.DEFAULT");
        intent.setClassName(getPackageName(), this.getClass().getName());
        intent.setData(Uri.parse(text));
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP_MR1) {
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_DOCUMENT | Intent.FLAG_ACTIVITY_MULTIPLE_TASK);
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            var shortcutManager = getSystemService(ShortcutManager.class);
            if (shortcutManager == null) return;
            var appIcon = getApplicationInfo().loadIcon(getPackageManager());
            Bitmap bitmap = Bitmap.createBitmap(
                    Math.max(appIcon.getIntrinsicWidth(), 96),
                    Math.max(appIcon.getIntrinsicHeight(), 96),
                    Bitmap.Config.ARGB_8888);
            Canvas canvas = new Canvas(bitmap);
            appIcon.setBounds(0, 0, canvas.getWidth(), canvas.getHeight());
            appIcon.draw(canvas);
            var shortcutInfo = new ShortcutInfo.Builder(this, text)
                    .setIcon(Icon.createWithBitmap(bitmap))
                    .setShortLabel(label)
                    .setIntent(intent)
                    .build();
            // 返回值只表示桌面是否接受了"待用户确认"的请求，不代表已添加
            boolean accepted = shortcutManager.requestPinShortcut(shortcutInfo, null);
            Toast.makeText(this, accepted ? "已请求添加，请在桌面确认" : "桌面不支持添加",
                    Toast.LENGTH_SHORT).show();
        } else {
            Intent addShortcut = new Intent("com.android.launcher.action.INSTALL_SHORTCUT");
            addShortcut.putExtra(Intent.EXTRA_SHORTCUT_NAME, label);
            addShortcut.putExtra(Intent.EXTRA_SHORTCUT_INTENT, intent);
            addShortcut.putExtra("duplicate", false);
            sendBroadcast(addShortcut);
            Toast.makeText(this, "添加成功", Toast.LENGTH_SHORT).show();
        }
    }

    // ==================== 委托方法 ====================

    @Override
    public void sendMsg(String msg) {
        mDelegate.sendMsg(msg);
        if (mLogAdapter != null) mLogAdapter.notifyDataSetChanged();
    }

    public void setDebug(boolean debug) {
        LuaConfig.setDebug(debug);
    }

    // ==================== LuaMetaTable 支持 ====================

    public LuaValue __index(LuaValue key) {
        return mDelegate.__index(key);
    }

    public void __newindex(LuaValue key, LuaValue value) {
        mDelegate.__newindex(key, value);
    }
}
