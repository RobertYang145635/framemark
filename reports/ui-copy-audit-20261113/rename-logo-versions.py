#!/usr/bin/env python3
"""Rename the ingestion artefacts in the logo-version `name` field.

UI copy audit 2026-11-13, item 1. The `name` field is shown as the version title in
every logo picker, and it still carried review-sheet residue: bare stems (`Canon 4`),
tone suffixes that the 黑标/白标 chip already says (`Agfa Black`), a resolution
(`... lockup 700x253`), a file format (`Nokia NEW Logo SVG`), pinyin/truncation
(`OLYMPUS Aolinbasi`, `HUAWEI Huawe Black`, `Xiaomi Loing`) and generic `Logo`/`wordmark`
words in a Chinese UI.

Only `versions[].name` changes. `id`, `brandKey`, `assetKey`, `file`, `resolvedPath`,
`sha256`, `status`, `era`, `years` and every other field are untouched, so the persisted
render keys and the engine's asset lookup are unaffected.

Both copies (`ui/.../composeResources/files/data/logos.json` and `engine/data/logos.json`)
are written with the same bytes; `InspectorDataRealDataTest` asserts they stay identical.
"""
import json, io, re, sys, os

ROOT = r"E:\FrameMark\work\ui-v2"
TARGETS = [
    r"ui\composeApp\src\commonMain\composeResources\files\data\logos.json",
    r"engine\data\logos.json",
]

# --- explicit renames: version id -> new name -----------------------------------
# Hand-authored for the names that are not a mechanical pattern; every entry is
# listed before/after in the audit report.
EXPLICIT = {
    # Laika = 徕卡 in the source stems; the id keeps `laika`.
    "laika-legacy-2": "Leica 徕卡字样 2",
    "laika-legacy-long": "Leica 徕卡字样长标",
    # resolution + marketing suffix, and a file format + the word NEW
    "sigma-2025-lockup": "SIGMA 官方 VI 发布字标",
    "nokia-new-logo-svg": "Nokia 新字标",
    # typos / pinyin / truncation
    "xiaomi-legacy-loing": "Xiaomi 字标",
    "xiao-mi": "Xiaomi 小米字标",
    "aolinbasi-legacy": "OLYMPUS 奥林巴斯字标",
    "aolinbasi-legacy-black": "OLYMPUS 奥林巴斯黑白字标",
    "caisi-legacy": "ZEISS 蔡司字标",
    "binde-legacy-black": "PENTAX 宾得黑色款",
    "yijia-legacy-black": "OnePlus 一加黑色款",
    "yijia-legacy-red": "OnePlus 一加红标",
    "huawei-legacy-huawe": "HUAWEI 黑色款",
    "phaseone-legacy-black": "PHASE ONE 黑白字标",
    "oneplus-wordmark-4": "OnePlus 字标 4",
    "alpa-14940": "ALPA 字标",
    "agfacolour": "Agfa 彩色字标",
    "iqoo-gap": "iQOO 黑色款",
    "nubia-legacy-black": "nubia 黑色款",
    "lumo-legacy-black": "LUMO 黑色款",
    # a generic English noun inside the title
    "tamron-logo": "Tamron 字标",
    "phase-one-logo": "PHASE ONE 字标",
    "panasonic-logo-blue": "Panasonic 蓝色字标",
    "panasonic-1": "Panasonic 黑色字标",
    "casio-logo": "CASIO 黑色字标",
    "vivitar-1": "Vivitar 黑色字标",
    "logo-wiko-white": "WIKO 黑色字标",
    "samsung-8": "SAMSUNG 黑色字标",
    "google-camera": "Google Pixel 相机字标",
    "tokina-classic": "Tokina 黑色字标（含图标）",
    "rollei-white": "Rollei 白色字标",
    "rollei-black-box": "Rollei 黑框字标",
    "arri": "ARRI 企业字标",
    "apple-11": "Apple 标志 + 字标",
    "fujifilm-green-banner": "Fujifilm 绿白横幅字标",
}

# --- mechanical rules ----------------------------------------------------------
# ` black`/` white` as the last word: the tone chip already labels the cut, so keep the
# tone only as a Chinese qualifier of the mark.
TONE = re.compile(r'^(?P<brand>.+?)\s+(?P<tone>black|white)$', re.IGNORECASE)
# a bare catalogue number at the end is a file stem, not a description.
STEM = re.compile(r'^(?P<base>.+?)\s+(?P<n>\d+)$')

TONE_WORD = {"black": "黑色款", "white": "白色款"}


def rename(name: str, version: dict) -> str:
    resolved = EXPLICIT.get(version["id"]) or _mechanical(name)
    # A version that ships both inks already labels each cut 黑标 / 白标, so a tone in
    # the *version* name would contradict the chip ("Canon 黑色款 · 白标").
    if len(version.get("cuts") or []) > 1:
        for tone in ("黑色款", "白色款"):
            if resolved.endswith(tone):
                resolved = resolved[: -len(tone)] + "字标"
    return resolved


def _mechanical(name: str) -> str:
    tone = TONE.match(name)
    if tone:
        return f"{tone.group('brand')} {TONE_WORD[tone.group('tone').lower()]}"
    stem = STEM.match(name)
    if stem:
        return f"{stem.group('base')} 第 {stem.group('n')} 款"
    return name


def main(apply: bool) -> int:
    src_path = os.path.join(ROOT, TARGETS[0])
    raw = io.open(src_path, encoding='utf-8', newline='').read()
    data = json.loads(raw)
    rows, changed = [], 0
    for version in data["versions"]:
        before = version["name"]
        after = rename(before, version)
        if after != before:
            changed += 1
            rows.append((version["id"], before, after))
            version["name"] = after
    payload = json.dumps(data, ensure_ascii=False, indent=2) + "\n"
    print(f"renamed {changed} version names")
    for vid, before, after in rows:
        print(f"  {vid}\t{before}\t{after}")
    if apply:
        for target in TARGETS:
            path = os.path.join(ROOT, target)
            io.open(path, "w", encoding="utf-8", newline="").write(payload)
            print("wrote", target, len(payload))
    return changed


if __name__ == "__main__":
    main(apply="--apply" in sys.argv)
