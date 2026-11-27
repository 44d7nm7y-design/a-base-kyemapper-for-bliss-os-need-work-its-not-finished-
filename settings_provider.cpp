// settings_provider.cpp

#include "settings_provider.h"

#include <algorithm>
#include <filesystem>
#include <fstream>
#include <map>
#include <system_error>
#include <utility>

namespace fs = std::filesystem;

namespace gaming_keyboard {

namespace {

const char kActivationKeyName[] = "activation_key";
const char kDefaultActivationKey[] = "F5";

std::map<std::string, std::string> ReadAll(const std::string& path) {
    std::map<std::string, std::string> out;
    std::ifstream in(path);
    std::string line;
    while (std::getline(in, line)) {
        auto eq = line.find('=');
        if (eq == std::string::npos || eq == 0) continue;
        out[line.substr(0, eq)] = line.substr(eq + 1);
    }
    return out;
}

bool IsAllowedKey(const std::string& key) {
    const auto& allowed = AllowedActivationKeys();
    return std::find(allowed.begin(), allowed.end(), key) != allowed.end();
}

}  // namespace

const std::vector<std::string>& AllowedActivationKeys() {
    static const std::vector<std::string> keys = {
        "F1", "F2", "F3", "F4", "F5", "F6", "F7", "F8", "F9",
        "1", "2", "3", "4", "5", "6", "7", "8", "9",
    };
    return keys;
}

FileSettingsStorage::FileSettingsStorage(std::string file_path)
    : file_path_(std::move(file_path)) {}

std::string FileSettingsStorage::Get(const std::string& key,
                                     const std::string& default_value) const {
    auto all = ReadAll(file_path_);
    auto it = all.find(key);
    return it == all.end() ? default_value : it->second;
}

bool FileSettingsStorage::Set(const std::string& key, const std::string& value) {
    auto all = ReadAll(file_path_);
    all[key] = value;

    auto tmp = file_path_ + ".tmp";
    {
        std::ofstream out(tmp);
        for (const auto& [k, v] : all) out << k << '=' << v << '\n';
        out.flush();
        if (!out) return false;
    }
    std::error_code ec;
    fs::rename(tmp, file_path_, ec);  // replaces the old file in one step
    return !ec;
}

SettingsProvider::SettingsProvider(std::shared_ptr<KeymapStore> store,
                                   std::shared_ptr<SettingsStorage> storage)
    : store_(std::move(store)), storage_(std::move(storage)) {}

std::vector<GameListing> SettingsProvider::GetMappedGamesList() const {
    return store_->ListMappedGames();
}

bool SettingsProvider::RemoveGame(const std::string& package_name) {
    return store_->DeleteFile(package_name);
}

std::string SettingsProvider::GetGlobalActivationKey() const {
    std::string key = storage_->Get(kActivationKeyName, kDefaultActivationKey);
    return IsAllowedKey(key) ? key : kDefaultActivationKey;  // ignore a damaged value
}

bool SettingsProvider::SetGlobalActivationKey(const std::string& key) {
    if (!IsAllowedKey(key)) return false;  // reject anything outside F1-F9 / 1-9
    return storage_->Set(kActivationKeyName, key);
}

std::string SettingsProvider::EffectiveActivationKey(const Keymap& keymap) const {
    if (!keymap.activation_key.empty() && IsAllowedKey(keymap.activation_key)) {
        return keymap.activation_key;
    }
    return GetGlobalActivationKey();
}

}  // namespace gaming_keyboard
