#!/bin/bash
# ui.sh find TEXT  -> prints center of first node whose text or content-desc equals TEXT; ui.sh tap TEXT -> taps it
A=~/Android/Sdk/platform-tools/adb
$A shell uiautomator dump /sdcard/ui.xml >/dev/null 2>&1
xy=$($A shell cat /sdcard/ui.xml | python3 -c '
import sys,re
t=sys.argv[1]; x=sys.stdin.read()
for m in re.finditer(r"<node [^>]*>", x):
    n=m.group(0)
    tx=re.search(r" text=\"([^\"]*)\"",n).group(1); cd=re.search(r"content-desc=\"([^\"]*)\"",n).group(1)
    if t in (tx,cd):
        a,b,c,d=map(int,re.search(r"bounds=\"\[(\d+),(\d+)\]\[(\d+),(\d+)\]",n).groups()); print((a+c)//2,(b+d)//2); break
' "$2")
[ -z "$xy" ] && { echo "NOT FOUND: $2"; exit 1; }
echo "$2 @ $xy"; [ "$1" = tap ] && $A shell input tap $xy
exit 0
