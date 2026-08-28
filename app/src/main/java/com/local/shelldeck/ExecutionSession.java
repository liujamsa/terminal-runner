package com.local.shelldeck;

import com.furyform.terminal.NativePTY;

import java.io.File;
import java.io.FileInputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.regex.Pattern;

final class ExecutionSession {
    private static final int MAX_OUTPUT_CHARS = 300_000;
    private static final String PTY_READY_MARKER = "\u001eSHELLDECK_READY\u001f";
    private static final String PTY_ECHO_FAILED_MARKER = "\u001eSHELLDECK_ECHO_FAILED\u001f";
    private static final Pattern PTY_PID_MARKER = Pattern.compile(
            "\u001eSHELLDECK_PID:(\\d+)\u001f");
    private static final Pattern ANSI = Pattern.compile(
            "\\u001B(?:\\[[0-?]*[ -/]*[@-~]|\\][^\\u0007]*(?:\\u0007|\\u001B\\\\))");

    final String id;
    final String path;
    final String name;
    final String scriptId;
    final String scriptHash;
    final String workingDirectory;
    final int notificationId;
    final long startedAt = System.currentTimeMillis();

    private final ScriptExecutionService service;
    private final boolean root;
    private final List<String> replayInputs;
    private final List<String> replayPrompts;
    private boolean rememberManualInputs;
    private final CopyOnWriteArrayList<ScriptExecutionService.Listener> listeners =
            new CopyOnWriteArrayList<>();
    private final Object outputLock = new Object();
    private final Object inputLock = new Object();
    private final Object closeLock = new Object();
    private final Object ptyOutputLock = new Object();
    private final Object replayOutputLock = new Object();
    private final AtomicBoolean finishPublished = new AtomicBoolean();
    private final AtomicBoolean manualInputOverride = new AtomicBoolean();
    private final StringBuilder output = new StringBuilder();
    private final StringBuilder ptyLineCarry = new StringBuilder();
    private final StringBuilder ptyStartupCarry = new StringBuilder();
    private final StringBuilder replayOutputWindow = new StringBuilder();

    private volatile Process process;
    private volatile int ptySessionId = -1;
    private volatile int state = ScriptExecutionService.STATE_STARTING;
    private volatile Integer exitCode;
    private volatile boolean channelCloseRequested;
    private volatile boolean ptyClosed;
    private boolean ptyStartupPending;
    private long scriptOutputGeneration;

    ExecutionSession(ScriptExecutionService service, String id, String path, String name,
                     boolean root, String scriptId, String scriptHash, String workingDirectory,
                     boolean memoryEnabled, List<String> savedInputs,
                     List<String> savedPrompts, int notificationId) {
        this.service = service;
        this.id = id;
        this.path = path;
        this.name = name;
        this.root = root;
        this.scriptId = scriptId;
        this.scriptHash = scriptHash;
        this.workingDirectory = workingDirectory;
        this.replayInputs = memoryEnabled ? new ArrayList<>(savedInputs) : new ArrayList<>();
        this.replayPrompts = new ArrayList<>();
        for (int index = 0; index < replayInputs.size(); index++) {
            replayPrompts.add(memoryEnabled && index < savedPrompts.size()
                    ? savedPrompts.get(index) : "");
        }
        this.rememberManualInputs = memoryEnabled;
        this.notificationId = notificationId;
    }

    void start() {
        service.onSessionStatus(this, "正在启动", true);
        new Thread(this::execute, "script-" + id.substring(0, Math.min(8, id.length()))).start();
    }

    void reject(String reason) {
        appendOutput("[启动已拦截] " + reason + "\n");
        setState(ScriptExecutionService.STATE_FAILED, null);
        publishFinishedOnce();
    }

    String addListener(ScriptExecutionService.Listener listener) {
        String snapshot;
        synchronized (outputLock) {
            listeners.addIfAbsent(listener);
            snapshot = output.toString();
        }
        listener.onStateChanged(state, exitCode);
        return snapshot;
    }

    void removeListener(ScriptExecutionService.Listener listener) {
        listeners.remove(listener);
    }

    boolean hasListeners() {
        return !listeners.isEmpty();
    }

    int getState() {
        return state;
    }

    Integer getExitCode() {
        return exitCode;
    }

    boolean isRoot() {
        return root;
    }

    boolean isCloseRequested() {
        return channelCloseRequested;
    }

