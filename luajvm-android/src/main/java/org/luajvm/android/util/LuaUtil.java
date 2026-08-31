package org.luajvm.android.util;

import android.annotation.SuppressLint;
import android.content.Context;

import org.luajvm.android.runtime.LuaLog;

import org.luajvm.core.LuaString;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Enumeration;
import java.util.Locale;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;

@SuppressWarnings("unused")
public class LuaUtil {

    private static final int BUFFER_SIZE = 8192;

    private static final char[] HEX_DIGITS = "0123456789abcdef".toCharArray();

    // 文件魔数（大写十六进制）到扩展名，按魔数长度降序排列——前缀匹配时长魔数先命中。
    // wav/avi 的魔数位于 RIFF 头偏移 8 处，从偏移 0 读永不命中，保留只为不改变既有识别行为。
    private static final String[][] FILE_MAGICS = {
            {"255044462D312E", "pdf"},
            {"7B5C727466", "rtf"}, {"3C3F786D6C", "xml"}, {"68746D6C3E", "html"},
            {"89504E47", "png"}, {"47494638", "gif"}, {"49492A00", "tif"},
            {"41433130", "dwg"}, {"38425053", "psd"}, {"D0CF11E0", "doc"},
            {"504B0304", "docx"}, {"52617221", "rar"}, {"57415645", "wav"},
            {"41564920", "avi"},
            {"FFD8FF", "jpg"}, {"1F8B08", "gz"},
            {"424D", "bmp"},
    };

    // ==================== 文件读写 ====================

    public static byte[] readAsset(Context context, String name) throws IOException {
        try (InputStream is = context.getAssets().open(name)) {
            return readAll(is);
        }
    }

    public static byte[] readAll(String path) throws IOException {
        try (InputStream is = new FileInputStream(path)) {
            return readAll(is);
        }
    }

    /** 读空 input 但**不关闭**它——流的所有权归调用方（saf 等调用点依赖这一点）。 */
    public static byte[] readAll(InputStream input) throws IOException {
        try (ByteArrayOutputStream out = new ByteArrayOutputStream(BUFFER_SIZE * 128)) {
            byte[] buf = new byte[BUFFER_SIZE];
            int read;
            while ((read = input.read(buf)) != -1) {
                out.write(buf, 0, read);
            }
            return out.toByteArray();
        }
    }

    /**
     * ByteBuffer 从头到 limit 的全部字节。
     *
     * <p>在 duplicate 上读，原 buffer 的 position/limit 不动，调用方可继续使用它。
     * duplicate 继承原 limit，只把 position 归零，故读出的是 [0, limit)；
     * {@code clear()} 会把 limit 抬到 capacity，那样会多读出尾部未写入的字节。
     */
    public static byte[] readAll(ByteBuffer buffer) {
        ByteBuffer dup = buffer.duplicate();
        dup.position(0);
        byte[] out = new byte[dup.remaining()];
        dup.get(out);
        return out;
    }

    // ==================== 文件复制 ====================

    public static void copyAsset(Context context, String assetName, String destPath) throws IOException {
        try (OutputStream out = new FileOutputStream(destPath);
             InputStream in = context.getAssets().open(assetName)) {
            copyStream(in, out);
        }
    }

    public static void copyFile(String src, String dest) {
        try (InputStream in = new FileInputStream(src);
             OutputStream out = new FileOutputStream(dest)) {
            copyStream(in, out);
        } catch (IOException e) {
            LuaLog.getInstance().add("copyFile: " + e.getMessage());
        }
    }

    public static boolean copyFile(InputStream in, OutputStream out) {
        return copyStream(in, out);
    }

    public static boolean copyDir(String src, String dest) {
        return copyDir(new File(src), new File(dest));
    }

    public static boolean copyDir(File src, File dest) {
        File parent = dest.getParentFile();
        if (parent != null && !parent.exists() && !parent.mkdirs() && !parent.isDirectory()) return false;
        if (src.isDirectory()) {
            File[] children = src.listFiles();
            if (children != null && children.length != 0) {
                for (File child : children) {
                    if (!copyDir(child, new File(dest, child.getName()))) return false;
                }
            } else {
                // 目标已是目录不算失败，否则一个已存在的空子目录会中止整棵树的拷贝
                return dest.isDirectory() || dest.mkdirs();
            }
        } else {
            try (InputStream in = new FileInputStream(src);
                 OutputStream out = new FileOutputStream(dest)) {
                return copyStream(in, out);
            } catch (IOException e) {
                LuaLog.getInstance().add("copyDir: " + e.getMessage());
                return false;
            }
        }
        return true;
    }

