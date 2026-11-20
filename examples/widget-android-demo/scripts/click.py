#!/usr/bin/env python3
"""Click fuzz: render SEED on the Fuzz widget, then tap every clickable view of the widget.
Nothing in the fuzz tree depends on the click counter, so no click may ever be dropped as stale."""
import subprocess, sys, re, time, os
ADB = [os.path.expanduser('~/Android/Sdk/platform-tools/adb')]
PKG = 'dev.nucleusframework.widgetandroiddemo'
LAUNCHER = 'com.google.android.apps.nexuslauncher'
def adb(*a): return subprocess.run(ADB + list(a), capture_output=True, text=True, errors='replace').stdout

def nodes():
    adb('shell', 'uiautomator', 'dump', '/sdcard/c.xml')
    x = adb('shell', 'cat', '/sdcard/c.xml')
    # The widget: the AppWidgetHostView subtree (its node class is the launcher's host view).
    out, inside, depth = [], False, 0
    for m in re.finditer(r'<node [^>]*?(/?)>|</node>', x):
        tag = m.group(0)
        if tag == '</node>':
            if inside:
                depth -= 1
                if depth == 0: inside = False
            continue
        selfclose = tag.endswith('/>')
        cls = re.search(r'class="([^"]*)"', tag).group(1)
        if not inside and 'AppWidgetHostView' in cls:
            inside, depth = True, (0 if selfclose else 1); continue
        if inside:
            if re.search(r'clickable="true"', tag):
                a, b, c, d = map(int, re.search(r'bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"', tag).groups())
                if c > a and d > b:
                    t = re.search(r' text="([^"]*)"', tag).group(1)
                    out.append(((a + c) // 2, (b + d) // 2, cls.split('.')[-1], t[:30]))
            if not selfclose: depth += 1
    return out

def main():
    seeds = [int(s) for s in sys.argv[1:]]
    total_bad = 0
    for seed in seeds:
        adb('logcat', '-b', 'all', '-c')
        adb('shell', 'am', 'start', '-n', f'{PKG}/.MainActivity', '--es', 'action', 'fuzz', '--el', 'seed', str(seed), '--ez', 'finish', 'true')
        time.sleep(2.0)
        adb('shell', 'input', 'keyevent', 'HOME'); time.sleep(1.0)
        targets = nodes()
        bad = []
        for (x, y, cls, t) in targets:
            adb('shell', 'input', 'tap', str(x), str(y))
            time.sleep(1.0)
            top = adb('shell', 'dumpsys', 'activity', 'activities')
            if 'topResumedActivity' in top and LAUNCHER not in re.search(r'topResumedActivity=.*', top).group(0):
                adb('shell', 'input', 'keyevent', 'HOME'); time.sleep(1.0)
        time.sleep(1.5)
        log = adb('logcat', '-d', '-s', 'WidgetDemo:I')
        invoked = len(re.findall(r'ActionInvoked', log))
        dropped = re.findall(r'ActionDropped\([^)]*\)', log)
        failed = re.findall(r'Failed\([^)]*', log)
        crash = adb('logcat', '-d', '-b', 'crash')
        if dropped: bad.append(f'DROPPED {dropped[:5]}')
        if failed: bad.append(f'FAILED {failed[:3]}')
        if 'Process:' in crash: bad.append('CRASH ' + ' | '.join(l for l in crash.splitlines() if 'Process:' in l or 'Exception' in l)[:400])
        total_bad += bool(bad)
        print(f'seed {seed}: {len(targets)} clickables, {invoked} actions invoked', 'OK' if not bad else 'FAIL', *bad, sep=' ', flush=True)
    print(f'{len(seeds) - total_bad}/{len(seeds)} seeds clean')
main()
