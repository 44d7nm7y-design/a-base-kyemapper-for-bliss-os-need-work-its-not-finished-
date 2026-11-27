package org.blissos.gamingkeyboard;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.provider.Settings;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * Settings > Language & Input > Physical Keyboard > Gaming Keyboard.
 *
 * Shows every game with a saved keymap, lets you remove one, and lets you
 * choose the global activation key (F1-F9 or 1-9). This is a standalone
 * screen; the Bliss OS integrator can reuse the KeymapNative calls inside
 * the real Settings app.
 */
public class GamingKeyboardSettingsActivity extends Activity {
    private static final int COLOR_BG = 0xFF151515;
    private static final int COLOR_TEXT = 0xFFF2F2F2;
    private static final int COLOR_SUBTEXT = 0xFF9A9A9A;
    private static final int COLOR_ROW = 0xFF232323;
    private static final int COLOR_BUTTON = 0xFF2F2F2F;
    private static final int COLOR_DANGER = 0xFFE05555;
    private static final int COLOR_PROBLEM = 0xFFFFB4A9;

    private final List<String> keys = new ArrayList<>();
    private float density;

    private TextView storageProblem;
    private LinearLayout controls;
    private Spinner keySpinner;
    private LinearLayout listContainer;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        density = getResources().getDisplayMetrics().density;

        for (int i = 1; i <= 9; i++) keys.add("F" + i);
        for (int i = 1; i <= 9; i++) keys.add(String.valueOf(i));

