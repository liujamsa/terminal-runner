package com.furyform.terminal;

/** Minimal JNI bridge for the local PTY functions used by ShellDeck. */
public final class NativePTY {
    static {
        System.loadLibrary("term");
    }

    private NativePTY() {}

    public static native int nativeStartPTY(
            int rows, int cols, String shell, String[] environment, String cwd);

    public static native byte[] nativeRead(int id);

    public static native int nativeWrite(int id, byte[] data);

    public static native void nativeClose(int id);

    public static native void nativeSendSignal(int id, int signal);

    public static native boolean nativeIsAlive(int id);

    public static native int nativeGetExitCode(int id);
}
