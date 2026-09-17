#!/usr/bin/env python3
"""Exact expected-vs-measured AT-SPI tree verification for tao-demo.

Linux counterpart of scripts/ci/verify-uia-tree-golden.ps1.

Method:
  1. EXPECTED = scripts/ci/a11y-goldens/<fixture>.atspi.json
     (source of truth catalog: name, role, states, interfaces, actions,
      attributes, optional value/text constraints)
  2. MEASURED = live pyatspi walk of the projected AT-SPI tree
  3. DIFF     = field-by-field match; exit non-zero on any MISSING / mismatch

Also writes, with --out-dir:
  - <fixture>.measured.json — the actual tree
  - <fixture>.diff.txt      — human-readable failure summary

Usage:
  python3 scripts/ci/verify-atspi-tree-golden.py --fixture a11y-tab
  python3 scripts/ci/verify-atspi-tree-golden.py --fixture complex-tab --strict-extra
  python3 scripts/ci/verify-atspi-tree-golden.py --dump-only --out-dir /tmp/a11y

Exit 0 = every expected node matches the measured properties.
"""

import argparse
import json
import os
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

import atspi_probe as ap  # noqa: E402


def measure(depth, node):
    value = ap.numeric_value(node)
    rect = ap.extents(node)
    return {
        "depth": depth,
        "name": ap.node_name(node),
        "role": ap.role_name(node),
        "states": ap.state_names(node),
        "interfaces": ap.interface_names(node),
        "actions": ap.action_names(node),
        "attributes": ap.attributes(node),
        "text": ap.text_value(node),
        "value": None if value is None else value[0],
        "valueMin": None if value is None else value[1],
        "valueMax": None if value is None else value[2],
        "extents": None if rect is None else {"x": rect[0], "y": rect[1], "w": rect[2], "h": rect[3]},
        "extentsValid": bool(rect and rect[2] > 0 and rect[3] > 0),
    }


def name_matches(measured_name, spec):
    mode = spec.get("nameMatch", "exact")
    expected = spec.get("name", "")
    actual = measured_name or ""
    if mode == "exact":
        return actual == expected
    if mode == "prefix":
        return actual.startswith(expected)
    if mode == "contains":
        return expected in actual
    if mode == "any":
        return True
    raise ValueError(f"unknown nameMatch '{mode}'")


def find_match(measured, spec):
    candidates = [
        m
        for m in measured
        if (not spec.get("role") or m["role"] == ap.canonical_role(spec["role"]))
        and name_matches(m["name"], spec)
    ]
    if not candidates:
        return None
    # Best match = fewest property mismatches, then on-screen. Several nodes can
    # share a name or role (Compose keeps off-screen copies of scrolled-away
    # rows, and unnamed entries are told apart only by their states), so the
    # diff itself is the discriminator.
    return min(candidates, key=lambda m: (len(diff_node(spec, m)), not m["extentsValid"], m["depth"]))


def diff_node(spec, m):
    """Returns the list of property mismatches for one expected node."""
    problems = []
    if spec.get("role") and m["role"] != ap.canonical_role(spec["role"]):
        problems.append(f"role expected={spec['role']} actual={m['role']}")
    for state in spec.get("statesAll", []):
        if state not in m["states"]:
            problems.append(f"missing state {state} (actual=[{','.join(m['states'])}])")
    for state in spec.get("statesNone", []):
        if state in m["states"]:
            problems.append(f"forbidden state {state} present")
    for iface in spec.get("interfacesAll", []):
        if iface not in m["interfaces"]:
            problems.append(f"missing interface {iface} (actual=[{','.join(m['interfaces'])}])")
    for iface in spec.get("interfacesNone", []):
        if iface in m["interfaces"]:
            problems.append(f"forbidden interface {iface} present")
    for action in spec.get("actionsAll", []):
        if action not in m["actions"]:
            problems.append(f"missing action '{action}' (actual={m['actions']})")
    if spec.get("actionsExact") is not None and m["actions"] != spec["actionsExact"]:
        problems.append(f"actions expected={spec['actionsExact']} actual={m['actions']}")
    for key, expected in spec.get("attributes", {}).items():
        actual = m["attributes"].get(key)
        if actual != expected:
            problems.append(f"attribute {key} expected={expected} actual={actual}")
    for key in ("value", "valueMin", "valueMax"):
        if spec.get(key) is None:
            continue
        actual = m[key]
        if actual is None or abs(float(actual) - float(spec[key])) > spec.get("valueTolerance", 0.001):
            problems.append(f"{key} expected={spec[key]} actual={actual}")
    if spec.get("text") is not None and m["text"] != spec["text"]:
        problems.append(f"text expected='{spec['text']}' actual='{m['text']}'")
    if spec.get("requireValidExtents") and not m["extentsValid"]:
        problems.append(f"extents invalid: {m['extents']}")
    return problems


