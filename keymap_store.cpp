// keymap_store.cpp
//
// Implementation of KeymapStore. Uses nlohmann::json (header-only,
// https://github.com/nlohmann/json) for parsing — swap for whatever
// JSON library the Bliss OS build already depends on if there's a
// preferred one; the schema itself (docs/FILE_FORMAT.md) doesn't
// require this specific library.

#include "keymap_store.h"

#include <filesystem>
#include <fstream>
#include <chrono>
#include <random>
#include <sstream>
#include <iomanip>

#include <nlohmann/json.hpp>

namespace fs = std::filesystem;
using json = nlohmann::json;

namespace gaming_keyboard {

namespace {

std::string NowIso8601() {
    auto now = std::chrono::system_clock::now();
    std::time_t t = std::chrono::system_clock::to_time_t(now);
    std::tm tm_utc{};
    gmtime_r(&t, &tm_utc);
    std::ostringstream oss;
    oss << std::put_time(&tm_utc, "%Y-%m-%dT%H:%M:%SZ");
    return oss.str();
}

std::string GenerateShortId() {
    static const char kChars[] = "0123456789abcdef";
    static std::mt19937 rng{std::random_device{}()};
    std::uniform_int_distribution<int> dist(0, 15);
    std::string id(4, '0');
    for (auto& c : id) c = kChars[dist(rng)];
    return id;
}

std::string MappingTypeToString(MappingType t) {
    return t == MappingType::kKey ? "key" : "mouse";
}

MappingType MappingTypeFromString(const std::string& s) {
    return s == "mouse" ? MappingType::kMouse : MappingType::kKey;
}

json MappingToJson(const MappingEntry& m) {
    return json{
        {"id", m.id},
        {"type", MappingTypeToString(m.type)},
        {"input_key", m.input_key},
        {"action_label", m.action_label},
        {"position", {{"x", m.position.x}, {"y", m.position.y}}},
    };
}

MappingEntry MappingFromJson(const json& j) {
    MappingEntry m;
    m.id = j.at("id").get<std::string>();
    m.type = MappingTypeFromString(j.at("type").get<std::string>());
    m.input_key = j.at("input_key").get<std::string>();
    m.action_label = j.value("action_label", "");
    m.position.x = j.at("position").at("x").get<int>();
    m.position.y = j.at("position").at("y").get<int>();
    return m;
}

json KeymapToJson(const Keymap& k) {
    json mappings = json::array();
    for (const auto& m : k.mappings) mappings.push_back(MappingToJson(m));

    return json{
        {"schema_version", k.schema_version},
        {"package_name", k.package_name},
        {"display_name", k.display_name},
        {"created_at", k.created_at},
        {"updated_at", k.updated_at},
        {"activation_key", k.activation_key},
        {"mappings", mappings},
    };
}

Keymap KeymapFromJson(const json& j) {
    Keymap k;
    k.schema_version = j.value("schema_version", 1);
    k.package_name = j.at("package_name").get<std::string>();
    k.display_name = j.value("display_name", k.package_name);
    k.created_at = j.value("created_at", "");
    k.updated_at = j.value("updated_at", "");
    k.activation_key = j.value("activation_key", "F5");
    if (j.contains("mappings")) {
        for (const auto& item : j.at("mappings")) {
            k.mappings.push_back(MappingFromJson(item));
        }
    }
    return k;
}

}  // namespace

KeymapStore::KeymapStore(std::string store_dir) : store_dir_(std::move(store_dir)) {
    fs::create_directories(store_dir_);
}

std::string KeymapStore::PathFor(const std::string& package_name) const {
    return (fs::path(store_dir_) / (package_name + ".json")).string();
}

std::optional<Keymap> KeymapStore::Load(const std::string& package_name) const {
    auto path = PathFor(package_name);
    if (!fs::exists(path)) return std::nullopt;

    std::ifstream in(path);
    if (!in) return std::nullopt;

    json j;
    in >> j;
    return KeymapFromJson(j);
}

Keymap KeymapStore::CreateNew(const std::string& package_name,
                               const std::string& display_name,
                               const std::string& activation_key) const {
    Keymap k;
    k.package_name = package_name;
    k.display_name = display_name;
    k.activation_key = activation_key;
    k.created_at = NowIso8601();
    k.updated_at = k.created_at;
    return k;
}

std::string KeymapStore::AddMapping(Keymap& keymap,
                                     MappingType type,
                                     const std::string& input_key,
                                     Position position,
                                     const std::string& action_label) {
    MappingEntry entry;
    entry.id = GenerateShortId();
    entry.type = type;
    entry.input_key = input_key;
    entry.action_label = action_label;
    entry.position = position;
    keymap.mappings.push_back(entry);
    return entry.id;
}

bool KeymapStore::UndoLast(Keymap& keymap) {
    if (keymap.mappings.empty()) return false;
    keymap.mappings.pop_back();
    return true;
}

std::string KeymapStore::Save(Keymap& keymap) const {
    keymap.updated_at = NowIso8601();
    auto path = PathFor(keymap.package_name);
    std::ofstream out(path);
    out << KeymapToJson(keymap).dump(2);
    return path;
}

bool KeymapStore::DeleteFile(const std::string& package_name) const {
    auto path = PathFor(package_name);
    if (!fs::exists(path)) return false;
    return fs::remove(path);
}

std::vector<GameListing> KeymapStore::ListMappedGames() const {
    std::vector<GameListing> result;
    if (!fs::exists(store_dir_)) return result;

    for (const auto& entry : fs::directory_iterator(store_dir_)) {
        if (entry.path().extension() != ".json") continue;

        std::ifstream in(entry.path());
        if (!in) continue;

        json j;
        in >> j;
        GameListing g;
        g.package_name = j.value("package_name", "");
        g.display_name = j.value("display_name", g.package_name);
        result.push_back(g);
    }
    return result;
}

}  // namespace gaming_keyboard
