package com.lvshu.build;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.OpenableColumns;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** 酸败自助补丁工具：直装 + 补丁，一体版主界面。 */
public class MainActivity extends Activity implements Host {

    private static final int REQ_STORAGE = 1001;

    private Ui ui;
    private Engine engine;
    private FrameLayout content;
    private String current = "home";
    private Pane currentPane;

    private final android.os.Handler resumeHandler =
            new android.os.Handler(android.os.Looper.getMainLooper());
    private final Runnable recheckPermissions = new Runnable() {
        @Override
        public void run() {
            if (currentPane != null) currentPane.onHostResume();
        }
    };

    private final Map<String, View> panes = new HashMap<String, View>();
    private final List<TextView> navIcons = new ArrayList<TextView>();
    private final List<TextView> navLabels = new ArrayList<TextView>();

    private final StringBuilder logBuf = new StringBuilder();
    private TextView logView;

    // 纯本地离线版：无在线补丁首页，四个功能页全部为本地功能。
    private static final String[] IDS = {"direct", "patch", "files", "settings"};
    private static final String[] ICONS = {"\u2699", "\u2726", "\u2637", "\u2692"};
    private static final String[] LABELS = {"直装", "补丁", "文件", "设置"};

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        DiagLog.mark("MainActivity.onCreate 开始");
        super.onCreate(savedInstanceState);
        DiagLog.mark("super.onCreate 完成");
        ui = new Ui(this);
        DiagLog.mark("new Ui 完成");
        engine = new Engine(this);
        DiagLog.mark("new Engine 完成");
        // 恢复上次在文件页设定的工作目录（渠道服），保证补丁始终装到用户选的目录。
        Engine.restoreWorkRoot(this);
        // 确保日志/补丁目录结构存在（跟随自定义根路径）。
        Paths.ensureDirs(this);
        String bootLine = "==== 启动 " + new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.ROOT).format(new Date())
                + " v" + versionName() + " ====";
        appendFile(bootLine);
        DiagLog.run("打开工具 " + bootLine.replace("=", "").trim());
        DiagLog.mark("buildRoot 开始");
        setContentView(buildRoot());
        DiagLog.mark("setContentView 完成");
        show("direct");
        DiagLog.mark("show(direct) 完成");
        askStorage();
        DiagLog.mark("askStorage 完成");
        runDiag();
        DiagLog.mark("runDiag 已启动, onCreate 结束（纯本地离线版：无统计/无联网）");
        DiagLog.mark("诊断日志路径: " + DiagLog.crashReportPath());
    }

    @Override
    protected void onResume() {
        super.onResume();
        // 从系统设置页授权后返回会走这里：立即刷新一次，再延迟复查一次（部分机型首次读取仍旧值）。
        if (currentPane != null) currentPane.onHostResume();
        resumeHandler.removeCallbacks(recheckPermissions);
        resumeHandler.postDelayed(recheckPermissions, 200L);
    }

    @Override
    protected void onPause() {
        resumeHandler.removeCallbacks(recheckPermissions);
        super.onPause();
    }

    private void askStorage() {
        if (Build.VERSION.SDK_INT >= 30) {
            return;
        }
        if (checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{
                    Manifest.permission.READ_EXTERNAL_STORAGE,
                    Manifest.permission.WRITE_EXTERNAL_STORAGE}, REQ_STORAGE);
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQ_STORAGE && grantResults.length > 0
                && grantResults[0] != PackageManager.PERMISSION_GRANTED) {
            toast("没有存储权限，补丁和直装工作会受影响");
        }
    }

    // ---------------- 骨架 ----------------

    private View buildRoot() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Ui.BG);

        content = new FrameLayout(this);
        root.addView(content, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        LinearLayout navBar = new LinearLayout(this);
        navBar.setOrientation(LinearLayout.HORIZONTAL);
        navBar.setBackgroundColor(0xFFFFFFFF);
        navBar.setPadding(ui.dp(4), ui.dp(6), ui.dp(4), ui.dp(8));
        for (int i = 0; i < IDS.length; i++) {
            final String id = IDS[i];
            LinearLayout item = new LinearLayout(this);
            item.setOrientation(LinearLayout.VERTICAL);
            item.setGravity(Gravity.CENTER);
            item.setPadding(0, ui.dp(2), 0, ui.dp(2));
            item.setClickable(true);
            TextView ic = new TextView(this);
            ic.setText(ICONS[i]);
            ic.setTextSize(18);
            ic.setGravity(Gravity.CENTER);
            TextView lb = new TextView(this);
            lb.setText(LABELS[i]);
            lb.setTextSize(10);
            lb.setGravity(Gravity.CENTER);
            item.addView(ic);
            item.addView(lb);
            item.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    show(id);
                }
            });
            navBar.addView(item, new LinearLayout.LayoutParams(0,
                    ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
            navIcons.add(ic);
            navLabels.add(lb);
        }
        root.addView(navBar, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        return root;
    }

    private View paneFor(String id) {
        View v = panes.get(id);
        if (v != null) {
            return v;
        }
        Pane p;
        if ("direct".equals(id)) {
            p = new DirectPane(this);
        } else if ("patch".equals(id)) {
            p = new PatchPane(this);
        } else if ("files".equals(id)) {
            p = new FilesPane(this);
        } else {
            p = new SettingsPane(this);
        }
        v = p.view();
        v.setTag(p);
        panes.put(id, v);
        return v;
    }

    private void show(String id) {
        if ("log".equals(id)) {
            showLog();
            return;
        }
        current = id;
        View v = paneFor(id);
        content.removeAllViews();
        content.addView(v, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        for (int i = 0; i < IDS.length; i++) {
            boolean on = IDS[i].equals(id);
            navIcons.get(i).setTextColor(on ? Ui.ACCENT : Ui.DIM);
            navLabels.get(i).setTextColor(on ? Ui.ACCENT : Ui.SUB);
            navLabels.get(i).setTypeface(on ? Typeface.DEFAULT_BOLD : Typeface.DEFAULT);
        }
        Object tag = v.getTag();
        if (tag instanceof Pane) {
            currentPane = (Pane) tag;
            currentPane.onShow();
        }
    }

    /** 供面板之间跳转。 */
    public void go(String id) {
        show(id);
    }

    // ---------------- Host ----------------

    @Override
    public Ui ui() {
        return ui;
    }

    @Override
    public Activity act() {
        return this;
    }

    @Override
    public Engine engine() {
        return engine;
    }

    @Override
    public void log(String s) {
        String line = new SimpleDateFormat("HH:mm:ss", Locale.ROOT).format(new Date()) + "  " + s;
        appendFile(line);
        logBuf.append(line).append('\n');
        if (logBuf.length() > 40000) {
            logBuf.delete(0, logBuf.length() - 40000);
        }
        final String text = logBuf.toString();
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                if (logView != null) {
                    logView.setText(text);
                }
            }
        });
    }

    @Override
    public void toast(final String s) {
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                Toast.makeText(MainActivity.this, s, Toast.LENGTH_SHORT).show();
            }
        });
    }

    @Override
    public String gamePkg() {
        return getSharedPreferences("cfg", 0).getString("game_pkg", Engine.DEFAULT_GAME);
    }

    @Override
    public void setGamePkg(String pkg) {
        // 切换目标客户端时清掉文件页设置的工作目录覆盖，恢复按包名计算。
        Engine.clearWorkFilesRoot();
        getSharedPreferences("cfg", 0).edit().putString("game_pkg", pkg).apply();
        log("目标游戏改为 " + pkg);
        View v = panes.get("patch");
        if (v != null && v.getTag() instanceof Pane) {
            currentPane = (Pane) v.getTag();
            currentPane.onShow();
        }
    }

    // ---------------- 选文件 ----------------

    private static final int REQ_PICK = 2001;
    private Host.FilePick pickCb;

    @Override
    public void pickFile(final Host.FilePick cb) {
        pickCb = cb;
        Intent i = new Intent(Intent.ACTION_GET_CONTENT);
        i.setType("*/*");
        i.addCategory(Intent.CATEGORY_OPENABLE);
        try {
            startActivityForResult(Intent.createChooser(i, "选择文件"), REQ_PICK);
        } catch (Throwable e) {
            toast("打不开文件选择器：" + e);
        }
    }

    @Override
    protected void onActivityResult(int req, int res, Intent data) {
        super.onActivityResult(req, res, data);
        if (req != REQ_PICK || res != RESULT_OK || data == null || data.getData() == null) {
            return;
        }
        final Host.FilePick cb = pickCb;
        pickCb = null;
        if (cb == null) {
            return;
        }
        final Uri uri = data.getData();
        final String name = displayName(uri);
        toast("读取 " + name + " …");
        new Thread(new Runnable() {
            @Override
            public void run() {
                final File f = copyUri(uri, name);
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        if (f == null) {
                            toast("读取文件失败");
                            return;
                        }
                        cb.onPicked(f, name);
                    }
                });
            }
        }, "pick").start();
    }

    private String displayName(Uri uri) {
        String name = null;
        try {
            Cursor c = getContentResolver().query(uri, null, null, null, null);
            if (c != null) {
                int idx = c.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                if (c.moveToFirst() && idx >= 0) {
                    name = c.getString(idx);
                }
                c.close();
            }
        } catch (Throwable ignored) {
        }
        if (name == null || name.length() == 0) {
            String p = uri.getLastPathSegment();
            name = p == null ? ("pick_" + Engine.stamp() + ".bin") : p;
        }
        return name;
    }

    private File copyUri(Uri uri, String name) {
        try {
            File out = new File(engine.workDir(),
                    "pick_" + Engine.stamp() + "_" + name.replaceAll("[/\\\\]", "_"));
            InputStream in = getContentResolver().openInputStream(uri);
            if (in == null) {
                return null;
            }
            FileOutputStream fo = new FileOutputStream(out);
            byte[] buf = new byte[81920];
            int n;
            long total = 0;
            while ((n = in.read(buf)) > 0) {
                fo.write(buf, 0, n);
                total += n;
            }
            fo.close();
            in.close();
            return total > 0 ? out : null;
        } catch (Throwable e) {
            log("复制文件失败：" + e);
            return null;
        }
    }

    // ---------------- 启动自检 ----------------

    /** 把 uid / 目录可读性写进日志，方便远端排查。 */
    private void runDiag() {
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    String mine = Engine.selfSharedUserId(MainActivity.this);
                    log("自检：本 App uid=" + android.os.Process.myUid()
                            + " sharedUserId=" + mine + " 目标=" + gamePkg()
                            + " 目标uid=" + Engine.uidOf(MainActivity.this, gamePkg()));
                    String[] targets = Api.listTargets(MainActivity.this);
                    for (String t : targets) {
                        String pkg = t.split("\t")[0];
                        File d = new File(Engine.gameFiles(pkg));
                        log("自检：发现 " + pkg + " uid=" + Engine.uidOf(MainActivity.this, pkg)
                                + " 目录存在=" + d.exists() + " 可列=" + Engine.canList(d));
                    }
                    File cur = new File(Engine.gameFiles(gamePkg()));
                    if (!Engine.canList(cur)) {
                        for (String t : targets) {
                            String pkg = t.split("\t")[0];
                            if (Engine.canList(new File(Engine.gameFiles(pkg)))) {
                                log("自检：目标目录不可读，自动切到 " + pkg);
                                setGamePkg(pkg);
                                break;
                            }
                        }
                    }
                } catch (Throwable e) {
                    log("自检异常：" + e);
                }
            }
        }, "diag").start();
    }

    // ---------------- 落盘诊断 ----------------

    private final Object fileLock = new Object();
    private int fileWrites;

    private java.io.File logFile() {
        // 运行日志：优先公共目录 运行日志/run.log，回退应用私有目录
        try {
            java.io.File runDir = new java.io.File(Paths.runLogDir(this));
            if (runDir.isDirectory() || runDir.mkdirs()) {
                return new java.io.File(runDir, "run.log");
            }
        } catch (Throwable ignored) {
        }
        java.io.File dir = getExternalFilesDir(null);
        if (dir == null) {
            dir = getFilesDir();
        }
        return new java.io.File(dir, "run.log");
    }

    private void appendFile(String line) {
        synchronized (fileLock) {
            try {
                java.io.File f = logFile();
                if (fileWrites > 200 && f.length() > 512 * 1024) {
                    f.delete();
                    fileWrites = 0;
                }
                java.io.FileOutputStream o = new java.io.FileOutputStream(f, true);
                try {
                    o.write((line + "\n").getBytes("UTF-8"));
                } finally {
                    o.close();
                }
                fileWrites++;
            } catch (Throwable ignored) {
            }
        }
    }

    // 崩溃捕获已统一由 DiagApp.attachBaseContext 安装的 DiagLog 全局处理器负责,
    // 它比 Activity 更早生效,且写到公共目录,此处不再重复安装以免覆盖。

    private String versionName() {
        try {
            return getPackageManager().getPackageInfo(getPackageName(), 0).versionName;
        } catch (Throwable e) {
            return "?";
        }
    }

    // ---------------- 日志页 ----------------

    public void showLog() {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setBackgroundColor(Ui.BG);

        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setPadding(ui.dp(12), ui.dp(10), ui.dp(12), ui.dp(10));
        bar.setBackgroundColor(0xFFFFFFFF);
        TextView title = new TextView(this);
        title.setText("运行日志");
        title.setTextSize(16);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        bar.addView(title, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        TextView back = new TextView(this);
        back.setText("返回");
        back.setTextColor(Ui.ACCENT);
        back.setPadding(ui.dp(12), ui.dp(4), ui.dp(4), ui.dp(4));
        back.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                show(current);
            }
        });
        bar.addView(back);
        box.addView(bar);

        ScrollView sc = new ScrollView(this);
        logView = ui.mono(logBuf.toString());
        logView.setTextIsSelectable(true);
        logView.setPadding(ui.dp(12), ui.dp(10), ui.dp(12), ui.dp(20));
        sc.addView(logView);
        box.addView(sc, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        LinearLayout bottom = new LinearLayout(this);
        bottom.setPadding(ui.dp(12), ui.dp(8), ui.dp(12), ui.dp(14));
        bottom.setBackgroundColor(0xFFFFFFFF);
        android.widget.Button clear = ui.ghost("清空", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                logBuf.setLength(0);
                if (logView != null) {
                    logView.setText("");
                }
            }
        });
        bottom.addView(clear, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ui.dp(42)));
        box.addView(bottom);

        content.removeAllViews();
        content.addView(box, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
    }
}