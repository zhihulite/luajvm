package org.luajvm.android;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import android.app.Instrumentation;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.ListView;
import android.widget.TextView;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.luajvm.android.host.LuaActivity;
import org.luajvm.core.Globals;
import org.luajvm.core.LuaTable;
import org.luajvm.core.LuaValue;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;

/**
 * LuaActivity 的端到端仪器化测试：一个最小 Lua 页面（assets/apk_probe.lua）
 * 把各条平台链路各走一遍，本类读页面留下的 {@code RESULT} 表逐项断言。
 *
 * <p><b>为什么必须是仪器化测试</b>：这些链路都要 {@code Context}/{@code Activity}/
 * {@code Looper}/{@code DisplayMetrics}/dexmaker —— 纯 JVM 与 Robolectric 都到不了
 * （见 docs/GATES.md 的分层表）。
 *
 * <p><b>脚本放 filesDir 而不是别处</b>：域内判定覆盖应用 dataDir（含 files 与
 * cache）、externalFilesDir 与引擎 rootDir，{@code findRoot(filesDir)} 直接命中 ⇒
 * 脚本属域内 ⇒ 走生产链路、不触发域外 .lua 的用户确认框。放到 /sdcard 等
 * 域外路径会弹 AlertDialog 卡死测试。
 */
@RunWith(AndroidJUnit4.class)
public class LuaActivityInstrumentedTest {

    private static final String SCRIPT = "apk_probe.lua";
    /** 页面里逐项 pcall 的项数上限内的等待时长；Lua 侧全部同步执行，超时说明卡在某项。 */
    private static final long READY_TIMEOUT_MS = 20_000;

    private static LuaActivity activity;
    private static Globals globals;

    @BeforeClass
    public static void launchOnce() throws Exception {
        Instrumentation inst = InstrumentationRegistry.getInstrumentation();
        Context target = inst.getTargetContext();

        // 脚本落 filesDir（= luaDir 默认值，域内）
        File script = new File(target.getFilesDir(), SCRIPT);
        copyAsset(inst, SCRIPT, script);
        assertTrue("脚本应已写入 filesDir: " + script, script.isFile());

        Intent intent = new Intent(target, LuaActivity.class)
                .setData(Uri.fromFile(script))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        activity = (LuaActivity) inst.startActivitySync(intent);
        assertNotNull("LuaActivity 应已启动", activity);

        globals = waitForGlobals();
        assertNotNull("引擎应已初始化并暴露 Globals", globals);
        waitForFlag("DONE", READY_TIMEOUT_MS);
        // startActivitySync 只保证 onCreate 返回；View 附窗发生在首次遍历，
        //   不等 idle 就断言 isAttachedToWindow 会稳定失败
        inst.waitForIdleSync();
    }

    @AfterClass
    public static void finishOnce() {
        if (activity != null) {
            activity.finish();
            activity = null;
        }
        globals = null;
    }

    // ==================== 辅助 ====================

    private static void copyAsset(Instrumentation inst, String name, File dest) throws IOException {
        // 用 instrumentation 自己的 assets：脚本在测试 APK 里，不在被测应用里
        try (InputStream in = inst.getContext().getAssets().open(name);
             OutputStream out = Files.newOutputStream(dest.toPath())) {
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
        }
    }

    private static Globals waitForGlobals() throws InterruptedException {
        long deadline = System.currentTimeMillis() + READY_TIMEOUT_MS;
        while (System.currentTimeMillis() < deadline) {
            Globals g = activity.getLuaState();
            if (g != null && !g.get("RESULT").isnil()) return g;
            Thread.sleep(50);
        }
        return activity.getLuaState();
    }

