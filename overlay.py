"""
overlay.py

Runnable prototype of the Gaming Keyboard overlay described by Shad:

  - Translucent "recorder style" window titled "Gaming Keyboard"
  - Three buttons: Undo / Save / Delete, plus a red X (close) to the right
  - Drag the window anywhere; press a key to map it at that position
  - Double-click the center of the window to arm mouse-mapping mode,
    then drag to place it
  - Click an already-placed mapping to re-arm it for reassignment
    (it highlights to confirm selection)

This is a desktop prototype for demoing/testing the interaction model.
It is NOT the system-level overlay — that has to be a native Android/
Bliss OS accessibility-layer overlay, which is out of scope for Python.
The point here is to nail the UX and mapping logic so the native
implementation (see cpp_service/) has a clear, already-tested reference.

Run with: python3 overlay.py <package_name> <display_name>
Example:  python3 overlay.py com.kiloo.subwaysurf "Subway Surfers"
"""

import sys
import tkinter as tk
from tkinter import font as tkfont

import keymap_store as store


HIGHLIGHT_COLOR = "#5ec8ff"
BG_COLOR = "#1c1c1c"
FG_COLOR = "#f2f2f2"
ACCENT_COLOR = "#2f2f2f"
CLOSE_COLOR = "#e05555"


class MappingChip:
    """A single mapped key/mouse action shown as a small draggable chip
    inside the overlay. Clicking it re-arms it for reassignment."""

    def __init__(self, canvas, entry, on_select):
        self.canvas = canvas
        self.entry = entry
        self.on_select = on_select
        x, y = entry["position"]["x"], entry["position"]["y"]
        label = entry["input_key"]
        self.rect = canvas.create_oval(
            x - 22, y - 22, x + 22, y + 22,
            fill=ACCENT_COLOR, outline=HIGHLIGHT_COLOR, width=2
        )
        self.text = canvas.create_text(
            x, y, text=label, fill=FG_COLOR, font=("Helvetica", 10, "bold")
        )
        canvas.tag_bind(self.rect, "<Button-1>", self._clicked)
        canvas.tag_bind(self.text, "<Button-1>", self._clicked)

    def _clicked(self, _event):
        self.on_select(self)

    def flash_selected(self):
        """Visual confirmation that this chip is now armed for reassignment."""
        self.canvas.itemconfig(self.rect, outline="#ffffff", width=4)
        self.canvas.after(
            250, lambda: self.canvas.itemconfig(self.rect, outline=HIGHLIGHT_COLOR, width=2)
        )

    def update_label(self, new_key):
        self.entry["input_key"] = new_key
        self.canvas.itemconfig(self.text, text=new_key)

    def move_to(self, x, y):
        self.entry["position"]["x"] = x
        self.entry["position"]["y"] = y
        self.canvas.coords(self.rect, x - 22, y - 22, x + 22, y + 22)
        self.canvas.coords(self.text, x, y)

    def destroy(self):
        self.canvas.delete(self.rect)
        self.canvas.delete(self.text)


