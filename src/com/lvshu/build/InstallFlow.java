package com.lvshu.build;

import android.app.AlertDialog;
import android.app.ProgressDialog;
import android.content.Context;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;

import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;

/** 统一的补丁安装入口：判断 zip / 单文件，带进度框。 */
public final class InstallFlow {

    public interface Done {
        void onDone(boolean ok, String message);
    }

    private InstallFlow() {
    }

    public static void handle(final Host host, final File file, final String name, final String tag, final Done done) {
        // 可能从下载线程调进来，Dialog 必须在主线程创建
        if (android.os.Looper.myLooper() != android.os.Looper.getMainLooper()) {
            host.act().runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    handle(host, file, name, tag, done);
                }
            });
            return;
        }
        final Context ctx = host.act();
        final LinearLayout box = new LinearLayout(ctx);
        box.setOrientation(LinearLayout.VERTICAL);
        int pad = host.ui().dp(18);
        box.setPadding(pad, pad / 2, pad, pad / 2);

        final TextView status = new TextView(ctx);
        status.setText("准备中…");
        status.setTextSize(13);
        box.addView(status);

        final ProgressBar bar = new ProgressBar(ctx, null, android.R.attr.progressBarStyleHorizontal);
        bar.setMax(100);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                host.ui().dp(6));
        lp.topMargin = host.ui().dp(12);
        box.addView(bar, lp);

        final AlertDialog dlg = new AlertDialog.Builder(ctx)
                .setTitle("安装补丁")
                .setView(box)
                .setCancelable(false)
                .create();
        if (!host.act().isFinishing()) {
            dlg.show();
        }

        final Engine.Progress cb = new Engine.Progress() {
            @Override
            public void on(final int pct, final String msg) {
                host.act().runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        bar.setProgress(pct);
                        status.setText(msg);
                    }
                });
            }
        };

        final String pkg = host.gamePkg();
        final boolean zip = isZip(file);
        String startMsg = "开始安装 " + name + (zip ? "（压缩包）" : "（单文件）");
        host.log(startMsg);
        DiagLog.installLog(startMsg);

        if (zip) {
            ZipPatch.install(host.engine(), pkg, file, tag, cb, new ZipPatch.Done() {
                @Override
                public void onDone(final String error, final String summary) {
                    finish(host, dlg, error, summary, done, tag + " " + name);
                }
            });
            return;
        }

        new Thread(new Runnable() {
            @Override
            public void run() {
                String err;
                try {
                    cb.on(30, "定位 " + name + "…");
                    err = host.engine().installBundle(file, name, tag, pkg, cb);
                } catch (Throwable e) {
                    err = "安装异常：" + e;
                }
                finish(host, dlg, err, "", done, tag + " " + name);
            }
        }, "bundle-install").start();
    }

    private static void finish(final Host host, final AlertDialog dlg, final String error,
                               final String summary, final Done done, final String what) {
        host.act().runOnUiThread(new Runnable() {
            @Override
            public void run() {
                try {
                    dlg.dismiss();
                } catch (Throwable ignored) {
                }
                boolean ok = error == null || error.length() == 0;
                if (ok) {
                    String msg = "安装完成：" + what + (summary.length() > 0 ? "（" + summary + "）" : "");
                    host.log(msg);
                    DiagLog.installLog(msg);
                    host.toast("安装完成，重启游戏生效");
                } else {
                    String msg = "安装失败：" + error;
                    host.log(msg);
                    DiagLog.installLog(msg);
                    host.toast(error);
                }
                if (done != null) {
                    done.onDone(ok, ok ? summary : error);
                }
            }
        });
    }

    /** 按扩展名 + 文件头判断是否 zip。 */
    public static boolean isZip(File f) {
        String n = f.getName().toLowerCase();
        if (n.endsWith(".zip") || n.endsWith(".apk")) {
            return true;
        }
        try {
            InputStream in = new FileInputStream(f);
            byte[] b = new byte[4];
            int n2 = in.read(b);
            in.close();
            return n2 == 4 && b[0] == 'P' && b[1] == 'K';
        } catch (Exception e) {
            return false;
        }
    }
}