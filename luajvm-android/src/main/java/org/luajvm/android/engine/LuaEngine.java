package org.luajvm.android.engine;

import android.content.Context;
import android.graphics.Rect;
import android.os.Build;
import android.util.DisplayMetrics;
import android.view.WindowManager;

import org.luajvm.android.AndroidLuaJavaContext;
import org.luajvm.android.runtime.DebugProps;
import org.luajvm.android.runtime.LuaConfig;
import org.luajvm.android.runtime.LuaLog;
import org.luajvm.android.runtime.LuaPathResolver;
import org.luajvm.android.runtime.LuaScheduler;
import org.luajvm.android.LuaApplication;
import org.luajvm.android.api.LuaContext;
import org.luajvm.android.api.LuaGcable;

import org.luajvm.bind.Coercion;
import org.luajvm.bind.JavaCall;
import org.luajvm.bind.JavaLib;
import org.luajvm.bind.Platform;
import org.luajvm.core.Globals;
import org.luajvm.core.LuaError;
import org.luajvm.core.LuaErrors;
import org.luajvm.core.LuaFunction;
import org.luajvm.core.LuaString;
import org.luajvm.core.LuaTable;
import org.luajvm.core.LuaValue;
import org.luajvm.lib.BaseLib;
import org.luajvm.core.Varargs;
import org.luajvm.vm.LuaCall;

import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.Objects;


/**
 * Lua 执行引擎，负责 Lua 状态管理和脚本执行。
 */
public class LuaEngine implements LuaContext {
    protected final Context mContext;
    protected final Object mHost;
    protected final String mHostName;
    protected final LuaContext mLuaContext;
    protected final LuaPathResolver mPathResolver;
    private final List<LuaGcable> mGcList = new CopyOnWriteArrayList<>();
    // sendError → onError 回传进行中（防 onError 自身报错递归回传）。
    // 按线程各持一份：sendError 可从 IO/WebView 线程进来，用一个共享标志会让某线程
    //   正在回传期间、另一线程的错误被当作递归而丢掉
    private final ThreadLocal<Boolean> mDispatchingOnError = new ThreadLocal<>();
    private ModuleInstaller mModuleInstaller;
    protected String mLuaFileName;
    protected String mLuaFile;
    protected Globals mGlobals;
    /**
     * 供 {@code luajava.bindClass} 兜底查类的 ClassLoader 链，默认空表；
     * 宿主/脚本往里加 loader 后 {@code bindClass} 会真的用上。
     */
    protected final ArrayList<ClassLoader> mClassLoaders = new ArrayList<>();
    protected int mWidth;
    protected int mHeight;
    protected float mDensity;
    private OnInitListener mInitListener;

    /** 占位 C 搜索器：返回 nil，require 视为该搜索器无结果且不产生消息行。 */
    private static final LuaFunction NOP_C_SEARCHER = new LuaFunction() {
        @Override
        public Varargs call(Varargs args) {
            return LuaValue.NIL;
        }
    };

    public LuaEngine(Context context, Object host, String hostName) {
        // 必须在任何引擎类初始化之前：A/B 开关是 static final，类初始化时求值一次。
        //   文件不存在时零影响；见 DebugProps。
        DebugProps.loadOnce(context);
        mContext = context;
        mHost = host;
        mHostName = hostName;
        mPathResolver = new LuaPathResolver(mContext);
        mLuaContext = mContext instanceof LuaContext ctx ? ctx : this;
    }

    public void setOnInitListener(OnInitListener listener) {
        mInitListener = listener;
    }

    /**
     * 注入标准模块注册器。必须在 {@link #init(String, Object[])} 之前调用；
     * 宿主层（BaseDelegate）构造时自动注入。
     */
    public void setModuleInstaller(ModuleInstaller installer) {
        mModuleInstaller = installer;
    }

    public void init(String luaPath) {
        init(luaPath, null);
    }

