package com.local.shelldeck;

import android.app.Activity;
import android.app.AlertDialog;
import android.graphics.Typeface;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

public final class ProcessMonitorActivity extends Activity {
    private static final long REFRESH_INTERVAL_MS = 3000;
    private enum SortMode { CPU, MEMORY }

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final List<RootProcess> allProcesses = new ArrayList<>();
    private final List<RootProcess> visibleProcesses = new ArrayList<>();
    private final Runnable refreshRunnable = this::refreshNow;
    private RootProcessRepository repository;
    private ProcessAdapter adapter;
    private TextView totalValue;
    private TextView cpuValue;
    private TextView memoryValue;
    private TextView emptyView;
    private View emptyState;
    private SegmentOption cpuSort;
    private SegmentOption memorySort;
    private EditText search;
    private CheckBox onlyKillable;
    private SortMode sortMode = SortMode.CPU;
    private boolean refreshing;
    private boolean active;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Ui.applyLightSystemBars(this);
        repository = new RootProcessRepository(this);
        buildContent();
    }

    @Override
    protected void onStart() {
        super.onStart();
        active = true;
        refreshNow();
    }

    @Override
    protected void onStop() {
        active = false;
        handler.removeCallbacks(refreshRunnable);
        super.onStop();
    }

    private void buildContent() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Ui.INK);
        root.setOnApplyWindowInsetsListener((view, insets) -> {
            view.setPadding(0, insets.getSystemWindowInsetTop(), 0,
                    insets.getSystemWindowInsetBottom());
            return insets;
        });

        LinearLayout toolbar = new LinearLayout(this);
        toolbar.setGravity(Gravity.CENTER_VERTICAL);
        toolbar.setPadding(Ui.dp(this, 18), Ui.dp(this, 7), Ui.dp(this, 12), Ui.dp(this, 5));

        LinearLayout identity = new LinearLayout(this);
        identity.setOrientation(LinearLayout.VERTICAL);
        TextView title = Ui.text(this, "进程监控", 19, Ui.TEXT, Typeface.BOLD);
        title.setTypeface(Typeface.create("sans-serif-condensed", Typeface.BOLD));
        TextView subtitle = Ui.text(this, "ROOT TOP  /  3S", 10, Ui.MUTED, Typeface.BOLD);
        subtitle.setTypeface(Typeface.create("monospace", Typeface.BOLD));
        identity.addView(title, new LinearLayout.LayoutParams(-1, Ui.dp(this, 29)));
        identity.addView(subtitle, new LinearLayout.LayoutParams(-1, Ui.dp(this, 19)));
        toolbar.addView(identity, new LinearLayout.LayoutParams(0, Ui.dp(this, 52), 1));

        ImageButton refresh = Ui.iconButton(this, R.drawable.ic_refresh, getString(R.string.refresh_processes));
        refresh.setOnClickListener(view -> refreshNow());
        toolbar.addView(refresh, new LinearLayout.LayoutParams(Ui.dp(this, 48), Ui.dp(this, 48)));
        root.addView(toolbar, new LinearLayout.LayoutParams(-1, Ui.dp(this, 64)));

        LinearLayout metrics = new LinearLayout(this);
        metrics.setPadding(Ui.dp(this, 14), Ui.dp(this, 8), Ui.dp(this, 14), Ui.dp(this, 8));
        metrics.setBackgroundColor(Ui.SURFACE);
        totalValue = addMetric(metrics, R.drawable.ic_processes, "进程", "--");
        addDivider(metrics);
        cpuValue = addMetric(metrics, R.drawable.ic_cpu, "CPU 占用", "--");
        addDivider(metrics);
        memoryValue = addMetric(metrics, R.drawable.ic_memory, "剩余内存", "--");
        root.addView(metrics, new LinearLayout.LayoutParams(-1, Ui.dp(this, 62)));

        LinearLayout filters = new LinearLayout(this);
        filters.setGravity(Gravity.CENTER_VERTICAL);
        filters.setPadding(Ui.dp(this, 14), Ui.dp(this, 9), Ui.dp(this, 10), Ui.dp(this, 9));
        search = new EditText(this);
        search.setSingleLine(true);
        search.setTextSize(13);
        search.setTextColor(Ui.TEXT);
        search.setHintTextColor(Ui.MUTED);
        search.setHint("搜索名称、PID 或命令");
        search.setPadding(Ui.dp(this, 12), 0, Ui.dp(this, 12), 0);
        search.setBackground(Ui.outlinedBackground(this, Ui.SURFACE, Ui.LINE, 5));
        search.setCompoundDrawablesWithIntrinsicBounds(R.drawable.ic_search, 0, 0, 0);
        search.setCompoundDrawableTintList(android.content.res.ColorStateList.valueOf(Ui.MUTED));
        search.setCompoundDrawablePadding(Ui.dp(this, 8));
        search.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) { applyFilters(); }
            @Override public void afterTextChanged(Editable s) {}
        });
        filters.addView(search, new LinearLayout.LayoutParams(0, Ui.dp(this, 44), 1));

        onlyKillable = new CheckBox(this);
        onlyKillable.setText("仅脚本");
        onlyKillable.setTextSize(12);
        onlyKillable.setTextColor(Ui.TEXT);
        onlyKillable.setGravity(Gravity.CENTER);
        onlyKillable.setButtonTintList(android.content.res.ColorStateList.valueOf(Ui.ACCENT));
        onlyKillable.setChecked(false);
        onlyKillable.setOnCheckedChangeListener((button, checked) -> applyFilters());
        LinearLayout.LayoutParams toggleParams = new LinearLayout.LayoutParams(
                Ui.dp(this, 104), Ui.dp(this, 44));
        toggleParams.leftMargin = Ui.dp(this, 8);
        filters.addView(onlyKillable, toggleParams);
        root.addView(filters, new LinearLayout.LayoutParams(-1, Ui.dp(this, 62)));

        LinearLayout sortBar = new LinearLayout(this);
        sortBar.setGravity(Gravity.CENTER_VERTICAL);
        sortBar.setPadding(Ui.dp(this, 18), Ui.dp(this, 5), Ui.dp(this, 14), Ui.dp(this, 5));
        TextView sortLabel = Ui.text(this, "排序", 11, Ui.MUTED, Typeface.BOLD);
        sortBar.addView(sortLabel, new LinearLayout.LayoutParams(0, -1, 1));
        LinearLayout sortControl = new LinearLayout(this);
        sortControl.setPadding(Ui.dp(this, 2), Ui.dp(this, 2), Ui.dp(this, 2), Ui.dp(this, 2));
        sortControl.setBackground(Ui.outlinedBackground(this, Ui.SURFACE, Ui.LINE, 6));
        cpuSort = sortOption("CPU", R.drawable.ic_cpu, SortMode.CPU);
        memorySort = sortOption("内存", R.drawable.ic_memory, SortMode.MEMORY);
        sortControl.addView(cpuSort.root, new LinearLayout.LayoutParams(Ui.dp(this, 88), -1));
        sortControl.addView(memorySort.root, new LinearLayout.LayoutParams(Ui.dp(this, 88), -1));
        sortBar.addView(sortControl, new LinearLayout.LayoutParams(-2, Ui.dp(this, 40)));
        root.addView(sortBar, new LinearLayout.LayoutParams(-1, Ui.dp(this, 52)));
        updateSortControls();

        LinearLayout columns = new LinearLayout(this);
        columns.setGravity(Gravity.CENTER_VERTICAL);
        columns.setPadding(Ui.dp(this, 18), 0, Ui.dp(this, 14), 0);
        TextView processColumn = Ui.text(this, "PROCESS", 9, Ui.MUTED, Typeface.BOLD);
        processColumn.setTypeface(Typeface.create("monospace", Typeface.BOLD));
        columns.addView(processColumn, new LinearLayout.LayoutParams(0, -1, 1));
        TextView actionColumn = Ui.text(this, "ACTION", 9, Ui.MUTED, Typeface.BOLD);
        actionColumn.setGravity(Gravity.CENTER);
        actionColumn.setTypeface(Typeface.create("monospace", Typeface.BOLD));
        columns.addView(actionColumn, new LinearLayout.LayoutParams(Ui.dp(this, 56), -1));
        root.addView(columns, new LinearLayout.LayoutParams(-1, Ui.dp(this, 28)));

        FrameLayout content = new FrameLayout(this);
        ListView list = new ListView(this);
        list.setDivider(null);
        list.setSelector(android.R.color.transparent);
        list.setVerticalScrollBarEnabled(false);
        adapter = new ProcessAdapter();
        list.setAdapter(adapter);
        content.addView(list, new FrameLayout.LayoutParams(-1, -1));
        LinearLayout empty = new LinearLayout(this);
        empty.setOrientation(LinearLayout.VERTICAL);
        empty.setGravity(Gravity.CENTER);
        ImageView emptyIcon = new ImageView(this);
        emptyIcon.setImageResource(R.drawable.ic_processes);
        emptyIcon.setImageTintList(android.content.res.ColorStateList.valueOf(Ui.BLUE));
        emptyIcon.setBackground(Ui.background(this, Ui.SURFACE_HIGH, 8));
        emptyIcon.setPadding(Ui.dp(this, 14), Ui.dp(this, 14), Ui.dp(this, 14), Ui.dp(this, 14));
        empty.addView(emptyIcon, new LinearLayout.LayoutParams(Ui.dp(this, 58), Ui.dp(this, 58)));
        emptyView = Ui.text(this, "正在读取 Root 进程", 13, Ui.MUTED, Typeface.NORMAL);
        emptyView.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams emptyText = new LinearLayout.LayoutParams(-1, Ui.dp(this, 48));
        emptyText.topMargin = Ui.dp(this, 8);
        empty.addView(emptyView, emptyText);
        emptyState = empty;
        content.addView(empty, new FrameLayout.LayoutParams(-1, -1));
        root.addView(content, new LinearLayout.LayoutParams(-1, 0, 1));
        root.addView(AppTabs.create(this, AppTabs.PROCESSES),
                new LinearLayout.LayoutParams(-1, Ui.dp(this, 61)));

        setContentView(root);
        root.requestApplyInsets();
    }

    private TextView addMetric(LinearLayout parent, int iconRes, String label, String initial) {
        LinearLayout group = new LinearLayout(this);
        group.setOrientation(LinearLayout.VERTICAL);
        group.setGravity(Gravity.CENTER);
        TextView value = Ui.text(this, initial, 16, Ui.TEXT, Typeface.BOLD);
        value.setGravity(Gravity.CENTER);
        value.setTypeface(Typeface.create("monospace", Typeface.BOLD));
        LinearLayout caption = new LinearLayout(this);
        caption.setGravity(Gravity.CENTER);
        ImageView metricIcon = new ImageView(this);
        metricIcon.setImageResource(iconRes);
        metricIcon.setImageTintList(android.content.res.ColorStateList.valueOf(Ui.MUTED));
        caption.addView(metricIcon, new LinearLayout.LayoutParams(Ui.dp(this, 13), Ui.dp(this, 13)));
        TextView captionText = Ui.text(this, label, 9, Ui.MUTED, Typeface.BOLD);
        captionText.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams captionTextParams = new LinearLayout.LayoutParams(-2, -1);
        captionTextParams.leftMargin = Ui.dp(this, 4);
        caption.addView(captionText, captionTextParams);
        group.addView(value, new LinearLayout.LayoutParams(-1, Ui.dp(this, 27)));
        group.addView(caption, new LinearLayout.LayoutParams(-1, Ui.dp(this, 19)));
        parent.addView(group, new LinearLayout.LayoutParams(0, -1, 1));
        return value;
    }

    private SegmentOption sortOption(String label, int iconRes, SortMode mode) {
        SegmentOption option = new SegmentOption();
        option.icon.setImageResource(iconRes);
        option.label.setText(label);
        option.root.setOnClickListener(view -> {
            sortMode = mode;
            updateSortControls();
            applyFilters();
        });
        return option;
    }

    private void updateSortControls() {
        updateSortOption(cpuSort, sortMode == SortMode.CPU);
        updateSortOption(memorySort, sortMode == SortMode.MEMORY);
    }

    private void updateSortOption(SegmentOption option, boolean selected) {
        if (option == null) return;
        option.label.setTextColor(selected ? android.graphics.Color.WHITE : Ui.MUTED);
        option.icon.setImageTintList(android.content.res.ColorStateList.valueOf(
                selected ? android.graphics.Color.WHITE : Ui.MUTED));
        option.root.setBackground(Ui.background(this, selected ? Ui.BLUE : Ui.SURFACE, 4));
    }

    private final class SegmentOption {
        final LinearLayout root = new LinearLayout(ProcessMonitorActivity.this);
        final ImageView icon = new ImageView(ProcessMonitorActivity.this);
        final TextView label;

        SegmentOption() {
            root.setGravity(Gravity.CENTER);
            root.setClickable(true);
            root.setFocusable(true);
            icon.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
            root.addView(icon, new LinearLayout.LayoutParams(
                    Ui.dp(ProcessMonitorActivity.this, 18), Ui.dp(ProcessMonitorActivity.this, 18)));
            label = Ui.text(ProcessMonitorActivity.this, "", 11, Ui.TEXT, Typeface.BOLD);
            label.setGravity(Gravity.CENTER);
            LinearLayout.LayoutParams labelParams = new LinearLayout.LayoutParams(-2, -1);
            labelParams.leftMargin = Ui.dp(ProcessMonitorActivity.this, 6);
            root.addView(label, labelParams);
        }
    }

    private void addDivider(LinearLayout parent) {
        View divider = new View(this);
        divider.setBackgroundColor(Ui.LINE);
        parent.addView(divider, new LinearLayout.LayoutParams(Ui.dp(this, 1), -1));
    }

    private void refreshNow() {
        if (refreshing) return;
        refreshing = true;
        handler.removeCallbacks(refreshRunnable);
        new Thread(() -> {
            try {
                List<RootProcess> values = repository.load();
                runOnUiThread(() -> {
                    if (!active) return;
                    allProcesses.clear();
                    allProcesses.addAll(values);
                    updateMetrics();
                    applyFilters();
                });
                repository.preloadAppIcons(values);
                runOnUiThread(() -> {
                    if (active && adapter != null) adapter.notifyDataSetChanged();
                });
            } catch (Exception error) {
                runOnUiThread(() -> {
                    emptyView.setText(error.getMessage() == null
                            ? "无法读取 Root 进程" : error.getMessage());
                    emptyState.setVisibility(View.VISIBLE);
                });
            } finally {
                runOnUiThread(() -> {
                    refreshing = false;
                    if (active) handler.postDelayed(refreshRunnable, REFRESH_INTERVAL_MS);
                });
            }
        }, "process-refresh").start();
    }

    private void updateMetrics() {
        float cpu = 0;
        for (RootProcess process : allProcesses) {
            cpu += Math.max(0, process.cpuPercent);
        }
        android.app.ActivityManager.MemoryInfo memoryInfo =
                new android.app.ActivityManager.MemoryInfo();
        getSystemService(android.app.ActivityManager.class).getMemoryInfo(memoryInfo);
        totalValue.setText(String.valueOf(allProcesses.size()));
        cpuValue.setText(String.format(Locale.ROOT, "%.1f%%", Math.min(100f, cpu)));
        memoryValue.setText(formatRss(memoryInfo.availMem / 1024));
    }

    private void applyFilters() {
        if (adapter == null || search == null || onlyKillable == null) return;
        String query = search.getText().toString().trim().toLowerCase(Locale.ROOT);
        boolean killableOnly = onlyKillable.isChecked();
        visibleProcesses.clear();
        for (RootProcess process : allProcesses) {
            if (killableOnly && !process.canKill()) continue;
            String haystack = (process.pid + " " + process.name + " " + process.displayName + " "
                    + (process.packageName == null ? "" : process.packageName) + " "
                    + process.arguments + " " + process.user).toLowerCase(Locale.ROOT);
            if (!query.isEmpty() && !haystack.contains(query)) continue;
            visibleProcesses.add(process);
        }
        Comparator<RootProcess> comparator = sortMode == SortMode.MEMORY
                ? Comparator.comparingLong((RootProcess value) -> value.rssKb).reversed()
                .thenComparing(Comparator.comparingDouble(
                        (RootProcess value) -> value.cpuPercent).reversed())
                : Comparator.comparingDouble((RootProcess value) -> value.cpuPercent).reversed()
                .thenComparing(Comparator.comparingLong(
                        (RootProcess value) -> value.rssKb).reversed());
        visibleProcesses.sort(comparator.thenComparingInt(value -> value.pid));
        adapter.notifyDataSetChanged();
        emptyView.setText(allProcesses.isEmpty() ? "没有读取到进程" : "没有匹配的进程");
        emptyState.setVisibility(visibleProcesses.isEmpty() ? View.VISIBLE : View.GONE);
    }

    private void showDetails(RootProcess process) {
        String type = process.kind == RootProcess.Kind.SCRIPT ? "脚本进程"
                : process.kind == RootProcess.Kind.APP ? "第三方应用" : "系统保护";
        String message = "PID  " + process.pid + "\nPPID  " + process.parentPid
                + "\nUSER  " + process.user + "\nSTATE  " + process.state
                + "\nCPU  " + String.format(Locale.ROOT, "%.1f%%", process.cpuPercent)
                + "\nRSS  " + formatRss(process.rssKb) + "\nTYPE  " + type
                + (process.packageName == null ? "" : "\nPACKAGE  " + process.packageName)
                + "\n\n" + process.arguments;
        new AlertDialog.Builder(this)
                .setTitle(process.displayName)
                .setMessage(message)
                .setPositiveButton("关闭", null)
                .show();
    }

    private void confirmKill(RootProcess process) {
        if (!process.canKill()) {
            Toast.makeText(this, "只允许结束本应用启动的脚本进程", Toast.LENGTH_SHORT).show();
            return;
        }
        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle("结束脚本进程？")
                .setMessage(process.displayName + "\nPID " + process.pid + "  ·  CPU "
                        + String.format(Locale.ROOT, "%.1f%%", process.cpuPercent)
                        + "  ·  " + formatRss(process.rssKb))
                .setNegativeButton("取消", null)
                .setPositiveButton("结束", (ignored, which) -> kill(process))
                .create();
        dialog.setOnShowListener(ignored -> dialog.getButton(AlertDialog.BUTTON_POSITIVE)
                .setTextColor(Ui.DANGER));
        dialog.show();
    }

    private void kill(RootProcess process) {
        Toast.makeText(this, "正在结束 PID " + process.pid, Toast.LENGTH_SHORT).show();
        new Thread(() -> {
            RootProcessRepository.KillResult result = repository.kill(process);
            runOnUiThread(() -> {
                Toast.makeText(this, result.message,
                        result.success ? Toast.LENGTH_SHORT : Toast.LENGTH_LONG).show();
                refreshNow();
            });
        }, "process-kill").start();
    }

    private static String formatRss(long kb) {
        if (kb < 1024) return kb + " KB";
        if (kb < 1024 * 1024) return String.format(Locale.ROOT, "%.1f MB", kb / 1024f);
        return String.format(Locale.ROOT, "%.1f GB", kb / (1024f * 1024f));
    }

    private final class ProcessAdapter extends BaseAdapter {
        @Override public int getCount() { return visibleProcesses.size(); }
        @Override public RootProcess getItem(int position) { return visibleProcesses.get(position); }
        @Override public long getItemId(int position) { return getItem(position).pid; }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            ProcessRow row;
            if (convertView == null) {
                row = new ProcessRow();
                convertView = row.root;
                convertView.setTag(row);
            } else {
                row = (ProcessRow) convertView.getTag();
            }
            RootProcess process = getItem(position);
            int color = process.kind == RootProcess.Kind.SCRIPT ? Ui.ACCENT
                    : process.kind == RootProcess.Kind.APP ? Ui.LAVENDER : Ui.LINE;
            row.rail.setBackgroundColor(color);
            android.graphics.drawable.Drawable appIcon = repository.loadAppIcon(process.packageName);
            if (appIcon == null) row.icon.setImageResource(R.drawable.ic_process_default);
            else row.icon.setImageDrawable(appIcon);
            row.name.setText(process.displayName);
            String type = process.kind == RootProcess.Kind.SCRIPT ? "SCRIPT"
                    : process.kind == RootProcess.Kind.APP ? "APP" : "LOCK";
            String identity = process.packageName == null ? "PID " + process.pid
                    : process.packageName + "  ·  PID " + process.pid;
            row.details.setText(getString(R.string.process_details_format,
                    identity, process.cpuPercent, formatRss(process.rssKb), type));
            row.action.setImageResource(process.canKill() ? R.drawable.ic_stop : R.drawable.ic_lock);
            row.action.setBackground(process.canKill()
                    ? Ui.background(ProcessMonitorActivity.this, Ui.DANGER, 5)
                    : Ui.background(ProcessMonitorActivity.this, Ui.SURFACE_HIGH, 5));
            Ui.setIconTint(row.action,
                    process.canKill() ? android.graphics.Color.WHITE : Ui.MUTED);
            Ui.setEnabled(row.action, process.canKill());
            row.action.setOnClickListener(view -> confirmKill(process));
            row.root.setOnClickListener(view -> showDetails(process));
            return convertView;
        }
    }

    private final class ProcessRow {
        final LinearLayout root = new LinearLayout(ProcessMonitorActivity.this);
        final View rail = new View(ProcessMonitorActivity.this);
        final ImageView icon = new ImageView(ProcessMonitorActivity.this);
        final TextView name;
        final TextView details;
        final ImageButton action;

        ProcessRow() {
            root.setGravity(Gravity.CENTER_VERTICAL);
            root.setPadding(0, Ui.dp(ProcessMonitorActivity.this, 4),
                    Ui.dp(ProcessMonitorActivity.this, 12), Ui.dp(ProcessMonitorActivity.this, 4));
            root.setBackgroundColor(Ui.SURFACE);
            root.addView(rail, new LinearLayout.LayoutParams(Ui.dp(ProcessMonitorActivity.this, 3), -1));
            icon.setScaleType(ImageView.ScaleType.FIT_CENTER);
            icon.setPadding(Ui.dp(ProcessMonitorActivity.this, 5), Ui.dp(ProcessMonitorActivity.this, 5),
                    Ui.dp(ProcessMonitorActivity.this, 5), Ui.dp(ProcessMonitorActivity.this, 5));
            icon.setBackground(Ui.background(ProcessMonitorActivity.this, Ui.SURFACE_HIGH, 6));
            LinearLayout.LayoutParams iconParams = new LinearLayout.LayoutParams(
                    Ui.dp(ProcessMonitorActivity.this, 40), Ui.dp(ProcessMonitorActivity.this, 40));
            iconParams.leftMargin = Ui.dp(ProcessMonitorActivity.this, 10);
            root.addView(icon, iconParams);
            LinearLayout text = new LinearLayout(ProcessMonitorActivity.this);
            text.setOrientation(LinearLayout.VERTICAL);
            text.setGravity(Gravity.CENTER_VERTICAL);
            text.setPadding(Ui.dp(ProcessMonitorActivity.this, 10), 0,
                    Ui.dp(ProcessMonitorActivity.this, 8), 0);
            name = Ui.text(ProcessMonitorActivity.this, "", 13, Ui.TEXT, Typeface.BOLD);
            name.setSingleLine(true);
            name.setEllipsize(android.text.TextUtils.TruncateAt.END);
            details = Ui.text(ProcessMonitorActivity.this, "", 10, Ui.MUTED, Typeface.NORMAL);
            details.setSingleLine(true);
            details.setTypeface(Typeface.create("monospace", Typeface.NORMAL));
            text.addView(name, new LinearLayout.LayoutParams(-1, Ui.dp(ProcessMonitorActivity.this, 27)));
            text.addView(details, new LinearLayout.LayoutParams(-1, Ui.dp(ProcessMonitorActivity.this, 22)));
            root.addView(text, new LinearLayout.LayoutParams(0, Ui.dp(ProcessMonitorActivity.this, 58), 1));
            action = Ui.iconButton(ProcessMonitorActivity.this, R.drawable.ic_lock,
                    getString(R.string.end_process));
            root.addView(action, new LinearLayout.LayoutParams(
                    Ui.dp(ProcessMonitorActivity.this, 44), Ui.dp(ProcessMonitorActivity.this, 44)));
            root.setMinimumHeight(Ui.dp(ProcessMonitorActivity.this, 66));
        }
    }
}
