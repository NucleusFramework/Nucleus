"""Shared AT-SPI probing helpers for the Linux a11y verification suite.

The scripts in this directory talk to the running tao-demo through pyatspi —
the same client stack Orca and Accerciser use — so everything they assert is
observable by a real assistive technology.

Requires: at-spi2-core (registryd) running with
`org.a11y.Status.IsEnabled = true`, and python3-pyatspi.
"""

import shutil
import subprocess
import time

import pyatspi

DEFAULT_APP = "Sample Tao"
MAX_DEPTH = 16
MAX_NODES = 4000

# Roles a keyboard/screen-reader user can act on. Used by the golden probe's
# strict-extra mode and by the tab sweep.
INTERACTIVE_ROLES = frozenset(
    {
        "push button",
        "button",
        "toggle button",
        "check box",
        "radio button",
        "entry",
        "text",
        "slider",
        "combo box",
        "page tab",
        "link",
        "spin button",
        "list item",
    }
)

# Window-manager / title-bar chrome the demo draws itself. Never part of a
# content golden.
CHROME_NAMES = frozenset({"Close", "Maximize", "Minimize", "Restore", "Clear"})

# at-spi2-core renamed a few role strings; probes and goldens compare the
# canonical form so the same fixture works on an Ubuntu runner and on a
# rolling desktop. (ATSPI_ROLE_PUSH_BUTTON prints as "push button" on
# at-spi2-core <= 2.54 and as "button" on newer releases.)
ROLE_ALIASES = {"push button": "button"}


def canonical_role(role):
    return ROLE_ALIASES.get(role, role)


def state_names(node):
    """Sorted AT-SPI state names (e.g. 'enabled', 'focusable') for a node."""
    try:
        return sorted(
            pyatspi.STATE_VALUE_TO_NAME.get(s, str(s)) for s in node.getState().getStates()
        )
    except Exception:
        return []


def interface_names(node):
    """Short interface names, e.g. ['Accessible', 'Action', 'Component']."""
    try:
        return sorted(i.split(".")[-1] for i in node.get_interfaces())
    except Exception:
        return []


def action_names(node):
    try:
        action = node.queryAction()
        return [action.getName(i) for i in range(action.nActions)]
    except Exception:
        return []


def attributes(node):
    try:
        return dict(a.split(":", 1) for a in node.getAttributes() if ":" in a)
    except Exception:
        return {}


def role_name(node):
    try:
        return canonical_role(node.getRoleName())
    except Exception:
        return ""


def node_name(node):
    try:
        return node.name or ""
    except Exception:
        return ""


def extents(node):
    """Screen-space extents as (x, y, w, h), or None when unavailable."""
    try:
        component = node.queryComponent()
        rect = component.getExtents(pyatspi.DESKTOP_COORDS)
        return (rect.x, rect.y, rect.width, rect.height)
    except Exception:
        return None


def numeric_value(node):
    """(value, min, max) for Value-interface nodes, else None."""
    try:
        value = node.queryValue()
        return (value.currentValue, value.minimumValue, value.maximumValue)
    except Exception:
        return None


def text_value(node):
    """Full contents of the Text interface, or None when not text."""
    try:
        text = node.queryText()
        return text.getText(0, text.characterCount)
    except Exception:
        return None


def walk(node, depth=0, out=None):
    """Depth-first collection of (depth, node) pairs."""
    if out is None:
        out = []
    try:
        out.append((depth, node))
        if depth < MAX_DEPTH and len(out) < MAX_NODES:
            for i in range(node.childCount):
                child = node.getChildAtIndex(i)
                if child is not None:
                    walk(child, depth + 1, out)
    except Exception:
        pass
    return out


def find_app(app_name=DEFAULT_APP, timeout_s=0):
    """Locate the demo application on the AT-SPI desktop.

    Returns (app, [(depth, node)]). Retries until `timeout_s` elapses so the
    caller can tolerate a still-starting JVM.
    """
    deadline = time.time() + timeout_s
    while True:
        desktop = pyatspi.Registry.getDesktop(0)
        for i in range(desktop.childCount):
            app = desktop.getChildAtIndex(i)
            if app is None:
                continue
            try:
                if app.name != app_name:
                    continue
            except Exception:
                continue
            nodes = walk(app)
            if len(nodes) > 1:
                return app, nodes
        if time.time() >= deadline:
            return None, []
        time.sleep(1)


def nodes_of(app):
    return walk(app)


