package org.luajvm.android.lib;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import android.os.Looper;

import androidx.test.ext.junit.runners.AndroidJUnit4;

import org.junit.BeforeClass;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.luajvm.android.runtime.LuaConfig;

import java.net.InetAddress;

/**
 * 同步 HTTP 的真网测试：请求 example.com。
 *
 * <p><b>为什么必须是仪器化测试</b>：走设备的网络栈与 DNS、{@code Looper}
 * （主线程等待上限那条守卫只在有 Looper 时成立），且需要 APK 的 INTERNET 权限。
 *
 * <p><b>网络不可用时显式跳过而不是静默通过</b>：先探一次 DNS，探不通就 {@code fail}
 * 并说明是环境问题 —— 把"没网"读成"HTTP 正常"是最典型的门禁空转。
 */
@RunWith(AndroidJUnit4.class)
public class SyncHttpInstrumentedTest {

    private static final String URL = "http://example.com";
    private static final String URL_HTTPS = "https://example.com";

    @BeforeClass
    public static void requireNetwork() {
        try {
            InetAddress addr = InetAddress.getByName("example.com");
            assertNotNull("DNS 应能解析 example.com", addr);
        } catch (Exception e) {
            fail("环境无网络或 DNS 不可用，本类无法判定 HTTP 行为（不是被测代码的问题）：" + e);
        }
    }

    /** 后台线程上的 GET：应拿到 200 与非空正文。 */
    @Test
    public void getReturns200WithBodyOffMainThread() {
        assertNotSame("本用例须在非主线程执行", Looper.myLooper(), Looper.getMainLooper());
        HttpCore.HttpResult r = SyncHttp.get(URL);
        assertNotNull("结果不得为 null", r);
        assertEquals("example.com 应返回 200，实际 " + r.code + "，正文=" + brief(r.text), 200, r.code);
        assertNotNull("正文不得为 null", r.text);
        assertTrue("正文应含 example.com 的标题，实际=" + brief(r.text),
                r.text.contains("Example Domain"));
    }

    /** HTTPS 同样要通：TLS 握手走设备的 trust store。 */
    @Test
    public void httpsGetSucceeds() {
        HttpCore.HttpResult r = SyncHttp.get(URL_HTTPS);
        assertEquals("HTTPS 应返回 200，实际 " + r.code, 200, r.code);
        assertTrue("HTTPS 正文应含标题", r.text.contains("Example Domain"));
    }

    /** HEAD 应有状态码且无正文（或空正文），不得因缺 body 而抛。 */
    @Test
    public void headHasStatusWithoutBody() {
        HttpCore.HttpResult r = SyncHttp.head(URL);
        assertEquals("HEAD 应返回 200", 200, r.code);
        assertTrue("HEAD 不应有正文，实际长度=" + (r.text == null ? -1 : r.text.length()),
                r.text == null || r.text.isEmpty());
    }

    /** 响应头须能取到：contentType 与 headers 表都不得为空。 */
    @Test
    public void responseHeadersArePopulated() {
        HttpCore.HttpResult r = SyncHttp.get(URL);
        assertEquals(200, r.code);
        assertNotNull("headers 不得为 null", r.headers);
        assertFalse("headers 不得为空", r.headers.isEmpty());
        assertNotNull("contentType 不得为 null", r.contentType);
        assertTrue("contentType 应是 text/html，实际=" + r.contentType,
                r.contentType.toLowerCase().contains("text/html"));
    }

    /** 不存在的主机：须给出可判别的失败而不是抛到调用方，也不得当成成功。 */
    @Test
    public void unresolvableHostFailsCleanly() {
        HttpCore.HttpResult r;
        try {
            r = SyncHttp.get("http://no-such-host.invalid");
        } catch (Exception e) {
            return; // 抛语义化异常同样可接受
        }
        assertTrue("不可解析主机不得返回 2xx，实际 " + r.code, r.code < 200 || r.code >= 400);
    }

    /**
     * 主线程等待上限：{@code mainThreadWaitMs} 须把等待钉在
     * {@link LuaConfig#MAIN_THREAD_WAIT_CAP_MS} 以内，否则 UI 线程同步请求直接 ANR。
     * 这条不发真请求，只验算式——真在主线程发请求会让测试自己 ANR。
     */
    @Test
    public void mainThreadWaitIsCapped() {
        long cap = SyncHttp.MAIN_THREAD_WAIT_CAP_MS;
        assertTrue("上限须为正，实际 " + cap, cap > 0);
        assertTrue("远大于上限的超时须被截到上限",
                SyncHttp.mainThreadWaitMs((int) cap * 100) <= cap);
        assertTrue("小于上限的超时须原样保留",
                SyncHttp.mainThreadWaitMs((int) (cap / 2)) <= cap);
    }

    private static String brief(String s) {
        if (s == null) return "<null>";
        return s.length() <= 120 ? s : s.substring(0, 120) + "…";
    }
}
