package com.local.shelldeck;

import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.graphics.drawable.Drawable;
import android.os.Process;
import android.util.LruCache;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.HashMap;
import java.util.Set;
import java.util.concurrent.TimeUnit;

final class RootProcessRepository {
    private static final String PS_FIELDS = "PID,PPID,USER,STAT,PCPU,RSS,NAME,ARGS";
    private static final String[] PROTECTED_PREFIXES = {
            "android.", "com.android.", "com.google.android.", "com.oplus.",
            "com.coloros.", "com.heytap.", "com.nearme.", "com.oneplus.",
            "com.qualcomm.", "com.qti.", "com.mediatek.", "org.codeaurora.", "vendor.",
            "/system/", "/vendor/", "/apex/", "/product/", "/system_ext/"
    };
    private static final Set<String> PROTECTED_NAMES = Collections.unmodifiableSet(new HashSet<>(Arrays.asList(
            "init", "kthreadd", "system_server", "zygote", "zygote64",
            "surfaceflinger", "servicemanager", "hwservicemanager", "vndservicemanager",
            "lmkd", "logd", "netd", "vold", "installd", "keystore2", "apexd",
            "ueventd", "adbd", "statsd", "tombstoned", "audioserver", "cameraserver",
            "mediaserver", "drmserver", "gatekeeperd", "healthd", "update_engine",
            "wificond", "incidentd", "dumpstate", "bootanimation")));

    static final class KillResult {
        final boolean success;
        final String message;

        KillResult(boolean success, String message) {
            this.success = success;
            this.message = message;
        }
    }

    private final Context context;
    private final PackageManager packageManager;
    private final Map<String, String> appLabels = new HashMap<>();
    private final Set<String> installedPackages = new HashSet<>();
    private final Set<String> protectedPackages = new HashSet<>();
    private final LruCache<String, Drawable> iconCache = new LruCache<>(64);
    private boolean packageMetadataLoaded;

    RootProcessRepository(Context context) {
        this.context = context.getApplicationContext();
        packageManager = this.context.getPackageManager();
    }

    List<RootProcess> load() throws IOException {
        ensurePackageMetadata();
        List<RootProcess> result = attachSafetyData(parse(runRoot(
                "ps -A -w -o " + PS_FIELDS), Collections.emptySet(), appLabels,
                installedPackages, protectedPackages, runsPrefix()));
        result.removeIf(RootProcessRepository::isSamplerProcess);
        result.sort(Comparator.comparingDouble((RootProcess value) -> value.cpuPercent)
                .reversed().thenComparing(Comparator.comparingLong(
                        (RootProcess value) -> value.rssKb).reversed()));
        return result;
    }

    private static boolean isSamplerProcess(RootProcess process) {
        return "ps".equals(process.name)
                && process.arguments.contains("-A")
                && process.arguments.contains(PS_FIELDS);
    }

    KillResult kill(RootProcess expected) {
        try {
            RootProcess current = find(expected.pid);
            if (current == null) return new KillResult(true, "进程已经结束");
            if (!sameIdentity(expected, current) || !current.canKill()) {
                return new KillResult(false, "安全校验未通过，已拦截");
            }

            signalIfIdentityMatches(expected, 15);
            Thread.sleep(800);
            current = find(expected.pid);
            if (current == null) return new KillResult(true, "进程已结束");
            if (!sameIdentity(expected, current) || !current.canKill()) {
                return new KillResult(false, "PID 已变化，已停止操作");
            }
            signalIfIdentityMatches(expected, 9);
            Thread.sleep(120);
            return find(expected.pid) == null
                    ? new KillResult(true, "进程已强制结束")
                    : new KillResult(false, "进程仍在运行");
        } catch (Exception error) {
            String message = error.getMessage();
            return new KillResult(false, message == null ? "结束进程失败" : message);
        }
    }

    private RootProcess find(int pid) throws IOException {
        List<RootProcess> values = attachSafetyData(parse(runRoot(
                "ps -p " + pid + " -w -o " + PS_FIELDS + " 2>/dev/null || true"),
                Collections.emptySet(), appLabels, installedPackages,
                protectedPackages, runsPrefix()));
        for (RootProcess value : values) if (value.pid == pid) return value;
        return null;
    }

