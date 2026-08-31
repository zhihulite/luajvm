package org.luajvm.android.engine;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import org.junit.After;

import org.luajvm.android.runtime.LuaConfig;
import org.luajvm.android.runtime.LuaLog;
import org.luajvm.bind.JavaCall;
import org.luajvm.bind.Platform;
import org.luajvm.core.Globals;
import org.luajvm.core.LuaError;
import org.luajvm.core.Varargs;
import org.junit.Before;
import org.junit.Test;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Pattern;

/**
 * 钉住 LuaLog 的线程安全与 LuaConfig.LogLevel.NONE 的门槛语义。
 *
 * <p>纯 JVM 可跑的前提：{@code LuaConfig.setLogLevel(NONE)} 把全部输出挡在 android.util.Log
 * 之前，且 {@code LuaLog.setDebug(false)}（默认值）让 add() 不走 Log.i。
 * 这也正是 NONE 必须真的"全关"才成立 —— 见 {@link #noneLevelSuppressesEverything()}。
 */
public class LuaLogConcurrencyTest {

    // "HH:mm:ss.SSS " 前缀：并发下 SimpleDateFormat 被共享会产出错位/乱码时间戳
    private static final Pattern TIMESTAMPED =
            Pattern.compile("^\\d{2}:\\d{2}:\\d{2}\\.\\d{3} .*", Pattern.DOTALL);

    private LuaConfig.LogLevel mSavedLevel;

    @Before
    public void muteAndroidLog() {
        mSavedLevel = LuaConfig.getLogLevel();
        LuaConfig.setLogLevel(LuaConfig.LogLevel.NONE);
        LuaLog.getInstance().setDebug(false);
        LuaLog.getInstance().clear();
    }

    @After
    public void restore() {
        LuaConfig.setLogLevel(mSavedLevel);
        LuaLog.getInstance().clear();
    }

    /**
     * NONE 曾被写成 priority=-1，而 log() 判据是 {@code sLogLevel.priority > level.priority}，
     * 于是 NONE 比任何级别都低、放行全部日志（与"全关"相反）。若该 bug 回归，这里的
     * logError 会打到未 mock 的 android.util.Log 并抛 RuntimeException。
     */
    @Test
    public void noneLevelSuppressesEverything() {
        assertEquals(LuaConfig.LogLevel.NONE, LuaConfig.getLogLevel());
        // 四个级别全走一遍：NONE 生效时一条都不该到达 android.util.Log
        LuaConfig.log("debug-probe");
        LuaConfig.logInfo("info-probe");
        LuaConfig.logWarn("warn-probe");
        LuaConfig.logError("error-probe", new IllegalStateException("boom"));
    }

    /**
     * static SimpleDateFormat 非线程安全：并发 format() 会产出乱码时间戳，
     * 严重时抛 ArrayIndexOutOfBoundsException / NumberFormatException。
     * add() 的真实并发来源：Lua print -> sendMsg、task/thread 的 IO 池、LuaServer 的 socket 线程。
     */
    @Test
    public void concurrentAddProducesWellFormedTimestamps() throws Exception {
        final int threads = 8;
        final int perThread = 400;
        final AtomicInteger errors = new AtomicInteger();
        final CountDownLatch start = new CountDownLatch(1);

        ExecutorService pool = Executors.newFixedThreadPool(threads);
        try {
            for (int t = 0; t < threads; t++) {
                final int id = t;
                pool.submit(() -> {
                    try {
                        start.await();
                        for (int i = 0; i < perThread; i++) {
                            LuaLog.getInstance().add("t" + id + "-" + i);
                        }
                    } catch (Throwable e) {
                        errors.incrementAndGet();
                    }
                });
            }
            start.countDown();
            pool.shutdown();
            assertTrue("并发写日志未在超时内结束", pool.awaitTermination(60, TimeUnit.SECONDS));
        } finally {
            pool.shutdownNow();
        }
        assertEquals("并发 add() 抛异常（SimpleDateFormat 共享）", 0, errors.get());

        // 每一行都必须是合法的 "HH:mm:ss.SSS <msg>"；共享 SimpleDateFormat 会破坏这个格式
        List<String> logs = LuaLog.getInstance().getLogs();
        assertTrue("日志不应为空，否则本门禁空转", logs.size() > 0);
        for (String line : logs) {
            assertNotNull(line);
            assertTrue("时间戳格式被并发破坏: <" + line + ">", TIMESTAMPED.matcher(line).matches());
        }
    }