    public void init(String luaPath, Object[] arg) {
        if (luaPath == null || luaPath.isEmpty()) {
            throw new IllegalArgumentException("luaPath cannot be null or empty");
        }
        mLuaFile = luaPath;
        File f = new File(luaPath);
        String luaDir = f.getParent();
        if (luaDir == null) {
            luaDir = mContext.getFilesDir().getAbsolutePath();
        }
        mPathResolver.setLuaDir(luaDir);
        mLuaFileName = f.getName();
        try {
            mPathResolver.setRootDir(LuaPathResolver.findRoot(luaDir));
        } catch (RuntimeException e) {
            mPathResolver.setRootDir(luaDir);
            sendMsg("Warning: " + e.getMessage());
        }
        initSize();
        // 预编译字节码优先：构建期用 org.luajvm.tools.LuacCompiler 把 assets 下的 .lua
        //   编成同名 .luac，loadFile 命中后跳过 Lexer/Parser/CodeGen，缺失/损坏静默回落源码。
        //   Android 宿主是 Activity/Service，无命令行可传 -Dluajvm.luac，必须编程开启。
        BaseLib.setLuacPreferred(true);
        mGlobals = Platform.standardGlobals();

        initEnv();
        // java diff: Globals.luajavaLib 的声明类型是 LuaValue（core 不认识 bind.JavaLib，
        //   见 CoreLayerContractTest 的分层规则），故经 bind 侧访问器还原具体类型。
        //   standardGlobals() 必然装配 luajava，具名异常兜底将来换 bareGlobals() 的误改。
        JavaLib javaLib = JavaLib.forGlobals(mGlobals);
        if (javaLib == null) {
            throw new IllegalStateException("luajava 未装配：Globals.luajavaLib 不是 JavaLib");
        }
        javaLib.classLoaders = mClassLoaders;
        AndroidLuaJavaContext.setLuaContext(getLuaContext());
        String luaPathStr = LuaPathResolver.buildLuaPath(luaDir);
        String rootLuaPath = LuaPathResolver.buildLuaPath(mPathResolver.getRootDir());

        LuaTable pkg = (LuaTable) mGlobals.get("package");
        if (pkg != null) {
            pkg.set("path", LuaString.newStr(luaPathStr + ";" + rootLuaPath));
        }
        try {
            registerBaseModules();
            if (mModuleInstaller != null) mModuleInstaller.installModules(this);
            JavaCall.set(mGlobals, mHostName, mHost);
            JavaCall.set(mGlobals, "this", mHost);
            // java diff: Globals.loadfile 丢弃第二返回值，这里直调 baselib.loadFile 取
            //   (NIL, 错误消息)，加载失败才能进 onError 而非静默 onSuccess
            Varargs loaded = loadFileWithMsg(mLuaFile);
            if (!loaded.arg1().isfunction()) {
                throw LuaErrors.errorObject(loaded.arg(2).isnil()
                        ? "cannot load " + mLuaFile : loaded.arg(2).toJavaString());
            }
            if (arg != null && arg.length > 0) {
                JavaCall.call(loaded.arg1(), arg);
            } else {
                JavaCall.call(loaded.arg1());
            }
            if (mInitListener != null) mInitListener.onSuccess();
        } catch (Exception e) {
            sendError("Lua init error", e);
            if (mInitListener != null) mInitListener.onError(e);
        }
    }

    /**
     * 注册基础 Lua 模块。
     *
     * @deprecated 标准模块集已迁至宿主层（BaseDelegate.registerStandardModules），
     *     经 {@link ModuleInstaller} 在 init 序列同一注册点注入；本方法保留为外部
     *     子类的覆写扩展点（默认空实现，调用时序不变）。
     */
    @Deprecated
    protected void registerBaseModules() {
    }

    /**
     * 读取 init.lua 配置
     */
    protected void initEnv() {
        File initFile = new File(mPathResolver.getLuaDir() + "/init.lua");
        if (!initFile.exists()) return;
        try {
            LuaValue f = mGlobals.loadfile("init.lua");
            if (f.isfunction()) LuaCall.invoke(f, LuaValue.NONE);
            LuaValue debug = mGlobals.get("debugmode");
            if (debug.isnil()) debug = mGlobals.get("debug_mode");
            if (debug.isboolean()) LuaConfig.setDebug(debug.toboolean());
        } catch (Exception e) {
            sendMsg(e.getMessage());
        }
    }

