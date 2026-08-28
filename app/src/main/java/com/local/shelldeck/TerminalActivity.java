package com.local.shelldeck;

import android.app.Activity;
import android.app.ActivityManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.graphics.Typeface;
import android.os.Bundle;
import android.os.IBinder;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.inputmethod.EditorInfo;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.UUID;

public final class TerminalActivity extends Activity implements ScriptExecutionService.Listener {
    private static final int MAX_OUTPUT_CHARS = 300_000;
    private static final long OUTPUT_FLUSH_DELAY_MS = 50;
    static final String EXTRA_SESSION_ID = "session_id";
    static final String EXTRA_SCRIPT_PATH = "script_path";
    static final String EXTRA_WORKING_DIRECTORY = "working_directory";
    static final String EXTRA_SCRIPT_NAME = "script_name";
    static final String EXTRA_ROOT = "root";
    static final String EXTRA_SCRIPT_ID = "script_id";
    static final String EXTRA_SCRIPT_HASH = "script_hash";
    static final String EXTRA_INPUT_MEMORY_ENABLED = "input_memory_enabled";
    static final String EXTRA_AUTO_INPUTS = "auto_inputs";
    static final String EXTRA_AUTO_PROMPTS = "auto_prompts";

    private ScriptExecutionService service;
    private boolean bound;
    private TextView output;
    private TextView stateLabel;
    private ScrollView scroll;
    private EditText input;
    private ImageButton send;
    private ImageButton stop;
    private String scriptName;
    private String scriptId;
    private String scriptHash;
    private String sessionId;
    private boolean inputMemoryEnabled;
    private ArrayList<String> autoInputs;
    private ArrayList<String> autoPrompts;
    private final StringBuilder pendingOutput = new StringBuilder();
    private boolean outputFlushScheduled;
    private final Runnable outputFlushRunnable = this::flushPendingOutput;
    private final Runnable scrollToBottomRunnable = () -> scroll.scrollTo(
            0, Math.max(0, output.getHeight() - scroll.getHeight()));

