#!/usr/bin/env python3
"""Cross-reference logos.json version names against rendered dump text."""
import json, io, re, os
from collections import defaultdict

BASE = r"E:\FrameMark\work\ui-v2"
D = BASE + r"\ui\composeApp\src\commonMain\composeResources\files\data"
CL = BASE + r"\reports\ui-copy-audit-20261113\rendered_clusters.tsv"

rendered = {}
for line in io.open(CL, encoding='utf-8'):
    parts = line.rstrip('\n').split('\t')
    if len(parts) < 3 or not parts[0].isdigit():
        continue
    rendered[parts[2]] = int(parts[0])

data = json.load(io.open(os.path.join(D, 'logos.json'), encoding='utf-8'))
SUS = re.compile(r'(\d{3,4}x\d{3,4}|\bSVG\b|\bNEW\b|Officially|Officia|announcement|Laika|Loing|Caisi|Aolinbasi|Huawe |Xiao MI|Google Camera|Black\b|White\b|Colou?r\b|Logo\b|lockup|sans|serif|wordmark)', re.I)

print("=== suspicious version names that ARE rendered in dumps ===")
seen = set()
for v in data['versions']:
    if v.get('selected') is not True:
        continue
    n = v.get('name') or ''
    if not SUS.search(n):
        continue
    hits = [k for k in rendered if k == n or k.startswith(n + ' ·') or (n and n in k)]
    tag = 'RENDERED' if hits else 'not-in-dumps'
    if tag == 'RENDERED':
        for h in sorted(set(hits)):
            print(f"  [{tag}] {h!r}  (x{rendered[h]})")
    else:
        if n not in seen:
            seen.add(n)

print(f"\n=== suspicious names NOT seen in any dump: {len(seen)} ===")
for n in sorted(seen)[:60]:
    print("   ", repr(n))
