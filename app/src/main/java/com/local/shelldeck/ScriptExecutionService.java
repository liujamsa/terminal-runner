package com.local.shelldeck;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.net.Uri;
import android.os.Binder;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;

import java.util.ArrayList;
import java.util.Collections;
import java.io.File;
import java.io.IOException;
import java.util.Map;
import java.util.List;
import java.util.Comparator;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

public final class ScriptExecutionService extends Service {
    static final String ACTION_RUN = "com.local.shelldeck.RUN";
    static final String EXTRA_SESSION_ID = "session_id";
    static final String EXTRA_PATH = "path";
    static final String EXTRA_WORKING_DIRECTORY = "working_directory";
    static final String EXTRA_NAME = "name";
    static final String EXTRA_ROOT = "root";
    static final String EXTRA_SCRIPT_ID = "script_id";
    static final String EXTRA_SCRIPT_HASH = "script_hash";
    static final String EXTRA_INPUT_MEMORY_ENABLED = "input_memory_enabled";
    static final String EXTRA_AUTO_INPUTS = "auto_inputs";
    static final String EXTRA_AUTO_PROMPTS = "auto_prompts";

    static final int STATE_IDLE = 0;
    static final int STATE_STARTING = 1;
    static final int STATE_RUNNING = 2;
    static final int STATE_FINISHED = 3;
    static final int STATE_FAILED = 4;
    static final int STATE_STOPPED = 5;

    private static final String CHANNEL_ID = "script_execution";
    private static final int SUMMARY_NOTIFICATION_ID = 41;
    private static final int SESSION_NOTIFICATION_BASE = 1000;
    private static final int MAX_CONCURRENT_SESSIONS = 4;
    private static final long FINISHED_SESSION_TTL_MS = 120_000;

    interface Listener {
        void onOutput(String chunk);
        void onStateChanged(int state, Integer exitCode);
    }

    interface SessionListListener {
        void onSessionsChanged();
    }

    static final class SessionSummary {
        final String id;
        final String name;
        final boolean root;
        final int state;
        final Integer exitCode;
        final boolean closing;
        final long startedAt;

        SessionSummary(ExecutionSession session) {
            id = session.id;
            name = session.name;
            root = session.isRoot();
            state = session.getState();
            exitCode = session.getExitCode();
            closing = session.isCloseRequested();
            startedAt = session.startedAt;
        }
    }

    final class LocalBinder extends Binder {
        ScriptExecutionService getService() {
            return ScriptExecutionService.this;
        }
    }

    private final IBinder binder = new LocalBinder();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final Map<String, ExecutionSession> sessions = new ConcurrentHashMap<>();
    private final CopyOnWriteArrayList<SessionListListener> sessionListListeners =
            new CopyOnWriteArrayList<>();
    private boolean foreground;

    @Override
    public void onCreate() {
        super.onCreate();
        NotificationChannel channel = new NotificationChannel(
                CHANNEL_ID, "脚本执行", NotificationManager.IMPORTANCE_LOW);
        channel.setDescription("独立运行的本机脚本通道");
        getSystemService(NotificationManager.class).createNotificationChannel(channel);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null || !ACTION_RUN.equals(intent.getAction())) return START_NOT_STICKY;
        String sessionId = intent.getStringExtra(EXTRA_SESSION_ID);
        String path = intent.getStringExtra(EXTRA_PATH);
        String scriptId = intent.getStringExtra(EXTRA_SCRIPT_ID);
        String workingDirectory = intent.getStringExtra(EXTRA_WORKING_DIRECTORY);
        if (!isValidSessionId(sessionId) || !isValidScriptPath(sessionId, path)
                || !isValidWorkingDirectory(scriptId, workingDirectory)) {
            return START_NOT_STICKY;
        }
        if (sessions.containsKey(sessionId)) return START_NOT_STICKY;

