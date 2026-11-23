#!/usr/bin/env python3
"""Frame-aspect gate: do the hand borders survive every photo aspect?

Covers the glass family (2026-11-23 audit) and the OPPO Polaroid · PLD frames
(7 frame arts, one per ratio bucket).

This is the automatic version of the 2026-11-23 玻璃 05 / 玻璃色卡 / 液态玻璃 audit
(evidence/glass-aspect-20261123). It renders the hand cards that use each glass
template with the four fixtures the release gate already uses (4:3 canonical,
3:4 portrait, 1:1 square, 16:9 wide) and applies only the rules that template
actually cares about:

  glassSideCard        - no text ink box may overlap the photo card;
                         the card must follow the photo aspect (no crop);
                         every card using the template is checked, so the
                         玻璃 05 / 玻璃卡片 linkage cannot break silently;
                         玻璃 05 additionally keeps the frozen composition
                         (photo starts 45.93%, card 40.89%W / 88.7%H, 5.65% pad).
  glassPalettePortrait - all six hex labels stay inside the glass panel;
                         the panel keeps panelWidthPct of the canvas width;
                         its aspect stays 676:310 unless the height cap bites.
  glass                - the bottom capsule keeps one aspect across fixtures
                         (7.16:1 for the stacked pill) and the text stays in it.
  polaroidFrame        - the canvas must match the frame art it draws (uniform
                         scale, no stretch), the art must be present and the
                         photo must stay inside the canvas. Regression: the
                         bundle shipped only the 4:3 landscape art, so every
                         other aspect rendered that art stretched.

Usage (runtime = a packaged desktop runtime: bin/e4hand.exe + data/):

  python qa/release-gate/frame-aspect.py --rt <runtime> [--exe <e4hand.exe>]
         [--fixtures <dir>] [--keep]

Exit code 0 = every rule passed, 1 = at least one violation (details printed).

Fixtures are centre crops of <runtime>/data/exif.jpg (it carries the EXIF block
the hand cards read for brand/model/exposure rows) plus the shipped
data/photo.png as the canonical 4:3 sample.  The engine only falls back to the
card's captured EXIF when `--photo` equals `card.photo.path`, so each fixture
gets a one-card cards file whose photo path points at the fixture.
"""
import argparse
import json
import os
import shutil
import subprocess
import sys
import tempfile

from PIL import Image

RATIOS = {"portrait": 3 / 4, "square": 1.0, "wide": 16 / 9}
FIXTURE_MAX_EDGE = 1400

# 模板 → 规则。卡片从 runtime 的 hand/cards.json 里按 templateId 反查，新增同模板
# 的卡自动进来；下面的具体数值只是设计稿的占比，换了设计要一起改。
CARD_EXPECT = {
    # 玻璃 05：冻结构图（evidence/glass-aspect-20261123/geometry.md）
    "glass-pill-5": {"photo_x_pct": 45.93, "photo_w_pct": 40.89, "photo_h_pct": 88.7, "padding_pct": 5.65},
    # 玻璃卡片：宽度驱动，卡片固定占画布 56.25%
    "glass-card": {"photo_w_pct": 56.25},
}
PILL_ASPECT = 7.1619          # 液态玻璃 stacked 胶囊的设计宽高比（94%W / 17.5%H @ 4:3）
PILL_ASPECT_TOL = 0.04        # 跨画幅 ±4%
PALETTE_PANEL_ASPECT = 676 / 310
PALETTE_PANEL_ASPECT_TOL = 0.05
PCT_TOL = 1.5                 # 占比断言容差（百分点）


class Gate:
    def __init__(self):
        self.rows = []
        self.failures = []

    def check(self, ok, card, fixture, rule, detail):
        row = {"card": card, "fixture": fixture, "rule": rule, "ok": bool(ok), "detail": detail}
        self.rows.append(row)
        if not ok:
            self.failures.append(row)
        return ok


def sh(cmd, cwd):
    proc = subprocess.run(cmd, cwd=cwd, capture_output=True, text=True)
    if proc.returncode != 0:
        raise RuntimeError(f"{' '.join(cmd)}\n{proc.stdout[-600:]}\n{proc.stderr[-600:]}")
    return proc


def centre_crop(im, ratio):
    w, h = im.size
    target_w = int(h * ratio)
    if target_w <= w:
        x = (w - target_w) // 2
        return im.crop((x, 0, x + target_w, h))
    target_h = int(w / ratio)
    y = (h - target_h) // 2
    return im.crop((0, y, w, y + target_h))


