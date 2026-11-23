package com.dsh.eye;

import android.app.Activity;
import android.os.Build;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * 空会话时的那个「鱼眼」标记 + 三句能直接发的话。
 *
 * ```
 *         ◎          ← 同心圆环（Glyph "fisheye"，蓝→紫渐变）
 *        鱼眼
 *   我能看、能听、能说话
 *   ┌──────────────────┐
 *   │ 看一眼我桌上有什么 │        ← 点一下**填进输入框**（不是直接发）
 *   └──────────────────┘
 *   ┌────────────┐
 *   │ 这个多少钱  │
 *   └────────────┘
 * ```
 *
 * 为什么点一下只填不发：上一版点了就直接发出去，用户想改一个字都不行 ——
 * 而这三句是**模板**，九成的用法是"照这个格式改一改"。
 */
final class EmptyView extends LinearLayout {

    interface Cb {
        void onSuggest(String label);
        /** 点「一起看屏幕」：直接开启陪看（不是填进输入框）。 */
        void onCompanion();
    }

    EmptyView(Activity a, final Cb cb) {
        super(a);
        setOrientation(VERTICAL);
        setGravity(Gravity.CENTER_HORIZONTAL);
        // 顶部留白按屏高算：小屏不至于把内容挤到中间，大屏也不会飘在最上面
        int pad = (int) (a.getResources().getDisplayMetrics().heightPixels * 0.12f);
        setPadding(0, pad, 0, dp(Theme.S5));

        ImageView mark = new ImageView(a);
        mark.setImageDrawable(Glyph.ofGradient(a, "fisheye", 76, Theme.ACCENT, Theme.ACCENT_2));
        addView(mark, new LinearLayout.LayoutParams(dp(76), dp(76)));

        TextView name = new TextView(a);
        name.setText("鱼眼");
        name.setTextColor(Theme.TEXT);
        name.setTextSize(Theme.T_TITLE);
        name.setGravity(Gravity.CENTER);
        name.setPadding(0, dp(Theme.S3), 0, dp(Theme.S1));
        if (Build.VERSION.SDK_INT >= 21) name.setLetterSpacing(0.08f);
        addView(name);

        TextView tag = new TextView(a);
        tag.setText("我能看、能听、能说话");
        tag.setTextColor(Theme.TEXT_DIM);
        tag.setTextSize(Theme.T_SUB);
        tag.setGravity(Gravity.CENTER);
        tag.setPadding(0, 0, 0, dp(Theme.S5));
        addView(tag);

        // 功能入口（点一下**直接开**）：放在话术模板前面，这是这一版最想让人点的那个
        TextView comp = new TextView(a);
        comp.setText("◉  一起看屏幕");
        comp.setTextSize(Theme.T_SUB + 1);
        comp.setTextColor(Theme.ACCENT);
        comp.setGravity(Gravity.CENTER);
        comp.setSingleLine(true);
        comp.setPadding(dp(Theme.S5), dp(11), dp(Theme.S5), dp(11));
        comp.setBackground(Theme.pillStroke(Theme.SURFACE_2, Theme.ACCENT, dp(1)));
        LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        clp.topMargin = dp(Theme.S2);
        comp.setLayoutParams(clp);
        comp.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                if (cb != null) cb.onCompanion();
            }
        });
        Anim.press(comp);
        addView(comp);

        String[] asks = { "看一眼我桌上有什么", "这个多少钱", "帮我记一件事" };
        for (final String ask : asks) {
            TextView pill = new TextView(a);
            pill.setText(ask);
            pill.setTextSize(Theme.T_SUB + 1);
            pill.setTextColor(Theme.TEXT);
            pill.setGravity(Gravity.CENTER);
            pill.setSingleLine(true);
            pill.setPadding(dp(Theme.S4), dp(10), dp(Theme.S4), dp(10));
            pill.setBackground(Theme.pillStroke(Theme.SURFACE_2, Theme.LINE, dp(1)));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.topMargin = dp(Theme.S2);
            pill.setLayoutParams(lp);
            pill.setOnClickListener(new View.OnClickListener() {
                public void onClick(View v) {
                    if (cb != null) cb.onSuggest(ask);
                }
            });
            Anim.press(pill);
            addView(pill);
        }

        Anim.fadeInUp(this, 0);
    }

    private int dp(float v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }
}