    private static boolean hasProtectedPrefix(String identity) {
        for (String prefix : PROTECTED_PREFIXES) {
            if (identity.startsWith(prefix) || identity.contains(" " + prefix)) return true;
        }
        return false;
    }

    private static boolean sameIdentity(RootProcess expected, RootProcess current) {
        return expected.pid == current.pid
                && expected.startTimeTicks > 0
                && expected.startTimeTicks == current.startTimeTicks
                && expected.user.equals(current.user)
                && expected.name.equals(current.name)
                && expected.arguments.equals(current.arguments);
    }

    static List<RootProcess> parse(String output, Set<String> scripts) {
        return parse(output, scripts, Collections.emptyMap());
    }

    static List<RootProcess> parse(String output, Set<String> scripts,
                                   Map<String, String> appLabels) {
        return parseInternal(output, scripts, appLabels,
                Collections.emptySet(), Collections.emptySet(), false, null);
    }

    private static List<RootProcess> parse(String output, Set<String> scripts,
                                           Map<String, String> appLabels,
                                           Set<String> installedPackages,
                                           Set<String> protectedPackages,
                                           String runsPrefix) {
        return parseInternal(output, scripts, appLabels,
                installedPackages, protectedPackages, true, runsPrefix);
    }

    private static List<RootProcess> parseInternal(String output, Set<String> scripts,
                                                   Map<String, String> appLabels,
                                                    Set<String> installedPackages,
                                                    Set<String> protectedPackages,
                                                    boolean strict, String runsPrefix) {
        List<RootProcess> result = new ArrayList<>();
        String[] lines = output.split("\\r?\\n");
        for (String raw : lines) {
            String line = raw.trim();
            if (line.isEmpty() || line.startsWith("PID ")) continue;
            String[] values = line.split("\\s+", 8);
            if (values.length < 7) continue;
            try {
                int pid = Integer.parseInt(values[0]);
                int parentPid = Integer.parseInt(values[1]);
                String user = values[2];
                String state = values[3];
                float cpu = Float.parseFloat(values[4]);
                long rss = Long.parseLong(values[5]);
                String name = values[6];
                String arguments = values.length == 8 ? values[7] : name;
                String packageName = extractPackageName(name, arguments);
                RootProcess.Kind kind = strict
                         ? classifyStrict(pid, parentPid, user, name, arguments, scripts,
                         packageName, installedPackages, protectedPackages, runsPrefix)
                        : classifyStatic(pid, parentPid, user, name, arguments, scripts, null);
                String displayName = packageName == null ? name
                        : appLabels.getOrDefault(packageName, name);
                result.add(new RootProcess(pid, parentPid, user, state, cpu, rss,
                        name, arguments, displayName, packageName, kind, 0));
            } catch (NumberFormatException ignored) {
                // Ignore a process that disappeared while ps was formatting it.
            }
        }
        return result;
    }

    private synchronized void ensurePackageMetadata() {
        if (packageMetadataLoaded) return;
        loadInstalledApps();
        packageMetadataLoaded = true;
    }

    private void loadInstalledApps() {
        try {
            List<ApplicationInfo> applications = packageManager.getInstalledApplications(0);
            for (ApplicationInfo application : applications) {
                installedPackages.add(application.packageName);
                CharSequence label = packageManager.getApplicationLabel(application);
                if (label != null && label.length() > 0) {
                    appLabels.put(application.packageName, label.toString());
                }
                int protectedFlags = ApplicationInfo.FLAG_SYSTEM
                        | ApplicationInfo.FLAG_UPDATED_SYSTEM_APP
                        | ApplicationInfo.FLAG_PERSISTENT;
                if ((application.flags & protectedFlags) != 0
                        || application.uid < Process.FIRST_APPLICATION_UID
                        || application.packageName.equals(context.getPackageName())
                        || hasProtectedPrefix(application.packageName.toLowerCase(Locale.ROOT))) {
                    protectedPackages.add(application.packageName);
                }
            }
        } catch (RuntimeException ignored) {
            // If package metadata cannot be read, strict classification locks unknown apps.
        }
    }

    Drawable loadAppIcon(String packageName) {
        if (packageName == null || packageName.isEmpty()) return null;
        return iconCache.get(packageName);
    }

