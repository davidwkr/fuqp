#!/usr/bin/env python3
"""Bring any vector-drawable pathData over aapt2's 32767-byte string-pool limit back under it,
losslessly. Over the limit, aapt2 silently writes the literal STRING_TOO_LARGE and the drawable
renders as garbage. Upstream's alt launcher icon (ic_launcher_alt_4 since the 2026-10 sync,
ic_launcher_alt_5 before) ships one path at 33835 bytes.

Two transforms, neither of which changes a coordinate:
  1. drop zero-length `v0` / `h0` segments (they neither draw nor move the pen; only safe on
     fill-only paths, so stroked paths are refused)
  2. drop redundant leading zeros: `0.5` -> `.5`, `-0.5` -> `-.5`
The traced point sequence is compared before and after; any difference aborts the write.
Run from the repo root; pass files, or nothing to scan app/src/main/res.
"""
import glob, re, sys

LIMIT = 32767
TOK = re.compile(r'([MmLlHhVvCcSsQqTtAaZz])([^MmLlHhVvCcSsQqTtAaZz]*)')
NUM = re.compile(r'-?\d*\.?\d+(?:[eE][-+]?\d+)?')
PATH_EL = re.compile(r'<path\b[^>]*?android:pathData\s*=\s*"([^"]*)"[^>]*?/?>', re.S)


def trace(d):
    """Absolute points in drawing order; zero-length line segments are dropped on both sides,
    since removing them is exactly the claim being checked."""
    x = y = sx = sy = 0.0
    pts = []

    def add(kind, px, py):
        p = (kind, round(px, 6), round(py, 6))
        if kind == 'l' and pts and pts[-1][1:] == p[1:]:
            return
        pts.append(p)

    for c, arg in TOK.findall(d):
        n = [float(v) for v in NUM.findall(arg)]
        if c in 'Mm':
            for i in range(0, len(n) - 1, 2):
                x, y = (n[i], n[i + 1]) if c == 'M' else (x + n[i], y + n[i + 1])
                if i == 0:
                    sx, sy = x, y
                add('m', x, y)
        elif c in 'Ll':
            for i in range(0, len(n) - 1, 2):
                x, y = (n[i], n[i + 1]) if c == 'L' else (x + n[i], y + n[i + 1])
                add('l', x, y)
        elif c in 'Hh':
            for v in n:
                x = v if c == 'H' else x + v
                add('l', x, y)
        elif c in 'Vv':
            for v in n:
                y = v if c == 'V' else y + v
                add('l', x, y)
        elif c in 'Cc':
            for i in range(0, len(n) - 5, 6):
                p = n[i:i + 6]
                if c == 'c':
                    p = [p[0] + x, p[1] + y, p[2] + x, p[3] + y, p[4] + x, p[5] + y]
                add('c', p[0], p[1]); add('c', p[2], p[3]); add('c', p[4], p[5])
                x, y = p[4], p[5]
        elif c in 'Zz':
            add('z', sx, sy)
            x, y = sx, sy
        else:
            raise SystemExit(f'unhandled path command {c!r}; not touching this file')
    return pts


def shrink(d):
    prev = None
    while prev != d:
        prev = d
        d = re.sub(r'[vh]0(?=[A-Za-z])', '', d)
    return re.sub(r'(?<![\d.])0\.', '.', d)


files = sys.argv[1:] or glob.glob('app/src/main/res/**/*.xml', recursive=True)
for path in files:
    src = open(path, encoding='utf-8').read()
    out, changed = src, False
    for m in reversed(list(PATH_EL.finditer(src))):
        d = m.group(1)
        if len(d.encode()) <= LIMIT:
            continue
        if 'strokeColor' in m.group(0) or 'strokeWidth' in m.group(0):
            raise SystemExit(f'{path}: oversized path is stroked; v0 removal is not safe there')
        new = shrink(d)
        if trace(d) != trace(new):
            raise SystemExit(f'{path}: geometry changed, refusing to write')
        if len(new.encode()) > LIMIT:
            raise SystemExit(f'{path}: still {len(new)} bytes after lossless shrink')
        a, b = m.start(1), m.end(1)
        out = out[:a] + new + out[b:]
        changed = True
        print(f'{path}: {len(d)} -> {len(new)} bytes (margin {LIMIT - len(new)}), '
              f'{len(trace(d))} points identical')
    if changed:
        open(path, 'w', encoding='utf-8').write(out)
