package org.blissos.gamingkeyboard;

import android.accessibilityservice.AccessibilityService;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.SystemClock;
import android.util.Log;
import android.view.KeyEvent;
import android.view.accessibility.AccessibilityEvent;

import org.json.JSONObject;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * The launcher. Runs as an accessibility service so it can:
 *  - see which app is in the foreground (feeds the game detector)
 *  - receive the activation key before any app does, and open the overlay
 *  - turn mapped keys into taps while a mapped game is open (the engine)
 *  - hear about game installs (feeds the "keymap detected" notification)
 */
public class GamingKeyboardService extends AccessibilityService {
    private static final String TAG = "GamingKeyboardService";
    private static final long KEY_CACHE_MS = 3000;

    private GameDetectorNative detector;
    private GamingKeyboardNotifier notifier;
    private GamingKeyboardOverlay overlay;
    private GamingKeyboardEngine engine;
    private boolean overlayOpen = false;
    private boolean engineActive = false;
    private boolean ready = false;

    private String foregroundPackage = null;

    // Keys we already acted on: their repeats and release must be swallowed too.
    private final Set<Integer> consumedKeys = new HashSet<>();

    // Activation key lookup is cached so ordinary gameplay key presses
    // don't read files every time.
    private String cachedKeyPackage = null;
    private int cachedKeyCode = KeyEvent.KEYCODE_UNKNOWN;
    private long cachedKeyAtMs = 0;

    private final Map<String, Boolean> gameCache = new HashMap<>();

