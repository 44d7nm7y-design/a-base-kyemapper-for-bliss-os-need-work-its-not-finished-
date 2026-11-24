// keymap_jni.cpp
//
// Bridge between Android (Java) and the native KeymapStore / SettingsProvider.
// Keymaps cross the boundary as UTF-8 JSON bytes, so Java never has to know
// about C++ structs, and game names with any character survive the trip.

#include <jni.h>
#include <android/log.h>

#include <filesystem>
#include <memory>
#include <mutex>
#include <string>

#include <nlohmann/json.hpp>

#include "keymap_store.h"
#include "settings_provider.h"

using json = nlohmann::json;
using namespace gaming_keyboard;

namespace fs = std::filesystem;

#define LOG_TAG "KeymapJni"
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

namespace {

std::mutex g_mutex;
std::shared_ptr<KeymapStore> g_store;
std::shared_ptr<SettingsProvider> g_settings;

// ---------- small JNI helpers ----------

std::string ToStdString(JNIEnv* env, jstring s) {
    if (s == nullptr) return "";
    const char* chars = env->GetStringUTFChars(s, nullptr);
    if (chars == nullptr) return "";
    std::string out(chars);
    env->ReleaseStringUTFChars(s, chars);
    return out;
}

std::string BytesToStdString(JNIEnv* env, jbyteArray arr) {
    if (arr == nullptr) return "";
    jsize len = env->GetArrayLength(arr);
    std::string out(static_cast<size_t>(len), '\0');
    if (len > 0) {
        env->GetByteArrayRegion(arr, 0, len, reinterpret_cast<jbyte*>(&out[0]));
    }
    return out;
}

jbyteArray ToByteArray(JNIEnv* env, const std::string& s) {
    jbyteArray arr = env->NewByteArray(static_cast<jsize>(s.size()));
    if (arr == nullptr) return nullptr;
    if (!s.empty()) {
        env->SetByteArrayRegion(arr, 0, static_cast<jsize>(s.size()),
                                reinterpret_cast<const jbyte*>(s.data()));
    }
    return arr;
}

std::string DumpJson(const json& j) {
    // "replace" keeps a stray bad byte from throwing during dump.
    return j.dump(2, ' ', false, json::error_handler_t::replace);
}

// ---------- Keymap <-> JSON (same schema as docs/FILE_FORMAT.md) ----------

json KeymapToJson(const Keymap& k) {
    json mappings = json::array();
    for (const auto& m : k.mappings) {
        mappings.push_back({
            {"id", m.id},
            {"type", m.type == MappingType::kMouse ? "mouse" : "key"},
            {"input_key", m.input_key},
            {"action_label", m.action_label},
            {"position", {{"x", m.position.x}, {"y", m.position.y}}},
        });
    }
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
    k.activation_key = j.value("activation_key", "");
    if (j.contains("mappings")) {
        for (const auto& item : j.at("mappings")) {
            MappingEntry m;
            m.id = item.at("id").get<std::string>();
            m.type = item.at("type").get<std::string>() == "mouse"
                         ? MappingType::kMouse
                         : MappingType::kKey;
            m.input_key = item.at("input_key").get<std::string>();
            m.action_label = item.value("action_label", "");
            m.position.x = item.at("position").at("x").get<int>();
            m.position.y = item.at("position").at("y").get<int>();
            k.mappings.push_back(m);
        }
    }
    return k;
}

}  // namespace

// ---------- JNI entry points ----------
// Java class: org.blissos.gamingkeyboard.KeymapNative

extern "C" JNIEXPORT jboolean JNICALL
Java_org_blissos_gamingkeyboard_KeymapNative_nativeInit(
        JNIEnv* env, jclass, jstring keymap_dir, jstring settings_file) {
    std::string dir = ToStdString(env, keymap_dir);
    std::string settings_path = ToStdString(env, settings_file);
    if (dir.empty() || settings_path.empty()) return JNI_FALSE;

    std::lock_guard<std::mutex> lock(g_mutex);
    try {
        std::error_code ec;
        fs::create_directories(fs::path(settings_path).parent_path(), ec);

        auto store = std::make_shared<KeymapStore>(dir);
        auto storage = std::make_shared<FileSettingsStorage>(settings_path);
        g_store = store;
        g_settings = std::make_shared<SettingsProvider>(store, storage);
    } catch (const std::exception& e) {
        LOGE("Init failed: %s", e.what());
        return JNI_FALSE;
    }
    return JNI_TRUE;
}

// Returns the saved keymap as JSON bytes, or null if the game has none.
extern "C" JNIEXPORT jbyteArray JNICALL
Java_org_blissos_gamingkeyboard_KeymapNative_nativeLoadKeymap(
        JNIEnv* env, jclass, jstring package_name) {
    std::shared_ptr<KeymapStore> store;
    {
        std::lock_guard<std::mutex> lock(g_mutex);
        store = g_store;
    }
    if (!store) return nullptr;

    auto keymap = store->Load(ToStdString(env, package_name));
    if (!keymap) return nullptr;
    return ToByteArray(env, DumpJson(KeymapToJson(*keymap)));
}