    private static boolean copyStream(InputStream in, OutputStream out) {
        try {
            byte[] buf = new byte[BUFFER_SIZE];
            int read;
            while ((read = in.read(buf)) != -1) {
                out.write(buf, 0, read);
            }
            return true;
        } catch (IOException e) {
            // 只吞 IO 失败；RuntimeException 照常上抛，不掩盖编程错误
            LuaLog.getInstance().add("copyStream: " + e.getMessage());
            return false;
        }
    }

    // ==================== 删除 ====================

    public static boolean rmDir(File file) {
        if (file.isDirectory()) {
            File[] children = file.listFiles();
            if (children != null) {
                for (File child : children) rmDir(child);
            }
        }
        file.setWritable(true);
        return file.delete();
    }

    public static void rmDir(File dir, String ext) {
        if (dir.isDirectory()) {
            File[] children = dir.listFiles();
            if (children != null) {
                for (File child : children) rmDir(child, ext);
            }
        }
        if (dir.getName().endsWith(ext)) dir.delete();
    }

    // ==================== ZIP ====================

    public static byte[] readZip(String zipPath, String entryPath) throws IOException {
        try (ZipFile zip = new ZipFile(zipPath)) {
            ZipEntry entry = zip.getEntry(entryPath);
            if (entry == null) throw new FileNotFoundException(entryPath + " in " + zipPath);
            try (InputStream is = zip.getInputStream(entry)) {
                return readAll(is);
            }
        }
    }

    public static void unzip(String zipPath) throws IOException {
        unzip(zipPath, parentDirOf(zipPath), "");
    }

    public static void unzip(String zipPath, boolean namedDir) throws IOException {
        if (!namedDir) {
            unzip(zipPath);
            return;
        }
        String name = zipBaseName(new File(zipPath).getName());
        unzip(zipPath, parentDirOf(zipPath) + File.separator + name, "");
    }

    // 裸文件名（"a.zip"）的 getParent() 为 null，直接拼进 new File(String, String) 会 NPE
    private static String parentDirOf(String path) {
        String parent = new File(path).getAbsoluteFile().getParent();
        return parent != null ? parent : ".";
    }

    public static void unzip(String zipPath, String destDir) throws IOException {
        unzip(zipPath, destDir, "");
    }

    public static void unzip(String zipPath, String destDir, String prefix) throws IOException {
        File root = new File(destDir);
        String rootPath = root.getCanonicalPath();
        try (ZipFile zip = new ZipFile(zipPath)) {
            Enumeration<? extends ZipEntry> entries = zip.entries();
            while (entries.hasMoreElements()) {
                ZipEntry entry = entries.nextElement();
                String name = entry.getName();
                if (!name.startsWith(prefix)) continue;
                File out = resolveEntry(root, rootPath, name);
                if (entry.isDirectory()) {
                    if (!out.exists()) out.mkdirs();
                } else {
                    File dir = out.getParentFile();
                    if (dir != null && !dir.exists() && !dir.mkdirs()) {
                        throw new IOException("Failed to create: " + dir);
                    }
                    try (OutputStream fos = new FileOutputStream(out);
                         InputStream is = zip.getInputStream(entry)) {
                        if (!copyStream(is, fos)) {
                            // 必须检查返回值：丢弃它会让写失败的 unzip 仍"成功"返回、留下残缺文件
                            throw new IOException("Failed to extract: " + name);
                        }
                    }
                }
            }
        }
    }

    // Zip Slip 防护：条目名含 ../ 时可写到 destDir 之外，解压前先做规范化校验
    private static File resolveEntry(File root, String rootPath, String entryName) throws IOException {
        File out = new File(root, entryName);
        String path = out.getCanonicalPath();
        if (!path.equals(rootPath) && !path.startsWith(rootPath + File.separator)) {
            throw new IOException("Zip entry outside target dir: " + entryName);
        }
        return out;
    }

    public static boolean zip(String srcPath) {
        return zip(srcPath, parentDirOf(srcPath));
    }

    public static boolean zip(String srcPath, String destDir) {
        return zip(srcPath, destDir, new File(srcPath).getName() + ".zip");
    }

    public static boolean zip(String srcPath, String destDir, String zipName) {
        File zipFile = new File(destDir, zipName);
        File parent = zipFile.getParentFile();
        if (parent != null && !parent.exists() && !parent.mkdirs()) return false;
        try (FileOutputStream fos = new FileOutputStream(zipFile);
             ZipOutputStream zos = new ZipOutputStream(new BufferedOutputStream(fos))) {
            // 每次压缩各自持一份缓冲：共享 static byte[] 会让并发 zip() 互相踩数据
            compress(new File(srcPath), zos, "", new byte[BUFFER_SIZE]);
            return true;
        } catch (IOException e) {
            LuaLog.getInstance().addError("LuaUtil", e);
            return false;
        }
    }