        String name = intent.getStringExtra(EXTRA_NAME);
        boolean root = intent.getBooleanExtra(EXTRA_ROOT, false);
        ArrayList<String> inputs = intent.getStringArrayListExtra(EXTRA_AUTO_INPUTS);
        ArrayList<String> prompts = intent.getStringArrayListExtra(EXTRA_AUTO_PROMPTS);
        ExecutionSession session = new ExecutionSession(
                this, sessionId, path, name == null ? "脚本" : name,
                root,
                scriptId,
                intent.getStringExtra(EXTRA_SCRIPT_HASH),
                workingDirectory,
                intent.getBooleanExtra(EXTRA_INPUT_MEMORY_ENABLED, false),
                inputs == null ? Collections.emptyList() : inputs,
                prompts == null ? Collections.emptyList() : prompts,
                notificationIdFor(sessionId));
        sessions.put(sessionId, session);
        notifySessionListChanged();
        ensureForeground();
        if (activeSessionCount() > MAX_CONCURRENT_SESSIONS) {
            session.reject("为防止手机资源耗尽，最多同时运行 "
                    + MAX_CONCURRENT_SESSIONS + " 个脚本通道");
            return START_NOT_STICKY;
        }
        session.start();
        return START_NOT_STICKY;
    }

    @Override
    public IBinder onBind(Intent intent) {
        return binder;
    }

    @Override
    public void onDestroy() {
        for (ExecutionSession session : sessions.values()) session.closeChannel();
        sessions.clear();
        sessionListListeners.clear();
        super.onDestroy();
    }

    String addListener(String sessionId, Listener listener) {
        ExecutionSession session = sessions.get(sessionId);
        if (session == null) {
            listener.onStateChanged(STATE_FAILED, null);
            return "[此运行通道已结束或应用进程已被系统回收]\n";
        }
        return session.addListener(listener);
    }

    void removeListener(String sessionId, Listener listener) {
        ExecutionSession session = sessions.get(sessionId);
        if (session == null) return;
        session.removeListener(listener);
        scheduleSessionRemoval(session);
    }

    int getStateValue(String sessionId) {
        ExecutionSession session = sessions.get(sessionId);
        return session == null ? STATE_FAILED : session.getState();
    }

    void sendInput(String sessionId, String input) throws Exception {
        ExecutionSession session = sessions.get(sessionId);
        if (session == null) throw new IllegalStateException("运行通道已结束");
        session.sendInput(input);
    }

    void closeChannel(String sessionId) {
        ExecutionSession session = sessions.get(sessionId);
        if (session != null) {
            session.closeChannel();
            notifySessionListChanged();
        }
    }

    void addSessionListListener(SessionListListener listener) {
        sessionListListeners.addIfAbsent(listener);
    }

    void removeSessionListListener(SessionListListener listener) {
        sessionListListeners.remove(listener);
    }

    List<SessionSummary> activeSessions() {
        List<SessionSummary> values = new ArrayList<>();
        for (ExecutionSession session : sessions.values()) {
            if (session.isActive()) values.add(new SessionSummary(session));
        }
        values.sort(Comparator.comparingLong(
                (SessionSummary value) -> value.startedAt).reversed());
        return values;
    }

    Handler handler() {
        return mainHandler;
    }

    void onSessionStatus(ExecutionSession session, String status, boolean ongoing) {
        mainHandler.post(() -> {
            if (ongoing) ensureForeground();
            getSystemService(NotificationManager.class).notify(
                    session.notificationId, buildSessionNotification(session, status, ongoing));
            updateSummaryNotification();
            dispatchSessionListChanged();
        });
    }

    void onSessionFinished(ExecutionSession session) {
        mainHandler.post(() -> {
            getSystemService(NotificationManager.class).cancel(session.notificationId);
            updateSummaryNotification();
            scheduleSessionRemoval(session);
            dispatchSessionListChanged();
        });
    }

    private void ensureForeground() {
        Notification summary = buildSummaryNotification(Math.max(1, activeSessionCount()));
        if (!foreground) {
            startForeground(SUMMARY_NOTIFICATION_ID, summary);
            foreground = true;
        } else {
            getSystemService(NotificationManager.class).notify(SUMMARY_NOTIFICATION_ID, summary);
        }
    }

    private void updateSummaryNotification() {
        int active = activeSessionCount();
        if (active > 0) {
            ensureForeground();
        } else if (foreground) {
            stopForeground(STOP_FOREGROUND_REMOVE);
            foreground = false;
        }
        maybeStopService();
    }

    private Notification buildSummaryNotification(int count) {
        Intent open = new Intent(this, RunningSessionsActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT);
        PendingIntent pending = PendingIntent.getActivity(this, SUMMARY_NOTIFICATION_ID, open,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        return new Notification.Builder(this, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_terminal)
                .setContentTitle(count + " 个脚本通道正在运行")
                .setContentText("每个控制台独立输入、输出和结束")
                .setContentIntent(pending)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setCategory(Notification.CATEGORY_SERVICE)
                .build();
    }

    private Notification buildSessionNotification(ExecutionSession session,
                                                    String status, boolean ongoing) {
        Intent open = new Intent(this, TerminalActivity.class)
                .setAction(Intent.ACTION_VIEW)
                .setData(Uri.parse("shelldeck://session/" + session.id))
                .putExtra(TerminalActivity.EXTRA_SESSION_ID, session.id)
                .putExtra(TerminalActivity.EXTRA_SCRIPT_NAME, session.name)
                .putExtra(TerminalActivity.EXTRA_SCRIPT_ID, session.scriptId);
        PendingIntent pending = PendingIntent.getActivity(this, session.notificationId, open,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        return new Notification.Builder(this, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_terminal)
                .setContentTitle(session.name)
                .setContentText(status)
                .setContentIntent(pending)
                .setOngoing(ongoing)
                .setOnlyAlertOnce(true)
                .setGroup(CHANNEL_ID)
                .setCategory(Notification.CATEGORY_PROGRESS)
                .build();
    }

    private void scheduleSessionRemoval(ExecutionSession session) {
        if (session.isActive() || session.hasListeners()) return;
        mainHandler.postDelayed(() -> {
            if (session.isActive() || session.hasListeners()) return;
            sessions.remove(session.id, session);
            dispatchSessionListChanged();
            maybeStopService();
        }, FINISHED_SESSION_TTL_MS);
    }

    private void maybeStopService() {
        if (activeSessionCount() > 0) return;
        for (ExecutionSession session : sessions.values()) {
            if (session.hasListeners()) return;
        }
        stopSelf();
    }

    private int activeSessionCount() {
        int count = 0;
        for (ExecutionSession session : sessions.values()) if (session.isActive()) count++;
        return count;
    }

    private int notificationIdFor(String sessionId) {
        return SESSION_NOTIFICATION_BASE + (sessionId.hashCode() & 0x0fffffff);
    }

    private void notifySessionListChanged() {
        mainHandler.post(this::dispatchSessionListChanged);
    }

    private void dispatchSessionListChanged() {
        for (SessionListListener listener : sessionListListeners) listener.onSessionsChanged();
    }

    private static boolean isValidSessionId(String value) {
        return value != null && value.matches("[A-Za-z0-9_-]{1,80}");
    }

    boolean isValidScriptPath(String sessionId, String value) {
        if (value == null || value.isEmpty()) return false;
        try {
            File runs = new File(getFilesDir(), "runs").getCanonicalFile();
            File session = new File(runs, sessionId).getCanonicalFile();
            File expected = new File(session, "script.sh").getCanonicalFile();
            File actual = new File(value).getCanonicalFile();
            return runs.equals(session.getParentFile())
                    && expected.equals(actual)
                    && actual.isFile();
        } catch (IOException | RuntimeException ignored) {
            return false;
        }
    }

    boolean isValidWorkingDirectory(String scriptId, String value) {
        if (!ScriptItem.isValidId(scriptId) || value == null || value.isEmpty()) return false;
        try {
            File workspaces = new File(getFilesDir(), "workspaces").getCanonicalFile();
            File expected = new File(workspaces, scriptId).getCanonicalFile();
            File actual = new File(value).getCanonicalFile();
            return workspaces.equals(expected.getParentFile())
                    && expected.equals(actual)
                    && actual.isDirectory();
        } catch (IOException | RuntimeException ignored) {
            return false;
        }
    }

}
