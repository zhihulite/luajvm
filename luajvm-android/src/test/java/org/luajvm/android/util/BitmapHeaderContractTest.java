package org.luajvm.android.util;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 钉住图片请求头对所有远程加载点一致生效。
 *
 * <p>{@link LuaBitmap} 的 setHeader/setHeaders 把请求头存进进程级表，脚本用它给图片 CDN
 * 带鉴权（Cookie/Referer/User-Agent）。请求头只在加载点显式包装 {@code GlideUrl} 时才进入
 * 请求 —— 任何直接 {@code load(url)} 的加载点都会静默丢头，表现为「异步加载能过鉴权、
 * 同步加载 401」这类只在部分入口出现的故障。
 *
 * <p>扫源码而非跑 Glide：Glide 的加载走 Android 线程池与 Bitmap 解码，纯 JVM 跑不起来；
 * 而「每个远程加载点都必须经统一包装」本就是源码级不变量（同 core 侧
 * {@code ThreadEntryContractTest} 的手法）。
 */
public class BitmapHeaderContractTest {

    /** Glide 链式调用的起点。取到语句末尾的 {@code ;} 为止即一个完整加载点。 */
    private static final Pattern GLIDE_ENTRY = Pattern.compile("Glide\\.with\\(");

    /** 语句内的取模型调用，捕获实参。 */
    private static final Pattern LOAD_ARG = Pattern.compile("\\.load\\(([^)]*)\\)");

    /** 实参已经过统一包装。 */
    private static final String WRAPPER = "toGlideModel";

    /**
     * 只接受本地模型的加载点：实参类型本身排除了远程 URL（File / 资源 id），
     * 带请求头没有意义。列白名单而不是放宽正则 —— 新增加载点必须显式归类。
     */
    private static final List<String> LOCAL_ONLY_ARGS = List.of("file", "resourceId");

    /** 模块内所有含 Glide 加载点的文件。少写一个就等于漏守，故由测试自己校验完整性。 */
    private static final List<String> GLIDE_FILES = List.of(
            "util/LuaBitmap.java",
            "widget/LuaBitmapDrawable.java",
            "widget/NineBitmapDrawable.java",
            "widget/AdapterHelper.java",
            "widget/LuaLayout.java");

    private static final Path MAIN = Path.of("src/main/java/org/luajvm/android");

    /** 每个远程加载点都必须经 {@code toGlideModel} 取模型，否则请求头静默丢失。 */
    @Test
    public void everyRemoteLoadSiteGoesThroughHeaderWrapper() throws IOException {
        List<String> offenders = new ArrayList<>();
        int checked = 0;
        for (String rel : GLIDE_FILES) {
            Path file = MAIN.resolve(rel);
            assertTrue("被测文件应存在: " + file, Files.isRegularFile(file));
            String text = Files.readString(file, StandardCharsets.UTF_8);
            Matcher entry = GLIDE_ENTRY.matcher(text);
            while (entry.find()) {
                int end = text.indexOf(';', entry.start());
                String stmt = end < 0 ? text.substring(entry.start())
                        : text.substring(entry.start(), end);
                Matcher load = LOAD_ARG.matcher(stmt);
                if (!load.find()) continue;   // Glide.with 但不取模型（如 pauseRequests）
                String arg = load.group(1).trim();
                if (arg.isEmpty() || LOCAL_ONLY_ARGS.contains(arg)) continue;
                checked++;
                if (!stmt.contains(WRAPPER)) {
                    long line = 1 + text.substring(0, entry.start()).chars()
                            .filter(c -> c == '\n').count();
                    offenders.add(rel + ":" + line + " load(" + arg + ")");
                }
            }
        }
        assertTrue("前置：应扫到远程加载点（实测 " + checked + " 处）；为 0 则本断言空转",
                checked > 0);
        assertTrue("远程图片加载点必须经 LuaBitmap." + WRAPPER + " 取模型，否则 setHeader"
                + " 设的请求头静默丢失（实测 " + offenders.size() + " 处绕过）: " + offenders,
                offenders.isEmpty());
    }