    boolean isActive() {
        return state == ScriptExecutionService.STATE_STARTING
                || state == ScriptExecutionService.STATE_RUNNING;
    }

    void sendInput(String input) throws Exception {
        if (channelCloseRequested) throw new IllegalStateException("运行通道已关闭");
        String prompt = capturePromptAnchor();
        String value = input.endsWith("\n") ? input : input + "\n";
        int currentPty = ptySessionId;
        if (currentPty >= 0 && !ptyClosed) {
            int written;
            boolean stoppedReplay;
            synchronized (inputLock) {
                if (ptyClosed || ptySessionId != currentPty) {
                    throw new IllegalStateException("运行通道已关闭");
                }
                stoppedReplay = stopReplayForManualInput();
                written = NativePTY.nativeWrite(
                        currentPty, value.getBytes(StandardCharsets.UTF_8));
            }
            if (written < 0) throw new IllegalStateException("终端输入失败");
            if (stoppedReplay) appendOutput("\n[已切换为手动输入，本次自动输入已停止]\n");
            rememberInput(input, prompt);
            return;
        }
        Process current = process;
        if (current == null || !current.isAlive()) throw new IllegalStateException("脚本已结束");
        boolean stoppedReplay;
        synchronized (inputLock) {
            if (channelCloseRequested || process != current) {
                throw new IllegalStateException("运行通道已关闭");
            }
            stoppedReplay = stopReplayForManualInput();
            OutputStream stream = current.getOutputStream();
            stream.write(value.getBytes(StandardCharsets.UTF_8));
            stream.flush();
        }
        if (stoppedReplay) appendOutput("\n[已切换为手动输入，本次自动输入已停止]\n");
        appendOutput("\n> " + input.replace("\n", "\\n") + "\n");
        rememberInput(input, prompt);
    }

    private boolean stopReplayForManualInput() {
        boolean stopped = !replayInputs.isEmpty()
                && manualInputOverride.compareAndSet(false, true);
        if (stopped) wakeReplayWaiter();
        return stopped;
    }

    void closeChannel() {
        synchronized (closeLock) {
            if (channelCloseRequested) return;
            channelCloseRequested = true;
        }
        wakeReplayWaiter();
        setState(ScriptExecutionService.STATE_STOPPED, null);
        publishFinishedOnce();
    }

    private void execute() {
        try {
            File script = new File(path);
            if (!service.isValidScriptPath(id, path)) {
                throw new IllegalArgumentException("脚本运行路径安全校验失败");
            }
            if (!service.isValidWorkingDirectory(scriptId, workingDirectory)) {
                throw new IllegalArgumentException("脚本工作目录安全校验失败");
            }
            File workspace = new File(workingDirectory);
            if (scriptHash == null || !scriptHash.equals(ScriptStore.sha256(script))) {
                replayInputs.clear();
                replayPrompts.clear();
                rememberManualInputs = false;
            }
            if (channelCloseRequested) throw new IllegalStateException("通道已关闭");
            boolean elf = isElf(script);
            if (elf && executeElfWithPty(script, workspace)) return;
            executeWithProcess(script, elf, workspace);
        } catch (Exception error) {
            if (channelCloseRequested) {
                appendOutput("\n[当前通道已关闭，已脱离的后台进程不受影响]\n");
                setState(ScriptExecutionService.STATE_STOPPED, null);
            } else {
                String message = error.getMessage() == null
                        ? error.getClass().getSimpleName() : error.getMessage();
                appendOutput("\n[启动失败] " + message + "\n");
                setState(ScriptExecutionService.STATE_FAILED, null);
            }
        } finally {
            process = null;
            closePtyOnce();
            publishFinishedOnce();
        }
    }

