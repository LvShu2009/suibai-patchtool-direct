package com.lvshu.build;

import android.app.Application;
import android.content.Context;
import android.content.pm.PackageInfo;
import android.os.Build;
import android.os.Process;
import android.util.Log;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.text.SimpleDateFormat;
import java.util.Arrays;
import java.util.Date;
import java.util.Locale;

public final class DiagLog {
    private static final String LOG_TAG = "DiagLog";
    private static final String LOG_NAME = "diag.log";
    private static final String RUN_LOG_NAME = "run.log";
    private static final String CRASH_NAME = "crash.txt";
    private static final Object LOCK = new Object();

    private static final SimpleDateFormat DATE_FORMAT =
            new SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US);

    // 以下字段仅在持有 LOCK 时访问。
    private static Context appContext;
    private static boolean installed;
    private static File activeLogFile;

    private DiagLog() {
    }

    public static void install(Context ctx) {
        try {
            synchronized (LOCK) {
                if (ctx != null) {
                    // attachBaseContext() 阶段可能尚无 Application Context。
                    if (appContext == null && ctx instanceof Application) {
                        appContext = ctx;
                    }

                    try {
                        Context applicationContext = ctx.getApplicationContext();
                        if (applicationContext != null) {
                            appContext = applicationContext;
                        }
                    } catch (Throwable ignored) {
                    }
                }

                // 即使已安装异常处理器，也允许重新尝试创建日志目录。
                try {
                    Paths.ensureDirs(ctx != null ? ctx : appContext);
                } catch (Throwable ignored) {
                }

                if (installed) {
                    return;
                }

                final Thread.UncaughtExceptionHandler previous =
                        Thread.getDefaultUncaughtExceptionHandler();

                Thread.setDefaultUncaughtExceptionHandler(
                        new Thread.UncaughtExceptionHandler() {
                            @Override
                            public void uncaughtException(Thread thread,
                                                          Throwable error) {
                                try {
                                    crash(
                                            thread == null
                                                    ? "unknown thread"
                                                    : thread.getName(),
                                            error);
                                } catch (Throwable ignored) {
                                } finally {
                                    boolean delegated = false;

                                    if (previous != null) {
                                        try {
                                            previous.uncaughtException(
                                                    thread, error);
                                            delegated = true;
                                        } catch (Throwable ignored) {
                                        }
                                    }

                                    if (!delegated) {
                                        try {
                                            Process.killProcess(Process.myPid());
                                        } catch (Throwable ignored) {
                                        }
                                        try {
                                            System.exit(10);
                                        } catch (Throwable ignored) {
                                        }
                                    }
                                }
                            }
                        });

                installed = true;
            }
        } catch (Throwable ignored) {
        }
    }

    public static void mark(String stage) {
        try {
            Log.i(LOG_TAG, stage);
        } catch (Throwable ignored) {
        }

        try {
            synchronized (LOCK) {
                appendLog(appContext, timestamped(String.valueOf(stage)));
            }
        } catch (Throwable ignored) {
        }
    }

    /** 运行状态日志：打开工具、下载、重试等，写入 运行日志/run.log。 */
    public static void run(String stage) {
        try {
            synchronized (LOCK) {
                appendTo(appContext, RUN_LOG_NAME,
                        timestamped(String.valueOf(stage)), false);
            }
        } catch (Throwable ignored) {
        }
    }

    /** 补丁安装日志：写入 安装日志/diag.log（与诊断同一文件）。 */
    public static void installLog(String stage) {
        mark(stage);
    }

    public static void deviceInfo(Context ctx) {
        try {
            synchronized (LOCK) {
                Context context = ctx != null ? ctx : appContext;
                String packageName = "<unknown>";
                String versionName = "<unknown>";
                String sharedUserId = "<unknown>";

                if (context != null) {
                    try {
                        packageName = context.getPackageName();
                    } catch (Throwable ignored) {
                    }

                    try {
                        // sharedUserId 位于 PackageInfo，而非 ApplicationInfo。
                        PackageInfo info = context.getPackageManager()
                                .getPackageInfo(packageName, 0);
                        versionName = String.valueOf(info.versionName);
                        sharedUserId = String.valueOf(info.sharedUserId);
                    } catch (Throwable ignored) {
                    }
                }

                String snapshot = "设备快照"
                        + " MODEL=" + Build.MODEL
                        + " BRAND=" + Build.BRAND
                        + " VERSION.RELEASE=" + Build.VERSION.RELEASE
                        + " VERSION.SDK_INT=" + Build.VERSION.SDK_INT
                        + " ABI=" + Arrays.toString(Build.SUPPORTED_ABIS)
                        + " process=" + readProcessName()
                        + " package=" + packageName
                        + " versionName=" + versionName
                        + " UID=" + Process.myUid()
                        + " sharedUserId=" + sharedUserId;

                appendLog(context, timestamped(snapshot));
            }
        } catch (Throwable ignored) {
        }
    }

    public static void crash(String tag, Throwable e) {
        try {
            synchronized (LOCK) {
                String stack = stackTrace(e);
                String record = timestamped(
                        "!!!! CRASH " + String.valueOf(tag) + "\n" + stack);

                // 保留原行为：安装日志中也记录本次崩溃。
                try {
                    appendLog(appContext, record);
                } catch (Throwable ignored) {
                }

                // 独立写入崩溃目录，不复用安装日志的首选目录。
                try {
                    writeWithFallback(
                            appContext, CRASH_NAME, record, false);
                } catch (Throwable ignored) {
                }
            }
        } catch (Throwable ignored) {
        }
    }

    public static void checkLastCrash(Context ctx) {
        try {
            synchronized (LOCK) {
                Context context = ctx != null ? ctx : appContext;

                for (int i = 0; i < 3; i++) {
                    try {
                        File dir = crashDirectory(context, i);
                        if (dir != null
                                && new File(dir, CRASH_NAME).isFile()) {
                            String stage = "存在上次崩溃记录";

                            try {
                                Log.i(LOG_TAG, stage);
                            } catch (Throwable ignored) {
                            }

                            appendLog(context, timestamped(stage));
                            return;
                        }
                    } catch (Throwable ignored) {
                    }
                }
            }
        } catch (Throwable ignored) {
        }
    }

    public static String crashReportPath() {
        try {
            synchronized (LOCK) {
                // 保留原语义：返回实际成功写入的安装日志路径。
                if (activeLogFile != null) {
                    try {
                        return activeLogFile.getAbsolutePath();
                    } catch (Throwable ignored) {
                    }
                }

                // 尚无实际写入路径时，优先返回 Paths 的安装日志默认路径。
                // Paths 不可用时，继续尝试应用外部目录、内部目录。
                for (int i = 0; i < 3; i++) {
                    try {
                        File dir = directory(appContext, i);
                        if (dir != null) {
                            return new File(dir, LOG_NAME).getAbsolutePath();
                        }
                    } catch (Throwable ignored) {
                    }
                }
            }
        } catch (Throwable ignored) {
        }

        // Paths 和 Context 均不可用时，不臆造公共目录路径。
        return LOG_NAME;
    }

    private static String timestamped(String content) {
        try {
            return DATE_FORMAT.format(new Date()) + "  " + content
                    + (content.endsWith("\n") ? "" : "\n");
        } catch (Throwable ignored) {
            try {
                return String.valueOf(content) + "\n";
            } catch (Throwable ignoredAgain) {
                return "\n";
            }
        }
    }

    private static void appendLog(Context ctx, String record) {
        try {
            File file = writeWithFallback(ctx, LOG_NAME, record, true);
            if (file != null) {
                activeLogFile = file;
            }
        } catch (Throwable ignored) {
        }
    }

    /** 按文件名把记录写到对应目录（运行日志走 运行日志/ 目录）。 */
    private static void appendTo(Context ctx, String name,
                                 String record, boolean trackActive) {
        try {
            File file = writeWithFallback(ctx, name, record, true);
            if (trackActive && file != null) {
                activeLogFile = file;
            }
        } catch (Throwable ignored) {
        }
    }

    private static File writeWithFallback(Context ctx, String name,
                                          String record, boolean append) {
        try {
            for (int i = 0; i < 3; i++) {
                try {
                    File dir;
                    if (CRASH_NAME.equals(name)) {
                        dir = crashDirectory(ctx, i);
                    } else if (RUN_LOG_NAME.equals(name)) {
                        dir = runDirectory(ctx, i);
                    } else {
                        dir = directory(ctx, i);
                    }

                    if (dir == null) {
                        continue;
                    }

                    if (!dir.isDirectory()
                            && !dir.mkdirs()
                            && !dir.isDirectory()) {
                        continue;
                    }

                    File file = new File(dir, name);
                    if (writeFile(file, record, append)) {
                        return file;
                    }
                } catch (Throwable ignored) {
                    // 当前目录失败后继续尝试下一层回退目录。
                }
            }
        } catch (Throwable ignored) {
        }

        return null;
    }

    private static File directory(Context ctx, int index) {
        try {
            if (index == 0) {
                return new File(Paths.installLogDir(ctx));
            }

            if (ctx == null) {
                return null;
            }

            if (index == 1) {
                return ctx.getExternalFilesDir(null);
            }

            if (index == 2) {
                return ctx.getFilesDir();
            }
        } catch (Throwable ignored) {
        }

        return null;
    }

    private static File crashDirectory(Context ctx, int index) {
        try {
            if (index == 0) {
                return new File(Paths.crashLogDir(ctx));
            }

            // 回退目录与安装日志保持一致，文件名仍为 crash.txt。
            return directory(ctx, index);
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static File runDirectory(Context ctx, int index) {
        try {
            if (index == 0) {
                return new File(Paths.runLogDir(ctx));
            }

            // 回退目录沿用安装日志的回退链，文件名仍为 run.log。
            return directory(ctx, index);
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static boolean writeFile(File file, String record,
                                     boolean append) {
        FileOutputStream stream = null;
        OutputStreamWriter writer = null;

        try {
            stream = new FileOutputStream(file, append);
            writer = new OutputStreamWriter(stream, "UTF-8");
            writer.write(record);
            writer.close();
            writer = null;
            stream = null;
            return true;
        } catch (Throwable ignored) {
            return false;
        } finally {
            if (writer != null) {
                try {
                    writer.close();
                } catch (Throwable ignored) {
                }
            }

            if (stream != null) {
                try {
                    stream.close();
                } catch (Throwable ignored) {
                }
            }
        }
    }

    private static String stackTrace(Throwable error) {
        if (error == null) {
            return "<null throwable>";
        }

        try {
            String stack = Log.getStackTraceString(error);
            if (stack != null && stack.length() != 0) {
                return stack;
            }
        } catch (Throwable ignored) {
        }

        // Log.getStackTraceString() 可能省略 UnknownHostException 堆栈。
        try {
            StringWriter buffer = new StringWriter();
            PrintWriter printer = new PrintWriter(buffer);
            error.printStackTrace(printer);
            printer.flush();
            return buffer.toString();
        } catch (Throwable ignored) {
            return "<stack trace unavailable>";
        }
    }

    private static String readProcessName() {
        try {
            if (Build.VERSION.SDK_INT >= 28) {
                try {
                    String name = Application.getProcessName();
                    if (name != null && name.length() != 0) {
                        return name;
                    }
                } catch (Throwable ignored) {
                }
            }
        } catch (Throwable ignored) {
        }

        FileInputStream stream = null;

        try {
            stream = new FileInputStream("/proc/self/cmdline");
            byte[] buffer = new byte[4096];
            int length = 0;

            while (length < buffer.length) {
                int value = stream.read();
                if (value <= 0) {
                    break;
                }
                buffer[length++] = (byte) value;
            }

            if (length > 0) {
                return new String(buffer, 0, length, "UTF-8");
            }
        } catch (Throwable ignored) {
        } finally {
            if (stream != null) {
                try {
                    stream.close();
                } catch (Throwable ignored) {
                }
            }
        }

        return "<unknown>";
    }
}
