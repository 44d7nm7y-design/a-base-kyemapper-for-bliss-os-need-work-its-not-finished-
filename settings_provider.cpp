// settings_provider.cpp

#include "settings_provider.h"

#include <algorithm>

namespace gaming_keyboard {

const std::vector<std::string>& AllowedActivationKeys() {
    static const std::vector<std::string> keys = {
        "F1", "F2", "F3", "F4", "F5", "F6", "F7", "F8", "F9",
        "1", "2", "3", "4", "5", "6", "7", "8", "9",
    };
    return keys;
}

SettingsProvider::SettingsProvider(std::shared_ptr<KeymapStore> store)
    : store_(std::move(store)) {}

std::vector<GameListing> SettingsProvider::GetMappedGamesList() const {
    return store_->ListMappedGames();
}

bool SettingsProvider::RemoveGame(const std::string& package_name) {
    return store_->DeleteFile(package_name);
}

std::string SettingsProvider::GetGlobalActivationKey() const {
    return global_activation_key_;
}

bool SettingsProvider::SetGlobalActivationKey(const std::string& key) {
    const auto& allowed = AllowedActivationKeys();
    if (std::find(allowed.begin(), allowed.end(), key) == allowed.end()) {
        return false;  // reject keys outside F1-F9 / 1-9
    }
    global_activation_key_ = key;
    return true;
}

}  // namespace gaming_keyboard
