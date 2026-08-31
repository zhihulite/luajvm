package org.luajvm.android.lib;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;

/**
 * 钉住 SyncHttp 主线程等待不越过 ANR 阈值。
 *
 * <p>系统 ANR 阈值 5000ms，而默认 {@code httpTimeout} 6000ms 加 500ms 余量是 6500ms ——
 * 不设上限时主线程调用必然被系统判 ANR。同步返回值是已发布契约，故形态不变、只压等待。
 *
 * <p>纯 JVM 可跑：只算等待时长，不发请求、不碰 Looper。
 */
public class SyncHttpAnrContractTest {

    /** 系统 ANR 阈值。上限必须严格小于它，留出主线程其它工作的余量。 */
    private static final long ANR_THRESHOLD_MS = 5000;

    /** 默认等待必须低于 ANR 阈值 —— 这是修复前唯一会实际触发的场景。 */
    @Test
    public void defaultWaitStaysBelowAnrThreshold() {
        long wait = SyncHttp.mainThreadWaitMs(6000);
        assertTrue("默认 httpTimeout=6000ms 时主线程等待应 < ANR 阈值 " + ANR_THRESHOLD_MS
                        + "ms（实测 " + wait + "ms）；修复前是 6500ms 必然 ANR",
                wait < ANR_THRESHOLD_MS);
    }

    /** 脚本把超时调到任意大也不得越线 —— setHttpTimeout 上限由 LuaConfig 决定，不能倒灌。 */
    @Test
    public void hugeTimeoutIsStillCapped() {
        for (int timeout : new int[]{6000, 10_000, 60_000, Integer.MAX_VALUE - 1000}) {
            long wait = SyncHttp.mainThreadWaitMs(timeout);
            assertTrue("httpTimeout=" + timeout + "ms 时主线程等待应 < ANR 阈值（实测 "
                    + wait + "ms）", wait < ANR_THRESHOLD_MS);
        }
    }

    /** 溢出防护：timeout + 500 用 long 运算，不得因 int 溢出变成负数提前返回。 */
    @Test
    public void noIntOverflowOnExtremeTimeout() {
        long wait = SyncHttp.mainThreadWaitMs(Integer.MAX_VALUE);
        assertTrue("极端 timeout 不得因溢出得到非正等待（实测 " + wait + "ms）", wait > 0);
    }

    /**
     * 小超时按实际值等，不被上限抬高 —— 否则脚本显式设的短超时失效。
     */
    @Test
    public void smallTimeoutIsNotInflated() {
        assertEquals("httpTimeout=1000ms 时应等 1500ms（不被上限抬到 4500）",
                1500L, SyncHttp.mainThreadWaitMs(1000));
        assertEquals("httpTimeout=2000ms 时应等 2500ms",
                2500L, SyncHttp.mainThreadWaitMs(2000));
    }

    /** 上限值本身必须落在 ANR 阈值之下（常量被改大时立刻失败）。 */
    @Test
    public void capConstantIsBelowThreshold() {
        assertTrue("MAIN_THREAD_WAIT_CAP_MS=" + SyncHttp.MAIN_THREAD_WAIT_CAP_MS
                        + "ms 应 < ANR 阈值 " + ANR_THRESHOLD_MS + "ms",
                SyncHttp.MAIN_THREAD_WAIT_CAP_MS < ANR_THRESHOLD_MS);
    }

    /**
     * 同步 API 形态不得被悄悄改成异步 —— 返回值是已发布契约（脚本按返回值写）。
     * 压 ANR 风险的代价只能落在「提前判超时」，不能落在「改签名」。
     */
    @Test
    public void syncApiShapePreserved() {
        String[] syncMethods = {"get", "head", "post", "put", "delete", "upload"};
        for (String name : syncMethods) {
            boolean found = false;
            for (Method m : SyncHttp.class.getDeclaredMethods()) {
                if (!m.getName().equals(name) || !Modifier.isPublic(m.getModifiers())) continue;
                found = true;
                assertEquals(name + " 必须仍同步返回 HttpResult（已发布契约）",
                        HttpCore.HttpResult.class, m.getReturnType());
            }
            assertTrue("前置：应存在公开方法 " + name + "（为 0 则本断言空转）", found);
        }
    }
}
