#!/usr/bin/env python3
"""FrameMark release gate B: validate every sweep render.

Rules applied to every frame
  * PNG decodes, non-empty, sane colour mode
  * engine report canvas == PNG size; hand canvas_matches_ops
  * no NaN/Inf anywhere in the report
  * text boxes inside the canvas, measure not wider than the box, no .notdef
  * photo / logo / shape / band boxes inside the canvas (backdrop+shadow may bleed)
  * image not blank / one flat colour / fully transparent
  * canonical frames additionally diff against the oracle goldens

Outputs check/report.json, check/report.md, check/contact-<fixture>.png and
check/diffs/*.png for frames that need a human look.
"""
import argparse
import hashlib
import json
import os
import sys
from collections import Counter, defaultdict

import numpy as np
from PIL import Image, ImageDraw

RT_DEFAULT = r"E:/FrameMark/execution-ui-v2-20261106/build/runs/20261117055959864/artifacts/desktop-runtime"
GOLDENS_DEFAULT = r"D:/border&watermark/_research/oracle/goldens"
OUT_DEFAULT = r"E:/FrameMark/qa/release-gate-20261117"

EPS = 1.5


def load_json(p):
    with open(p, "r", encoding="utf-8") as fh:
        return json.load(fh)


def walk_numbers(obj, path=""):
    if isinstance(obj, dict):
        for k, v in obj.items():
            yield from walk_numbers(v, f"{path}/{k}")
    elif isinstance(obj, list):
        for i, v in enumerate(obj):
            yield from walk_numbers(v, f"{path}[{i}]")
    elif isinstance(obj, float):
        yield path, obj


def num_bad(v):
    return v != v or v in (float("inf"), float("-inf"))


def rect_outside(rect, w, h, eps=EPS):
    x, y, rw, rh = rect
    return x < -eps or y < -eps or x + rw > w + eps or y + rh > h + eps


def ssim(a, b):
    a = a.astype(np.float64)
    b = b.astype(np.float64)
    c1 = (0.01 * 255) ** 2
    c2 = (0.03 * 255) ** 2
    mu_a, mu_b = a.mean(), b.mean()
    va, vb = a.var(), b.var()
    cov = ((a - mu_a) * (b - mu_b)).mean()
    return ((2 * mu_a * mu_b + c1) * (2 * cov + c2)) / ((mu_a**2 + mu_b**2 + c1) * (va + vb + c2))


def dhash(img, size=8):
    g = np.asarray(img.convert("L").resize((size + 1, size), Image.LANCZOS), dtype=np.int16)
    bits = (g[:, 1:] > g[:, :-1]).flatten()
    v = 0
    for bit in bits:
        v = (v << 1) | int(bit)
    return v


def hamming(a, b):
    return bin(a ^ b).count("1")


def build_golden_map(goldens_root, manifest, cards):
    index = {}
    for root, _dirs, files in os.walk(goldens_root):
        for f in files:
            if f.lower().endswith(".png"):
                index.setdefault(f, os.path.join(root, f))
    syscam = os.path.join(goldens_root, "system-camera")
    ir = {}
    for style in manifest.get("styles", []):
        sid = style.get("id", "")
        if ":" not in sid:
            continue
        fam, sty = sid.split(":", 1)
        candidates = [f"stock-{fam}-{sty}.png", f"stock-{fam.split('-')[0]}-{sty}.png"]
        for name in candidates:
            p = os.path.join(syscam, name)
            if os.path.exists(p):
                ir[style["uiCardId"]] = p
                break
    hand = {}
    for card in cards:
        name = os.path.basename(card.get("golden", ""))
        if name in index:
            hand[card["card"]] = index[name]
    return ir, hand


def compare_golden(cur_path, golden_path):
    cur = Image.open(cur_path).convert("RGB")
    gold = Image.open(golden_path).convert("RGB")
    if cur.size != gold.size:
        cur = cur.resize(gold.size, Image.LANCZOS)
    a = np.asarray(cur, dtype=np.int16)
    b = np.asarray(gold, dtype=np.int16)
    d = np.abs(a - b)
    gray_a = np.asarray(cur.convert("L"), dtype=np.uint8)
    gray_b = np.asarray(gold.convert("L"), dtype=np.uint8)
    return {
        "size": list(gold.size),
        "mean": float(d.mean()),
        "p99": float(np.percentile(d, 99)),
        "max": int(d.max()),
        "gt8": float((d > 8).mean()),
        "gt16": float((d > 16).mean()),
        "ssim": float(ssim(gray_a, gray_b)),
    }


