#!/usr/bin/env python3
"""AT-SPI *event delivery* verification for tao-demo.

Linux counterpart of scripts/ci/verify-uia-events.ps1 (which gates on a COM
UIA client receiving PropertyChanged). Here we subscribe to the AT-SPI D-Bus
signals Orca listens to, drive the A11y tab through the same client APIs an AT
would use, and assert the signals actually arrive.

A correct tree that never emits events is useless to a screen reader, so every
category below is gating:
  - object:state-changed:checked            (checkbox toggle)
  - object:property-change:accessible-value (slider)
  - object:property-change:accessible-name  (counter label + live region)
  - object:text-changed:insert              (EditableText round-trip)
  - object:state-changed:focused            (Component.grabFocus)
  - object:children-changed                 (subtree mutation on tab switch)

Runs the driving steps inside the GLib main loop that dispatches the events, so
no cross-thread D-Bus traffic is involved.
"""

import argparse
import os
import sys
import time
from collections import defaultdict

# GLib only — no gi.require_version("Gtk"): the CI runner installs the AT-SPI
# stack without the GTK typelib, and the probe never touches GTK.
from gi.repository import GLib

import pyatspi

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

import atspi_probe as ap  # noqa: E402

EVENT_TYPES = [
    "object:state-changed:checked",
    "object:state-changed:focused",
    "object:property-change:accessible-value",
    "object:property-change:accessible-name",
    "object:text-changed:insert",
    "object:text-caret-moved",
    "object:children-changed",
]


class EventLog:
    def __init__(self, app_name):
        self.app_name = app_name
        self.events = defaultdict(list)
        self.total = 0

    def on_event(self, event):
        try:
            app = event.host_application
            if app is not None and app.name != self.app_name:
                return
        except Exception:
            pass
        try:
            source_name = event.source.name or ""
        except Exception:
            source_name = ""
        self.events[event.type].append((source_name, event.detail1, str(event.any_data)[:60]))
        self.total += 1

    def count(self, prefix):
        return sum(len(v) for k, v in self.events.items() if k.startswith(prefix))

    def sources(self, prefix):
        return [
            src for k, v in self.events.items() if k.startswith(prefix) for src, _, _ in v
        ]


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--app", default=ap.DEFAULT_APP)
    parser.add_argument("--window", default="Tao Backend Demo")
    parser.add_argument("--timeout", type=int, default=int(os.environ.get("ATSPI_TIMEOUT_S", "300")))
    args = parser.parse_args()

    reporter = ap.Reporter("AT-SPI event verification")
    app, _ = ap.find_app(args.app, timeout_s=args.timeout)
    if app is None:
        print(f"  [FAIL] AT-SPI application '{args.app}' not found")
        return 1
    if not ap.select_tab(app, "A11y"):
        print("  [FAIL] cannot select the A11y tab")
        return 1
    # AccessKit gates focus events on the toplevel being focused, so raise the
    # window before subscribing (xdotool; installed alongside at-spi2-core in CI).
    if not ap.activate_window(args.window):
        print(f"  [FAIL] cannot activate window '{args.window}' (xdotool missing?)")
        return 1

    log = EventLog(args.app)
    for event_type in EVENT_TYPES:
        pyatspi.Registry.registerEventListener(log.on_event, event_type)

    steps = []

    def step(description, action):
        steps.append((description, action))

    def toggle_checkbox():
        node = ap.find_by_name(app, "Tri-state checkbox", timeout_s=3)
        return node is not None and ap.do_action(node, "click")

    def set_volume():
        node = ap.find_by_name(app, "Volume", role="slider", timeout_s=3)
        if node is None:
            return False
        node.queryValue().currentValue = 0.42
        return True

    def click_increment():
        node = ap.find_by_name(app, "Increment", timeout_s=3)
        return node is not None and ap.do_action(node, "click")

    def update_live_region():
        node = ap.find_by_name(app, "Update status", timeout_s=3)
        return node is not None and ap.do_action(node, "click")

    def type_into_field():
        node = ap.find_by_name(app, "A11y text field", role="entry", timeout_s=3)
        if node is None:
            return False
        # EditableText is the interface Orca's "type this" command uses; it
        # lands on the JVM as SemanticsActions.SetText.
        node.queryEditableText().setTextContents(f"evt{int(time.time()) % 100000}")
        return True

    def focus_field():
        node = ap.find_by_name(app, "A11y text field", role="entry", timeout_s=3)
        return node is not None and node.queryComponent().grabFocus()

    def switch_tab():
        return ap.select_tab(app, "Complex", settle_s=0.0)

    step("toggle Tri-state checkbox", toggle_checkbox)
    step("set Volume value", set_volume)
    step("click Increment", click_increment)
    step("click Update status (live region)", update_live_region)
    step("EditableText.setTextContents", type_into_field)
    step("Component.grabFocus on the text field", focus_field)
    step("switch to the Complex tab", switch_tab)

    results = {}
    pending = list(steps)

    def pump():
        if not pending:
            GLib.timeout_add(2500, lambda: (pyatspi.Registry.stop(), False)[1])
            return False
        description, action = pending.pop(0)
        try:
            results[description] = bool(action())
        except Exception as error:  # noqa: BLE001 - reported below
            print(f"  step '{description}' raised {type(error).__name__}: {error}")
            results[description] = False
        GLib.timeout_add(1200, pump)
        return False

    GLib.timeout_add(500, pump)
    GLib.timeout_add_seconds(90, lambda: (pyatspi.Registry.stop(), False)[1])
    pyatspi.Registry.start()

    for event_type in sorted(log.events):
        print(f"  {event_type}: {len(log.events[event_type])}")
    print(f"  total events: {log.total}")

    reporter.section("Driving steps")
    for description, _ in steps:
        reporter.check(results.get(description, False), f"step: {description}")

    reporter.section("Signals received")
    reporter.check(log.total >= 1, f"AT-SPI client received events (total={log.total})")
    reporter.check(
        log.count("object:state-changed:checked") >= 1,
        "state-changed:checked from the checkbox toggle",
    )
    reporter.check(
        log.count("object:property-change:accessible-value") >= 1,
        "property-change:accessible-value from the slider",
    )
    name_sources = log.sources("object:property-change:accessible-name")
    reporter.check(
        len(name_sources) >= 1,
        f"property-change:accessible-name (sources: {name_sources[:4]})",
    )
    reporter.check(
        any(src.startswith("Status updated at") for src in name_sources),
        "live region (assertive) announced its new text via accessible-name",
    )
    reporter.check(
        log.count("object:text-changed:insert") >= 1,
        "text-changed:insert from the EditableText round-trip",
    )
    reporter.check(
        log.count("object:state-changed:focused") >= 1,
        "state-changed:focused from Component.grabFocus",
    )
    reporter.check(
        log.count("object:children-changed") >= 1,
        "children-changed when the tab subtree is replaced",
    )

    return reporter.exit_code()


if __name__ == "__main__":
    sys.exit(main())