    private void executeWithProcess(File script, boolean elf, File workspace) throws Exception {
        if (channelCloseRequested) throw new IllegalStateException("通道已关闭");
        ProcessBuilder builder;
        if (root) {
            String enterWorkspace = "cd " + shellQuote(workspace.getAbsolutePath())
                    + " && export HOME=" + shellQuote(workspace.getAbsolutePath()) + " && ";
            String command = elf
                    ? enterWorkspace + "chmod 700 " + shellQuote(path)
                    + " && exec " + shellQuote(path)
                    : enterWorkspace + "exec /system/bin/sh " + shellQuote(path);
            builder = new ProcessBuilder("su", "-c", command);
        } else if (elf) {
            //noinspection ResultOfMethodCallIgnored
            script.setExecutable(true, true);
            builder = new ProcessBuilder(path);
        } else {
            builder = new ProcessBuilder("/system/bin/sh", path);
        }
        builder.directory(workspace);
        builder.redirectErrorStream(true);
        builder.environment().put("TERM", "xterm-256color");
        builder.environment().put("LANG", "C.UTF-8");
        builder.environment().put("SHELL", "/system/bin/sh");
        builder.environment().put("HOME", workspace.getAbsolutePath());
        Process current = builder.start();
        process = current;
        if (channelCloseRequested) {
            current.destroy();
            throw new IllegalStateException("通道已关闭");
        }
        setState(ScriptExecutionService.STATE_RUNNING, null);
        service.onSessionStatus(this, "正在运行", true);
        scheduleProcessReplay(current);

        try (java.io.Reader reader = new java.io.InputStreamReader(
                current.getInputStream(), StandardCharsets.UTF_8)) {
            char[] buffer = new char[2048];
            int count;
            while ((count = reader.read(buffer)) != -1) {
                appendScriptOutput(new String(buffer, 0, count));
            }
        }
        int result = current.waitFor();
        exitCode = result;
        if (channelCloseRequested) {
            appendOutput("\n[当前通道已关闭，已脱离的后台进程不受影响]\n");
            setState(ScriptExecutionService.STATE_STOPPED, result);
        } else {
            appendOutput("\n[进程结束，退出码 " + result + "]\n");
            setState(result == 0 ? ScriptExecutionService.STATE_FINISHED
                    : ScriptExecutionService.STATE_FAILED, result);
        }
    }

    private boolean executeElfWithPty(File script, File workspace) throws Exception {
        if (channelCloseRequested) throw new IllegalStateException("通道已关闭");
        int nativeId;
        try {
            String[] environment = {
                    "TERM=xterm-256color", "LANG=C.UTF-8", "SHELL=/system/bin/sh",
                    "PATH=/system/bin:/system/xbin:/vendor/bin",
                    "HOME=" + workspace.getAbsolutePath()
            };
            nativeId = NativePTY.nativeStartPTY(32, 100,
                    root ? "su" : "/system/bin/sh", environment,
                    workspace.getAbsolutePath());
        } catch (LinkageError error) {
            appendOutput("[PTY 不可用，回退到普通执行: "
                    + error.getClass().getSimpleName() + "]\n");
            return false;
        }
        if (nativeId < 0) {
            appendOutput("[PTY 启动失败，回退到普通执行]\n");
            return false;
        }
        if (channelCloseRequested) {
            NativePTY.nativeClose(nativeId);
            throw new IllegalStateException("通道已关闭");
        }

        ptySessionId = nativeId;
        ptyClosed = false;
        setState(ScriptExecutionService.STATE_RUNNING, null);
        service.onSessionStatus(this, "正在运行 · PTY", true);

        prepareQuietPty(nativeId);
        String command = "cd " + shellQuote(workspace.getAbsolutePath())
                + " && export HOME=" + shellQuote(workspace.getAbsolutePath())
                + " && stty -echo; printf '\\036SHELLDECK_PID:%s\\037\\n' \"$$\"; chmod 700 "
                + shellQuote(script.getAbsolutePath())
                + " && exec " + shellQuote(script.getAbsolutePath())
                + "; rc=$?; exit $rc\n";
        synchronized (inputLock) {
            if (ptyClosed || channelCloseRequested || ptySessionId != nativeId) {
                throw new IllegalStateException("通道已关闭");
            }
            synchronized (ptyOutputLock) {
                ptyStartupCarry.setLength(0);
                ptyStartupPending = true;
            }
            if (NativePTY.nativeWrite(nativeId, command.getBytes(StandardCharsets.UTF_8)) < 0) {
                throw new IllegalStateException("无法向 PTY 写入启动命令");
            }
        }
        schedulePtyReplay(nativeId);

        byte[] data;
        while (!ptyClosed && (data = NativePTY.nativeRead(nativeId)) != null) {
            if (data.length > 0) appendPtyOutput(new String(data, StandardCharsets.UTF_8));
        }
        flushPtyOutput();
        if (channelCloseRequested || ptyClosed) {
            appendOutput("\n[当前通道已关闭，已脱离的后台进程不受影响]\n");
            setState(ScriptExecutionService.STATE_STOPPED, null);
            return true;
        }
        int result;
        synchronized (inputLock) {
            if (ptyClosed || channelCloseRequested || ptySessionId != nativeId) {
                appendOutput("\n[当前通道已关闭，已脱离的后台进程不受影响]\n");
                setState(ScriptExecutionService.STATE_STOPPED, null);
                return true;
            }
            if (NativePTY.nativeIsAlive(nativeId)) {
                throw new IllegalStateException("PTY 输出意外中断");
            }
            result = NativePTY.nativeGetExitCode(nativeId);
        }
        exitCode = result >= 0 ? result : null;
        if (result >= 0) {
            appendOutput("\n[PTY 进程结束，退出码 " + result + "]\n");
            setState(result == 0 ? ScriptExecutionService.STATE_FINISHED
                    : ScriptExecutionService.STATE_FAILED, result);
        } else {
            appendOutput("\n[PTY 进程已结束，未取得退出码]\n");
            setState(ScriptExecutionService.STATE_FINISHED, null);
        }
        return true;
    }

