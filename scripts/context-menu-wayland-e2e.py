#!/usr/bin/python3
"""Compositor-driven E2E for the Linux context menu flyout on native Wayland.

Boots a nested `gnome-shell --headless` (Mutter, the compositor the bug
reports come from), launches `ContextMenuE2EMainKt` on it, drives a real
pointer through `org.gnome.Mutter.RemoteDesktop`, and reads the result back
from `org.gnome.Shell.Screenshot` captures plus the fixture's own stdout log.

Scenarios (each prints PASS/FAIL, exit code is the number of failures):
  latency   first frame of the menu within LATENCY_BUDGET_MS of the press
  once      the menu shows once per right click (no show/hide/show flicker)
  bottom    a menu opened near the bottom of a window sitting at the bottom of
            the screen is fully visible (flipped or slid on screen)
  repeat    open / dismiss / open / dismiss / open all show a menu
  reopen    three right clicks in a row each move the menu

Prerequisites: `./gradlew :nucleus-application:contextMenuE2EClasspath`,
GNOME Shell with --headless (Ubuntu 26.04), python3-gi, Pillow.
"""
import json
import os
import signal
import subprocess
import sys
import tempfile
import threading
import time

import gi

gi.require_version("Gio", "2.0")
gi.require_version("GLib", "2.0")
from gi.repository import Gio, GLib  # noqa: E402
from PIL import Image  # noqa: E402

REPO = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
CLASSPATH_FILE = os.path.join(REPO, "nucleus-application/build/e2e/context-menu-classpath.txt")
MAIN_CLASS = "dev.nucleusframework.application.contextmenu.ContextMenuE2EMainKt"
JAVA = os.environ.get("NUCLEUS_E2E_JAVA", "/usr/lib/jvm/java-17-openjdk-amd64/bin/java")
WAYLAND_NAME = "nucleus-cm-e2e"
MONITOR_W, MONITOR_H = 1600, 1000
WINDOW_W, WINDOW_H = 900, 600
TITLE = "context-menu-e2e"
EDGE_INSET_PX = 10  # rounded corners and anti-aliased frame edges are not menu pixels
LATENCY_BUDGET_MS = 250
LATENCY_SAMPLES = 4
BTN_LEFT, BTN_RIGHT = 0x110, 0x111
WORK = tempfile.mkdtemp(prefix="nucleus-cm-e2e-")


def log(msg):
    print(f"[driver {time.strftime('%H:%M:%S')}] {msg}", flush=True)


# ── nested shell ─────────────────────────────────────────────────────────────

def start_shell():
    bus_file = os.path.join(WORK, "bus")
    shell_log = open(os.path.join(WORK, "shell.log"), "w")
    cmd = [
        "dbus-run-session", "--", "sh", "-c",
        f"echo $DBUS_SESSION_BUS_ADDRESS > {bus_file}; exec gnome-shell --headless "
        f"--virtual-monitor {MONITOR_W}x{MONITOR_H} --wayland-display={WAYLAND_NAME} --unsafe-mode",
    ]
    env = dict(os.environ)
    env.pop("WAYLAND_DISPLAY", None)
    env.pop("DISPLAY", None)
    socket = os.path.join(os.environ["XDG_RUNTIME_DIR"], WAYLAND_NAME)
    # A previous run killed mid-way leaves the socket and its lock behind, and
    # Mutter then refuses to create its own.
    for stale in (socket, socket + ".lock"):
        if os.path.exists(stale):
            os.remove(stale)
    # Own process group: dbus-run-session does not forward SIGTERM to the shell.
    proc = subprocess.Popen(cmd, stdout=shell_log, stderr=subprocess.STDOUT, env=env, start_new_session=True)
    deadline = time.time() + 40
    while time.time() < deadline:
        if os.path.exists(socket) and os.path.exists(bus_file) and os.path.getsize(bus_file) > 0:
            break
        if proc.poll() is not None:
            raise SystemExit(f"gnome-shell exited early, see {shell_log.name}")
        time.sleep(0.2)
    else:
        raise SystemExit("gnome-shell headless did not come up")
    address = open(bus_file).read().strip()
    # The Shell registers its D-Bus names a little after the socket appears.
    bus = None
    while time.time() < deadline:
        try:
            bus = Gio.DBusConnection.new_for_address_sync(
                address,
                Gio.DBusConnectionFlags.AUTHENTICATION_CLIENT | Gio.DBusConnectionFlags.MESSAGE_BUS_CONNECTION,
                None, None,
            )
            bus.call_sync("org.gnome.Shell", "/org/gnome/Shell", "org.gnome.Shell", "Eval",
                          GLib.Variant("(s)", ("1",)), None, Gio.DBusCallFlags.NONE, 5000, None)
            break
        except GLib.Error:
            time.sleep(0.5)
    else:
        raise SystemExit("org.gnome.Shell never answered")
    log(f"nested shell up: WAYLAND_DISPLAY={WAYLAND_NAME} bus={address}")
    return proc, bus, address