def make_fixtures(data_dir, out_dir):
    """4:3 用随包样片，另外三张从 exif.jpg 中心裁（保留 EXIF 块）。"""
    os.makedirs(out_dir, exist_ok=True)
    fixtures = {"4x3": os.path.join(data_dir, "photo.png")}
    source = os.path.join(data_dir, "exif.jpg")
    if not os.path.exists(source):
        raise RuntimeError(f"missing fixture source {source}")
    im = Image.open(source).convert("RGB")
    for name, ratio in RATIOS.items():
        cropped = centre_crop(im, ratio)
        if max(cropped.size) > FIXTURE_MAX_EDGE:
            k = FIXTURE_MAX_EDGE / max(cropped.size)
            cropped = cropped.resize((round(cropped.width * k), round(cropped.height * k)), Image.LANCZOS)
        path = os.path.join(out_dir, f"{name}.jpg")
        cropped.save(path, "JPEG", quality=95)
        fixtures[f"{3 if name == 'portrait' else 1 if name == 'square' else 16}x"
                 f"{4 if name == 'portrait' else 1 if name == 'square' else 9}"] = path
    return fixtures


def cards_for(data_dir, work_dir, template, fixture_path):
    doc = json.load(open(os.path.join(data_dir, "hand/cards.json"), encoding="utf-8"))
    keep = [c for c in doc["cards"] if c.get("templateId") == template]
    for card in keep:
        card["photo"]["path"] = fixture_path
    path = os.path.join(work_dir, f"cards-{template}-{os.path.basename(fixture_path)}.json")
    json.dump({"cards": keep}, open(path, "w", encoding="utf-8"), ensure_ascii=False, indent=1)
    return path, keep


def render(exe, data_dir, work_dir, card, cards_path, photo):
    png = os.path.join(work_dir, f"{card}-{os.path.basename(photo)}.png")
    report = png + ".json"
    sh([exe, "render", "--card", card, "--cards", cards_path, "--hand", "hand",
        "--fonts", "fonts", "--extra-fonts", "e4-extra-fonts",
        "--photo", photo, "--preview-max-edge", "900",
        "--out", png, "--report", report], cwd=data_dir)
    rep = json.load(open(report, encoding="utf-8"))
    os.remove(png)
    os.remove(report)
    return rep


def pct(value, total):
    return 100.0 * value / total if total else 0.0


def rects_of(rep):
    return [t.get("rect") or {} for t in rep["report"].get("texts", [])]


def overlap(a, b):
    """两矩形相交面积（像素）。任一为空则 0。"""
    if not all(k in a for k in ("x", "y", "w", "h")) or not all(k in b for k in ("x", "y", "w", "h")):
        return 0.0
    w = min(a["x"] + a["w"], b["x"] + b["w"]) - max(a["x"], b["x"])
    h = min(a["y"] + a["h"], b["y"] + b["h"]) - max(a["y"], b["y"])
    return max(0.0, w) * max(0.0, h)


def glass_backdrop(rep):
    for item in rep["report"].get("decor", []):
        if item.get("kind") == "glass.backdrop":
            return item["rect"]
    return None


def check_side_card(gate, exe, data_dir, work_dir, template, fixtures):
    measured = {}
    for name, photo in fixtures.items():
        cards_path, cards = cards_for(data_dir, work_dir, template, photo)
        for card in cards:
            cid = card["card"]
            rep = render(exe, data_dir, work_dir, cid, cards_path, photo)
            cw, ch = rep["canvas"]
            px, py, pw, ph = rep["photo_rect"]
            card_aspect = pw / ph if ph else 0.0
            src = Image.open(photo)
            photo_aspect = src.width / src.height
            # 规则 1：卡片跟照片同比例（照片完整入卡，不被窗口裁）
            gate.check(abs(card_aspect - photo_aspect) / photo_aspect < 0.01, cid, name,
                       "card follows photo aspect",
                       f"card {card_aspect:.3f} vs photo {photo_aspect:.3f}")
            # 规则 2：卡片在画布内
            inside = px >= -0.5 and py >= -0.5 and px + pw <= cw + 0.5 and py + ph <= ch + 0.5
            gate.check(inside, cid, name, "card inside canvas",
                       f"card x{px:.0f} y{py:.0f} {pw:.0f}x{ph:.0f} in {cw}x{ch}")
            # 规则 3：没有文字压到照片卡上
            worst = 0.0
            worst_txt = ""
            for t in rep["report"].get("texts", []):
                r = t.get("rect") or {}
                if not r.get("w"):
                    continue
                area = overlap(r, {"x": px, "y": py, "w": pw, "h": ph})
                ink = max(1.0, r["w"] * r["h"])
                ratio = area / ink
                if ratio > worst:
                    worst, worst_txt = ratio, (t.get("s") or t.get("raw") or "")
            gate.check(worst <= 0.0, cid, name, "text stays off the photo",
                       f"max overlap {100 * worst:.1f}%{' (' + worst_txt + ')' if worst_txt else ''}")
            expected = CARD_EXPECT.get(cid)
            if expected:
                got = {"photo_x_pct": pct(px, cw), "photo_w_pct": pct(pw, cw),
                       "photo_h_pct": pct(ph, ch), "padding_pct": pct(py, ch)}
                for key, want in expected.items():
                    gate.check(abs(got[key] - want) <= PCT_TOL, cid, name, key,
                               f"{got[key]:.2f}% (design {want}%)")
            measured.setdefault(cid, {})[name] = rep
    return measured