    // 单个条目失败直接上抛：只记日志继续会让 zip() 为缺条目的包报成功
    private static void compress(File file, ZipOutputStream zos, String prefix, byte[] buf) throws IOException {
        if (file.isFile()) {
            try (FileInputStream fis = new FileInputStream(file);
                 BufferedInputStream bis = new BufferedInputStream(fis, BUFFER_SIZE)) {
                zos.putNextEntry(new ZipEntry(prefix + file.getName()));
                int read;
                while ((read = bis.read(buf)) != -1) zos.write(buf, 0, read);
                zos.closeEntry();
            }
        } else if (file.isDirectory()) {
            File[] children = file.listFiles();
            if (children != null) {
                for (File child : children) {
                    compress(child, zos, child.isDirectory() ? prefix + child.getName() + "/" : prefix, buf);
                }
            }
        }
    }

    public static LuaString readZipFile(String zipPath, String entryPath) throws IOException {
        return LuaString.valueOf(readZip(zipPath, entryPath));
    }

    /** 注入 APK 路径的版本：调用方无需经过 LuaApplication 单例。 */
    public static LuaString readApkFile(String apkPath, String entryPath) throws IOException {
        try (ZipFile zip = new ZipFile(apkPath)) {
            ZipEntry entry = zip.getEntry(entryPath);
            if (entry == null) throw new FileNotFoundException(entryPath + " in apk");
            try (InputStream is = zip.getInputStream(entry)) {
                return LuaString.valueOf(readAll(is));
            }
        }
    }

    // 去后缀，并在第一个 '_' 与 '(' 处截断：a_1(2).zip -> a
    private static String zipBaseName(String name) {
        int i = name.lastIndexOf('.');
        if (i > 0) name = name.substring(0, i);
        i = name.indexOf('_');
        if (i > 0) name = name.substring(0, i);
        i = name.indexOf('(');
        if (i > 0) name = name.substring(0, i);
        return name;
    }

    // ==================== 文件类型 ====================

    public static String getFileType(String path) {
        try (InputStream is = new FileInputStream(path)) {
            return getFileType(is);
        } catch (IOException e) {
            LuaLog.getInstance().addError("LuaUtil", e);
        }
        return "unknown";
    }

    public static String getFileType(File file) {
        try (InputStream is = new FileInputStream(file)) {
            return getFileType(is);
        } catch (IOException e) {
            LuaLog.getInstance().addError("LuaUtil", e);
        }
        return "unknown";
    }

    public static String getFileType(InputStream is) {
        String header = readMagicHeader(is);
        if (header == null) return "unknown";
        // FILE_MAGICS 按魔数长度降序，首个前缀命中即最长匹配
        for (String[] magic : FILE_MAGICS) {
            if (header.startsWith(magic[0])) return magic[1];
        }
        return "unknown";
    }

    // 读文件头并转大写十六进制。读 7 字节：最长魔数 pdf 占 14 个十六进制位；
    //   只读 4 字节再用精确键查表，jpg/gz/bmp/rtf/xml/html/pdf 会永远匹配不上。
    private static String readMagicHeader(InputStream is) {
        try (is) {
            byte[] head = new byte[7];
            // 单次 read 不保证读满：循环补读，短读会让长魔数（pdf 14 个十六进制位）误判 unknown
            int n = 0;
            while (n < head.length) {
                int r = is.read(head, n, head.length - n);
                if (r <= 0) break;
                n += r;
            }
            if (n <= 0) return null;
            // FILE_MAGICS 用大写，toHex 出小写（摘要惯例），此处统一
            return toHex(head, n).toUpperCase(Locale.ROOT);
        } catch (IOException ignored) {
            return null;
        }
    }

    // 定长十六进制：每字节恒两位。BigInteger.toString(16) 会吞前导零，
    //   首字节为 0x00 时 MD5 只剩 31 位（约 1/256 的输入会命中）。
    private static String toHex(byte[] bytes, int len) {
        StringBuilder sb = new StringBuilder(len * 2);
        for (int i = 0; i < len; i++) {
            sb.append(HEX_DIGITS[(bytes[i] >> 4) & 0xF]).append(HEX_DIGITS[bytes[i] & 0xF]);
        }
        return sb.toString();
    }

    // ==================== 哈希 ====================

    public static String getFileMD5(String path) {
        return getFileMD5(new File(path));
    }

    public static String getFileMD5(File file) {
        try (InputStream in = new FileInputStream(file)) {
            return digest(in, "MD5");
        } catch (IOException e) {
            return null;
        }
    }

    public static String getFileMD5(InputStream in) {
        return digest(in, "MD5");
    }

    public static String getFileSha1(String path) {
        return getFileSha1(new File(path));
    }

