// java-only: luajvm_tests/interop.lua 用的互操作测试 Bean - public 字段验证 Field 访问路径
package org.luajvm.test;

public class InteropTestBean {
    public static final int MAGIC = 42;  // final 静态 -> cachedStaticFinals 路径
    public static int total;   // 非 final 静态字段 -> 静态 setter spreader 路径
    public int count;          // 非 final 实例字段 -> getter/setter spreader 路径
    public String name;        // 非 final 实例 String 字段

    public static int staticAdd(int a, int b) {
        return a + b;
    }

    public int add(int a, int b) {
        return a + b;
    }

    public String concat(String a, String b) {
        return a + b;
    }
}
