package org.luajvm.android.gate;

import org.junit.Test;

/**
 * 四个静态门禁的 JUnit 入口，使它们随 {@code testDebugUnitTest} 自动执行。
 *
 * <p>门禁本体是 {@code main} 方法（判据自带前置自检与 {@code AssertionError}），
 * 此处只做转发：四者都读 {@code .class} 常量池或 {@code .java} 源码文本，
 * APK 里两者都不存在，故只能留在纯 JVM 层（见 docs/GATES.md 的分层表）。
 *
 * <p>门禁内部找不到编译产物时会自行 SKIP 并打印原因，不静默通过。
 */
public class AndroidStaticGatesTest {

    /** 包依赖必须保持单向分层，防止依赖环回潮。 */
    @Test
    public void layerContract() throws Exception {
        LayerContractTest.main(new String[0]);
    }

    /** 宿主类不得手写 LuaContext 转发，接口 default 不得被父类静默盖掉。 */
    @Test
    public void hostContract() throws Exception {
        HostContractTest.main(new String[0]);
    }

    /** Lua VM 进入点必须全部经自动执行区（LuaCall.invoke / JavaCall），禁裸 call。 */
    @Test
    public void threadEntryContract() throws Exception {
        ThreadEntryContractTest.main(new String[0]);
    }

    /** baseline profile 的类/方法签名必须与实际代码一致，失效条目等于白发。 */
    @Test
    public void baselineProfile() throws Exception {
        BaselineProfileTest.main(new String[0]);
    }
}
