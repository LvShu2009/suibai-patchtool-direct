package com.lvshu.build;

import android.app.AlertDialog;
import android.content.Context;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;

/** 带进度框的后台任务。 */
public final class Busy {

    public interface Task {
        /** 返回空串表示成功，否则是错误描述。 */
        String run(Engine.Progress cb) throws Exception;
    }

    private Busy() {
    }

    public static void run(final Host host, String title, final Task task) {
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
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, host.ui().dp(6));
        lp.topMargin = host.ui().dp(12);
        box.addView(bar, lp);

        final AlertDialog dlg = new AlertDialog.Builder(ctx)
                .setTitle(title)
                .setView(box)
                .setCancelable(false)
                .create();
        if (!host.act().isFinishing()) {
            dlg.show();
        }

        new Thread(new Runnable() {
            @Override
            public void run() {
                String err;
                try {
                    err = task.run(new Engine.Progress() {
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
                    });
                } catch (Throwable e) {
                    err = "异常：" + e;
                }
                final String result = err;
                host.act().runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        try {
                            dlg.dismiss();
                        } catch (Throwable ignored) {
                        }
                        if (result == null || result.length() == 0) {
                            host.log(title + " 完成");
                            host.toast("完成，重启游戏生效");
                        } else {
                            host.log(title + " 失败：" + result);
                            host.toast(result);
                        }
                    }
                });
            }
        }, "busy").start();
    }
}