    public static String getFileSha1(File file) {
        try (InputStream in = new FileInputStream(file)) {
            return digest(in, "SHA-1");
        } catch (IOException e) {
            return null;
        }
    }

    public static String getFileSha1(InputStream in) {
        return digest(in, "SHA-1");
    }

    /**
     * 字节数据的 MD5 十六进制摘要（小写，定长 32 位）。
     *
     * <p>Lua 字符串本身就是字节串，可直喂 {@code byte[]} 形参（Coercion 的
     * BytesParamCoercion），故脚本侧传原始数据即可，不需要任何编码往返。
     */
    public static String md5(byte[] data) {
        return digest(data, "MD5");
    }

    /** 字节数据的 SHA-1 十六进制摘要（小写，定长 40 位）。 */
    public static String sha1(byte[] data) {
        return digest(data, "SHA-1");
    }

    private static String digest(byte[] data, String algo) {
        if (data == null) return null;
        try {
            byte[] hash = MessageDigest.getInstance(algo).digest(data);
            return toHex(hash, hash.length);
        } catch (NoSuchAlgorithmException e) {
            LuaLog.getInstance().addError("LuaUtil", e);
            return null;
        }
    }

    private static String digest(InputStream in, String algo) {
        try (in) {
            MessageDigest md = MessageDigest.getInstance(algo);
            byte[] buf = new byte[BUFFER_SIZE];
            int n;
            while ((n = in.read(buf)) != -1) md.update(buf, 0, n);
            byte[] hash = md.digest();
            return toHex(hash, hash.length);
        } catch (IOException | NoSuchAlgorithmException e) {
            LuaLog.getInstance().addError("LuaUtil", e);
            return null;
        }
    }

    // ==================== 保存 ====================

    public static void save(String path, String text) {
        try {
            File parent = new File(path).getParentFile();
            if (parent != null && !parent.exists()) parent.mkdirs();
            try (FileOutputStream out = new FileOutputStream(path)) {
                out.write(text.getBytes());
            }
        } catch (Exception e) {
            LuaLog.getInstance().addError("LuaUtil", e);
        }
    }

    /** 写出并关闭流——所有权归本方法（与 readAll 相反）；返回写入是否成功。 */
    public static boolean save(OutputStream out, LuaString text) {
        try (out) {
            out.write(text.bytes());
            return true;
        } catch (Exception e) {
            LuaLog.getInstance().addError("LuaUtil", e);
            return false;
        }
    }

    // ==================== 工具 ====================

    @SuppressLint("SimpleDateFormat")
    public static String getTimeName(String name, String ext) {
        return name + new SimpleDateFormat("_yyyy-MM-dd-HH-mm-ss").format(new Date()) + ext;
    }

    public static float getSimilarityRatio(String src, String target) {
        int max = Math.max(src.length(), target.length());
        return max == 0 ? 1f : 1 - (float) levenshtein(src, target) / max;
    }

    private static int levenshtein(String src, String tgt) {
        int m = src.length(), n = tgt.length();
        if (m == 0) return n;
        if (n == 0) return m;
        int[] prev = new int[n + 1], curr = new int[n + 1];
        for (int j = 0; j <= n; j++) prev[j] = j;
        for (int i = 1; i <= m; i++) {
            curr[0] = i;
            char c1 = src.charAt(i - 1);
            for (int j = 1; j <= n; j++) {
                int cost = (c1 == tgt.charAt(j - 1)
                        || c1 == Character.toLowerCase(tgt.charAt(j - 1))
                        || Character.toLowerCase(c1) == tgt.charAt(j - 1)) ? 0 : 1;
                curr[j] = Math.min(Math.min(curr[j - 1] + 1, prev[j] + 1), prev[j - 1] + cost);
            }
            int[] tmp = prev;
            prev = curr;
            curr = tmp;
        }
        return prev[n];
    }

    /**
     * 入口脚本是否就位 —— {@code .lua} 或同名 {@code .luac} 任一存在即算。
     *
     * <p>只打字节码的包（{@code -PluaMode=luac}）里没有 {@code .lua}，运行期由
     * {@code BaseLib.loadFile} 读同名 {@code .luac}。若「关键文件是否存在」的判据只看
     * {@code .lua}，会把「已解包」误判成「解包失败」：assets 每次启动都被重解包，
     * 且 Activity 走 default view 使 {@code Globals} 为 null。
     */
    public static boolean luaEntryExists(File luaFile) {
        if (luaFile == null) return false;
        if (luaFile.isFile()) return true;
        String path = luaFile.getPath();
        return path.endsWith(".lua")
                && new File(path.substring(0, path.length() - 4) + ".luac").isFile();
    }
}
