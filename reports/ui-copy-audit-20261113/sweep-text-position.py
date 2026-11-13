#!/usr/bin/env python3
"""Find string literals that sit in a UI text position (Text/BasicText/label/
contentDescription/title/placeholder...) and are ASCII-only, i.e. candidates for
'English internal name in a Chinese UI'."""
import os, re, io, json

ROOT = r"E:\FrameMark\work\ui-v2\ui\composeApp\src"
SETS = ["commonMain", "androidMain", "desktopMain"]
OUT = r"E:\FrameMark\work\ui-v2\reports\ui-copy-audit-20261113"

TEXT_POS = re.compile(
    r'(Text\s*\(|BasicText\s*\(|text\s*=\s*$|text\s*=\s*|label\s*=\s*|contentDescription\s*=\s*|'
    r'title\s*=\s*|subtitle\s*=\s*|placeholder\s*=\s*|hint\s*=\s*|note\s*=\s*|'
    r'\.semantics\s*\{|semanticText|CuratedCount\s*\(|CuratedNote\s*\(|CuratedSubNote\s*\()')

def strip_comments(src):
    out = []
    i = 0; n = len(src); state = None
    while i < n:
        c = src[i]; nxt = src[i+1] if i+1 < n else ''
        if state is None:
            if c == '/' and nxt == '/': state='line'; i+=2; continue
            if c == '/' and nxt == '*': state='block'; i+=2; continue
            if c == '"' and src.startswith('"""', i): state='raw'; out.append('"""'); i+=3; continue
            if c == '"': state='str'; out.append(c); i+=1; continue
            if c == "'": state='char'; i+=1; continue
            out.append(c); i+=1; continue
        if state == 'line':
            if c == '\n': state=None; out.append(c)
            i+=1; continue
        if state == 'block':
            if c == '*' and nxt == '/': state=None; i+=2; continue
            if c == '\n': out.append('\n')
            i+=1; continue
        if state in ('str','char'):
            q = '"' if state=='str' else "'"
            if c == '\\': out.append(src[i:i+2]); i+=2; continue
            if c == q: state=None; out.append(c); i+=1; continue
            out.append(c); i+=1; continue
        if state == 'raw':
            if src.startswith('"""', i): state=None; out.append('"""'); i+=3; continue
            out.append(c); i+=1; continue
    return ''.join(out)

STR = re.compile(r'"""((?:.|\n)*?)"""|"((?:[^"\\\n]|\\.)*)"')
CJK = re.compile(r'[\u3400-\u9fff]')

rows = []
for s in SETS:
    for dirpath, _, files in os.walk(os.path.join(ROOT, s)):
        for f in files:
            if not f.endswith('.kt'): continue
            p = os.path.join(dirpath, f)
            src = io.open(p, encoding='utf-8').read()
            masked = strip_comments(src)
            lines = masked.split('\n')
            for m in STR.finditer(masked):
                val = m.group(1) if m.group(1) is not None else (m.group(2) or '')
                if not val.strip() or CJK.search(val): continue
                if not re.search(r'[A-Za-z]', val): continue
                # look back up to 120 chars for a text-position marker
                head = masked[max(0, m.start()-120):m.start()]
                if not TEXT_POS.search(head):
                    continue
                line = masked.count('\n', 0, m.start()) + 1
                rows.append((os.path.relpath(p, ROOT).replace('\\','/'), line, val))

f = io.open(os.path.join(OUT, 'ascii-text-position.tsv'), 'w', encoding='utf-8')
f.write("file\tline\tliteral\n")
for r in rows:
    f.write(f"{r[0]}\t{r[1]}\t{r[2]}\n")
f.close()
print("ascii literals in text positions:", len(rows))
for r in rows:
    print(f"{r[0]}:{r[1]}\t{r[2][:100]!r}")
