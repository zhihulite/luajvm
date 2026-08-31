package org.luajvm.android.widget;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 平台层若干源码不变量：绑定热路径的遍历形态、缓存的引用强度、废弃 API 的替代分支、
 * 资源名的目录约束。这些都读 {@code .java} 文本，APK 里没有源码，只能留纯 JVM 层。
 */
public class PlatformSourceContractTest {

    private static final Path MAIN = Path.of("src/main/java/org/luajvm/android");

    private static String read(String rel) throws IOException {
        Path p = MAIN.resolve(rel);
        assertTrue("被测文件应存在: " + p.toAbsolutePath(), Files.isRegularFile(p));
        return Files.readString(p, StandardCharsets.UTF_8);
    }

    /**
     * 剥掉注释再扫代码。
     *
     * <p>不剥的话"不得出现 X"这类断言会被解释 X 为何不该用的注释自己触发 ——
     * 越是把理由写清楚的代码越容易误判。
     */
    private static String code(String rel) throws IOException {
        String s = read(rel);
        s = Pattern.compile("/\\*.*?\\*/", Pattern.DOTALL).matcher(s).replaceAll("");
        return Pattern.compile("//[^\\n]*").matcher(s).replaceAll("");
    }

    // ==================== 绑定热路径：单趟遍历 ====================

    /**
     * 每行绑定属性必须 {@code next()} 单趟走完。
     *
     * <p>{@code keys()} 会先分配一个键数组，拿到键后还要再 {@code get(key)} 查一次表，
     * 即每属性两次哈希加一次数组分配；列表滚动时这是逐行逐属性的开销。
     */
    @Test
    public void adapterBindingUsesSingleTraversal() throws IOException {
        String[] files = {
                "widget/LuaAdapter.java",
                "widget/LuaMultiAdapter.java",
                "widget/LuaExpandableListAdapter.java",
                "widget/AdapterHelper.java",
        };
        for (String f : files) {
            String src = read(f);
            assertFalse(f + " 的绑定路径不得用 keys() 快照（每属性两次哈希 + 键数组分配）",
                    src.contains(".keys()"));
            assertTrue(f + " 应改用 next() 单趟遍历", src.contains(".next("));
        }
    }

    /** 单趟遍历必须直接取 next 的值，回头再 get 一次就白省了。 */
    @Test
    public void singleTraversalReadsValueFromPair() throws IOException {
        for (String f : new String[]{"widget/LuaAdapter.java", "widget/LuaMultiAdapter.java",
                "widget/LuaExpandableListAdapter.java", "widget/AdapterHelper.java"}) {
            String src = read(f);
            assertTrue(f + " 应从 next 的返回值取 value（pair.arg(2)）",
                    src.contains("pair.arg(2)"));
        }
    }

    // ==================== 构造器缓存：不得钉住 ClassLoader ====================

    /**
     * 跨实例共享的 Class 键缓存必须弱引用。
     *
     * <p>Lua 可经 {@code import} 从自定义 {@code ClassLoader} 绑类，强键会把该 loader
     * 连同其加载的全部类钉在进程里。
     */
    @Test
    public void sharedClassKeyedCacheIsWeak() throws IOException {
        String src = code("widget/LuaLayout.java");
        int at = src.indexOf("CTOR_CACHE =");
        assertTrue("应有跨实例的构造器缓存", at > 0);
        String decl = src.substring(at, Math.min(src.length(), at + 140));
        assertTrue("Class 键的进程级缓存须用 WeakHashMap（否则钉住自定义 ClassLoader）；实际=" + decl,
                decl.contains("WeakHashMap"));
        assertTrue("跨实例共享须线程安全", decl.contains("synchronizedMap"));
    }

    /** 负缓存要靠 containsKey 区分，否则"已判明无 4 参构造器"每次都会重探。 */
    @Test
    public void constructorNegativeCacheIsDistinguishable() throws IOException {
        String src = read("widget/LuaLayout.java");
        assertTrue("负缓存须用 containsKey 区分未探测与已判无",
                src.contains("CTOR_CACHE.containsKey("));
    }

    // ==================== 废弃 API 的替代分支 ====================

    /**
     * 屏幕尺寸在 API 30+ 须走 {@code getMaximumWindowMetrics}。
     * {@code getCurrentWindowMetrics} 给的是当前窗口大小，分屏下会让 Lua 侧宽高变化。
     */
    @Test
    public void screenSizeUsesMaximumWindowMetricsOnApi30() throws IOException {
        String src = code("engine/LuaEngine.java");
        assertTrue("API 30+ 须用 getMaximumWindowMetrics", src.contains("getMaximumWindowMetrics"));
        assertFalse("不得用 getCurrentWindowMetrics（分屏下是窗口大小，非屏幕尺寸）",
                src.contains("getCurrentWindowMetrics"));
        assertTrue("须按 SDK_INT 分支，低版本仍走 getDefaultDisplay",
                src.contains("Build.VERSION_CODES.R") && src.contains("getDefaultDisplay"));
    }

    /** 共享存储根须从 getExternalFilesDir 反推，不用已废弃的 Environment 入口。 */
    @Test
    public void externalRootDerivedWithoutDeprecatedApi() throws IOException {
        String src = code("runtime/LuaPathResolver.java");
        assertFalse("不得再用 Environment.getExternalStorageDirectory（API 29 deprecated）",
                src.contains("Environment.getExternalStorageDirectory()"));
        assertTrue("须从 getExternalFilesDir 反推存储根", src.contains("getExternalFilesDir"));
        assertTrue("取不到时须有回落，不得返回 null", src.contains("getFilesDir()"));
    }

    // ==================== 资源名的目录约束 ====================

    /** 资源名不得越出 res/&lt;kind&gt;，否则目录外的文件会被当资源静默读进来。 */
    @Test
    public void resourceNamesAreConfinedToResDir() throws IOException {
        String src = code("lib/res.java");
        assertTrue("应有目录约束 helper", src.contains("private String resolveInRes("));
        assertTrue("须用 canonical path 比对（字符串前缀挡不住 ..）",
                src.contains("getCanonicalPath()"));
        // 字面量拼死类型名的调用点只允许 string 表那两处（文件名固定，不含外部输入）；
        //   helper 内是 "res/" + kind，不匹配该模式
        Matcher m = Pattern.compile("getLuaPath\\(\"res/(\\w+)\"").matcher(src);
        int direct = 0;
        StringBuilder kinds = new StringBuilder();
        while (m.find()) {
            direct++;
            kinds.append(m.group(1)).append(' ');
        }
        assertEquals("只允许 string 表直接拼 res/ 路径（它用固定文件名，不受外部输入影响），"
                + "其余须经 resolveInRes；实际命中=[" + kinds + "]", 2, direct);
        assertTrue("drawable 查找须经 helper", src.contains("resolveInRes(\"drawable\""));
        assertTrue("layout 查找须经 helper", src.contains("resolveInRes(\"layout\""));
    }
}
