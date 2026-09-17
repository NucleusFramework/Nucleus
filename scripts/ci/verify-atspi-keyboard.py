#!/usr/bin/env python3
"""Keyboard accessibility verification for tao-demo (A11y tab).

Linux counterpart of scripts/ci/verify-uia-keyboard.ps1. Drives the app like a
keyboard user — real X key events through xdotool, never synthetic Compose
input — and observes every result only through the AT-SPI tree:

  1. AT-SPI Component.grabFocus lands the 'focused' state on the target
  2. Tab traversal visits several distinct focusable accessibles
  3. Enter / Space on the focused button advances the click counter
  4. Typing real keystrokes into the entry updates its Text interface
  5. Shift+Tab walks the focus chain backwards
  6. Action.doAction baseline still works (pattern path, no keyboard)

Prerequisites: tao-demo on the A11y tab, xdotool installed, and the window
reachable on the current DISPLAY.
"""

import argparse
import os
import sys
import time

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

import atspi_probe as ap  # noqa: E402

TEXT_FIELD = "A11y text field"

# Upper bound on grabFocus → 'focused' state in the AT-SPI tree. Nothing about
# that chain is synchronous: grabFocus is a D-Bus round trip, Compose applies the
# focus request on its next frame, and AccessKit pushes the resulting tree update
# after it — so on a loaded CI runner the state can land well past the settle
# time a fixed sleep would allow. Polled, not slept.
FOCUS_TIMEOUT_S = 8


def focus_node(app, name, role=None):
    """grabFocus on `name` and wait for the 'focused' state to actually land."""
    node = ap.find_by_name(app, name, role=role, timeout_s=6)
    if node is None:
        return None
    try:
        node.queryComponent().grabFocus()
    except Exception:
        return node
    if not focus_landed(app, name):
        # Never observed carrying the state; give the caller the same minimal
        # settle the fixed sleep used to provide before it reads the tree.
        time.sleep(0.4)
    return node


def focus_landed(app, name):
    """Whether `name` carries the AT-SPI 'focused' state within the timeout."""
    return bool(
        ap.wait_for(
            lambda: name in ap.focused_nodes(app),
            timeout_s=FOCUS_TIMEOUT_S,
            interval_s=0.25,
        )
    )


def entry_text(app):
    node = ap.find_by_name(app, TEXT_FIELD, role="entry", timeout_s=4)
    return None if node is None else ap.text_value(node)


