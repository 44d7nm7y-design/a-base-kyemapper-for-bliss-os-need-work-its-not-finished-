// settings_provider.h
//
// Backing logic for Settings > Language & Input > Physical Keyboard >
// Gaming Keyboard. This is intentionally UI-framework-agnostic: it
// exposes plain data (a list of games, a get/set for the global
// activation key) that the integrator binds to whatever Bliss OS uses
// for its Settings screens (likely a PreferenceFragment / Settings
// list item, matching how the existing Wi-Fi section is built).

#pragma once

#include <string>
#include <vector>
#include <memory>

#include "keymap_store.h"

namespace gaming_keyboard {

// Keys allowed for the global activation key preference, per spec:
// F1-F9 and 1-9.
const std::vector<std::string>& AllowedActivationKeys();

class SettingsProvider {
public:
    explicit SettingsProvider(std::shared_ptr<KeymapStore> store);

    // For the Gaming Keyboard list screen: one row per mapped game.
    std::vector<GameListing> GetMappedGamesList() const;

    // "Delete" action on a list row — removes that game's keymap file
    // entirely. Returns true if something was actually deleted.
    bool RemoveGame(const std::string& package_name);

    // Global activation key (default overlay-launch key when a game
    // doesn't specify its own). Persisted separately from per-game
    // keymap files, e.g. in Bliss OS's standard SharedPreferences /
    // system settings provider — storage mechanism left to integrator.
    std::string GetGlobalActivationKey() const;
    bool SetGlobalActivationKey(const std::string& key);

private:
    std::shared_ptr<KeymapStore> store_;
    std::string global_activation_key_ = "F5";
};

}  // namespace gaming_keyboard
