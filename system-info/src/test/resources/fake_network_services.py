#!/usr/bin/python3
"""Fake xdg-desktop-portal NetworkMonitor / NetworkManager for LinuxConnectivityE2ETest.

Usage: fake_network_services.py portal3|portal2|nm
Serves on the bus named by DBUS_SESSION_BUS_ADDRESS (the test points the
system bus at the same private daemon). Prints "ready" once the name is owned,
then reads commands from stdin, one per line:

    set <available 0|1> <metered 0|1> <connectivity>   update state and signal it
    signal                                             signal without changing anything
"""
import sys
import warnings

from gi.repository import Gio, GLib

# register_object(vtable callbacks) is deprecated in PyGObject but still the simplest fake.
warnings.filterwarnings("ignore", category=DeprecationWarning)

MODE = sys.argv[1]

PORTAL_XML = """
<node><interface name="org.freedesktop.portal.NetworkMonitor">
  <method name="GetAvailable"><arg type="b" direction="out"/></method>
  <method name="GetMetered"><arg type="b" direction="out"/></method>
  <method name="GetConnectivity"><arg type="u" direction="out"/></method>
  %s
  <signal name="changed"/>
</interface></node>
""" % ('<method name="GetStatus"><arg type="a{sv}" direction="out"/></method>' if MODE == "portal3" else "")

NM_XML = """
<node><interface name="org.freedesktop.NetworkManager">
  <property name="State" type="u" access="read"/>
  <property name="Connectivity" type="u" access="read"/>
  <property name="Metered" type="u" access="read"/>
</interface></node>
"""

state = {"available": True, "metered": False, "connectivity": 4}
conn = None


def nm_props():
    # NMState 70 = CONNECTED_GLOBAL, 20 = DISCONNECTED; NMMetered 3 = GUESS_YES, 4 = GUESS_NO.
    return {
        "State": GLib.Variant("u", 70 if state["available"] else 20),
        "Connectivity": GLib.Variant("u", state["connectivity"]),
        "Metered": GLib.Variant("u", 3 if state["metered"] else 4),
    }


def portal_call(connection, sender, path, iface, method, params, invocation):
    if method == "GetStatus":
        invocation.return_value(GLib.Variant("(a{sv})", ({
            "available": GLib.Variant("b", state["available"]),
            "metered": GLib.Variant("b", state["metered"]),
            "connectivity": GLib.Variant("u", state["connectivity"]),
        },)))
    elif method == "GetAvailable":
        invocation.return_value(GLib.Variant("(b)", (state["available"],)))
    elif method == "GetMetered":
        invocation.return_value(GLib.Variant("(b)", (state["metered"],)))
    elif method == "GetConnectivity":
        invocation.return_value(GLib.Variant("(u)", (state["connectivity"],)))


def nm_get_property(connection, sender, path, iface, name):
    return nm_props()[name]


def emit():
    if MODE == "nm":
        conn.emit_signal(None, "/org/freedesktop/NetworkManager", "org.freedesktop.DBus.Properties",
                         "PropertiesChanged",
                         GLib.Variant("(sa{sv}as)", ("org.freedesktop.NetworkManager", nm_props(), [])))
    else:
        conn.emit_signal(None, "/org/freedesktop/portal/desktop", "org.freedesktop.portal.NetworkMonitor",
                         "changed", None)
    conn.flush_sync(None)


def on_stdin(channel, condition):
    line = channel.readline()
    if not line:
        loop.quit()
        return False
    parts = line.split()
    if parts and parts[0] == "set":
        state["available"] = parts[1] == "1"
        state["metered"] = parts[2] == "1"
        state["connectivity"] = int(parts[3])
    if parts:
        emit()
    print("ok", flush=True)
    return True


def on_bus(connection, name):
    global conn
    conn = connection
    if MODE == "nm":
        info = Gio.DBusNodeInfo.new_for_xml(NM_XML).interfaces[0]
        connection.register_object("/org/freedesktop/NetworkManager", info, None, nm_get_property, None)
    else:
        info = Gio.DBusNodeInfo.new_for_xml(PORTAL_XML).interfaces[0]
        connection.register_object("/org/freedesktop/portal/desktop", info, portal_call, None, None)


def on_name(connection, name):
    print("ready", flush=True)


bus_name = "org.freedesktop.NetworkManager" if MODE == "nm" else "org.freedesktop.portal.Desktop"
Gio.bus_own_name(Gio.BusType.SESSION, bus_name, Gio.BusNameOwnerFlags.NONE, on_bus, on_name, None)
GLib.io_add_watch(GLib.IOChannel.unix_new(sys.stdin.fileno()), GLib.IO_IN | GLib.IO_HUP, on_stdin)
loop = GLib.MainLoop()
loop.run()
