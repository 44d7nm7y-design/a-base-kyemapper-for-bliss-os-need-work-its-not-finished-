// keymap_store.h
//
// System-level keymap storage manager. This is the C++ counterpart to
// python_prototype/keymap_store.py — same file format (see
// docs/FILE_FORMAT.md), same behavior, so a mapping made in the
// prototype and one made by this native service are interchangeable.
//
// Intended to run as part of a Bliss OS system service (not inside any
// single app's process), storing files outside app sandboxes so they
// survive uninstall/reinstall of the mapped game.
//
// NOTE FOR INTEGRATOR: this header/impl deliberately has no Android-
// specific includes so it can be unit-tested on a desktop Linux build
// first. Package-install/launch detection (which needs PackageManager /
// ActivityManager hooks) lives separately in game_detector.h — this
// file only knows how to read/write keymap files.

#pragma once

#include <string>
#include <vector>
#include <optional>
#include <cstdint>

namespace gaming_keyboard {

struct Position {
    int x = 0;
    int y = 0;
};

enum class MappingType {
    kKey,
    kMouse,
};

struct MappingEntry {
    std::string id;              // 4-char unique id, used for undo tracking
    MappingType type;
    std::string input_key;       // e.g. "W", "SPACE", "MOUSE_LEFT"
    std::string action_label;    // optional human label
    Position position;
};

struct Keymap {
    int schema_version = 1;
    std::string package_name;    // persistent identity key (Android package id)
    std::string display_name;    // cosmetic, shown in Settings list
    std::string created_at;      // ISO-8601 UTC
    std::string updated_at;      // ISO-8601 UTC
    std::string activation_key;  // e.g. "F5"; falls back to global default if empty
    std::vector<MappingEntry> mappings;
};

struct GameListing {
    std::string package_name;
    std::string display_name;
};

// KeymapStore owns all reads/writes to the on-disk keymap files.
// One instance is expected to be shared by the overlay service and the
// Settings UI provider (see settings_provider.h).
class KeymapStore {
public:
    // store_dir should be a system-level directory outside any app's
    // sandbox, e.g. "/data/system/gaming_keyboard/keymaps".
    explicit KeymapStore(std::string store_dir);

    // Returns the saved keymap for a package, or std::nullopt if the
    // game has never been mapped.
    std::optional<Keymap> Load(const std::string& package_name) const;

    // Creates a fresh, empty in-memory keymap for a new mapping session.
    // Does not write to disk until Save() is called.
    Keymap CreateNew(const std::string& package_name,
                      const std::string& display_name,
                      const std::string& activation_key = "F5") const;

    // Appends a mapping entry to the in-memory keymap and returns the
    // generated id (for undo tracking). Does not write to disk.
    std::string AddMapping(Keymap& keymap,
                            MappingType type,
                            const std::string& input_key,
                            Position position,
                            const std::string& action_label = "");

    // Removes the most recently added mapping entry (LIFO), if any.
    // Returns true if an entry was removed. Does not write to disk.
    bool UndoLast(Keymap& keymap);

    // Writes the keymap to disk, overwriting any existing file for this
    // package_name in full (re-saving always replaces, per spec).
    // Returns the path written to.
    std::string Save(Keymap& keymap) const;

    // Removes a game's keymap file entirely (used both by the in-overlay
    // "Delete" — which discards an unsaved session and never calls this —
    // and by the Settings-list delete action, which does call this).
    bool DeleteFile(const std::string& package_name) const;

    // Lists every game that currently has a saved keymap, for the
    // Settings > Physical Keyboard > Gaming Keyboard list.
    std::vector<GameListing> ListMappedGames() const;

private:
    std::string store_dir_;

    std::string PathFor(const std::string& package_name) const;
};

}  // namespace gaming_keyboard