// Creates a fresh, empty keymap (not saved yet) with timestamps filled in.
extern "C" JNIEXPORT jbyteArray JNICALL
Java_org_blissos_gamingkeyboard_KeymapNative_nativeNewKeymap(
        JNIEnv* env, jclass, jstring package_name, jbyteArray display_name) {
    std::shared_ptr<KeymapStore> store;
    {
        std::lock_guard<std::mutex> lock(g_mutex);
        store = g_store;
    }
    if (!store) return nullptr;

    Keymap keymap = store->CreateNew(ToStdString(env, package_name),
                                     BytesToStdString(env, display_name));
    return ToByteArray(env, DumpJson(KeymapToJson(keymap)));
}

// Saves a keymap (JSON bytes). Replaces any older file for the same game.
extern "C" JNIEXPORT jboolean JNICALL
Java_org_blissos_gamingkeyboard_KeymapNative_nativeSaveKeymap(
        JNIEnv* env, jclass, jbyteArray keymap_json) {
    std::shared_ptr<KeymapStore> store;
    {
        std::lock_guard<std::mutex> lock(g_mutex);
        store = g_store;
    }
    if (!store) return JNI_FALSE;

    try {
        Keymap keymap = KeymapFromJson(json::parse(BytesToStdString(env, keymap_json)));
        return store->Save(keymap).empty() ? JNI_FALSE : JNI_TRUE;
    } catch (const std::exception& e) {
        LOGE("Save failed: %s", e.what());
        return JNI_FALSE;
    }
}

extern "C" JNIEXPORT jboolean JNICALL
Java_org_blissos_gamingkeyboard_KeymapNative_nativeDeleteKeymap(
        JNIEnv* env, jclass, jstring package_name) {
    std::shared_ptr<KeymapStore> store;
    {
        std::lock_guard<std::mutex> lock(g_mutex);
        store = g_store;
    }
    if (!store) return JNI_FALSE;
    return store->DeleteFile(ToStdString(env, package_name)) ? JNI_TRUE : JNI_FALSE;
}

// Returns a JSON array: [{"package_name": "...", "display_name": "..."}, ...]
extern "C" JNIEXPORT jbyteArray JNICALL
Java_org_blissos_gamingkeyboard_KeymapNative_nativeListGames(JNIEnv* env, jclass) {
    std::shared_ptr<SettingsProvider> settings;
    {
        std::lock_guard<std::mutex> lock(g_mutex);
        settings = g_settings;
    }
    json list = json::array();
    if (settings) {
        for (const auto& game : settings->GetMappedGamesList()) {
            list.push_back({
                {"package_name", game.package_name},
                {"display_name", game.display_name},
            });
        }
    }
    return ToByteArray(env, DumpJson(list));
}

extern "C" JNIEXPORT jstring JNICALL
Java_org_blissos_gamingkeyboard_KeymapNative_nativeGetGlobalActivationKey(JNIEnv* env, jclass) {
    std::shared_ptr<SettingsProvider> settings;
    {
        std::lock_guard<std::mutex> lock(g_mutex);
        settings = g_settings;
    }
    return env->NewStringUTF(settings ? settings->GetGlobalActivationKey().c_str() : "F5");
}

extern "C" JNIEXPORT jboolean JNICALL
Java_org_blissos_gamingkeyboard_KeymapNative_nativeSetGlobalActivationKey(
        JNIEnv* env, jclass, jstring key) {
    std::shared_ptr<SettingsProvider> settings;
    {
        std::lock_guard<std::mutex> lock(g_mutex);
        settings = g_settings;
    }
    if (!settings) return JNI_FALSE;
    return settings->SetGlobalActivationKey(ToStdString(env, key)) ? JNI_TRUE : JNI_FALSE;
}

// The key that opens the overlay for this game: its own key if set,
// otherwise the global one.
extern "C" JNIEXPORT jstring JNICALL
Java_org_blissos_gamingkeyboard_KeymapNative_nativeEffectiveActivationKey(
        JNIEnv* env, jclass, jstring package_name) {
    std::shared_ptr<KeymapStore> store;
    std::shared_ptr<SettingsProvider> settings;
    {
        std::lock_guard<std::mutex> lock(g_mutex);
        store = g_store;
        settings = g_settings;
    }
    if (!store || !settings) return env->NewStringUTF("F5");

    auto keymap = store->Load(ToStdString(env, package_name));
    std::string key = keymap ? settings->EffectiveActivationKey(*keymap)
                             : settings->GetGlobalActivationKey();
    return env->NewStringUTF(key.c_str());
}
