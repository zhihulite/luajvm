package org.luajvm.android.host;

import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.ServiceConnection;
import android.net.Uri;

import androidx.core.content.ContextCompat;

import org.luajvm.android.runtime.LuaConfig;
import org.luajvm.android.LuaApplication;
import org.luajvm.android.api.LuaContext;
import org.luajvm.android.api.LuaHostDelegate;
import org.luajvm.android.api.LuaSafHost;
import org.luajvm.android.engine.LuaEngine;
import org.luajvm.android.engine.ModuleInstaller;
import org.luajvm.android.api.LuaGcable;
import org.luajvm.android.lib.Http;
import org.luajvm.android.lib.SyncHttp;
import org.luajvm.android.lib.file;
import org.luajvm.android.lib.json;
import org.luajvm.android.lib.loadbitmap;
import org.luajvm.android.lib.loadmenu;
import org.luajvm.android.lib.print;
import org.luajvm.android.lib.printf;
import org.luajvm.android.lib.saf;
import org.luajvm.android.lib.task;
import org.luajvm.android.lib.thread;
import org.luajvm.android.lib.timer;
import org.luajvm.android.util.LuaBroadcastReceiver;
import org.luajvm.android.widget.loadlayout;
import org.luajvm.android.lib.res;

import org.luajvm.bind.Coercion;
import org.luajvm.bind.JavaCall;
import org.luajvm.bind.JavaLib;
import org.luajvm.core.Globals;
import org.luajvm.core.LuaError;
import org.luajvm.core.LuaErrors;
import org.luajvm.core.LuaValue;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Delegate 基类，提取 ActivityDelegate 和 ServiceDelegate 的公共代码。
 */
public abstract class BaseDelegate implements LuaHostDelegate {

    private final Context mContext;
    private final LuaEngine mEngine;
    // 多槽：每次注册一个新 receiver（旧的单槽第二次注册会静默顶掉第一个）；
    //   volatile 需求由 COW 列表承担——Lua 可在 thread/task 的 IO 线程注册，注销发生在主线程
    private final List<LuaBroadcastReceiver> mReceivers = new CopyOnWriteArrayList<>();

    protected BaseDelegate(Context context, LuaEngine engine) {
        mContext = context;
        mEngine = engine;
        mEngine.setModuleInstaller(this::registerStandardModules);
    }

    /**
     * 安装全部标准 Lua 模块（宿主层是 engine 与 lib 之间唯一的组合点）。
     * 经 {@link ModuleInstaller} 在 LuaEngine.init 序列的注册点被调；
     * 全局名与注册顺序是 Lua 可见契约，不得随意变动。
     */
    protected void registerStandardModules(LuaEngine engine) {
        Globals g = engine.getLuaState();
        LuaContext ctx = engine.getLuaContext();
        g.set("print", new print(ctx));
        g.set("printf", new printf(ctx));
        g.set("loadlayout", new loadlayout(ctx));
        g.set("loadbitmap", new loadbitmap(ctx));
        g.set("loadmenu", new loadmenu(ctx));
        g.set("task", new task(ctx));
        g.set("thread", new thread(ctx));
        g.set("timer", new timer(ctx));
        // saf：Storage Access Framework 封装，整套流程走 Activity 的文件选择器回调，
        //   只有 Activity 宿主注册（Service 类宿主拿不到选择器，注册了调用时 NPE）。
        //   Lua 侧用法：saf.select(fn) / saf.list(fn) / saf.read(fn) / saf.save(...)。
        if (mContext instanceof LuaSafHost safHost) {
            g.set("saf", Coercion.toLua(new saf(safHost)));
        }
        g.loadLib(new res(ctx), "res");
        g.loadLib(new json(), "json");
        g.loadLib(new file(engine::findFile), "file");
        JavaCall.set(g, "Http", Http.class);
        JavaCall.set(g, "http", SyncHttp.class);
        g.set("android", new JavaLib.Package(g, "android"));
        g.set("java", new JavaLib.Package(g, "java"));
        g.set("com", new JavaLib.Package(g, "com"));
        g.set("org", new JavaLib.Package(g, "org"));
    }

    // ==================== 引擎生命周期 ====================

    protected LuaEngine getEngine() {
        return mEngine;
    }

    public void setOnInitListener(LuaEngine.OnInitListener listener) {
        mEngine.setOnInitListener(listener);
    }

    public void init(String luaPath, Object[] arg) {
        // Service 类宿主由系统直接拉起、不经过 Welcome：脚本加载前须等 assets 就绪
        LuaApplication app = LuaApplication.getInstance();
        if (app != null) app.awaitAssets();
        mEngine.init(luaPath, arg);
    }