def keystrokes_reach_app(app, window):
    """Preflight: can XTEST keystrokes actually land in the app?

    Under a Wayland compositor, XTEST key events are routed to the surface the
    *compositor* considers focused, which an X11 activation request cannot
    reliably change — so every keyboard assertion would fail for reasons that
    have nothing to do with the a11y stack. Detect that up front and say so.
    """
    field = focus_node(app, TEXT_FIELD, role="entry")
    if field is None:
        return False
    ap.activate_window(window, settle_s=0.3)
    before = entry_text(app) or ""
    ap.type_text("x")
    landed = bool(ap.wait_for(lambda: (entry_text(app) or "") != before, timeout_s=5))
    if landed:
        ap.send_keys("BackSpace")
    return landed


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--app", default=ap.DEFAULT_APP)
    parser.add_argument("--window", default="Tao Backend Demo")
    parser.add_argument("--timeout", type=int, default=int(os.environ.get("ATSPI_TIMEOUT_S", "300")))
    args = parser.parse_args()

    reporter = ap.Reporter("Keyboard a11y verification (AT-SPI)")
    app, _ = ap.find_app(args.app, timeout_s=args.timeout)
    if app is None:
        print(f"  [FAIL] AT-SPI application '{args.app}' not found")
        return 1
    reporter.check(ap.select_tab(app, "A11y"), "A11y tab selected")
    reporter.check(
        ap.find_by_name(app, "Increment", timeout_s=15) is not None, "A11y tab content visible"
    )
    if not reporter.check(ap.activate_window(args.window), f"window '{args.window}' activated"):
        # Without X focus no keystroke can reach the app; failing loudly beats
        # reporting green on an untested path.
        return reporter.exit_code()

    if not reporter.check(
        keystrokes_reach_app(app, args.window), "XTEST keystrokes reach the window"
    ):
        print(
            "\n  Hint: run this probe on a real X server (CI uses Xvfb + openbox).\n"
            f"  Session type is '{os.environ.get('XDG_SESSION_TYPE', 'unknown')}': under Wayland the\n"
            "  compositor owns keyboard focus, so xdotool cannot deliver keys to an\n"
            "  XWayland client that it did not focus itself."
        )
        return reporter.exit_code()

    reporter.section("Focus via AT-SPI grabFocus")
    increment = focus_node(app, "Increment")
    reporter.check(increment is not None, "Increment element found")
    landed = focus_landed(app, "Increment")
    focused = ap.focused_nodes(app)
    print(f"  focused accessibles: {focused}")
    reporter.check(landed, f"Increment carries the 'focused' state (got {focused})")

    reporter.section("Tab key traversal")
    visited = set()
    for _ in range(25):
        visited.update(ap.focused_nodes(app))
        ap.send_keys("Tab")
        time.sleep(0.15)
    visited.discard("")
    print(f"  visited focus names ({len(visited)}): {sorted(visited)[:20]}")
    reporter.check(
        len(visited) >= 3, f"Tab traversal visited >= 3 distinct accessibles (got {len(visited)})"
    )
    interesting = {
        "Increment",
        "Tri-state checkbox",
        "Notifications switch",
        "Volume",
        TEXT_FIELD,
        "Update status",
        "Bare toggleable",
        "Open dialog",
        "Priority Low",
        "Priority Medium",
        "Priority High",
    }
    hits = sorted(visited & interesting)
    reporter.check(len(hits) >= 2, f"Tab chain includes >= 2 known A11y controls (got {hits})")

    reporter.section("Enter / Space activates the focused button")
    focus_node(app, "Increment")
    ap.activate_window(args.window, settle_s=0.3)
    before = ap.click_counter(app)
    ap.send_keys("Return")
    advanced = ap.wait_for(lambda: ap.click_counter(app) > before, timeout_s=8)
    key_used = "Enter"
    if not advanced:
        focus_node(app, "Increment")
        before = ap.click_counter(app)
        ap.send_keys("space")
        advanced = ap.wait_for(lambda: ap.click_counter(app) > before, timeout_s=8)
        key_used = "Space"
    reporter.check(
        bool(advanced),
        f"{key_used} on Increment advances the click counter ({before} -> {ap.click_counter(app)})",
    )

    reporter.section("Typing real keystrokes into the entry")
    field = focus_node(app, TEXT_FIELD, role="entry")
    reporter.check(field is not None, "text field element found")
    ap.activate_window(args.window, settle_s=0.3)
    ap.send_keys("ctrl+a", "BackSpace")
    token = f"kb{int(time.time()) % 100000}"
    ap.type_text(token)
    typed = ap.wait_for(lambda: (entry_text(app) or "").endswith(token), timeout_s=10)
    print(f"  Text interface value: '{entry_text(app)}'")
    reporter.check(bool(typed), f"typed token '{token}' visible through the Text interface")
    status = ap.find_by_prefix(app, "TextField status: text=", timeout_s=5)
    reporter.check(
        status is not None and token in ap.node_name(status),
        f"status label mirrors the typed token ('{ap.node_name(status) if status else None}')",
    )

    reporter.section("Shift+Tab walks the chain backwards")
    focus_node(app, "Increment")
    ap.activate_window(args.window, settle_s=0.3)
    before_focus = ap.focused_nodes(app)
    ap.send_keys("shift+Tab")
    time.sleep(0.4)
    after_focus = ap.focused_nodes(app)
    print(f"  focus before: {before_focus} ; after: {after_focus}")
    reporter.check(
        bool(after_focus) and after_focus != before_focus,
        f"Shift+Tab moved focus ({before_focus} -> {after_focus})",
    )

    reporter.section("Action interface baseline")
    node = ap.find_by_name(app, "Increment", timeout_s=5)
    before = ap.click_counter(app)
    reporter.check(node is not None and ap.do_action(node, "click"), "doAction('click') accepted")
    reporter.check(
        ap.wait_for(lambda: ap.click_counter(app) > before, timeout_s=10),
        f"doAction advances the click counter past {before}",
    )

    return reporter.exit_code()


if __name__ == "__main__":
    sys.exit(main())