    /** trimIfNeeded 在并发下不得让 size 无界增长（MAX_LOG_SIZE=500，减半策略）。 */
    @Test
    public void logListStaysBounded() throws Exception {
        for (int i = 0; i < 3000; i++) {
            LuaLog.getInstance().add("bound-" + i);
        }
        int size = LuaLog.getInstance().size();
        assertTrue("日志条数应被 MAX_LOG_SIZE 约束，实测 " + size, size <= 500);
        assertTrue("裁剪不应清空日志，实测 " + size, size > 0);
    }

    /**
     * addError 曾把 tag 当 msg 传给 LuaConfig.logError(String,Throwable)，
     * 且 e.getMessage() 为 null 时日志只剩一个 tag。这里钉住两点：
     * 消息进入日志列表，且 message 为 null 时退到 toString()。
     */
    @Test
    public void addErrorKeepsDetailEvenWhenMessageIsNull() {
        LuaLog.getInstance().clear();
        LuaLog.getInstance().addError("probeTag", new IllegalStateException("with-message"));
        LuaLog.getInstance().addError("probeTag2", new NullPointerException());

        List<String> logs = LuaLog.getInstance().getLogs();
        assertEquals(2, logs.size());
        assertTrue("应含异常消息: " + logs.get(0), logs.get(0).contains("with-message"));
        // message 为 null 时必须退到 toString()（含异常类名），而不是留空
        assertTrue("message 为 null 时应退到 toString(): " + logs.get(1),
                logs.get(1).contains("NullPointerException"));
    }

    /**
     * 带 Lua traceback 的错误必须是一条记录：消息与 {@code stack traceback:} 同属一个
     * 日志条目、共用一个时间戳。曾分两次 add，于是日志列表里成为两条独立条目、各带
     * 时间戳，且 UI 的 newest-first 视角会把 traceback 排到消息之前（读起来上下颠倒）。
     */
    @Test
    public void luaErrorMessageAndTracebackShareOneRecord() {
        Globals g = Platform.standardGlobals();
        LuaLog.getInstance().clear();
        // 复刻 LuaEngine.init 的加载执行路径：load 取函数后经 JavaCall 调用，
        //   这样 LuaError 才带上 Lua 侧调用帧快照
        byte[] src = "local function inner() error('boom') end inner()"
                .getBytes(StandardCharsets.UTF_8);
        Varargs loaded = g.load(new ByteArrayInputStream(src), "=probe", "bt", null);
        try {
            JavaCall.call(loaded.arg1());
            throw new AssertionError("chunk 应当抛错");
        } catch (LuaError e) {
            LuaLog.getInstance().addError("Lua init error", e);
        }

        List<String> logs = LuaLog.getInstance().getLogs();
        assertEquals("消息与 traceback 必须合成一条记录", 1, logs.size());
        String record = logs.get(0);
        assertTrue("应含错误消息: " + record, record.contains("boom"));
        assertTrue("应含 traceback: " + record, record.contains("stack traceback:"));
        // 时间戳只在记录开头出现一次：traceback 各帧不得各自带时间戳
        assertTrue("记录应以时间戳开头: " + record, TIMESTAMPED.matcher(record).matches());
        assertTrue("错误消息须排在 traceback 之前",
                record.indexOf("boom") < record.indexOf("stack traceback:"));
    }
}
