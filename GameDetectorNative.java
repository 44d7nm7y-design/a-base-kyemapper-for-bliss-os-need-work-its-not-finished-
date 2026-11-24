package org.blissos.gamingkeyboard;

import android.util.Log;

import java.nio.charset.StandardCharsets;

/**
 * Java side of the game detector. The launcher service reports package
 * events here; the native detector decides whether a saved keymap is
 * involved and calls the Listener so notifications can be shown.
 */
public final class GameDetectorNative {
    private static final String TAG = "GameDetectorNative";

    /** Receives the detector's decisions. Called on the thread that reported the event. */
    public interface Listener {
        /** A game with a saved keymap was installed again. */
        void onKeymapDetected(String displayName);

        /** A mapped game came to the foreground and its keymap is ready. */
        void onOverlayActive(String displayName);

        /** The mapped game left the foreground. */
        void onOverlayCleared();
    }

    static {
        try {
            System.loadLibrary("gaming_keyboard_native");
        } catch (UnsatisfiedLinkError e) {
            Log.e(TAG, "Failed to load gaming_keyboard_native", e);
        }
    }

    private final String keymapDir;
    private final Listener listener;
    private volatile boolean started = false;

    public GameDetectorNative(String keymapDir, Listener listener) {
        this.keymapDir = keymapDir;
        this.listener = listener;
    }

    // ---------- native methods ----------

    private native boolean nativeCreate(String keymapDir);
    private native void nativeDestroy();
    private native void nativeOnPackageInstalled(String packageName);
    private native void nativeOnPackageForegrounded(String packageName);
    private native void nativeOnPackageBackgrounded(String packageName);
    private native String nativeCurrentArmedPackage();

    // ---------- public API ----------

    public synchronized boolean start() {
        if (started) return true;
        try {
            started = nativeCreate(keymapDir);
        } catch (UnsatisfiedLinkError e) {
            Log.e(TAG, "Native library missing", e);
            started = false;
        }
        return started;
    }

    public synchronized void stop() {
        if (!started) return;
        started = false;
        nativeDestroy();
    }

    /** Call for fresh installs and reinstalls only, not for app updates. */
    public void onPackageInstalled(String packageName) {
        if (started) nativeOnPackageInstalled(packageName);
    }

    public void onPackageForegrounded(String packageName) {
        if (started) nativeOnPackageForegrounded(packageName);
    }

    public void onPackageBackgrounded(String packageName) {
        if (started) nativeOnPackageBackgrounded(packageName);
    }

    /** The game whose keymap is armed right now, or "" if none. */
    public String getArmedPackage() {
        return started ? nativeCurrentArmedPackage() : "";
    }

    // ---------- called from native code (do not rename) ----------

    private void dispatchKeymapDetected(byte[] name) {
        listener.onKeymapDetected(new String(name, StandardCharsets.UTF_8));
    }

    private void dispatchOverlayActive(byte[] name) {
        listener.onOverlayActive(new String(name, StandardCharsets.UTF_8));
    }

    private void dispatchOverlayCleared() {
        listener.onOverlayCleared();
    }
}