class Shell:
    def __init__(self, bus):
        self.bus = bus

    def call(self, dest, path, iface, method, params=None, timeout=10000):
        return self.bus.call_sync(dest, path, iface, method, params, None, Gio.DBusCallFlags.NONE, timeout, None)

    def eval(self, js):
        ok, result = self.call("org.gnome.Shell", "/org/gnome/Shell", "org.gnome.Shell", "Eval",
                               GLib.Variant("(s)", (js,))).unpack()
        if not ok:
            raise RuntimeError(f"Eval failed: {result}")
        # Eval JSON-encodes its result; a JS expression that already returned a
        # JSON string therefore comes back double-encoded.
        value = json.loads(result) if result else None
        if isinstance(value, str):
            try:
                value = json.loads(value)
            except ValueError:
                pass
        return value

    def windows(self):
        return self.eval(
            "JSON.stringify(global.get_window_actors().map(a => { const w = a.meta_window; "
            "const r = w.get_frame_rect(); const b = w.get_buffer_rect(); "
            "return {title: w.get_title(), type: w.get_window_type(), "
            "x: r.x, y: r.y, w: r.width, h: r.height, bx: b.x, by: b.y, bw: b.width, bh: b.height}; }))"
        )

    def find_window(self, title, timeout=60):
        deadline = time.time() + timeout
        while time.time() < deadline:
            for w in self.windows() or []:
                if w["title"] == title and w["w"] > 1:
                    return w
            time.sleep(0.25)
        raise SystemExit(f"window {title!r} never appeared; windows={self.windows()}")

    def move_window(self, title, x, y):
        self.eval(
            "(() => { const w = global.get_window_actors().map(a => a.meta_window)"
            f".find(w => w.get_title() === {json.dumps(title)}); w.move_frame(true, {x}, {y}); return 'ok'; }})()"
        )

    def screenshot(self, path):
        ok, used = self.call("org.gnome.Shell.Screenshot", "/org/gnome/Shell/Screenshot",
                             "org.gnome.Shell.Screenshot", "Screenshot",
                             GLib.Variant("(bbs)", (False, False, path))).unpack()
        if not ok:
            raise RuntimeError("screenshot failed")
        return Image.open(used).convert("RGB")


class Pointer:
    """org.gnome.Mutter.RemoteDesktop pointer: the only injection Mutter accepts on Wayland."""

    def __init__(self, shell):
        self.shell = shell
        rd = "org.gnome.Mutter.RemoteDesktop"
        sc = "org.gnome.Mutter.ScreenCast"
        (self.session,) = shell.call(rd, "/org/gnome/Mutter/RemoteDesktop", rd, "CreateSession").unpack()
        (session_id,) = shell.call(rd, self.session, "org.freedesktop.DBus.Properties", "Get",
                                   GLib.Variant("(ss)", (rd + ".Session", "SessionId"))).unpack()
        (sc_session,) = shell.call(
            sc, "/org/gnome/Mutter/ScreenCast", sc, "CreateSession",
            GLib.Variant("(a{sv})", ({"remote-desktop-session-id": GLib.Variant("s", session_id)},)),
        ).unpack()
        shell.call(rd, self.session, rd + ".Session", "Start")
        (self.stream,) = shell.call(
            sc, sc_session, sc + ".Session", "RecordMonitor",
            GLib.Variant("(sa{sv})", ("Meta-0", {"cursor-mode": GLib.Variant("u", 1)})),
        ).unpack()
        self.rd = rd
        log(f"remote desktop session {self.session} stream {self.stream}")

    def move(self, x, y):
        self.shell.call(self.rd, self.session, self.rd + ".Session", "NotifyPointerMotionAbsolute",
                        GLib.Variant("(sdd)", (self.stream, float(x), float(y))))

    def button(self, code, pressed):
        self.shell.call(self.rd, self.session, self.rd + ".Session", "NotifyPointerButton",
                        GLib.Variant("(ib)", (code, pressed)))

    def click(self, x, y, code=BTN_LEFT, hold_ms=60):
        self.move(x, y)
        time.sleep(0.05)
        self.button(code, True)
        time.sleep(hold_ms / 1000)
        self.button(code, False)


