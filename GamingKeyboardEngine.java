package org.blissos.gamingkeyboard;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.GestureDescription;
import android.graphics.Path;
import android.graphics.Rect;
import android.os.Build;
import android.os.SystemClock;
import android.util.DisplayMetrics;
import android.util.Log;
import android.util.SparseArray;
import android.view.KeyEvent;
import android.view.WindowManager;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;

/**
 * Turns a key press into a tap on the screen, at the spot saved in the
 * game's keymap. Uses the accessibility service's gesture dispatch, so it
 * needs no root. Taps only: holding a key does not hold the finger down.
 * Mouse-type mappings are ignored in this version.
 *
 * Everything here runs on the main thread (the service calls it from
 * onKeyEvent), so there is no locking.
 */
public final class GamingKeyboardEngine {
    private static final String TAG = "GamingKeyboardEngine";

    private static final long TAP_DURATION_MS = 50;
    private static final long REFRESH_MS = 3000;          // re-read the keymap this often
    private static final long STUCK_DISPATCH_MS = 1500;   // give up waiting for a tap result
    private static final int MAX_QUEUED_TAPS = 10;

    private final AccessibilityService service;

    // Android key code -> the screen spots (x, y) it taps. A key mapped
    // twice taps both spots at once.
    private final SparseArray<List<int[]>> tapsByKey = new SparseArray<>();
    private final ArrayDeque<int[]> pendingTaps = new ArrayDeque<>();

    private String loadedPackage = null;
    private long loadedAtMs = 0;
    private boolean dispatching = false;
    private long dispatchStartedMs = 0;

    private final AccessibilityService.GestureResultCallback callback =
            new AccessibilityService.GestureResultCallback() {
                @Override
                public void onCompleted(GestureDescription gesture) {
                    dispatching = false;
                    pump();
                }

                @Override
                public void onCancelled(GestureDescription gesture) {
                    dispatching = false;
                    pump();
                }
            };

    public GamingKeyboardEngine(AccessibilityService service) {
        this.service = service;
    }

    // ---------- loading the game's keymap ----------

    /** (Re)reads the saved keymap for this game. */
    public void load(String packageName) {
        tapsByKey.clear();
        loadedPackage = packageName;
        loadedAtMs = SystemClock.uptimeMillis();

        JSONObject keymap = KeymapNative.loadKeymap(packageName);
        if (keymap == null) return;
        JSONArray mappings = keymap.optJSONArray("mappings");
        if (mappings == null) return;

        for (int i = 0; i < mappings.length(); i++) {
            JSONObject entry = mappings.optJSONObject(i);
            if (entry == null || !"key".equals(entry.optString("type"))) continue;

            int keyCode = keyCodeFromName(entry.optString("input_key"));
            if (keyCode == KeyEvent.KEYCODE_UNKNOWN) continue;

            JSONObject position = entry.optJSONObject("position");
            if (position == null) continue;

            List<int[]> spots = tapsByKey.get(keyCode);
            if (spots == null) {
                spots = new ArrayList<>();
                tapsByKey.put(keyCode, spots);
            }
            spots.add(new int[] {position.optInt("x"), position.optInt("y")});
        }
    }

    /** Forget everything (the foreground app has no keymap). */
    public void unload() {
        tapsByKey.clear();
        pendingTaps.clear();
        loadedPackage = null;
    }

    public void shutdown() {
        unload();
        dispatching = false;
    }

    // ---------- key presses ----------

    /**
     * Call when a key goes down. Returns true if the key is mapped and a tap
     * was sent, so the caller can keep the key away from the game.
     */
    public boolean handleKeyDown(int keyCode) {
        if (loadedPackage == null) return false;

        // The keymap may have been changed or deleted in Settings meanwhile.
        if (SystemClock.uptimeMillis() - loadedAtMs > REFRESH_MS) load(loadedPackage);

        List<int[]> spots = tapsByKey.get(keyCode);
        if (spots == null || spots.isEmpty()) return false;

        for (int[] spot : spots) {
            if (pendingTaps.size() < MAX_QUEUED_TAPS) pendingTaps.add(spot);
        }
        pump();
        return true;
    }

    // ---------- sending the taps ----------

    // Sends everything waiting as ONE gesture (several strokes at once), because
    // Android cancels a gesture when another one is sent while it is running.
    private void pump() {
        if (dispatching) {
            if (SystemClock.uptimeMillis() - dispatchStartedMs < STUCK_DISPATCH_MS) return;
            dispatching = false;  // the result never came back; carry on
        }
        if (pendingTaps.isEmpty()) return;

        Rect bounds = screenBounds();
        GestureDescription.Builder builder = new GestureDescription.Builder();
        int maxStrokes = GestureDescription.getMaxStrokeCount();
        int strokes = 0;

        while (!pendingTaps.isEmpty() && strokes < maxStrokes) {
            int[] spot = pendingTaps.poll();
            Path path = new Path();
            path.moveTo(clamp(spot[0], bounds.left, bounds.right - 1),
                        clamp(spot[1], bounds.top, bounds.bottom - 1));
            builder.addStroke(new GestureDescription.StrokeDescription(path, 0, TAP_DURATION_MS));
            strokes++;
        }

        boolean accepted = false;
        try {
            accepted = service.dispatchGesture(builder.build(), callback, null);
        } catch (RuntimeException e) {
            Log.e(TAG, "Could not send the tap", e);
        }

        if (accepted) {
            dispatching = true;
            dispatchStartedMs = SystemClock.uptimeMillis();
        } else {
            pendingTaps.clear();  // old taps fired late would be worse than lost ones
            Log.w(TAG, "Android refused the tap (is gesture permission enabled for the service?)");
        }
    }

    // ---------- helpers ----------

    @SuppressWarnings("deprecation")
    private Rect screenBounds() {
        WindowManager windowManager = service.getSystemService(WindowManager.class);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            return windowManager.getMaximumWindowMetrics().getBounds();
        }
        DisplayMetrics metrics = new DisplayMetrics();
        windowManager.getDefaultDisplay().getRealMetrics(metrics);
        return new Rect(0, 0, metrics.widthPixels, metrics.heightPixels);
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    // Saved names look like "W", "SPACE", "DPAD_UP" (Android's KEYCODE_ names without the prefix).
    private static int keyCodeFromName(String name) {
        if (name == null || name.isEmpty()) return KeyEvent.KEYCODE_UNKNOWN;
        String full = name.startsWith("KEYCODE_") ? name : "KEYCODE_" + name;
        return KeyEvent.keyCodeFromString(full);
    }
}
