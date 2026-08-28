package com.local.shelldeck;

import android.app.Activity;
import android.content.Intent;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.RippleDrawable;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

final class AppTabs {
    static final int SCRIPTS = 0;
    static final int CHANNELS = 1;
    static final int PROCESSES = 2;

    private AppTabs() {}

    static View create(Activity activity, int selectedPage) {
        int selectedColor = Ui.BLUE;
        LinearLayout bar = new LinearLayout(activity);
        bar.setOrientation(LinearLayout.VERTICAL);
        bar.setBackgroundColor(Ui.SURFACE);
        bar.setElevation(Ui.dp(activity, 6));

        View divider = new View(activity);
        divider.setBackgroundColor(Ui.LINE);
        bar.addView(divider, new LinearLayout.LayoutParams(-1, Ui.dp(activity, 1)));

        LinearLayout row = new LinearLayout(activity);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.addView(tab(activity, R.drawable.ic_terminal, "脚本",
                selectedPage == SCRIPTS, selectedColor, () -> {
            if (selectedPage == SCRIPTS) return;
            Intent intent = new Intent(activity, MainActivity.class)
                    .addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT);
            activity.startActivity(intent);
            activity.overridePendingTransition(0, 0);
        }), new LinearLayout.LayoutParams(0, Ui.dp(activity, 60), 1));
        row.addView(tab(activity, R.drawable.ic_sessions, "通道",
                selectedPage == CHANNELS, selectedColor, () -> {
            if (selectedPage == CHANNELS) return;
            Intent intent = new Intent(activity, RunningSessionsActivity.class)
                    .addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT);
            activity.startActivity(intent);
            activity.overridePendingTransition(0, 0);
        }), new LinearLayout.LayoutParams(0, Ui.dp(activity, 60), 1));
        row.addView(tab(activity, R.drawable.ic_processes, "进程",
                selectedPage == PROCESSES, selectedColor, () -> {
            if (selectedPage == PROCESSES) return;
            Intent intent = new Intent(activity, ProcessMonitorActivity.class)
                    .addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT);
            activity.startActivity(intent);
            activity.overridePendingTransition(0, 0);
        }), new LinearLayout.LayoutParams(0, Ui.dp(activity, 60), 1));
        bar.addView(row, new LinearLayout.LayoutParams(-1, Ui.dp(activity, 60)));
        return bar;
    }

    private static View tab(Activity activity, int iconRes, String label,
                            boolean selected, int selectedColor, Runnable action) {
        FrameLayout tab = new FrameLayout(activity);
        int rippleColor = selected ? Color.argb(28, Color.red(selectedColor),
                Color.green(selectedColor), Color.blue(selectedColor))
                : Color.argb(20, 24, 33, 38);
        tab.setBackground(new RippleDrawable(
                ColorStateList.valueOf(rippleColor),
                new ColorDrawable(Ui.SURFACE), null));
        tab.setClickable(true);
        tab.setFocusable(true);
        tab.setSelected(selected);
        tab.setContentDescription(selected ? label + "，当前页面" : label);
        tab.setOnClickListener(view -> action.run());

        View rail = new View(activity);
        rail.setBackgroundColor(selected ? selectedColor : Color.TRANSPARENT);
        FrameLayout.LayoutParams railParams = new FrameLayout.LayoutParams(-1, Ui.dp(activity, 3));
        railParams.gravity = Gravity.TOP;
        railParams.leftMargin = Ui.dp(activity, 28);
        railParams.rightMargin = Ui.dp(activity, 28);
        tab.addView(rail, railParams);

        LinearLayout content = new LinearLayout(activity);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setGravity(Gravity.CENTER);
        FrameLayout iconSlot = new FrameLayout(activity);
        ImageView icon = new ImageView(activity);
        icon.setImageResource(iconRes);
        icon.setImageTintList(ColorStateList.valueOf(selected ? selectedColor : Ui.MUTED));
        icon.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
        FrameLayout.LayoutParams iconParams = new FrameLayout.LayoutParams(
                Ui.dp(activity, 22), Ui.dp(activity, 22));
        iconParams.gravity = Gravity.CENTER;
        iconSlot.addView(icon, iconParams);
        content.addView(iconSlot, new LinearLayout.LayoutParams(-1, Ui.dp(activity, 29)));
        TextView text = Ui.text(activity, label, 11,
                selected ? selectedColor : Ui.MUTED, selected ? Typeface.BOLD : Typeface.NORMAL);
        text.setGravity(Gravity.CENTER);
        content.addView(text, new LinearLayout.LayoutParams(-1, Ui.dp(activity, 21)));
        FrameLayout.LayoutParams contentParams = new FrameLayout.LayoutParams(-1, Ui.dp(activity, 52));
        contentParams.gravity = Gravity.BOTTOM;
        tab.addView(content, contentParams);
        return tab;
    }
}