class GamingKeyboardOverlay:
    def __init__(self, root, package_name, display_name):
        self.root = root
        self.package_name = package_name
        self.display_name = display_name

        existing = store.load_keymap(package_name)
        if existing:
            self.keymap = existing
        else:
            self.keymap = store.new_keymap(package_name, display_name)

        self.chips = {}  # entry id -> MappingChip
        self.armed_entry_id = None  # id of chip waiting for a new key press
        self.awaiting_new_mapping_at = None  # (x, y) waiting for first key/click

        self._build_window()
        self._render_existing_mappings()

    # ---------- window / chrome ----------

    def _build_window(self):
        root = self.root
        root.title("Gaming Keyboard")
        root.attributes("-alpha", 0.85)  # translucent
        root.configure(bg=BG_COLOR)
        root.geometry("560x360+300+200")
        root.attributes("-topmost", True)

        header = tk.Frame(root, bg=BG_COLOR)
        header.pack(fill="x", padx=8, pady=(8, 0))

        title_font = tkfont.Font(family="Helvetica", size=13, weight="bold")
        tk.Label(
            header, text=f"Gaming Keyboard — {self.display_name}",
            bg=BG_COLOR, fg=FG_COLOR, font=title_font
        ).pack(side="left")

        close_btn = tk.Button(
            header, text="✕", command=self._on_close,
            bg=BG_COLOR, fg=CLOSE_COLOR, bd=0, font=("Helvetica", 14, "bold"),
            activebackground=BG_COLOR, activeforeground=CLOSE_COLOR
        )
        close_btn.pack(side="right")

        btn_bar = tk.Frame(root, bg=BG_COLOR)
        btn_bar.pack(fill="x", padx=8, pady=6)

        tk.Button(btn_bar, text="Undo", command=self._on_undo,
                  bg=ACCENT_COLOR, fg=FG_COLOR, bd=0, padx=12, pady=4).pack(side="left", padx=4)
        tk.Button(btn_bar, text="Delete", command=self._on_delete,
                  bg=ACCENT_COLOR, fg=FG_COLOR, bd=0, padx=12, pady=4).pack(side="left", padx=4)
        tk.Button(btn_bar, text="Save", command=self._on_save,
                  bg="#3a7a3a", fg=FG_COLOR, bd=0, padx=12, pady=4).pack(side="left", padx=4)

        self.status = tk.Label(
            root, text=self._status_text(), bg=BG_COLOR, fg="#9a9a9a",
            font=("Helvetica", 9)
        )
        self.status.pack(fill="x", padx=8)

        # The mapping surface: drag the whole window using this canvas,
        # single-click empty space to arm a new key mapping at that point,
        # double-click center to arm mouse-mapping mode.
        self.canvas = tk.Canvas(root, bg="#101010", highlightthickness=0)
        self.canvas.pack(fill="both", expand=True, padx=8, pady=8)

        self.canvas.bind("<ButtonPress-1>", self._canvas_press)
        self.canvas.bind("<B1-Motion>", self._canvas_drag_window)
        self.canvas.bind("<Double-Button-1>", self._canvas_double_click)

        root.bind("<Key>", self._on_key_press)

        self._drag_origin = None

    def _status_text(self):
        if self.awaiting_new_mapping_at:
            return "Click a spot, then press any key (or double-click for mouse)…"
        if self.armed_entry_id:
            return "Reassigning — press the new key now…"
        return "Click empty space to add a mapping, or click an existing one to change it."

    def _refresh_status(self):
        self.status.config(text=self._status_text())

    # ---------- rendering ----------

    def _render_existing_mappings(self):
        for entry in self.keymap["mappings"]:
            chip = MappingChip(self.canvas, entry, self._on_chip_selected)
            self.chips[entry["id"]] = chip

    # ---------- interaction: placing / dragging the overlay itself ----------

    def _canvas_press(self, event):
        # If clicking empty canvas space (not a chip), arm a new mapping here.
        clicked = self.canvas.find_withtag("current")
        if not clicked:
            self.awaiting_new_mapping_at = (event.x, event.y)
            self.armed_entry_id = None
            self._refresh_status()
        self._drag_origin = (event.x_root, event.y_root)

    def _canvas_drag_window(self, event):
        # Whole-window drag (only meaningful when not actively placing a chip)
        if self._drag_origin is None:
            return
        dx = event.x_root - self._drag_origin[0]
        dy = event.y_root - self._drag_origin[1]
        x = self.root.winfo_x() + dx
        y = self.root.winfo_y() + dy
        self.root.geometry(f"+{x}+{y}")
        self._drag_origin = (event.x_root, event.y_root)

    def _canvas_double_click(self, event):
        """Double-click = arm mouse mapping mode at this position."""
        self.awaiting_new_mapping_at = (event.x, event.y)
        self.armed_entry_id = None
        self._refresh_status()
        entry = store.add_mapping(
            self.keymap, mapping_type="mouse", input_key="MOUSE_LEFT",
            position=(event.x, event.y), action_label=""
        )
        chip = MappingChip(self.canvas, entry, self._on_chip_selected)
        self.chips[entry["id"]] = chip
        self.awaiting_new_mapping_at = None
        self._refresh_status()

    # ---------- interaction: chip selection / reassignment ----------

    def _on_chip_selected(self, chip: MappingChip):
        chip.flash_selected()
        self.armed_entry_id = chip.entry["id"]
        self.awaiting_new_mapping_at = None
        self._refresh_status()

    # ---------- interaction: key capture ----------

    def _on_key_press(self, event):
        key_name = event.keysym.upper()

        if self.armed_entry_id:
            chip = self.chips[self.armed_entry_id]
            chip.update_label(key_name)
            self.armed_entry_id = None
            self._refresh_status()
            return

        if self.awaiting_new_mapping_at:
            x, y = self.awaiting_new_mapping_at
            entry = store.add_mapping(
                self.keymap, mapping_type="key", input_key=key_name,
                position=(x, y), action_label=""
            )
            chip = MappingChip(self.canvas, entry, self._on_chip_selected)
            self.chips[entry["id"]] = chip
            self.awaiting_new_mapping_at = None
            self._refresh_status()

    # ---------- buttons ----------

    def _on_undo(self):
        removed = store.undo_last(self.keymap)
        if removed and removed["id"] in self.chips:
            self.chips.pop(removed["id"]).destroy()
        self._refresh_status()

    def _on_delete(self):
        # Discard the whole in-memory session without writing to disk.
        for chip in self.chips.values():
            chip.destroy()
        self.chips.clear()
        self.keymap["mappings"].clear()
        self._on_close()

    def _on_save(self):
        path = store.save_keymap(self.keymap)
        print(f"Saved keymap to: {path}")
        self._on_close()

    def _on_close(self):
        self.root.destroy()


def main():
    if len(sys.argv) < 3:
        print('Usage: python3 overlay.py <package_name> "<Display Name>"')
        print('Example: python3 overlay.py com.kiloo.subwaysurf "Subway Surfers"')
        sys.exit(1)

    package_name = sys.argv[1]
    display_name = sys.argv[2]

    root = tk.Tk()
    GamingKeyboardOverlay(root, package_name, display_name)
    root.mainloop()


if __name__ == "__main__":
    main()
