package org.luajvm.android.host;

import android.content.Context;
import android.content.Intent;
import android.database.Cursor;
import android.net.Uri;
import android.provider.MediaStore.Images.Media;
import android.provider.OpenableColumns;
import android.webkit.MimeTypeMap;

import androidx.annotation.NonNull;
import androidx.core.content.FileProvider;


import org.luajvm.android.runtime.LuaConfig;
import org.luajvm.android.util.LuaUtil;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Objects;

/**
 * Activity/文件操作工具类。
 */
public class LuaIntentHelper {

    /** Intent extra 键，与 host.LuaActivity.ARG/NAME 同值同协议 */
    private static final String EXTRA_ARG = "arg";
    private static final String EXTRA_NAME = "name";

    protected LuaIntentHelper() {
    }

    public static void newActivity(Context context, String luaDir, String path) throws FileNotFoundException {
        newActivity(context, luaDir, 1, path, null, false);
    }

    public static void newActivity(Context context, String luaDir, String path, Object[] arg) throws FileNotFoundException {
        newActivity(context, luaDir, 1, path, arg, false);
    }

    public static void newActivity(Context context, String luaDir, int requestCode, String path) throws FileNotFoundException {
        newActivity(context, luaDir, requestCode, path, null, false);
    }

    public static void newActivity(Context context, String luaDir, int requestCode, String path, Object[] arg) throws FileNotFoundException {
        newActivity(context, luaDir, requestCode, path, arg, false);
    }

    public static void newActivity(Context context, String luaDir, int requestCode, String path, Object[] arg, boolean newDocument) throws FileNotFoundException {
        Intent intent = new Intent(context, newDocument ? LuaActivityX.class : LuaActivity.class);
        intent.putExtra(EXTRA_NAME, path);

        String fullPath = resolveLuaPath(luaDir, path);

        if (newDocument) {
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_DOCUMENT | Intent.FLAG_ACTIVITY_MULTIPLE_TASK);
        }

