package org.luajvm.android.host;

import android.app.Activity;
import android.content.ComponentName;
import android.content.Intent;
import android.content.ServiceConnection;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.IBinder;
import android.view.KeyEvent;
import android.view.Menu;
import android.view.MenuItem;
import android.view.MotionEvent;

import org.luajvm.android.LuaApplication;
import org.luajvm.android.engine.LuaEngine;
import org.luajvm.android.runtime.LuaConfig;
import org.luajvm.android.util.LuaUtil;
import org.luajvm.android.api.CallLuaFunction;
import org.luajvm.android.api.LuaSafHost;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import org.luajvm.bind.Coercion;
import org.luajvm.bind.JavaCall;
import org.luajvm.core.Globals;
import org.luajvm.core.LuaError;
import org.luajvm.core.LuaFunction;
import org.luajvm.core.LuaValue;

import java.io.File;
import java.io.IOException;
import java.util.HashMap;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Activity 委托类，处理生命周期、UI 交互、权限、Service 绑定等逻辑。
 */
public class ActivityDelegate extends BaseDelegate implements LuaSafHost.SafDelegate {

    private final Activity mActivity;
    private final UICallback mUICallback;
    private final HashMap<Integer, LuaFunction> mResultCallbacks = new HashMap<>();
    private String mLuaPath = "main.lua";
    private LuaValue mOnKeyShortcut;
    private LuaValue mOnKeyDown;
    private LuaValue mOnKeyUp;
    private LuaValue mOnKeyLongPress;
    private LuaValue mOnTouchEvent;

    public ActivityDelegate(Activity activity, String hostName, UICallback callback) {
        super(activity, new LuaEngine(activity, activity, hostName));
        mActivity = activity;
        mUICallback = callback;
    }

    // ==================== 引擎生命周期（封装完整初始化流程） ====================

    /**
     * 初始化并启动 Lua 引擎。接管 LuaActivity.onCreate 的全部初始化逻辑。
     */
    public void initEngine(Bundle savedInstanceState) {
        // 进程未经 Welcome 直接冷启动（task 恢复、外部调试拉起）时解压可能仍在
        //   后台进行，入口路径解析须等 assets 就绪
        if (mActivity.getApplication() instanceof LuaApplication app) {
            app.awaitAssets();
        }
        String luaPath = null;
        Intent intent = mActivity.getIntent();
        Uri data = intent != null ? intent.getData() : null;
        LuaConfig.log("ActivityDelegate intent data=" + data + " intent=" + intent);
        // getIntent() 可空（见上面 data 的判空），空时按无参启动
        final Object[] arg = intent == null ? new Object[0] : Objects.requireNonNullElse(
                getSerializableArray(intent, "arg"), new Object[0]);
        if (data != null) {
            String path = data.getPath();
            if (path != null && !path.isEmpty()) {
                File luaFile = new File(path);
                if (luaFile.isFile() && luaFile.canRead()) {
                    if (isInPrivateDir(luaFile)) {
                        mUICallback.setTitle(luaFile.getName());
                        luaPath = path;
                    } else {
                        // exported Activity 可被第三方用任意可读路径拉起：
                        //   域外 .lua 须经单次确认，域内（宿主脚本区）行为不变
                        confirmExternalLua(luaFile, arg, savedInstanceState);
                        return;
                    }
                }
            }
        }
        // luaPath extra 与 URI data 同为 intent 可控的脚本入口（LuaRunActivity 转发、
        //   脚本页跳转都走 extra），域外同样须经用户确认；域内交由 startEngine
        //   的回落链路照常执行，不打扰正常导航
        if (luaPath == null && intent != null) {
            String extra = intent.getStringExtra("luaPath");
            if (extra != null && !extra.isEmpty() && LuaUtil.luaEntryExists(new File(extra))) {
                File luaFile = new File(extra);
                if (!isInPrivateDir(luaFile)) {
                    confirmExternalLua(luaFile, arg, savedInstanceState);
                    return;
                }
            }
        }
        startEngine(luaPath, arg, savedInstanceState);
    }

