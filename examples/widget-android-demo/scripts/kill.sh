#!/bin/bash
# kill.sh COUNT: storm COUNT increments, kill -9 the app the moment it reports them done, then
# read the state back from disk in a fresh process.
A=~/Android/Sdk/platform-tools/adb
PKG=dev.nucleusframework.widgetandroiddemo
S="$A shell am start -n $PKG/.MainActivity"
$A logcat -b all -c
$S --es action storm --ei count $1 --ei workers 8 --ez finish true >/dev/null
for i in $(seq 1 600); do $A logcat -d -s WidgetDemo:I | grep -q "STORM done" && break; sleep 0.05; done
$A shell kill -9 $($A shell pidof $PKG)
DONE=$($A logcat -d -s WidgetDemo:I | grep -oE "STORM done [0-9]+ storm=[0-9]+" | grep -o "[0-9]*$")
sleep 1
$A logcat -b all -c
$S --es action state --ez finish true >/dev/null
for i in $(seq 1 50); do $A logcat -d -s WidgetDemo:I | grep -q STATE && break; sleep 0.2; done
AFTER=$($A logcat -d -s WidgetDemo:I | grep -o "storm=[0-9null]*" | head -1 | cut -d= -f2)
echo "reported=$DONE afterRestart=$AFTER $([ "$DONE" = "$AFTER" ] && echo OK || echo LOST)"