def find_by_name(app, name, role=None, timeout_s=8, exact=True):
    """Re-walk the tree until a node matches; AccessKit recycles nodes on
    every push, so cached references go stale."""
    deadline = time.time() + timeout_s
    while True:
        for _, node in walk(app):
            candidate = node_name(node)
            hit = candidate == name if exact else name in candidate
            if hit and (role is None or role_name(node) == role):
                return node
        if time.time() >= deadline:
            return None
        time.sleep(0.25)


def find_by_prefix(app, prefix, timeout_s=8):
    deadline = time.time() + timeout_s
    while True:
        for _, node in walk(app):
            if node_name(node).startswith(prefix):
                return node
        if time.time() >= deadline:
            return None
        time.sleep(0.25)


def wait_for(predicate, timeout_s=15, interval_s=0.5):
    """Poll `predicate` until it returns truthy. Returns the last result."""
    deadline = time.time() + timeout_s
    while True:
        result = predicate()
        if result:
            return result
        if time.time() >= deadline:
            return result
        time.sleep(interval_s)


def do_action(node, name=None):
    """Perform an AT-SPI action by name (default: the first one)."""
    try:
        action = node.queryAction()
    except Exception:
        return False
    if action.nActions == 0:
        return False
    index = 0
    if name is not None:
        names = [action.getName(i) for i in range(action.nActions)]
        if name not in names:
            return False
        index = names.index(name)
    try:
        action.doAction(index)
        return True
    except Exception:
        return False


def select_tab(app, tab_name, settle_s=1.0):
    """Activate a main navigation tab the way an AT would."""
    tab = find_by_name(app, tab_name, role="page tab", timeout_s=10)
    if tab is None:
        return False
    if not do_action(tab, "click"):
        return False
    time.sleep(settle_s)
    return True


def click_counter(app):
    """Current value of the A11y tab's 'click counter N' label, or -1."""
    node = find_by_prefix(app, "click counter ", timeout_s=3)
    if node is None:
        return -1
    try:
        return int(node_name(node).rsplit(" ", 1)[1])
    except (IndexError, ValueError):
        return -1


def activate_window(title="Tao Backend Demo", settle_s=1.0):
    """Give the demo window X input focus (xdotool).

    AccessKit only emits focus events while the toplevel is focused
    (`update_window_focus_state`), and keystrokes obviously need it too, so
    probes that assert either must raise the window first.
    """
    if shutil.which("xdotool") is None:
        return False
    ids = subprocess.run(
        ["xdotool", "search", "--name", title],
        capture_output=True,
        text=True,
        check=False,
    ).stdout.split()
    if not ids:
        return False
    window_id = ids[-1]
    subprocess.run(["xdotool", "windowactivate", "--sync", window_id], check=False)
    subprocess.run(["xdotool", "windowfocus", window_id], check=False)
    time.sleep(settle_s)
    return True


def send_keys(*keys, delay_ms=60):
    """Send real X keystrokes (xdotool key), e.g. send_keys('Tab', 'Return')."""
    if shutil.which("xdotool") is None:
        return False
    for key in keys:
        subprocess.run(["xdotool", "key", "--clearmodifiers", key], check=False)
        time.sleep(delay_ms / 1000)
    return True


def type_text(text, delay_ms=25):
    """Type a literal string through X (xdotool type)."""
    if shutil.which("xdotool") is None:
        return False
    subprocess.run(
        ["xdotool", "type", "--clearmodifiers", "--delay", str(delay_ms), text], check=False
    )
    time.sleep(0.3)
    return True


def focused_nodes(app):
    """Names of nodes currently carrying the AT-SPI 'focused' state."""
    return [
        node_name(node)
        for _, node in walk(app)
        if "focused" in state_names(node) and node_name(node)
    ]


class Reporter:
    """[PASS]/[FAIL] accounting shared by every probe."""

    def __init__(self, title):
        self.failures = []
        self.passes = 0
        print(f"== {title} ==")

    def check(self, condition, what):
        if condition:
            self.passes += 1
            print(f"  [PASS] {what}")
        else:
            self.failures.append(what)
            print(f"  [FAIL] {what}")
        return bool(condition)

    def section(self, title):
        print(f"\n-- {title} --")

    def exit_code(self):
        print(f"\n== {len(self.failures)} failure(s), {self.passes} pass(es) ==")
        for failure in self.failures:
            print(f"  - {failure}")
        return 1 if self.failures else 0