    private final BroadcastReceiver packageReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (!Intent.ACTION_PACKAGE_ADDED.equals(intent.getAction())) return;
            // An app update also sends "package added"; only fresh installs count.
            if (intent.getBooleanExtra(Intent.EXTRA_REPLACING, false)) return;
            Uri data = intent.getData();
            if (data == null || detector == null) return;
            String packageName = data.getSchemeSpecificPart();
            if (packageName != null) detector.onPackageInstalled(packageName);
        }
    };

    // ---------- service lifecycle ----------

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();

        if (!KeymapNative.init()) {
            Log.e(TAG, "Native keymap storage failed to start; service is idle");
            return;
        }

        notifier = new GamingKeyboardNotifier(this);
        detector = new GameDetectorNative(KeymapNative.DEFAULT_KEYMAP_DIR, notifier);
        if (!detector.start()) {
            Log.e(TAG, "Game detector failed to start; service is idle");
            detector = null;
            return;
        }

        engine = new GamingKeyboardEngine(this);

        IntentFilter filter = new IntentFilter(Intent.ACTION_PACKAGE_ADDED);
        filter.addDataScheme("package");
        registerReceiver(packageReceiver, filter, Context.RECEIVER_NOT_EXPORTED);

        ready = true;
    }

    @Override
    public void onInterrupt() {
        // Nothing to interrupt: this service gives no spoken or haptic feedback.
    }

    @Override
    public void onDestroy() {
        shutDown();
        super.onDestroy();
    }

    @Override
    public boolean onUnbind(Intent intent) {
        shutDown();
        return super.onUnbind(intent);
    }

    private void shutDown() {
        closeOverlay();
        if (ready) {
            try {
                unregisterReceiver(packageReceiver);
            } catch (IllegalArgumentException ignored) {
                // already unregistered
            }
        }
        ready = false;
        engineActive = false;
        consumedKeys.clear();
        if (engine != null) {
            engine.shutdown();
            engine = null;
        }
        if (detector != null) {
            detector.stop();
            detector = null;
        }
        if (notifier != null) {
            notifier.cancelAll();
            notifier = null;
        }
    }

    // ---------- foreground tracking ----------

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        if (!ready || event.getEventType() != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return;

        CharSequence packageSeq = event.getPackageName();
        if (packageSeq == null) return;
        String packageName = packageSeq.toString();
        if (shouldIgnore(packageName, event.getClassName())) return;
        if (packageName.equals(foregroundPackage)) return;

        String previous = foregroundPackage;
        foregroundPackage = packageName;

        // Switching apps closes the overlay without saving.
        closeOverlay();

        if (previous != null) detector.onPackageBackgrounded(previous);
        detector.onPackageForegrounded(packageName);
        refreshEngine();
    }

    // Windows that appear on top of a game without meaning "a different app".
    private boolean shouldIgnore(String packageName, CharSequence className) {
        if (packageName.equals(getPackageName())) return true;       // our own overlay
        if (packageName.equals("com.android.systemui")) return true;  // shade, volume, etc.
        if (packageName.equals("android")) return true;               // system dialogs
        return className != null
                && className.toString().startsWith("android.inputmethodservice");  // keyboards
    }

    // The engine only works while a game with a saved keymap is in front.
    private void refreshEngine() {
        if (engine == null || detector == null) return;
        if (foregroundPackage != null && foregroundPackage.equals(detector.getArmedPackage())) {
            engine.load(foregroundPackage);
            engineActive = true;
        } else {
            engine.unload();
            engineActive = false;
        }
    }

    // ---------- keys ----------

    @Override
    protected boolean onKeyEvent(KeyEvent event) {
        if (!ready) return false;
        int code = event.getKeyCode();
        int action = event.getAction();

        // Finish consuming a key we already acted on (repeats and release).
        if (consumedKeys.contains(code)) {
            if (action == KeyEvent.ACTION_UP) consumedKeys.remove(code);
            return true;
        }

        if (action != KeyEvent.ACTION_DOWN || event.getRepeatCount() != 0) return false;
        if (overlayOpen || foregroundPackage == null) return false;  // overlay gets every key
        if (event.isCtrlPressed() || event.isAltPressed() || event.isMetaPressed()) return false;

        // 1. The activation key opens the overlay.
        if (isPossibleActivationKey(code)
                && canMap(foregroundPackage)
                && code == activationKeyCodeFor(foregroundPackage)) {
            consumedKeys.add(code);
            openOverlay(foregroundPackage);
            return true;
        }

        // 2. A mapped key becomes a tap at its saved spot.
        if (engineActive && engine != null && engine.handleKeyDown(code)) {
            consumedKeys.add(code);
            return true;
        }

        return false;
    }

    private static boolean isPossibleActivationKey(int code) {
        return (code >= KeyEvent.KEYCODE_F1 && code <= KeyEvent.KEYCODE_F9)
                || (code >= KeyEvent.KEYCODE_1 && code <= KeyEvent.KEYCODE_9);
    }

    // "F1".."F9" and "1".."9" -> Android key codes.
    private static int keyCodeFor(String key) {
        if (key == null) return KeyEvent.KEYCODE_UNKNOWN;
        if (key.length() == 2 && key.charAt(0) == 'F' && key.charAt(1) >= '1' && key.charAt(1) <= '9') {
            return KeyEvent.KEYCODE_F1 + (key.charAt(1) - '1');
        }
        if (key.length() == 1 && key.charAt(0) >= '1' && key.charAt(0) <= '9') {
            return KeyEvent.KEYCODE_1 + (key.charAt(0) - '1');
        }
        return KeyEvent.KEYCODE_UNKNOWN;
    }

    private int activationKeyCodeFor(String packageName) {
        long now = SystemClock.uptimeMillis();
        if (!packageName.equals(cachedKeyPackage) || now - cachedKeyAtMs > KEY_CACHE_MS) {
            cachedKeyCode = keyCodeFor(KeymapNative.getEffectiveActivationKey(packageName));
            cachedKeyPackage = packageName;
            cachedKeyAtMs = now;
        }
        return cachedKeyCode;
    }

    // The overlay opens for games that already have a keymap, and for any
    // app Android marks as a game (so the first mapping is possible).
    private boolean canMap(String packageName) {
        if (packageName.equals(detector.getArmedPackage())) return true;
        return isGame(packageName);
    }

    @SuppressWarnings("deprecation")
    private boolean isGame(String packageName) {
        Boolean cached = gameCache.get(packageName);
        if (cached != null) return cached;
        try {
            ApplicationInfo info = getPackageManager().getApplicationInfo(packageName, 0);
            boolean game = info.category == ApplicationInfo.CATEGORY_GAME
                    || (info.flags & ApplicationInfo.FLAG_IS_GAME) != 0;
            gameCache.put(packageName, game);
            return game;
        } catch (PackageManager.NameNotFoundException e) {
            return false;
        }
    }

    // ---------- overlay ----------

    private void openOverlay(String packageName) {
        overlayOpen = true;
        try {
            overlay = new GamingKeyboardOverlay(this, packageName, displayNameFor(packageName),
                    this::onOverlayClosed);
            overlay.show();
        } catch (RuntimeException e) {
            Log.e(TAG, "Could not open the overlay", e);
            overlay = null;
            overlayOpen = false;
        }
    }

    private void closeOverlay() {
        GamingKeyboardOverlay current = overlay;
        if (current != null) current.hide();  // reports back through onOverlayClosed
    }

    // Called exactly once each time the overlay closes.
    private void onOverlayClosed(boolean saved) {
        overlay = null;
        overlayOpen = false;
        cachedKeyPackage = null;  // activation key may have changed, look it up again

        // A save makes (or updates) this game's mapping: arm it and load the new layout.
        if (saved && foregroundPackage != null && detector != null) {
            detector.onPackageForegrounded(foregroundPackage);
            refreshEngine();
        }
    }

    private String displayNameFor(String packageName) {
        JSONObject keymap = KeymapNative.loadKeymap(packageName);
        if (keymap != null) {
            String name = keymap.optString("display_name", "");
            if (!name.isEmpty()) return name;
        }
        try {
            PackageManager pm = getPackageManager();
            return pm.getApplicationLabel(pm.getApplicationInfo(packageName, 0)).toString();
        } catch (PackageManager.NameNotFoundException e) {
            return packageName;
        }
    }
}
