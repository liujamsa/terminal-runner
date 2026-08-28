package com.local.shelldeck;

final class RootProcess {
    enum Kind { SCRIPT, APP, PROTECTED }

    final int pid;
    final int parentPid;
    final String user;
    final String state;
    final float cpuPercent;
    final long rssKb;
    final String name;
    final String arguments;
    final String displayName;
    final String packageName;
    final Kind kind;
    final long startTimeTicks;

    RootProcess(int pid, int parentPid, String user, String state, float cpuPercent,
                 long rssKb, String name, String arguments, String displayName,
                 String packageName, Kind kind, long startTimeTicks) {
        this.pid = pid;
        this.parentPid = parentPid;
        this.user = user;
        this.state = state;
        this.cpuPercent = cpuPercent;
        this.rssKb = rssKb;
        this.name = name;
        this.arguments = arguments;
        this.displayName = displayName;
        this.packageName = packageName;
        this.kind = kind;
        this.startTimeTicks = startTimeTicks;
    }

    boolean canKill() {
        return kind == Kind.SCRIPT && startTimeTicks > 0;
    }

    RootProcess withSafety(Kind safeKind, long safeStartTimeTicks) {
        return new RootProcess(pid, parentPid, user, state, cpuPercent, rssKb, name,
                arguments, displayName, packageName, safeKind, safeStartTimeTicks);
    }
}
