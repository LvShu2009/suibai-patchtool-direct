package com.lvshu.build;

import android.app.AlertDialog;
import android.content.DialogInterface;
import android.content.Intent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.io.File;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/** 补丁页：游戏信息、安装入口、补丁列表。 */
public class PatchPane implements Pane {

    private final Host host;
    private final Ui ui;
    private final ScrollView root;
    private final LinearLayout box;
    private final LinearLayout listBox;
    private final TextView info;

    public PatchPane(Host host) {
        this.host = host;
        this.ui = host.ui();

        root = ui.scroller();
        box = ui.column();
        root.addView(box);

        box.addView(ui.title("补丁"));
        TextView sub = ui.sub("把补丁铺进游戏的热更目录；装之前会自动备份原文件，可随时还原。");
        sub.setPadding(0, ui.dp(6), 0, ui.dp(12));
        box.addView(sub);

        info = ui.mono("");
        info.setPadding(0, ui.dp(4), 0, ui.dp(10));
        box.addView(info);

        // 纯本地版：只保留从本地文件导入补丁包，无联网下载入口。
        android.widget.Button zipBtn = ui.primary("导入补丁包", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                pickZip();
            }
        });
        LinearLayout.LayoutParams zipLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ui.dp(46));
        zipLp.topMargin = ui.dp(8);
        box.addView(zipBtn, zipLp);

        LinearLayout row2 = new LinearLayout(host.act());
        row2.setOrientation(LinearLayout.HORIZONTAL);
        android.widget.Button fileBtn = ui.ghost("装单个文件", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                pickSingle();
            }
        });
        android.widget.Button launchBtn = ui.ghost("启动游戏", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                launchGame();
            }
        });
        row2.addView(fileBtn, new LinearLayout.LayoutParams(0, ui.dp(46), 1f));
        LinearLayout.LayoutParams r2 = new LinearLayout.LayoutParams(0, ui.dp(46), 1f);
        r2.leftMargin = ui.dp(8);
        row2.addView(launchBtn, r2);
        box.addView(row2, ui.mtop(ui.dp(46)));

        // 维护工具：回滚/清理备份（不调用 Shizuku/SU）
        box.addView(ui.section("维护"));
        LinearLayout maint = new LinearLayout(host.act());
        maint.setOrientation(LinearLayout.HORIZONTAL);
        android.widget.Button clearBak = ui.ghost("清理备份", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                confirmClearBackups();
            }
        });
        android.widget.Button histBtn = ui.ghost("安装历史 / 回滚", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                showHistory();
            }
        });
        maint.addView(clearBak, new LinearLayout.LayoutParams(0, ui.dp(44), 1f));
        LinearLayout.LayoutParams mp = new LinearLayout.LayoutParams(0, ui.dp(44), 1f);
        mp.leftMargin = ui.dp(8);
        maint.addView(histBtn, mp);
        box.addView(maint, ui.mtop(ui.dp(42)));

        box.addView(ui.section("补丁列表（" + Paths.patchDir(host.act()) + "）"));
        listBox = new LinearLayout(host.act());
        listBox.setOrientation(LinearLayout.VERTICAL);
        box.addView(listBox);

        refresh();
    }

    @Override
    public View view() {
        return root;
    }

    @Override
    public void onShow() {
        refresh();
    }

    private void refresh() {
        if (listBox == null) {
            return;
        }
        final String pkg = host.gamePkg();
        String mode = host.engine().accessMode(pkg);
        File gameDir = new File(Engine.gameFiles(pkg));
        int myUid = android.os.Process.myUid();
        int gameUid = Engine.uidOf(host.act(), pkg);
        String line = "游戏 " + pkg + "\n"
                + "写入模式 " + mode + " · SDK " + android.os.Build.VERSION.SDK_INT + "\n"
                + "uid " + myUid + " · 目标 " + gameUid + (myUid == gameUid ? "（共享）" : "（不同）") + "\n"
                + (gameDir.isDirectory() ? "热更目录就绪" : "热更目录不存在（先运行一次游戏）");
        if (!"直连".equals(mode)) {
            line += "\n提示：不是直连模式。先做一份直装包装上（与游戏同 UID 才能写它的数据目录），"
                    + "或在设置里授予「所有文件访问权限」。";
        }
        info.setText(line);

        listBox.removeAllViews();
        final String patchDir = Paths.patchDir(host.act());
        // 两个来源：① 酸败原版布局——zip 直接放在补丁存放目录根下；② 文件夹补丁 <名>/files/。
        final List<File> zips = Patcher.listPatches(new File(patchDir));
        List<Engine.Patch> patches = host.engine().listPatches();
        if (zips.isEmpty() && patches.isEmpty()) {
            TextView empty = ui.label("还没有补丁。把 zip 放在 " + patchDir + " 下，或点上方「导入补丁包」。");
            empty.setPadding(0, ui.dp(8), 0, ui.dp(8));
            listBox.addView(empty);
            return;
        }

        // ① 根目录 zip（酸败补丁助手的标准布局）——点击直接走成熟的 zip 安装流程。
        for (final File zip : zips) {
            LinearLayout row = ui.row("\u2726", zip.getName(), fmtSize(zip.length()),
                    new View.OnClickListener() {
                        @Override
                        public void onClick(View v) {
                            installZip(zip);
                        }
                    });
            listBox.addView(card(row));
        }

        // ② 文件夹补丁（老布局），保留原有的安装/回滚/删除等操作。
        for (final Engine.Patch p : patches) {
            String desc = p.desc;
            if (desc.length() == 0) {
                desc = (p.ver.length() > 0 ? p.ver + " · " : "") + p.count + " 个文件";
            } else {
                desc = desc + (p.ver.length() > 0 ? " · " + p.ver : "") + " · " + p.count + " 个文件";
            }
            LinearLayout row = ui.row("\u2726", p.name, desc, new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    menu(p);
                }
            });
            listBox.addView(card(row));
        }
    }

    /** 统一的行卡片外框。 */
    private LinearLayout card(LinearLayout row) {
        LinearLayout wrap = new LinearLayout(host.act());
        wrap.setOrientation(LinearLayout.VERTICAL);
        wrap.setBackground(ui.round(Ui.CARD, 12));
        wrap.setPadding(ui.dp(6), ui.dp(2), ui.dp(6), ui.dp(2));
        wrap.addView(row);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = ui.dp(8);
        return wrap;
    }

    /** 走统一安装入口（内部判断 zip，带进度框，含 CAB 定位）。 */
    private void installZip(final File zip) {
        InstallFlow.handle(host, zip, zip.getName(), zip.getName(), new InstallFlow.Done() {
            @Override
            public void onDone(boolean ok, String message) {
                if (ok) {
                    refresh();
                }
            }
        });
    }

    private static String fmtSize(long bytes) {
        if (bytes < 1024L) {
            return bytes + " B";
        }
        if (bytes < 1024L * 1024L) {
            return (bytes / 1024L) + " KB";
        }
        return String.format(Locale.ROOT, "%.1f MB", bytes / 1024.0 / 1024.0);
    }

    private void menu(final Engine.Patch p) {
        String[] items = {"安装", "回滚（还原到安装前）", "删除已安装的文件", "查看文件", "删除这个补丁"};
        new AlertDialog.Builder(host.act())
                .setTitle(p.name)
                .setItems(items, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int which) {
                        if (which == 0) {
                            doInstall(p);
                        } else if (which == 1) {
                            doRestore(p);
                        } else if (which == 2) {
                            confirmDeleteInstalled(p);
                        } else if (which == 3) {
                            showFiles(p);
                        } else {
                            confirmDelete(p);
                        }
                    }
                })
                .show();
    }

    /** 删除该补丁装进游戏目录的文件（不走 Shizuku/SU，按备份清单定位）。 */
    private void confirmDeleteInstalled(final Engine.Patch p) {
        new AlertDialog.Builder(host.act())
                .setTitle("删除已安装的文件")
                .setMessage("删除「" + p.name + "」装进游戏目录的文件（按安装备份清单定位），"
                        + "补丁源文件会保留。确定继续？")
                .setPositiveButton("删除", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int which) {
                        Busy.run(host, "删除已装文件 " + p.name, new Busy.Task() {
                            @Override
                            public String run(Engine.Progress cb) throws Exception {
                                cb.on(50, "按备份清单删除游戏目录文件…");
                                int n = host.engine().deleteInstalled(p.folder);
                                host.log("删除已装文件：" + p.name + "（" + n + " 个）");
                                return "已删除 " + n + " 个已安装文件";
                            }
                        });
                    }
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private void doInstall(final Engine.Patch p) {
        final String pkg = host.gamePkg();
        Busy.run(host, "安装 " + p.name, new Busy.Task() {
            @Override
            public String run(Engine.Progress cb) throws Exception {
                return host.engine().install(host.engine().itemsOf(p, pkg), p.folder, p.folder, cb);
            }
        });
    }

    private void doRestore(final Engine.Patch p) {
        final File bak = host.engine().latestBackup(p.folder);
        if (bak == null) {
            host.toast("这个补丁没有备份记录");
            return;
        }
        Busy.run(host, "还原 " + p.name, new Busy.Task() {
            @Override
            public String run(Engine.Progress cb) throws Exception {
                cb.on(50, "还原 " + bak.getName());
                return host.engine().restore(bak);
            }
        });
    }

    private void confirmDelete(final Engine.Patch p) {
        new AlertDialog.Builder(host.act())
                .setTitle("删除补丁")
                .setMessage("只删除补丁目录下的「" + p.folder + "」，已装进游戏的文件不会回滚。")
                .setPositiveButton("删除", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int which) {
                        ZipPatch.deleteTree(p.dir);
                        host.log("删除补丁 " + p.folder);
                        refresh();
                    }
                })
                .setNegativeButton("取消", null)
                .show();
    }

    /** 清理所有安装备份快照。 */
    private void confirmClearBackups() {
        long kb = host.engine().backupSize() / 1024;
        new AlertDialog.Builder(host.act())
                .setTitle("清理备份")
                .setMessage("将删除全部安装前的备份快照（约 " + kb + " KB）。\n"
                        + "清理后无法再用「回滚 / 还原到安装前」恢复这些文件。确定继续？")
                .setPositiveButton("清理", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int which) {
                        Busy.run(host, "清理备份", new Busy.Task() {
                            @Override
                            public String run(Engine.Progress cb) throws Exception {
                                cb.on(50, "删除备份快照…");
                                final int n = host.engine().clearBackups();
                                host.log("已清理 " + n + " 份备份");
                                return "已清理 " + n + " 份备份";
                            }
                        });
                    }
                })
                .setNegativeButton("取消", null)
                .show();
    }

    /** 安装历史：点一条用其备份还原。 */
    private void showHistory() {
        final java.util.List<Engine.Hist> list = host.engine().history();
        if (list.isEmpty()) {
            host.toast("还没有安装历史");
            return;
        }
        String[] items = new String[list.size()];
        for (int i = 0; i < list.size(); i++) {
            Engine.Hist h = list.get(i);
            items[i] = h.time + "  " + h.tag + "  " + h.name;
        }
        new AlertDialog.Builder(host.act())
                .setTitle("安装历史（点一条还原）")
                .setItems(items, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int which) {
                        final Engine.Hist h = list.get(which);
                        final File bak = new File(h.bak);
                        if (h.bak.length() == 0 || !bak.isDirectory()) {
                            host.toast("备份目录不存在，已移除该记录");
                            host.engine().removeHist(h.bak);
                            return;
                        }
                        Busy.run(host, "还原 " + h.tag, new Busy.Task() {
                            @Override
                            public String run(Engine.Progress cb) throws Exception {
                                cb.on(50, "从备份还原…");
                                return host.engine().restore(bak);
                            }
                        });
                    }
                })
                .setNegativeButton("关闭", null)
                .show();
    }

    private void showFiles(Engine.Patch p) {
        ScrollView sc = new ScrollView(host.act());
        LinearLayout b = new LinearLayout(host.act());
        b.setOrientation(LinearLayout.VERTICAL);
        b.setPadding(ui.dp(10), ui.dp(6), ui.dp(10), ui.dp(6));
        sc.addView(b);
        for (String rel : p.rels) {
            b.addView(ui.mono(rel));
        }
        new AlertDialog.Builder(host.act())
                .setTitle(p.name + " · " + p.rels.size() + " 个文件")
                .setView(sc)
                .setNegativeButton("关闭", null)
                .show();
    }

    // ---------------- 安装入口 ----------------

    private void pickZip() {
        host.pickFile(new Host.FilePick() {
            @Override
            public void onPicked(File file, String name) {
                InstallFlow.handle(host, file, name, "本地安装", new InstallFlow.Done() {
                    @Override
                    public void onDone(boolean ok, String message) {
                        refresh();
                    }
                });
            }
        });
    }

    private void pickSingle() {
        host.pickFile(new Host.FilePick() {
            @Override
            public void onPicked(File file, String name) {
                InstallFlow.handle(host, file, name, "单文件", new InstallFlow.Done() {
                    @Override
                    public void onDone(boolean ok, String message) {
                        refresh();
                    }
                });
            }
        });
    }

    private void launchGame() {
        try {
            Intent i = host.act().getPackageManager().getLaunchIntentForPackage(host.gamePkg());
            if (i == null) {
                host.toast("没有找到游戏 " + host.gamePkg());
                return;
            }
            host.act().startActivity(i);
            host.log("已启动 " + host.gamePkg());
        } catch (Throwable e) {
            host.toast("启动失败：" + e);
        }
    }
}