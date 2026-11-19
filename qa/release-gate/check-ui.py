#!/usr/bin/env python3
"""FrameMark release gate C: validate the captured UI screenshots.

Pixel checks catch blank/black/white frames and prove that the five palettes
really differ and that dark mode is darker than light mode.  The uiautomator
dumps captured next to every phone shot are checked for controls that escaped
the screen or tap targets smaller than 40 px.  Contact sheets group the whole
matrix so a human can scan it in one image per device.
"""
import json
import os
import sys
import xml.etree.ElementTree as ET
from collections import defaultdict

import numpy as np
from PIL import Image, ImageDraw

ROOT = r"E:/FrameMark/qa/release-gate-20261117/ui"
OUT = os.path.join(ROOT, "check")


def load(path):
    with open(path, encoding="utf-8") as fh:
        return json.load(fh)


def image_stats(path):
    img = Image.open(path).convert("RGB")
    arr = np.asarray(img, dtype=np.uint8)
    return {
        "size": [img.width, img.height],
        "mean": float(arr.mean()),
        "std": float(arr.std()),
        "colours": int(np.unique(arr.reshape(-1, 3)[:: max(1, arr.shape[0] * arr.shape[1] // 4096)], axis=0).shape[0]),
    }


def check_phone():
    d = os.path.join(ROOT, "phone")
    if not os.path.isdir(d):
        return None
    frames, problems = [], []
    stats = {}
    for name in sorted(os.listdir(d)):
        if not name.endswith(".png") or name.startswith("_"):
            continue
        path = os.path.join(d, name)
        s = image_stats(path)
        stats[name] = s
        issues = []
        if s["size"] not in ([1440, 3168], [3168, 1440]):
            issues.append(f"unexpected size {s['size']}")
        if s["std"] < 4:
            issues.append(f"flat frame std={s['std']:.1f}")
        if s["mean"] < 2:
            issues.append("black frame")
        xml_path = os.path.join(d, name[:-4] + ".xml")
        nodes = []
        if os.path.exists(xml_path):
            try:
                tree = ET.parse(xml_path)
                W, H = s["size"]
                for n in tree.iter("node"):
                    b = n.get("bounds") or ""
                    if not b.startswith("["):
                        continue
                    import re
                    m = re.match(r"\[(\d+),(\d+)\]\[(\d+),(\d+)\]", b)
                    if not m:
                        continue
                    x1, y1, x2, y2 = map(int, m.groups())
                    label = (n.get("text") or "").strip() or (n.get("content-desc") or "").strip()
                    if n.get("clickable") == "true" and (x2 - x1) < 40 and (y2 - y1) < 40:
                        issues.append(f"tiny tap target {label or '?'} {b}")
                    if label and (x1 < -2 or y1 < -2 or x2 > W + 2 or y2 > H + 2):
                        issues.append(f"label outside screen {label!r} {b}")
                    if label:
                        nodes.append(label)
            except ET.ParseError as exc:
                issues.append(f"ui dump parse: {exc}")
        frames.append({"name": name, "stats": s, "issues": issues, "nodes": len(nodes)})
        for issue in issues:
            problems.append(f"{name}: {issue}")
    # palette differentiation + light/dark contrast, using the settings page shots
    for metric in ("settings", "home", "editor"):
        for mode in ("浅色", "深色"):
            means = {}
            for pal in ["暖灰墨绿", "石墨琥珀", "瓷白靛青", "纸白陶土", "经典蓝"]:
                key = f"{metric}-{pal}-{mode}.png"
                if key in stats:
                    means[pal] = stats[key]["mean"]
            if len(means) >= 2:
                vals = list(means.values())
                if max(vals) - min(vals) < 1.5:
                    problems.append(f"{metric}/{mode}: palettes look identical (means {vals})")
        light = stats.get(f"{metric}-暖灰墨绿-浅色.png")
        dark = stats.get(f"{metric}-暖灰墨绿-深色.png")
        if light and dark and light["mean"] - dark["mean"] < 15:
            problems.append(f"{metric}: dark mode is not darker (light {light['mean']:.1f} vs dark {dark['mean']:.1f})")
    return {"frames": frames, "problems": problems}


def check_desktop():
    d = os.path.join(ROOT, "desktop")
    manifest = os.path.join(d, "desktop-matrix.json")
    if not os.path.exists(manifest):
        return None
    rows = load(manifest)
    if isinstance(rows, dict):
        rows = [rows]
    frames, problems = [], []
    for row in rows:
        path = row.get("shot")
        if not path or not os.path.exists(path):
            problems.append(f"{row.get('name')}: missing screenshot")
            continue
        s = image_stats(path)
        issues = []
        if s["std"] < 4:
            issues.append(f"flat frame std={s['std']:.1f}")
        w, h = row.get("window", "0x0").split("x")
        # The capture is the client area: a decorated window loses its frame and
        # title bar (<= 32 px horizontally, <= 64 px vertically).
        if abs(s["size"][0] - int(w)) > 32 or abs(s["size"][1] - int(h)) > 64:
            issues.append(f"capture {s['size']} != window {w}x{h}")
        frames.append({"name": row.get("name"), "stats": s, "issues": issues})
        problems += [f"{row.get('name')}: {i}" for i in issues]
    # palette differentiation on the editor shots
    for mode in ("light", "dark"):
        means = []
        for pal in ("forest", "graphite", "indigo", "clay", "classic"):
            name = f"palette-{pal}-{mode}"
            f = next((x for x in frames if x["name"] == name), None)
            if f:
                means.append(f["stats"]["mean"])
        if len(means) >= 2 and max(means) - min(means) < 0.8:
            problems.append(f"desktop palettes/{mode} look identical (means {means})")
    return {"frames": frames, "problems": problems}


def contact_sheet(directory, out_path, cols=10, width=220):
    if not os.path.isdir(directory):
        return
    files = sorted(f for f in os.listdir(directory) if f.endswith(".png") and not f.startswith("_"))
    if not files:
        return
    thumbs = []
    for name in files:
        im = Image.open(os.path.join(directory, name)).convert("RGB")
        th = max(1, int(im.height * width / im.width))
        thumbs.append((name, im.resize((width, th), Image.LANCZOS)))
    cell = max(t.height for _, t in thumbs) + 18
    rows = (len(thumbs) + cols - 1) // cols
    sheet = Image.new("RGB", (cols * width, rows * cell), (24, 24, 26))
    draw = ImageDraw.Draw(sheet)
    for i, (name, t) in enumerate(thumbs):
        x = (i % cols) * width
        y = (i // cols) * cell
        sheet.paste(t, (x, y))
        draw.text((x + 3, y + t.height + 2), name[:32], fill=(225, 225, 225))
    os.makedirs(os.path.dirname(out_path), exist_ok=True)
    sheet.save(out_path)


def main():
    os.makedirs(OUT, exist_ok=True)
    report = {"phone": check_phone(), "desktop": check_desktop()}
    problems = []
    for key in ("phone", "desktop"):
        if report[key]:
            problems += [f"{key}: {p}" for p in report[key]["problems"]]
    report["problems"] = problems
    report["ok"] = not problems
    with open(os.path.join(OUT, "ui-report.json"), "w", encoding="utf-8") as fh:
        json.dump(report, fh, ensure_ascii=False, indent=2)

    lines = ["# FrameMark UI 视觉校验", ""]
    for key, title in (("phone", "手机"), ("desktop", "桌面")):
        section = report.get(key)
        if not section:
            continue
        lines.append(f"## {title}")
        lines.append("")
        lines.append(f"- 截图：{len(section['frames'])} 张")
        lines.append(f"- 问题：{len(section['problems'])}")
        for p in section["problems"][:40]:
            lines.append(f"  - {p}")
        lines.append("")
    with open(os.path.join(OUT, "ui-report.md"), "w", encoding="utf-8") as fh:
        fh.write("\n".join(lines) + "\n")

    contact_sheet(os.path.join(ROOT, "phone"), os.path.join(OUT, "contact-phone.png"))
    contact_sheet(os.path.join(ROOT, "desktop"), os.path.join(OUT, "contact-desktop.png"))
    print(json.dumps({"phone": len(report["phone"]["frames"]) if report["phone"] else 0,
                      "desktop": len(report["desktop"]["frames"]) if report["desktop"] else 0,
                      "problems": len(problems)}, ensure_ascii=False))
    return 0 if not problems else 2


if __name__ == "__main__":
    sys.exit(main())