    private void closePtyOnce() {
        synchronized (inputLock) {
            int nativeId = ptySessionId;
            if (nativeId < 0 || ptyClosed) return;
            ptyClosed = true;
            ptySessionId = -1;
            NativePTY.nativeClose(nativeId);
        }
    }

    private void rememberInput(String input, String prompt) {
        if (rememberManualInputs && scriptId != null && scriptHash != null) {
            new InputMemoryStore(service).append(scriptId, scriptHash, input, prompt);
        }
    }

    private void schedulePtyReplay(int nativeId) {
        if (replayInputs.isEmpty()) return;
        resetReplayOutputWindow();
        new Thread(() -> {
            appendOutput("[自动输入已启用：等待脚本提示后依次发送 "
                    + replayInputs.size() + " 项]\n");
            if (!waitForPrompt(replayPrompts.get(0), nativeId, null)) return;
            int sent = 0;
            for (int index = 0; index < replayInputs.size(); index++) {
                String input = replayInputs.get(index);
                resetReplayOutputWindow();
                synchronized (inputLock) {
                    if (!isReplayTargetActive(nativeId, null)) break;
                    String line = input.endsWith("\n") ? input : input + "\n";
                    if (NativePTY.nativeWrite(nativeId,
                            line.getBytes(StandardCharsets.UTF_8)) < 0) break;
                    sent++;
                }
                appendOutput("[自动输入 " + sent + "/" + replayInputs.size()
                        + (sent < replayInputs.size() ? "，等待下一步响应" : "，已全部发送") + "]\n");
                if (sent < replayInputs.size()) {
                    if (!waitForPrompt(replayPrompts.get(index + 1), nativeId, null)) break;
                }
            }
        }, "pty-replay-" + id.substring(0, Math.min(8, id.length()))).start();
    }

    private void scheduleProcessReplay(Process target) {
        if (replayInputs.isEmpty()) return;
        resetReplayOutputWindow();
        new Thread(() -> {
            appendOutput("[自动输入已启用：等待脚本提示后依次发送 "
                    + replayInputs.size() + " 项]\n");
            if (!waitForPrompt(replayPrompts.get(0), -1, target)) return;
            int sent = 0;
            for (int index = 0; index < replayInputs.size(); index++) {
                String input = replayInputs.get(index);
                resetReplayOutputWindow();
                synchronized (inputLock) {
                    if (!isReplayTargetActive(-1, target)) break;
                    try {
                        OutputStream stream = target.getOutputStream();
                        String line = input.endsWith("\n") ? input : input + "\n";
                        stream.write(line.getBytes(StandardCharsets.UTF_8));
                        stream.flush();
                        sent++;
                    } catch (Exception ignored) {
                        break;
                    }
                }
                appendOutput("[自动输入 " + sent + "/" + replayInputs.size()
                        + (sent < replayInputs.size() ? "，等待下一步响应" : "，已全部发送") + "]\n");
                if (sent < replayInputs.size()) {
                    if (!waitForPrompt(replayPrompts.get(index + 1), -1, target)) break;
                }
            }
        }, "process-replay-" + id.substring(0, Math.min(8, id.length()))).start();
    }

