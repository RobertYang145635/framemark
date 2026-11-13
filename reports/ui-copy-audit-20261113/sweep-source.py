#!/usr/bin/env python3
"""Extract Kotlin string literals from production UI sources (comments stripped)
and classify user-visible candidates for the UI copy audit."""
import os, re, sys, json, io

ROOT = r"E:\FrameMark\work\ui-v2\ui\composeApp\src"
SETS = ["commonMain", "androidMain", "desktopMain"]

def strip_comments(src):
    """Remove // and /* */ comments and string-safe mask, keeping positions."""
    out = []
    i = 0
    n = len(src)
    state = None  # None, 'line', 'block', 'str', 'raw', 'char'
    while i < n:
        c = src[i]
        nxt = src[i+1] if i+1 < n else ''
        if state is None:
            if c == '/' and nxt == '/':
                state = 'line'; i += 2; continue
            if c == '/' and nxt == '*':
                state = 'block'; i += 2; continue
            if c == '"' and src.startswith('"""', i):
                state = 'raw'; out.append('"""'); i += 3; continue
            if c == '"':
                state = 'str'; out.append(c); i += 1; continue
            if c == "'":
                state = 'char'; i += 1; continue
            out.append(c); i += 1; continue
        if state == 'line':
            if c == '\n':
                state = None; out.append(c)
            i += 1; continue
        if state == 'block':
            if c == '*' and nxt == '/':
                state = None; i += 2; continue
            if c == '\n':
                out.append('\n')
            i += 1; continue
        if state in ('str', 'char'):
            q = '"' if state == 'str' else "'"
            if c == '\\':
                out.append(src[i:i+2]); i += 2; continue
            if c == q:
                state = None; out.append(c); i += 1; continue
            out.append(c); i += 1; continue
        if state == 'raw':
            if src.startswith('"""', i):
                state = None; out.append('"""'); i += 3; continue
            out.append(c); i += 1; continue
    return ''.join(out)

STR_RE = re.compile(r'"""((?:.|\n)*?)"""|"((?:[^"\\\n]|\\.)*)"')

def literals(masked):
    res = []
    for m in STR_RE.finditer(masked):
        if m.group(1) is not None:
            val = m.group(1)
        else:
            val = m.group(2) or ''
            if '$' in val:  # interpolation: keep but mark
                pass
        line = masked.count('\n', 0, m.start()) + 1
        res.append((line, val))
    return res

CJK = re.compile(r'[\u3000-\u9fff\uff00-\uffef]')
MARKERS = re.compile(r'(TODO|FIXME|XXX|HACK|\bWIP\b|\bdebug\b|Debug|\bdebug\b|测试|临时|占位|待办|样例|示例数据|mock|stub|dummy|placeholder|\bbeta\b|实验)')
IDENT_TOKENS = re.compile(
    r'(photoCropX|stockOffsetXPct|pctOfLongEdge|fNumber|showLogo|anchor\b|ver:|fmlogo_|\.webp|\.png|\.jpg|'
    r'a6-photo|stock-|hand-|laika|panasionic|D3D12|Ganesh|Skia|FM_[A-Z0-9_]+|\bnull\b|\bundefined\b|\bNaN\b)')
QA_HOOKS = re.compile(r'(LaunchOptions|fmCard|fmPhoto|fmOpenExport|evidence|Evidence|测试)')

rows = []
for s in SETS:
    base = os.path.join(ROOT, s)
    for dirpath, _, files in os.walk(base):
        for f in files:
            if not f.endswith('.kt'):
                continue
            p = os.path.join(dirpath, f)
            src = io.open(p, encoding='utf-8').read()
            masked = strip_comments(src)
            for line, val in literals(masked):
                if not val.strip():
                    continue
                tags = []
                if CJK.search(val): tags.append('CJK')
                if MARKERS.search(val): tags.append('MARKER')
                if IDENT_TOKENS.search(val): tags.append('IDENT')
                if QA_HOOKS.search(val): tags.append('QA')
                rows.append(dict(file=os.path.relpath(p, ROOT).replace('\\', '/'),
                                 line=line, text=val, tags=tags))

# Write full inventory
outp = r"E:\FrameMark\work\ui-v2\reports\ui-copy-audit-20261113\source_literals.json"
json.dump(rows, io.open(outp, 'w', encoding='utf-8'), ensure_ascii=False, indent=1)

print("total literals:", len(rows))
from collections import Counter
c = Counter()
for r in rows:
    for t in r['tags']:
        c[t] += 1
print("tagged:", dict(c))

# print MARKER / IDENT / QA hits with context
print("\n===== MARKER hits =====")
for r in rows:
    if 'MARKER' in r['tags']:
        print(f"{r['file']}:{r['line']}\t{r['text'][:160]!r}")
print("\n===== IDENT hits =====")
for r in rows:
    if 'IDENT' in r['tags']:
        print(f"{r['file']}:{r['line']}\t{r['text'][:160]!r}")
print("\n===== QA hits =====")
for r in rows:
    if 'QA' in r['tags']:
        print(f"{r['file']}:{r['line']}\t{r['text'][:160]!r}")
