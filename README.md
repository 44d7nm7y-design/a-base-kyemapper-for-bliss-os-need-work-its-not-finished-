# Gaming Keyboard — Bliss OS Key Mapper

A system-level key mapping tool proposed for Bliss OS, designed by Shad.
Idea, UX, and behavior spec by Shad; this repo contains a tested Python
prototype of the overlay/UX plus a C++ skeleton for the real system
service, meant as a starting point for native integration.

## What this is (and isn't)

**Is:** an overlay-based tool that lets a user manually place virtual
key/mouse controls on screen for games that don't have native keyboard
and mouse support, with mappings persisted at the system level so they
survive uninstalling and reinstalling the game.

**Isn't:** a tool that automatically detects or handles games that
*already* have native keyboard/mouse support built in (e.g. Delta
Force, Call of Duty Mobile). Those are explicitly out of scope — if
someone wants to build that on top of this, it's a separate project.

## How it should feel to a user

1. Settings → Language & Input → Physical Keyboard → **Gaming
   Keyboard**. This shows every game with a saved mapping (styled like
   a "devices you're signed into" list) and lets you set the global
   activation key (F1–F9 or 1–9).
2. In a game, press the activation key. A translucent, recorder-style
   overlay titled **"Gaming Keyboard"** appears with three buttons —
   **Undo**, **Delete**, **Save** — and a red **✕** to close without
   doing anything.
3. Click empty space + press a key → places a key mapping there. Drag
   the overlay to reposition it. Double-click the overlay's center →
   arms mouse-mapping mode.
4. Click an already-placed mapping to re-arm it (it flashes/highlights
   to confirm selection), then press a new key to reassign it.
5. **Undo** removes the last mapping made this session. **Delete**
   discards the whole session without saving. **Save** writes the
   mapping to a file named for the game, replacing any older mapping
   for that game, and closes the overlay.
6. Uninstall the game — the mapping file is untouched (it lives outside
   the app's sandbox). Reinstall it — the tool detects the package
   automatically and shows a notification that the keymap is active
   again.

## Repo layout

```
docs/
  FILE_FORMAT.md        <- the JSON schema BOTH implementations share
python_prototype/
  keymap_store.py        <- read/write logic (tested, see below)
  overlay.py              <- runnable Tkinter overlay prototype
  settings_list.py        <- runnable Settings > Gaming Keyboard list prototype
cpp_service/
  keymap_store.h/.cpp     <- native storage manager (mirrors keymap_store.py)
  game_detector.h/.cpp    <- install/foreground detection + notifications
  settings_provider.h/.cpp<- backing data for the Settings list screen
```

## Why both Python and C++

The Python prototype is a **testable reference implementation** of the
interaction model — drag, click-to-place, double-click for mouse,
undo/save/delete/close — so the exact behavior can be demoed and
refined without touching native code. It's already been run and its
storage logic unit-tested (see below).

The C++ skeleton is the **starting point for the real system
integration** — it can't be fully tested standalone here since it
needs Bliss OS/AOSP hooks (PackageManager, ActivityManager or an
accessibility service, and the actual overlay window APIs), but the
storage and detection *logic* is fully written out and structured so
whoever integrates it isn't starting from a blank file.

## What's already working vs. what's left to wire up

**Done and tested:**
- Keymap file format (`docs/FILE_FORMAT.md`) — JSON, keyed by package
  name, versioned schema.
- Python `keymap_store.py` — create/load/save/undo/delete/list. Ran a
  full round-trip test successfully (create → save → load → undo →
  list → delete).
- Python `overlay.py` — full interaction model: window drag, click-to-
  place + key capture, double-click for mouse mapping, click-to-
  reassign with visual confirmation, Undo/Delete/Save/Close buttons.
  Syntax-verified, ready to run with `python3 overlay.py <package> "<name>"`.
- Python `settings_list.py` — the Gaming Keyboard list screen with
  per-row delete and an activation-key dropdown (F1–F9, 1–9).
- C++ `KeymapStore` — mirrors the Python storage logic exactly, same
  file format. Uses `nlohmann::json` (header-only; not vendored in
  this repo — add it via your existing dependency manager, or drop in
  `json.hpp` directly).
- C++ `GameDetector` — matching/notification logic for install and
  foreground events, built against an abstract `NotifierInterface` so
  it isn't tied to a specific notification API yet.
- C++ `SettingsProvider` — backing data (list + activation-key
  get/set) for the Settings screen, framework-agnostic.

**Left for the Bliss OS/AOSP integrator:**
- Wiring `GameDetector::OnPackageInstalled` /
  `OnPackageForegrounded` / `OnPackageBackgrounded` to real system
  events (PackageManager broadcast receivers; ActivityManager,
  UsageStatsManager, or an accessibility service for foreground
  detection — ideally whichever pattern the existing native features,
  like Wi-Fi, already use, for consistency).
- Implementing `NotifierInterface` against Bliss OS's actual
  notification system.
- Building the real translucent system overlay window (Android
  `TYPE_ACCESSIBILITY_OVERLAY` or similar) — the Python `overlay.py`
  is the interaction reference, not something that runs natively.
- Binding `SettingsProvider` into an actual Settings UI screen
  (PreferenceFragment or whatever Bliss OS's Settings app uses).
- Choosing the real system storage path (spec assumes something like
  `/data/system/gaming_keyboard/keymaps/`) and permissions so it's
  writable by the service but outside any single app's sandbox.
- Global hardware key capture for the activation key (F1–F9 / 1–9),
  independent of whatever app is in the foreground.

> **Note on privilege level:** capturing a global hardware key while
> another app is in the foreground isn't something a normal
> third-party Android app can do — it requires either an
> AccessibilityService or a privileged/system-signed component. Since
> this is meant to ship as a native Bliss OS feature (not a sideloaded
> app), that's the right tier to build it at, but it's worth flagging
> explicitly for whoever picks this up so it's scoped as system-level
> work from the start, not bolted on later.

## Try the prototype yourself

```bash
cd python_prototype

# Open the settings list (empty at first)
python3 settings_list.py

# Map a game — opens the overlay
python3 overlay.py com.kiloo.subwaysurf "Subway Surfers"
# click empty space, press a key, click Save

# Reopen settings list — "Subway Surfers" now appears
python3 settings_list.py
```