    public void destroy() {
        // Service 宿主的 onDestroy 直接调 destroy()：receiver 不在此注销会常驻泄漏
        //   （Activity 路径重复注销幂等无害）
        unregisterReceiver();
        mEngine.destroy();
    }

    /**
     * 销毁引擎并清理资源：注销广播 → 引擎销毁。Lua 的 onDestroy 由
     * LuaEngine.destroy 统一触发——此处不能再 runFunc 一次，否则 Activity 路径会双调
     * （脚本侧幂等性不可依赖）。
     */
    public void destroyEngine() {
        destroy();
    }

    /**
     * 按主脚本文件名（或 main）调用入口函数；welcome 脚本跳过。
     */
    protected void runMainFunc(Object[] arg) {
        LuaEngine engine = getEngine();
        String name = new File(engine.getLuaPath()).getName();
        int idx = name.lastIndexOf(".");
        if (idx > 0) name = name.substring(0, idx);
        if (name.equals("welcome")) return;
        Globals g = engine.getLuaState();
        LuaValue f = g.get(name);
        if (!f.isfunction()) f = g.get("main");
        if (f.isfunction()) JavaCall.call(f, arg);
    }

    // ==================== 广播 ====================

    /**
     * 旧签名，保持 NOT_EXPORTED：API 33+ 只收本 app 的广播。
     */
    public Intent registerReceiver(IntentFilter filter) {
        return registerReceiver(filter, false);
    }

    /**
     * exported=true 才能收其他 app 的广播（API 33+ 强制显式声明，否则外部广播静默丢失）；
     * 注册的 receiver 统一由 unregisterReceiver()/destroy() 全部注销。
     */
    public Intent registerReceiver(IntentFilter filter, boolean exported) {
        LuaBroadcastReceiver receiver =
                new LuaBroadcastReceiver((context, intent) -> runFunc("onReceive", context, intent));
        int flags = exported ? ContextCompat.RECEIVER_EXPORTED : ContextCompat.RECEIVER_NOT_EXPORTED;
        Intent sticky = ContextCompat.registerReceiver(mContext, receiver, filter, flags);
        // 注册成功后才登记：注册抛异常时先登记会让之后的注销报 Receiver not registered
        mReceivers.add(receiver);
        return sticky;
    }

    public void unregisterReceiver() {
        // 逐个 remove(Object) 而不是"遍历快照后 clear"：COW 的迭代器是创建时快照，
        //   clear() 会把遍历期间新注册的 receiver 一并抹掉却从不注销（常驻泄漏）；
        //   remove 只让一个线程拿到 true，并发注销也不会对同一个 receiver 注销两次
        for (LuaBroadcastReceiver receiver : mReceivers) {
            if (mReceivers.remove(receiver))
                LuaConfig.runSafely(() -> mContext.unregisterReceiver(receiver), "unregisterReceiver");
        }
    }

    // ==================== Service 绑定 ====================

    public boolean bindService(ServiceConnection conn, int flag) {
        return bindService("service.lua", conn, flag);
    }

    public boolean bindService(String path, ServiceConnection conn, int flag) {
        String fullPath;
        try {
            fullPath = LuaIntentHelper.resolveLuaPath(getLuaDir(), path);
        } catch (FileNotFoundException e) {
            throw LuaErrors.errorObject(e);
        }
        LuaService.setLuaPath(fullPath);
        try {
            LuaService.setEnabled(mContext, null);
        } catch (IllegalStateException e) {
            // ISE 来自后台 startService，不是 bindService；记下后照旧继续 bind——
            //   BIND_AUTO_CREATE 不受后台限制，返回 false 反而白挡掉一次合法的 bind
            sendError("bindService", e);
        }
        return mContext.bindService(new Intent(mContext, LuaService.class), conn, flag);
    }

    public void startService(String path, Object[] arg) throws FileNotFoundException {
        String fullPath = LuaIntentHelper.resolveLuaPath(getLuaDir(), path);
        try {
            LuaService.setEnabled(mContext, fullPath);
        } catch (IllegalStateException e) {
            // API 26+ 后台 startService 抛 ISE：需前台服务，这里不擅自加通知，转 sendError
            sendError("startService", e);
        }
    }

    public boolean stopService() {
        return mContext.stopService(new Intent(mContext, LuaService.class));
    }

    // ==================== LuaMetaTable 支持 ====================

    public LuaValue __index(LuaValue key) {
        return getLuaState().get(key);
    }

    public void __newindex(LuaValue key, LuaValue value) {
        getLuaState().set(key, value);
    }

    // ==================== 脚本执行 ====================

    public Object runFunc(String name, Object... args) {
        return mEngine.runFunc(name, args);
    }

