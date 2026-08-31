package org.luajvm.android.widget;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * 两条平台行为的源码合约：子项可选性默认值、Uri 取路径的 SAF 回落。
 *
 * <p>两者都要真实 {@code Context}/{@code ContentResolver}/{@code ExpandableListView}
 * 才能验行为，那部分在 androidTest；本类只钉住源码层面的不变量——纯 JVM 可判，
 * 且能防住"改回旧行为"这类回归。
 */
public class ExpandableAndUriContractTest {

    private static final Path MAIN = Path.of("src/main/java/org/luajvm/android");

    private static String read(String rel) throws IOException {
        Path p = MAIN.resolve(rel);
        assertTrue("被测文件应存在: " + p.toAbsolutePath(), Files.isRegularFile(p));
        return Files.readString(p, StandardCharsets.UTF_8);
    }

    /** 取方法体：从签名起到下一个同缩进的 `    }` 为止。 */
    private static String methodBody(String src, String signature) {
        int at = src.indexOf(signature);
        assertTrue("应有方法 " + signature, at >= 0);
        int end = src.indexOf("\n    }", at);
        assertTrue("方法 " + signature + " 应有结尾", end > at);
        return src.substring(at, end);
    }

    // ==================== isChildSelectable ====================

    /**
     * 子项默认可选。恒 false 会让 ExpandableListView 完全收不到子项点击，
     * onChildClick 不触发、按下也无反馈。
     */
    @Test
    public void childSelectableDefaultsToTrue() throws IOException {
        String src = read("widget/LuaExpandableListAdapter.java");
        String body = methodBody(src,
                "public boolean isChildSelectable(int groupPosition, int childPosition)");
        assertFalse("isChildSelectable 不得直接 return false（子项将永不可点）",
                body.contains("return false;"));
        assertTrue("无回调时应返回 true", body.contains("return true;"));
    }

    /** 需按位置禁用时要有 Lua 侧入口，否则默认可选就成了死规则。 */
    @Test
    public void childSelectableIsOverridableFromLua() throws IOException {
        String src = read("widget/LuaExpandableListAdapter.java");
        assertTrue("应有 setChildSelectable 供 Lua 侧赋值",
                src.contains("public void setChildSelectable(LuaValue"));
        String body = methodBody(src,
                "public boolean isChildSelectable(int groupPosition, int childPosition)");
        assertTrue("应在回调存在时调用它", body.contains("mChildSelectable"));
        assertTrue("回调须经 LuaCall.invoke 进自动执行区", body.contains("LuaCall.invoke"));
    }

    /** 回调抛错不得让整个列表失去响应。 */
    @Test
    public void childSelectableCallbackErrorFallsBackToSelectable() throws IOException {
        String src = read("widget/LuaExpandableListAdapter.java");
        String body = methodBody(src,
                "public boolean isChildSelectable(int groupPosition, int childPosition)");
        int cat = body.indexOf("catch (");
        assertTrue("回调调用须有 catch", cat > 0);
        assertTrue("catch 分支须返回 true（失败不应让列表整体点不动）",
                body.indexOf("return true;", cat) > cat);
    }

    /** 位置须按 Lua 惯例 +1 传出，与本模块其余 adapter 的下标口径一致。 */
    @Test
    public void childSelectablePassesOneBasedPositions() throws IOException {
        String src = read("widget/LuaExpandableListAdapter.java");
        String body = methodBody(src,
                "public boolean isChildSelectable(int groupPosition, int childPosition)");
        assertTrue("groupPosition 应 +1 传给 Lua", body.contains("groupPosition + 1"));
        assertTrue("childPosition 应 +1 传给 Lua", body.contains("childPosition + 1"));
    }

    // ==================== getPathFromUri ====================

    /**
     * content 协议在 MediaStore.DATA 取不到时须回落到 ContentResolver 读流。
     * 该列在 API 29 起对 SAF/下载/云盘文档一律为 null，只查它等于常态静默失败。
     */
    @Test
    public void contentUriFallsBackToStreamCopy() throws IOException {
        String src = read("host/LuaIntentHelper.java");
        assertTrue("须经 ContentResolver.openInputStream 读内容",
                src.contains("openInputStream"));
        String body = methodBody(src, "public static String getPathFromUri(Context context, Uri uri)");
        assertTrue("DATA 取不到时须走复制回落", body.contains("copyToCache"));
        assertTrue("DATA 值须校验可读性，空串或已删除的文件不得直接返回",
                body.contains("canRead()"));
    }

    /** 副本命名须含大小，内容变化后不得读到过期副本。 */
    @Test
    public void cacheKeyIncludesSizeSoStaleCopiesAreNotReused() throws IOException {
        String src = read("host/LuaIntentHelper.java");
        String body = methodBody(src, "private static String copyToCache(Context context, Uri uri)");
        assertTrue("副本名须含 Uri 哈希", body.contains("uri.toString().hashCode()"));
        assertTrue("副本名须含大小", body.contains("size"));
        assertTrue("命中已有副本须比对长度", body.contains("dest.length() == size"));
    }

    /** 须先写 .part 再改名：中途失败不得留下长度对得上却内容截断的副本。 */
    @Test
    public void copyIsAtomicViaTempFile() throws IOException {
        String src = read("host/LuaIntentHelper.java");
        String body = methodBody(src, "private static String copyToCache(Context context, Uri uri)");
        assertTrue("须写临时文件", body.contains("\".part\""));
        assertTrue("须改名到最终位置", body.contains("renameTo(dest)"));
        int rename = body.indexOf("renameTo(dest)");
        assertTrue("改名失败须清理临时文件",
                body.indexOf("tmp.delete()", rename) > rename);
    }

    /** 副本落 cacheDir 且可整体清理，否则会无界堆积。 */
    @Test
    public void copiesLiveInClearableCacheDir() throws IOException {
        String src = read("host/LuaIntentHelper.java");
        assertTrue("副本须落 cacheDir", src.contains("context.getCacheDir()"));
        assertTrue("须提供清理入口", src.contains("public static int clearUriCache(Context context)"));
    }

    /** file 协议仍直接返回路径，不该被复制路径拖慢。 */
    @Test
    public void fileUriReturnsPathDirectly() throws IOException {
        String src = read("host/LuaIntentHelper.java");
        String body = methodBody(src, "public static String getPathFromUri(Context context, Uri uri)");
        int f = body.indexOf("\"file\".equals(scheme)");
        assertTrue("须处理 file 协议", f > 0);
        assertTrue("file 协议应直接返回 uri.getPath()",
                body.indexOf("uri.getPath()", f) > f);
        assertTrue("file 分支须早于复制逻辑",
                f < body.indexOf("copyToCache"));
    }
}
