package org.blissos.gamingkeyboard;

import android.content.Context;
import android.graphics.PixelFormat;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.util.Log;
import android.util.TypedValue;
import android.view.GestureDetector;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/**
 * The Gaming Keyboard overlay: a translucent full-screen layer with a small
 * draggable "Gaming Keyboard" panel (Undo / Delete / Save / close).
 *
 *  - Tap empty space, then press a key: maps that key to that spot.
 *  - Double-tap empty space: adds a mouse-click mapping there.
 *  - Tap a mapping: selects it (it pulses), then press a new key to change it
 *    (or the right / middle mouse button for a mouse mapping).
 *  - Drag a mapping to move it. Drag the panel to get it out of the way.
 *
 * Positions are stored in screen pixels.
 */
public class GamingKeyboardOverlay {
    private static final String TAG = "GamingKeyboardOverlay";

    /** Called exactly once when the overlay closes. saved is true only after a successful Save. */
    public interface OnCloseListener {
        void onClosed(boolean saved);
    }

    private static final int COLOR_SCRIM = 0x66000000;
    private static final int COLOR_PANEL = 0xE61C1C1C;
    private static final int COLOR_TEXT = 0xFFF2F2F2;
    private static final int COLOR_SUBTEXT = 0xFF9A9A9A;
    private static final int COLOR_BUTTON = 0xFF2F2F2F;
    private static final int COLOR_SAVE = 0xFF3A7A3A;
    private static final int COLOR_CLOSE = 0xFFE05555;
    private static final int COLOR_CHIP = 0xFF2F2F2F;
    private static final int COLOR_CHIP_EDGE = 0xFF5EC8FF;
    private static final int COLOR_CHIP_SELECTED = 0xFFFFFFFF;

    private static final int CHIP_SIZE_DP = 48;
    private static final int MARKER_SIZE_DP = 40;

    private final Context context;
    private final WindowManager windowManager;
    private final String packageName;
    private final String displayName;
    private final OnCloseListener listener;
    private final JSONObject keymap;
    private final float density;
    private final int touchSlop;

    private final List<Chip> chips = new ArrayList<>();
    private final int[] offset = new int[2];  // screen position of the overlay's top-left corner

    private OverlayRoot root;
    private LinearLayout panel;
    private TextView status;
    private View pendingMarker;
    private Chip armedChip;
    private boolean hasPending;
    private int pendingX;
    private int pendingY;
    private boolean shown;
    private boolean closed;

    public GamingKeyboardOverlay(Context context, String packageName, String displayName,
                                 OnCloseListener listener) {
        this.context = context;
        this.packageName = packageName;
        this.displayName = displayName;
        this.listener = listener;
        this.windowManager = context.getSystemService(WindowManager.class);
        this.density = context.getResources().getDisplayMetrics().density;
        this.touchSlop = ViewConfiguration.get(context).getScaledTouchSlop();

        if (windowManager == null) {
            throw new IllegalStateException("No window manager available");
        }

        JSONObject loaded = KeymapNative.loadKeymap(packageName);
        if (loaded == null) loaded = KeymapNative.newKeymap(packageName, displayName);
        if (loaded == null) {
            throw new IllegalStateException("Keymap storage is not available");
        }
        this.keymap = loaded;
    }

    // ---------- showing and closing ----------

