package org.blissos.gamingkeyboard;

import android.util.Log;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Java side of the bridge to the native keymap storage.
 * Call init() once (the launcher service does this on start), then use
 * the other methods. Keymaps are plain JSONObjects in the format from
 * FILE_FORMAT.md.
 */
public final class KeymapNative {
    private static final String TAG = "KeymapNative";

    public static final String DEFAULT_KEYMAP_DIR = "/data/system/gaming_keyboard/keymaps";
    public static final String DEFAULT_SETTINGS_FILE = "/data/system/gaming_keyboard/settings.conf";
    public static final String FALLBACK_ACTIVATION_KEY = "F5";

    private static boolean sLoaded = false;

    static {
        try {
            System.loadLibrary("gaming_keyboard_native");
            sLoaded = true;
        } catch (UnsatisfiedLinkError e) {
            Log.e(TAG, "Failed to load gaming_keyboard_native", e);
        }
    }

    private KeymapNative() {}

    // ---------- native methods ----------

    private static native boolean nativeInit(String keymapDir, String settingsFile);
    private static native byte[] nativeLoadKeymap(String packageName);
    private static native byte[] nativeNewKeymap(String packageName, byte[] displayName);
    private static native boolean nativeSaveKeymap(byte[] keymapJson);
    private static native boolean nativeDeleteKeymap(String packageName);
    private static native byte[] nativeListGames();
    private static native String nativeGetGlobalActivationKey();
    private static native boolean nativeSetGlobalActivationKey(String key);
    private static native String nativeEffectiveActivationKey(String packageName);

    // ---------- public API ----------

    public static boolean isAvailable() {
        return sLoaded;
    }

    public static boolean init() {
        return init(DEFAULT_KEYMAP_DIR, DEFAULT_SETTINGS_FILE);
    }

    public static boolean init(String keymapDir, String settingsFile) {
        return sLoaded && nativeInit(keymapDir, settingsFile);
    }

    /** Saved keymap for a game, or null if it has none. */
    public static JSONObject loadKeymap(String packageName) {
        if (!sLoaded) return null;
        return parseObject(nativeLoadKeymap(packageName));
    }

    /** A fresh empty keymap (not saved yet). */
    public static JSONObject newKeymap(String packageName, String displayName) {
        if (!sLoaded) return null;
        return parseObject(nativeNewKeymap(packageName,
                displayName.getBytes(StandardCharsets.UTF_8)));
    }

    /** Saves the keymap, replacing any older one for the same game. */
    public static boolean saveKeymap(JSONObject keymap) {
        if (!sLoaded || keymap == null) return false;
        return nativeSaveKeymap(keymap.toString().getBytes(StandardCharsets.UTF_8));
    }

    /** Removes a game's saved keymap (Settings list delete). */
    public static boolean deleteKeymap(String packageName) {
        return sLoaded && nativeDeleteKeymap(packageName);
    }

    /** Every game with a saved keymap: [{package_name, display_name}, ...]. */
    public static JSONArray listGames() {
        if (!sLoaded) return new JSONArray();
        byte[] bytes = nativeListGames();
        if (bytes == null) return new JSONArray();
        try {
            return new JSONArray(new String(bytes, StandardCharsets.UTF_8));
        } catch (JSONException e) {
            Log.e(TAG, "Bad game list", e);
            return new JSONArray();
        }
    }

    public static String getGlobalActivationKey() {
        return sLoaded ? nativeGetGlobalActivationKey() : FALLBACK_ACTIVATION_KEY;
    }

    /** Accepts F1-F9 and 1-9 only; returns false for anything else. */
    public static boolean setGlobalActivationKey(String key) {
        return sLoaded && nativeSetGlobalActivationKey(key);
    }

    /** The key that opens the overlay for this game (its own key, else the global one). */
    public static String getEffectiveActivationKey(String packageName) {
        return sLoaded ? nativeEffectiveActivationKey(packageName) : FALLBACK_ACTIVATION_KEY;
    }

    // ---------- helpers for the overlay ----------

    /** 4-character id for a new mapping entry. */
    public static String newMappingId() {
        return String.format(Locale.ROOT, "%04x", ThreadLocalRandom.current().nextInt(0x10000));
    }

    /**
     * A new mapping entry. type is "key" or "mouse"; x and y are screen
     * pixels. Add it to the keymap's "mappings" array.
     */
    public static JSONObject newMapping(String type, String inputKey, int x, int y) {
        try {
            JSONObject position = new JSONObject();
            position.put("x", x);
            position.put("y", y);

            JSONObject entry = new JSONObject();
            entry.put("id", newMappingId());
            entry.put("type", type);
            entry.put("input_key", inputKey);
            entry.put("action_label", "");
            entry.put("position", position);
            return entry;
        } catch (JSONException e) {
            throw new IllegalStateException(e);  // cannot happen with these keys
        }
    }

    private static JSONObject parseObject(byte[] bytes) {
        if (bytes == null) return null;
        try {
            return new JSONObject(new String(bytes, StandardCharsets.UTF_8));
        } catch (JSONException e) {
            Log.e(TAG, "Bad keymap JSON", e);
            return null;
        }
    }
}