    public void sendMsg(String msg) {
        mEngine.sendMsg(msg);
    }

    public void sendError(String title, Exception e) {
        mEngine.sendError(title, e);
    }

    // ==================== Activity 跳转 ====================

    public void newActivity(String path) throws FileNotFoundException {
        LuaIntentHelper.newActivity(mContext, getLuaDir(), path);
    }

    public void newActivity(String path, Object[] arg) throws FileNotFoundException {
        LuaIntentHelper.newActivity(mContext, getLuaDir(), path, arg);
    }

    public void newActivity(int requestCode, String path) throws FileNotFoundException {
        LuaIntentHelper.newActivity(mContext, getLuaDir(), requestCode, path);
    }

    public void newActivity(int requestCode, String path, Object[] arg) throws FileNotFoundException {
        LuaIntentHelper.newActivity(mContext, getLuaDir(), requestCode, path, arg);
    }

    public void newActivity(int requestCode, String path, Object[] arg, boolean newDocument) throws FileNotFoundException {
        LuaIntentHelper.newActivity(mContext, getLuaDir(), requestCode, path, arg, newDocument);
    }

    // ==================== 文件工具 ====================

    public Uri getUriForFile(File file) {
        return LuaIntentHelper.getUriForFile(mContext, file);
    }

    public String getPathFromUri(Uri uri) {
        return LuaIntentHelper.getPathFromUri(mContext, uri);
    }

    public void installApk(String path) {
        LuaIntentHelper.installApk(mContext, path);
    }

    public void openFile(String path) {
        LuaIntentHelper.openFile(mContext, path);
    }

    public void shareFile(String path) {
        LuaIntentHelper.shareFile(mContext, path);
    }

    // ==================== LuaContext 接口实现 ====================

    @Override
    public ArrayList<ClassLoader> getClassLoaders() {
        return mEngine.getClassLoaders();
    }

    @Override
    public void call(String func, Object... args) {
        mEngine.call(func, args);
    }

    @Override
    public void set(String name, Object value) {
        mEngine.set(name, value);
    }

    @Override
    public String getLuaPath() {
        return mEngine.getLuaPath();
    }

    @Override
    public String getLuaPath(String path) {
        return mEngine.getLuaPath(path);
    }

    @Override
    public String getLuaPath(String dir, String name) {
        return mEngine.getPathResolver().getLuaPath(dir, name);
    }

    @Override
    public String getLuaDir() {
        return mEngine.getLuaDir();
    }

    @Override
    public String getLuaDir(String dir) {
        return mEngine.getPathResolver().getLuaDir(dir);
    }

    @Override
    public String getLuaExtDir() {
        return mEngine.getPathResolver().getExtDir();
    }

    @Override
    public void setLuaExtDir(String dir) {
        mEngine.getPathResolver().setExtDir(dir);
    }

    @Override
    public String getLuaExtDir(String dir) {
        return mEngine.getPathResolver().getExtDir(dir);
    }

    @Override
    public String getLuaExtPath(String path) {
        return mEngine.getPathResolver().getExtPath(path);
    }

    @Override
    public String getLuaExtPath(String dir, String name) {
        return mEngine.getPathResolver().getExtPath(dir, name);
    }

    @Override
    public String getRootDir() {
        return mEngine.getRootDir();
    }

    @Override
    public Context getContext() {
        return mContext;
    }

    @Override
    public Globals getLuaState() {
        return mEngine.getLuaState();
    }

    @Override
    public Object doFile(String path, Object... arg) {
        return mEngine.doFile(path, arg);
    }

    @Override
    public int getWidth() {
        return mEngine.getWidth();
    }

    @Override
    public int getHeight() {
        return mEngine.getHeight();
    }

    @Override
    public float getDensity() {
        return mEngine.getDensity();
    }

    @Override
    public Map<String, Object> getGlobalData() {
        return LuaApplication.getInstance().getGlobalData();
    }

    @Override
    public Map<String, ?> getSharedData() {
        return LuaApplication.getInstance().getSharedData();
    }

    @Override
    public Object getSharedData(String key) {
        return LuaApplication.getInstance().getSharedData(key);
    }

    @Override
    public Object getSharedData(String key, Object def) {
        return LuaApplication.getInstance().getSharedData(key, def);
    }

    @Override
    public boolean setSharedData(String key, Object value) {
        return LuaApplication.getInstance().setSharedData(key, value);
    }

    @Override
    public void regGc(LuaGcable obj) {
        mEngine.regGc(obj);
    }

    @Override
    public InputStream findResource(String name) {
        return mEngine.findResource(name);
    }

    @Override
    public String findFile(String filename) {
        return mEngine.findFile(filename);
    }
}
