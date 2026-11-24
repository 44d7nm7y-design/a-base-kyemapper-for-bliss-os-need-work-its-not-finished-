package xtr.keymapper.editor;

import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.os.IBinder;

import androidx.appcompat.view.ContextThemeWrapper;

import xtr.keymapper.R;
import xtr.keymapper.keymap.KeymapConfig;

public class ShowKeymapService extends Service {
    private EditorUI editorUi;

    public static void start(Context context, String selectedProfile) {
        Intent intent = new Intent(context, ShowKeymapService.class);
        intent.putExtra(EditorActivity.PROFILE_NAME, selectedProfile);
        context.startService(intent);
    }

    @Override
    public void onDestroy() {
        if (editorUi != null) {
            editorUi.hideView();
            editorUi = null;
        }
        super.onDestroy();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (editorUi != null) {
            editorUi.hideView();
        }

        String selectedProfile = (intent != null) ? intent.getStringExtra(EditorActivity.PROFILE_NAME) : null;
        if (selectedProfile == null) {
            selectedProfile = "Default";
        }

        KeymapConfig keymapConfig = new KeymapConfig(this);
        Context themedContext = new ContextThemeWrapper(this, R.style.Theme_XtMapper);

        editorUi = new EditorUI(themedContext, editorCallback, selectedProfile, EditorUI.SHOW_KEYMAP_ONLY);
        editorUi.loadKeymapAfterView();
        editorUi.showControls(keymapConfig.showControlsOpacity);

        return START_NOT_STICKY;
    }

    private final EditorCallback editorCallback = () -> {
        editorUi = null;
        stopSelf();
    };

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}