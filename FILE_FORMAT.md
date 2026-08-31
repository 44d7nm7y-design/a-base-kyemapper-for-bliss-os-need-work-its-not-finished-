# Gaming Keyboard — Keymap File Format Spec

## Purpose

Each game that has been key-mapped gets exactly one persistent file on the
system. This file must:

- Survive uninstall/reinstall of the game (it lives outside the game's app
  data, in the Gaming Keyboard tool's own storage area).
- Be identified by the game's **package name**, not its display name, so
  reinstalling the same app is recognized reliably even if the display
  name changes across versions.
- Be simple enough to parse from both Python (prototype) and C++
  (system service), and human-readable enough to debug by hand.

Format chosen: **JSON**. Every implementation (prototype and native) reads
and writes this same schema so they never drift apart.

## Storage location (system-level, conceptual)

```
/data/system/gaming_keyboard/keymaps/<package_name>.json
```

(Exact path is up to the Bliss OS integrator — this is system-level,
outside any single app's sandbox, so it is not wiped on uninstall.)

## Schema

```json
{
  "schema_version": 1,
  "package_name": "com.kiloo.subwaysurf",
  "display_name": "Subway Surfers",
  "created_at": "2026-08-29T12:00:00Z",
  "updated_at": "2026-08-29T12:00:00Z",
  "activation_key": "F5",
  "mappings": [
    {
      "id": "0001",
      "type": "key",
      "input_key": "W",
      "action_label": "Move Forward",
      "position": { "x": 120, "y": 640 }
    },
    {
      "id": "0002",
      "type": "mouse",
      "input_key": "MOUSE_LEFT",
      "action_label": "Jump",
      "position": { "x": 900, "y": 500 }
    }
  ]
}
```

### Field notes

- `package_name` — the Android package identifier (e.g.
  `com.kiloo.subwaysurf`). This is the persistent key. Reinstall detection
  works by matching this field against newly-installed/launched packages,
  never the display name.
- `display_name` — human-readable name shown in the Settings > Gaming
  Keyboard list (e.g. "Subway Surfers"). Cosmetic only.
- `activation_key` — which key launches the overlay for this specific
  game. Falls back to a global default (configurable in Settings) if not
  set per-game.
- `mappings` — ordered list of individual key/mouse placements.
  - `id` — 4-character unique id within this file (used for undo: the
    last id appended is the one undo removes).
  - `type` — `"key"` or `"mouse"`.
  - `input_key` — the physical key or mouse action being mapped (e.g.
    `"W"`, `"SPACE"`, `"MOUSE_LEFT"`).
  - `action_label` — optional human label for what this does in-game.
  - `position` — where the overlay control sits on screen (x/y in
    device pixels), i.e. where the user dragged it.

### Save/replace behavior

- Saving always **overwrites** the existing file for that `package_name`
  in full (not a merge). This matches the spec: "if you map it again,
  the older one is replaced."
- `updated_at` is refreshed on every save; `created_at` is set once.

### Undo behavior

- Undo removes the **last entry appended** to the in-memory `mappings`
  list during the current overlay session (LIFO). It does not touch the
  on-disk file until Save is pressed.

### Delete behavior (in-overlay "Delete" button)

- Discards the entire in-memory session (all mappings made since the
  overlay was opened this time) **without** writing to disk, then closes
  the overlay. The previously-saved file (if any) is untouched.
- This is distinct from deleting a game's entry from the Settings list
  (see below), which does remove the on-disk file.

### Deleting from the Settings > Gaming Keyboard list

- Removes the file at
  `/data/system/gaming_keyboard/keymaps/<package_name>.json` entirely.
  The game no longer appears in the list, and no auto-detection happens
  for it until it's mapped again.

## Reinstall / re-detection flow

1. Game is uninstalled. Its file in `gaming_keyboard/keymaps/` is
   **not** touched (it lives at system level, outside the app sandbox).
2. Game is reinstalled (or any app is installed/launched).
3. The system service checks the new package name against existing
   keymap files.
4. On match: show a notification ("Gaming Keyboard: keymap detected for
   <display_name>") and mark the mapping active for that package, ready
   to trigger via the activation key.
