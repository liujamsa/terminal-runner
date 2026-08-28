package com.local.shelldeck;

import android.app.Activity;
import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.View;
import android.widget.ImageButton;
import android.widget.TextView;

import java.util.Locale;

final class Ui {
    static final int INK = Color.rgb(247, 249, 251);
    static final int SURFACE = Color.rgb(255, 255, 255);
    static final int SURFACE_HIGH = Color.rgb(241, 245, 247);
    static final int LINE = Color.rgb(221, 229, 234);
    static final int TEXT = Color.rgb(23, 33, 38);
    static final int MUTED = Color.rgb(101, 117, 127);
    static final int ACCENT = Color.rgb(94, 158, 214);
    static final int ACCENT_DARK = Color.rgb(66, 126, 178);
    static final int BLUE = Color.rgb(107, 130, 232);
    static final int BLUE_TINT = Color.rgb(235, 239, 253);
    static final int ROOT_TINT = Color.rgb(255, 235, 238);
    static final int ROOT_TEXT = Color.rgb(178, 65, 82);
    static final int LAVENDER = Color.rgb(154, 136, 210);
    static final int AMBER = Color.rgb(141, 120, 199);
    static final int DANGER = Color.rgb(216, 102, 115);

    private Ui() {}

    static void applyLightSystemBars(Activity activity) {
        activity.getWindow().setStatusBarColor(INK);
        activity.getWindow().setNavigationBarColor(SURFACE);
        activity.getWindow().getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR | View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR);
    }

    static int dp(Context context, int value) {
        return Math.round(value * context.getResources().getDisplayMetrics().density);
    }

    static GradientDrawable background(Context context, int color, int radiusDp) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(color);
        drawable.setCornerRadius(dp(context, radiusDp));
        return drawable;
    }

    static GradientDrawable outlinedBackground(Context context, int color, int stroke, int radiusDp) {
        GradientDrawable drawable = background(context, color, radiusDp);
        drawable.setStroke(dp(context, 1), stroke);
        return drawable;
    }

    static TextView text(Context context, String value, float sizeSp, int color, int style) {
        TextView view = new TextView(context);
        view.setText(value);
        view.setTextSize(sizeSp);
        view.setTextColor(color);
        view.setGravity(Gravity.CENTER_VERTICAL);
        view.setTypeface(Typeface.create("sans", style));
        view.setLetterSpacing(0);
        return view;
    }

    static ImageButton iconButton(Context context, int drawable, String description) {
        ImageButton button = new ImageButton(context);
        button.setImageResource(drawable);
        button.setContentDescription(description);
        button.setBackground(background(context, SURFACE_HIGH, 6));
        button.setPadding(dp(context, 12), dp(context, 12), dp(context, 12), dp(context, 12));
        button.setImageTintList(ColorStateList.valueOf(TEXT));
        button.setScaleType(ImageButton.ScaleType.CENTER_INSIDE);
        return button;
    }

    static void setEnabled(View view, boolean enabled) {
        view.setEnabled(enabled);
        view.setAlpha(enabled ? 1f : 0.4f);
    }

    static void setIconTint(ImageButton button, int color) {
        button.setImageTintList(ColorStateList.valueOf(color));
    }

    static String formatSize(long bytes) {
        if (bytes < 0) return "大小未知";
        if (bytes < 1024) return bytes + " B";
        if (bytes < 1024 * 1024) return String.format(Locale.ROOT, "%.1f KB", bytes / 1024f);
        return String.format(Locale.ROOT, "%.1f MB", bytes / (1024f * 1024f));
    }
}
