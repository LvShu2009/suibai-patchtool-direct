package com.lvshu.build;

import android.content.Context;
import android.util.Log;

import java.io.File;

public final class Paths {

    public static final String DEFAULT_ROOT =
            "/storage/emulated/0/LvShu/酸败补丁";

    private static final String TAG = "Paths";
    private static final String PREFS_NAME = "cfg";
    private static final String KEY_BASE_ROOT = "base_root";

    private static final String PATCH_NAME = "补丁存放位置（zip）";
    private static final String LOG_NAME = "酸败自助补丁工具";
    private static final String INSTALL_NAME = "安装日志";
    private static final String RUN_NAME = "运行日志";
    private static final String CRASH_NAME = "崩溃日志";

    private Paths() {
    }

    public static String root(Context ctx) {
        String path = ctx.getSharedPreferences(PREFS_NAME, 0)
                .getString(KEY_BASE_ROOT, DEFAULT_ROOT);
        return normalize(path);
    }

    public static void setRoot(Context ctx, String path) {
        ctx.getSharedPreferences(PREFS_NAME, 0)
                .edit()
                .putString(KEY_BASE_ROOT, normalize(path))
                .apply();
    }

    public static String patchDir(Context ctx) {
        return root(ctx) + "/" + PATCH_NAME;
    }

    public static String logRoot(Context ctx) {
        return root(ctx) + "/" + LOG_NAME;
    }

    public static String installLogDir(Context ctx) {
        return logRoot(ctx) + "/" + INSTALL_NAME;
    }

    public static String runLogDir(Context ctx) {
        return logRoot(ctx) + "/" + RUN_NAME;
    }

    public static String crashLogDir(Context ctx) {
        return logRoot(ctx) + "/" + CRASH_NAME;
    }

    public static void ensureDirs(Context ctx) {
        ensureDir(ctx, patchDir(ctx), PATCH_NAME);
        ensureDir(ctx, logRoot(ctx), LOG_NAME);
        ensureDir(ctx, installLogDir(ctx),
                LOG_NAME + "/" + INSTALL_NAME);
        ensureDir(ctx, runLogDir(ctx),
                LOG_NAME + "/" + RUN_NAME);
        ensureDir(ctx, crashLogDir(ctx),
                LOG_NAME + "/" + CRASH_NAME);
    }

    public static String describe(Context ctx) {
        String base = root(ctx);
        String logs = base + "/" + LOG_NAME;

        return "根路径：" + base
                + "\n补丁目录：" + base + "/" + PATCH_NAME
                + "\n日志根：" + logs
                + "\n安装日志目录：" + logs + "/" + INSTALL_NAME
                + "\n运行日志目录：" + logs + "/" + RUN_NAME
                + "\n崩溃日志目录：" + logs + "/" + CRASH_NAME;
    }

    private static String normalize(String path) {
        if (path == null) {
            return DEFAULT_ROOT;
        }

        String value = path.trim();
        int end = value.length();
        while (end > 0 && value.charAt(end - 1) == '/') {
            end--;
        }

        return end == 0 ? DEFAULT_ROOT : value.substring(0, end);
    }

    private static boolean createDirectory(File directory) {
        return directory.isDirectory()
                || directory.mkdirs()
                || directory.isDirectory();
    }

    private static void ensureDir(Context ctx, String path,
                                  String relativePath) {
        try {
            if (createDirectory(new File(path))) {
                Log.i(TAG, "目录就绪：" + path);
                return;
            }
            Log.w(TAG, "目录创建失败：" + path);
        } catch (Throwable error) {
            Log.w(TAG, "目录创建异常：" + path + "；"
                    + error.getClass().getName());
        }

        // 公共目录不可用时，尽力创建应用内部备用目录。
        // 不修改用户配置，也不改变各路径方法的返回值。
        ensureFallbackDir(ctx, relativePath);
    }

    private static void ensureFallbackDir(Context ctx,
                                          String relativePath) {
        String fallbackPath = relativePath;

        try {
            File filesDir = ctx.getFilesDir();
            if (filesDir == null) {
                Log.w(TAG, "备用目录创建失败：应用内部目录不可用；"
                        + relativePath);
                return;
            }

            File fallbackRoot = new File(filesDir, "paths_fallback");
            File directory = new File(fallbackRoot, relativePath);
            fallbackPath = directory.getAbsolutePath();

            if (createDirectory(directory)) {
                Log.i(TAG, "备用目录就绪：" + fallbackPath);
            } else {
                Log.w(TAG, "备用目录创建失败：" + fallbackPath);
            }
        } catch (Throwable error) {
            Log.w(TAG, "备用目录创建异常：" + fallbackPath + "；"
                    + error.getClass().getName());
        }
    }
}