    /**
     * 域外 .lua 的单次执行询问（IDE 调试、文件管理器打开等场景）。
     * intent 携带有效令牌（确认后 recreate 的实例）时消费令牌并执行；
     * 确认后签发令牌写回 intent 并 recreate，由重建实例在 onCreate 内同步启动引擎，
     * androidx 的 ActivityResult 注册窗口（STARTED 之前）保持可用；
     * 拒绝或关闭对话框结束 Activity。
     */
    private void confirmExternalLua(File luaFile, Object[] arg, Bundle savedInstanceState) {
        Intent intent = mActivity.getIntent();
        String canonical = canonicalPath(luaFile);
        String token = intent != null ? intent.getStringExtra(LUA_TRUST_EXTRA) : null;
        if (canonical != null && token != null && canonical.equals(sLuaTrustTokens.remove(token))) {
            mUICallback.setTitle(luaFile.getName());
            startEngine(luaFile.getAbsolutePath(), arg, savedInstanceState);
            return;
        }
        final boolean[] granted = {false};
        new MaterialAlertDialogBuilder(mActivity)
                .setTitle("Lua")
                .setMessage("该脚本位于应用数据目录之外，是否执行？\n" + luaFile.getAbsolutePath())
                .setPositiveButton(android.R.string.ok, (d, w) -> {
                    if (intent == null || canonical == null) return;
                    granted[0] = true;
                    String newToken = UUID.randomUUID().toString();
                    sLuaTrustTokens.put(newToken, canonical);
                    intent.putExtra(LUA_TRUST_EXTRA, newToken);
                    mActivity.recreate();
                })
                .setNegativeButton(android.R.string.cancel, null)
                .setOnDismissListener(d -> {
                    if (!granted[0]) mActivity.finish();
                })
                .show();
    }

    // ==================== 域外脚本单次执行令牌 ====================

    /** 待消费令牌：token -> canonical 路径。仅存进程内存，消费即移除。 */
    private static final ConcurrentHashMap<String, String> sLuaTrustTokens = new ConcurrentHashMap<>();

    private static final String LUA_TRUST_EXTRA = "luaTrustToken";

    /**
     * 域内判定：脚本位于应用私有目录（dataDir 及其下的 files/cache 等、
     * externalFilesDir）或引擎 rootDir（LuaPathResolver.findRoot 解析的项目根）之内。
     * canonical 消解 /sdcard 与 /storage/emulated/0 这类同卷符号链接；
     * 解析失败按域外（走确认框），不静默放行。
     */
    private boolean isInPrivateDir(File file) {
        String target = canonicalPath(file);
        if (target == null) return false;
        File extDir = mActivity.getExternalFilesDir(null);
        return underRoot(target, getEngine().getRootDir())
                || underRoot(target, mActivity.getApplicationInfo().dataDir)
                || (extDir != null && underRoot(target, extDir.getAbsolutePath()));
    }

    private static boolean underRoot(String target, String root) {
        if (root == null || root.isEmpty()) return false;
        String canonical = canonicalPath(new File(root));
        return canonical != null && target.startsWith(canonical + File.separator);
    }

    private static String canonicalPath(File file) {
        try {
            return file.getCanonicalPath();
        } catch (IOException e) {
            return null;
        }
    }

    /**
     * 域内路径与确认后的域外路径共用的执行链路；luaPath==null 时回落宿主默认入口。
     */
    private void startEngine(String luaPath, Object[] arg, Bundle savedInstanceState) {
        if (luaPath == null) {
            // 普通业务 URI 不是 Lua 文件，仍使用宿主默认入口；URI 留在 Intent 中供 Lua 路由。
            String path = getEngine().getLuaContext().getLuaPath();
            if (path == null || path.isEmpty()) {
                // Intent 的 luaPath extra：LuaActivity 的脚本页跳转方法经此传入口
                path = mActivity.getIntent() != null ? mActivity.getIntent().getStringExtra("luaPath") : null;
            }
            if ((path == null || path.isEmpty())
                    && mActivity.getApplication() instanceof LuaApplication app) {
                path = app.getLocalDir() + "/main.lua";
            }
            // 只打字节码的包里 .lua 不在盘上，可读性须按 .lua/.luac 任一判定
            if (path == null || !LuaUtil.luaEntryExists(new File(path))) {
                mUICallback.applyDefaultView();
                sendMsg("Lua path error: " + (path == null ? "path is null" : "file not readable"));
                return;
            }
            luaPath = new File(path).getAbsolutePath();
        }
        // 走到这里 luaPath 必非 null：上面两个分支要么赋值，要么已经 return
        mLuaPath = luaPath;
        LuaConfig.log("ActivityDelegate final mLuaPath=" + mLuaPath + " calling init()");
        setOnInitListener(new LuaEngine.OnInitListener() {
            @Override
            public void onSuccess() {
                mActivity.runOnUiThread(() -> {
                    cacheLuaEventHandlers();
                    try {
                        runMainFunc(arg);
                        if (!mUICallback.isSetViewed()) mUICallback.applyDefaultView();
                        mUICallback.handleVersionChanged(savedInstanceState);
                    } catch (Exception e) {
                        mUICallback.handleError(e);
                    }
                });
            }

            @Override
            public void onError(Exception e) {
                mActivity.runOnUiThread(() -> mUICallback.handleError(e));
            }
        });
        init(mLuaPath, arg);
    }

