package com.lvshu.build;

import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.DialogInterface;
import android.content.Intent;
import android.net.Uri;
import android.provider.DocumentsContract;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.method.LinkMovementMethod;
import android.text.style.ClickableSpan;
import android.view.View;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.io.File;

/** 设置页：目标游戏(工作目录)、补丁目录、记录(日志)、关于。全项可点击。 */
public class SettingsPane implements Pane {

    private final Host host;
    private final Ui ui;
    private final ScrollView root;
    private final LinearLayout box;

    // 每行副标题，操作后即时刷新
    private TextView gameSub;
    private TextView patchSub;
    private TextView recordSub;

    public SettingsPane(Host host) {
        this.host = host;
        this.ui = host.ui();

        root = ui.scroller();
        box = ui.column();
        root.addView(box);

        box.addView(ui.title("设置"));

        // ---------- 目标游戏（工作目录）----------
        box.addView(ui.section("目标游戏 / 工作目录"));
        LinearLayout c1 = ui.card();
        gameSub = new TextView(host.act());
        c1.addView(twoLine("\u25B8", "当前工作目录", gameSubText(), gameSub, new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                gameMenu();
            }
        }));
        box.addView(c1, ui.lp(android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                android.view.ViewGroup.LayoutParams.WRAP_CONTENT));

        // ---------- 补丁目录 ----------
        box.addView(ui.section("补丁目录"));
        LinearLayout c2 = ui.card();
        patchSub = new TextView(host.act());
        c2.addView(twoLine("\u25A1", "补丁存放位置（zip）", Paths.patchDir(host.act()), patchSub,
                new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        patchMenu();
                    }
                }));
        c2.addView(ui.divider());
        c2.addView(ui.row("\u2726", "新建示例补丁目录", "生成 <补丁名>/files 结构", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                makeSample();
            }
        }));
        box.addView(c2, ui.lp(android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                android.view.ViewGroup.LayoutParams.WRAP_CONTENT));

        // ---------- 记录（日志）----------
        box.addView(ui.section("记录 / 日志"));
        LinearLayout c3 = ui.card();
        recordSub = new TextView(host.act());
        c3.addView(twoLine("\u21BA", "日志目录", Paths.logRoot(host.act()), recordSub,
                new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        recordMenu();
                    }
                }));
        c3.addView(ui.divider());
        c3.addView(ui.row("\u2263", "查看运行日志", "安装 / 直装过程记录", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (host.act() instanceof MainActivity) {
                    ((MainActivity) host.act()).showLog();
                } else {
                    host.toast("当前页面不支持");
                }
            }
        }));
        box.addView(c3, ui.lp(android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                android.view.ViewGroup.LayoutParams.WRAP_CONTENT));

        // ---------- 直装状态诊断 ----------
        box.addView(ui.section("直装状态诊断"));
        LinearLayout cdiag = ui.card();
        cdiag.addView(ui.row("\u26A1", "检查直装状态", "读取 UID / 共享UID / 签名，可复制发给作者", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                showDiag();
            }
        }));
        box.addView(cdiag, ui.lp(android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                android.view.ViewGroup.LayoutParams.WRAP_CONTENT));

        // ---------- 关于 ----------
        box.addView(ui.section("关于"));
        LinearLayout c4 = ui.card();
        c4.addView(ui.row("\u2139", "关于 酸败自助补丁工具", "版本与致谢", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                showAbout();
            }
        }));
        box.addView(c4, ui.lp(android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                android.view.ViewGroup.LayoutParams.WRAP_CONTENT));

        box.addView(ui.section(""));
        TextView tip = ui.label(Paths.describe(host.act()));
        tip.setTextSize(11);
        box.addView(tip);
    }

    /** 两行卡片行：图标 + 标题 + 副标题(外部 TextView 引用，便于刷新)。 */
    private View twoLine(String icon, String title, String sub, TextView subView,
                         View.OnClickListener l) {
        LinearLayout r = new LinearLayout(host.act());
        r.setOrientation(LinearLayout.HORIZONTAL);
        r.setGravity(android.view.Gravity.CENTER_VERTICAL);
        r.setPadding(ui.dp(4), ui.dp(11), ui.dp(6), ui.dp(11));
        r.setClickable(true);
        if (l != null) {
            r.setOnClickListener(l);
        }
        if (icon != null && icon.length() > 0) {
            TextView ic = new TextView(host.act());
            ic.setText(icon);
            ic.setTextSize(16);
            ic.setWidth(ui.dp(30));
            r.addView(ic);
        }
        LinearLayout col = new LinearLayout(host.act());
        col.setOrientation(LinearLayout.VERTICAL);
        TextView t = new TextView(host.act());
        t.setText(title);
        t.setTextSize(14);
        t.setTextColor(Ui.TXT);
        col.addView(t);
        subView.setTextSize(11);
        subView.setTextColor(Ui.SUB);
        subView.setText(sub);
        col.addView(subView);
        r.addView(col);
        return r;
    }

    /** 单行卡片行(仅目标游戏用，副标题自带引用)。 */
    private View twoLine(String icon, String title, String sub, View.OnClickListener l) {
        TextView dummy = new TextView(host.act());
        return twoLine(icon, title, sub, dummy, l);
    }

    private void refreshSubs() {
        if (gameSub != null) {
            gameSub.setText(gameSubText());
        }
        if (patchSub != null) {
            patchSub.setText(Paths.patchDir(host.act()));
        }
        if (recordSub != null) {
            recordSub.setText(Paths.logRoot(host.act()));
        }
    }

    private String gameSubText() {
        if (Engine.hasWorkFilesRoot()) {
            return Engine.gameFiles(host.gamePkg()) + "（已指定）";
        }
        return host.gamePkg() + " · 按包名自动";
    }

    @Override
    public View view() {
        return root;
    }

    @Override
    public void onShow() {
        refreshSubs();
    }

    // ---------------- 目标游戏 / 工作目录 ----------------

    private void gameMenu() {
        final String[] items = {"切换目标客户端", "手动指定工作目录", "清除工作目录（按包名自动）"};
        new AlertDialog.Builder(host.act())
                .setTitle("目标游戏 / 工作目录\n" + gameSubText())
                .setItems(items, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int which) {
                        if (which == 0) {
                            pickGame();
                        } else if (which == 1) {
                            editWorkRoot();
                        } else {
                            Engine.clearWorkFilesRoot();
                            host.toast("已恢复按包名自动");
                            refreshSubs();
                        }
                    }
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private void pickGame() {
        final String[] targets = Api.listTargets(host.act());
        if (targets.length == 0) {
            host.toast("没检测到 300 客户端，先装官方包");
            return;
        }
        String[] labels = new String[targets.length];
        for (int i = 0; i < targets.length; i++) {
            String[] f = targets[i].split("\t", -1);
            labels[i] = (f.length > 1 ? f[1] : f[0]);
        }
        new AlertDialog.Builder(host.act())
                .setTitle("选择目标客户端")
                .setItems(labels, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int which) {
                        String[] f = targets[which].split("\t", -1);
                        host.setGamePkg(f[0]);
                        refreshSubs();
                    }
                })
                .show();
    }

    private void editWorkRoot() {
        final EditText input = new EditText(host.act());
        input.setText(Engine.hasWorkFilesRoot() ? Engine.gameFiles(host.gamePkg()) : "");
        input.setHint("/storage/emulated/0/Android/data/<包名>/files");
        input.setTextSize(12);
        input.setSingleLine(false);
        new AlertDialog.Builder(host.act())
                .setTitle("手动指定工作目录")
                .setMessage("填游戏 files 目录（官服/渠道服均可）。安装补丁只认这个目录。")
                .setView(input)
                .setPositiveButton("保存", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int which) {
                        String v = input.getText().toString().trim();
                        if (!v.startsWith("/")) {
                            host.toast("请输入绝对路径");
                            return;
                        }
                        String root = GameStorage.gameRoot(v);
                        if (root == null) {
                            host.toast("不是有效的游戏 files 路径");
                            return;
                        }
                        Engine.setWorkFilesRoot(root);
                        host.toast("工作目录已设为 " + root);
                        refreshSubs();
                    }
                })
                .setNegativeButton("取消", null)
                .show();
    }

    // ---------------- 补丁目录 ----------------

    private void patchMenu() {
        final String[] items = {"修改基础根目录", "创建所需文件夹", "打开补丁目录"};
        new AlertDialog.Builder(host.act())
                .setTitle("补丁目录")
                .setItems(items, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int which) {
                        if (which == 0) {
                            editRoot();
                        } else if (which == 1) {
                            createDirs();
                        } else {
                            openDir(Paths.patchDir(host.act()));
                        }
                    }
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private void editRoot() {
        final EditText input = new EditText(host.act());
        input.setText(Paths.root(host.act()));
        input.setTextSize(12);
        input.setSingleLine(false);
        new AlertDialog.Builder(host.act())
                .setTitle("基础根目录")
                .setMessage("补丁、日志都放到这个目录下，与酸败补丁助手共享同一层。默认 "
                        + Paths.DEFAULT_ROOT)
                .setView(input)
                .setPositiveButton("保存", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int which) {
                        String v = input.getText().toString().trim();
                        if (!v.startsWith("/")) {
                            host.toast("请输入绝对路径");
                            return;
                        }
                        Paths.setRoot(host.act(), v);
                        Paths.ensureDirs(host.act());
                        host.toast("基础根目录已更新");
                        refreshSubs();
                    }
                })
                .setNeutralButton("恢复默认", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int which) {
                        Paths.setRoot(host.act(), Paths.DEFAULT_ROOT);
                        Paths.ensureDirs(host.act());
                        host.toast("已恢复默认根目录");
                        refreshSubs();
                    }
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private void createDirs() {
        Paths.ensureDirs(host.act());
        host.toast("已尝试创建所需文件夹");
        host.log("创建目录：\n" + Paths.describe(host.act()));
        refreshSubs();
    }

    private void makeSample() {
        File dir = new File(Paths.patchDir(host.act()), "示例补丁");
        File files = new File(dir, "files/data/android/assetbundle");
        Engine.mkdirs(files);
        try {
            Engine.writeText(new File(dir, "info.txt"),
                    "name=示例补丁\ndesc=把 assetbundle 放进 files 下对应路径即可\nver=1.0\nauthor=lvshu\n");
            Engine.writeText(new File(files, "说明.txt"),
                    "删掉这个 txt，把真正的 .assetbundle 按同样相对路径放进来。\n");
            host.toast("已创建示例补丁目录");
            host.log("创建示例补丁：" + dir.getAbsolutePath());
        } catch (Exception e) {
            host.toast("创建失败：" + e);
        }
    }

    // ---------------- 记录 / 日志 ----------------

    private void recordMenu() {
        final String[] items = {"打开运行日志目录", "打开安装日志目录", "打开崩溃日志目录", "查看运行日志（界面）", "复制日志根目录路径"};
        new AlertDialog.Builder(host.act())
                .setTitle("记录 / 日志")
                .setItems(items, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int which) {
                        if (which == 0) {
                            openDir(Paths.runLogDir(host.act()));
                        } else if (which == 1) {
                            openDir(Paths.installLogDir(host.act()));
                        } else if (which == 2) {
                            openDir(Paths.crashLogDir(host.act()));
                        } else if (which == 3) {
                            if (host.act() instanceof MainActivity) {
                                ((MainActivity) host.act()).showLog();
                            }
                        } else {
                            copy(Paths.logRoot(host.act()));
                        }
                    }
                })
                .setNegativeButton("取消", null)
                .show();
    }

    /** 尝试用系统文件管理器打开目录，失败则复制路径。 */
    private void openDir(String dir) {
        try {
            String prefix = "/storage/emulated/0/";
            if (dir.startsWith(prefix)) {
                android.net.Uri uri = DocumentsContract.buildDocumentUri(
                        "com.android.externalstorage.documents",
                        "primary:" + dir.substring(prefix.length()));
                android.content.Intent i = new android.content.Intent(android.content.Intent.ACTION_VIEW);
                i.setDataAndType(uri, DocumentsContract.Document.MIME_TYPE_DIR);
                i.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK);
                host.act().startActivity(i);
                host.toast("已尝试打开：" + dir);
                return;
            }
        } catch (Throwable ignored) {
        }
        copy(dir);
    }

    private void copy(String text) {
        try {
            ClipboardManager cm = (ClipboardManager) host.act()
                    .getSystemService(android.content.Context.CLIPBOARD_SERVICE);
            cm.setPrimaryClip(ClipData.newPlainText("path", text));
            host.toast("路径已复制");
        } catch (Throwable e) {
            host.toast(text);
        }
    }

    // ---------------- 直装状态诊断 ----------------

    @SuppressWarnings("deprecation")
    private void showDiag() {
        android.content.Context ctx = host.act();
        android.content.pm.PackageManager pm = ctx.getPackageManager();
        String self = ctx.getPackageName();
        String game = host.gamePkg();

        StringBuilder sb = new StringBuilder();
        sb.append("===== 直装状态诊断 =====\n\n");

        // 本工具
        sb.append("【工具 App】\n");
        sb.append("包名: ").append(self).append("\n");
        sb.append("UID: ").append(android.os.Process.myUid()).append("\n");
        sb.append("声明的 sharedUserId: ").append(safeUid(pm, self)).append("\n");
        sb.append("签名SHA256: ").append(SigningIdentity.installedSignatureSha256(ctx, self)).append("\n\n");

        // 目标游戏
        sb.append("【目标游戏】\n");
        sb.append("包名: ").append(game).append("\n");
        int gameUid = Engine.uidOf(ctx, game);
        sb.append("UID: ").append(gameUid).append("\n");
        String gameUidStr = safeUid(pm, game);
        sb.append("系统读到的 sharedUserId: ").append(gameUidStr).append("\n");
        sb.append("签名SHA256: ").append(SigningIdentity.installedSignatureSha256(ctx, game)).append("\n");
        sb.append("安装来源 installer: ").append(safeInstaller(pm, game)).append("\n");
        sb.append("版本: ").append(safeVersion(pm, game)).append("\n");
        sb.append("split 分包数: ").append(safeSplitCount(pm, game)).append("\n");
        sb.append("sourceDir: ").append(safeSource(pm, game)).append("\n\n");

        // 判定
        sb.append("===== 判定 =====\n");
        int myUid = android.os.Process.myUid();
        String selfUidStr = safeUid(pm, self);
        boolean uidSame = (myUid == gameUid && gameUid >= 0);
        boolean uidStrSame = selfUidStr != null && selfUidStr.equals(gameUidStr);
        String selfSig = SigningIdentity.installedSignatureSha256(ctx, self);
        String gameSig = SigningIdentity.installedSignatureSha256(ctx, game);
        boolean sigSame = selfSig != null && !selfSig.isEmpty() && selfSig.equals(gameSig);

        sb.append("UID 相同: ").append(uidSame ? "是" : "否 (" + myUid + " vs " + gameUid + ")").append("\n");
        sb.append("sharedUserId 相同: ").append(uidStrSame ? "是" : "否 ('" + selfUidStr + "' vs '" + gameUidStr + "')").append("\n");
        sb.append("签名相同: ").append(sigSame ? "是" : "否").append("\n\n");

        if (gameUid < 0) {
            sb.append(">> 目标游戏未安装，请确认包名。\n");
        } else if (uidSame) {
            sb.append(">> UID 已对齐，可以读取游戏目录。\n");
        } else if (!uidStrSame) {
            sb.append(">> 根因锁定：系统读到的游戏 sharedUserId 与工具不一致。\n")
              .append("   游戏包声明的共享UID缺失/不同 → 系统分配了独立UID。\n")
              .append("   若签名相同却 sharedUserId 缺失，多为覆盖安装未真正替换，")
              .append("   或制作时写入未生效。请把以上信息发给作者。\n");
        } else if (!sigSame) {
            sb.append(">> 根因锁定：签名不一致，共享UID失效。请用本工具重做直装包。\n");
        } else {
            sb.append(">> sharedUserId 与签名都一致但 UID 仍不同：\n")
              .append("   多为覆盖安装时系统保留了旧包，或装在了不同用户空间/多开。\n")
              .append("   请先卸载干净游戏，再安装本工具制作的直装包。\n");
        }

        final String text = sb.toString();
        TextView tv = new TextView(ctx);
        tv.setText(text);
        tv.setTextSize(12);
        tv.setTextColor(Ui.TXT);
        tv.setPadding(ui.dp(16), ui.dp(12), ui.dp(16), ui.dp(12));
        ScrollView sv = new ScrollView(ctx);
        sv.addView(tv);

        new AlertDialog.Builder(ctx)
                .setTitle("直装状态诊断")
                .setView(sv)
                .setPositiveButton("复制全部", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int which) {
                        copy(text);
                    }
                })
                .setNegativeButton("关闭", null)
                .show();
    }

    private String safeUid(android.content.pm.PackageManager pm, String pkg) {
        try {
            return pm.getPackageInfo(pkg, 0).sharedUserId;
        } catch (Throwable e) {
            return "<读取失败:" + e.getClass().getSimpleName() + ">";
        }
    }

    private String safeInstaller(android.content.pm.PackageManager pm, String pkg) {
        try {
            return String.valueOf(pm.getInstallerPackageName(pkg));
        } catch (Throwable e) {
            return "?";
        }
    }

    private String safeVersion(android.content.pm.PackageManager pm, String pkg) {
        try {
            android.content.pm.PackageInfo pi = pm.getPackageInfo(pkg, 0);
            return pi.versionName + " (" + pi.versionCode + ")";
        } catch (Throwable e) {
            return "?";
        }
    }

    private String safeSource(android.content.pm.PackageManager pm, String pkg) {
        try {
            return pm.getApplicationInfo(pkg, 0).sourceDir;
        } catch (Throwable e) {
            return "?";
        }
    }

    private String safeSplitCount(android.content.pm.PackageManager pm, String pkg) {
        try {
            String[] splits = pm.getApplicationInfo(pkg, 0).splitSourceDirs;
            return (splits == null ? 0 : splits.length)
                    + (splits != null && splits.length > 0 ? "（有分包，注意资源完整性）" : "（单包）");
        } catch (Throwable e) {
            return "?";
        }
    }

    // ---------------- 关于 ----------------

    private static final String GITHUB_URL = "https://github.com/LvShu2009/suibai-patchtool-direct";

    private void showAbout() {
        String prefix = "继承 miku fan 的 300 补丁社区，用 Java 重新构建；\n"
                + "定位与目录方面沿用 LvShu 的酸败补丁助手；\n";
        String linkText = "项目开源地址：GitHub 仓库（点击访问）";
        String suffix = "\n本版本为纯本地离线工具：不含在线补丁站点、不联网、不统计。\n\n"
                + "本版本作者：LvShu\n\n"
                + "包名：" + host.act().getPackageName() + "\n"
                + "共享 UID：" + Api.SHARED_UID + "\n"
                + "签名指纹：" + Api.certFingerprint(host.act());

        SpannableStringBuilder sb = new SpannableStringBuilder();
        sb.append("酸败自助补丁工具（Java 版）\n\n");
        sb.append(prefix);
        int start = sb.length();
        sb.append(linkText);
        int end = sb.length();
        sb.append(suffix);
        sb.setSpan(new ClickableSpan() {
            @Override
            public void onClick(View widget) {
                try {
                    host.act().startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(GITHUB_URL)));
                } catch (Throwable t) {
                    host.toast("打开浏览器失败：" + t);
                }
            }

            @Override
            public void updateDrawState(android.text.TextPaint ds) {
                ds.setColor(Ui.ACCENT);   // 蓝色字，与主题一致
                ds.setUnderlineText(false);
            }
        }, start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);

        TextView tv = new TextView(host.act());
        tv.setText(sb);
        tv.setTextSize(15);
        tv.setTextColor(Ui.TXT);
        tv.setMovementMethod(LinkMovementMethod.getInstance());
        int pad = ui.dp(4);
        tv.setPadding(pad, pad, pad, pad);

        new AlertDialog.Builder(host.act())
                .setTitle("关于 酸败自助补丁工具")
                .setView(tv)
                .setPositiveButton("确定", null)
                .show();
    }
}
