package com.lvshu.build;

import android.app.Activity;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

/** 轻量 UI 工具：卡片、标题、按钮、行。 */
public final class Ui {

    public static final int BG = 0xFFF2F4F7;
    public static final int CARD = 0xFFFFFFFF;
    public static final int TXT = 0xFF1F2937;
    public static final int SUB = 0xFF6B7280;
    public static final int DIM = 0xFF9CA3AF;
    public static final int ACCENT = 0xFF2563EB;
    public static final int GREEN = 0xFF16A34A;
    public static final int ORANGE = 0xFFD97706;
    public static final int RED = 0xFFDC2626;

    private final Activity a;
    private final float d;

    public Ui(Activity a) {
        this.a = a;
        this.d = a.getResources().getDisplayMetrics().density;
    }

    public int dp(float v) {
        return (int) (v * d + 0.5f);
    }

    public static GradientDrawable round(int color, float radiusDp, Activity a) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(color);
        g.setCornerRadius(radiusDp * a.getResources().getDisplayMetrics().density);
        return g;
    }

    public GradientDrawable round(int color, float radiusDp) {
        return round(color, radiusDp, a);
    }

    public ScrollView scroller() {
        ScrollView s = new ScrollView(a);
        s.setFillViewport(true);
        s.setBackgroundColor(BG);
        return s;
    }

    public LinearLayout column() {
        LinearLayout l = new LinearLayout(a);
        l.setOrientation(LinearLayout.VERTICAL);
        l.setPadding(dp(16), dp(18), dp(16), dp(24));
        return l;
    }

    public LinearLayout card() {
        LinearLayout l = new LinearLayout(a);
        l.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(14);
        l.setPadding(pad, pad, pad, pad);
        l.setBackground(round(CARD, 14));
        return l;
    }

    public LinearLayout.LayoutParams lp(int w, int h) {
        return new LinearLayout.LayoutParams(w, h);
    }

    public LinearLayout.LayoutParams mtop(int h) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, h);
        p.topMargin = dp(8);
        return p;
    }

    public TextView title(String text) {
        TextView t = new TextView(a);
        t.setText(text);
        t.setTextSize(21);
        t.setTextColor(0xFF111827);
        t.setTypeface(Typeface.DEFAULT_BOLD);
        return t;
    }

    public TextView sub(String text) {
        TextView t = new TextView(a);
        t.setText(text);
        t.setTextSize(12);
        t.setTextColor(SUB);
        t.setLineSpacing(dp(2), 1f);
        return t;
    }

    public TextView label(String text) {
        TextView t = new TextView(a);
        t.setText(text);
        t.setTextSize(12);
        t.setTextColor(SUB);
        return t;
    }

    public TextView mono(String text) {
        TextView t = new TextView(a);
        t.setText(text);
        t.setTextSize(11);
        t.setTypeface(Typeface.MONOSPACE);
        t.setTextColor(0xFF374151);
        return t;
    }

    public TextView section(String text) {
        TextView t = new TextView(a);
        t.setText(text);
        t.setTextSize(12);
        t.setTextColor(DIM);
        t.setPadding(0, dp(14), 0, dp(6));
        return t;
    }

    public LinearLayout divider() {
        LinearLayout v = new LinearLayout(a);
        v.setBackgroundColor(0x11000000);
        v.setLayoutParams(new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 1));
        return v;
    }

    public Button button(String text, View.OnClickListener l) {
        Button b = new Button(a);
        b.setText(text);
        b.setAllCaps(false);
        b.setTextSize(14);
        b.setOnClickListener(l);
        return b;
    }

    /** 主按钮（实心蓝）。 */
    public Button primary(String text, View.OnClickListener l) {
        Button b = button(text, l);
        b.setTextColor(Color.WHITE);
        b.setBackground(round(ACCENT, 12));
        b.setAllCaps(false);
        return b;
    }

    public Button ghost(String text, View.OnClickListener l) {
        Button b = button(text, l);
        b.setTextColor(TXT);
        b.setBackground(round(0xFFEDF0F4, 12));
        return b;
    }

    /** 可点击的列表行：左侧图标字，右侧标题 + 副标题。 */
    public LinearLayout row(String icon, String text, String sub, View.OnClickListener l) {
        LinearLayout r = new LinearLayout(a);
        r.setOrientation(LinearLayout.HORIZONTAL);
        r.setGravity(Gravity.CENTER_VERTICAL);
        r.setPadding(dp(4), dp(11), dp(6), dp(11));
        if (l != null) {
            r.setOnClickListener(l);
            r.setClickable(true);
        }
        if (icon != null && icon.length() > 0) {
            TextView ic = new TextView(a);
            ic.setText(icon);
            ic.setTextSize(16);
            ic.setGravity(Gravity.CENTER);
            ic.setWidth(dp(30));
            r.addView(ic);
        }
        LinearLayout box = new LinearLayout(a);
        box.setOrientation(LinearLayout.VERTICAL);
        TextView t = new TextView(a);
        t.setText(text == null ? "" : text);
        t.setTextSize(14);
        t.setTextColor(TXT);
        box.addView(t);
        if (sub != null && sub.length() > 0) {
            TextView s = new TextView(a);
            s.setText(sub);
            s.setTextSize(11);
            s.setTextColor(SUB);
            s.setPadding(0, dp(1), 0, 0);
            box.addView(s);
        }
        r.addView(box, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        return r;
    }
}