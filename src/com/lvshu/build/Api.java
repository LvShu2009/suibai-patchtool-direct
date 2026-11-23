package com.lvshu.build;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.security.KeyFactory;
import java.security.PrivateKey;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.security.spec.PKCS8EncodedKeySpec;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.os.Environment;

/**
 * AndLua / Java 都从这里调用的门面。
 *
 * 调用方式（Lua）：
 *   import "com.lvshu.build.Api"
 *   local t = Api.listTargets(activity)      -- String[]，每行 pkg\tlabel\tver\tcode\tapk
 *   Api.startBuild(activity, pkg, outPath)
 *   Api.progress() / Api.status() / Api.done() / Api.result()
 */
public final class Api {

    /** 内置密钥声明的共享 UID，必须与直装包内 manifest 完全一致。 */
    public static final String SHARED_UID = "lvshu.direct300";

    private static volatile int sProgress;
    private static volatile String sStatus = "";
    private static volatile boolean sDone = true;
    private static volatile String sResult = "";
    private static volatile boolean sRunning;

    private Api() {
    }

    // ---------------- 已安装包 ----------------

    /** 列出可做成直装的游戏包（同一账号体系下的全部渠道）。 */
    public static String[] listTargets(Context ctx) {
        List<String> out = new ArrayList<String>();
        PackageManager pm = ctx.getPackageManager();
        List<PackageInfo> all;
        try {
            all = pm.getInstalledPackages(0);
        } catch (RuntimeException e) {
            all = new ArrayList<PackageInfo>();
        }
        for (PackageInfo pi : all) {
            if (pi.applicationInfo == null) {
                continue;
            }
            String pkg = pi.packageName;
            if (!isGamePackage(pkg, pi.applicationInfo)) {
                continue;
            }
            CharSequence label = pm.getApplicationLabel(pi.applicationInfo);
            String path = pi.applicationInfo.sourceDir;
            out.add(pkg + "\t" + label + "\t" + (pi.versionName == null ? "" : pi.versionName)
                    + "\t" + pi.versionCode + "\t" + path);
        }
        return out.toArray(new String[0]);
    }

    /** 300 大作战的官服 / 体验服 / 各渠道包名都落在 jumpw 下，另外用签名兜底。 */
    private static boolean isGamePackage(String pkg, ApplicationInfo ai) {
        String p = pkg.toLowerCase(Locale.ROOT);
        if (p.startsWith("com.jumpw.") || p.contains("jumpw") || p.contains("mobile300")) {
            return true;
        }
        // 渠道服可能换成别的包名，用进程名/数据目录兜底
        return ai.processName != null && ai.processName.toLowerCase(Locale.ROOT).contains("jumpw");
    }

    // ---------------- 构建 ----------------

    public static boolean isRunning() {
        return sRunning;
    }

    public static int progress() {
        return sProgress;
    }

    public static String status() {
        return sStatus == null ? "" : sStatus;
    }

    public static boolean done() {
        return sDone;
    }

    public static String result() {
        return sResult == null ? "" : sResult;
    }

    public static synchronized void startBuild(final Context ctx, final String pkg, final String outDir) {
        startBuild(ctx, pkg, outDir, false);
    }