    private boolean waitForPrompt(String expectedPrompt, int nativeId, Process target) {
        synchronized (replayOutputLock) {
            while (isReplayTargetActive(nativeId, target)) {
                try {
                    if (!isPromptReady(expectedPrompt)) {
                        replayOutputLock.wait(1000);
                        continue;
                    }
                    long observed = scriptOutputGeneration;
                    replayOutputLock.wait(220);
                    if (scriptOutputGeneration == observed && isPromptReady(expectedPrompt)) {
                        return true;
                    }
                } catch (InterruptedException ignored) {
                    Thread.currentThread().interrupt();
                    return false;
                }
            }
            return false;
        }
    }

    private boolean isPromptReady(String expectedPrompt) {
        String rawVisible = replayOutputWindow.toString();
        String visible = normalizePrompt(rawVisible);
        if (visible.isEmpty()) return false;
        String expected = normalizePrompt(expectedPrompt);
        if (!expected.isEmpty()) return visible.contains(expected);
        String trimmed = rawVisible.trim();
        int lineStart = Math.max(trimmed.lastIndexOf('\n'), trimmed.lastIndexOf('\r'));
        String tail = normalizePrompt(trimmed.substring(lineStart + 1));
        if (tail.isEmpty()) return false;
        String lower = tail.toLowerCase(java.util.Locale.ROOT);
        char last = tail.charAt(tail.length() - 1);
        return ":：?？>#＃$]】)）".indexOf(last) >= 0
                || lower.contains("请输入") || lower.contains("请选择")
                || lower.contains("输入") || lower.contains("选择")
                || lower.contains("确认") || lower.contains("继续")
                || lower.contains("password") || lower.contains("token")
                || lower.contains("key") || lower.contains("yes/no")
                || lower.contains("y/n");
    }

    private boolean isReplayTargetActive(int nativeId, Process target) {
        if (channelCloseRequested || manualInputOverride.get()) return false;
        if (target != null) return process == target && target.isAlive();
        return nativeId >= 0 && !ptyClosed && ptySessionId == nativeId;
    }

    private void resetReplayOutputWindow() {
        synchronized (replayOutputLock) {
            replayOutputWindow.setLength(0);
        }
    }

    private void appendScriptOutput(String raw) {
        if (raw == null || raw.isEmpty()) return;
        String visible = ANSI.matcher(raw).replaceAll("")
                .replace("\r\n", "\n").replace('\r', '\n');
        synchronized (replayOutputLock) {
            replayOutputWindow.append(visible);
            if (replayOutputWindow.length() > 16_384) {
                replayOutputWindow.delete(0, replayOutputWindow.length() - 16_384);
            }
            scriptOutputGeneration++;
            replayOutputLock.notifyAll();
        }
        appendOutput(raw);
    }

    private String capturePromptAnchor() {
        String snapshot;
        synchronized (outputLock) {
            snapshot = output.toString();
        }
        String[] lines = snapshot.replace('\r', '\n').split("\n", -1);
        for (int index = lines.length - 1; index >= 0; index--) {
            String line = normalizePrompt(lines[index]);
            if (line.isEmpty() || isInternalOutputLine(line)) continue;
            return line.length() > 240 ? line.substring(line.length() - 240) : line;
        }
        return "";
    }

    private static boolean isInternalOutputLine(String line) {
        return line.startsWith("[自动输入") || line.startsWith("[已切换为手动输入")
                || line.startsWith("[进程") || line.startsWith("[当前通道")
                || line.startsWith("[启动") || line.startsWith("$ ");
    }

    private static String normalizePrompt(String value) {
        if (value == null) return "";
        return ANSI.matcher(value).replaceAll("").replaceAll("\\s+", " ").trim();
    }

    private void wakeReplayWaiter() {
        synchronized (replayOutputLock) {
            replayOutputLock.notifyAll();
        }
    }

    private void prepareQuietPty(int nativeId) throws Exception {
        String setup = "export PS1= PS2=; stty -echo && printf '\\036SHELLDECK_READY\\037' "
                + "|| printf '\\036SHELLDECK_ECHO_FAILED\\037'\n";
        synchronized (inputLock) {
            if (ptyClosed || channelCloseRequested || ptySessionId != nativeId) {
                throw new IllegalStateException("通道已关闭");
            }
            if (NativePTY.nativeWrite(nativeId, setup.getBytes(StandardCharsets.UTF_8)) < 0) {
                throw new IllegalStateException("无法初始化 PTY");
            }
        }
        StringBuilder startup = new StringBuilder();
        byte[] data;
        while (!ptyClosed && (data = NativePTY.nativeRead(nativeId)) != null) {
            startup.append(new String(data, StandardCharsets.UTF_8));
            if (startup.indexOf(PTY_READY_MARKER) >= 0) return;
            if (startup.indexOf(PTY_ECHO_FAILED_MARKER) >= 0) {
                throw new IllegalStateException("PTY 无法关闭命令回显");
            }
            if (startup.length() > 16_384) startup.delete(0, startup.length() - 4096);
        }
        if (channelCloseRequested || ptyClosed) throw new IllegalStateException("通道已关闭");
        throw new IllegalStateException("Root PTY 在初始化时退出");
    }

