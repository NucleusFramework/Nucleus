#!/usr/bin/env python3
"""Multi-tab AT-SPI probe for tao-demo.

Linux counterpart of scripts/ci/verify-uia-all-tabs.ps1.

Walks every main navigation tab through AT-SPI (no NUCLEUS_DEMO_TAB — tabs are
switched with the same Action interface an AT uses), asserts key accessible
names exist per tab, and exercises the interfaces a screen reader would drive:
Action (click / custom actions), Value (slider, progress) and the state
round-trips they produce.

Exit 0 = every tab tree is navigable and all action round-trips land.
"""

import argparse
import os
import sys
import time

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

import atspi_probe as ap  # noqa: E402

TAB_CHECKS = [
    ("Demo", ["Demo"], 8),
    ("Scroll", ["Scroll"], 15),
    ("Zoom", ["Zoom"], 8),
    ("Window actions", ["Window actions"], 10),
    (
        "A11y",
        ["Increment", "Tri-state checkbox", "Notifications switch", "Volume", "Cannot press"],
        25,
    ),
    ("Complex", ["Add item", "Clear done", "Reset", "Buy milk", "Start"], 30),
    ("Events", ["Events"], 8),
    ("WebView", ["WebView"], 6),
    ("SwiftUI", ["SwiftUI"], 6),
    # Texture: the contentScale / filterQuality labels are the only strings that
    # are identical on every platform. The producer summary line names the
    # backend (D3D11 / Metal IOSurface / DMA-BUF) and degrades to an
    # "unavailable" message on a CI runner with no render node, so it is not
    # something to assert on.
    ("Texture", ["FillBounds", "Crop", "None (nearest)"], 8),
]


def named_nodes(app):
    return [ap.node_name(node) for _, node in ap.nodes_of(app) if ap.node_name(node)]


def checked_state(node):
    return "checked" in ap.state_names(node)


def exercise_a11y_tab(app, reporter):
    increment = ap.find_by_name(app, "Increment", timeout_s=5)
    before = ap.click_counter(app)
    reporter.check(
        increment is not None and ap.do_action(increment, "click"),
        "A11y: Action.doAction('click') on Increment",
    )
    reporter.check(
        ap.wait_for(lambda: ap.click_counter(app) > before, timeout_s=15),
        f"A11y: click counter advances past {before}",
    )

    checkbox = ap.find_by_name(app, "Tri-state checkbox", timeout_s=5)
    if checkbox is None:
        reporter.check(False, "A11y: Tri-state checkbox present")
    else:
        before_checked = checked_state(checkbox)
        ap.do_action(checkbox, "click")
        after = ap.wait_for(
            lambda: (
                lambda n: n is not None and checked_state(n) != before_checked
            )(ap.find_by_name(app, "Tri-state checkbox", timeout_s=2)),
            timeout_s=10,
        )
        reporter.check(bool(after), f"A11y: checkbox 'checked' state flips (was {before_checked})")

    volume = ap.find_by_name(app, "Volume", role="slider", timeout_s=5)
    if volume is None:
        reporter.check(False, "A11y: Volume slider present")
    else:
        try:
            volume.queryValue().currentValue = 0.55
            applied = ap.wait_for(
                lambda: (
                    lambda n: n is not None
                    and ap.numeric_value(n)
                    and abs(ap.numeric_value(n)[0] - 0.55) < 0.08
                )(ap.find_by_name(app, "Volume", role="slider", timeout_s=2)),
                timeout_s=10,
            )
            reporter.check(bool(applied), "A11y: Value.setCurrentValue(0.55) on Volume round-trips")
        except Exception as error:  # noqa: BLE001 - reported as a failure
            reporter.check(False, f"A11y: Volume Value interface: {error}")

    notification = ap.find_by_prefix(app, "Notification (clicks:", timeout_s=5)
    if notification is None:
        reporter.check(False, "A11y: custom-action node present")
    else:
        names = ap.action_names(notification)
        reporter.check(
            names == ["Mark as read", "Archive"],
            f"A11y: custom actions exposed through Action interface {names}",
        )


