"""
keymap_store.py

Reads and writes the JSON keymap files described in docs/FILE_FORMAT.md.
This module is shared logic — it's deliberately kept free of any UI code
so the same read/write behavior can later be mirrored 1:1 in the C++
service (see cpp_service/keymap_store.h/.cpp for the native equivalent).
"""

import json
import os
import re
import uuid
from datetime import datetime, timezone
from typing import Optional


SCHEMA_VERSION = 1

# In the real system-level build this would be a protected system path,
# e.g. /data/system/gaming_keyboard/keymaps/. For the prototype we use a
# local folder so it can run and be tested standalone.
DEFAULT_STORE_DIR = os.path.join(
    os.path.expanduser("~"), ".gaming_keyboard", "keymaps"
)

# Package names become file names, so only allow plain package characters.
_SAFE_PACKAGE_RE = re.compile(r"^[A-Za-z0-9_][A-Za-z0-9_.]{0,254}$")


def _is_safe_package_name(package_name: str) -> bool:
    return bool(_SAFE_PACKAGE_RE.match(package_name)) and ".." not in package_name


def _now_iso() -> str:
    return datetime.now(timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ")


def _path_for(package_name: str, store_dir: str = DEFAULT_STORE_DIR) -> str:
    os.makedirs(store_dir, exist_ok=True)
    return os.path.join(store_dir, f"{package_name}.json")


def load_keymap(package_name: str, store_dir: str = DEFAULT_STORE_DIR) -> Optional[dict]:
    """Return the saved keymap dict for a package, or None if not mapped yet
    (or if the file is damaged)."""
    if not _is_safe_package_name(package_name):
        return None
    path = _path_for(package_name, store_dir)
    if not os.path.exists(path):
        return None
    try:
        with open(path, "r", encoding="utf-8") as f:
            return json.load(f)
    except (OSError, ValueError):
        return None


def new_keymap(package_name: str, display_name: str, activation_key: str = "") -> dict:
    """Create a fresh in-memory keymap structure for a new mapping session.
    An empty activation_key means "use the global activation key"."""
    now = _now_iso()
    return {
        "schema_version": SCHEMA_VERSION,
        "package_name": package_name,
        "display_name": display_name,
        "created_at": now,
        "updated_at": now,
        "activation_key": activation_key,
        "mappings": [],
    }


def add_mapping(keymap: dict, mapping_type: str, input_key: str,
                 position: tuple, action_label: str = "") -> dict:
    """Append a mapping entry (key or mouse) to the in-memory keymap.

    Returns the entry that was added, so the caller can track it for undo.
    """
    entry = {
        "id": uuid.uuid4().hex[:4],
        "type": mapping_type,
        "input_key": input_key,
        "action_label": action_label,
        "position": {"x": position[0], "y": position[1]},
    }
    keymap["mappings"].append(entry)
    return entry


def undo_last(keymap: dict) -> Optional[dict]:
    """Remove and return the most recently added mapping, if any."""
    if keymap["mappings"]:
        return keymap["mappings"].pop()
    return None


def save_keymap(keymap: dict, store_dir: str = DEFAULT_STORE_DIR) -> str:
    """Write the keymap to disk, overwriting any existing file for this
    package (per the spec: re-saving replaces the older mapping).
    Writes to a temporary file first, so a crash mid-save can't destroy
    the existing mapping."""
    if not _is_safe_package_name(keymap["package_name"]):
        raise ValueError(f"Unsafe package name: {keymap['package_name']!r}")
    keymap["updated_at"] = _now_iso()
    path = _path_for(keymap["package_name"], store_dir)
    tmp_path = path + ".tmp"
    with open(tmp_path, "w", encoding="utf-8") as f:
        json.dump(keymap, f, indent=2)
    os.replace(tmp_path, path)
    return path


def delete_keymap_file(package_name: str, store_dir: str = DEFAULT_STORE_DIR) -> bool:
    """Remove a game's keymap entirely (Settings list 'delete' action).
    Returns True if a file was actually removed."""
    if not _is_safe_package_name(package_name):
        return False
    path = _path_for(package_name, store_dir)
    if os.path.exists(path):
        os.remove(path)
        return True
    return False


def list_mapped_games(store_dir: str = DEFAULT_STORE_DIR) -> list:
    """Return [{'package_name':..., 'display_name':...}, ...] for the
    Settings > Physical Keyboard > Gaming Keyboard list."""
    if not os.path.isdir(store_dir):
        return []
    games = []
    for fname in os.listdir(store_dir):
        if not fname.endswith(".json"):
            continue
        try:
            with open(os.path.join(store_dir, fname), "r", encoding="utf-8") as f:
                data = json.load(f)
            games.append({
                "package_name": data["package_name"],
                "display_name": data.get("display_name", data["package_name"]),
            })
        except (OSError, ValueError, KeyError):
            continue  # one damaged file shouldn't empty the whole list
    return games