    private final ServiceConnection connection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder binder) {
            service = ((ScriptExecutionService.LocalBinder) binder).getService();
            bound = true;
            output.setText(service.addListener(sessionId, TerminalActivity.this));
            updateControls(service.getStateValue(sessionId));
            scrollToBottom();
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            bound = false;
            service = null;
            updateState(ScriptExecutionService.STATE_FAILED, null);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Ui.applyLightSystemBars(this);
        sessionId = getIntent().getStringExtra(EXTRA_SESSION_ID);
        if (sessionId == null || sessionId.isEmpty()) sessionId = UUID.randomUUID().toString();
        scriptName = getIntent().getStringExtra(EXTRA_SCRIPT_NAME);
        if (scriptName == null) scriptName = "终端";
        setTaskDescription(new ActivityManager.TaskDescription(scriptName));
        scriptId = getIntent().getStringExtra(EXTRA_SCRIPT_ID);
        scriptHash = getIntent().getStringExtra(EXTRA_SCRIPT_HASH);
        boolean root = getIntent().getBooleanExtra(EXTRA_ROOT, false);
        inputMemoryEnabled = getIntent().getBooleanExtra(EXTRA_INPUT_MEMORY_ENABLED, false);
        autoInputs = getIntent().getStringArrayListExtra(EXTRA_AUTO_INPUTS);
        if (autoInputs == null) autoInputs = new ArrayList<>();
        autoPrompts = getIntent().getStringArrayListExtra(EXTRA_AUTO_PROMPTS);
        if (autoPrompts == null) autoPrompts = new ArrayList<>();
        buildContent();

        String path = getIntent().getStringExtra(EXTRA_SCRIPT_PATH);
        if (savedInstanceState == null && path != null) {
            Intent run = new Intent(this, ScriptExecutionService.class);
            run.setAction(ScriptExecutionService.ACTION_RUN);
            run.putExtra(ScriptExecutionService.EXTRA_SESSION_ID, sessionId);
            run.putExtra(ScriptExecutionService.EXTRA_PATH, path);
            run.putExtra(ScriptExecutionService.EXTRA_WORKING_DIRECTORY,
                    getIntent().getStringExtra(EXTRA_WORKING_DIRECTORY));
            run.putExtra(ScriptExecutionService.EXTRA_NAME, scriptName);
            run.putExtra(ScriptExecutionService.EXTRA_ROOT, root);
            run.putExtra(ScriptExecutionService.EXTRA_SCRIPT_ID, scriptId);
            run.putExtra(ScriptExecutionService.EXTRA_SCRIPT_HASH, scriptHash);
            run.putExtra(ScriptExecutionService.EXTRA_INPUT_MEMORY_ENABLED, inputMemoryEnabled);
            run.putStringArrayListExtra(ScriptExecutionService.EXTRA_AUTO_INPUTS, autoInputs);
            run.putStringArrayListExtra(ScriptExecutionService.EXTRA_AUTO_PROMPTS, autoPrompts);
            startForegroundService(run);
        }
    }

    @Override
    protected void onStart() {
        super.onStart();
        bindService(new Intent(this, ScriptExecutionService.class), connection, Context.BIND_AUTO_CREATE);
    }

    @Override
    protected void onStop() {
        flushPendingOutput();
        if (bound) {
            service.removeListener(sessionId, this);
            unbindService(connection);
            bound = false;
        }
        super.onStop();
    }

    private void buildContent() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Ui.INK);
        root.setOnApplyWindowInsetsListener((view, insets) -> {
            view.setPadding(0, insets.getSystemWindowInsetTop(), 0, insets.getSystemWindowInsetBottom());
            return insets;
        });

        LinearLayout toolbar = new LinearLayout(this);
        toolbar.setOrientation(LinearLayout.HORIZONTAL);
        toolbar.setGravity(Gravity.CENTER_VERTICAL);
        toolbar.setPadding(Ui.dp(this, 10), Ui.dp(this, 8), Ui.dp(this, 16), Ui.dp(this, 6));
        ImageButton back = Ui.iconButton(this, R.drawable.ic_back, getString(R.string.go_back));
        back.setBackgroundColor(android.graphics.Color.TRANSPARENT);
        back.setOnClickListener(view -> finish());
        toolbar.addView(back, new LinearLayout.LayoutParams(Ui.dp(this, 48), Ui.dp(this, 48)));

        LinearLayout titleGroup = new LinearLayout(this);
        titleGroup.setOrientation(LinearLayout.VERTICAL);
        TextView title = Ui.text(this, scriptName, 17, Ui.TEXT, Typeface.BOLD);
        title.setSingleLine(true);
        title.setEllipsize(android.text.TextUtils.TruncateAt.MIDDLE);
        stateLabel = Ui.text(this, "正在连接", 11, Ui.AMBER, Typeface.BOLD);
        stateLabel.setTypeface(Typeface.create("monospace", Typeface.BOLD));
        titleGroup.addView(title, new LinearLayout.LayoutParams(-1, Ui.dp(this, 27)));
        titleGroup.addView(stateLabel, new LinearLayout.LayoutParams(-1, Ui.dp(this, 21)));
        toolbar.addView(titleGroup, new LinearLayout.LayoutParams(0, Ui.dp(this, 52), 1));
        root.addView(toolbar, new LinearLayout.LayoutParams(-1, Ui.dp(this, 66)));

        LinearLayout terminalHeader = new LinearLayout(this);
        terminalHeader.setGravity(Gravity.CENTER_VERTICAL);
        terminalHeader.setPadding(Ui.dp(this, 18), 0, Ui.dp(this, 18), 0);
        terminalHeader.setBackgroundColor(Ui.SURFACE);
        TextView prompt = Ui.text(this, ">_  LIVE OUTPUT", 11, Ui.ACCENT, Typeface.BOLD);
        prompt.setTypeface(Typeface.create("monospace", Typeface.BOLD));
        terminalHeader.addView(prompt, new LinearLayout.LayoutParams(0, -1, 1));
        String memoryStatus = !inputMemoryEnabled ? "记忆：关"
                : autoInputs.isEmpty() ? "记忆：待学习" : "自动：" + autoInputs.size() + "项";
        TextView encoding = Ui.text(this, memoryStatus, 10, Ui.MUTED, Typeface.BOLD);
        encoding.setTypeface(Typeface.create("monospace", Typeface.NORMAL));
        terminalHeader.addView(encoding, new LinearLayout.LayoutParams(-2, -1));
        root.addView(terminalHeader, new LinearLayout.LayoutParams(-1, Ui.dp(this, 38)));

        scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setBackgroundColor(Ui.INK);
        output = Ui.text(this, "", 13, Ui.TEXT, Typeface.NORMAL);
        output.setTypeface(Typeface.create("monospace", Typeface.NORMAL));
        output.setGravity(Gravity.TOP | Gravity.START);
        output.setTextIsSelectable(true);
        output.setLineSpacing(Ui.dp(this, 3), 1f);
        output.setPadding(Ui.dp(this, 18), Ui.dp(this, 16), Ui.dp(this, 18), Ui.dp(this, 20));
        scroll.addView(output, new ScrollView.LayoutParams(-1, -2));
        root.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));

        LinearLayout composer = new LinearLayout(this);
        composer.setGravity(Gravity.CENTER_VERTICAL);
        composer.setPadding(Ui.dp(this, 12), Ui.dp(this, 10), Ui.dp(this, 12), Ui.dp(this, 10));
        composer.setBackgroundColor(Ui.SURFACE);

        input = new EditText(this);
        input.setSingleLine(true);
        input.setTextSize(14);
        input.setTextColor(Ui.TEXT);
        input.setHintTextColor(Ui.MUTED);
        input.setHint("输入内容");
        input.setTypeface(Typeface.create("monospace", Typeface.NORMAL));
        input.setPadding(Ui.dp(this, 13), 0, Ui.dp(this, 13), 0);
        input.setBackground(Ui.outlinedBackground(this, Ui.SURFACE_HIGH, Ui.LINE, 6));
        input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        input.setImeOptions(EditorInfo.IME_ACTION_SEND);
        input.setOnEditorActionListener((view, actionId, event) -> {
            if (actionId == EditorInfo.IME_ACTION_SEND) {
                submitInput();
                return true;
            }
            return false;
        });
        composer.addView(input, new LinearLayout.LayoutParams(0, Ui.dp(this, 48), 1));

        send = Ui.iconButton(this, R.drawable.ic_send, getString(R.string.send_input));
        send.setBackground(Ui.background(this, Ui.ACCENT, 6));
        Ui.setIconTint(send, android.graphics.Color.WHITE);
        send.setOnClickListener(view -> submitInput());
        LinearLayout.LayoutParams sendParams = new LinearLayout.LayoutParams(Ui.dp(this, 48), Ui.dp(this, 48));
        sendParams.leftMargin = Ui.dp(this, 8);
        composer.addView(send, sendParams);

        stop = Ui.iconButton(this, R.drawable.ic_stop, getString(R.string.stop_script));
        stop.setBackground(Ui.background(this, Ui.DANGER, 6));
        Ui.setIconTint(stop, android.graphics.Color.WHITE);
        stop.setOnClickListener(view -> {
            if (service != null) {
                Ui.setEnabled(stop, false);
                stateLabel.setText("正在结束");
                stateLabel.setTextColor(Ui.MUTED);
                service.closeChannel(sessionId);
                finish();
            }
        });
        LinearLayout.LayoutParams stopParams = new LinearLayout.LayoutParams(Ui.dp(this, 48), Ui.dp(this, 48));
        stopParams.leftMargin = Ui.dp(this, 8);
        composer.addView(stop, stopParams);
        root.addView(composer, new LinearLayout.LayoutParams(-1, Ui.dp(this, 68)));

        setContentView(root);
        root.requestApplyInsets();
        updateControls(ScriptExecutionService.STATE_STARTING);
    }

    private void submitInput() {
        String value = input.getText().toString();
        if (service == null) return;
        try {
            service.sendInput(sessionId, value);
            input.setText("");
            input.requestFocus();
        } catch (Exception error) {
            Toast.makeText(this, error.getMessage(), Toast.LENGTH_SHORT).show();
        }
    }

    @Override
    public void onOutput(String chunk) {
        pendingOutput.append(chunk);
        if (!outputFlushScheduled) {
            outputFlushScheduled = true;
            output.postDelayed(outputFlushRunnable, OUTPUT_FLUSH_DELAY_MS);
        }
    }

    private void flushPendingOutput() {
        output.removeCallbacks(outputFlushRunnable);
        outputFlushScheduled = false;
        if (pendingOutput.length() == 0) return;
        output.append(pendingOutput);
        pendingOutput.setLength(0);
        if (output.length() > MAX_OUTPUT_CHARS) {
            output.setText(output.getText().subSequence(
                    output.length() - MAX_OUTPUT_CHARS, output.length()));
        }
        scrollToBottom();
    }

    @Override
    public void onStateChanged(int state, Integer exitCode) {
        updateState(state, exitCode);
        updateControls(state);
    }

    private void updateState(int state, Integer exitCode) {
        switch (state) {
            case ScriptExecutionService.STATE_STARTING:
                stateLabel.setText(R.string.state_starting);
                stateLabel.setTextColor(Ui.AMBER);
                break;
            case ScriptExecutionService.STATE_RUNNING:
                stateLabel.setText(R.string.state_running);
                stateLabel.setTextColor(Ui.ACCENT);
                break;
            case ScriptExecutionService.STATE_FINISHED:
                stateLabel.setText(R.string.state_exit_zero);
                stateLabel.setTextColor(Ui.ACCENT);
                break;
            case ScriptExecutionService.STATE_STOPPED:
                stateLabel.setText(R.string.state_stopped);
                stateLabel.setTextColor(Ui.AMBER);
                break;
            case ScriptExecutionService.STATE_FAILED:
                stateLabel.setText(exitCode == null ? "FAILED" : "EXIT " + exitCode);
                stateLabel.setTextColor(Ui.DANGER);
                break;
            default:
                stateLabel.setText(R.string.state_idle);
                stateLabel.setTextColor(Ui.MUTED);
        }
    }

    private void updateControls(int state) {
        boolean running = state == ScriptExecutionService.STATE_STARTING || state == ScriptExecutionService.STATE_RUNNING;
        Ui.setEnabled(input, running);
        Ui.setEnabled(send, running);
        Ui.setEnabled(stop, running);
    }

    private void scrollToBottom() {
        scroll.removeCallbacks(scrollToBottomRunnable);
        scroll.post(scrollToBottomRunnable);
    }
}
