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
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/** 文件页：浏览游戏热更目录 / 补丁目录，点 .assetbundle 直接装，文本可预览。 */
public class FilesPane implements Pane {

    private final Host host;
    private final Ui ui;
    private final LinearLayout root;
    private final TextView pathText;
    private final LinearLayout listBox;

    private File cur;
    private final List<File> stack = new ArrayList<File>();

    public FilesPane(Host host) {
        this.host = host;
        this.ui = host.ui();

        root = new LinearLayout(host.act());
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Ui.BG);

        LinearLayout bar = new LinearLayout(host.act());
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(android.view.Gravity.CENTER_VERTICAL);
        bar.setPadding(ui.dp(12), ui.dp(10), ui.dp(8), ui.dp(8));
        bar.setBackgroundColor(0xFFFFFFFF);
        TextView up = ui.ghost("\u2191 上级", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                goUp();
            }
        });
        up.setPadding(0, 0, 0, 0);
        bar.addView(up, new LinearLayout.LayoutParams(ui.dp(74), ui.dp(34)));
        pathText = ui.mono("");
        pathText.setPadding(ui.dp(8), 0, 0, 0);
        pathText.setSingleLine(true);
        bar.addView(pathText, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        TextView home = ui.ghost("游戏目录", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                open(new File(Engine.gameFiles(host.gamePkg())));
            }
        });
        home.setPadding(0, 0, 0, 0);
        bar.addView(home, new LinearLayout.LayoutParams(ui.dp(78), ui.dp(34)));
        root.addView(bar);

        ScrollView sc = new ScrollView(host.act());
        listBox = new LinearLayout(host.act());
        listBox.setOrientation(LinearLayout.VERTICAL);
        listBox.setPadding(ui.dp(12), ui.dp(8), ui.dp(12), ui.dp(24));
        sc.addView(listBox);
        root.addView(sc, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        open(new File(Engine.gameFiles(host.gamePkg())));
    }

    @Override
    public View view() {
        return root;
    }

    @Override
    public void onShow() {
        if (cur != null && !cur.exists()) {
            open(new File(Engine.gameFiles(host.gamePkg())));
        }
    }

    private void open(File dir) {
        cur = dir;
        // 用户浏览到哪个渠道服的游戏 files 目录，补丁就装到哪个目录。
        // gameRoot() 会把 files 及其子目录都归一到同一个 files 根；非游戏目录自动忽略。
        Engine.setWorkFilesRoot(dir.getAbsolutePath());
        pathText.setText(dir.getAbsolutePath());
        listBox.removeAllViews();

        File[] files = dir.listFiles();
        if (files == null) {
            boolean exists = dir.isDirectory();
            int myUid = android.os.Process.myUid();
            int gameUid = Engine.uidOf(host.act(), host.gamePkg());
            String msg = exists
                    ? "目录存在但读不进来（uid 不一致）：\n" + dir.getAbsolutePath()
                    : "目录不存在：" + dir.getAbsolutePath();
            msg += "\n\n本 App uid=" + myUid + " · 目标 " + host.gamePkg() + " uid=" + gameUid
                    + "\n" + (myUid == gameUid
                    ? "uid 相同，按理能读——可能是目录还没被游戏创建。"
                    : "uid 不同：说明当前装的游戏不是「直装包」，两边不同 UID，Android 11+ 不让跨 App 读数据目录。\n"
                    + "解决：用直装页做一份直装包装上（签名和共享 UID 才对齐）。");
            TextView t = ui.label(msg);
            t.setTextColor(Ui.RED);
            listBox.addView(t);
            android.widget.Button pick = ui.ghost("换一个目标客户端", new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    new AlertDialog.Builder(host.act())
                            .setTitle("选择目标客户端")
                            .setItems(labels(), new DialogInterface.OnClickListener() {
                                @Override
                                public void onClick(DialogInterface d, int which) {
                                    host.setGamePkg(pkgs.get(which));
                                    open(new File(Engine.gameFiles(host.gamePkg())));
                                }
                            })
                            .show();
                }
            });
            listBox.addView(pick, ui.mtop(ui.dp(44)));
            return;
        }
        List<File> list = new ArrayList<File>(Arrays.asList(files));
        Collections.sort(list, new Comparator<File>() {
            @Override
            public int compare(File a, File b) {
                if (a.isDirectory() != b.isDirectory()) {
                    return a.isDirectory() ? -1 : 1;
                }
                return a.getName().compareToIgnoreCase(b.getName());
            }
        });
        if (list.isEmpty()) {
            listBox.addView(ui.label("（空目录）"));
            return;
        }
        for (final File f : list) {
            String sub = f.isDirectory() ? "目录" : human(f.length());
            String icon = f.isDirectory() ? "\u25B8" : (isText(f.getName()) ? "\u2261" : "\u00B7");
            LinearLayout row = ui.row(icon, f.getName(), sub, new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    tap(f);
                }
            });
            listBox.addView(row);
            listBox.addView(ui.divider());
        }
    }

    private void tap(final File f) {
        if (f.isDirectory()) {
            stack.add(cur);
            open(f);
            return;
        }
        if (f.getName().toLowerCase().endsWith(".assetbundle")) {
            new AlertDialog.Builder(host.act())
                    .setTitle(f.getName())
                    .setMessage("把它装进游戏里对应位置？（会先备份原文件）")
                    .setPositiveButton("安装", new DialogInterface.OnClickListener() {
                        @Override
                        public void onClick(DialogInterface d, int which) {
                            InstallFlow.handle(host, f, f.getName(), "文件页", new InstallFlow.Done() {
                                @Override
                                public void onDone(boolean ok, String message) {
                                }
                            });
                        }
                    })
                    .setNegativeButton("取消", null)
                    .show();
            return;
        }
        if (isText(f.getName()) && f.length() < 200 * 1024) {
            showText(f);
            return;
        }
        fileMenu(f);
    }

    private void showText(File f) {
        ScrollView sc = new ScrollView(host.act());
        TextView t = ui.mono(Engine.readText(f));
        t.setTextIsSelectable(true);
        t.setPadding(ui.dp(10), ui.dp(8), ui.dp(10), ui.dp(8));
        sc.addView(t);
        new AlertDialog.Builder(host.act())
                .setTitle(f.getName())
                .setView(sc)
                .setNegativeButton("关闭", null)
                .show();
    }

    private void fileMenu(final File f) {
        String[] items = {"大小：" + human(f.length()), "用其他应用打开", "删除"};
        new AlertDialog.Builder(host.act())
                .setTitle(f.getName())
                .setItems(items, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int which) {
                        if (which == 1) {
                            openWith(f);
                        } else if (which == 2) {
                            confirmDelete(f);
                        }
                    }
                })
                .show();
    }

    private void confirmDelete(final File f) {
        new AlertDialog.Builder(host.act())
                .setTitle("删除")
                .setMessage("确定删除 " + f.getName() + "？")
                .setPositiveButton("删除", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int which) {
                        f.delete();
                        open(cur);
                    }
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private void openWith(File f) {
        try {
            Intent i = new Intent(Intent.ACTION_VIEW);
            i.setDataAndType(ShareProvider.uriFor(f), "*/*");
            i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_ACTIVITY_NEW_TASK);
            host.act().startActivity(Intent.createChooser(i, "用其他应用打开"));
        } catch (Throwable e) {
            host.toast("没有可用的应用");
        }
    }

    private void goUp() {
        if (!stack.isEmpty()) {
            File up = stack.remove(stack.size() - 1);
            open(up);
            return;
        }
        File parent = cur == null ? null : cur.getParentFile();
        if (parent != null) {
            open(parent);
        }
    }

    private final List<String> pkgs = new ArrayList<String>();

    private String[] labels() {
        pkgs.clear();
        String[] targets = Api.listTargets(host.act());
        String[] labels = new String[targets.length];
        for (int i = 0; i < targets.length; i++) {
            String[] f = targets[i].split("\t");
            pkgs.add(f[0]);
            labels[i] = f[1] + "\n" + f[0] + "  uid=" + Engine.uidOf(host.act(), f[0]);
        }
        return labels;
    }

    static boolean isText(String name) {
        String n = name.toLowerCase();
        int dot = n.lastIndexOf('.');
        if (dot < 0) {
            return false;
        }
        String ext = n.substring(dot + 1);
        return ext.equals("txt") || ext.equals("json") || ext.equals("xml") || ext.equals("ini")
                || ext.equals("cfg") || ext.equals("conf") || ext.equals("lua") || ext.equals("log")
                || ext.equals("csv") || ext.equals("md") || ext.equals("properties") || ext.equals("html")
                || ext.equals("js") || ext.equals("css") || ext.equals("yml") || ext.equals("yaml");
    }

    static String human(long n) {
        if (n < 1024) {
            return n + " B";
        }
        if (n < 1024 * 1024) {
            return (n / 1024) + " KB";
        }
        return String.format(java.util.Locale.ROOT, "%.1f MB", n / 1048576.0);
    }
}