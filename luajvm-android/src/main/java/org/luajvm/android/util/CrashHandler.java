package org.luajvm.android.util;

import android.content.Context;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Process;

import androidx.annotation.NonNull;
import androidx.core.content.pm.PackageInfoCompat;

import org.luajvm.android.runtime.LuaConfig;
import org.luajvm.android.runtime.LuaLog;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.lang.Thread.UncaughtExceptionHandler;
import java.lang.reflect.Field;
import java.text.SimpleDateFormat;
import java.util.Arrays;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * 全局异常捕获处理类
 * 程序发生未捕获异常时，收集设备信息和错误日志并保存到文件
 */
public class CrashHandler implements UncaughtExceptionHandler {

    private static final String TAG = "CrashHandler";
    private static final String CRASH_DIR = "crash";
    private static final String FILE_PREFIX = "crash";
    private static final String FILE_EXTENSION = ".log";
    private static final String DATE_FORMAT = "yyyy-MM-dd-HH-mm-ss";

    private static final CrashHandler sInstance = new CrashHandler();
    private final Map<String, String> mDeviceInfo = new LinkedHashMap<>();
    private UncaughtExceptionHandler mDefaultHandler;
    private Context mContext;

    private CrashHandler() {
    }

    public static CrashHandler getInstance() {
        return sInstance;
    }

    /**
     * 初始化崩溃捕获器
     *
     * @param context 上下文
     */
    public void init(Context context) {
        mContext = context.getApplicationContext();
        mDefaultHandler = Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler(this);
    }

    @Override
    public void uncaughtException(@NonNull Thread thread, @NonNull Throwable ex) {
        if (ex != null) {
            collectDeviceInfo();
            saveCrashLog(ex);
        }
        // 必须交还系统默认处理器（崩溃对话框 / 系统崩溃上报），
        // 否则系统侧收不到崩溃、表现为静默黑屏
        if (mDefaultHandler != null) {
            mDefaultHandler.uncaughtException(thread, ex);
        }
        // 默认处理器返回仍未退出（部分设备/模拟器上未捕获异常不会自动闪退，
        // 仅让 UI 线程栈展开、进程仍存活黑屏）时兜底：
        Process.killProcess(Process.myPid());
        System.exit(1);
    }

    /**
     * 收集设备信息
     */
    private void collectDeviceInfo() {
        collectPackageInfo();
        collectBuildInfo();
        collectVersionInfo();
    }

    private void collectPackageInfo() {
        try {
            PackageManager packageManager = mContext.getPackageManager();
            PackageInfo packageInfo = packageManager.getPackageInfo(
                    mContext.getPackageName(), PackageManager.GET_ACTIVITIES);
            if (packageInfo != null) {
                mDeviceInfo.put("versionName",
                        packageInfo.versionName != null ? packageInfo.versionName : "null");
                mDeviceInfo.put("versionCode",
                        String.valueOf(PackageInfoCompat.getLongVersionCode(packageInfo)));
            }
        } catch (PackageManager.NameNotFoundException e) {
            LuaConfig.logError(TAG, e);
        }
    }

    private void collectBuildInfo() {
        collectFields(Build.class);
    }

    private void collectVersionInfo() {
        collectFields(Build.VERSION.class);
    }

    private void collectFields(Class<?> clazz) {
        Field[] fields = clazz.getDeclaredFields();
        for (Field field : fields) {
            try {
                field.setAccessible(true);
                Object value = field.get(null);
                String stringValue = formatFieldValue(value);
                mDeviceInfo.put(field.getName(), stringValue);
                LuaConfig.log(TAG + ": " + field.getName() + " : " + stringValue);
            } catch (Exception e) {
                LuaConfig.logError(TAG, e);
            }
        }
    }

    private String formatFieldValue(Object value) {
        if (value == null) {
            return "null";
        }
        if (value instanceof String[] values) {
            return Arrays.toString(values);
        }
        return value.toString();
    }

    /**
     * 保存崩溃日志到文件
     *
     * @param ex 异常
     */
    private void saveCrashLog(Throwable ex) {
        String logContent = buildLogContent(ex);

        // 崩溃日志固定写外部存储；外部存储不可用时只报错，不落盘
        try {
            File externalDir = mContext.getExternalFilesDir(null);
            if (externalDir == null) {
                LuaLog.getInstance().addError(TAG,
                        new IOException("External storage unavailable, crash log not saved"));
                return;
            }
            File crashDir = new File(externalDir, CRASH_DIR);
            if (!crashDir.exists() && !crashDir.mkdirs()) {
                LuaLog.getInstance().addError(TAG,
                        new IOException("Failed to create crash directory: " + crashDir));
                return;
            }

            String fileName = generateFileName();
            File logFile = new File(crashDir, fileName);

            try (FileOutputStream fos = new FileOutputStream(logFile)) {
                fos.write(logContent.getBytes());
            }

            LuaLog.getInstance().add("Crash log saved: " + logFile.getAbsolutePath());

        } catch (Exception e) {
            LuaConfig.logError(TAG, e);
        }
    }

    private String buildLogContent(Throwable ex) {
        StringBuilder log = new StringBuilder();

        // 设备信息
        for (Map.Entry<String, String> entry : mDeviceInfo.entrySet()) {
            log.append(entry.getKey()).append("=").append(entry.getValue()).append("\n");
        }

        // 异常堆栈
        log.append("\n").append(getStackTraceString(ex));

        return log.toString();
    }

    private String getStackTraceString(Throwable ex) {
        var writer = new StringWriter();
        try (var printWriter = new PrintWriter(writer)) {
            // printStackTrace 已含全部 "Caused by:" 层级，手工遍历 cause 链会整段重复
            ex.printStackTrace(printWriter);
        }

        return writer.toString();
    }


    private String generateFileName() {
        // 崩溃可能发生在任意线程，SimpleDateFormat 非线程安全——每次新建
        //（崩溃路径，分配成本无所谓）
        String timestamp = String.valueOf(System.currentTimeMillis());
        String date = new SimpleDateFormat(DATE_FORMAT, Locale.getDefault()).format(new Date());
        return FILE_PREFIX + "-" + date + "-" + timestamp + FILE_EXTENSION;
    }
}
