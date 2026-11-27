// settings_provider.h
//
// Backing logic for Settings > Language & Input > Physical Keyboard >
// Gaming Keyboard. UI-framework-agnostic: plain data in, plain data out.
// The global activation key is persisted through SettingsStorage, so the
// integrator can swap the file-based version below for Android's real
// settings provider without touching this class.

#pragma once

#include <memory>
#include <string>
#include <vector>

#include "keymap_store.h"

namespace gaming_keyboard {

// Keys allowed for the global activation key: F1-F9 and 1-9.
const std::vector<std::string>& AllowedActivationKeys();

// Where small settings live between restarts.
class SettingsStorage {
public:
    virtual ~SettingsStorage() = default;
    virtual std::string Get(const std::string& key,
                            const std::string& default_value) const = 0;
    virtual bool Set(const std::string& key, const std::string& value) = 0;
};

// Simple key=value file. Works anywhere, including desktop tests.
// Suggested path: /data/system/gaming_keyboard/settings.conf
class FileSettingsStorage : public SettingsStorage {
public:
    explicit FileSettingsStorage(std::string file_path);
    std::string Get(const std::string& key,
                    const std::string& default_value) const override;
    bool Set(const std::string& key, const std::string& value) override;

private:
    std::string file_path_;
};

class SettingsProvider {
public:
    SettingsProvider(std::shared_ptr<KeymapStore> store,
                     std::shared_ptr<SettingsStorage> storage);

    // One row per mapped game, for the Gaming Keyboard list screen.
    std::vector<GameListing> GetMappedGamesList() const;

    // "Delete" on a list row: removes that game's keymap file.
    bool RemoveGame(const std::string& package_name);

    // Global activation key. Survives restarts. Defaults to F5.
    std::string GetGlobalActivationKey() const;
    bool SetGlobalActivationKey(const std::string& key);

    // The key that actually opens the overlay for this game: the game's
    // own key if it has one, otherwise the global key.
    std::string EffectiveActivationKey(const Keymap& keymap) const;

private:
    std::shared_ptr<KeymapStore> store_;
    std::shared_ptr<SettingsStorage> storage_;
};

}  // namespace gaming_keyboard