    /** 等页面把标记置上；超时即 fail 并带上已完成的项，便于定位卡在哪一步。 */
    private static void waitForFlag(String flag, long timeoutMs) throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            if (globals.get(flag).isboolean() && globals.get(flag).toboolean()) return;
            Thread.sleep(50);
        }
        fail("等待 " + flag + " 超时；已完成项=" + dumpResult());
    }

    private static String dumpResult() {
        LuaValue r = globals.get("RESULT");
        if (!r.istable()) return "<RESULT 不是表>";
        StringBuilder sb = new StringBuilder();
        LuaValue k = LuaValue.NIL;
        while (true) {
            org.luajvm.core.Varargs n = r.next(k);
            if (n.isnil(1)) break;
            k = n.arg1();
            sb.append(k.toJavaString()).append('=').append(n.arg(2).toJavaString()).append(' ');
        }
        return sb.toString();
    }

    /** 列出某张表的键，用于定位"写进去了但读不到"这类键/读法不一致。 */
    private static String dumpKeys(LuaValue t, int limit) {
        if (!t.istable()) return "<不是表: " + t.toJavaString() + ">";
        StringBuilder sb = new StringBuilder();
        LuaValue k = LuaValue.NIL;
        int i = 0;
        while (i++ < limit) {
            org.luajvm.core.Varargs n = t.next(k);
            if (n.isnil(1)) break;
            k = n.arg1();
            sb.append(k.toJavaString()).append(' ');
        }
        return sb.toString();
    }

    /**
     * 读 RESULT 里某一项：{@code true} 即通过，字符串即 Lua 侧的错误消息。
     *
     * <p><b>必须判"是布尔 true"而不是 {@code toboolean()}</b>：Lua 里非空字符串为真，
     * 用真值判定会把错误消息读成通过 —— 判据自身空转，比没有判据更糟。
     */
    private static void assertProbePassed(String name) {
        LuaValue v = globals.get("RESULT").get(name);
        if (v.isnil()) fail("页面未执行该项（RESULT 无 " + name + " 键）：" + dumpResult());
        if (!v.isboolean() || !v.toboolean()) fail(name + " 失败：" + v.toJavaString());
    }

    /**
     * 取 id 注入的全局并转回 View。
     *
     * <p>判型只按「能否转回 View」，不查 {@code isuserdata()}：{@code LuaLayout} 往 env
     * 里放的是 {@code JavaObject}（bind 层的包装），其内部标签不是核心的 full userdata，
     * 断言标签会把实现细节钉进测试。
     */
    private static View byId(String luaGlobal, Class<? extends View> type) {
        LuaValue v = globals.get(luaGlobal);
        if (v.isnil()) {
            // 回落到页面显式挂的 VIEWS 表：区分「id 未注入」与「注入进了脚本 _ENV
            //   而非 Globals 本体」——后者是读法问题，不是缺陷
            LuaValue views = globals.get("VIEWS");
            assertTrue("既无同名全局也无 VIEWS 表，id 注入确实失败：" + luaGlobal,
                    views.istable());
            v = views.get(luaGlobal);
        }
        assertFalse("id=" + luaGlobal + " 应可取到（全局或 VIEWS），实为 nil"
                + "；VIEWS 键=[" + dumpKeys(globals.get("VIEWS"), 12) + "]"
                + "；globals 全部键=[" + dumpKeys(globals, 400) + "]", v.isnil());
        Object o = v.touserdata(View.class);
        assertNotNull("id=" + luaGlobal + " 应能转回 View，实际值=" + v.toJavaString(), o);
        assertTrue("id=" + luaGlobal + " 应是 " + type.getSimpleName() + "，实为 "
                + o.getClass().getName(), type.isInstance(o));
        return (View) o;
    }

    // ==================== 页面各项 ====================

    /** 前置自检：页面真的跑完了全部项，否则后面每条断言都可能是恒真空转。 */
    @Test
    public void pageRanEveryProbe() {
        for (String name : new String[]{"import", "layout", "adapter", "override_click",
                "override_twice", "receiver", "shared_data", "global_data", "json", "file",
                "timer", "thread"}) {
            assertFalse("页面应执行 " + name + " 项；实际=" + dumpResult(), globals.get("RESULT").get(name).isnil());
        }
    }

    @Test
    public void layoutBuildsRealViewTree() {
        assertProbePassed("layout");
        View title = byId("title", TextView.class);
        assertEquals("text 属性应落到 TextView", "apk probe",
                ((TextView) title).getText().toString());
        // 断言父链能走到 setContentView 装上的那棵树，而不是 isAttachedToWindow：
        //   后者还取决于设备是否亮屏/Activity 是否 resumed，与"树装对了"无关
        View contentRoot = activity.findViewById(android.R.id.content);
        assertNotNull("content root 应存在", contentRoot);
        View p = title;
        while (p.getParent() instanceof View parent) {
            if (parent == contentRoot) return;
            p = parent;
        }
        fail("id=title 的 View 不在 setContentView 装上的树里（父链止于 "
                + p.getClass().getName() + "）；说明建了两棵树，id 指向未装上的那棵");
    }

    /**
     * textSize 的四种单位都要按设备 DisplayMetrics 换算。
     * 纯 JVM 拿不到 density/屏宽，这条只能在设备上判。
     */
    @Test
    public void textSizeUnitsResolveAgainstRealMetrics() {
        assertProbePassed("layout");
        var m = activity.getResources().getDisplayMetrics();
        float px = ((TextView) byId("sized_px", TextView.class)).getTextSize();
        float dp = ((TextView) byId("sized_dp", TextView.class)).getTextSize();
        float sp = ((TextView) byId("title", TextView.class)).getTextSize();
        float pct = ((TextView) byId("sized_pct", TextView.class)).getTextSize();

        assertEquals("16px 应原样落像素", 16f, px, 0.5f);
        assertEquals("16dp 应按 density 换算", 16f * m.density, dp, 0.5f);
        assertEquals("16sp 应按 scaledDensity 换算", 16f * m.scaledDensity, sp, 1.0f);
        assertEquals("5% 应按屏宽百分比换算", m.widthPixels * 0.05f, pct, 1.0f);
    }

    @Test
    public void adapterBindsAndTagsEveryRow() {
        assertProbePassed("adapter");
        ListView list = (ListView) byId("list", ListView.class);
        assertNotNull("Adapter 应已设上", list.getAdapter());
        assertEquals("三行数据", 3, list.getAdapter().getCount());
        // getView 必须给每个返回的 View 打 tag：坏行不得让后续行拿到脏 tag
        for (int i = 0; i < 3; i++) {
            View row = list.getAdapter().getView(i, null, list);
            assertNotNull("第 " + i + " 行应返回 View", row);
            assertNotNull("第 " + i + " 行的 View 必须带 tag（复用靠它辨认布局）", row.getTag());
        }
    }

    /** dexmaker 在设备上生成 dex 并分派回 Lua；含"第二次 override 增加方法"那条。 */
    @Test
    public void overrideDispatchesOnDevice() {
        assertProbePassed("override_click");
        assertProbePassed("override_twice");
    }

    /** 注册三个 receiver 后全部注销：不得漏注销，也不得对同一个注销两次。 */
    @Test
    public void receiverRegisterAndUnregisterAreBalanced() {
        assertProbePassed("receiver");
    }

    @Test
    public void sharedAndGlobalDataRoundTrip() {
        assertProbePassed("shared_data");
        assertProbePassed("global_data");
    }

    /** Android 自带 org.json：JSON null 须落 Lua nil 而非 JSONObject.NULL 的 userdata。 */
    @Test
    public void jsonRoundTripsOnDeviceImplementation() {
        assertProbePassed("json");
    }

    @Test
    public void fileIoWorksInRealLuaDir() {
        assertProbePassed("file");
    }

    /** timer/thread 的回调须真正回到主线程执行（页面里置 ASYNC 标记）。 */
    @Test
    public void asyncCallbacksReachMainLooper() throws Exception {
        assertProbePassed("timer");
        assertProbePassed("thread");
        long deadline = System.currentTimeMillis() + 10_000;
        while (System.currentTimeMillis() < deadline) {
            LuaValue async = globals.get("ASYNC");
            if (async.istable() && async.get("timer").toboolean()
                    && async.get("thread").toboolean()) return;
            Thread.sleep(50);
        }
        LuaValue async = globals.get("ASYNC");
        fail("timer/thread 回调未在 10s 内执行；timer=" + async.get("timer").toJavaString()
                + " thread=" + async.get("thread").toJavaString());
    }

    /** 页面里没有一项失败——逐项报告，一次看全，不因第一个失败就中断。 */
    @Test
    public void noProbeReportedFailure() {
        StringBuilder failures = new StringBuilder();
        LuaValue r = globals.get("RESULT");
        LuaValue k = LuaValue.NIL;
        while (true) {
            org.luajvm.core.Varargs n = r.next(k);
            if (n.isnil(1)) break;
            k = n.arg1();
            // 同样按"是布尔 true"判：字符串在 Lua 里为真，真值判定会漏报
            if (!n.arg(2).isboolean() || !n.arg(2).toboolean()) {
                failures.append("\n  ").append(k.toJavaString()).append(": ")
                        .append(n.arg(2).toJavaString());
            }
        }
        if (failures.length() > 0) fail("页面报告失败项：" + failures);
    }

    // ==================== sendError → onError 回传 ====================

    /** 在页面 Globals 里执行一段 Lua（load 后立即调用）。 */
    private static void runLua(String code) {
        Object chunk = org.luajvm.bind.JavaCall.call(globals.get("load"), code);
        assertFalse("Lua 片段应可加载: " + code, chunk == null);
        org.luajvm.bind.JavaCall.call((LuaValue) chunk);
    }

    @Test
    public void sendErrorDispatchesToLuaOnError() {
        runLua("onError = function(title, msg) _ONERR = title .. '|' .. msg end");
        activity.sendError("probe-title", new IllegalStateException("boom"));
        assertEquals("onError 应收到 title 与 message", "probe-title|boom",
                globals.get("_ONERR").toJavaString());
    }

    /** onError 自身抛错时只进日志，不得递归回传或把异常抛回调用方。 */
    @Test
    public void onErrorFailureDoesNotRecurse() {
        runLua("onError = function() error('inside onError') end _ONERR2 = 'alive'");
        activity.sendError("probe-title", new IllegalStateException("boom"));
        assertEquals("onError 自身报错不应中断 sendError", "alive",
                globals.get("_ONERR2").toJavaString());
    }
}
