// game_detector.h
//
// Watches for package install/launch events at the system level and
// checks them against KeymapStore. When a package with an existing
// keymap is detected, it:
//   1. Fires a notification ("Gaming Keyboard: keymap detected for X")
//   2. Marks that package's keymap "active", so the activation key
//      (e.g. F5) triggers the overlay while that game is in the
//      foreground.
//
// INTEGRATOR NOTE: the actual hooks for "package installed" and
// "package launched / foreground app changed" are Bliss OS /
// AOSP-specific (PackageManager broadcast receivers, ActivityManager /
// UsageStatsManager, or an accessibility service watching window
// changes — whichever pattern Bliss OS's own native features, like the
// Wi-Fi module, already use for consistency). Those hooks are NOT
// implemented here — this class defines the interface the integrator
// wires real system events into, plus the matching/notification logic
// that's shared regardless of which hook mechanism is used.

#pragma once

#include <string>
#include <functional>
#include <memory>

#include "keymap_store.h"

namespace gaming_keyboard {

// Abstract notifier so this class isn't tied to a specific Android
// notification API — the integrator provides a concrete implementation
// that calls into Bliss OS's actual NotificationManager.
class NotifierInterface {
public:
    virtual ~NotifierInterface() = default;
    virtual void ShowKeymapDetected(const std::string& display_name) = 0;
    virtual void ShowOverlayActive(const std::string& display_name) = 0;
};

class GameDetector {
public:
    GameDetector(std::shared_ptr<KeymapStore> store,
                 std::shared_ptr<NotifierInterface> notifier);

    // Call this whenever the system observes a package being installed
    // (or reinstalled). If a keymap file already exists for this
    // package_name, the mapping is considered "recovered" automatically
    // — nothing further needs to happen on disk, since the file was
    // never deleted (it lives outside the app sandbox). This just
    // confirms the match and notifies the user.
    void OnPackageInstalled(const std::string& package_name);

    // Call this whenever the system observes a package coming to the
    // foreground (app launched / resumed). If that package has a saved
    // keymap, this arms the activation key for the current foreground
    // session and shows the "active" notification once per session.
    void OnPackageForegrounded(const std::string& package_name);

    // Call this when the foreground app changes away from a mapped
    // game, so the activation key stops being armed for it.
    void OnPackageBackgrounded(const std::string& package_name);

    // Returns the package_name currently armed for overlay activation,
    // or empty string if none. The activation-key handler (wherever
    // Bliss OS captures global hardware key events) should check this
    // before deciding to launch the overlay.
    std::string CurrentArmedPackage() const { return armed_package_; }

private:
    std::shared_ptr<KeymapStore> store_;
    std::shared_ptr<NotifierInterface> notifier_;
    std::string armed_package_;
    bool notified_this_session_ = false;
};

}  // namespace gaming_keyboard