    public void show() {
        if (shown || closed) return;
        buildViews();

        WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                        | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT);
        lp.setTitle("Gaming Keyboard");
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            lp.layoutInDisplayCutoutMode =
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;
        }

        windowManager.addView(root, lp);
        shown = true;
        root.requestFocus();
    }

    /** Closes without saving. */
    public void hide() {
        close(false);
    }

    private void close(boolean saved) {
        if (closed) return;
        closed = true;
        if (shown && root != null) {
            try {
                windowManager.removeView(root);
            } catch (IllegalArgumentException e) {
                Log.w(TAG, "Overlay was already removed", e);
            }
        }
        shown = false;
        if (listener != null) listener.onClosed(saved);
    }

    // ---------- building the views ----------

    private void buildViews() {
        root = new OverlayRoot(context);

        panel = new LinearLayout(context);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setPadding(dp(12), dp(10), dp(12), dp(12));
        panel.setMinimumWidth(dp(260));
        GradientDrawable panelBackground = new GradientDrawable();
        panelBackground.setColor(COLOR_PANEL);
        panelBackground.setCornerRadius(dp(14));
        panel.setBackground(panelBackground);

        // Header: title and game name on the left, red close button on the right.
        LinearLayout header = new LinearLayout(context);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);

        LinearLayout titles = new LinearLayout(context);
        titles.setOrientation(LinearLayout.VERTICAL);
        titles.addView(text("Gaming Keyboard", 15, COLOR_TEXT, true));
        titles.addView(text(displayName, 11, COLOR_SUBTEXT, false));
        header.addView(titles, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        TextView closeButton = text("\u2715", 18, COLOR_CLOSE, true);
        closeButton.setPadding(dp(14), dp(4), dp(2), dp(4));
        closeButton.setContentDescription("Close without saving");
        closeButton.setOnClickListener(v -> close(false));
        header.addView(closeButton, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        panel.addView(header, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        // Buttons: Undo, Delete, Save.
        LinearLayout buttons = new LinearLayout(context);
        buttons.setOrientation(LinearLayout.HORIZONTAL);
        buttons.addView(button("Undo", COLOR_BUTTON, v -> undo()), buttonParams());
        buttons.addView(button("Delete", COLOR_BUTTON, v -> close(false)), buttonParams());
        buttons.addView(button("Save", COLOR_SAVE, v -> save()), buttonParams());

        LinearLayout.LayoutParams buttonsRow = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        buttonsRow.topMargin = dp(10);
        panel.addView(buttons, buttonsRow);

        status = text("", 11, COLOR_SUBTEXT, false);
        status.setMaxWidth(dp(280));
        LinearLayout.LayoutParams statusParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        statusParams.topMargin = dp(8);
        panel.addView(status, statusParams);

        enablePanelDragging();

        root.addView(panel, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        // Once the layer has been laid out we know where it sits on screen,
        // so existing mappings can be placed at their saved pixel positions.
        root.getViewTreeObserver().addOnGlobalLayoutListener(
                new ViewTreeObserver.OnGlobalLayoutListener() {
                    @Override
                    public void onGlobalLayout() {
                        if (root == null || root.getWidth() == 0 || panel.getWidth() == 0) return;
                        root.getViewTreeObserver().removeOnGlobalLayoutListener(this);
                        onFirstLayout();
                    }
                });
    }

    private void onFirstLayout() {
        root.getLocationOnScreen(offset);

        panel.setX((root.getWidth() - panel.getWidth()) / 2f);
        panel.setY(dp(24));

        JSONArray mappings = keymap.optJSONArray("mappings");
        if (mappings != null) {
            for (int i = 0; i < mappings.length(); i++) {
                JSONObject entry = mappings.optJSONObject(i);
                if (entry != null) addChip(entry);
            }
        }
        panel.bringToFront();
        updateStatus();
    }

    private void enablePanelDragging() {
        panel.setOnTouchListener(new View.OnTouchListener() {
            private float downRawX;
            private float downRawY;
            private float startX;
            private float startY;

            @Override
            public boolean onTouch(View v, MotionEvent e) {
                switch (e.getActionMasked()) {
                    case MotionEvent.ACTION_DOWN:
                        downRawX = e.getRawX();
                        downRawY = e.getRawY();
                        startX = panel.getX();
                        startY = panel.getY();
                        return true;
                    case MotionEvent.ACTION_MOVE: {
                        float x = startX + e.getRawX() - downRawX;
                        float y = startY + e.getRawY() - downRawY;
                        panel.setX(clamp(x, 0, Math.max(0, root.getWidth() - panel.getWidth())));
                        panel.setY(clamp(y, 0, Math.max(0, root.getHeight() - panel.getHeight())));
                        return true;
                    }
                    default:
                        return true;
                }
            }
        });
    }

    // ---------- actions ----------

    private void undo() {
        if (chips.isEmpty()) return;
        Chip last = chips.remove(chips.size() - 1);
        if (armedChip == last) armedChip = null;
        last.remove();
        updateStatus();
    }

    private void save() {
        if (chips.isEmpty()) {
            toast("Nothing to save yet");
            return;
        }
        JSONArray mappings = new JSONArray();
        for (Chip chip : chips) mappings.put(chip.entry);
        try {
            keymap.put("mappings", mappings);
        } catch (JSONException e) {
            Log.e(TAG, "Could not build the keymap", e);
            toast("Could not save the keymap");
            return;
        }
        if (!KeymapNative.saveKeymap(keymap)) {
            Log.e(TAG, "Saving failed for " + packageName);
            toast("Could not save the keymap");
            return;
        }
        close(true);
    }

    // ---------- placing and selecting mappings ----------

    private void beginPending(int screenX, int screenY) {
        disarm();
        clearPending();

        int size = dp(MARKER_SIZE_DP);
        pendingMarker = new View(context);
        GradientDrawable ring = new GradientDrawable();
        ring.setShape(GradientDrawable.OVAL);
        ring.setColor(0x00000000);
        ring.setStroke(dp(2), COLOR_CHIP_EDGE, dp(4), dp(3));
        pendingMarker.setBackground(ring);
        root.addView(pendingMarker, new FrameLayout.LayoutParams(size, size));
        pendingMarker.setX(screenX - offset[0] - size / 2f);
        pendingMarker.setY(screenY - offset[1] - size / 2f);

        hasPending = true;
        pendingX = screenX;
        pendingY = screenY;
        panel.bringToFront();
        updateStatus();
    }

    private void clearPending() {
        if (pendingMarker != null) {
            root.removeView(pendingMarker);
            pendingMarker = null;
        }
        hasPending = false;
    }

    private void disarm() {
        if (armedChip != null) {
            armedChip.highlight(false);
            armedChip = null;
        }
    }

    private void addMouseChip(int screenX, int screenY) {
        disarm();
        clearPending();
        addChip(KeymapNative.newMapping("mouse", "MOUSE_LEFT", screenX, screenY));
        panel.bringToFront();
        updateStatus();
    }

    private Chip addChip(JSONObject entry) {
        JSONObject position = entry.optJSONObject("position");
        int x = position == null ? 0 : position.optInt("x");
        int y = position == null ? 0 : position.optInt("y");
        Chip chip = new Chip(entry, x, y);
        chips.add(chip);
        return chip;
    }

    private void selectChip(Chip chip) {
        clearPending();
        if (armedChip != null && armedChip != chip) armedChip.highlight(false);
        armedChip = chip;
        chip.highlight(true);
        chip.pulse();
        updateStatus();
    }

    private void assignToArmed(String type, String inputKey) {
        if (armedChip == null) return;
        armedChip.assign(type, inputKey);
        armedChip.highlight(false);
        armedChip = null;
        updateStatus();
    }

    // ---------- keyboard ----------

    private void handleKey(int keyCode) {
        if (keyCode == KeyEvent.KEYCODE_BACK) {
            close(false);
            return;
        }
        String name = keyName(keyCode);
        if (name == null) return;

        if (armedChip != null) {
            assignToArmed("key", name);
        } else if (hasPending) {
            int x = pendingX;
            int y = pendingY;
            clearPending();
            addChip(KeymapNative.newMapping("key", name, x, y));
            panel.bringToFront();
            updateStatus();
        }
    }

    private static String keyName(int keyCode) {
        if (keyCode == KeyEvent.KEYCODE_UNKNOWN) return null;
        String name = KeyEvent.keyCodeToString(keyCode);
        String prefix = "KEYCODE_";
        return name.startsWith(prefix) ? name.substring(prefix.length()) : name;
    }

    // ---------- small helpers ----------

    private void updateStatus() {
        if (status == null) return;
        if (armedChip != null) {
            status.setText("Press the new key (or right / middle mouse button)\u2026");
        } else if (hasPending) {
            status.setText("Press a key to map it here\u2026");
        } else {
            status.setText("Tap a spot to add a mapping, double-tap for a mouse click, "
                    + "or tap a mapping to change it.");
        }
    }

    private void toast(String message) {
        Toast.makeText(context, message, Toast.LENGTH_SHORT).show();
    }

    private int dp(float value) {
        return Math.round(value * density);
    }

    private static float clamp(float value, float min, float max) {
        return Math.max(min, Math.min(max, value));
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    private TextView text(String value, float sp, int color, boolean bold) {
        TextView view = new TextView(context);
        view.setText(value);
        view.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        view.setTextColor(color);
        if (bold) view.setTypeface(Typeface.DEFAULT_BOLD);
        return view;
    }

    private Button button(String label, int color, View.OnClickListener onClick) {
        Button button = new Button(context);
        button.setText(label);
        button.setAllCaps(false);
        button.setTextColor(COLOR_TEXT);
        button.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        GradientDrawable background = new GradientDrawable();
        background.setColor(color);
        background.setCornerRadius(dp(8));
        button.setBackground(background);
        button.setMinHeight(0);
        button.setMinimumHeight(0);
        button.setMinWidth(0);
        button.setMinimumWidth(0);
        button.setPadding(dp(12), dp(8), dp(12), dp(8));
        button.setFocusable(false);  // keys belong to the mapping, not to the buttons
        button.setOnClickListener(onClick);
        return button;
    }

    private LinearLayout.LayoutParams buttonParams() {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        params.setMargins(dp(3), 0, dp(3), 0);
        return params;
    }

    private static void putPosition(JSONObject entry, int x, int y) {
        try {
            JSONObject position = entry.optJSONObject("position");
            if (position == null) {
                position = new JSONObject();
                entry.put("position", position);
            }
            position.put("x", x);
            position.put("y", y);
        } catch (JSONException e) {
            Log.e(TAG, "Could not update a position", e);
        }
    }

    private static String displayLabel(String inputKey) {
        switch (inputKey) {
            case "MOUSE_LEFT":
                return "LMB";
            case "MOUSE_RIGHT":
                return "RMB";
            case "MOUSE_MIDDLE":
                return "MMB";
            default:
                return inputKey.length() > 5 ? inputKey.replace('_', '\n') : inputKey;
        }
    }

    // ---------- one mapped key / mouse action, drawn as a circle ----------

    private final class Chip {
        final JSONObject entry;
        final TextView view;
        private final GradientDrawable shape = new GradientDrawable();
        int cx;  // centre, in screen pixels
        int cy;

        Chip(JSONObject entry, int cx, int cy) {
            this.entry = entry;
            this.cx = cx;
            this.cy = cy;

            final Chip self = this;
            int size = dp(CHIP_SIZE_DP);

            view = new TextView(context);
            view.setGravity(Gravity.CENTER);
            view.setTextColor(COLOR_TEXT);
            view.setTypeface(Typeface.DEFAULT_BOLD);
            shape.setShape(GradientDrawable.OVAL);
            shape.setColor(COLOR_CHIP);
            shape.setStroke(dp(2), COLOR_CHIP_EDGE);
            view.setBackground(shape);

            root.addView(view, new FrameLayout.LayoutParams(size, size));
            refreshLabel();
            place();

            view.setOnTouchListener(new View.OnTouchListener() {
                private float downRawX;
                private float downRawY;
                private int startCx;
                private int startCy;
                private boolean dragging;

                @Override
                public boolean onTouch(View v, MotionEvent e) {
                    switch (e.getActionMasked()) {
                        case MotionEvent.ACTION_DOWN:
                            downRawX = e.getRawX();
                            downRawY = e.getRawY();
                            startCx = self.cx;
                            startCy = self.cy;
                            dragging = false;
                            return true;
                        case MotionEvent.ACTION_MOVE: {
                            float dx = e.getRawX() - downRawX;
                            float dy = e.getRawY() - downRawY;
                            if (!dragging && Math.hypot(dx, dy) > touchSlop) dragging = true;
                            if (dragging) {
                                self.moveTo(startCx + Math.round(dx), startCy + Math.round(dy));
                            }
                            return true;
                        }
                        case MotionEvent.ACTION_UP:
                            if (!dragging) selectChip(self);
                            return true;
                        default:
                            return true;
                    }
                }
            });
        }

        void place() {
            float half = dp(CHIP_SIZE_DP) / 2f;
            view.setX(cx - offset[0] - half);
            view.setY(cy - offset[1] - half);
        }

        void moveTo(int x, int y) {
            cx = clamp(x, offset[0], offset[0] + root.getWidth());
            cy = clamp(y, offset[1], offset[1] + root.getHeight());
            place();
            putPosition(entry, cx, cy);
        }

        void assign(String type, String inputKey) {
            try {
                entry.put("type", type);
                entry.put("input_key", inputKey);
            } catch (JSONException e) {
                Log.e(TAG, "Could not change a mapping", e);
            }
            refreshLabel();
        }

        void refreshLabel() {
            String label = displayLabel(entry.optString("input_key", "?"));
            view.setText(label);
            float sp;
            if (label.contains("\n")) sp = 9;
            else if (label.length() <= 2) sp = 16;
            else if (label.length() == 3) sp = 13;
            else sp = 11;
            view.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        }

        void highlight(boolean on) {
            shape.setStroke(dp(on ? 4 : 2), on ? COLOR_CHIP_SELECTED : COLOR_CHIP_EDGE);
        }

        void pulse() {
            view.animate().scaleX(1.3f).scaleY(1.3f).setDuration(120)
                    .withEndAction(() -> view.animate().scaleX(1f).scaleY(1f).setDuration(120))
                    .start();
        }

        void remove() {
            root.removeView(view);
        }
    }

    // ---------- the full-screen layer ----------

    private final class OverlayRoot extends FrameLayout {
        private final GestureDetector gestures;
        private boolean swallowGesture = false;

        OverlayRoot(Context c) {
            super(c);
            setBackgroundColor(COLOR_SCRIM);
            setFocusable(true);
            setFocusableInTouchMode(true);

            gestures = new GestureDetector(c, new GestureDetector.SimpleOnGestureListener() {
                @Override
                public boolean onDown(MotionEvent e) {
                    return true;
                }

                @Override
                public boolean onSingleTapConfirmed(MotionEvent e) {
                    beginPending(Math.round(e.getRawX()), Math.round(e.getRawY()));
                    return true;
                }

                @Override
                public boolean onDoubleTap(MotionEvent e) {
                    addMouseChip(Math.round(e.getRawX()), Math.round(e.getRawY()));
                    return true;
                }
            });
        }

        // Every key goes to the overlay while it is open.
        @Override
        public boolean dispatchKeyEvent(KeyEvent event) {
            if (event.getAction() == KeyEvent.ACTION_DOWN && event.getRepeatCount() == 0) {
                handleKey(event.getKeyCode());
            }
            return true;
        }

        // A right or middle mouse click reassigns the selected mapping to that button.
        @Override
        public boolean dispatchTouchEvent(MotionEvent e) {
            int action = e.getActionMasked();
            if (swallowGesture) {
                if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
                    swallowGesture = false;
                }
                return true;
            }
            if (armedChip != null && action == MotionEvent.ACTION_DOWN
                    && e.getToolType(0) == MotionEvent.TOOL_TYPE_MOUSE) {
                int buttons = e.getButtonState();
                String name = null;
                if ((buttons & MotionEvent.BUTTON_SECONDARY) != 0) name = "MOUSE_RIGHT";
                else if ((buttons & MotionEvent.BUTTON_TERTIARY) != 0) name = "MOUSE_MIDDLE";
                if (name != null) {
                    swallowGesture = true;
                    assignToArmed("mouse", name);
                    return true;
                }
            }
            return super.dispatchTouchEvent(e);
        }

        @Override
        public boolean onTouchEvent(MotionEvent e) {
            gestures.onTouchEvent(e);
            return true;
        }
    }
}