    void preloadAppIcons(List<RootProcess> processes) {
        Set<String> packages = new HashSet<>();
        for (RootProcess process : processes) {
            if (process.packageName != null && !process.packageName.isEmpty()) {
                packages.add(process.packageName);
            }
        }
        for (String packageName : packages) {
            if (iconCache.get(packageName) != null) continue;
            try {
                Drawable icon = packageManager.getApplicationIcon(packageName);
                if (icon != null) iconCache.put(packageName, icon);
            } catch (PackageManager.NameNotFoundException | RuntimeException ignored) {
                // Missing icons keep using the lightweight default drawable.
            }
        }
    }

    private static String extractPackageName(String name, String arguments) {
        String[] candidates = {firstToken(arguments), name};
        for (String candidate : candidates) {
            int suffix = candidate.indexOf(':');
            if (suffix > 0) candidate = candidate.substring(0, suffix);
            if (candidate.matches("[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z0-9_]+)+")) {
                return candidate;
            }
        }
        return null;
    }

    private static String firstToken(String value) {
        int space = value.indexOf(' ');
        return space < 0 ? value : value.substring(0, space);
    }

    private static RootProcess.Kind classifyStatic(int pid, int parentPid, String user,
                                                   String name, String arguments,
                                                   Set<String> scripts, String runsPrefix) {
        if (pid <= 100 || pid == Process.myPid() || parentPid == 2 || name.startsWith("[")) {
            return RootProcess.Kind.PROTECTED;
        }
        String identity = (name + " " + arguments).toLowerCase(Locale.ROOT);
        if (PROTECTED_NAMES.contains(name.toLowerCase(Locale.ROOT)) || hasProtectedPrefix(identity)) {
            return RootProcess.Kind.PROTECTED;
        }
        if (isRunScriptInvocation(name, arguments, runsPrefix)) return RootProcess.Kind.SCRIPT;
        if (identity.contains("com.local.shelldeck")) return RootProcess.Kind.PROTECTED;
        if (user.matches("u\\d+_a\\d+")) return RootProcess.Kind.APP;
        return RootProcess.Kind.PROTECTED;
    }

    private static RootProcess.Kind classifyStrict(int pid, int parentPid, String user,
                                                   String name, String arguments,
                                                   Set<String> scripts, String packageName,
                                                   Set<String> installedPackages,
                                                   Set<String> protectedPackages,
                                                   String runsPrefix) {
        if (packageName != null && protectedPackages.contains(packageName)) {
            return RootProcess.Kind.PROTECTED;
        }
        RootProcess.Kind base = classifyStatic(
                pid, parentPid, user, name, arguments, scripts, runsPrefix);
        if (base == RootProcess.Kind.SCRIPT || base == RootProcess.Kind.PROTECTED) return base;
        if (packageName == null || !installedPackages.contains(packageName)
                || protectedPackages.contains(packageName)) {
            return RootProcess.Kind.PROTECTED;
        }
        return RootProcess.Kind.APP;
    }

    private List<RootProcess> attachSafetyData(List<RootProcess> values) {
        List<RootProcess> safe = new ArrayList<>(values.size());
        for (RootProcess value : values) {
            if (value.kind != RootProcess.Kind.SCRIPT || !hasOwnedRunFile(value.arguments)) {
                safe.add(value.kind == RootProcess.Kind.SCRIPT
                        ? value.withSafety(RootProcess.Kind.PROTECTED, 0) : value);
                continue;
            }
            long startTime = readStartTime(value.pid);
            safe.add(value.withSafety(startTime > 0
                    ? RootProcess.Kind.SCRIPT : RootProcess.Kind.PROTECTED, startTime));
        }
        return safe;
    }

    private long readStartTime(int pid) {
        try {
            String stat = runRoot("cat /proc/" + pid + "/stat").trim();
            int commandEnd = stat.lastIndexOf(')');
            if (commandEnd < 0 || commandEnd + 2 >= stat.length()) return 0;
            String[] fields = stat.substring(commandEnd + 2).trim().split("\\s+");
            return fields.length > 19 ? Long.parseLong(fields[19]) : 0;
        } catch (Exception ignored) {
            return 0;
        }
    }

    private void signalIfIdentityMatches(RootProcess expected, int signal) throws IOException {
        if (expected.startTimeTicks <= 0 || (signal != 9 && signal != 15)) {
            throw new IOException("进程安全信息无效，已拦截");
        }
        String command = "stat=$(cat /proc/" + expected.pid + "/stat 2>/dev/null) || exit 3; "
                + "rest=${stat##*) }; set -- $rest; "
                + "[ \"${20}\" = \"" + expected.startTimeTicks + "\" ] || exit 4; "
                + "kill -" + signal + " " + expected.pid;
        runRoot(command);
    }