        setContentView(buildUi());
    }

    @Override
    protected void onResume() {
        super.onResume();
        boolean storageReady = KeymapNative.init();
        storageProblem.setVisibility(storageReady ? View.GONE : View.VISIBLE);
        controls.setVisibility(storageReady ? View.VISIBLE : View.GONE);
        if (storageReady) {
            showCurrentKey();
            refreshList();
        }
    }

    // ---------- building the screen ----------

    private View buildUi() {
        ScrollView scroll = new ScrollView(this);
        scroll.setBackgroundColor(COLOR_BG);

        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dp(16), dp(24), dp(16), dp(32));
        scroll.addView(content, new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        content.addView(text("Gaming Keyboard", 22, COLOR_TEXT, true), params(0));
        content.addView(text("Games with a saved key mapping. Reinstalling a game "
                + "restores its mapping automatically.", 13, COLOR_SUBTEXT, false), params(6));

        storageProblem = text("The key mapper storage could not start, so nothing can be shown "
                + "or saved right now. The native library may be missing, or this app may not "
                + "have permission to use the storage folder.", 14, COLOR_PROBLEM, false);
        storageProblem.setVisibility(View.GONE);
        content.addView(storageProblem, params(16));

        controls = new LinearLayout(this);
        controls.setOrientation(LinearLayout.VERTICAL);
        content.addView(controls, params(0));

        // Activation key.
        controls.addView(text("Activation key", 15, COLOR_TEXT, true), params(24));

        keySpinner = new Spinner(this);
        ArrayAdapter<String> adapter = new ArrayAdapter<>(
                this, android.R.layout.simple_spinner_item, keys);
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        keySpinner.setAdapter(adapter);
        keySpinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                String key = keys.get(position);
                if (key.equals(KeymapNative.getGlobalActivationKey())) return;  // nothing changed
                if (KeymapNative.setGlobalActivationKey(key)) {
                    refreshList();
                } else {
                    toast("Could not save the activation key");
                    showCurrentKey();
                }
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {
            }
        });
        controls.addView(keySpinner, params(4));

        controls.addView(text("Press this key while a game is open to show the mapping "
                + "overlay. F-keys are safest: a number key cannot be typed in a game "
                + "while it is the activation key.", 12, COLOR_SUBTEXT, false), params(4));

        // Turning the service on.
        controls.addView(button("Open accessibility settings", COLOR_BUTTON, v -> {
            try {
                startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS));
            } catch (RuntimeException e) {
                toast("Could not open accessibility settings");
            }
        }), params(20));
        controls.addView(text("Gaming Keyboard has to be switched on there to work.",
                12, COLOR_SUBTEXT, false), params(4));

        // The list of mapped games.
        controls.addView(text("Mapped games", 15, COLOR_TEXT, true), params(28));
        listContainer = new LinearLayout(this);
        listContainer.setOrientation(LinearLayout.VERTICAL);
        controls.addView(listContainer, params(8));

        return scroll;
    }

    private void showCurrentKey() {
        int index = keys.indexOf(KeymapNative.getGlobalActivationKey());
        keySpinner.setSelection(Math.max(0, index), false);
    }

    private void refreshList() {
        listContainer.removeAllViews();

        List<String[]> games = new ArrayList<>();  // {display name, package name}
        JSONArray array = KeymapNative.listGames();
        for (int i = 0; i < array.length(); i++) {
            JSONObject game = array.optJSONObject(i);
            if (game == null) continue;
            String packageName = game.optString("package_name", "");
            if (packageName.isEmpty()) continue;
            games.add(new String[] {game.optString("display_name", packageName), packageName});
        }
        Collections.sort(games, (a, b) ->
                a[0].toLowerCase(Locale.ROOT).compareTo(b[0].toLowerCase(Locale.ROOT)));

        if (games.isEmpty()) {
            listContainer.addView(text("No games mapped yet. Open a game and press "
                    + KeymapNative.getGlobalActivationKey() + " to map it.",
                    13, COLOR_SUBTEXT, false));
            return;
        }
        for (String[] game : games) {
            listContainer.addView(buildRow(game[0], game[1]), params(8));
        }
    }

    private View buildRow(String displayName, String packageName) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(14), dp(12), dp(10), dp(12));
        GradientDrawable background = new GradientDrawable();
        background.setColor(COLOR_ROW);
        background.setCornerRadius(dp(12));
        row.setBackground(background);

        LinearLayout names = new LinearLayout(this);
        names.setOrientation(LinearLayout.VERTICAL);
        names.addView(text(displayName, 16, COLOR_TEXT, false));
        names.addView(text(packageName, 11, COLOR_SUBTEXT, false));
        row.addView(names, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        Button delete = button("Delete", COLOR_DANGER, v -> confirmDelete(displayName, packageName));
        row.addView(delete, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        return row;
    }

    private void confirmDelete(String displayName, String packageName) {
        new AlertDialog.Builder(this)
                .setTitle("Remove mapping")
                .setMessage("Remove the saved key mapping for " + displayName + "? "
                        + "You can map the game again later.")
                .setPositiveButton("Remove", (dialog, which) -> {
                    if (!KeymapNative.deleteKeymap(packageName)) {
                        toast("Could not remove the mapping");
                    }
                    refreshList();
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    // ---------- small helpers ----------

    private void toast(String message) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show();
    }

    private int dp(float value) {
        return Math.round(value * density);
    }

    private LinearLayout.LayoutParams params(int topMarginDp) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        params.topMargin = dp(topMarginDp);
        return params;
    }

    private TextView text(String value, float sp, int color, boolean bold) {
        TextView view = new TextView(this);
        view.setText(value);
        view.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        view.setTextColor(color);
        if (bold) view.setTypeface(Typeface.DEFAULT_BOLD);
        return view;
    }

    private Button button(String label, int color, View.OnClickListener onClick) {
        Button button = new Button(this);
        button.setText(label);
        button.setAllCaps(false);
        button.setTextColor(COLOR_TEXT);
        GradientDrawable background = new GradientDrawable();
        background.setColor(color);
        background.setCornerRadius(dp(8));
        button.setBackground(background);
        button.setPadding(dp(14), dp(8), dp(14), dp(8));
        button.setOnClickListener(onClick);
        return button;
    }
}
