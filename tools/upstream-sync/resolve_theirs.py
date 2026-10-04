#!/usr/bin/env python3
"""Resolve every conflict hunk in a file to the upstream ("theirs") side, then rebrand it.
Usage: resolve_theirs.py FILE OLD=NEW [OLD=NEW ...]   (substitutions apply to taken hunks only)"""
import re, sys

path, subs = sys.argv[1], [a.split('=', 1) for a in sys.argv[2:]]
src = open(path, encoding='utf-8').read()
pat = re.compile(r'^<<<<<<< [^\n]*\n(.*?)^=======\n(.*?)^>>>>>>> [^\n]*\n', re.S | re.M)

def take(m):
    theirs = m.group(2)
    for old, new in subs:
        theirs = theirs.replace(old, new)
    return theirs

out, n = pat.subn(take, src)
open(path, 'w', encoding='utf-8').write(out)
left = len(re.findall(r'^(<<<<<<<|=======|>>>>>>>)( |$)', out, re.M))
print(f'{path}: {n} hunk(s) -> upstream+rebrand, markers left: {left}')