    /**
     * 读 Object[] 形态的 serializable extra：外部可构造类型不符的 extra，裸强转必 CCE；
     * API 33+ 走类型化重载（类型不符返回 null），旧系统用 instanceof 防护。
     */
    private static Object[] getSerializableArray(Intent intent, String name) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            return intent.getSerializableExtra(name, Object[].class);
        }
        Object v = intent.getSerializableExtra(name);
        return v instanceof Object[] arr ? arr : null;
    }

    /**
     * 把 5 个按键/触摸全局快照进字段，只在引擎初始化成功那一刻执行一次
     * ⇒ 这 5 个全局必须在主 chunk 加载期装好，运行期再赋值不会生效。
     */
    private void cacheLuaEventHandlers() {
        Globals g = getLuaState();
        mOnKeyShortcut = getLuaFunc(g, "onKeyShortcut");
        mOnKeyDown = getLuaFunc(g, "onKeyDown");
        mOnKeyUp = getLuaFunc(g, "onKeyUp");
        mOnKeyLongPress = getLuaFunc(g, "onKeyLongPress");
        mOnTouchEvent = getLuaFunc(g, "onTouchEvent");
    }

    private LuaValue getLuaFunc(Globals g, String name) {
        LuaValue f = g.get(name);
        return f.isnil() ? null : f;
    }

    // ==================== 生命周期回调 ====================

    public void onNewIntent(Intent intent) {
        mActivity.setIntent(intent);
        runFunc("onNewIntent", intent);
    }

    public void onStart() {
        runFunc("onStart");
    }

    public void onResume() {
        runFunc("onResume");
    }

    public void onPause() {
        runFunc("onPause");
    }

    public void onStop() {
        runFunc("onStop");
    }

    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        runFunc("onRequestPermissionsResult", requestCode, permissions, grantResults);
    }

    // ==================== 菜单 ====================

    public boolean onCreateOptionsMenu(Menu menu) {
        runFunc("onCreateOptionsMenu", menu);
        return true;
    }

    public boolean onOptionsItemSelected(MenuItem item) {
        Object ret = !item.hasSubMenu() ? runFunc("onOptionsItemSelected", item) : null;
        if (ret instanceof Boolean handled && handled) return true;
        if (!item.hasSubMenu()) runFunc("onMenuItemSelected", item.getItemId(), item);
        return false;
    }

    // ==================== 按键与触摸事件 ====================

    public boolean onKeyShortcut(int keyCode, KeyEvent event) {
        return dispatchKey(mOnKeyShortcut, "onKeyShortcut", keyCode, event);
    }

    public boolean onKeyDown(int keyCode, KeyEvent event) {
        return dispatchKey(mOnKeyDown, "onKeyDown", keyCode, event);
    }

    public boolean onKeyUp(int keyCode, KeyEvent event) {
        return dispatchKey(mOnKeyUp, "onKeyUp", keyCode, event);
    }

    public boolean onKeyLongPress(int keyCode, KeyEvent event) {
        return dispatchKey(mOnKeyLongPress, "onKeyLongPress", keyCode, event);
    }

    public boolean onTouchEvent(MotionEvent event) {
        if (mOnTouchEvent == null) return false;
        try {
            Object ret = JavaCall.call(mOnTouchEvent, event);
            return ret instanceof Boolean handled && handled;
        } catch (LuaError e) {
            sendError("onTouchEvent", e);
            return false;
        }
    }

    /**
     * 四个按键回调共用的派发：Lua 返回 true 表示已消费。
     * 定参而非 Object... —— 触摸/按键是高频入口，varargs 每次会多分配一个数组。
     */
    private boolean dispatchKey(LuaValue handler, String name, int keyCode, KeyEvent event) {
        if (handler == null) return false;
        try {
            Object ret = JavaCall.call(handler, keyCode, event);
            return ret instanceof Boolean handled && handled;
        } catch (LuaError e) {
            sendError(name, e);
            return false;
        }
    }

    // ==================== 广播 / Service 绑定 ====================
    // registerReceiver/unregisterReceiver 与 bindService(String,...)/startService/stopService
    // 在 BaseDelegate（mContext 统一）；此处只保留 Activity 特有的默认连接封装。

    public boolean bindService(int flags) {
        return bindService(new ServiceConnection() {
            @Override
            public void onServiceConnected(ComponentName comp, IBinder binder) {
                runFunc("onServiceConnected", comp, ((LuaService.LuaBinder) binder).getService());
            }

            @Override
            public void onServiceDisconnected(ComponentName comp) {
                runFunc("onServiceDisconnected", comp);
            }
        }, flags);
    }

    // ==================== ActivityResult 回调 ====================

    // ==================== SAF 选择器流程（SafDelegate 实现） ====================

    @Override
    @CallLuaFunction(value = CallLuaFunction.Thread.MAIN,
            note = "经 onActivityResult 主线程派发")
    public void openDocumentTree(LuaFunction function) {
        startForResult(Intent.ACTION_OPEN_DOCUMENT_TREE, function);
    }

    @Override
    @CallLuaFunction(value = CallLuaFunction.Thread.MAIN,
            note = "经 onActivityResult 主线程派发")
    public void getDocument(String type, LuaFunction function) {
        startForResult(Intent.ACTION_OPEN_DOCUMENT, function, type);
    }

    @Override
    @CallLuaFunction(value = CallLuaFunction.Thread.MAIN,
            note = "经 onActivityResult 主线程派发")
    public void createDocument(String type, String name, LuaFunction function) {
        Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType(type);
        intent.putExtra(Intent.EXTRA_TITLE, name);
        int requestCode = function.hashCode();
        registerCallback(requestCode, function);
        mActivity.startActivityForResult(intent, requestCode);
    }

    private void startForResult(String action, LuaFunction function, String... mimeTypes) {
        Intent intent = new Intent(action);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        if (mimeTypes.length > 0 && !mimeTypes[0].isEmpty()) intent.setType(mimeTypes[0]);
        int requestCode = function.hashCode();
        registerCallback(requestCode, function);
        mActivity.startActivityForResult(intent, requestCode);
    }

    /**
     * 注册 requestCode → Lua 回调。由 onActivityResult 分发，Android 保证在主线程。
     */
    void registerCallback(int code, LuaFunction function) {
        mResultCallbacks.put(code, function);
    }

    public boolean onActivityResult(int requestCode, int resultCode, Intent data) {
        LuaFunction func = mResultCallbacks.remove(requestCode);
        // 必须走 JavaCall.call（经 LuaCall.invoke 进执行区）：裸 func.call 会与
        // 主线程/IO 池的 Lua 执行并发直捣解释器栈。
        if (func != null) JavaCall.call(func, Coercion.toLua(data));
        if (data != null) {
            String name = data.getStringExtra("name");
            if (name != null) {
                Object[] res = getSerializableArray(data, "data");
                if (res == null) {
                    runFunc("onResult", name);
                } else {
                    Object[] arg = new Object[res.length + 1];
                    arg[0] = name;
                    System.arraycopy(res, 0, arg, 1, res.length);
                    Object ret = runFunc("onResult", arg);
                    if (ret instanceof Boolean handled && handled) return true;
                }
            }
        }
        runFunc("onActivityResult", requestCode, resultCode, data);
        return false;
    }

    /**
     * 回调接口，由 LuaActivity 提供 UI 操作能力
     */
    public interface UICallback {
        void applyDefaultView();

        void setTitle(CharSequence title);

        boolean isSetViewed();

        void handleError(Exception e);

        void handleVersionChanged(Bundle savedInstanceState);
    }
}
