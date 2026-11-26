# global-hotkey internals

`HotKeyEventListener` gets every `HotKeyEvent` (PRESSED / RELEASED, `isRepeat`, `timestamp`, `activationToken`); the legacy `HotKeyListener` is presses only (repeats included).

## Wayland activation token (#739)

Linux portal: `Activated` / `Deactivated` carry an `activation_token` (xdg-desktop-portal 1.21+, GNOME 50) — the only way a Wayland window may take focus from a hotkey. It is handled automatically: `global-hotkey` and `notification-linux` (`ActivationToken` signal) `offer` every token to `core-runtime`'s `ActivationToken` mailbox (latest wins, single-use, 10 s), and `TaoWindow.focus()` (so `requestFocus()` / `toFront()`) `take`s it on Linux and hands it to GDK (`gdk_wayland_display_set_startup_notification_id` right before `present`, consumed by `gdk_wayland_window_focus`); without it GDK mints a token from the app's last input serial and Mutter refuses it ("App is ready").

## Press / repeat / release

GNOME re-emits `Activated` per auto-repeat (~30 ms, fresh token each): `isRepeat` = no release since the previous press and that press < 1 s old. X11 maps the grab's `KeyRelease`, matched on keycode alone (modifiers may lift first).

## E2E

The portal resolves a host app id from an `app-<id>-<n>.scope` cgroup **and** a matching `.desktop` file. `scripts/linux-hotkey-activation-e2e.sh` + `examples/hotkey-activation-demo` (real GNOME portal, pre-seeded dconf grant, uinput keyboard, zenity holding focus; oracle = a key typed after the hotkey reaches the window). A re-mapped (hidden → shown) Wayland toplevel is a new window that Mutter focuses with or without a token — the bug is a window that stays mapped.
