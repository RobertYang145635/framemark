#!/usr/bin/env python3
"""Sweep 3 — rendered-text sweep over every device UI dump.

Extracts text= / content-desc= per <node>, keeps only nodes owned by this app's
packages (the system photo picker and SystemUI are other products), clusters the
distinct strings and flags the ones that read as internal / degenerate.
"""
import os, re, io, json, sys
from collections import Counter, defaultdict

ROOTS = [
    r"E:\FrameMark\execution-ui-v2-20261106\evidence",
]
OUT = r"E:\FrameMark\work\ui-v2\reports\ui-copy-audit-20261113"

APP_PKG = re.compile(r'^com\.framemark\.')
ATTR = re.compile(r'\b(text|content-desc|package)="((?:[^"\\]|\\.)*)"')
NODE = re.compile(r'<node\b[^>]*>')

def unescape(s):
    s = re.sub(r'&#x([0-9A-Fa-f]+);', lambda m: chr(int(m.group(1), 16)), s)
    s = re.sub(r'&#(\d+);', lambda m: chr(int(m.group(1))), s)
    return (s.replace('&lt;', '<').replace('&gt;', '>')
             .replace('&quot;', '"').replace('&apos;', "'").replace('&amp;', '&'))

dumps = []
for root in ROOTS:
    for dirpath, _, files in os.walk(root):
        for f in files:
            if f.lower().endswith('.xml'):
                dumps.append(os.path.join(dirpath, f))

occ = []                 # (dumprel, kind, text)
per = defaultdict(list)
skipped_foreign = Counter()

for p in sorted(dumps):
    try:
        src = io.open(p, encoding='utf-8', errors='replace').read()
    except Exception as e:
        print("ERR", p, e); continue
    rel = os.path.relpath(p, ROOTS[0]).replace('\\', '/')
    for m in NODE.finditer(src):
        tag = m.group(0)
        d = dict((k, unescape(v)) for k, v in ATTR.findall(tag))
        pkg = d.get('package', '')
        if pkg and not APP_PKG.match(pkg):
            skipped_foreign[pkg] += 1
            continue
        for kind in ('text', 'content-desc'):
            val = d.get(kind, '')
            if val.strip() == '':
                continue
            occ.append((rel, kind, val))
            per[val].append((rel, kind))

counter = Counter(v for _, _, v in occ)

CJK = re.compile(r'[\u3400-\u9fff]')
MARKER = re.compile(r'(TODO|FIXME|XXX|HACK|\bWIP\b|debug|Debug|测试|临时|占位|待办|样例|示例数据|mock|stub|dummy|placeholder|beta|实验)')
RAW_PARAM = re.compile(r'\b(photoCropX|photoCropY|stockOffsetXPct|stockOffsetYPct|pctOfLongEdge|fNumber|showLogo|slots|fontScale|marginPct|footerPct)\b')
ASSETKEY = re.compile(r'(^|\s)(ver:|fmlogo_)|(^\S*\.(png|jpe?g|webp|gif))$', re.I)
CARDID = re.compile(r'^(stock|hand|vendor|user)-[a-z0-9-]+$', re.I)
BRANDKEY = re.compile(r'^(laika|panasionic|hasu_long|hassel_wordmark|fushi_white|lumo_black)$', re.I)
ENGINE = re.compile(r'\b(D3D12|Ganesh|Skia|SkRenderer|JNI|null|undefined|NaN)\b')
FMTAG = re.compile(r'\bFM_[A-Z0-9_]+\b')
STATUSWORDS = re.compile(r'(_merge|__|NEEDS_REVIEW|CANDIDATE|LEGACY|REVIEW|REJECTED|APPROVED|n\.d\.)')
PATHLIKE = re.compile(r'([A-Za-z]:\\|/data/|/storage/|\.\./)')
DUP_PUNCT = re.compile(r'([，。、；：！？,.;:!?·])\1|[，。、；：！？,.;:!?·]$')
DANGLING = re.compile(r'·\s*$|^\s*·|·\s*[—\-]\s*$')
HUMAN_NUM = re.compile(r'^(?:卡片|人物|品牌|模型|样式|版本)?\s*\d+$')
LEADING_DOT = re.compile(r'^\s*·\s*')

def classify(v):
    tags = []
    s = v.strip()
    if MARKER.search(v): tags.append('MARKER')
    if RAW_PARAM.search(v): tags.append('RAW_PARAM')
    if CARDID.match(s): tags.append('CARD_ID')
    if BRANDKEY.match(s): tags.append('BRAND_KEY')
    if ASSETKEY.search(v): tags.append('FILE_NAME')
    if ENGINE.search(v): tags.append('ENGINE')
    if FMTAG.search(v): tags.append('FM_TAG')
    if STATUSWORDS.search(v): tags.append('STATUS_WORD')
    if PATHLIKE.search(v): tags.append('PATH')
    if DANGLING.search(v): tags.append('DANGLING_SEP')
    if DUP_PUNCT.search(LEADING_DOT.sub('', s)): tags.append('PUNCT')
    if re.match(r'^[-+±]?\d+([.,:]\d+)?%?$', s) and not HUMAN_NUM.match(s): tags.append('BARE_NUM')
    if not CJK.search(v):
        if re.match(r'^[A-Za-z][A-Za-z0-9_-]*$', s): tags.append('LATIN_TOKEN')
        if re.search(r'[A-Za-z]', v) and re.search(r'[\u3400-\u9fff]', v): tags.append('MIXED')
    # duplicated adjacent token, e.g. "OPPO · OPPO 相机"
    m = re.match(r'^(\S+)\s*·\s*(.+)$', s)
    if m:
        a, b = m.group(1), m.group(2)
        if b.lower().startswith(a.lower()) and len(a) >= 2: tags.append('DUP_LABEL')
    return tags

tagged = {v: classify(v) for v in counter}

oc = io.open(os.path.join(OUT, 'rendered-occurrences.tsv'), 'w', encoding='utf-8')
oc.write("dump\tkind\ttext\n")
for rel, kind, val in occ:
    oc.write(f"{rel}\t{kind}\t{val}\n")
oc.close()

cf = io.open(os.path.join(OUT, 'rendered-strings.tsv'), 'w', encoding='utf-8')
cf.write("count\tlen\ttext\ttags\n")
for v, n in sorted(counter.items(), key=lambda kv: (-kv[1], kv[0])):
    cf.write(f"{n}\t{len(v)}\t{v.replace(chr(9),' ')}\t{','.join(tagged[v])}\n")
cf.close()

print("dumps:", len(dumps))
print("app nodes with text/desc:", len(occ), "distinct:", len(counter))
print("skipped foreign packages:", dict(skipped_foreign))
print("\n===== flagged =====")
for v, n in sorted(counter.items(), key=lambda kv: (-kv[1], kv[0])):
    if tagged[v]:
        print(f"{n:5d}\t{','.join(tagged[v]):30s}\t{v[:110]!r}")
