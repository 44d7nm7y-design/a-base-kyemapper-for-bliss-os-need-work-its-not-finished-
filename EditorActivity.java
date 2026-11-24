package xtr.keymapper.editor;

import android.app.Activity;
import android.os.Bundle;
import android.provider.Settings;
import android.view.WindowManager;

import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.core.view.WindowInsetsControllerCompat;

import xtr.keymapper.R;
import xtr.keymapper.activity.MainActivity;
import xtr.keymapper.keymap.KeymapConfig;

public class EditorActivity extends Activity {
    public static final String PROFILE_NAME = "profile";
    private EditorUI editor;

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        String selectedProfile = getIntent().getStringExtra(PROFILE_NAME);
        if (selectedProfile == null) {
            finish();
            return;
        }

        WindowInsetsControllerCompat windowInsetsController = WindowCompat.getInsetsController(getWindow(), getWindow().getDecorView());
        windowInsetsController.setSystemBarsBehavior(WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
        windowInsetsController.hide(WindowInsetsCompat.Type.systemBars());

        if (editor != null) {
            editor.hideView();
        }

        setTheme(R.style.Theme_XtMapper);

        editor = new EditorUI(this, this::editorCallback, selectedProfile, EditorUI.START_EDITOR);

        KeymapConfig keymapConfig = new KeymapConfig(this);
        if (Settings.canDrawOverlays(this)) {
            editor.open(keymapConfig.editorOverlay);
        } else {
            editor.open(false);
            MainActivity.checkOverlayPermission(this);
        }
    }

    private void editorCallback() {
        editor = null;
        finish();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (editor != null) {
            editor.hideView();
        }
        editor = null;
    }
}