        // Uri.fromFile 做路径段编码；裸拼 "file://" 时路径里的 #/? 会截断 URI
        intent.setData(Uri.fromFile(new File(fullPath)));
        if (arg != null) intent.putExtra(EXTRA_ARG, arg);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        context.startActivity(intent);
    }

    /**
     * 解析 Lua 脚本路径
     */
    @NonNull
    public static String resolveLuaPath(String luaDir, String path) throws FileNotFoundException {
        String fullPath = path.startsWith("/") ? path : luaDir + "/" + path;
        File target = new File(fullPath);

        if (target.isDirectory()) {
            File mainLua = new File(fullPath + "/main.lua");
            if (mainLua.exists()) fullPath = mainLua.getAbsolutePath();
        } else if (!target.exists() && !fullPath.endsWith(".lua")) {
            fullPath += ".lua";
        }

        if (!new File(fullPath).exists()) {
            throw new FileNotFoundException(fullPath);
        }
        return fullPath;
    }

    public static Uri getUriForFile(Context context, File path) {
        // authority 必须与 manifest 的 ${applicationId}.FileProvider 同串，
        // 少了后缀 FileProvider 会找不到声明并抛 IllegalArgumentException
        return FileProvider.getUriForFile(context, context.getPackageName() + ".FileProvider", path);
    }

    /** 经 SAF 取到的内容复制到该缓存子目录，可整体清理。 */
    private static final String URI_CACHE_DIR = "uri_cache";

    /**
     * 把 Uri 解析成 Lua 侧可直接 {@code io.open} 的本地路径。
     *
     * <p>content 协议先查 {@code MediaStore.DATA}：该列在 API 29 起对多数 provider
     * 返回 null（SAF、下载、云盘文档都没有本地路径列），此时经 {@code ContentResolver}
     * 打开输入流并复制到 {@code cacheDir/uri_cache}，返回副本路径。契约仍是"返回可打开
     * 的路径"，Lua 侧无需区分来源。
     *
     * <p>副本按 Uri 与文件大小命名，同一 Uri 重复解析命中已有副本不重复复制。
     */
    public static String getPathFromUri(Context context, Uri uri) {
        if (uri == null) return null;
        String scheme = uri.getScheme();
        if (scheme == null) return null;

        if ("file".equals(scheme)) return uri.getPath();
        if (!"content".equals(scheme)) return null;

        // DATA 列存在即直接用：省去复制，且拿到的是真实路径
        try (Cursor cursor = context.getContentResolver().query(uri,
                new String[]{Media.DATA}, null, null, null)) {
            if (cursor != null && cursor.moveToFirst()) {
                int dataColumn = cursor.getColumnIndex(Media.DATA);
                if (dataColumn >= 0) {
                    String path = cursor.getString(dataColumn);
                    // 列存在但值为空，或文件已不在：继续走复制
                    if (path != null && !path.isEmpty() && new File(path).canRead()) return path;
                }
            }
        } catch (Exception ignored) {
            // provider 不支持该列会抛，属正常路径
        }
        return copyToCache(context, uri);
    }

    /** 经 ContentResolver 读流并落缓存，返回副本路径；失败返回 null。 */
    private static String copyToCache(Context context, Uri uri) {
        String displayName = queryDisplayName(context, uri);
        long size = querySize(context, uri);
        // 名字含 Uri 哈希与大小：同一 Uri 内容变化（大小变）会得到新副本，
        //   不会读到过期内容；不同 Uri 即便同名也不互相覆盖
        String ext = "";
        if (displayName != null) {
            int dot = displayName.lastIndexOf('.');
            if (dot > 0) ext = displayName.substring(dot);
        }
        String key = Integer.toHexString(uri.toString().hashCode()) + "_" + size + ext;
        File dir = new File(context.getCacheDir(), URI_CACHE_DIR);
        if (!dir.isDirectory() && !dir.mkdirs()) return null;
        File dest = new File(dir, key);
        if (dest.isFile() && (size < 0 || dest.length() == size)) return dest.getAbsolutePath();

        // 先写临时文件再改名：中途失败不会留下长度对得上却内容截断的副本
        File tmp = new File(dir, key + ".part");
        try (InputStream in = context.getContentResolver().openInputStream(uri)) {
            if (in == null) return null;
            try (OutputStream out = new FileOutputStream(tmp)) {
                if (!LuaUtil.copyFile(in, out)) return null;
            }
        } catch (Exception e) {
            LuaConfig.logError("getPathFromUri", e);
            tmp.delete();
            return null;
        }
        if (dest.isFile() && !dest.delete()) {
            tmp.delete();
            return null;
        }
        if (!tmp.renameTo(dest)) {
            tmp.delete();
            return null;
        }
        return dest.getAbsolutePath();
    }

    private static String queryDisplayName(Context context, Uri uri) {
        try (Cursor c = context.getContentResolver().query(uri,
                new String[]{OpenableColumns.DISPLAY_NAME}, null, null, null)) {
            if (c != null && c.moveToFirst()) {
                int i = c.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                if (i >= 0) return c.getString(i);
            }
        } catch (Exception ignored) {
        }
        return null;
    }

    private static long querySize(Context context, Uri uri) {
        try (Cursor c = context.getContentResolver().query(uri,
                new String[]{OpenableColumns.SIZE}, null, null, null)) {
            if (c != null && c.moveToFirst()) {
                int i = c.getColumnIndex(OpenableColumns.SIZE);
                if (i >= 0 && !c.isNull(i)) return c.getLong(i);
            }
        } catch (Exception ignored) {
        }
        return -1;
    }

    /**
     * 清空 Uri 副本缓存。宿主可在退出或低内存时调用；不调用则随 app 缓存被系统回收。
     *
     * @return 删除的文件数
     */
    public static int clearUriCache(Context context) {
        File dir = new File(context.getCacheDir(), URI_CACHE_DIR);
        File[] files = dir.listFiles();
        if (files == null) return 0;
        int n = 0;
        for (File f : files) if (f.delete()) n++;
        return n;
    }

    public static String getMimeType(File file) {
        String name = file.getName();
        int lastDot = name.lastIndexOf('.');
        if (lastDot >= 0) {
            String ext = name.substring(lastDot + 1);
            return Objects.requireNonNullElse(
                    MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext),
                    "application/octet-stream");
        }
        return "application/octet-stream";
    }

    public static void installApk(Context context, String path) {
        viewFile(context, path, Intent.FLAG_GRANT_READ_URI_PERMISSION);
    }

    public static void openFile(Context context, String path) {
        viewFile(context, path,
                Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
    }

    private static void viewFile(Context context, String path, int uriFlags) {
        Intent intent = new Intent(Intent.ACTION_VIEW);
        File file = new File(path);
        intent.setFlags(uriFlags);
        intent.setDataAndType(getUriForFile(context, file), getMimeType(file));
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        context.startActivity(intent);
    }

    public static void shareFile(Context context, String path) {
        Intent intent = new Intent(Intent.ACTION_SEND);
        File file = new File(path);
        intent.setType("*/*");
        intent.setFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        intent.putExtra(Intent.EXTRA_STREAM, getUriForFile(context, file));
        context.startActivity(Intent.createChooser(intent, file.getName()).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
    }
}
