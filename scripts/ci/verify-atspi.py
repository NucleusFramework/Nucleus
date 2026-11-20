#!/usr/bin/env python3
"""CI probe: verifies the Tao Linux AT-SPI projection (AccessKit) end-to-end.

Walks the AT-SPI desktop through pyatspi (the same bus Orca uses), locates the
Lab a11y-surface fixture window (opens on the A11y tab), and asserts:
  1. the expected named elements are projected,
  2. performing the Increment action updates the click counter.

Requires: at-spi2-core running (registryd), org.a11y.Status.IsEnabled=true
(the CI job sets it with busctl, mirroring scripts/a11y-linux-iter.sh), and
python3-pyatspi. Exit 0 = all assertions hold.
"""
import os
import sys
import time

import pyatspi

TIMEOUT_S = int(os.environ.get("ATSPI_TIMEOUT_S", "300"))
EXPECTED = ["Increment", "Tri-state checkbox", "Notifications switch"]


def walk(node, depth=0, out=None):
    if out is None:
        out = []
    try:
        out.append(node)
        if depth < 14:
            for i in range(node.childCount):
                child = node.getChildAtIndex(i)
                if child is not None:
                    walk(child, depth + 1, out)
    except Exception:
        pass
    return out


def find_app():
    desktop = pyatspi.Registry.getDesktop(0)
    for i in range(desktop.childCount):
        app = desktop.getChildAtIndex(i)
        if app is None:
            continue
        nodes = walk(app)
        names = {n.name for n in nodes if n.name}
        if "Increment" in names:
            return app, nodes
    return None, []


def main():
    failures = 0
    print("── AT-SPI a11y verification ──")
    deadline = time.time() + TIMEOUT_S
    app, nodes = None, []
    while time.time() < deadline:
        app, nodes = find_app()
        if app:
            break
        time.sleep(2)
    if not app:
        print("  [FAIL] no AT-SPI application exposing 'Increment' found")
        sys.exit(1)
    print(f"application: '{app.name}' ({len(nodes)} accessibles)")

    names = {n.name for n in nodes if n.name}
    for expected in EXPECTED:
        ok = expected in names
        print(f"  [{'PASS' if ok else 'FAIL'}] element '{expected}' projected")
        failures += 0 if ok else 1

    # Action round-trip: Increment -> "click counter 1" appears.
    increment = next((n for n in nodes if n.name == "Increment"), None)
    acted = False
    if increment is not None:
        try:
            action = increment.queryAction()
            for i in range(action.nActions):
                action.doAction(i)
                acted = True
                break
        except Exception as e:  # noqa: BLE001 - report and fail below
            print(f"  action error: {e}")
    if acted:
        updated = False
        act_deadline = time.time() + 15
        while time.time() < act_deadline and not updated:
            _, nodes = find_app()
            updated = any(n.name == "click counter 1" for n in nodes if n.name)
            if not updated:
                time.sleep(1)
        print(f"  [{'PASS' if updated else 'FAIL'}] doAction(Increment) -> 'click counter 1'")
        failures += 0 if updated else 1
    else:
        print("  [FAIL] Increment exposes no performable action")
        failures += 1

    # ── Advanced semantics ──────────────────────────────────────────────
    def refind(prefix):
        _, ns = find_app()
        return next((n for n in ns if n.name and n.name.startswith(prefix)), None), ns

    def has_state(node, state):
        try:
            return node.getState().contains(state)
        except Exception:
            return False

    _, nodes = find_app()

    # Headings project as ROLE_HEADING.
    heading = next(
        (n for n in nodes if n.name == "Accessibility Test Surface" and n.getRole() == pyatspi.ROLE_HEADING),
        None,
    )
    print(f"  [{'PASS' if heading else 'FAIL'}] heading role projected for 'Accessibility Test Surface'")
    failures += 0 if heading else 1

    # Disabled button must NOT carry STATE_ENABLED.
    cannot = next((n for n in nodes if n.name == "Cannot press"), None)
    ok = cannot is not None and not has_state(cannot, pyatspi.STATE_ENABLED)
    print(f"  [{'PASS' if ok else 'FAIL'}] 'Cannot press' projected as disabled")
    failures += 0 if ok else 1

    # Toggleable without an explicit Role must still be checkable.
    bare = next((n for n in nodes if n.name == "Bare toggleable"), None)
    ok = bare is not None and has_state(bare, pyatspi.STATE_CHECKABLE)
    print(f"  [{'PASS' if ok else 'FAIL'}] 'Bare toggleable' projected as checkable")
    failures += 0 if ok else 1

    # Custom actions carry their labels and are performable.
    notif, _ = refind("Notification (clicks:")
    performed = False
    if notif is not None:
        try:
            action = notif.queryAction()
            names = [action.getName(i) for i in range(action.nActions)]
            if "Mark as read" in names:
                action.doAction(names.index("Mark as read"))
                performed = True
            else:
                print(f"  custom actions seen: {names}")
        except Exception as e:  # noqa: BLE001
            print(f"  custom action error: {type(e).__name__}: {e!r}")
    updated = False
    if performed:
        deadline2 = time.time() + 15
        while time.time() < deadline2 and not updated:
            n2, _ = refind("Notification (clicks:")
            updated = n2 is not None and "10" in n2.name  # clicks jumped by +100
            if not updated:
                time.sleep(1)
    print(f"  [{'PASS' if updated else 'FAIL'}] custom action 'Mark as read' round-trip")
    failures += 0 if updated else 1

    # Live region: pressing 'Update status' must surface the new status text.
    upd = next((n for n in nodes if n.name == "Update status"), None)
    live = False
    if upd is not None:
        try:
            a = upd.queryAction()
            if a.nActions > 0:
                a.doAction(0)
            deadline3 = time.time() + 15
            while time.time() < deadline3 and not live:
                n3, _ = refind("Status updated at")
                live = n3 is not None
                if not live:
                    time.sleep(1)
        except Exception as e:  # noqa: BLE001
            print(f"  live region error: {e}")
    print(f"  [{'PASS' if live else 'FAIL'}] live region text updated after 'Update status'")
    failures += 0 if live else 1

    print(f"── {failures} failure(s) ──")
    sys.exit(1 if failures else 0)


if __name__ == "__main__":
    main()