    private void appendPtyOutput(String raw) {
        synchronized (ptyOutputLock) {
            raw = consumePtyStartup(raw);
            if (raw.isEmpty()) return;
            String value = ptyLineCarry + raw;
            ptyLineCarry.setLength(0);
            int start = 0;
            int newline;
            while ((newline = value.indexOf('\n', start)) >= 0) {
                String line = value.substring(start, newline + 1);
                if (!isKnownSystemLinkerWarning(line)) appendScriptOutput(line);
                start = newline + 1;
            }
            if (start >= value.length()) return;
            String tail = value.substring(start);
            String prefix = "WARNING: linker:";
            if (prefix.startsWith(tail) || tail.startsWith(prefix)) {
                ptyLineCarry.append(tail);
            }
            else appendScriptOutput(tail);
        }
    }

    private String consumePtyStartup(String raw) {
        if (!ptyStartupPending) return raw;
        ptyStartupCarry.append(raw);
        java.util.regex.Matcher marker = PTY_PID_MARKER.matcher(ptyStartupCarry);
        if (!marker.find()) {
            if (ptyStartupCarry.length() > 131_072) {
                ptyStartupCarry.delete(0, ptyStartupCarry.length() - 65_536);
            }
            return "";
        }
        String remainder = ptyStartupCarry.substring(marker.end());
        ptyStartupCarry.setLength(0);
        ptyStartupPending = false;
        if (remainder.startsWith("\r\n")) return remainder.substring(2);
        if (remainder.startsWith("\r") || remainder.startsWith("\n")) {
            return remainder.substring(1);
        }
        return remainder;
    }

    private void publishFinishedOnce() {
        if (finishPublished.compareAndSet(false, true)) service.onSessionFinished(this);
    }

    private void flushPtyOutput() {
        synchronized (ptyOutputLock) {
            ptyStartupCarry.setLength(0);
            ptyStartupPending = false;
            if (ptyLineCarry.length() == 0) return;
            String tail = ptyLineCarry.toString();
            ptyLineCarry.setLength(0);
            if (!isKnownSystemLinkerWarning(tail)) appendScriptOutput(tail);
        }
    }

    private static boolean isKnownSystemLinkerWarning(String line) {
        return (line.contains("WARNING: linker: readlink(\"/proc/self/fd/")
                && line.contains("failed: No such file or directory"))
                || (line.contains("WARNING: linker: unable to get realpath for the library \"")
                && line.contains("\". Will use given path."));
    }

    private void appendOutput(String raw) {
        String chunk = ANSI.matcher(raw).replaceAll("")
                .replace("\r\n", "\n").replace('\r', '\n');
        List<ScriptExecutionService.Listener> recipients;
        synchronized (outputLock) {
            output.append(chunk);
            if (output.length() > MAX_OUTPUT_CHARS) {
                output.delete(0, output.length() - MAX_OUTPUT_CHARS);
            }
            recipients = new ArrayList<>(listeners);
        }
        service.handler().post(() -> {
            for (ScriptExecutionService.Listener listener : recipients) listener.onOutput(chunk);
        });
    }

    private void setState(int value, Integer code) {
        state = value;
        exitCode = code;
        wakeReplayWaiter();
        service.handler().post(() -> {
            for (ScriptExecutionService.Listener listener : listeners) {
                listener.onStateChanged(value, code);
            }
        });
    }

    private static String shellQuote(String value) {
        return "'" + value.replace("'", "'\\''") + "'";
    }

    private static boolean isElf(File file) {
        byte[] magic = new byte[4];
        try (FileInputStream input = new FileInputStream(file)) {
            return input.read(magic) == magic.length && magic[0] == 0x7f
                    && magic[1] == 'E' && magic[2] == 'L' && magic[3] == 'F';
        } catch (Exception ignored) {
            return false;
        }
    }
}