    /** 文件清单必须涵盖模块内全部 Glide 加载点，否则新加的加载点无人守护。 */
    @Test
    public void fileListCoversEveryGlideCallSite() throws IOException {
        List<String> missing = new ArrayList<>();
        try (var walk = Files.walk(MAIN)) {
            for (Path p : walk.filter(x -> x.toString().endsWith(".java")).toList()) {
                String text = Files.readString(p, StandardCharsets.UTF_8);
                if (!GLIDE_ENTRY.matcher(text).find()) continue;
                String rel = MAIN.relativize(p).toString().replace('\\', '/');
                if (!GLIDE_FILES.contains(rel)) missing.add(rel);
            }
        }
        assertTrue("这些文件含 Glide 加载点但不在 GLIDE_FILES 清单里（漏守）: " + missing,
                missing.isEmpty());
    }

    /** 本地路径不得被判为远程 —— {@code new URL("/sdcard/a.png")} 会抛。 */
    @Test
    public void localPathsAreNotTreatedAsRemote() {
        assertTrue("http 应判为远程", LuaBitmap.isRemote("http://a/b.png"));
        assertTrue("https 应判为远程", LuaBitmap.isRemote("https://a/b.png"));
        assertTrue("scheme 大小写不敏感", LuaBitmap.isRemote("HTTPS://A/b.png"));
        assertFalse("绝对路径不是远程", LuaBitmap.isRemote("/sdcard/a.png"));
        assertFalse("相对路径不是远程", LuaBitmap.isRemote("img/a.png"));
        assertFalse("file:// 不是远程", LuaBitmap.isRemote("file:///sdcard/a.png"));
        assertFalse("content:// 不是远程", LuaBitmap.isRemote("content://media/1"));
        assertFalse("assets 不是远程", LuaBitmap.isRemote("/android_asset/a.png"));
        assertFalse("null 不是远程", LuaBitmap.isRemote(null));
        assertFalse("空串不是远程", LuaBitmap.isRemote(""));
    }

    /**
     * 无请求头时不得包装：包成 GlideUrl 会换掉 Glide 的缓存键，
     * 使同一图片在设 / 未设请求头两态下各存一份磁盘缓存。
     */
    @Test
    public void modelIsPlainStringWhenNoHeaders() {
        LuaBitmap.clearHeaders();
        assertFalse("无请求头时不应包装（保持 Glide 缓存键不变）",
                LuaBitmap.wrapsHeaders("http://a/b.png"));
    }

    /** 设了请求头时：远程 URL 才包装，本地路径不包装。 */
    @Test
    public void headersOnlyWrapRemoteUrls() {
        LuaBitmap.clearHeaders();
        LuaBitmap.setHeader("Cookie", "k=v");
        try {
            assertFalse("本地路径不得包装（GlideUrl 解析必抛 MalformedURLException）",
                    LuaBitmap.wrapsHeaders("/sdcard/a.png"));
            assertFalse("content:// 不得包装", LuaBitmap.wrapsHeaders("content://media/1"));
            assertTrue("远程 URL 应包装以携带请求头",
                    LuaBitmap.wrapsHeaders("http://a/b.png"));
            assertTrue("https 同样应包装", LuaBitmap.wrapsHeaders("https://a/b.png"));
        } finally {
            LuaBitmap.clearHeaders();
        }
    }

    /** 请求头表必须能清空 —— 否则脚本换账号后仍带旧 Cookie。 */
    @Test
    public void headersAreClearable() {
        LuaBitmap.setHeader("Cookie", "old=1");
        assertTrue("前置：设过头后远程 URL 应包装（否则本用例空转）",
                LuaBitmap.wrapsHeaders("http://a/b.png"));
        LuaBitmap.clearHeaders();
        assertFalse("clearHeaders 后应回到无头态",
                LuaBitmap.wrapsHeaders("http://a/b.png"));
    }

    /** 保底：确认扫描目录存在（路径写错会让上面的扫描断言空转）。 */
    @Test
    public void sourceTreeIsWhereWeThink() {
        assertTrue("源码目录应存在: " + MAIN.toAbsolutePath(), Files.isDirectory(MAIN));
    }
}
