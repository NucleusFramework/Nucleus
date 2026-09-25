#!/usr/bin/env python3
"""Fake org.freedesktop.portal.Screenshot for tests. Mode is read from $PORTAL_MODE_FILE on every call."""
import os, random, struct, sys, urllib.parse, zlib
import dbus, dbus.service, dbus.lowlevel
from dbus.mainloop.glib import DBusGMainLoop
from gi.repository import GLib

MODE_FILE = os.environ["PORTAL_MODE_FILE"]
SHOTS = os.environ["PORTAL_DIR"]
W, H = 640, 480

def pattern(x, y):
    return (x & 255, y & 255, ((x >> 8) * 64 + (y >> 8) * 16 + ((x + y) & 15)) & 255)

def png(path, alpha):
    raw = bytearray()
    for y in range(H):
        raw.append(0)
        for x in range(W):
            raw.extend(pattern(x, y))
            if alpha:
                raw.append(128)
    def chunk(t, d):
        c = struct.pack(">I", len(d)) + t + d
        return c + struct.pack(">I", zlib.crc32(t + d) & 0xffffffff)
    data = b"\x89PNG\r\n\x1a\n" + chunk(b"IHDR", struct.pack(">IIBBBBB", W, H, 8, 6 if alpha else 2, 0, 0, 0))
    data += chunk(b"IDAT", zlib.compress(bytes(raw), 1)) + chunk(b"IEND", b"")
    with open(path, "wb") as f:
        f.write(data)

def uri(path):
    return "file://" + urllib.parse.quote(path)

class Portal(dbus.service.Object):
    def __init__(self, bus):
        super().__init__(bus, "/org/freedesktop/portal/desktop")
        self.bus = bus

    def respond(self, path, code, results):
        msg = dbus.lowlevel.SignalMessage(path, "org.freedesktop.portal.Request", "Response")
        msg.append(dbus.UInt32(code), dbus.Dictionary(results, signature="sv"), signature="ua{sv}")
        self.bus.send_message(msg)
        return False

    @dbus.service.method("org.freedesktop.portal.Screenshot", in_signature="sa{sv}", out_signature="o",
                         sender_keyword="sender")
    def Screenshot(self, parent, options, sender):
        mode = open(MODE_FILE).read().strip()
        token = str(options.get("handle_token", "t%d" % random.randint(0, 1 << 30)))
        if mode == "ignore_token":
            token = "portal%d" % random.randint(0, 1 << 30)
        path = "/org/freedesktop/portal/desktop/request/%s/%s" % (sender[1:].replace(".", "_"), token)
        if mode == "dbus_error":
            raise dbus.exceptions.DBusException("boom", name="org.freedesktop.portal.Error.Failed")
        if mode == "access_denied":
            raise dbus.exceptions.DBusException("denied", name="org.freedesktop.DBus.Error.AccessDenied")
        name = "Screenshot-%d.png" % random.randint(0, 1 << 30)
        delay = 0
        results = {}
        code = 0
        if mode in ("ok", "ignore_token", "early") or mode.startswith("delay:"):
            f = os.path.join(SHOTS, name); png(f, False); results = {"uri": uri(f)}
            if mode.startswith("delay:"):
                delay = int(mode.split(":")[1])
        elif mode == "ok_rgba_spaces":
            f = os.path.join(SHOTS, "Scr een é %25 " + name); png(f, True); results = {"uri": uri(f)}
        elif mode == "symlink":
            t = os.path.join(SHOTS, "target.png"); png(t, False)
            l = os.path.join(SHOTS, "link-%d.bin" % random.randint(0, 1 << 30))
            os.symlink(t, l); results = {"uri": uri(l)}
        elif mode == "cancel":
            code = 1
        elif mode == "error":
            code = 2
        elif mode == "bogus_uri":
            results = {"uri": "http://example.com/x.png"}
        elif mode == "missing_file":
            results = {"uri": uri(os.path.join(SHOTS, "nope.png"))}
        elif mode == "not_png":
            f = os.path.join(SHOTS, "garbage-%d.bin" % random.randint(0, 1 << 30))
            open(f, "wb").write(os.urandom(4096)); results = {"uri": uri(f)}
        elif mode == "no_uri":
            results = {}
        elif mode == "wrong_path":
            f = os.path.join(SHOTS, name + ".bin"); png(f, False); results = {"uri": uri(f)}
            path_sig = "/org/freedesktop/portal/desktop/request/other/xyz"
            GLib.idle_add(self.respond, path_sig, 0, results)
            return dbus.ObjectPath(path)
        elif mode == "silent":
            return dbus.ObjectPath(path)
        if mode == "early":
            self.respond(path, code, results)
        elif delay:
            GLib.timeout_add(delay, self.respond, path, code, results)
        else:
            GLib.idle_add(self.respond, path, code, results)
        return dbus.ObjectPath(path)

DBusGMainLoop(set_as_default=True)
bus = dbus.SessionBus()
name = dbus.service.BusName("org.freedesktop.portal.Desktop", bus)
portal = Portal(bus)
print("fake portal ready", flush=True)
GLib.MainLoop().run()