    /**
     * 获取屏幕尺寸。
     *
     * <p>API 30 起用 {@code getMaximumWindowMetrics}：它给的是该显示区上最大可能窗口的
     * bounds，等于屏幕尺寸，与 {@code getDefaultDisplay().getMetrics()} 同值。
     * {@code getCurrentWindowMetrics} 不能用——分屏/自由窗口下它给当前窗口大小，
     * 会让 Lua 侧的 {@code width}/{@code height} 随窗口变化。
     * density 两条路径都从 {@code Resources} 取，不受此影响。
     */
    protected void initSize() {
        var wm = (WindowManager)
                mContext.getSystemService(Context.WINDOW_SERVICE);
        if (wm == null) return;
        mDensity = mContext.getResources().getDisplayMetrics().density;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Rect bounds = wm.getMaximumWindowMetrics().getBounds();
            mWidth = bounds.width();
            mHeight = bounds.height();
        } else {
            var metrics = new DisplayMetrics();
            wm.getDefaultDisplay().getMetrics(metrics);
            mWidth = metrics.widthPixels;
            mHeight = metrics.heightPixels;
        }
    }

    public Object runFunc(String name, Object... args) {
        try {
            LuaValue func = mGlobals.get(name);
            if (func.isfunction()) return JavaCall.call(func, args);
        } catch (Exception e) {
            sendError(name, e);
        }
        return null;
    }

    public boolean runBooleanFunc(String name, Object... args) {
        try {
            LuaValue func = mGlobals.get(name);
            if (func == null || func.isnil()) return false;
            int len = args != null ? args.length : 0;
            LuaValue[] luaArgs = new LuaValue[len];
            for (int i = 0; i < len; i++) {
                luaArgs[i] = Coercion.toLua(args[i]);
            }
            return LuaCall.invoke(func, Varargs.of(luaArgs)).arg1().toboolean();
        } catch (Exception e) {
            sendError(name, e);
            return false;
        }
    }

    @Override
    public void sendMsg(String msg) {
        LuaLog.getInstance().add(msg);
    }

    @Override
    public void sendError(String title, Exception e) {
        // 错误进 LuaLog（applyDefaultView 的日志列表可见），不经 Lua print：
        //   print 是脚本普通输出通道，错误借道会把每条错误混进正常日志流
        LuaLog.getInstance().addError(title, e);
        dispatchOnError(title, e);
    }

    /**
     * 回传 Lua 全局 onError(title, message)。
     * onError 未定义或为 nil 时静默跳过；onError 自身抛错时只进日志，
     * 不再递归回传（否则 onError 报错 → sendError → onError → 无限循环）。
     */
    private void dispatchOnError(String title, Exception e) {
        if (mGlobals == null || Boolean.TRUE.equals(mDispatchingOnError.get())) return;
        LuaValue func = mGlobals.get("onError");
        if (!func.isfunction()) return;
        // e.getMessage() 可能为 null，退到 e.toString() 保住异常类名；LuaError 追加
        //   traceback，让 onError 的弹窗/落盘拿到出错调用链而不止一条消息
        String detail = e == null ? "" : Objects.requireNonNullElse(e.getMessage(), e.toString());
        String tb = LuaLog.tracebackOf(e);
        if (!tb.isEmpty()) detail = detail + "\n" + tb;
        mDispatchingOnError.set(Boolean.TRUE);
        try {
            JavaCall.call(func, title, detail);
        } catch (Exception err) {
            LuaLog.getInstance().addError("onError", err);
        } finally {
            mDispatchingOnError.remove();
        }
    }

    public void destroy() {
        // 先于下面的 mGlobals==null 早退：无论引擎是否初始化成功，都必须解开
        //   进程级 static 对本 Activity 的强引用（见 AndroidLuaJavaContext.clearLuaContext）。
        AndroidLuaJavaContext.clearLuaContext(mLuaContext);
        if (mGlobals == null) return;
        runFunc("onDestroy");
        for (LuaGcable gc : mGcList) {
            try {
                gc.gc();
            } catch (Exception ignored) {
            }
        }
        mGcList.clear();
    }

    @Override
    public InputStream findResource(String name) {
        try {
            if (new File(name).exists()) return new FileInputStream(name);
        } catch (Exception ignored) {
        }
        try {
            String path = mPathResolver.getLuaPath(name);
            if (new File(path).exists()) return new FileInputStream(path);
        } catch (Exception ignored) {
        }
        try {
            return mContext.getAssets().open(name);
        } catch (Exception ignored) {
        }
        return null;
    }

    // ==================== ResourceFinder ====================

    @Override
    public String findFile(String filename) {
        if (filename.startsWith("/")) return filename;
        return mPathResolver.getLuaPath(filename);
    }

    @Override
    public ArrayList<ClassLoader> getClassLoaders() {
        return mClassLoaders;
    }

    // ==================== LuaContext 接口实现 ====================

    @Override
    public void call(String func, Object... args) {
        JavaCall.call(mGlobals.get(func), args);
    }

    @Override
    public void set(String name, Object value) {
        JavaCall.set(mGlobals, name, value);
    }

    @Override
    public String getLuaPath() {
        return mLuaFile;
    }

    @Override
    public String getLuaPath(String path) {
        return mPathResolver.getLuaPath(path);
    }

    @Override
    public String getLuaPath(String dir, String name) {
        return mPathResolver.getLuaPath(dir, name);
    }

    @Override
    public String getLuaDir() {
        return mPathResolver.getLuaDir();
    }

    @Override
    public String getLuaDir(String dir) {
        return mPathResolver.getLuaDir(dir);
    }

    // extDir 系全部经 resolver，与 BaseDelegate→resolver 的生产路径（公共外部存储 LuaJVM）
    // 保持同一语义，不私自回落 getExternalFilesDir
    @Override
    public String getLuaExtDir() {
        return mPathResolver.getExtDir();
    }

    @Override
    public void setLuaExtDir(String dir) {
        mPathResolver.setExtDir(dir);
    }

    @Override
    public String getLuaExtDir(String dir) {
        return mPathResolver.getExtDir(dir);
    }

    @Override
    public String getLuaExtPath(String path) {
        return mPathResolver.getExtPath(path);
    }

    @Override
    public String getLuaExtPath(String dir, String name) {
        return mPathResolver.getExtPath(dir, name);
    }

    @Override
    public String getRootDir() {
        return mPathResolver.getRootDir();
    }

    @Override
    public Context getContext() {
        return mContext;
    }

    public LuaContext getLuaContext() {
        return mLuaContext;
    }

    @Override
    public Globals getLuaState() {
        return mGlobals;
    }

    @Override
    public int getWidth() {
        return mWidth;
    }

    @Override
    public int getHeight() {
        return mHeight;
    }

    @Override
    public float getDensity() {
        return mDensity;
    }

    /** 直调 baselib 取错误消息前先判空：Globals.loadfile 有这个具名守卫，裸调只会 NPE。 */
    private Varargs loadFileWithMsg(String path) {
        if (mGlobals.baselib == null) throw LuaErrors.errorObject("no baselib");
        return mGlobals.baselib.loadFile(path, "bt", mGlobals);
    }

    @Override
    public Object doFile(String path, Object... arg) {
        // 与 init 同款：加载失败取第二返回值 sendError，不再静默返回 null
        Varargs loaded = loadFileWithMsg(path);
        if (!loaded.arg1().isfunction()) {
            sendError("doFile", LuaErrors.errorObject(loaded.arg(2).isnil()
                    ? "cannot load " + path : loaded.arg(2).toJavaString()));
            return null;
        }
        return JavaCall.call(loaded.arg1(), arg);
    }

    // sharedData/globalData 与 BaseDelegate 同后端（LuaApplication 持久化）。
    // 唯一持久化后端；生产路径下宿主总是 LuaHost（即 LuaContext）。
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
        mGcList.add(obj);
    }

    public boolean isDebug() {
        return LuaConfig.isDebug();
    }

    // ==================== 工具方法 ====================

    public void setDebug(boolean debug) {
        LuaConfig.setDebug(debug);
    }

    public LuaPathResolver getPathResolver() {
        return mPathResolver;
    }

    public interface OnInitListener {
        void onSuccess();

        void onError(Exception e);
    }
}
