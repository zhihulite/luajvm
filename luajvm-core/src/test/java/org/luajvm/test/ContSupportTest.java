package org.luajvm.test;

import org.luajvm.core.ContSupport;

/**
 * 验证 Continuation 模式的启用状态与基本功能。
 */
public class ContSupportTest {
    public static void main(String[] args) {
        System.out.println("=== ContSupport 状态检查 ===");
        System.out.println("ContSupport.SUPPORTED = " + ContSupport.SUPPORTED);
        System.out.println("System property luajvm.cont = " + System.getProperty("luajvm.cont"));
        
        if (ContSupport.SUPPORTED) {
            System.out.println("OK Continuation 模式已启用");
            System.out.println("  - API 可用且已通过冒烟测试");
            System.out.println("  - 协程将使用 jdk.internal.vm.Continuation");
        } else {
            System.out.println("FAIL Continuation 模式未启用");
            System.out.println("  原因可能：");
            System.out.println("  1. 未设置 -Dluajvm.cont=true");
            System.out.println("  2. 未添加 --add-exports java.base/jdk.internal.vm=ALL-UNNAMED");
            System.out.println("  3. JDK 版本 < 19（Continuation API 不存在）");
            System.out.println("  4. 运行在 Android/ART 上（无此 API）");
            System.out.println("  -> 将回落到线程模式（虚拟线程或平台线程）");
        }
        
        System.out.println("\nContSupportTest: " + (ContSupport.SUPPORTED ? "ENABLED" : "DISABLED"));
    }
}
