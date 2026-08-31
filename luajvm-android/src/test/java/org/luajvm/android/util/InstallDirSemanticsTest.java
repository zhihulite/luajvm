package org.luajvm.android.util;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * 钉住安装区语义：{@code AssetInstaller.extract} 每次整目录清空后按 assets 重写，
 * 且必须把「被删除且未由 assets 写回」的孤儿路径回报给调用方。
 *
 * <p>语义本身是设计意图（用户数据归 {@code getLuaExtDir}，安装区只放 APK 内容），
 * 本门禁守的是<b>非静默</b>：孤儿不上报会让脚本作者的数据无声消失且无从定位。
 *
 * <p>纯 JVM 可跑：走与 Context 解耦的 {@code extract(String apkPath, ...)} 重载，
 * 不触碰 android stub（stub 方法体是 {@code throw new RuntimeException("Stub!")}）。
 */
public class InstallDirSemanticsTest {

    /** 造一个含 assets/ 前缀条目的假 APK。 */
    private static File fakeApk(Path dir, String... entries) throws Exception {
        File apk = dir.resolve("fake.apk").toFile();
        try (ZipOutputStream zos = new ZipOutputStream(new FileOutputStream(apk))) {
            for (String e : entries) {
                zos.putNextEntry(new ZipEntry("assets/" + e));
                zos.write(("content:" + e).getBytes(StandardCharsets.UTF_8));
                zos.closeEntry();
            }
        }
        return apk;
    }

    /** assets 内容必须落地；这是后面所有断言的前提。 */
    @Test
    public void assetsAreExtracted() throws Exception {
        Path tmp = Files.createTempDirectory("installdir");
        File apk = fakeApk(tmp, "main.lua", "pages/a.lua");
        File dest = tmp.resolve("files").toFile();

        AssetInstaller.extract(apk.getAbsolutePath(), "assets/", dest, false);

        assertTrue("main.lua 应被解压", new File(dest, "main.lua").isFile());
        assertTrue("子目录条目应被解压", new File(dest, "pages/a.lua").isFile());
    }

    /**
     * 核心断言：非 assets 产物被清掉时必须回报。
     *
     * <p>缺陷态（extract 返回 void 或恒返回空表）下此断言失败 —— 它直接取证
     * 「孤儿被发现」，不依赖任何日志通道。
     */
    @Test
    public void nonAssetFilesAreReportedAsOrphans() throws Exception {
        Path tmp = Files.createTempDirectory("installdir");
        File apk = fakeApk(tmp, "main.lua");
        File dest = tmp.resolve("files").toFile();
        assertTrue(dest.mkdirs());

        // 模拟脚本误把数据写进安装区
        Files.writeString(dest.toPath().resolve("user_data.json"), "{}");
        Files.createDirectories(dest.toPath().resolve("cache"));
        Files.writeString(dest.toPath().resolve("cache/token.txt"), "secret");
        // 同时放一个 assets 也有的文件：它会被写回，不算孤儿
        Files.writeString(dest.toPath().resolve("main.lua"), "old");

        List<String> orphans =
                AssetInstaller.extract(apk.getAbsolutePath(), "assets/", dest, false);

        assertFalse("前置：应真的清空过目录（user_data.json 必须已消失）", new File(dest, "user_data.json").exists());
        assertTrue("孤儿清单应含 user_data.json（实测 " + orphans + "）",
                orphans.contains("user_data.json"));
        assertTrue("孤儿清单应含子目录下的 cache/token.txt（实测 " + orphans + "）",
                orphans.contains("cache/token.txt"));
        assertFalse("assets 内也有的 main.lua 不是孤儿（会被写回）",
                orphans.contains("main.lua"));
    }

    /** 干净安装区（内容全部来自 assets）不得报孤儿 —— 否则每次升级都刷无用告警。 */
    @Test
    public void cleanInstallReportsNoOrphans() throws Exception {
        Path tmp = Files.createTempDirectory("installdir");
        File apk = fakeApk(tmp, "main.lua", "pages/a.lua");
        File dest = tmp.resolve("files").toFile();

        // 第一次解压建立干净安装区
        AssetInstaller.extract(apk.getAbsolutePath(), "assets/", dest, false);
        // 第二次（模拟升级）：内容全来自 assets，不应报孤儿
        List<String> orphans =
                AssetInstaller.extract(apk.getAbsolutePath(), "assets/", dest, false);

        assertEquals("干净安装区重解压不应报孤儿（实测 " + orphans + "）",
                List.of(), orphans);
    }

    /** 首次安装（目录不存在）不得报孤儿。 */
    @Test
    public void firstInstallReportsNoOrphans() throws Exception {
        Path tmp = Files.createTempDirectory("installdir");
        File apk = fakeApk(tmp, "main.lua");
        File dest = tmp.resolve("files").toFile();

        List<String> orphans =
                AssetInstaller.extract(apk.getAbsolutePath(), "assets/", dest, false);

        assertEquals("首次安装无孤儿（实测 " + orphans + "）", List.of(), orphans);
        assertTrue("且 assets 应已落地", new File(dest, "main.lua").isFile());
    }

    /** 孤儿数量必须封顶 —— 安装区被当数据目录用时可能有上千文件，全量列出会刷爆日志。 */
    @Test
    public void orphanListIsCapped() throws Exception {
        Path tmp = Files.createTempDirectory("installdir");
        File apk = fakeApk(tmp, "main.lua");
        File dest = tmp.resolve("files").toFile();
        assertTrue(dest.mkdirs());
        int total = AssetInstaller.MAX_REPORTED_ORPHANS * 3;
        for (int i = 0; i < total; i++) {
            Files.writeString(dest.toPath().resolve("junk_" + i + ".dat"), "x");
        }

        List<String> orphans =
                AssetInstaller.extract(apk.getAbsolutePath(), "assets/", dest, false);

        assertEquals("孤儿清单应封顶在 " + AssetInstaller.MAX_REPORTED_ORPHANS
                        + "（实测 " + orphans.size() + "）",
                AssetInstaller.MAX_REPORTED_ORPHANS, orphans.size());
    }

    /**
     * 语义文档必须写明「安装区会被清空、用户数据写 extDir」。
     *
     * <p>这条不是形式主义：语义本身不改（清空是设计意图），唯一的防线就是文档与告警，
     * 文档若被后人删掉，下一个人会把清空当 bug 去"修"。
     */
    @Test
    public void installDirSemanticsAreDocumented() throws Exception {
        Path api = Path.of("src/main/java/org/luajvm/android/LuaApplication.java");
        assertTrue("被测文件应存在: " + api.toAbsolutePath(), Files.isRegularFile(api));
        String text = Files.readString(api, StandardCharsets.UTF_8);
        int at = text.indexOf("public String getLocalDir()");
        assertTrue("应有 getLocalDir 方法", at > 0);
        String doc = text.substring(Math.max(0, at - 400), at);
        assertTrue("getLocalDir 的 javadoc 应说明安装区会被清空", doc.contains("清空"));
        assertTrue("getLocalDir 的 javadoc 应指向用户数据目录 getLuaExtDir",
                doc.contains("getLuaExtDir") || doc.contains("用户数据"));
    }
}