    private boolean hasOwnedRunFile(String arguments) {
        File rawRuns = new File(context.getFilesDir(), "runs");
        String path = extractRunScriptPath(arguments, runsPrefix());
        if (path == null) return false;
        try {
            File script = new File(path).getCanonicalFile();
            File runs = new File(context.getFilesDir(), "runs").getCanonicalFile();
            File session = script.getParentFile();
            return script.isFile() && "script.sh".equals(script.getName())
                    && session != null && session.getName().matches("[A-Za-z0-9_-]{1,80}")
                    && runs.equals(session.getParentFile());
        } catch (IOException ignored) {
            return false;
        }
    }

    private String runsPrefix() {
        File rawRuns = new File(context.getFilesDir(), "runs");
        try {
            return rawRuns.getCanonicalPath() + File.separator;
        } catch (IOException ignored) {
            return rawRuns.getAbsolutePath() + File.separator;
        }
    }

    private static boolean isRunScriptInvocation(String name, String arguments,
                                                 String runsPrefix) {
        String lowerName = new File(name).getName().toLowerCase(Locale.ROOT);
        boolean expectedExecutable = lowerName.equals("sh") || lowerName.equals("bash")
                || lowerName.equals("dash") || lowerName.equals("toybox")
                || lowerName.equals("busybox") || lowerName.equals("script.sh");
        return expectedExecutable && extractRunScriptPath(arguments, runsPrefix) != null;
    }

    private static String extractRunScriptPath(String arguments, String runsPrefix) {
        if (arguments == null) return null;
        String normalizedPrefix = runsPrefix == null ? null : runsPrefix.replace('\\', '/');
        for (String raw : arguments.split("\\s+")) {
            String token = raw.replace('\\', '/');
            while (!token.isEmpty() && "'\"".indexOf(token.charAt(0)) >= 0) {
                token = token.substring(1);
            }
            while (!token.isEmpty() && "'\";,".indexOf(token.charAt(token.length() - 1)) >= 0) {
                token = token.substring(0, token.length() - 1);
            }
            int suffix = token.lastIndexOf("/script.sh");
            if (suffix < 0 || suffix + 10 != token.length()) continue;
            if (normalizedPrefix != null) {
                if (!token.startsWith(normalizedPrefix)) continue;
                String session = token.substring(normalizedPrefix.length(), suffix);
                if (session.matches("[A-Za-z0-9_-]{1,80}")) return token;
            } else if (token.matches("/data/(?:user/\\d+|data)/com\\.local\\.shelldeck/"
                    + "files/runs/[A-Za-z0-9_-]{1,80}/script\\.sh")) {
                return token;
            }
        }
        return null;
    }

    private String runRoot(String command) throws IOException {
        java.lang.Process process = null;
        try {
            process = new ProcessBuilder("su", "-c", command).redirectErrorStream(true).start();
            StringBuilder output = new StringBuilder();
            java.lang.Process target = process;
            Thread readerThread = new Thread(() -> {
                try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                        target.getInputStream(), StandardCharsets.UTF_8))) {
                    String line;
                    while ((line = reader.readLine()) != null) {
                        synchronized (output) {
                            if (output.length() < 2_000_000) output.append(line).append('\n');
                        }
                    }
                } catch (IOException ignored) {
                    // Destroying a timed-out process closes the stream.
                }
            }, "root-output");
            readerThread.start();
            if (!process.waitFor(8, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                try {
                    readerThread.join(500);
                } catch (InterruptedException error) {
                    Thread.currentThread().interrupt();
                }
                throw new IOException("Root 命令超时");
            }
            try {
                readerThread.join(1000);
            } catch (InterruptedException error) {
                Thread.currentThread().interrupt();
                throw new IOException("操作已中断", error);
            }
            if (process.exitValue() != 0) {
                String message;
                synchronized (output) {
                    message = output.toString().trim();
                }
                throw new IOException(message.isEmpty() ? "Root 命令失败" : message);
            }
            synchronized (output) {
                return output.toString();
            }
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            throw new IOException("操作已中断", error);
        } finally {
            if (process != null && process.isAlive()) process.destroyForcibly();
        }
    }
}
