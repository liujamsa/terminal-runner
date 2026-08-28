package com.local.shelldeck;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.res.ColorStateList;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.view.Gravity;
import android.view.HapticFeedbackConstants;
import android.view.View;
import android.view.ViewGroup;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.recyclerview.widget.DefaultItemAnimator;
import androidx.recyclerview.widget.DiffUtil;
import androidx.recyclerview.widget.ItemTouchHelper;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

public final class MainActivity extends Activity {
    private static final int OPEN_SCRIPT_MT = 1001;
    private static final int NOTIFICATION_PERMISSION = 1002;
    private static final int OPEN_SCRIPT_DOCUMENT = 1004;
    private static final int UPDATE_SCRIPT_MT = 1005;
    private static final int UPDATE_SCRIPT_DOCUMENT = 1006;
    private static final String PREFS = "settings";
    private static final String PREF_ROOT = "root_mode";
    private static final String STATE_UPDATE_SCRIPT_ID = "update_script_id";

    private final List<ScriptItem> scripts = new ArrayList<>();
    private ScriptStore store;
    private InputMemoryStore inputMemoryStore;
    private ScriptAdapter adapter;
    private TextView countView;
    private TextView emptyView;
    private View emptyState;
    private RecyclerView scriptList;
    private ItemTouchHelper itemTouchHelper;
    private boolean reorderDirty;
    private ModeOption normalMode;
    private ModeOption rootMode;
    private boolean rootEnabled;
    private boolean changingRoot;
    private String updateScriptId;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Ui.applyLightSystemBars(this);
        store = new ScriptStore(this);
        inputMemoryStore = new InputMemoryStore(this);
        if (savedInstanceState != null) {
            updateScriptId = savedInstanceState.getString(STATE_UPDATE_SCRIPT_ID);
        }
        buildContent();
        reloadScripts(false);
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

