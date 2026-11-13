#!/usr/bin/env python3
"""Sweep composeResources/files/data/*.json for user-visible label fields whose
VALUES are developer strings (review status, ids, asset names, paths)."""
import json, io, os, re, sys

D = r"E:\FrameMark\work\ui-v2\ui\composeApp\src\commonMain\composeResources\files\data"
OUT = r"E:\FrameMark\work\ui-v2\reports\ui-copy-audit-20261113"

TEXT_KEYS = {
    'label','name','cn','title','subtitle','note','badge','group','era','text','desc',
    'description','summary','hint','caption','family','vendor','category','series',
    'template','kind','style','tag','tags','displayName','alias','brand','maker',
    'model','years','surface','status','review','comment','remark','reason','judgement',
}
CJK = re.compile(r'[\u3400-\u9fff]')
SUSPECT = re.compile(
    r'(NEEDS_REVIEW|CANDIDATE|LEGACY|REVIEW|REJECTED|APPROVED|TODO|FIXME|XXX|HACK|'
    r'_merge|__|\bn\.d\.|不建议|占位|临时|测试|待办|样例|示例数据|debug|Debug|'
    r'mock|stub|dummy|placeholder|beta|实验|ver:|fmlogo_|\.webp|\.png|\.jpe?g|'
    r'[A-Za-z]:\\|/files/|^[a-z0-9_]+-[a-z0-9-]+$)')

hits = []
counts = {}

def walk(node, path, key_context):
    if isinstance(node, dict):
        for k, v in node.items():
            walk(v, path + [k], k)
    elif isinstance(node, list):
        for i, v in enumerate(node):
            walk(v, path + [f'[{i}]'], key_context)
    else:
        if not isinstance(node, str):
            return
        key = key_context or ''
        if key not in TEXT_KEYS:
            return
        v = node
        if not v.strip():
            if key in ('label','name','title','cn','years'):
                hits.append(('EMPTY', '/'.join(path), key, v))
            return
        if SUSPECT.search(v):
            hits.append(('SUSPECT', '/'.join(path), key, v))
        elif not CJK.search(v) and key in ('label','title','cn','name','note','subtitle','badge','family','series','template'):
            hits.append(('ASCII_LABEL', '/'.join(path), key, v))
        counts[(key, v)] = counts.get((key, v), 0) + 1

for f in sorted(os.listdir(D)):
    if not f.endswith('.json'):
        continue
    data = json.load(io.open(os.path.join(D, f), encoding='utf-8'))
    start = len(hits)
    walk(data, [f], None)
    print(f"{f}: {len(hits)-start} raw hits")

f = io.open(os.path.join(OUT, 'resource-hits.tsv'), 'w', encoding='utf-8')
f.write("kind\tpath\tkey\tvalue\n")
for kind, path, key, val in hits:
    f.write(f"{kind}\t{path}\t{key}\t{val}\n")
f.close()

from collections import Counter
print("\n===== kinds =====")
print(Counter(h[0] for h in hits))

print("\n===== SUSPECT =====")
for kind, path, key, val in hits:
    if kind == 'SUSPECT':
        print(f"{path}\t{key}={val!r}")

print("\n===== ASCII_LABEL (distinct) =====")
seen = set()
for kind, path, key, val in hits:
    if kind == 'ASCII_LABEL' and (key, val) not in seen:
        seen.add((key, val))
        print(f"{key}={val!r}\te.g. {path}")

print("\n===== EMPTY =====")
c = Counter((h[2]) for h in hits if h[0] == 'EMPTY')
print(c)
for kind, path, key, val in hits:
    if kind == 'EMPTY':
        print(f"{path}\t{key}=''")