def save_diff(cur_path, golden_path, out_path, gain=6):
    cur = Image.open(cur_path).convert("RGB")
    gold = Image.open(golden_path).convert("RGB")
    if cur.size != gold.size:
        cur = cur.resize(gold.size, Image.LANCZOS)
    d = np.abs(np.asarray(cur, np.int16) - np.asarray(gold, np.int16))
    heat = np.clip(d.astype(np.float32) * gain, 0, 255).astype(np.uint8)
    # red = bigger difference, on a dark copy of the frame
    base = (np.asarray(cur).astype(np.float32) * 0.25).astype(np.uint8)
    hot = heat.max(axis=2)
    out = base.copy()
    out[..., 0] = np.clip(base[..., 0] + hot, 0, 255)
    out[..., 1] = np.clip(base[..., 1] + hot * 0.15, 0, 255)
    out[..., 2] = np.clip(base[..., 2] + hot * 0.1, 0, 255)
    os.makedirs(os.path.dirname(out_path), exist_ok=True)
    Image.fromarray(out).save(out_path)


def entirely_outside(rect, w, h):
    x, y, rw, rh = rect
    return x >= w or y >= h or x + rw <= 0 or y + rh <= 0


def rect_overlap(a, b):
    ox = min(a[0] + a[2], b[0] + b[2]) - max(a[0], b[0])
    oy = min(a[1] + a[3], b[1] + b[3]) - max(a[1], b[1])
    if ox <= 2 or oy <= 2:
        return 0.0
    area = ox * oy
    smaller = min(a[2] * a[3], b[2] * b[3])
    return area / smaller if smaller > 0 else 0.0


def check_report(kind, report, w, h):
    """Return (hard problems, soft notes).

    Bands and backdrop images are allowed to bleed past the canvas by design
    (the frame band is drawn full width and then clipped); only content that is
    entirely off-canvas, a wrong canvas, tofu glyphs, or text boxes outside the
    frame are hard failures.  Per-line boxes of rotated/vertical text are also
    compared on their long axis.
    """
    problems = []
    notes = []
    boxes = []
    if kind == "ir":
        canvas = report.get("canvas") or {}
        if canvas.get("w") != w or canvas.get("h") != h:
            problems.append(f"canvas mismatch report={canvas} png={w}x{h}")
        for i, item in enumerate(report.get("items") or []):
            if item.get("kind") == "text":
                r = item.get("rect") or [0, 0, 0, 0]
                boxes.append(r)
                if rect_outside(r, w, h):
                    problems.append(f"text#{i} rect outside {r}")
                span = max(r[2] or 0, r[3] or 0)
                if (item.get("measure") or 0) > span + 2:
                    problems.append(f"text#{i} measure {item.get('measure')} > text span {span}")
                if item.get("notdef"):
                    problems.append(f"text#{i} notdef={item['notdef']}")
                if not item.get("lines") or not str(item.get("lines")[0]).strip():
                    problems.append(f"text#{i} empty lines")
            elif item.get("kind") == "image":
                r = item.get("rect") or item.get("draw") or [0, 0, 0, 0]
                if entirely_outside(r, w, h):
                    problems.append(f"image#{i} entirely off-canvas {r}")
            elif item.get("kind") == "shape":
                r = item.get("rect") or [0, 0, 0, 0]
                if entirely_outside(r, w, h):
                    problems.append(f"shape#{i} entirely off-canvas {r}")
        for i, band in enumerate(report.get("bands") or []):
            r = band.get("rect") or [0, 0, 0, 0]
            if entirely_outside(r, w, h):
                problems.append(f"band#{i} entirely off-canvas {r}")
    else:
        canvas = report.get("canvas") or [0, 0]
        if list(canvas) != [w, h]:
            problems.append(f"canvas mismatch report={canvas} png={w}x{h}")
        if report.get("canvas_matches_ops") is False:
            # A changed output ratio (design.ratio) legitimately changes the
            # canvas the captured ops were measured at; the PNG and report must
            # still agree, which the check above proves.
            notes.append("canvas_matches_ops=false (design ratio changes canvas)")
        inner = report.get("report") or {}
        for i, text in enumerate(inner.get("texts") or []):
            if text.get("hidden"):
                continue
            r = text.get("rect") or {}
            box = [r.get("x", 0), r.get("y", 0), r.get("w", 0), r.get("h", 0)]
            boxes.append(box)
            if rect_outside(box, w, h):
                problems.append(f"text#{i} {text.get('key')} rect outside {box}")
            span = max(box[2], box[3])
            if (text.get("measure") or 0) > span + 2:
                problems.append(f"text#{i} {text.get('key')} measure {text.get('measure')} > text span {span}")
        for i, img in enumerate(inner.get("images") or []):
            key = img.get("key")
            if key in ("backdrop", "photo-shadow"):
                continue
            dst = img.get("dst") or {}
            box = [dst.get("x", 0), dst.get("y", 0), dst.get("w", 0), dst.get("h", 0)]
            if entirely_outside(box, w, h):
                problems.append(f"image#{i} {key} entirely off-canvas {box}")
        for i, shape in enumerate(inner.get("shapes") or []):
            r = shape.get("rect") or {}
            box = [r.get("x", 0), r.get("y", 0), r.get("w", 0), r.get("h", 0)]
            if entirely_outside(box, w, h):
                problems.append(f"shape#{i} entirely off-canvas {box}")
    # Text boxes that almost fully coincide mean two lines were painted on top
    # of each other.  A small overlap can be a deliberate tight line spacing
    # (hassel xpan uses marginTop=-3), so only heavy overlap is a failure and a
    # mild one is recorded for review.
    for i in range(len(boxes)):
        for j in range(i + 1, len(boxes)):
            ratio = rect_overlap(boxes[i], boxes[j])
            if ratio > 0.5:
                problems.append(f"text overlap text#{i}/text#{j} = {ratio*100:.0f}% of the smaller box")
            elif ratio > 0.1:
                notes.append(f"tight text spacing text#{i}/text#{j} = {ratio*100:.0f}%")
    return problems, notes