def check_palette(gate, exe, data_dir, work_dir, template, fixtures):
    for name, photo in fixtures.items():
        cards_path, cards = cards_for(data_dir, work_dir, template, photo)
        for card in cards:
            cid = card["card"]
            rep = render(exe, data_dir, work_dir, cid, cards_path, photo)
            cw, ch = rep["canvas"]
            panel = glass_backdrop(rep)
            if not gate.check(panel is not None, cid, name, "glass panel reported", "decor glass.backdrop"):
                continue
            params = json.load(open(os.path.join(data_dir, f"designs/{cid}.json"), encoding="utf-8"))["design"]["params"]
            want_w = params.get("panelWidthPct", 89.2)
            gate.check(abs(pct(panel["w"], cw) - want_w) <= PCT_TOL, cid, name, "panel keeps its width share",
                       f"{pct(panel['w'], cw):.2f}% of canvas (panelWidthPct {want_w})")
            aspect = panel["w"] / panel["h"] if panel["h"] else 0.0
            clamped = abs(pct(panel["h"], ch) - params.get("panelMaxHeightPct", 60.0)) < 0.5
            if clamped:
                gate.check(aspect >= PALETTE_PANEL_ASPECT * (1 - PALETTE_PANEL_ASPECT_TOL), cid, name,
                           "panel aspect (height capped)", f"{aspect:.3f} >= 676:310")
            else:
                gate.check(abs(aspect - PALETTE_PANEL_ASPECT) / PALETTE_PANEL_ASPECT <= PALETTE_PANEL_ASPECT_TOL,
                           cid, name, "panel aspect 676:310", f"{aspect:.3f}")
            outside = 0
            for r in rects_of(rep):
                if not r.get("w"):
                    continue
                if not (r["x"] >= panel["x"] - 0.5 and r["y"] >= panel["y"] - 0.5
                        and r["x"] + r["w"] <= panel["x"] + panel["w"] + 0.5
                        and r["y"] + r["h"] <= panel["y"] + panel["h"] + 0.5):
                    outside += 1
            gate.check(outside == 0, cid, name, "swatch labels stay inside the panel",
                       f"{len(rects_of(rep)) - outside}/{len(rects_of(rep))} inside")


def check_pill(gate, exe, data_dir, work_dir, template, fixtures):
    aspects = {}
    for name, photo in fixtures.items():
        cards_path, cards = cards_for(data_dir, work_dir, template, photo)
        for card in cards:
            cid = card["card"]
            rep = render(exe, data_dir, work_dir, cid, cards_path, photo)
            cw, ch = rep["canvas"]
            pill = glass_backdrop(rep)
            if not gate.check(pill is not None, cid, name, "pill reported", "decor glass.backdrop"):
                continue
            aspect = pill["w"] / pill["h"] if pill["h"] else 0.0
            aspects.setdefault(cid, {})[name] = aspect
            gate.check(abs(aspect - PILL_ASPECT) / PILL_ASPECT <= PILL_ASPECT_TOL, cid, name,
                       "pill keeps its aspect", f"{aspect:.2f}:1 (design {PILL_ASPECT:.2f}:1)")
            outside = 0
            for r in rects_of(rep):
                if not r.get("w"):
                    continue
                if overlap(r, pill) < r["w"] * r["h"] * 0.98:
                    outside += 1
            gate.check(outside == 0, cid, name, "pill text stays inside the pill",
                       f"{len(rects_of(rep)) - outside}/{len(rects_of(rep))} inside")
    for cid, per_fixture in aspects.items():
        values = list(per_fixture.values())
        spread = (max(values) - min(values)) / max(values)
        gate.check(spread <= PILL_ASPECT_TOL, cid, "all", "pill aspect stable across fixtures",
                   f"{min(values):.2f}..{max(values):.2f} (spread {100 * spread:.1f}%)")



