# Gaming Keyboard — Bliss OS Key Mapper

A system-level key mapping tool for Bliss OS / Android-x86, designed by Shad.
It lets a user place virtual key controls on screen for games that have no
native keyboard and mouse support, and it keeps those mappings outside the
game's app data, so they survive uninstalling and reinstalling the game.

**Status: first complete draft, never compiled and never run on a device.**
Expect small build errors on the first build. See "What still needs testing".

## What it is (and isn't)

**Is:** an overlay-based mapper. In a game you press the activation key, a
translucent "Gaming Keyboard" overlay appears, and you place keys on the
screen. While the game is open, pressing a mapped key taps that spot.

**Isn't:** a tool for games that already support keyboard and mouse natively
(for example Delta Force or Call of Duty Mobile). Those are out of scope and
would be a separate project.

## How it works for the user

1. Settings > Language & Input > Physical Keyboard > **Gaming Keyboard** lists
   every game with a saved mapping, lets you delete one, and lets you choose
   the global activation key (F1-F9 or 1-9, default F5).
2. In a game, press the activation key. The overlay opens with **Undo**,
   **Delete**, **Save** and a red **X** (close without saving).
3. Tap empty space, then press a key: that key is mapped to that spot.
   Double-tap empty space for a mouse-click mapping.
4. Tap an existing mapping to select it (it pulses), then press a new key to
   change it. Drag a mapping to move it. Drag the panel out of the way.
5. **Undo** removes the last mapping of the session. **Delete** discards the
   session without saving. **Save** writes the game's file, replacing any
   older one, and closes the overlay.
6. Uninstall the game: its file is untouched. Reinstall it: a notification
   says the keymap was detected, and the mapping works again.

## How it is built

| Part | Files | Job |
|---|---|---|
| Storage | `keymap_store`, `settings_provider` (C++) | One JSON file per game, atomic saves, global activation key |
| Detection | `game_detector` (C++) | Matches installed or foreground apps against saved keymaps |
| Bridge | `keymap_jni.cpp`, `detector_jni.cpp`, `KeymapNative`, `GameDetectorNative` | Lets Java call the C++ code |
| Launcher | `GamingKeyboardService` | Accessibility service: foreground tracking, activation key, install events |
| Overlay | `GamingKeyboardOverlay` | The translucent mapping window (positions are saved in screen pixels) |
| Engine | `GamingKeyboardEngine` | Turns a mapped key press into a tap, using accessibility gesture dispatch |
| Notifications | `GamingKeyboardNotifier` | "Keymap detected" and "Keymap active" |
| Settings screen | `GamingKeyboardSettingsActivity` | Game list, delete, activation key picker |

The file format is described in `docs/FILE_FORMAT.md`. The Python files in
`python_prototype/` are a desktop demo of the interaction model and are not
part of the Android build.

## Folder layout

```
docs/FILE_FORMAT.md
python_prototype/        keymap_store.py, overlay.py, settings_list.py
cpp_service/             CMakeLists.txt, keymap_store, game_detector,
                         settings_provider, keymap_jni.cpp, detector_jni.cpp,
                         third_party/nlohmann/json.hpp
android/                 AndroidManifest.xml, build.gradle
android/java/org/blissos/gamingkeyboard/   all .java files
android/res/xml/         gaming_keyboard_service.xml
android/res/values/      strings.xml
reference_xtmapper/      other project's files, for reference only, not built
```

## Building

1. Download `json.hpp` (nlohmann/json, version 3.11.x) from the project's
   GitHub releases and save it at `cpp_service/third_party/nlohmann/json.hpp`.
2. Open the `android` module in Android Studio (or the Bliss OS build system).
3. The Java package `org.blissos.gamingkeyboard` is a placeholder. If it is
   renamed, the function names in the two JNI `.cpp` files must change too.

## What still needs testing

- The first build. Nothing has been compiled yet.
- Whether the overlay receives physical keyboard presses on a real Bliss OS
  device, and whether Esc arrives as Back (Back closes the overlay).
- Whether tap injection works in real games.

## Known limits of this version

- Taps only. Holding a key does not hold a finger down, so there is no
  joystick, swipe or drag, and no mouse-look.
- Mouse-type mappings are saved but do nothing yet. Accessibility services
  cannot see mouse clicks.
- Saved positions are screen pixels, so a layout made in one orientation or
  resolution will not line up in another.
- The activation key opens the overlay only in apps Android marks as games,
  or in games that already have a keymap.

## For the Bliss OS integrator

- Run it as a platform-signed system app. Storage is under
  `/data/system/gaming_keyboard/` and needs the right permissions and
  SELinux labels. For a test on an ordinary device, change the two
  `DEFAULT_` paths at the top of `KeymapNative.java` to a folder the app owns.
- Enable the accessibility service by default so users never see a prompt.
- Move the settings screen into Settings > Language & Input > Physical
  Keyboard. The screen only uses `KeymapNative` calls, which can be reused.
- Mouse mappings, holds, swipes and joystick control need a lower-level path,
  such as a virtual touchscreen through uinput. That is the main next step.

## Credits and license

Idea, UX and behavior spec by Shad. Files in `reference_xtmapper/` come from
another open-source mapper and are kept only for reference; they follow that
project's own license and are not part of the build.
