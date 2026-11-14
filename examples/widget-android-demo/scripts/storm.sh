#!/bin/bash
# storm.sh COUNT WORKERS TAPS: app storm + sequential taps on the -2000 rows, then the oracles.
A=~/Android/Sdk/platform-tools/adb
S="$A shell am start -n dev.nucleusframework.widgetandroiddemo/.MainActivity"
$A logcat -b all -c
$S --es action storm --ei count $1 --ei workers $2 --ez finish true >/dev/null
for i in $(seq 1 $3); do $A shell input tap 540 $(( (245 + 66 * (i % 14)) * 12 / 10 )); done
for i in $(seq 1 90); do $A logcat -d -s WidgetDemo:I | grep -q "STORM done" && break; sleep 1; done
sleep 4
$A logcat -d -s WidgetDemo:I | grep -E "STORM|Dropped|Failed"
echo "invoked=$($A logcat -d -s WidgetDemo:I | grep -c ActionInvoked)"
$A logcat -d | grep -iE "ANR in|not responding" | head -3
$A logcat -b all -c
$S --es action state --ez finish true >/dev/null
sleep 1.5
$A logcat -d -s WidgetDemo:I | grep STATE