    public static synchronized void startBuild(final Context ctx, final String pkg, final String outDir,
                                               final boolean injectBridge) {
        if (sRunning) {
            return;
        }
        sRunning = true;
        sDone = false;
        sProgress = 0;
        sResult = "";
        sStatus = "准备中…";
        Thread t = new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    String path = build(ctx, pkg, outDir, injectBridge);
                    sResult = path;
                    sStatus = "完成";
                    sProgress = 100;
                } catch (Throwable e) {
                    sResult = "ERR:" + describe(e);
                    sStatus = "失败：" + describe(e);
                } finally {
                    sDone = true;
                    sRunning = false;
                }
            }
        }, "direct-install-build");
        t.setPriority(Thread.NORM_PRIORITY - 1);
        t.start();
    }

    private static String describe(Throwable e) {
        String m = e.getMessage();
        return e.getClass().getSimpleName() + (m == null ? "" : (": " + m));
    }

    private static void report(int p, String msg) {
        sProgress = Math.max(0, Math.min(99, p));
        sStatus = msg;
    }

    /** 同步构建，返回输出路径。 */
    public static String build(Context ctx, String pkg, String outDir) throws Exception {
        return build(ctx, pkg, outDir, false);
    }

    public static String build(Context ctx, String pkg, String outDir, boolean injectBridge) throws Exception {
        PackageManager pm = ctx.getPackageManager();
        PackageInfo pi = pm.getPackageInfo(pkg, 0);
        String label = String.valueOf(pm.getApplicationLabel(pi.applicationInfo));
        String ver = pi.versionName == null ? String.valueOf(pi.versionCode) : pi.versionName;
        return buildApk(ctx, new File(pi.applicationInfo.sourceDir), label, ver, outDir, injectBridge);
    }

    /** 从指定 APK 文件构建（官服原包 / 别人发来的包都行）。 */
    public static String buildFromFile(Context ctx, File apk, String outDir) throws Exception {
        return buildFromFile(ctx, apk, outDir, false);
    }

    public static String buildFromFile(Context ctx, File apk, String outDir, boolean injectBridge) throws Exception {
        if (apk == null || !apk.isFile()) {
            throw new IOException("找不到这个 APK 文件");
        }
        String label = apk.getName().replaceFirst("\\.[A-Za-z0-9]+$", "");
        String ver = "";
        try {
            android.content.pm.PackageInfo pi = ctx.getPackageManager()
                    .getPackageArchiveInfo(apk.getAbsolutePath(), 0);
            if (pi != null) {
                ver = pi.versionName == null ? String.valueOf(pi.versionCode) : pi.versionName;
                if (pi.applicationInfo != null) {
                    CharSequence l = ctx.getPackageManager().getApplicationLabel(pi.applicationInfo);
                    if (l != null && l.length() > 0) {
                        String v2 = pi.versionName == null ? String.valueOf(pi.versionCode) : pi.versionName;
                        return buildApk(ctx, apk, String.valueOf(l), v2, outDir, injectBridge);
                    }
                }
            }
        } catch (Throwable ignored) {
        }
        return buildApk(ctx, apk, label, ver, outDir, injectBridge);
    }

    public static synchronized void startBuildFromFile(final Context ctx, final File apk, final String outDir) {
        startBuildFromFile(ctx, apk, outDir, false);
    }

    public static synchronized void startBuildFromFile(final Context ctx, final File apk, final String outDir,
                                                       final boolean injectBridge) {
        if (sRunning) {
            return;
        }
        sRunning = true;
        sDone = false;
        sProgress = 0;
        sResult = "";
        sStatus = "准备中…";
        Thread t = new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    String path = buildFromFile(ctx, apk, outDir, injectBridge);
                    sResult = path;
                    sStatus = "完成";
                    sProgress = 100;
                } catch (Throwable e) {
                    sResult = "ERR:" + describe(e);
                    sStatus = "失败：" + describe(e);
                } finally {
                    sDone = true;
                    sRunning = false;
                }
            }
        }, "direct-install-build-file");
        t.setPriority(Thread.NORM_PRIORITY - 1);
        t.start();
    }

    private static String buildApk(Context ctx, File source, String label, String ver, String outDir,
                                   boolean injectBridge) throws Exception {
        PackageManager pm = ctx.getPackageManager();
        if (!source.isFile()) {
            throw new IOException("读不到安装包：" + source);
        }
        // 有些机型 /data/app 下的 base.apk 不允许直接读，退回 su 拷一份
        if (!source.canRead()) {
            File copy = new File(ctx.getCacheDir(), "src_" + source.getName());
            if (!copy.exists() || copy.length() != source.length()) {
                report(1, "无权限直接读取，尝试 root 复制");
                if (!rootCopy(source, copy)) {
                    throw new IOException("无法读取安装包（既没直读权限也没有 root）");
                }
            }
            source = copy;
        }
        report(2, "读取 " + source.getName() + "（" + fmt(source.length()) + "）");

        byte[] manifest;
        ZipFile zf = new ZipFile(source);
        try {
            ZipEntry e = zf.getEntry("AndroidManifest.xml");
            if (e == null) {
                throw new IOException("安装包里没有 AndroidManifest.xml");
            }
            manifest = TestMain.readAll(zf.getInputStream(e));
        } finally {
            zf.close();
        }

        byte[] patched = Axml.setSharedUserId(manifest, SHARED_UID);
        report(5, "已写入 sharedUserId=" + SHARED_UID);
        // 中和会阻止共享 UID 的属性（game原包可能带 sandboxVersion=2 / sharedUserMaxSdkVersion）
        patched = Axml.neutralizeUidBlockers(patched);
        report(5, "已中和 sandbox/maxSdk 等阻断共享UID的属性");

        // 数据通道注入已移除：它会破坏游戏 manifest 导致直装包闪退。
        // 目录读写统一走工具的 root 后端（RootFileSystem）。
        Map<String, byte[]> extra = new LinkedHashMap<String, byte[]>();

        File dir = new File(outDir);
        if (!dir.exists() && !dir.mkdirs()) {
            throw new IOException("无法创建输出目录：" + dir);
        }
        String baseName = label.replaceAll("[\\\\/:*?\"<>|]", "_")
                + (ver == null || ver.length() == 0 ? "" : "_" + ver) + "_直装";
        File unsigned = new File(dir, baseName + ".apk.unsigned");
        File finalApk = new File(dir, baseName + ".apk");
        File tmpApk = new File(dir, baseName + ".apk.part");
        if (tmpApk.exists()) {
            tmpApk.delete();
        }
        if (unsigned.exists()) {
            unsigned.delete();
        }

        Map<String, byte[]> replace = new LinkedHashMap<String, byte[]>(extra);
        replace.put("AndroidManifest.xml", patched);
        ApkRepack.repack(source, unsigned, replace, new ApkRepack.Progress() {
            @Override
            public void onProgress(int percent, String message) {
                report(5 + percent * 80 / 100, message);
            }
        });
        // 回读校验：从产出的 unsigned 包里把 manifest 读回来，确认 sharedUserId 真的写进去了
        String check = "";
        ZipFile vf = new ZipFile(unsigned);
        try {
            ZipEntry ve = vf.getEntry("AndroidManifest.xml");
            if (ve != null) {
                check = Axml.getSharedUserId(TestMain.readAll(vf.getInputStream(ve)));
            }
        } finally {
            vf.close();
        }
        if (!SHARED_UID.equals(check)) {
            throw new IOException("manifest 校验失败：sharedUserId=" + check);
        }
        report(88, "manifest 校验通过（sharedUserId=" + check + "），开始签名");

        PrivateKey key = loadKey(ctx);
        X509Certificate cert = loadCert(ctx);
        Signer.sign(unsigned, tmpApk, key, cert, 21);
        unsigned.delete();

        if (finalApk.exists() && !finalApk.delete()) {
            throw new IOException("无法覆盖 " + finalApk.getName());
        }
        if (!tmpApk.renameTo(finalApk)) {
            throw new IOException("无法写出 " + finalApk);
        }
        report(100, "已生成 " + finalApk.getName() + "（" + fmt(finalApk.length()) + "）");
        return finalApk.getAbsolutePath();
    }

    /** 下一个可用的 classesN.dex 名字（原包已有 classes.dex…classesK.dex）。 */
    private static String nextDexName(File apk) {
        int max = 0;
        ZipFile zf = null;
        try {
            zf = new ZipFile(apk);
            java.util.Enumeration<? extends ZipEntry> en = zf.entries();
            while (en.hasMoreElements()) {
                String n = en.nextElement().getName();
                if (!n.matches("classes[0-9]*\\.dex")) {
                    continue;
                }
                if ("classes.dex".equals(n)) {
                    max = Math.max(max, 1);
                } else {
                    try {
                        max = Math.max(max, Integer.parseInt(n.substring(7, n.length() - 4)));
                    } catch (Throwable ignored) {
                    }
                }
            }
        } catch (Throwable ignored) {
        } finally {
            if (zf != null) {
                try {
                    zf.close();
                } catch (Throwable ignored) {
                }
            }
        }
        return max == 0 ? "classes.dex" : "classes" + (max + 1) + ".dex";
    }

    private static boolean rootCopy(File src, File dst) {
        try {
            if (dst.exists()) {
                dst.delete();
            }
            Process p = Runtime.getRuntime().exec(new String[]{
                    "su", "-c", "cp \"" + src.getAbsolutePath() + "\" \"" + dst.getAbsolutePath()
                            + "\" && chmod 644 \"" + dst.getAbsolutePath() + "\""});
            int rc = p.waitFor();
            return rc == 0 && dst.isFile() && dst.length() > 0;
        } catch (Throwable e) {
            return false;
        }
    }

    private static String fmt(long n) {
        if (n >= 1073741824L) {
            return String.format(Locale.ROOT, "%.2fG", n / 1073741824.0);
        }
        if (n >= 1048576L) {
            return String.format(Locale.ROOT, "%.1fM", n / 1048576.0);
        }
        return (n / 1024) + "K";
    }

    // ---------------- 内置密钥 ----------------

    public static PrivateKey loadKey(Context ctx) throws Exception {
        byte[] der = readAsset(ctx, "sign_key.der");
        return KeyFactory.getInstance("RSA").generatePrivate(new PKCS8EncodedKeySpec(der));
    }

    public static X509Certificate loadCert(Context ctx) throws Exception {
        byte[] der = readAsset(ctx, "sign_cert.der");
        return (X509Certificate) CertificateFactory.getInstance("X.509")
                .generateCertificate(new java.io.ByteArrayInputStream(der));
    }

    /** 证书 SHA-256 指纹，用来核对直装包与本体是不是同一把钥匙。 */
    public static String certFingerprint(Context ctx) {
        try {
            byte[] d = java.security.MessageDigest.getInstance("SHA-256").digest(loadCert(ctx).getEncoded());
            StringBuilder sb = new StringBuilder();
            for (byte b : d) {
                sb.append(String.format("%02X", b));
            }
            return sb.toString();
        } catch (Throwable e) {
            return "";
        }
    }

    private static byte[] readAsset(Context ctx, String name) throws IOException {
        InputStream in = ctx.getAssets().open(name);
        try {
            return TestMain.readAll(in);
        } finally {
            in.close();
        }
    }

    /** 默认输出目录：系统 Download。 */
    public static String defaultOutDir() {
        File f = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS);
        return f == null ? "/storage/emulated/0/Download" : f.getAbsolutePath();
    }

    /** 是否拿到“所有文件访问权限”。 */
    public static boolean hasAllFiles(Context ctx) {
        try {
            if (android.os.Build.VERSION.SDK_INT < 30) {
                return true;
            }
            return Environment.isExternalStorageManager();
        } catch (Throwable e) {
            return false;
        }
    }

    /** 目标目录能不能真的写进去（实际写一个探针文件，比 canWrite 准）。 */
    public static boolean canWriteDir(File dir) {
        try {
            if (!dir.isDirectory() && !dir.mkdirs()) {
                return false;
            }
            File probe = new File(dir, ".probe_" + System.currentTimeMillis());
            FileOutputStream o = new FileOutputStream(probe);
            o.write(1);
            o.close();
            probe.delete();
            return true;
        } catch (Throwable e) {
            return false;
        }
    }

    /** 解析实际可用的输出目录：首选用户设定，其次 Download，最后应用专属目录。 */
    public static String resolveOutDir(Context ctx, String desired) {
        if (desired != null && desired.trim().length() > 0) {
            File d = new File(desired.trim());
            if (canWriteDir(d)) {
                return d.getAbsolutePath();
            }
        }
        File dl = new File(defaultOutDir());
        if (canWriteDir(dl)) {
            return dl.getAbsolutePath();
        }
        File own = ctx.getExternalFilesDir(null);
        if (own != null && canWriteDir(own)) {
            return own.getAbsolutePath();
        }
        return ctx.getFilesDir().getAbsolutePath();
    }

    /** 保存日志用：把一段文本追加到文件。 */
    public static void appendLog(File f, String text) {
        try {
            FileOutputStream o = new FileOutputStream(f, true);
            o.write((text + "\n").getBytes("UTF-8"));
            o.close();
        } catch (IOException ignored) {
        }
    }
}