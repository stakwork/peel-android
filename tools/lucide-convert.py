#!/usr/bin/env python3
"""Builds app/src/main/assets/lucide/icons.json from an unpacked `npm pack lucide-static` (docs/phases/37-lucide-icons.md).
usage: lucide-convert.py <package dir> <out icons.json> [LucideNames.kt]
lucide-static also ships a file per old alias (`home`, `bar-chart-3`); with LucideNames.kt given, the names its ALIASES table renames
are left out, so they resolve through the table to the current name, as on iOS.
Format: {"<name>": [prim...] | "@<other name>"} ; prim = ["p",d] ["c",cx,cy,r,filled] ["r",x,y,w,h,rx,ry]
["l",x1,y1,x2,y2] ["e",cx,cy,rx,ry] ["pl",points] ["pg",points]. An "@name" string is an icon identical to an earlier one (an alias)."""
import re, glob, json, os, sys
pkg, out = sys.argv[1], sys.argv[2]
renamed = set(re.findall(r'"([a-z0-9-]+)" to "[a-z0-9-]+"', open(sys.argv[3]).read())) if len(sys.argv) > 3 else set()
def num(v): 
    f = float(v); return int(f) if f == int(f) else f
icons = {}; seen = {}
for f in sorted(glob.glob(pkg + '/icons/*.svg')):
    name = os.path.basename(f)[:-4]
    if name in renamed: continue
    prims = []
    for m in re.finditer(r'<(\w+)\s([^>]*?)/?>', open(f).read()):
        tag = m.group(1)
        if tag == 'svg': continue
        a = dict(re.findall(r'([\w-]+)="([^"]*)"', m.group(2)))
        g = lambda k, d=0: num(a.get(k, d))
        if tag == 'path': prims.append(['p', a['d']])
        elif tag == 'circle': prims.append(['c', g('cx'), g('cy'), g('r'), 1 if a.get('fill') == 'currentColor' else 0])
        elif tag == 'rect': prims.append(['r', g('x'), g('y'), g('width'), g('height'), g('rx'), g('ry', a.get('rx', 0))])
        elif tag == 'line': prims.append(['l', g('x1'), g('y1'), g('x2'), g('y2')])
        elif tag == 'ellipse': prims.append(['e', g('cx'), g('cy'), g('rx'), g('ry')])
        elif tag == 'polyline': prims.append(['pl', a['points']])
        elif tag == 'polygon': prims.append(['pg', a['points']])
        else: raise SystemExit('unknown ' + tag)
    key = json.dumps(prims, separators=(',', ':'))
    if key in seen: icons[name] = '@' + seen[key]
    else: seen[key] = name; icons[name] = prims
json.dump(icons, open(out, 'w'), separators=(',', ':'))
print(len(icons), os.path.getsize(out))
