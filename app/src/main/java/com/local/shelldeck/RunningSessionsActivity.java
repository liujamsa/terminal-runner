package com.local.shelldeck;

import android.app.Activity;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.res.ColorStateList;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Bundle;
import android.os.IBinder;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.FrameLayout;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public final class RunningSessionsActivity extends Activity {
    private final List<ScriptExecutionService.SessionSummary> sessions = new ArrayList<>();
    private final ScriptExecutionService.SessionListListener sessionListListener =
            this::refreshSessions;
    private ScriptExecutionService service;
    private SessionAdapter adapter;
    private TextView countView;
    private TextView emptyView;
    private View emptyState;
    private boolean bound;

    private final ServiceConnection connection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder binder) {
            service = ((ScriptExecutionService.LocalBinder) binder).getService();
            bound = true;
            service.addSessionListListener(sessionListListener);
            refreshSessions();
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            service = null;
            bound = false;
            showSessions(new ArrayList<>());
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Ui.applyLightSystemBars(this);
        buildContent();
    }

    @Override
    protected void onStart() {
        super.onStart();
        try {
            boolean requested = bindService(new Intent(this, ScriptExecutionService.class),
                    connection, 0);
            if (!requested) showSessions(new ArrayList<>());
        } catch (RuntimeException ignored) {
            showSessions(new ArrayList<>());
        }
    }

    @Override
    protected void onStop() {
        if (bound) {
            if (service != null) service.removeSessionListListener(sessionListListener);
            unbindService(connection);
            bound = false;
        }
        service = null;
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
        toolbar.setPadding(Ui.dp(this, 18), Ui.dp(this, 7), Ui.dp(this, 18), Ui.dp(this, 5));
        LinearLayout identity = new LinearLayout(this);
        identity.setOrientation(LinearLayout.VERTICAL);
        TextView title = Ui.text(this, "运行通道", 21, Ui.TEXT, Typeface.BOLD);
        title.setTypeface(Typeface.create("sans-serif-condensed", Typeface.BOLD));
        TextView subtitle = Ui.text(this, "LIVE SESSIONS  /  LOCAL", 10, Ui.MUTED, Typeface.BOLD);
        subtitle.setTypeface(Typeface.create("monospace", Typeface.BOLD));
        identity.addView(title, new LinearLayout.LayoutParams(-1, Ui.dp(this, 30)));
        identity.addView(subtitle, new LinearLayout.LayoutParams(-1, Ui.dp(this, 19)));
        toolbar.addView(identity, new LinearLayout.LayoutParams(0, Ui.dp(this, 52), 1));
        countView = Ui.text(this, "0", 12, android.graphics.Color.WHITE, Typeface.BOLD);
        countView.setGravity(Gravity.CENTER);
        countView.setTypeface(Typeface.create("monospace", Typeface.BOLD));
        countView.setBackground(Ui.background(this, Ui.BLUE, 6));
        toolbar.addView(countView, new LinearLayout.LayoutParams(Ui.dp(this, 42), Ui.dp(this, 32)));
        root.addView(toolbar, new LinearLayout.LayoutParams(-1, Ui.dp(this, 64)));

        LinearLayout columns = new LinearLayout(this);
        columns.setGravity(Gravity.CENTER_VERTICAL);
        columns.setPadding(Ui.dp(this, 18), 0, Ui.dp(this, 14), 0);
        TextView channelColumn = Ui.text(this, "CHANNEL", 9, Ui.MUTED, Typeface.BOLD);
        channelColumn.setTypeface(Typeface.create("monospace", Typeface.BOLD));
        columns.addView(channelColumn, new LinearLayout.LayoutParams(0, -1, 1));
        TextView actionColumn = Ui.text(this, "OPEN  STOP", 9, Ui.MUTED, Typeface.BOLD);
        actionColumn.setGravity(Gravity.CENTER);
        actionColumn.setTypeface(Typeface.create("monospace", Typeface.BOLD));
        columns.addView(actionColumn, new LinearLayout.LayoutParams(Ui.dp(this, 104), -1));
        root.addView(columns, new LinearLayout.LayoutParams(-1, Ui.dp(this, 32)));

        FrameLayout content = new FrameLayout(this);
        ListView list = new ListView(this);
        list.setDivider(null);
        list.setSelector(android.R.color.transparent);
        list.setVerticalScrollBarEnabled(false);
        adapter = new SessionAdapter();
        list.setAdapter(adapter);
        content.addView(list, new FrameLayout.LayoutParams(-1, -1));

        LinearLayout empty = new LinearLayout(this);
        empty.setOrientation(LinearLayout.VERTICAL);
        empty.setGravity(Gravity.CENTER);
        ImageView icon = new ImageView(this);
        icon.setImageResource(R.drawable.ic_sessions);
        icon.setImageTintList(ColorStateList.valueOf(Ui.BLUE));
        icon.setBackground(Ui.background(this, Ui.SURFACE_HIGH, 8));
        icon.setPadding(Ui.dp(this, 14), Ui.dp(this, 14), Ui.dp(this, 14), Ui.dp(this, 14));
        empty.addView(icon, new LinearLayout.LayoutParams(Ui.dp(this, 58), Ui.dp(this, 58)));
        emptyView = Ui.text(this, "当前没有运行中的通道\n启动脚本后会显示在这里",
                13, Ui.MUTED, Typeface.NORMAL);
        emptyView.setGravity(Gravity.CENTER);
        emptyView.setLineSpacing(Ui.dp(this, 5), 1f);
        LinearLayout.LayoutParams emptyText = new LinearLayout.LayoutParams(-1, Ui.dp(this, 58));
        emptyText.topMargin = Ui.dp(this, 8);
        empty.addView(emptyView, emptyText);
        emptyState = empty;
        content.addView(empty, new FrameLayout.LayoutParams(-1, -1));
        root.addView(content, new LinearLayout.LayoutParams(-1, 0, 1));
        root.addView(AppTabs.create(this, AppTabs.CHANNELS),
                new LinearLayout.LayoutParams(-1, Ui.dp(this, 61)));

        setContentView(root);
        root.requestApplyInsets();
        showSessions(new ArrayList<>());
    }

    private void refreshSessions() {
        if (service != null) showSessions(service.activeSessions());
    }

    private void showSessions(List<ScriptExecutionService.SessionSummary> values) {
        sessions.clear();
        sessions.addAll(values);
        countView.setText(String.valueOf(sessions.size()));
        adapter.notifyDataSetChanged();
        emptyState.setVisibility(sessions.isEmpty() ? View.VISIBLE : View.GONE);
    }

    private void openConsole(ScriptExecutionService.SessionSummary session) {
        Intent terminal = new Intent(this, TerminalActivity.class);
        terminal.setAction(Intent.ACTION_VIEW);
        terminal.setData(Uri.parse("shelldeck://session/" + session.id));
        terminal.putExtra(TerminalActivity.EXTRA_SESSION_ID, session.id);
        terminal.putExtra(TerminalActivity.EXTRA_SCRIPT_NAME, session.name);
        startActivity(terminal);
    }

    private void closeChannel(ScriptExecutionService.SessionSummary session) {
        if (service == null || session.closing) return;
        service.closeChannel(session.id);
        Toast.makeText(this, "正在结束 " + session.name, Toast.LENGTH_SHORT).show();
    }

    private static String stateLabel(ScriptExecutionService.SessionSummary session) {
        if (session.closing) return "CLOSING";
        return session.state == ScriptExecutionService.STATE_STARTING ? "STARTING" : "RUNNING";
    }

    private static String elapsed(long startedAt) {
        long seconds = Math.max(0, (System.currentTimeMillis() - startedAt) / 1000);
        if (seconds < 60) return seconds + "s";
        long minutes = seconds / 60;
        if (minutes < 60) return minutes + "m";
        return String.format(Locale.ROOT, "%dh%02dm", minutes / 60, minutes % 60);
    }

    private final class SessionAdapter extends BaseAdapter {
        @Override public int getCount() { return sessions.size(); }
        @Override public ScriptExecutionService.SessionSummary getItem(int position) {
            return sessions.get(position);
        }
        @Override public long getItemId(int position) { return getItem(position).id.hashCode(); }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            SessionRow row;
            if (convertView == null) {
                row = new SessionRow();
                convertView = row.root;
                convertView.setTag(row);
            } else {
                row = (SessionRow) convertView.getTag();
            }
            ScriptExecutionService.SessionSummary session = getItem(position);
            row.name.setText(session.name);
            row.details.setText(getString(R.string.session_details_format,
                    session.root ? "ROOT" : "SHELL", stateLabel(session),
                    elapsed(session.startedAt),
                    session.id.substring(0, Math.min(8, session.id.length()))));
            row.open.setOnClickListener(view -> openConsole(session));
            row.stop.setOnClickListener(view -> closeChannel(session));
            Ui.setEnabled(row.stop, !session.closing);
            row.root.setOnClickListener(view -> openConsole(session));
            return convertView;
        }
    }

    private final class SessionRow {
        final LinearLayout root = new LinearLayout(RunningSessionsActivity.this);
        final TextView name;
        final TextView details;
        final ImageButton open;
        final ImageButton stop;

        SessionRow() {
            root.setGravity(Gravity.CENTER_VERTICAL);
            root.setPadding(0, Ui.dp(RunningSessionsActivity.this, 4),
                    Ui.dp(RunningSessionsActivity.this, 12), Ui.dp(RunningSessionsActivity.this, 4));
            root.setBackgroundColor(Ui.SURFACE);
            View rail = new View(RunningSessionsActivity.this);
            rail.setBackgroundColor(Ui.BLUE);
            root.addView(rail, new LinearLayout.LayoutParams(Ui.dp(RunningSessionsActivity.this, 3), -1));
            ImageView icon = new ImageView(RunningSessionsActivity.this);
            icon.setImageResource(R.drawable.ic_sessions);
            icon.setImageTintList(ColorStateList.valueOf(Ui.BLUE));
            icon.setBackground(Ui.background(RunningSessionsActivity.this, Ui.SURFACE_HIGH, 6));
            icon.setPadding(Ui.dp(RunningSessionsActivity.this, 7), Ui.dp(RunningSessionsActivity.this, 7),
                    Ui.dp(RunningSessionsActivity.this, 7), Ui.dp(RunningSessionsActivity.this, 7));
            LinearLayout.LayoutParams iconParams = new LinearLayout.LayoutParams(
                    Ui.dp(RunningSessionsActivity.this, 40), Ui.dp(RunningSessionsActivity.this, 40));
            iconParams.leftMargin = Ui.dp(RunningSessionsActivity.this, 10);
            root.addView(icon, iconParams);

            LinearLayout text = new LinearLayout(RunningSessionsActivity.this);
            text.setOrientation(LinearLayout.VERTICAL);
            text.setGravity(Gravity.CENTER_VERTICAL);
            text.setPadding(Ui.dp(RunningSessionsActivity.this, 10), 0,
                    Ui.dp(RunningSessionsActivity.this, 6), 0);
            name = Ui.text(RunningSessionsActivity.this, "", 13, Ui.TEXT, Typeface.BOLD);
            name.setSingleLine(true);
            name.setEllipsize(android.text.TextUtils.TruncateAt.END);
            details = Ui.text(RunningSessionsActivity.this, "", 10, Ui.MUTED, Typeface.NORMAL);
            details.setSingleLine(true);
            details.setTypeface(Typeface.create("monospace", Typeface.NORMAL));
            text.addView(name, new LinearLayout.LayoutParams(-1, Ui.dp(RunningSessionsActivity.this, 27)));
            text.addView(details, new LinearLayout.LayoutParams(-1, Ui.dp(RunningSessionsActivity.this, 22)));
            root.addView(text, new LinearLayout.LayoutParams(0, Ui.dp(RunningSessionsActivity.this, 58), 1));

            open = Ui.iconButton(RunningSessionsActivity.this, R.drawable.ic_terminal, "打开控制台");
            open.setBackground(Ui.background(RunningSessionsActivity.this, Ui.SURFACE_HIGH, 5));
            Ui.setIconTint(open, Ui.BLUE);
            root.addView(open, new LinearLayout.LayoutParams(
                    Ui.dp(RunningSessionsActivity.this, 44), Ui.dp(RunningSessionsActivity.this, 44)));
            stop = Ui.iconButton(RunningSessionsActivity.this, R.drawable.ic_stop, "结束当前通道");
            stop.setBackground(Ui.background(RunningSessionsActivity.this, Ui.DANGER, 5));
            Ui.setIconTint(stop, android.graphics.Color.WHITE);
            LinearLayout.LayoutParams stopParams = new LinearLayout.LayoutParams(
                    Ui.dp(RunningSessionsActivity.this, 44), Ui.dp(RunningSessionsActivity.this, 44));
            stopParams.leftMargin = Ui.dp(RunningSessionsActivity.this, 6);
            root.addView(stop, stopParams);
            root.setMinimumHeight(Ui.dp(RunningSessionsActivity.this, 66));
        }
    }
}