# ── the app under test ───────────────────────────────────────────────────────

class App:
    def __init__(self, wayland, bus_address):
        classpath = open(CLASSPATH_FILE).read().strip()
        env = dict(os.environ)
        env.update({
            "WAYLAND_DISPLAY": wayland,
            "GDK_BACKEND": "wayland",
            "DBUS_SESSION_BUS_ADDRESS": bus_address,
            "NUCLEUS_E2E_WINDOW_W": str(WINDOW_W),
            "NUCLEUS_E2E_WINDOW_H": str(WINDOW_H),
        })
        env.pop("DISPLAY", None)
        if os.environ.get("E2E_WAYLAND_DEBUG"):
            env["WAYLAND_DEBUG"] = "1"
        self.lines = []
        self.log_path = os.path.join(WORK, "app.log")
        self.proc = subprocess.Popen([JAVA, "-cp", classpath, MAIN_CLASS], env=env,
                                     stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True)
        self.start = time.time()
        threading.Thread(target=self._pump, daemon=True).start()

    def _pump(self):
        with open(self.log_path, "w") as out:
            for line in self.proc.stdout:
                self.lines.append((time.time(), line.rstrip()))
                out.write(line)
                out.flush()

    def since(self, t):
        return [l for (ts, l) in self.lines if ts >= t and l.startswith("[e2e")]

    def stop(self):
        self.proc.terminate()
        try:
            self.proc.wait(10)
        except subprocess.TimeoutExpired:
            self.proc.kill()


# ── pixel analysis ───────────────────────────────────────────────────────────

TEXT_FIELD_DP = (20, 20, 420, 60)  # the fixture's white text field, window-relative


def menu_bbox(img, region, win=None):
    """Bounding box of non-green pixels inside region=(x0,y0,x1,y1), or None.

    The fixture's text field is white too; its rectangle is skipped.
    """
    x0, y0, x1, y1 = region
    skip = None
    if win is not None:
        fx0, fy0, fx1, fy1 = TEXT_FIELD_DP
        skip = (win["x"] + fx0 - 2, win["y"] + fy0 - 2, win["x"] + fx1 + 2, win["y"] + fy1 + 2)
    crop = img.crop((x0, y0, x1, y1))
    px = crop.load()
    xs, ys = [], []
    w, h = crop.size
    for y in range(0, h, 2):
        for x in range(0, w, 2):
            if skip and skip[0] <= x0 + x < skip[2] and skip[1] <= y0 + y < skip[3]:
                continue
            r, g, b = px[x, y]
            if abs(r) > 70 or abs(255 - g) > 70 or abs(b) > 70:
                xs.append(x)
                ys.append(y)
    if len(xs) < 40:  # a few stray pixels are not a menu
        return None
    return (x0 + min(xs), y0 + min(ys), x0 + max(xs) + 1, y0 + max(ys) + 1)


def menu_bbox_win(img, region, win):
    return menu_bbox(img, region, win)


def content_region(win, to_screen_bottom=False):
    x0, y0 = win["x"] + EDGE_INSET_PX, win["y"] + EDGE_INSET_PX
    x1 = win["x"] + win["w"] - EDGE_INSET_PX
    y1 = MONITOR_H if to_screen_bottom else win["y"] + win["h"] - EDGE_INSET_PX
    return (x0, y0, x1, y1)


def observe(shell, region, win, seconds, period=0.04):
    """Samples screenshots for `seconds`; returns [(t_rel_ms, bbox or None)]."""
    samples = []
    start = time.time()
    n = 0
    while time.time() - start < seconds:
        path = os.path.join(WORK, f"shot-{int(start)}-{n}.png")
        n += 1
        img = shell.screenshot(path)
        samples.append((int((time.time() - start) * 1000), menu_bbox(img, region, win), path))
        time.sleep(period)
    return samples


def app_ms(lines, needle):
    """Timestamp (ms, app clock) of the first fixture line containing needle."""
    for line in lines:
        if needle in line:
            return int(line.split("]")[0].split(" ")[1])
    return None


# ── scenarios ────────────────────────────────────────────────────────────────

