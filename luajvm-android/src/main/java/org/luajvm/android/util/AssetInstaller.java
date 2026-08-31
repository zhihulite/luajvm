package org.luajvm.android.util;

import android.content.Context;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * APK assets 解压核心，{@code LuaApplication.extractAssetsIfNeeded} 与
 * {@code Welcome.detectVersionChangeAndExtract} 共用。
 *
 * <p>destDir 是<b>安装区</b>（{@code getFilesDir}，即 {@code LuaApplication.getLocalDir}）：
 * 每次解压前整目录清空，内容完全由 APK 内 assets 决定。用户数据归
 * {@code getLuaExtDir}（外部存储），两者分离。
 *
 * <p>清空用 {@link LuaUtil#rmDir} 逐子项删除（保留 destDir 本身；rmDir 自带
 * setWritable，可清掉上次 Welcome 路径设置的只读 .dex）。是否给 .dex 加只读位由
 * 调用方策略决定。错误日志通道由调用方决定，本类只抛 {@link IOException}。
 */
public final class AssetInstaller {

    /** 孤儿清单上报条数上限：日志里列几条足以定位，全量会把日志刷爆。 */
    static final int MAX_REPORTED_ORPHANS = 20;

    private AssetInstaller() {
    }

    /**
     * 清空 destDir 并把 APK 内 entryPrefix 前缀的条目解压进去。
     * destDir 不存在且创建失败时静默返回。
     *
     * @return 被删除且未由 assets 重新写回的相对路径（安装区里的非 assets 产物）。
     *         调用方应告警：这些文件在升级时消失，属安装区语义，脚本要持久化
     *         应写 {@code getLuaExtDir}。最多 {@link #MAX_REPORTED_ORPHANS} 条。
     */
    public static List<String> extract(Context context, String entryPrefix, File destDir,
                                       boolean markDexReadOnly) throws IOException {
        return extract(context.getApplicationInfo().publicSourceDir,
                entryPrefix, destDir, markDexReadOnly);
    }

    /**
     * 按 APK 路径解压（{@link #extract(Context, String, File, boolean)} 的实现体）。
     * 与 Context 解耦使孤儿上报可在纯 JVM 下取证（Android stub 不能调 Context 方法）。
     */
    static List<String> extract(String apkPath, String entryPrefix, File destDir,
                                boolean markDexReadOnly) throws IOException {
        Set<String> before = listRelativeFiles(destDir);
        if (destDir.exists()) {
            File[] children = destDir.listFiles();
            if (children != null) for (File c : children) LuaUtil.rmDir(c);
        }
        if (!destDir.exists() && !destDir.mkdirs()) return List.of();

        Set<String> written = new LinkedHashSet<>();
        try (ZipInputStream zis = new ZipInputStream(new FileInputStream(apkPath))) {
            byte[] buffer = new byte[8192];
            ZipEntry entry;
            while ((entry = zis.getNextEntry()) != null) {
                String entryName = entry.getName();
                if (entryName.startsWith(entryPrefix) && !entry.isDirectory()) {
                    String relativePath = entryName.substring(entryPrefix.length());
                    File targetFile = new File(destDir, relativePath);
                    File parentDir = targetFile.getParentFile();
                    if (parentDir != null && !parentDir.exists()) parentDir.mkdirs();
                    try (FileOutputStream fos = new FileOutputStream(targetFile)) {
                        int len;
                        while ((len = zis.read(buffer)) > 0) fos.write(buffer, 0, len);
                    }
                    if (markDexReadOnly && relativePath.endsWith(".dex")) {
                        targetFile.setReadOnly();
                    }
                    written.add(relativePath);
                }
                zis.closeEntry();
            }
        }

        List<String> orphans = new ArrayList<>();
        for (String rel : before) {
            if (written.contains(rel)) continue;
            orphans.add(rel);
            if (orphans.size() >= MAX_REPORTED_ORPHANS) break;
        }
        return orphans;
    }

    /** destDir 下所有文件的相对路径（'/' 分隔，与 zip 条目名同形）。 */
    private static Set<String> listRelativeFiles(File destDir) {
        Set<String> out = new TreeSet<>();
        if (destDir == null || !destDir.isDirectory()) return out;
        collect(destDir, "", out);
        return out;
    }

    private static void collect(File dir, String prefix, Set<String> out) {
        File[] children = dir.listFiles();
        if (children == null) return;
        for (File c : children) {
            String rel = prefix.isEmpty() ? c.getName() : prefix + "/" + c.getName();
            if (c.isDirectory()) {
                collect(c, rel, out);
            } else {
                out.add(rel);
            }
        }
    }
}
