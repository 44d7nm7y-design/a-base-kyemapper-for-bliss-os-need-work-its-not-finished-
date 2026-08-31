"""
settings_list.py

Prototype of the Settings > Language & Input > Physical Keyboard >
Gaming Keyboard list screen. Shows every game with a saved keymap
(styled like Google's "signed-in devices" list), lets you delete an
entry, and lets you change the global activation key.

Run with: python3 settings_list.py
"""

import tkinter as tk
from tkinter import messagebox

import keymap_store as store

ACTIVATION_KEY_CHOICES = [f"F{i}" for i in range(1, 10)] + [str(i) for i in range(1, 10)]

BG_COLOR = "#151515"
FG_COLOR = "#f2f2f2"
ROW_COLOR = "#232323"
ACCENT_COLOR = "#2f2f2f"
DANGER_COLOR = "#e05555"


class GamingKeyboardSettings:
    def __init__(self, root):
        self.root = root
        root.title("Physical Keyboard — Gaming Keyboard")
        root.configure(bg=BG_COLOR)
        root.geometry("420x480")

        tk.Label(
            root, text="Gaming Keyboard", bg=BG_COLOR, fg=FG_COLOR,
            font=("Helvetica", 15, "bold")
        ).pack(anchor="w", padx=16, pady=(16, 4))

        tk.Label(
            root,
            text="Games with a saved key mapping. Reinstalling a game\n"
                 "restores its mapping automatically.",
            bg=BG_COLOR, fg="#9a9a9a", font=("Helvetica", 9), justify="left"
        ).pack(anchor="w", padx=16, pady=(0, 12))

        # Activation key selector
        key_row = tk.Frame(root, bg=BG_COLOR)
        key_row.pack(fill="x", padx=16, pady=(0, 12))
        tk.Label(key_row, text="Activation key:", bg=BG_COLOR, fg=FG_COLOR).pack(side="left")

        self.activation_var = tk.StringVar(value="F5")
        key_menu = tk.OptionMenu(key_row, self.activation_var, *ACTIVATION_KEY_CHOICES)
        key_menu.configure(bg=ACCENT_COLOR, fg=FG_COLOR, highlightthickness=0)
        key_menu.pack(side="left", padx=8)

        self.list_frame = tk.Frame(root, bg=BG_COLOR)
        self.list_frame.pack(fill="both", expand=True, padx=16, pady=8)

        self._render_list()

    def _render_list(self):
        for widget in self.list_frame.winfo_children():
            widget.destroy()

        games = store.list_mapped_games()

        if not games:
            tk.Label(
                self.list_frame, text="No games mapped yet.",
                bg=BG_COLOR, fg="#9a9a9a"
            ).pack(pady=20)
            return

        for game in games:
            row = tk.Frame(self.list_frame, bg=ROW_COLOR)
            row.pack(fill="x", pady=4)

            tk.Label(
                row, text=game["display_name"], bg=ROW_COLOR, fg=FG_COLOR,
                font=("Helvetica", 11), anchor="w"
            ).pack(side="left", padx=10, pady=10, fill="x", expand=True)

            tk.Label(
                row, text=game["package_name"], bg=ROW_COLOR, fg="#7a7a7a",
                font=("Helvetica", 8)
            ).pack(side="left", padx=(0, 10))

            tk.Button(
                row, text="Delete", command=lambda p=game["package_name"]: self._delete(p),
                bg=DANGER_COLOR, fg="white", bd=0, padx=10, pady=4
            ).pack(side="right", padx=10)

    def _delete(self, package_name):
        if messagebox.askyesno(
            "Remove mapping",
            f"Remove the saved key mapping for {package_name}?"
        ):
            store.delete_keymap_file(package_name)
            self._render_list()


def main():
    root = tk.Tk()
    GamingKeyboardSettings(root)
    root.mainloop()


if __name__ == "__main__":
    main()