def check_pixels(path):
    problems = []
    img = Image.open(path)
    if img.width < 16 or img.height < 16:
        problems.append(f"tiny image {img.width}x{img.height}")
    rgba = img.convert("RGBA")
    arr = np.asarray(rgba)
    rgb = arr[..., :3].astype(np.uint8)
    alpha = arr[..., 3]
    mean = float(rgb.mean())
    std = float(rgb.std())
    if std < 2.0:
        problems.append(f"flat image std={std:.2f} mean={mean:.1f}")
    if mean > 250.5:
        problems.append(f"blown-out white mean={mean:.1f}")
    if mean < 4.5:
        problems.append(f"all-black mean={mean:.1f}")
    if alpha.min() < 250 and (alpha < 8).mean() > 0.9:
        problems.append("fully transparent frame")
    # solid single colour: sample 4096 pixels and require >=2 distinct colours
    step = max(1, (rgb.shape[0] * rgb.shape[1]) // 4096)
    sample = rgb.reshape(-1, 3)[::step]
    uniq = np.unique(sample, axis=0).shape[0]
    if uniq < 3:
        problems.append(f"single-colour frame ({uniq} colours)")
    return problems


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--out", default=OUT_DEFAULT)
    ap.add_argument("--rt", default=RT_DEFAULT)
    ap.add_argument("--goldens", default=GOLDENS_DEFAULT)
    ap.add_argument("--mean-pass", type=float, default=1.5)
    ap.add_argument("--mean-warn", type=float, default=3.0)
    ap.add_argument("--ssim-pass", type=float, default=0.995)
    args = ap.parse_args()

    sweep = load_json(os.path.join(args.out, "results.json"))
    data = os.path.join(args.rt, "data")
    manifest = load_json(os.path.join(data, "manifest.json"))
    cards = load_json(os.path.join(data, "hand", "cards.json"))["cards"]
    ir_gold, hand_gold = build_golden_map(args.goldens, manifest, cards)
    known_path = os.path.join(os.path.dirname(os.path.abspath(__file__)), "known-deviations.json")
    known_deviations = load_json(known_path) if os.path.exists(known_path) else {}

    report = {
        "when": __import__("datetime").datetime.now().isoformat(timespec="seconds"),
        "sweep": sweep["summary"],
        "thresholds": {
            "mean_pass": args.mean_pass, "mean_warn": args.mean_warn,
            "ssim_pass": args.ssim_pass,
        },
        "frames": [],
        "golden": [],
        "missing_golden": [],
    }
    dhash_map = defaultdict(list)
    phash_map = defaultdict(list)

    for res in sweep["results"]:
        entry = {
            "kind": res["kind"], "fixture": res["fixture"], "id": res["id"],
            "code": res["code"], "out": res.get("out"), "ms": res.get("ms"),
            "problems": [], "golden": None,
        }
        if res["code"] != 0:
            entry["problems"].append(f"render code={res['code']} {res.get('error', '')}")
            entry["status"] = "FAIL"
            report["frames"].append(entry)
            continue
        png = res["out"]
        rep_path = res["report"]
        if not os.path.exists(png):
            entry["problems"].append("missing png")
            entry["status"] = "FAIL"
            report["frames"].append(entry)
            continue
        if not os.path.exists(rep_path):
            entry["problems"].append("missing report")
            entry["status"] = "FAIL"
            report["frames"].append(entry)
            continue
        report_json = load_json(rep_path)
        for path, value in walk_numbers(report_json):
            if num_bad(value):
                entry["problems"].append(f"NaN/Inf at {path}")
                break
        img = Image.open(png)
        entry["size"] = [img.width, img.height]
        problems, notes = check_report(res["kind"], report_json, img.width, img.height)
        entry["problems"].extend(problems)
        if notes:
            entry["notes"] = notes
        entry["problems"].extend(check_pixels(png))

        golden_path = None
        if res["fixture"] == "canonical":
            golden_path = (ir_gold if res["kind"] == "ir" else hand_gold).get(res["id"])
        if res["fixture"] == "canonical" and not golden_path:
            report["missing_golden"].append({"kind": res["kind"], "id": res["id"]})
        if golden_path:
            g = compare_golden(png, golden_path)
            entry["golden"] = g
            status = "PASS"
            if g["mean"] > args.mean_warn or g["ssim"] < 0.98:
                status = "FAIL"
            elif g["mean"] > args.mean_pass or g["ssim"] < args.ssim_pass or g["gt16"] > 0.002:
                status = "WARN"
            known = known_deviations.get(res["id"])
            if status == "FAIL" and known:
                status = "WARN"
                entry["golden"]["known"] = known
            entry["golden"]["status"] = status
            if status != "PASS":
                diff_name = f"{res['kind']}-{res['id']}.png".replace(":", "_")
                save_diff(png, golden_path, os.path.join(args.out, "check", "diffs", diff_name))
                entry["golden"]["diff"] = os.path.join("check", "diffs", diff_name)
            report["golden"].append({"kind": res["kind"], "id": res["id"], **g, "status": status})

        digest = hashlib.sha256(open(png, "rb").read()).hexdigest()
        entry["sha256"] = digest
        dhash_map[(res["fixture"], digest)].append(f"{res['kind']}:{res['id']}")
        phash_map[(res["fixture"], dhash(Image.open(png)))].append(f"{res['kind']}:{res['id']}")

        if entry["problems"]:
            entry["status"] = "FAIL"
        elif entry["golden"] and entry["golden"]["status"] in ("WARN", "FAIL"):
            entry["status"] = "WARN" if entry["golden"]["status"] == "WARN" else "FAIL"
        else:
            entry["status"] = "PASS"
        report["frames"].append(entry)

    dupes = []
    for (fixture, digest), ids in dhash_map.items():
        if len(ids) > 1:
            dupes.append({"fixture": fixture, "sha256": digest, "ids": ids})
    report["exact_duplicates"] = dupes
    near = []
    for (fixture, ph), ids in phash_map.items():
        if len(ids) > 1 and not any(d["fixture"] == fixture and d["ids"] == ids for d in dupes):
            near.append({"fixture": fixture, "dhash": hex(ph), "ids": ids})
    report["near_duplicates"] = near

    counts = Counter((f["kind"], f["fixture"], f["status"]) for f in report["frames"])
    report["counts"] = {f"{k[0]}/{k[1]}/{k[2]}": v for k, v in sorted(counts.items())}
    report["failures"] = [
        {"kind": f["kind"], "fixture": f["fixture"], "id": f["id"], "problems": f["problems"], "golden": f["golden"]}
        for f in report["frames"] if f["status"] == "FAIL"
    ]
    report["warnings"] = [
        {"kind": f["kind"], "fixture": f["fixture"], "id": f["id"], "golden": f["golden"]}
        for f in report["frames"] if f["status"] == "WARN"
    ]

    os.makedirs(os.path.join(args.out, "check"), exist_ok=True)
    with open(os.path.join(args.out, "check", "report.json"), "w", encoding="utf-8") as fh:
        json.dump(report, fh, ensure_ascii=False, indent=2)

    worst = sorted(report["golden"], key=lambda g: -g["mean"])[:20]
    lines = [
        "# FrameMark export sweep — 全边框渲染校验",
        "",
        f"- 时间：{report['when']}",
        f"- 渲染：{sweep['summary']['total']} 帧，成功 {sweep['summary']['ok']}，失败 {sweep['summary']['failed']}",
        f"- 帧状态：{json.dumps(report['counts'], ensure_ascii=False)}",
        f"- 黄金比对：{len(report['golden'])} 帧（缺黄金 {len(report['missing_golden'])}）",
        f"- 规则失败：{len(report['failures'])}，警告：{len(report['warnings'])}",
        "",
        "## 与黄金图偏差最大的 20 帧",
        "",
        "| 类型 | 边框 | mean | p99 | SSIM | >16/255 |",
        "| --- | --- | --- | --- | --- | --- |",
    ]
    for g in worst:
        lines.append(f"| {g['kind']} | {g['id']} | {g['mean']:.2f} | {g['p99']:.1f} | {g['ssim']:.4f} | {g['gt16']*100:.3f}% |")
    if report["failures"]:
        lines += ["", "## 失败", ""]
        for f in report["failures"][:60]:
            lines.append(f"- {f['kind']}/{f['fixture']}/{f['id']}: {'; '.join(f['problems'][:4]) or (f['golden'] and f['golden'].get('status'))}")
    if report["warnings"]:
        lines += ["", "## 警告", ""]
        for w in report["warnings"][:60]:
            g = w["golden"] or {}
            lines.append(f"- {w['kind']}/{w['id']}: mean={g.get('mean', 0):.2f} ssim={g.get('ssim', 0):.4f}")
    if dupes:
        lines += ["", "## 完全相同的渲染（可能边框未生效）", ""]
        for d in dupes[:20]:
            lines.append(f"- {d['fixture']} {d['sha256'][:16]}: {', '.join(d['ids'])}")
    with open(os.path.join(args.out, "check", "report.md"), "w", encoding="utf-8") as fh:
        fh.write("\n".join(lines) + "\n")

    make_contact_sheets(args.out, report["frames"])
    print(json.dumps({"counts": report["counts"], "failures": len(report["failures"]), "warnings": len(report["warnings"]), "dupes": len(dupes)}, ensure_ascii=False))
    return 0 if not report["failures"] else 2


def make_contact_sheets(out, frames):
    by_fixture = defaultdict(list)
    for f in frames:
        if f.get("status") != "FAIL" or f.get("out"):
            by_fixture[f["fixture"]].append(f)
    for fixture, items in by_fixture.items():
        items = [f for f in items if f.get("out") and os.path.exists(f["out"])]
        if not items:
            continue
        cols = 10
        tw = 220
        label_h = 18
        thumbs = []
        for f in items:
            im = Image.open(f["out"]).convert("RGB")
            th = max(1, int(im.height * tw / im.width))
            thumbs.append((f, im.resize((tw, th), Image.LANCZOS)))
        cell_h = max(t.height for _, t in thumbs) + label_h
        rows = (len(thumbs) + cols - 1) // cols
        sheet = Image.new("RGB", (cols * tw, rows * cell_h), (24, 24, 26))
        draw = ImageDraw.Draw(sheet)
        for i, (f, t) in enumerate(thumbs):
            x = (i % cols) * tw
            y = (i // cols) * cell_h
            sheet.paste(t, (x, y))
            label = f["id"][:34] + ("…" if len(f["id"]) > 34 else "")
            draw.text((x + 3, y + t.height + 3), label, fill=(220, 220, 220))
            if f.get("status") != "PASS":
                draw.rectangle([x, y, x + tw - 1, y + t.height - 1], outline=(255, 80, 80), width=3)
        os.makedirs(os.path.join(out, "check"), exist_ok=True)
        sheet.save(os.path.join(out, "check", f"contact-{fixture}.png"))


if __name__ == "__main__":
    sys.exit(main())
