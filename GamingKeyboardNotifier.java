package org.blissos.gamingkeyboard;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.Context;

/**
 * Shows the Gaming Keyboard notifications:
 *  - "keymap detected" when a mapped game is installed again (brief)
 *  - "keymap active" while a mapped game is in the foreground (stays until it leaves)
 * Needs Android 8.0 (API 26) or newer.
 */
public final class GamingKeyboardNotifier implements GameDetectorNative.Listener {
    private static final String CHANNEL_DETECTED = "gaming_keyboard_detected";
    private static final String CHANNEL_ACTIVE = "gaming_keyboard_active";
    private static final int ID_DETECTED = 1001;
    private static final int ID_ACTIVE = 1002;
    private static final long DETECTED_TIMEOUT_MS = 8000;

    // Placeholder icon. Replace with the project's own small icon.
    private static final int SMALL_ICON = android.R.drawable.ic_dialog_info;

    private final Context context;
    private final NotificationManager manager;

    public GamingKeyboardNotifier(Context context) {
        this.context = context;
        this.manager = context.getSystemService(NotificationManager.class);
        createChannels();
    }

    private void createChannels() {
        if (manager == null) return;

        NotificationChannel detected = new NotificationChannel(
                CHANNEL_DETECTED, "Keymap detected", NotificationManager.IMPORTANCE_DEFAULT);
        detected.setDescription("Shown when a game with a saved keymap is installed again");
        manager.createNotificationChannel(detected);

        NotificationChannel active = new NotificationChannel(
                CHANNEL_ACTIVE, "Keymap active", NotificationManager.IMPORTANCE_LOW);
        active.setDescription("Shown while a mapped game is open and its keymap is ready");
        active.setShowBadge(false);
        manager.createNotificationChannel(active);
    }

    @Override
    public void onKeymapDetected(String displayName) {
        if (manager == null) return;
        Notification notification = new Notification.Builder(context, CHANNEL_DETECTED)
                .setSmallIcon(SMALL_ICON)
                .setContentTitle("Gaming Keyboard")
                .setContentText("Keymap detected for " + displayName)
                .setAutoCancel(true)
                .setTimeoutAfter(DETECTED_TIMEOUT_MS)
                .setCategory(Notification.CATEGORY_STATUS)
                .build();
        manager.notify(ID_DETECTED, notification);
    }

    @Override
    public void onOverlayActive(String displayName) {
        if (manager == null) return;
        Notification notification = new Notification.Builder(context, CHANNEL_ACTIVE)
                .setSmallIcon(SMALL_ICON)
                .setContentTitle("Gaming Keyboard active")
                .setContentText("Keymap ready for " + displayName)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setCategory(Notification.CATEGORY_STATUS)
                .build();
        manager.notify(ID_ACTIVE, notification);
    }

    @Override
    public void onOverlayCleared() {
        if (manager == null) return;
        manager.cancel(ID_ACTIVE);
    }

    /** Removes both notifications. Call when the service shuts down. */
    public void cancelAll() {
        if (manager == null) return;
        manager.cancel(ID_DETECTED);
        manager.cancel(ID_ACTIVE);
    }
}