class Report:
    def __init__(self):
        self.failures = 0

    def check(self, name, ok, detail):
        print(f"{'PASS' if ok else 'FAIL'} {name}: {detail}", flush=True)
        if not ok:
            self.failures += 1


def run():
    shell_proc, bus, address = start_shell()
    shell = Shell(bus)
    app = App(WAYLAND_NAME, address)
    report = Report()
    try:
        win = shell.find_window(TITLE)
        log(f"window: {win}")
        pointer = Pointer(shell)
        # Wake the app's input path and make sure the window is focused/active.
        cx, cy = win["x"] + win["w"] // 2, win["y"] + win["h"] // 2
        pointer.click(cx, cy)
        time.sleep(0.5)
        t0 = time.time()
        pointer.click(cx, cy)
        time.sleep(0.5)
        report.check("input reaches the window", any("pointer Press" in l for l in app.since(t0)),
                     f"log={app.since(t0)}")

        # ── latency + once, window in the middle of the screen ──────────────
        shell.move_window(TITLE, (MONITOR_W - win["w"]) // 2, (MONITOR_H - win["h"]) // 2)
        time.sleep(0.6)
        win = shell.find_window(TITLE)
        region = content_region(win)
        cx, cy = win["x"] + win["w"] // 2, win["y"] + win["h"] // 2
        # Latency over several menus, from the app's own trace: screenshots are
        # heavy enough to starve the compositor's frame callbacks, so measuring
        # the first menu while sampling pixels measures the driver, not the app.
        latencies = []
        for _ in range(LATENCY_SAMPLES):
            t = time.time()
            pointer.click(cx, cy, BTN_RIGHT)
            time.sleep(0.6)
            trace = app.since(t)
            press = app_ms(trace, "pointer Press")
            present = app_ms(trace, "first present") or app_ms(trace, "menu OPEN")
            latencies.append((present - press) if (press is not None and present is not None) else None)
            pointer.click(win["x"] + 40, win["y"] + win["h"] - 40)
            time.sleep(0.5)
        report.check("latency", all(v is not None and v <= LATENCY_BUDGET_MS for v in latencies),
                     f"press→first present per menu: {latencies} ms (budget {LATENCY_BUDGET_MS})")
        stalls = [l for l in app.since(t0) if "frame stalled" in l]
        report.check("no frame stall while opening a menu", not stalls, f"stalls={stalls[:6]}")

        pointer.move(cx, cy)
        time.sleep(0.1)
        t_press = time.time()
        pointer.button(BTN_RIGHT, True)
        time.sleep(0.05)
        pointer.button(BTN_RIGHT, False)
        samples = observe(shell, region, win, 1.6)
        visible = [(t, b) for (t, b, _) in samples]
        first = next((t for (t, b) in visible if b), None)
        report.check("visible on screen after the press", first is not None,
                     f"first screenshot with the menu at {first} ms; trace:\n    " + "\n    ".join(app.since(t_press)))
        # show / hide / show within the window is the double display.
        pattern = []
        for (_, b) in visible:
            v = bool(b)
            if not pattern or pattern[-1] != v:
                pattern.append(v)
        report.check("once", pattern.count(True) <= 1 and (not pattern or pattern[-1] is True),
                     f"visibility pattern={pattern} log={app.since(t_press)}")
        ref = next((b for (_, b) in reversed(visible) if b), None)
        ref_h = (ref[3] - ref[1]) if ref else None
        log(f"reference menu bbox={ref} height={ref_h}")
        # dismiss with a left click far from the menu
        t_dismiss = time.time()
        pointer.click(win["x"] + 40, win["y"] + win["h"] - 40)
        time.sleep(0.5)
        gone = menu_bbox_win(shell.screenshot(os.path.join(WORK, "after-dismiss.png")), region, win) is None
        report.check("dismiss on outside click", gone, f"log={app.since(t_dismiss)}")

        # ── bottom: window flush with the screen bottom, click near its bottom ─
        shell.move_window(TITLE, (MONITOR_W - win["w"]) // 2, MONITOR_H - win["h"])
        time.sleep(0.6)
        win = shell.find_window(TITLE)
        log(f"window at bottom: {win}")
        region = content_region(win, to_screen_bottom=True)
        bx, by = win["x"] + win["w"] // 2, min(win["y"] + win["h"] - 30, MONITOR_H - 30)
        t_press = time.time()
        pointer.click(bx, by, BTN_RIGHT)
        time.sleep(0.8)
        shot = shell.screenshot(os.path.join(WORK, "bottom.png"))
        bbox = menu_bbox(shot, region, win)
        ok = bbox is not None and bbox[3] < MONITOR_H - 1 and (ref_h is None or abs((bbox[3] - bbox[1]) - ref_h) <= 4)
        report.check("bottom", ok,
                     f"menu bbox={bbox} reference height={ref_h} screen height={MONITOR_H} "
                     f"click=({bx},{by}) log={app.since(t_press)}")
        pointer.click(win["x"] + 40, win["y"] + 80)
        time.sleep(0.5)

        # ── repeat: open / dismiss ×3 in the middle ─────────────────────────
        shell.move_window(TITLE, (MONITOR_W - win["w"]) // 2, (MONITOR_H - win["h"]) // 2)
        time.sleep(0.6)
        win = shell.find_window(TITLE)
        region = content_region(win)
        cx, cy = win["x"] + win["w"] // 2, win["y"] + win["h"] // 2
        for i in range(3):
            t_press = time.time()
            pointer.click(cx, cy, BTN_RIGHT)
            time.sleep(0.7)
            bbox = menu_bbox_win(shell.screenshot(os.path.join(WORK, f"repeat-{i}.png")), region, win)
            report.check(f"repeat #{i + 1} shows", bbox is not None, f"bbox={bbox} log={app.since(t_press)}")
            t_dismiss = time.time()
            pointer.click(win["x"] + 40, win["y"] + win["h"] - 40)
            time.sleep(0.6)
            bbox = menu_bbox_win(shell.screenshot(os.path.join(WORK, f"repeat-{i}-closed.png")), region, win)
            report.check(f"repeat #{i + 1} dismisses", bbox is None, f"bbox={bbox} log={app.since(t_dismiss)}")

        # ── reopen: three right clicks in a row, no dismiss in between ─────
        points = [(cx - 200, cy - 100), (cx + 100, cy), (cx - 50, cy + 120)]
        for i, (px, py) in enumerate(points):
            t_press = time.time()
            pointer.click(px, py, BTN_RIGHT)
            time.sleep(0.7)
            bbox = menu_bbox_win(shell.screenshot(os.path.join(WORK, f"reopen-{i}.png")), region, win)
            near = bbox is not None and abs(bbox[0] - px) < 40 and abs(bbox[1] - py) < 40
            report.check(f"reopen #{i + 1} shows at the click", near,
                         f"click=({px},{py}) bbox={bbox} log={app.since(t_press)}")
        pointer.click(win["x"] + 40, win["y"] + win["h"] - 40)
        time.sleep(0.4)

        # ── textfield: the text context menu path, as in the demo ───────────
        tx, ty = win["x"] + 200, win["y"] + 40
        t_press = time.time()
        pointer.click(tx, ty, BTN_RIGHT)
        time.sleep(0.8)
        bbox = menu_bbox_win(shell.screenshot(os.path.join(WORK, "textfield.png")), region, win)
        report.check("textfield shows", bbox is not None, f"bbox={bbox} log={app.since(t_press)}")
        pointer.click(win["x"] + 40, win["y"] + win["h"] - 40)
        time.sleep(0.6)
        bbox = menu_bbox_win(shell.screenshot(os.path.join(WORK, "textfield-closed.png")), region, win)
        report.check("textfield dismisses", bbox is None, f"bbox={bbox}")

        # ── hold: a right click held longer than the menu takes to appear ───
        for i in range(2):
            t_press = time.time()
            pointer.click(cx, cy, BTN_RIGHT, hold_ms=350)
            time.sleep(0.6)
            trace = app.since(t_press)
            bbox = menu_bbox_win(shell.screenshot(os.path.join(WORK, f"hold-{i}.png")), region, win)
            released = any("pointer Release" in l for l in trace)
            report.check(f"hold #{i + 1} shows and the window sees the release", bbox is not None and released,
                         f"bbox={bbox} log={trace}")
            pointer.click(win["x"] + 40, win["y"] + win["h"] - 40)
            time.sleep(0.6)
    finally:
        log(f"artifacts in {WORK}")
        app.stop()
        os.killpg(shell_proc.pid, signal.SIGTERM)
        try:
            shell_proc.wait(10)
        except subprocess.TimeoutExpired:
            os.killpg(shell_proc.pid, signal.SIGKILL)
    print(f"failures={report.failures}", flush=True)
    return report.failures


if __name__ == "__main__":
    sys.exit(min(run(), 100))