def check_polaroid_frames(gate, exe, data_dir, work_dir, fixtures):
    """相框图必须和这一档的窗口同比例：画布 = 框图等比放大，照片在画布内，无 gap。"""
    for name, photo in fixtures.items():
        cards_path, cards = cards_for(data_dir, work_dir, "polaroidFrame", photo)
        for card in cards:
            cid = card["card"]
            rep = render(exe, data_dir, work_dir, cid, cards_path, photo)
            cw, ch = rep["canvas"]
            frames = [i for i in rep["report"].get("images", []) if i.get("key") == "frame"]
            if not gate.check(len(frames) == 1, cid, name, "frame art drawn", f"{len(frames)} frame image(s)"):
                continue
            art = frames[0]["src"]
            aw, ah = art["w"], art["h"]
            gate.check(abs(cw / ch - aw / ah) / (aw / ah) < 0.01, cid, name,
                       "canvas matches frame art aspect", f"canvas {cw/ch:.4f} vs art {aw/ah:.4f}")
            gate.check(abs(cw / aw - ch / ah) / (cw / aw) < 0.01, cid, name,
                       "frame art scaled uniformly", f"x{cw/aw:.3f} y{ch/ah:.3f}")
            gaps = rep["report"].get("gaps") or []
            gate.check(not gaps, cid, name, "no frame gaps", "; ".join(gaps)[:120])
            px, py, pw, ph = rep["photo_rect"]
            gate.check(px >= -0.5 and py >= -0.5 and px + pw <= cw + 0.5 and py + ph <= ch + 0.5,
                       cid, name, "photo inside canvas",
                       f"card x{px:.0f} y{py:.0f} {pw:.0f}x{ph:.0f} in {cw}x{ch}")


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--rt", default=os.environ.get("FM_RT", ""), help="packaged desktop runtime (bin + data)")
    ap.add_argument("--exe", default="", help="e4hand.exe override (default <rt>/bin/e4hand.exe)")
    ap.add_argument("--fixtures", default="", help="fixture dir (default: temp)")
    ap.add_argument("--work", default="", help="scratch dir (default: temp)")
    ap.add_argument("--keep", action="store_true", help="keep fixtures/scratch")
    args = ap.parse_args()
    if not args.rt:
        ap.error("--rt (or FM_RT) is required")
    rt = os.path.abspath(args.rt)
    data_dir = os.path.join(rt, "data")
    exe = os.path.abspath(args.exe) if args.exe else os.path.join(rt, "bin", "e4hand.exe")
    for path in (data_dir, exe):
        if not os.path.exists(path):
            ap.error(f"missing {path}")
    scratch = tempfile.mkdtemp(prefix="glass-aspect-")
    fixtures = make_fixtures(data_dir, args.fixtures or os.path.join(scratch, "fixtures"))
    work_dir = args.work or os.path.join(scratch, "work")
    os.makedirs(work_dir, exist_ok=True)

    gate = Gate()
    print(f"runtime {rt}")
    print(f"engine  {exe}")
    print(f"fixtures " + ", ".join(f"{k}={os.path.basename(v)}" for k, v in fixtures.items()))
    check_side_card(gate, exe, data_dir, work_dir, "glassSideCard", fixtures)
    check_palette(gate, exe, data_dir, work_dir, "glassPalettePortrait", fixtures)
    check_pill(gate, exe, data_dir, work_dir, "glass", fixtures)
    check_polaroid_frames(gate, exe, data_dir, work_dir, fixtures)

    cards = sorted({r["card"] for r in gate.rows})
    print()
    for row in gate.rows:
        mark = "ok  " if row["ok"] else "FAIL"
        print(f"  {mark} {row['card']:14s} {row['fixture']:5s} {row['rule']:34s} {row['detail']}")
    print()
    print(f"cards {len(cards)} ({', '.join(cards)})  checks {len(gate.rows)}  failures {len(gate.failures)}")
    if args.keep or args.fixtures or args.work:
        print(f"fixtures {fixtures['4x3'] and os.path.dirname(fixtures['4x3'])}  scratch {scratch}")
    else:
        shutil.rmtree(scratch, ignore_errors=True)
    print("RESULT " + ("PASS" if not gate.failures else "FAIL"))
    return 0 if not gate.failures else 1


if __name__ == "__main__":
    sys.exit(main())
