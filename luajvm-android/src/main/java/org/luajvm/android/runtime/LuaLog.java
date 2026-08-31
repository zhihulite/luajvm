package org.luajvm.android.runtime;

import android.util.Log;

import org.luajvm.core.Globals.DebugFrame;
import org.luajvm.core.LuaError;
import org.luajvm.core.LuaThread;
import org.luajvm.core.LuaValue;
import org.luajvm.lib.DebugHook;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 集中式 Lua 日志管理器。
 */
public final class LuaLog {

    private static final LuaLog INSTANCE = new LuaLog();
    private static final int MAX_LOG_SIZE = 500;
    // SimpleDateFormat 非线程安全（内部 Calendar 是可变共享状态），而 add() 会被多线程调：
    //   print -> sendMsg、thread/task 丢到 LuaScheduler IO 池、LuaServer 的 socket 线程；
    //   共享一个实例会产出乱码时间戳甚至抛 ArrayIndexOutOfBoundsException。
    //   用 ThreadLocal 而非 java.time：本库 minSdk 24 且未开 core library desugaring。
    private static final ThreadLocal<SimpleDateFormat> FORMAT =
            new ThreadLocal<SimpleDateFormat>() {
            @Override protected SimpleDateFormat initialValue() {
                return new SimpleDateFormat("HH:mm:ss.SSS", Locale.getDefault());
            }
        };

    // CopyOnWriteArrayList 替代 ArrayList + synchronized：日志写少读多
    //   （UI 的 ArrayAdapter 直接持有本列表引用并反复遍历），迭代快照天然避免
    //   ConcurrentModificationException，且读路径不阻塞（synchronized 会 pin 虚拟线程 carrier）。
    private final CopyOnWriteArrayList<String> mLogs = new CopyOnWriteArrayList<>();
    private volatile boolean mDebug = false;

    private LuaLog() {
    }

    public static LuaLog getInstance() {
        return INSTANCE;
    }

    // ==================== 添加日志 ====================

    /**
     * 将 LuaError.savedStack 格式化为 Lua 风格 traceback（与 CLI 一致）。
     * 委托引擎标准实现：帧名带 namewhat 限定（"[C]: in global 'require'"）、source 经
     * chunkid 去掉 '@' 前缀，与 debug.traceback 及 C 逐字一致。
     *
     * <p>非 LuaError 或无快照时返回空串，供错误回传方拼接。
     */
    public static String tracebackOf(Throwable e) {
        return e instanceof LuaError le ? formatLuaTraceback(le) : "";
    }

    private static String formatLuaTraceback(LuaError le) {
        // LuaError 惰性快照：不主动 ensure 的话 savedStack 恒为 null，traceback 拼不出来
        le.ensureSnapshot();
        ArrayList<DebugFrame> frames = le.savedStack;
        if (frames == null || frames.isEmpty()) return "";
        // 目标线程取快照首帧所属线程：filterFrames 按帧的 thread 归属过滤跨线程帧
        LuaThread target = frames.get(0).thread;
        LuaValue tb = DebugHook.tracebackFromSnapshot(target, frames, LuaValue.NIL, 0);
        return tb.isstring() ? tb.toJavaString() : "";
    }

    /**
     * 添加普通日志
     */
    public void add(String msg) {
        addLine(msg);
        // logcat 镜像受 debug 开关控制：关闭时零 android.util.Log 触碰（纯 JVM 测试的前提）
        if (mDebug) Log.i(LuaConfig.getTag(), msg);
    }

    /** 只入列表不镜像 logcat；错误路径的 logcat 由 LuaConfig.logError 按 ERROR 级统一发。 */
    private void addLine(String msg) {
        String line = FORMAT.get().format(new Date()) + " " + msg;
        trimIfNeeded();
        mLogs.add(line);
    }

    /**
     * 添加错误日志。消息与 Lua traceback 合成一条记录，与 Lua 官方错误输出同形：
     * 首行是错误消息，其后是 {@code stack traceback:} 及各帧。
     */
    public void addError(String tag, Throwable e) {
        // e.getMessage() 可能为 null（异常构造时未带消息），退到 e.toString()
        //   至少保住异常类名，别让日志只剩一个 tag。
        String detail = e == null ? "" : Objects.requireNonNullElse(e.getMessage(), e.toString());
        String text = tag + ": " + detail;
        // LuaError 携带 Lua 调试栈快照（savedStack），拼进同一条便于定位错误行。
        //   分两次 add 会各带一个时间戳、在日志列表里成为两行独立条目，
        //   newest-first 视角下 traceback 还会排到消息之前。
        String tb = e instanceof LuaError le ? formatLuaTraceback(le) : "";
        if (!tb.isEmpty()) text = text + "\n" + tb;
        addLine(text);
        // Lua traceback 已并入消息文本，logcat 不再传 throwable——Log.e 会把
        //   e.toString 再打一遍；其余异常保留 throwable 的 Java 栈供定位
        LuaConfig.logError(text, tb.isEmpty() ? e : null);
    }

    // ==================== 读取日志 ====================

    /**
     * 获取日志列表引用
     * 注意：此列表随日志更新而变化，不要直接修改
     */
    public List<String> getLogs() {
        return mLogs;
    }

    public int size() {
        return mLogs.size();
    }

    public String get(int index) {
        // CopyOnWriteArrayList 的 get 在并发 trim 下可能越界：显式判界，越界返回 null
        return index >= 0 && index < mLogs.size() ? mLogs.get(index) : null;
    }

    public void clear() {
        mLogs.clear();
    }

    // ==================== 配置 ====================

    public boolean isDebug() {
        return mDebug;
    }

    public void setDebug(boolean debug) {
        mDebug = debug;
    }

    // ==================== 内部方法 ====================

    private void trimIfNeeded() {
        // 满即删最旧一条：单次 remove(0) 是一次数组复制，均摊无尖峰；集中批量删
        //   （逐条 remove 半数）会让一次 trim 触发数百次全数组复制，print 热路径上
        //   周期性卡顿。不用 removeAll(前半快照)：日志行可能重复，会误删后半段同内容行。
        while (mLogs.size() >= MAX_LOG_SIZE) {
            mLogs.remove(0);
        }
    }
}