        LinearLayout page = new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);
        page.setPadding(Ui.dp(this, 18), Ui.dp(this, 14),
                Ui.dp(this, 18), Ui.dp(this, 10));

        LinearLayout top = new LinearLayout(this);
        top.setOrientation(LinearLayout.HORIZONTAL);
        top.setGravity(Gravity.CENTER_VERTICAL);

        rootEnabled = getSharedPreferences(PREFS, MODE_PRIVATE)
                .getBoolean(PREF_ROOT, false);

        LinearLayout identity = new LinearLayout(this);
        identity.setOrientation(LinearLayout.VERTICAL);
        TextView title = Ui.text(this, "脚本终端", 24, Ui.TEXT, Typeface.BOLD);
        title.setTypeface(Typeface.create("sans-serif-condensed", Typeface.BOLD));
        TextView subtitle = Ui.text(this, "SHELL DECK  /  LOCAL", 10, Ui.MUTED, Typeface.BOLD);
        subtitle.setTypeface(Typeface.create("monospace", Typeface.BOLD));
        identity.addView(title, new LinearLayout.LayoutParams(-1, Ui.dp(this, 32)));
        identity.addView(subtitle, new LinearLayout.LayoutParams(-1, Ui.dp(this, 20)));
        top.addView(identity, new LinearLayout.LayoutParams(0, Ui.dp(this, 56), 1));

        LinearLayout modeControl = new LinearLayout(this);
        modeControl.setPadding(Ui.dp(this, 2), Ui.dp(this, 2), Ui.dp(this, 2), Ui.dp(this, 2));
        modeControl.setBackground(Ui.outlinedBackground(this, Ui.SURFACE, Ui.LINE, 6));
        normalMode = modeOption("普通", R.drawable.ic_terminal, false);
        rootMode = modeOption("Root", R.drawable.ic_lock, true);
        modeControl.addView(normalMode.root, new LinearLayout.LayoutParams(Ui.dp(this, 76), -1));
        modeControl.addView(rootMode.root, new LinearLayout.LayoutParams(Ui.dp(this, 76), -1));
        top.addView(modeControl, new LinearLayout.LayoutParams(Ui.dp(this, 156), Ui.dp(this, 40)));
        page.addView(top, new LinearLayout.LayoutParams(-1, Ui.dp(this, 64)));
        updateRootControls(false);

        LinearLayout section = new LinearLayout(this);
        section.setOrientation(LinearLayout.HORIZONTAL);
        section.setGravity(Gravity.CENTER_VERTICAL);
        section.setPadding(0, Ui.dp(this, 12), 0, Ui.dp(this, 6));
        TextView sectionTitle = Ui.text(this, "已保存脚本", 15, Ui.TEXT, Typeface.BOLD);
        section.addView(sectionTitle, new LinearLayout.LayoutParams(0, Ui.dp(this, 48), 1));
        countView = Ui.text(this, "0", 12, Ui.MUTED, Typeface.BOLD);
        countView.setGravity(Gravity.CENTER);
        section.addView(countView, new LinearLayout.LayoutParams(Ui.dp(this, 36), Ui.dp(this, 48)));
        ImageButton refresh = Ui.iconButton(this, R.drawable.ic_refresh, getString(R.string.refresh_scripts));
        refresh.setOnClickListener(view -> reloadScripts(true));
        section.addView(refresh, marginParams(48, 48, 6));
        ImageButton add = Ui.iconButton(this, R.drawable.ic_add, getString(R.string.add_script));
        add.setBackground(Ui.background(this, Ui.BLUE, 6));
        Ui.setIconTint(add, android.graphics.Color.WHITE);
        add.setOnClickListener(view -> openScriptPicker());
        section.addView(add, marginParams(48, 48, 6));
        page.addView(section, new LinearLayout.LayoutParams(-1, Ui.dp(this, 66)));

        FrameLayout content = new FrameLayout(this);
        scriptList = new RecyclerView(this);
        scriptList.setLayoutManager(new LinearLayoutManager(this));
        scriptList.setHasFixedSize(true);
        scriptList.setVerticalScrollBarEnabled(false);
        scriptList.setOverScrollMode(View.OVER_SCROLL_NEVER);
        DefaultItemAnimator itemAnimator = new DefaultItemAnimator();
        itemAnimator.setMoveDuration(190);
        itemAnimator.setChangeDuration(140);
        scriptList.setItemAnimator(itemAnimator);
        adapter = new ScriptAdapter();
        scriptList.setAdapter(adapter);
        itemTouchHelper = new ItemTouchHelper(new ScriptDragCallback());
        itemTouchHelper.attachToRecyclerView(scriptList);
        content.addView(scriptList, new FrameLayout.LayoutParams(-1, -1));

        LinearLayout empty = new LinearLayout(this);
        empty.setOrientation(LinearLayout.VERTICAL);
        empty.setGravity(Gravity.CENTER);
        ImageView emptyIcon = new ImageView(this);
        emptyIcon.setImageResource(R.drawable.ic_terminal);
        emptyIcon.setImageTintList(android.content.res.ColorStateList.valueOf(Ui.BLUE));
        emptyIcon.setBackground(Ui.background(this, Ui.BLUE_TINT, 8));
        emptyIcon.setPadding(Ui.dp(this, 14), Ui.dp(this, 14), Ui.dp(this, 14), Ui.dp(this, 14));
        empty.addView(emptyIcon, new LinearLayout.LayoutParams(Ui.dp(this, 58), Ui.dp(this, 58)));
        emptyView = Ui.text(this, "还没有保存脚本\n点击右上角 + 添加一个", 14, Ui.MUTED, Typeface.NORMAL);
        emptyView.setGravity(Gravity.CENTER);
        emptyView.setLineSpacing(Ui.dp(this, 5), 1f);
        LinearLayout.LayoutParams emptyTextParams = new LinearLayout.LayoutParams(-1, Ui.dp(this, 60));
        emptyTextParams.topMargin = Ui.dp(this, 10);
        empty.addView(emptyView, emptyTextParams);
        emptyState = empty;
        content.addView(empty, new FrameLayout.LayoutParams(-1, -1));
        page.addView(content, new LinearLayout.LayoutParams(-1, 0, 1));
        root.addView(page, new LinearLayout.LayoutParams(-1, 0, 1));
        root.addView(AppTabs.create(this, AppTabs.SCRIPTS),
                new LinearLayout.LayoutParams(-1, Ui.dp(this, 61)));

        setContentView(root);
        root.requestApplyInsets();
    }

    private LinearLayout.LayoutParams marginParams(int width, int height, int left) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(Ui.dp(this, width), Ui.dp(this, height));
        params.leftMargin = Ui.dp(this, left);
        return params;
    }

    private final class ModeOption {
        final LinearLayout root = new LinearLayout(MainActivity.this);
        final ImageView icon = new ImageView(MainActivity.this);
        final TextView label;

        ModeOption() {
            root.setGravity(Gravity.CENTER);
            root.setClickable(true);
            root.setFocusable(true);
            icon.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
            root.addView(icon, new LinearLayout.LayoutParams(
                    Ui.dp(MainActivity.this, 18), Ui.dp(MainActivity.this, 18)));
            label = Ui.text(MainActivity.this, "", 11, Ui.TEXT, Typeface.BOLD);
            label.setGravity(Gravity.CENTER);
            LinearLayout.LayoutParams labelParams = new LinearLayout.LayoutParams(-2, -1);
            labelParams.leftMargin = Ui.dp(MainActivity.this, 6);
            root.addView(label, labelParams);
        }
    }

    private ModeOption modeOption(String label, int iconRes, boolean rootChoice) {
        ModeOption option = new ModeOption();
        option.icon.setImageResource(iconRes);
        option.label.setText(label);
        option.root.setContentDescription(label + "模式");
        option.root.setOnClickListener(view -> {
            if (changingRoot || rootEnabled == rootChoice) return;
            if (rootChoice) {
                verifyRoot();
            } else {
                rootEnabled = false;
                getSharedPreferences(PREFS, MODE_PRIVATE).edit()
                        .putBoolean(PREF_ROOT, false).apply();
                updateRootControls(false);
            }
        });
        return option;
    }

    private void updateRootControls(boolean checking) {
        updateModeOption(normalMode, !rootEnabled && !checking);
        updateModeOption(rootMode, rootEnabled && !checking);
        if (rootMode != null) rootMode.label.setText(checking ? "检查中" : "Root");
        Ui.setEnabled(normalMode.root, !checking);
        Ui.setEnabled(rootMode.root, !checking);
    }

    private void updateModeOption(ModeOption option, boolean selected) {
        if (option == null) return;
        boolean selectedRoot = selected && option == rootMode;
        int color = selectedRoot ? Ui.ROOT_TEXT
                : selected ? android.graphics.Color.WHITE : Ui.MUTED;
        int background = selectedRoot ? Ui.ROOT_TINT : selected ? Ui.BLUE : Ui.SURFACE;
        option.label.setTextColor(color);
        option.icon.setImageTintList(ColorStateList.valueOf(color));
        option.root.setBackground(Ui.background(this, background, 4));
    }

    @Override
    protected void onSaveInstanceState(Bundle state) {
        super.onSaveInstanceState(state);
        if (updateScriptId != null) state.putString(STATE_UPDATE_SCRIPT_ID, updateScriptId);
    }

    private void verifyRoot() {
        changingRoot = true;
        updateRootControls(true);
        new Thread(() -> {
            boolean granted = false;
            Process process = null;
            try {
                process = new ProcessBuilder("su", "-c", "id").redirectErrorStream(true).start();
                boolean finished = process.waitFor(5, TimeUnit.SECONDS);
                String line = "";
                if (finished) {
                    try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                        String value;
                        while ((value = reader.readLine()) != null) line += value;
                    }
                    granted = process.exitValue() == 0 && line.contains("uid=0");
                }
            } catch (Exception ignored) {
                granted = false;
            } finally {
                if (process != null && process.isAlive()) process.destroyForcibly();
            }
            boolean result = granted;
            runOnUiThread(() -> {
                rootEnabled = result;
                changingRoot = false;
                getSharedPreferences(PREFS, MODE_PRIVATE).edit()
                        .putBoolean(PREF_ROOT, result).apply();
                updateRootControls(false);
                Toast.makeText(this, result ? "Root 授权成功" : "未获得 Root 权限", Toast.LENGTH_SHORT).show();
            });
        }, "root-check").start();
    }

    private void openScriptPicker() {
        openScriptPicker(OPEN_SCRIPT_MT, OPEN_SCRIPT_DOCUMENT);
    }

    private void openScriptPicker(int mtRequestCode, int documentRequestCode) {
        String mtPackage = installedMtPackage();
        if (mtPackage != null) {
            Intent mt = new Intent(Intent.ACTION_GET_CONTENT);
            mt.addCategory(Intent.CATEGORY_OPENABLE);
            mt.setType("*/*");
            mt.setPackage(mtPackage);
            mt.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            try {
                startActivityForResult(mt, mtRequestCode);
                return;
            } catch (ActivityNotFoundException ignored) {
                // Fall through to Android's persistent document picker.
            }
        }
        openSystemScriptPicker(documentRequestCode);
    }

    private void openSystemScriptPicker(int requestCode) {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("*/*");
        intent.putExtra(Intent.EXTRA_MIME_TYPES, new String[]{"application/x-sh", "text/x-shellscript", "text/plain", "application/octet-stream"});
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
        startActivityForResult(intent, requestCode);
    }

    private String installedMtPackage() {
        String[] packages = {"bin.mt.plus.canary", "bin.mt.plus"};
        for (String packageName : packages) {
            try {
                getPackageManager().getPackageInfo(packageName, 0);
                Intent probe = new Intent(Intent.ACTION_GET_CONTENT);
                probe.addCategory(Intent.CATEGORY_OPENABLE);
                probe.setType("*/*");
                probe.setPackage(packageName);
                if (probe.resolveActivity(getPackageManager()) != null) return packageName;
            } catch (PackageManager.NameNotFoundException ignored) {
                // Try the other MT Manager package.
            }
        }
        return null;
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        boolean updateRequest = requestCode == UPDATE_SCRIPT_MT
                || requestCode == UPDATE_SCRIPT_DOCUMENT;
        if (resultCode != RESULT_OK || data == null || data.getData() == null) {
            if (updateRequest) updateScriptId = null;
            return;
        }
        Uri uri = data.getData();
        if (updateRequest) {
            ScriptItem target = findScript(updateScriptId);
            updateScriptId = null;
            if (target == null) {
                Toast.makeText(this, "脚本已不在列表中", Toast.LENGTH_SHORT).show();
                return;
            }
            try {
                ScriptItem updated = store.updateSource(target, uri);
                reloadScripts(false);
                Toast.makeText(this, "已更新 " + updated.name + "，名称和排序保持不变",
                        Toast.LENGTH_SHORT).show();
            } catch (Exception error) {
                Toast.makeText(this, error.getMessage() == null
                        ? "无法更新脚本源" : error.getMessage(), Toast.LENGTH_LONG).show();
            }
            return;
        }
        if (requestCode != OPEN_SCRIPT_MT && requestCode != OPEN_SCRIPT_DOCUMENT) return;
        try {
            ScriptItem added;
            if (requestCode == OPEN_SCRIPT_MT) {
                added = store.importCopy(uri);
            } else {
                getContentResolver().takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION);
                added = store.addOrUpdate(uri);
            }
            reloadScripts(false);
            Toast.makeText(this, "已保存 " + added.name, Toast.LENGTH_SHORT).show();
        } catch (Exception error) {
            Toast.makeText(this, error.getMessage() == null ? "无法添加脚本" : error.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    private ScriptItem findScript(String scriptId) {
        if (scriptId == null) return null;
        for (ScriptItem item : scripts) if (item.id.equals(scriptId)) return item;
        return null;
    }

    private void reloadScripts(boolean refreshMetadata) {
        List<ScriptItem> loaded = refreshMetadata ? store.refresh() : store.load();
        List<ScriptItem> previous = new ArrayList<>(scripts);
        DiffUtil.DiffResult changes = DiffUtil.calculateDiff(new DiffUtil.Callback() {
            @Override public int getOldListSize() { return previous.size(); }
            @Override public int getNewListSize() { return loaded.size(); }

            @Override
            public boolean areItemsTheSame(int oldPosition, int newPosition) {
                return previous.get(oldPosition).id.equals(loaded.get(newPosition).id);
            }

            @Override
            public boolean areContentsTheSame(int oldPosition, int newPosition) {
                ScriptItem oldItem = previous.get(oldPosition);
                ScriptItem newItem = loaded.get(newPosition);
                return oldItem.size == newItem.size && oldItem.modified == newItem.modified
                        && same(oldItem.name, newItem.name)
                        && same(oldItem.uri, newItem.uri)
                        && same(oldItem.localPath, newItem.localPath);
            }
        }, true);
        scripts.clear();
        scripts.addAll(loaded);
        changes.dispatchUpdatesTo(adapter);
        countView.setText(String.valueOf(scripts.size()));
        emptyState.setVisibility(scripts.isEmpty() ? View.VISIBLE : View.GONE);
        if (refreshMetadata) Toast.makeText(this, "脚本信息已刷新", Toast.LENGTH_SHORT).show();
    }

    private static boolean same(Object first, Object second) {
        return first == null ? second == null : first.equals(second);
    }

    private void notifyScriptChanged(String scriptId) {
        for (int index = 0; index < scripts.size(); index++) {
            if (scripts.get(index).id.equals(scriptId)) {
                adapter.notifyItemChanged(index);
                return;
            }
        }
    }

    private void runScript(ScriptItem item) {
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, NOTIFICATION_PERMISSION);
        }
        prepareAndRun(item, rootEnabled);
    }

    private void prepareAndRun(ScriptItem item, boolean useRoot) {
        String sessionId = UUID.randomUUID().toString();
        Toast.makeText(this, "正在准备 " + item.name, Toast.LENGTH_SHORT).show();
        new Thread(() -> {
            try {
                File file = store.prepareScript(item, sessionId);
                File workingDirectory = store.prepareWorkingDirectory(item);
                String scriptHash = ScriptStore.sha256(file);
                boolean inputMemoryEnabled = inputMemoryStore.isEnabled(item.id);
                List<InputMemoryStore.Entry> remembered = inputMemoryEnabled
                        ? inputMemoryStore.load(item.id, scriptHash) : new ArrayList<>();
                ArrayList<String> autoInputs = new ArrayList<>();
                ArrayList<String> autoPrompts = new ArrayList<>();
                for (InputMemoryStore.Entry entry : remembered) {
                    autoInputs.add(entry.value);
                    autoPrompts.add(entry.prompt);
                }
                Intent terminal = new Intent(this, TerminalActivity.class);
                terminal.setAction(Intent.ACTION_VIEW);
                terminal.setData(Uri.parse("shelldeck://session/" + sessionId));
                terminal.putExtra(TerminalActivity.EXTRA_SESSION_ID, sessionId);
                terminal.putExtra(TerminalActivity.EXTRA_SCRIPT_PATH, file.getAbsolutePath());
                terminal.putExtra(TerminalActivity.EXTRA_WORKING_DIRECTORY,
                        workingDirectory.getAbsolutePath());
                terminal.putExtra(TerminalActivity.EXTRA_SCRIPT_NAME, item.name);
                terminal.putExtra(TerminalActivity.EXTRA_ROOT, useRoot);
                terminal.putExtra(TerminalActivity.EXTRA_SCRIPT_ID, item.id);
                terminal.putExtra(TerminalActivity.EXTRA_SCRIPT_HASH, scriptHash);
                terminal.putExtra(TerminalActivity.EXTRA_INPUT_MEMORY_ENABLED, inputMemoryEnabled);
                terminal.putStringArrayListExtra(TerminalActivity.EXTRA_AUTO_INPUTS, autoInputs);
                terminal.putStringArrayListExtra(TerminalActivity.EXTRA_AUTO_PROMPTS, autoPrompts);
                runOnUiThread(() -> startActivity(terminal));
            } catch (Exception error) {
                runOnUiThread(() -> Toast.makeText(this,
                        error.getMessage() == null ? "脚本准备失败" : error.getMessage(), Toast.LENGTH_LONG).show());
            }
        }, "script-prepare").start();
    }

    private void confirmRemove(ScriptItem item) {
        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle("移除脚本？")
                .setMessage(item.name + " 将从列表移除，原文件不会删除。")
                .setNegativeButton("取消", null)
                .setPositiveButton("移除", (ignored, which) -> {
                    store.remove(item);
                    reloadScripts(false);
                }).create();
        dialog.setOnShowListener(ignored -> dialog.getButton(AlertDialog.BUTTON_POSITIVE).setTextColor(Ui.DANGER));
        dialog.show();
    }

    private void showScriptOptions(ScriptItem item) {
        boolean enabled = inputMemoryStore.isEnabled(item.id);
        int savedCount = inputMemoryStore.count(item.id);

        LinearLayout memoryPanel = new LinearLayout(this);
        memoryPanel.setOrientation(LinearLayout.VERTICAL);
        memoryPanel.setPadding(Ui.dp(this, 20), 0, Ui.dp(this, 20), Ui.dp(this, 8));

        CheckBox memoryToggle = new CheckBox(this);
        memoryToggle.setText("记忆并自动输入");
        memoryToggle.setTextSize(16);
        memoryToggle.setTextColor(Ui.TEXT);
        memoryToggle.setChecked(enabled);
        memoryPanel.addView(memoryToggle, new LinearLayout.LayoutParams(-1, Ui.dp(this, 52)));

        TextView memoryStatus = Ui.text(this, memoryStatusText(enabled, savedCount), 12, Ui.MUTED, Typeface.NORMAL);
        memoryPanel.addView(memoryStatus, new LinearLayout.LayoutParams(-1, -2));

        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle(item.name)
                .setView(memoryPanel)
                .setNegativeButton("关闭", null)
                .setNeutralButton(savedCount > 0 ? "清除输入" : "自定义名称", null)
                .setPositiveButton("更多", null)
                .create();
        memoryToggle.setOnCheckedChangeListener((ignored, value) -> {
            inputMemoryStore.setEnabled(item.id, value);
            memoryStatus.setText(memoryStatusText(value, inputMemoryStore.count(item.id)));
            notifyScriptChanged(item.id);
        });
        dialog.setOnShowListener(ignored -> {
            dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener(view -> {
                if (savedCount > 0) {
                    inputMemoryStore.clear(item.id);
                    dialog.dismiss();
                    notifyScriptChanged(item.id);
                    Toast.makeText(this, "记录已清除，下次运行将重新学习", Toast.LENGTH_SHORT).show();
                } else {
                    dialog.dismiss();
                    showRenameDialog(item);
                }
            });
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(view -> {
                dialog.dismiss();
                showMoreScriptOptions(item);
            });
        });
        dialog.show();
    }

    private String memoryStatusText(boolean enabled, int savedCount) {
        if (!enabled) return "已关闭；运行时不会记录或自动输入";
        if (savedCount == 0) return "已开启；首次运行时输入的内容会被记住";
        return "已开启；下次运行将自动输入已记录的 " + savedCount + " 项内容";
    }

    private void showMoreScriptOptions(ScriptItem item) {
        boolean hasRememberedInputs = inputMemoryStore.count(item.id) > 0;
        String[] choices = hasRememberedInputs
                ? new String[]{"自定义名称", "更新脚本源", "清除已记录输入", "移除脚本"}
                : new String[]{"自定义名称", "更新脚本源", "移除脚本"};
        new AlertDialog.Builder(this)
                .setTitle(item.name)
                .setItems(choices,
                        (dialog, which) -> {
                            if (which == 0) showRenameDialog(item);
                            else if (which == 1) updateScriptSource(item);
                            else if (hasRememberedInputs && which == 2) {
                                inputMemoryStore.clear(item.id);
                                notifyScriptChanged(item.id);
                                Toast.makeText(this, "记录已清除，下次运行将重新学习", Toast.LENGTH_SHORT).show();
                            } else confirmRemove(item);
                        })
                .show();
    }

    private void updateScriptSource(ScriptItem item) {
        updateScriptId = item.id;
        openScriptPicker(UPDATE_SCRIPT_MT, UPDATE_SCRIPT_DOCUMENT);
    }

    private void showRenameDialog(ScriptItem item) {
        EditText name = new EditText(this);
        name.setSingleLine(true);
        name.setText(item.name);
        name.setSelectAllOnFocus(true);
        name.setTextColor(Ui.TEXT);
        name.setHintTextColor(Ui.MUTED);
        int horizontal = Ui.dp(this, 20);
        FrameLayout container = new FrameLayout(this);
        container.setPadding(horizontal, 0, horizontal, 0);
        container.addView(name, new FrameLayout.LayoutParams(-1, Ui.dp(this, 52)));
        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle("自定义名称")
                .setView(container)
                .setNegativeButton("取消", null)
                .setPositiveButton("保存", null)
                .create();
        dialog.setOnShowListener(ignored -> dialog.getButton(AlertDialog.BUTTON_POSITIVE)
                .setOnClickListener(view -> {
                    String value = name.getText().toString().trim();
                    if (value.isEmpty()) {
                        name.setError("名称不能为空");
                        return;
                    }
                    store.rename(item, value);
                    reloadScripts(false);
                    dialog.dismiss();
                }));
        dialog.show();
        name.requestFocus();
    }

    private final class ScriptDragCallback extends ItemTouchHelper.Callback {
        @Override
        public int getMovementFlags(RecyclerView recyclerView, RecyclerView.ViewHolder holder) {
            return makeMovementFlags(ItemTouchHelper.UP | ItemTouchHelper.DOWN, 0);
        }

        @Override
        public boolean isLongPressDragEnabled() {
            return false;
        }

        @Override
        public boolean onMove(RecyclerView recyclerView, RecyclerView.ViewHolder source,
                              RecyclerView.ViewHolder target) {
            int from = source.getAdapterPosition();
            int to = target.getAdapterPosition();
            if (from == RecyclerView.NO_POSITION || to == RecyclerView.NO_POSITION || from == to) {
                return false;
            }
            ScriptItem moved = scripts.remove(from);
            scripts.add(to, moved);
            adapter.notifyItemMoved(from, to);
            reorderDirty = true;
            return true;
        }

        @Override
        public void onSwiped(RecyclerView.ViewHolder holder, int direction) {
            // Horizontal swipe actions are intentionally disabled.
        }

        @Override
        public float getMoveThreshold(RecyclerView.ViewHolder viewHolder) {
            return 0.28f;
        }

        @Override
        public void onSelectedChanged(RecyclerView.ViewHolder holder, int actionState) {
            super.onSelectedChanged(holder, actionState);
            if (actionState != ItemTouchHelper.ACTION_STATE_DRAG || !(holder instanceof ScriptHolder)) return;
            ScriptRow views = ((ScriptHolder) holder).views;
            holder.itemView.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);
            views.row.animate().cancel();
            views.row.setElevation(Ui.dp(MainActivity.this, 8));
            views.row.animate().scaleX(1.02f).scaleY(1.02f).alpha(0.96f)
                    .setDuration(120).start();
        }

        @Override
        public void clearView(RecyclerView recyclerView, RecyclerView.ViewHolder holder) {
            super.clearView(recyclerView, holder);
            if (holder instanceof ScriptHolder) {
                ScriptRow views = ((ScriptHolder) holder).views;
                views.row.animate().cancel();
                views.row.animate().scaleX(1f).scaleY(1f).alpha(1f).setDuration(170)
                        .withEndAction(() -> views.row.setElevation(0)).start();
            }
            if (reorderDirty) {
                store.reorder(scripts);
                reorderDirty = false;
            }
        }
    }

    private final class ScriptRow {
        final FrameLayout outer = new FrameLayout(MainActivity.this);
        final LinearLayout row = new LinearLayout(MainActivity.this);
        final ImageButton drag;
        final TextView name;
        final TextView metadata;
        final ImageButton options;
        final ImageButton play;

        ScriptRow() {
            outer.setPadding(0, Ui.dp(MainActivity.this, 4), 0, Ui.dp(MainActivity.this, 4));
            outer.setLayoutParams(new RecyclerView.LayoutParams(-1, Ui.dp(MainActivity.this, 76)));

            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(0, 0, Ui.dp(MainActivity.this, 6), 0);
            row.setBackground(Ui.outlinedBackground(MainActivity.this, Ui.SURFACE, Ui.LINE, 6));

            View rail = new View(MainActivity.this);
            rail.setBackgroundColor(Ui.BLUE);
            row.addView(rail, new LinearLayout.LayoutParams(Ui.dp(MainActivity.this, 3), -1));

            drag = Ui.iconButton(MainActivity.this, R.drawable.ic_drag_handle, "拖动排序");
            drag.setBackgroundColor(android.graphics.Color.TRANSPARENT);
            Ui.setIconTint(drag, Ui.MUTED);
            row.addView(drag, new LinearLayout.LayoutParams(Ui.dp(MainActivity.this, 46), -1));

            LinearLayout copy = new LinearLayout(MainActivity.this);
            copy.setOrientation(LinearLayout.VERTICAL);
            copy.setGravity(Gravity.CENTER_VERTICAL);
            name = Ui.text(MainActivity.this, "", 14, Ui.TEXT, Typeface.BOLD);
            name.setSingleLine(true);
            name.setEllipsize(android.text.TextUtils.TruncateAt.MIDDLE);
            metadata = Ui.text(MainActivity.this, "", 11, Ui.MUTED, Typeface.NORMAL);
            metadata.setSingleLine(true);
            metadata.setEllipsize(android.text.TextUtils.TruncateAt.END);
            metadata.setTypeface(Typeface.create("monospace", Typeface.NORMAL));
            copy.addView(name, new LinearLayout.LayoutParams(-1, Ui.dp(MainActivity.this, 28)));
            copy.addView(metadata, new LinearLayout.LayoutParams(-1, Ui.dp(MainActivity.this, 22)));
            row.addView(copy, new LinearLayout.LayoutParams(0, -1, 1));

            options = Ui.iconButton(MainActivity.this, R.drawable.ic_more,
                    getString(R.string.script_options));
            options.setBackgroundColor(android.graphics.Color.TRANSPARENT);
            row.addView(options, new LinearLayout.LayoutParams(
                    Ui.dp(MainActivity.this, 42), Ui.dp(MainActivity.this, 52)));

            play = Ui.iconButton(MainActivity.this, R.drawable.ic_play,
                    getString(R.string.run_script));
            play.setBackground(Ui.background(MainActivity.this, Ui.BLUE, 6));
            Ui.setIconTint(play, android.graphics.Color.WHITE);
            row.addView(play, new LinearLayout.LayoutParams(
                    Ui.dp(MainActivity.this, 44), Ui.dp(MainActivity.this, 44)));

            outer.addView(row, new FrameLayout.LayoutParams(-1, Ui.dp(MainActivity.this, 68)));
        }
    }

    private final class ScriptHolder extends RecyclerView.ViewHolder {
        final ScriptRow views;

        ScriptHolder(ScriptRow views) {
            super(views.outer);
            this.views = views;
            views.drag.setOnLongClickListener(view -> {
                if (itemTouchHelper != null && getAdapterPosition() != RecyclerView.NO_POSITION) {
                    itemTouchHelper.startDrag(this);
                    return true;
                }
                return false;
            });
        }
    }

    private final class ScriptAdapter extends RecyclerView.Adapter<ScriptHolder> {
        @Override
        public ScriptHolder onCreateViewHolder(ViewGroup parent, int viewType) {
            return new ScriptHolder(new ScriptRow());
        }

        @Override
        public void onBindViewHolder(ScriptHolder holder, int position) {
            ScriptItem item = scripts.get(position);
            holder.views.name.setText(item.name);
            String source = item.localPath == null ? "SAF" : "LOCAL";
            boolean memoryEnabled = inputMemoryStore.isEnabled(item.id);
            int inputCount = inputMemoryStore.count(item.id);
            String memory = !memoryEnabled ? "记忆关闭"
                    : inputCount == 0 ? "等待记录" : "自动输入 " + inputCount;
            holder.views.metadata.setText(getString(R.string.script_metadata_format,
                    Ui.formatSize(item.size), source, memory));
            holder.views.options.setOnClickListener(view -> showScriptOptions(item));
            holder.views.play.setOnClickListener(view -> runScript(item));
        }

        @Override
        public int getItemCount() {
            return scripts.size();
        }
    }
}
