package org.luajvm.android.host;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.net.Uri;

import androidx.core.content.FileProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

/**
 * {@code LuaIntentHelper.getPathFromUri} 的真机行为。
 *
 * <p>必须仪器化：要 {@code ContentResolver} 与 provider。纯 JVM 上
 * {@code MediaStore.DATA} 与 {@code openInputStream} 都无从验证。
 */
@RunWith(AndroidJUnit4.class)
public class UriPathInstrumentedTest {

    private static final String CONTENT = "uri-path-probe-内容";

    private Context ctx;
    private File src;

    @Before
    public void setUp() throws Exception {
        ctx = InstrumentationRegistry.getInstrumentation().getTargetContext();
        LuaIntentHelper.clearUriCache(ctx);
        // FileProvider 的 root 需与 manifest 的 file_paths 对得上，用 filesDir 最稳
        src = new File(ctx.getFilesDir(), "uri_probe.txt");
        Files.write(src.toPath(), CONTENT.getBytes(StandardCharsets.UTF_8));
    }

    @After
    public void tearDown() {
        if (src != null) src.delete();
        if (ctx != null) LuaIntentHelper.clearUriCache(ctx);
    }

    /** file:// 直接给路径，不产生副本。 */
    @Test
    public void fileUriReturnsPathWithoutCopying() {
        String path = LuaIntentHelper.getPathFromUri(ctx, Uri.fromFile(src));
        assertEquals("file:// 应原样返回路径", src.getAbsolutePath(), path);
        assertEquals("不应产生副本", 0, cacheFileCount());
    }

    /**
     * content:// 经 FileProvider：{@code MediaStore.DATA} 该 provider 不提供，
     * 须回落读流并给出可打开的副本路径。
     */
    @Test
    public void contentUriYieldsReadablePath() throws Exception {
        Uri uri = LuaIntentHelper.getUriForFile(ctx, src);
        assertNotNull("FileProvider 应能生成 Uri", uri);
        assertEquals("应是 content 协议", "content", uri.getScheme());

        String path = LuaIntentHelper.getPathFromUri(ctx, uri);
        assertNotNull("content:// 须解析出路径（DATA 列缺失时回落复制）", path);
        File f = new File(path);
        assertTrue("路径须指向可读文件: " + path, f.canRead());
        assertEquals("内容须与源文件一致",
                CONTENT, readUtf8(f));
    }

    /** 同一 Uri 重复解析命中已有副本，不重复复制。 */
    @Test
    public void repeatedResolveReusesCachedCopy() {
        Uri uri = LuaIntentHelper.getUriForFile(ctx, src);
        String first = LuaIntentHelper.getPathFromUri(ctx, uri);
        assertNotNull(first);
        int afterFirst = cacheFileCount();
        String second = LuaIntentHelper.getPathFromUri(ctx, uri);
        assertEquals("同一 Uri 应给出同一副本路径", first, second);
        assertEquals("不应新增副本", afterFirst, cacheFileCount());
    }

    /** 源内容变长后须给出新副本，不得返回过期内容。 */
    @Test
    public void changedContentProducesFreshCopy() throws Exception {
        Uri uri = LuaIntentHelper.getUriForFile(ctx, src);
        String before = LuaIntentHelper.getPathFromUri(ctx, uri);
        assertEquals(CONTENT, readUtf8(new File(before)));

        String grown = CONTENT + "-appended";
        Files.write(src.toPath(), grown.getBytes(StandardCharsets.UTF_8));
        String after = LuaIntentHelper.getPathFromUri(ctx, uri);
        assertNotNull(after);
        assertEquals("变更后须读到新内容",
                grown, readUtf8(new File(after)));
    }

    /** 不存在的 content Uri 须返回 null，不得抛到调用方。 */
    @Test
    public void unresolvableContentUriReturnsNull() {
        Uri bogus = Uri.parse("content://org.luajvm.absent.provider/nope");
        assertNull("无法解析的 content Uri 应返回 null", LuaIntentHelper.getPathFromUri(ctx, bogus));
    }

    /** null 与未知协议都不得抛。 */
    @Test
    public void nullAndUnknownSchemeAreSafe() {
        assertNull(LuaIntentHelper.getPathFromUri(ctx, null));
        assertNull(LuaIntentHelper.getPathFromUri(ctx, Uri.parse("https://example.com/a.txt")));
    }

    /** 清理入口须真的删掉副本，避免无界堆积。 */
    @Test
    public void clearUriCacheRemovesCopies() {
        Uri uri = LuaIntentHelper.getUriForFile(ctx, src);
        assertNotNull(LuaIntentHelper.getPathFromUri(ctx, uri));
        assertTrue("应已产生副本", cacheFileCount() > 0);
        LuaIntentHelper.clearUriCache(ctx);
        assertEquals("清理后副本应为 0", 0, cacheFileCount());
    }

    /** 设备 ART 无 Files.readString（Java 11 API，desugar 未覆盖）。 */
    private static String readUtf8(File f) throws Exception {
        return new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8);
    }

    private int cacheFileCount() {
        File dir = new File(ctx.getCacheDir(), "uri_cache");
        File[] fs = dir.listFiles();
        return fs == null ? 0 : fs.length;
    }
}
