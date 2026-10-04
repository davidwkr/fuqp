#!/usr/bin/env python3
"""Convert every string resource with 2+ bare format specifiers (%s, %d, ...) to positional
ones (%1$s, %2$d, ...) across all locales. Crowdin keeps reintroducing bare specifiers, and aapt
warns on them because translations may need to reorder arguments.
Run from the repo root."""
import re, glob

SPEC = re.compile(r'%(?!%)(\d+\$)?[-#+ 0,(]*\d*(?:\.\d+)?[a-zA-Z]')
fixed = []
for path in sorted(glob.glob('app/src/main/res/values*/strings.xml')):
    src = open(path, encoding='utf-8').read()

    def per_string(m):
        specs = list(SPEC.finditer(m.group(3)))
        if len(specs) < 2 or any(s.group(1) for s in specs):
            return m.group(0)
        n = [0]
        def number(mm):
            n[0] += 1
            return '%%%d$%s' % (n[0], mm.group(0)[1:])
        fixed.append(f'{path.split("/")[-2]}:{m.group(2)}')
        return m.group(1) + SPEC.sub(number, m.group(3)) + m.group(4)

    out = re.sub(r'(<string\s+name="([^"]+)"[^>]*>)(.*?)(</string>)', per_string, src, flags=re.S)
    if out != src:
        open(path, 'w', encoding='utf-8').write(out)
print('converted to positional:', fixed or '(none)')