def check_strict_extra(reporter, expected, measured):
    """Fail on interactive nodes the golden doesn't know about (drift guard)."""
    reporter.section("Strict extra (unlisted interactive)")
    specs = list(expected.get("nodes", [])) + list(expected.get("tabs", []))
    extras = 0
    for m in measured:
        if not m["name"] or m["role"] not in ap.INTERACTIVE_ROLES:
            continue
        if m["name"] in ap.CHROME_NAMES:
            continue
        # Reorder arrows surface as single-glyph names without a stable string.
        if len(m["name"]) <= 2:
            continue
        covered = any(
            (not spec.get("role") or m["role"] == ap.canonical_role(spec["role"]))
            and name_matches(m["name"], spec)
            for spec in specs
        )
        if not covered:
            extras += 1
            reporter.check(False, f"EXTRA unlisted interactive '{m['name']}' role={m['role']}")
    if extras == 0:
        reporter.check(True, "no unlisted interactive nodes")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--fixture", default="a11y-tab")
    parser.add_argument("--goldens-dir", default="")
    parser.add_argument("--out-dir", default="")
    parser.add_argument("--app", default=ap.DEFAULT_APP)
    parser.add_argument("--timeout", type=int, default=int(os.environ.get("ATSPI_TIMEOUT_S", "300")))
    parser.add_argument("--dump-only", action="store_true")
    parser.add_argument("--strict-extra", action="store_true")
    args = parser.parse_args()

    goldens_dir = args.goldens_dir or os.path.join(os.path.dirname(os.path.abspath(__file__)), "a11y-goldens")
    fixture_path = os.path.join(goldens_dir, f"{args.fixture}.atspi.json")
    expected = {}
    if not args.dump_only:
        if not os.path.exists(fixture_path):
            print(f"expected fixture not found: {fixture_path}")
            return 1
        with open(fixture_path, encoding="utf-8") as handle:
            expected = json.load(handle)

    reporter = ap.Reporter("AT-SPI tree golden verification")
    if expected:
        print(f"fixture: {fixture_path}")
        print(f"tab: {expected.get('tab')}  app: {expected.get('appName', args.app)}")

    app, _ = ap.find_app(expected.get("appName", args.app), timeout_s=args.timeout)
    if app is None:
        print(f"  [FAIL] AT-SPI application '{args.app}' not found")
        return 1

    if expected.get("tab") and not ap.select_tab(app, expected["tab"]):
        print(f"  [FAIL] cannot select tab '{expected['tab']}'")
        return 1

    measured = [measure(depth, node) for depth, node in ap.nodes_of(app)]
    print(f"measured nodes: {len(measured)}")

    if args.out_dir:
        os.makedirs(args.out_dir, exist_ok=True)
        measured_path = os.path.join(args.out_dir, f"{args.fixture}.measured.json")
        with open(measured_path, "w", encoding="utf-8") as handle:
            json.dump(measured, handle, indent=2, ensure_ascii=False)
        print(f"wrote measured snapshot: {measured_path}")

    if args.dump_only:
        print("dump-only: skipping diff")
        return 0

    reporter.section("Tab bar (expected vs measured)")
    for spec in expected.get("tabs", []):
        m = find_match(measured, spec)
        if m is None:
            reporter.check(False, f"TAB missing name='{spec['name']}' role={spec.get('role')}")
            continue
        problems = diff_node(spec, m)
        if problems:
            for problem in problems:
                reporter.check(False, f"TAB '{spec['name']}': {problem}")
        else:
            reporter.check(True, f"TAB '{spec['name']}' role={m['role']} actions={m['actions']}")

    reporter.section("Content nodes (expected vs measured)")
    for spec in expected.get("nodes", []):
        label = spec.get("id") or spec.get("name")
        m = find_match(measured, spec)
        if m is None:
            reporter.check(False, f"MISSING id={label} name='{spec.get('name')}' role={spec.get('role')}")
            continue
        problems = diff_node(spec, m)
        if problems:
            for problem in problems:
                reporter.check(False, f"{label}: {problem}")
        else:
            reporter.check(
                True,
                f"{label}: name='{m['name']}' role={m['role']} "
                f"states=[{','.join(s for s in m['states'] if s in ('enabled', 'focusable', 'checked', 'invalid entry'))}] "
                f"ifs=[{','.join(m['interfaces'])}]",
            )

    if args.strict_extra:
        check_strict_extra(reporter, expected, measured)

    code = reporter.exit_code()
    if args.out_dir:
        diff_path = os.path.join(args.out_dir, f"{args.fixture}.diff.txt")
        with open(diff_path, "w", encoding="utf-8") as handle:
            handle.write(f"EXPECTED vs MEASURED — {args.fixture}\n")
            handle.write(f"expected: {fixture_path}\nmeasured nodes: {len(measured)}\n")
            handle.write(f"passes: {reporter.passes}  failures: {len(reporter.failures)}\n\n")
            for failure in reporter.failures:
                handle.write(f"FAIL: {failure}\n")
        print(f"wrote diff: {diff_path}")
    return code


if __name__ == "__main__":
    sys.exit(main())
