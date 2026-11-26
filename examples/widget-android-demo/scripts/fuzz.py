#!/usr/bin/env python3
"""Seed sweep: render each seed on the pinned Fuzz widget and compare the launcher's view tree
with the spec's oracle. Usage: fuzz.py FIRST COUNT [settle_seconds]"""
import subprocess, sys, re, time, collections
ADB = [__import__('os').path.expanduser('~/Android/Sdk/platform-tools/adb')]
def adb(*a, check=False):
    return subprocess.run(ADB + list(a), capture_output=True, text=True, errors='replace').stdout
PKG = 'dev.nucleusframework.widgetandroiddemo'

def widget_counts():
    out = adb('shell', 'dumpsys', 'activity', 'top')
    lines = out.splitlines()
    trees = []
    for i, l in enumerate(lines):
        if 'AppWidgetHostView{' in l:
            ind = len(l) - len(l.lstrip())
            c = collections.Counter()
            for m in lines[i+1:]:
                mi = len(m) - len(m.lstrip())
                if mi <= ind or not m.strip(): break
                cm = re.match(r'\s*android\.widget\.(\w+)\{', m)
                if cm: c[cm.group(1)] += 1
            trees.append(c)
    return trees

import os
NOTREE = os.environ.get('NOTREE') == '1'
def main():
    first, count = int(sys.argv[1]), int(sys.argv[2])
    settle = float(sys.argv[3]) if len(sys.argv) > 3 else 1.2
    fails = []
    for seed in range(first, first + count):
        adb('logcat', '-b', 'all', '-c')
        adb('shell', 'am', 'start', '-n', f'{PKG}/.MainActivity', '--es', 'action', 'fuzz', '--el', 'seed', str(seed), '--ez', 'finish', 'true')
        t0 = time.time(); line = None
        while time.time() - t0 < 40:
            log = adb('logcat', '-d', '-s', 'WidgetDemo:I', 'NucleusWidgets:*', 'AppWidgetHostView:*', 'AndroidRuntime:E')
            m = re.search(rf'FUZZ seed={seed} depth=(\d+) invalid=(\w+) expect=\{{(.*?)\}} errors=\[(.*)\]', log)
            if m: line = m; break
            time.sleep(0.3)
        elapsed = time.time() - t0
        if not line:
            fails.append((seed, 'no FUZZ line (timeout)')); print(seed, 'TIMEOUT'); continue
        time.sleep(settle)
        log = adb('logcat', '-d')
        expect = collections.Counter({k: int(v) for k, v in (kv.split('=') for kv in line.group(3).split(', ') if kv)})
        trees = widget_counts()
        problems = []
        errs = line.group(4)
        invalid = line.group(2) == 'true'
        deep = 'views deep' in errs
        rest = re.sub(r'HostError\([^)]*views deep[^)]*\)', '', errs).strip(', ')
        if invalid:
            if 'maxLines must be positive' not in rest: problems.append('invalid spec rendered without failing')
            rest = ''
            deep = True  # the widget keeps its last render: no tree check
        if rest: problems.append('errors: ' + rest[:300])
        crash = adb('logcat', '-d', '-b', 'crash')
        if 'FATAL' in crash or 'Process:' in crash:
            problems.append('CRASH: ' + ' | '.join(l.strip() for l in crash.splitlines() if 'Process:' in l or 'Exception' in l)[:600])
            open(f'/tmp/widget-fuzz-crash-{seed}.txt', 'w').write(crash)
        for pat in ['Error inflating', 'ANR in']:
            for l in log.splitlines():
                if re.search(pat, l) and (PKG in l or 'WidgetDemo' in l or 'AppWidgetHostView' in l or 'NucleusWidgets' in l):
                    problems.append(l.strip()[:300])
        if NOTREE or deep:
            pass
        elif len(trees) != 1:
            problems.append(f'{len(trees)} widget trees')
        else:
            got = collections.Counter({k: v for k, v in trees[0].items() if k in ('LinearLayout','TextView','Button','ImageView','Switch','RadioButton')})
            if got != expect:
                problems.append(f'counts got={dict(got)} expect={dict(expect)}')
        status = ('OK' if not problems else 'FAIL') + (' (too deep: fallback)' if deep else '')
        print(f'{seed} depth={line.group(1)} {elapsed:.1f}s {status}', *problems[:4], sep='\n   ' if problems else ' ')
        if problems: fails.append((seed, problems))
    print(f'\n{count - len(fails)}/{count} OK; failing seeds: {[s for s, _ in fails]}')
main()
