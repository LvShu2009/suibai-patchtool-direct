package com.lvshu.build;

import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageInstaller;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.provider.DocumentsContract;
import android.provider.Settings;
import android.text.TextUtils;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;

/** 直装页：把已安装客户端补 sharedUserId 并重签成直装包。 */
public class DirectPane implements Pane {

    private final Host host;
    private final Ui ui;
    private final SharedPreferences prefs;
    private final Handler handler = new Handler(Looper.getMainLooper());

    private final RadioGroup group;
    private final List<String> pkgs = new ArrayList<String>();
    private final List<String> paths = new ArrayList<String>();
    private final ProgressBar bar;
    private final TextView status;
    private final TextView log;
    private final Button buildBtn;
    private final Button installBtn;
    private final TextView permState;
    private final TextView dirState;
    private final TextView installPerm;
    private final View root;

    private String lastApk;
    private String pendingInstall;

    public DirectPane(Host host) {
        this.host = host;
        this.ui = host.ui();
        this.prefs = host.act().getSharedPreferences("cfg", 0);

        ScrollView scroll = ui.scroller();
        LinearLayout box = ui.column();
        scroll.addView(box);

        box.addView(ui.title("制作直装"));
        TextView sub = ui.sub("给已安装的官服 / 渠道服客户端补上共享 UID（lvshu.direct300），"
                + "用内置密钥重签，产出一份可直接安装的直装包。装直装前需要先卸载原客户端。");
        sub.setPadding(0, ui.dp(6), 0, ui.dp(14));
        box.addView(sub);

        box.addView(ui.section("内置签名指纹"));
        TextView fp = ui.mono(Api.certFingerprint(host.act()));
        box.addView(fp);

        permState = ui.label("");
        permState.setPadding(0, ui.dp(10), 0, ui.dp(2));
        box.addView(permState);

        Button permBtn = ui.ghost("① 授予「所有文件访问权限」", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                openAllFilesSettings();
            }
        });
        box.addView(permBtn, ui.mtop(ui.dp(42)));

        dirState = ui.mono("");
        dirState.setPadding(0, ui.dp(10), 0, ui.dp(2));
        box.addView(dirState);

        Button dirBtn = ui.ghost("② 修改输出目录", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                editOutDir();
            }
        });
        box.addView(dirBtn, ui.mtop(ui.dp(42)));

        installPerm = ui.label("");
        installPerm.setPadding(0, ui.dp(8), 0, ui.dp(2));
        box.addView(installPerm);

        Button ipBtn = ui.ghost("③ 允许「安装未知应用」", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                openInstallPermission();
            }
        });
        box.addView(ipBtn, ui.mtop(ui.dp(42)));

        box.addView(ui.section("检测到的客户端"));
        group = new RadioGroup(host.act());
        group.setOrientation(RadioGroup.VERTICAL);
        box.addView(group);

        buildBtn = ui.primary("开始制作直装", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                startBuild();
            }
        });
        box.addView(buildBtn, ui.mtop(ui.dp(48)));

        bar = new ProgressBar(host.act(), null, android.R.attr.progressBarStyleHorizontal);
        bar.setMax(100);
        bar.setVisibility(View.GONE);
        LinearLayout.LayoutParams bp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ui.dp(6));
        bp.topMargin = ui.dp(12);
        box.addView(bar, bp);

        status = ui.label("");
        status.setPadding(0, ui.dp(10), 0, ui.dp(4));
        box.addView(status);

        log = ui.mono("");
        box.addView(log);

        installBtn = ui.primary("安装这份直装", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                install(lastApk);
            }
        });
        installBtn.setVisibility(View.GONE);
        box.addView(installBtn, ui.mtop(ui.dp(48)));

        Button openDir = ui.ghost("打开输出目录", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                showOutDirMenu();
            }
        });
        box.addView(openDir, ui.mtop(ui.dp(42)));

        Button uninstall = ui.ghost("卸载已安装的原客户端", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                int id = group.getCheckedRadioButtonId() - 1000;
                if (id >= 0 && id < pkgs.size()) {
                    uninstallMenu(pkgs.get(id));
                }
            }
        });
        box.addView(uninstall, ui.mtop(ui.dp(42)));

        Button fromFile = ui.ghost("④ 从本地 APK 制作（官服原包也行）", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                host.pickFile(new Host.FilePick() {
                    @Override
                    public void onPicked(File f, String name) {
                        startBuildFromFile(f);
                    }
                });
            }
        });
        box.addView(fromFile, ui.mtop(ui.dp(42)));

        root = scroll;
        reload();
        updatePermUi();
    }

    @Override
    public View view() {
        return root;
    }

    @Override
    public void onShow() {
        onHostResume();
    }

    @Override
    public void onHostResume() {
        updatePermUi();
        boolean canInstall = Build.VERSION.SDK_INT < 26 || host.act().getPackageManager().canRequestPackageInstalls();
        installPerm.setText(canInstall ? "安装权限：已允许"
                : "安装权限：未允许（点下面按钮开启，否则点安装没反应）");
        installPerm.setTextColor(canInstall ? Ui.GREEN : Ui.RED);
        if (canInstall && pendingInstall != null) {
            String p = pendingInstall;
            pendingInstall = null;
            install(p);
        }
        dirState.setText("输出目录：" + outDir());
    }

    private void updatePermUi() {
        boolean ok = Api.hasAllFiles(host.act());
        permState.setText(ok ? "文件访问权限：已授予"
                : "文件访问权限：未授予（不授予就写不进自定义目录）");
        permState.setTextColor(ok ? Ui.GREEN : Ui.RED);
    }

    private void openAllFilesSettings() {
        if (Build.VERSION.SDK_INT < 30) {
            Toast.makeText(host.act(), "当前系统不需要该权限", Toast.LENGTH_SHORT).show();
            return;
        }
        try {
            host.act().startActivity(new Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                    Uri.parse("package:" + host.act().getPackageName())));
        } catch (Throwable e) {
            host.act().startActivity(new Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION));
        }
    }

    private String outDir() {
        return prefs.getString("out_dir", Api.defaultOutDir());
    }

    private void editOutDir() {
        final EditText input = new EditText(host.act());
        input.setText(outDir());
        input.setTextSize(13);
        input.setSingleLine(false);
        new AlertDialog.Builder(host.act())
                .setTitle("输出目录")
                .setMessage("直装包会写到这里，例如 /storage/emulated/0/LvShu/300社区/重构")
                .setView(input)
                .setPositiveButton("保存", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int which) {
                        String v = input.getText().toString().trim();
                        if (v.length() == 0) {
                            return;
                        }
                        prefs.edit().putString("out_dir", v).apply();
                        dirState.setText("输出目录：" + v);
                    }
                })
                .setNeutralButton("恢复默认", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int which) {
                        prefs.edit().remove("out_dir").apply();
                        dirState.setText("输出目录：" + Api.defaultOutDir());
                    }
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private void reload() {
        group.removeAllViews();
        pkgs.clear();
        paths.clear();
        String[] targets = Api.listTargets(host.act());
        if (targets.length == 0) {
            TextView t = ui.label("没有发现 300 大作战客户端，先安装官方包再来。");
            t.setTextColor(Ui.RED);
            group.addView(t);
            buildBtn.setEnabled(false);
            return;
        }
        buildBtn.setEnabled(true);
        for (int i = 0; i < targets.length; i++) {
            String[] f = targets[i].split("\t");
            RadioButton rb = new RadioButton(host.act());
            rb.setId(1000 + i);
            rb.setText(f[1] + "  " + f[2] + "\n" + f[0]);
            rb.setTextSize(13);
            rb.setPadding(0, ui.dp(4), 0, ui.dp(4));
            group.addView(rb);
            pkgs.add(f[0]);
            paths.add(f[4]);
            if (i == 0) {
                rb.setChecked(true);
            }
        }
    }

    private void startBuild() {
        int id = group.getCheckedRadioButtonId() - 1000;
        if (id < 0 || id >= pkgs.size()) {
            Toast.makeText(host.act(), "先选一个客户端", Toast.LENGTH_SHORT).show();
            return;
        }
        buildBtn.setEnabled(false);
        installBtn.setVisibility(View.GONE);
        bar.setVisibility(View.VISIBLE);
        bar.setProgress(0);
        log.setText("");
        lastApk = null;
        host.log("开始直装：" + pkgs.get(id));
        Api.startBuild(host.act(), pkgs.get(id), Api.resolveOutDir(host.act(), outDir()), false);
        poll();
    }

    private void startBuildFromFile(File f) {
        buildBtn.setEnabled(false);
        installBtn.setVisibility(View.GONE);
        bar.setVisibility(View.VISIBLE);
        bar.setProgress(0);
        log.setText("");
        lastApk = null;
        host.log("从文件制作直装：" + f.getName() + "（" + (f.length() / 1048576) + " MB）");
        Api.startBuildFromFile(host.act(), f, Api.resolveOutDir(host.act(), outDir()), false);
        poll();
    }

    private void poll() {
        handler.postDelayed(new Runnable() {
            @Override
            public void run() {
                bar.setProgress(Api.progress());
                status.setText(Api.status());
                if (Api.isRunning()) {
                    handler.postDelayed(this, 400);
                    return;
                }
                buildBtn.setEnabled(true);
                String result = Api.result();
                if (result != null && result.startsWith("ERR:")) {
                    log.append(result + "\n");
                    host.log("直装失败：" + result);
                } else if (!TextUtils.isEmpty(result)) {
                    lastApk = result;
                    log.append("输出：" + result + "\n");
                    host.log("直装已生成：" + result);
                    // 把目标包名绑定到这次做出来的直装包的真实包名，
                    // 否则文件页/补丁页仍盯着默认的 com.jumpw.mobile300，
                    // 用渠道服本地包制作时就会"装好了却读不到目录"。
                    try {
                        android.content.pm.PackageInfo pi = host.act().getPackageManager()
                                .getPackageArchiveInfo(result, 0);
                        if (pi != null && pi.packageName != null && pi.packageName.length() > 0) {
                            Engine.clearWorkFilesRoot();
                            host.setGamePkg(pi.packageName);
                            log.append("目标包名已设为：" + pi.packageName + "\n");
                            host.log("目标包名已绑定：" + pi.packageName);
                        }
                    } catch (Throwable e) {
                        host.log("解析包名失败：" + e.getMessage());
                    }
                    installBtn.setVisibility(View.VISIBLE);
                }
            }
        }, 400);
    }

    private void install(String path) {
        if (path == null) {
            return;
        }
        File apk = new File(path);
        if (!apk.isFile()) {
            Toast.makeText(host.act(), "文件不存在", Toast.LENGTH_SHORT).show();
            return;
        }
        if (Build.VERSION.SDK_INT >= 26 && !host.act().getPackageManager().canRequestPackageInstalls()) {
            pendingInstall = path;
            Toast.makeText(host.act(), "先允许本应用「安装未知应用」", Toast.LENGTH_LONG).show();
            openInstallPermission();
            return;
        }
        pendingInstall = null;
        try {
            Intent i = new Intent(Intent.ACTION_VIEW);
            i.setDataAndType(ShareProvider.uriFor(apk), "application/vnd.android.package-archive");
            i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_ACTIVITY_NEW_TASK);
            host.act().startActivity(i);
            host.log("已交给系统安装器：" + apk.getName());
            return;
        } catch (Throwable e) {
            host.log("系统安装器打不开，改用 PackageInstaller：" + e);
        }
        installBySession(apk);
    }

    private void openInstallPermission() {
        try {
            host.act().startActivity(new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                    Uri.parse("package:" + host.act().getPackageName())));
        } catch (Throwable e) {
            try {
                host.act().startActivity(new Intent(Settings.ACTION_SECURITY_SETTINGS));
            } catch (Throwable ignored) {
            }
        }
    }

    private void installBySession(File apk) {
        try {
            PackageInstaller pi = host.act().getPackageManager().getPackageInstaller();
            PackageInstaller.SessionParams params =
                    new PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL);
            int session = pi.createSession(params);
            PackageInstaller.Session s = pi.openSession(session);
            InputStream in = new FileInputStream(apk);
            OutputStream out = s.openWrite("base.apk", 0, apk.length());
            byte[] buf = new byte[1 << 16];
            int n;
            while ((n = in.read(buf)) > 0) {
                out.write(buf, 0, n);
            }
            out.flush();
            s.fsync(out);
            out.close();
            in.close();
            Intent cb = new Intent(host.act().getPackageName() + ".INSTALL_RESULT");
            // Android 12L+/15 要求 PackageInstaller 的结果回调用可变 PendingIntent，
            // 否则 commit() 抛 IllegalArgumentException（本设备 OnePlus Android 15 命中）。
            int piFlags = android.app.PendingIntent.FLAG_UPDATE_CURRENT;
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
                piFlags |= android.app.PendingIntent.FLAG_MUTABLE;
            } else {
                piFlags |= android.app.PendingIntent.FLAG_IMMUTABLE;
            }
            android.app.PendingIntent piIntent = android.app.PendingIntent.getBroadcast(host.act(), session, cb, piFlags);
            s.commit(piIntent.getIntentSender());
            s.close();
            host.log("已提交安装：" + apk.getName());
        } catch (Throwable e) {
            host.log("安装失败：" + e);
            Toast.makeText(host.act(), "安装失败：" + e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    private void uninstallMenu(final String pkg) {
        // 本 App 无直接卸载权限，直接卸载弹窗多数机型不生效；
        // 只保留「打开应用详情页，手动卸载」，最稳妥。
        openAppDetails(pkg);
    }

    private void openAppDetails(String pkg) {
        try {
            Intent i = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + pkg));
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            host.act().startActivity(i);
        } catch (Throwable e) {
            Toast.makeText(host.act(), "打不开应用详情页：" + e, Toast.LENGTH_LONG).show();
        }
    }

    // ---------------- 输出目录 ----------------

    private void showOutDirMenu() {
        final String dir = Api.resolveOutDir(host.act(), outDir());
        String[] items = {"用系统文件管理器打开", "在应用内查看文件", "复制路径"};
        new AlertDialog.Builder(host.act())
                .setTitle(dir)
                .setItems(items, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int which) {
                        if (which == 0) {
                            openWithSystem(dir);
                        } else if (which == 1) {
                            showFiles(dir);
                        } else {
                            copyPath(dir);
                        }
                    }
                })
                .show();
    }

    private void openWithSystem(String dir) {
        String prefix = "/storage/emulated/0/";
        try {
            if (!dir.startsWith(prefix)) {
                throw new IllegalArgumentException("非主存储");
            }
            Uri uri = DocumentsContract.buildDocumentUri("com.android.externalstorage.documents",
                    "primary:" + dir.substring(prefix.length()));
            Intent i = new Intent(Intent.ACTION_VIEW);
            i.setDataAndType(uri, DocumentsContract.Document.MIME_TYPE_DIR);
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            host.act().startActivity(i);
        } catch (Throwable e) {
            Toast.makeText(host.act(), "系统文件管理器打不开，改用应用内列表", Toast.LENGTH_SHORT).show();
            showFiles(dir);
        }
    }

    private void copyPath(String dir) {
        try {
            ClipboardManager cm = (ClipboardManager) host.act().getSystemService(android.content.Context.CLIPBOARD_SERVICE);
            cm.setPrimaryClip(ClipData.newPlainText("path", dir));
            Toast.makeText(host.act(), "路径已复制", Toast.LENGTH_SHORT).show();
        } catch (Throwable e) {
            Toast.makeText(host.act(), dir, Toast.LENGTH_LONG).show();
        }
    }

    private void showFiles(String dir) {
        File d = new File(dir);
        ScrollView scroll = new ScrollView(host.act());
        LinearLayout box = new LinearLayout(host.act());
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(ui.dp(8), ui.dp(6), ui.dp(8), ui.dp(6));
        scroll.addView(box);

        File[] files = d.listFiles();
        if (files == null || files.length == 0) {
            TextView empty = ui.label(d.isDirectory() ? "（目录是空的）" : "目录不存在：" + dir);
            empty.setPadding(ui.dp(10), ui.dp(12), ui.dp(10), ui.dp(12));
            box.addView(empty);
        } else {
            java.util.Arrays.sort(files, new java.util.Comparator<File>() {
                @Override
                public int compare(File a, File b) {
                    return a.getName().compareToIgnoreCase(b.getName());
                }
            });
            for (final File f : files) {
                TextView row = new TextView(host.act());
                row.setText(f.getName() + "\n" + (f.length() / 1024) + " KB");
                row.setTextSize(13);
                row.setPadding(ui.dp(10), ui.dp(10), ui.dp(10), ui.dp(10));
                row.setOnClickListener(new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        fileMenu(f);
                    }
                });
                box.addView(row);
            }
        }

        LinearLayout wrap = new LinearLayout(host.act());
        wrap.setOrientation(LinearLayout.VERTICAL);
        wrap.addView(scroll, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        Button copy = ui.ghost("复制这个目录的路径", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                copyPath(Api.resolveOutDir(host.act(), outDir()));
            }
        });
        wrap.addView(copy);

        new AlertDialog.Builder(host.act())
                .setTitle("输出目录")
                .setView(wrap)
                .setNegativeButton("关闭", null)
                .show();
    }

    private void fileMenu(final File f) {
        final boolean apk = f.getName().toLowerCase().endsWith(".apk");
        String[] items = apk
                ? new String[]{"安装", "用其他应用打开", "复制路径"}
                : new String[]{"用其他应用打开", "复制路径"};
        new AlertDialog.Builder(host.act())
                .setTitle(f.getName())
                .setItems(items, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int which) {
                        if (apk && which == 0) {
                            install(f.getAbsolutePath());
                        } else if ((apk && which == 1) || (!apk && which == 0)) {
                            openWith(f);
                        } else {
                            copyPath(f.getAbsolutePath());
                        }
                    }
                })
                .show();
    }

    private void openWith(File f) {
        try {
            boolean apk = f.getName().toLowerCase().endsWith(".apk");
            Intent i = new Intent(Intent.ACTION_VIEW);
            i.setDataAndType(ShareProvider.uriFor(f), apk ? "application/vnd.android.package-archive" : "*/*");
            i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_ACTIVITY_NEW_TASK);
            host.act().startActivity(Intent.createChooser(i, "用其他应用打开"));
        } catch (Throwable e) {
            Toast.makeText(host.act(), "没有可用的应用：" + e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }
}