def exercise_complex_tab(app, reporter):
    add = ap.find_by_name(app, "Add item", timeout_s=5)
    reporter.check(add is not None and ap.do_action(add, "click"), "Complex: click 'Add item'")
    reporter.check(
        ap.wait_for(lambda: ap.find_by_prefix(app, "Item #", timeout_s=1) is not None, timeout_s=10),
        "Complex: new 'Item #N' appears in the tree",
    )

    toggle_name = "Toggle done for Buy milk"
    toggle = ap.find_by_name(app, toggle_name, timeout_s=5)
    if toggle is None:
        reporter.check(False, f"Complex: '{toggle_name}' present")
    else:
        before = checked_state(toggle)
        ap.do_action(toggle, "click")
        flipped = ap.wait_for(
            lambda: (
                lambda n: n is not None and checked_state(n) != before
            )(ap.find_by_name(app, toggle_name, timeout_s=2)),
            timeout_s=10,
        )
        reporter.check(bool(flipped), f"Complex: '{toggle_name}' checked flips (was {before})")

    group = ap.find_by_prefix(app, "Group α (settings)", timeout_s=5)
    if group is None:
        reporter.check(False, "Complex: expandable group present")
    else:
        before_name = ap.node_name(group)
        ap.do_action(group, "click")
        renamed = ap.wait_for(
            lambda: (
                lambda n: n is not None and ap.node_name(n) != before_name
            )(ap.find_by_prefix(app, "Group α (settings)", timeout_s=2)),
            timeout_s=10,
        )
        after = ap.find_by_prefix(app, "Group α (settings)", timeout_s=2)
        reporter.check(
            bool(renamed),
            f"Complex: expand/collapse renames group ('{before_name}' -> '{ap.node_name(after)}')",
        )

    start = ap.find_by_name(app, "Start", timeout_s=5)
    if start is None:
        reporter.check(False, "Complex: ticker 'Start' present")
    else:
        ap.do_action(start, "click")
        reporter.check(
            ap.wait_for(lambda: ap.find_by_name(app, "Stop", timeout_s=1) is not None, timeout_s=10),
            "Complex: 'Start' becomes 'Stop' after activation",
        )
        progress = ap.find_by_name(app, "Auto-ticker", timeout_s=5)
        if progress is None:
            reporter.check(False, "Complex: 'Auto-ticker' progress bar present")
        else:
            first = ap.numeric_value(progress)
            ticked = ap.wait_for(
                lambda: (
                    lambda n: n is not None
                    and ap.numeric_value(n)
                    and first
                    and ap.numeric_value(n)[0] != first[0]
                )(ap.find_by_name(app, "Auto-ticker", timeout_s=2)),
                timeout_s=15,
            )
            reporter.check(
                bool(ticked),
                "Complex: high-frequency partial updates move the progress Value",
            )
        stop = ap.find_by_name(app, "Stop", timeout_s=3)
        if stop is not None:
            ap.do_action(stop, "click")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--app", default=ap.DEFAULT_APP)
    parser.add_argument("--timeout", type=int, default=int(os.environ.get("ATSPI_TIMEOUT_S", "300")))
    args = parser.parse_args()

    reporter = ap.Reporter("Multi-tab AT-SPI verification")
    app, nodes = ap.find_app(args.app, timeout_s=args.timeout)
    if app is None:
        print(f"  [FAIL] AT-SPI application '{args.app}' not found")
        return 1
    print(f"application: '{args.app}' ({len(nodes)} accessibles)")

    reporter.section("Tab bar")
    for tab_name, _, _ in TAB_CHECKS:
        tab = ap.find_by_name(app, tab_name, role="page tab", timeout_s=6)
        reporter.check(tab is not None, f"tab '{tab_name}' exposed as a page tab")
        if tab is not None:
            reporter.check("click" in ap.action_names(tab), f"tab '{tab_name}' is activatable")

    for tab_name, must_have, min_named in TAB_CHECKS:
        reporter.section(f"Tab: {tab_name}")
        if not reporter.check(ap.select_tab(app, tab_name), f"navigate to tab '{tab_name}'"):
            continue
        names = named_nodes(app)
        reporter.check(
            len(names) >= min_named,
            f"{tab_name}: tree has >= {min_named} named nodes (got {len(names)})",
        )
        for expected in must_have:
            hit = any(expected == name or expected in name for name in names)
            if not hit:
                hit = ap.find_by_name(app, expected, timeout_s=4) is not None
            reporter.check(hit, f"{tab_name}: exposes '{expected}'")
        if tab_name == "A11y":
            exercise_a11y_tab(app, reporter)
        elif tab_name == "Complex":
            exercise_complex_tab(app, reporter)
        time.sleep(0.3)

    return reporter.exit_code()


if __name__ == "__main__":
    sys.exit(main())
