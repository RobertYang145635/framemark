# -*- coding: utf-8 -*-
"""Sheet builder — sheet-light.png / sheet-dark.png / sheet-risk.png.

Uses the unified pool (baseline PNG + catalog webp cuts) so every brand that has
any renderable wordmark appears.
"""
import json, os, sys
import numpy as np
from PIL import Image, ImageDraw, ImageFont

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import metrics as M  # noqa: E402

ROOT = r"E:/FrameMark/work/ui-v2"
LOGOS = os.path.join(ROOT, "ui/composeApp/src/commonMain/composeResources/files/logos")
LOGOV = os.path.join(ROOT, "ui/composeApp/src/commonMain/composeResources/files/logov")
HERE = os.path.dirname(os.path.abspath(__file__))


def jpath(name):
    """Metrics JSON lives next to the script, or in ./data for the published tree."""
    for p in (os.path.join(HERE, name), os.path.join(HERE, "data", name),
              os.path.join(HERE, os.pardir, "data", name)):
        if os.path.exists(p):
            return p
    raise SystemExit("missing %s (run the tools/analyze*.py steps first)" % name)


def font(size):
    for p in (r"C:/Windows/Fonts/arial.ttf", r"C:/Windows/Fonts/segoeui.ttf"):
        if os.path.exists(p):
            try:
                return ImageFont.truetype(p, size)
            except OSError:
                pass
    return ImageFont.load_default(size)


def path_of(asset):
    if asset["src"] == "png":
        return os.path.join(LOGOS, asset["file"])
    return os.path.join(LOGOV, asset["file"])


def load_rgba(asset):
    with Image.open(path_of(asset)) as im:
        return np.asarray(im.convert("RGBA"))


def draw_logo(canvas, asset, box, pad=5):
    arr = load_rgba(asset)
    alpha = arr[..., 3]
    ys, xs = np.nonzero(alpha > 8)
    if len(xs) == 0:
        return False
    crop = Image.fromarray(arr[ys.min():ys.max() + 1, xs.min():xs.max() + 1], "RGBA")
    bw = box[2] - box[0] - 2 * pad
    bh = box[3] - box[1] - 2 * pad
    scale = min(bw / crop.width, bh / crop.height)
    nw, nh = max(1, int(round(crop.width * scale))), max(1, int(round(crop.height * scale)))
    small = crop.resize((nw, nh), Image.LANCZOS)
    canvas.alpha_composite(small, (box[0] + (box[2] - box[0] - nw) // 2,
                                   box[1] + (box[3] - box[1] - nh) // 2))
    return True


def brand_sheets(pool, brands, outdir, cols=8, cw=200, ch=98):
    by_brand = {}
    for a in pool.values():
        if a["brandKey"]:
            by_brand.setdefault(a["brandKey"], []).append(a)
    order = sorted(brands, key=lambda b: (b.get("group", ""), (b.get("label") or b["key"]).lower()))
    rows = (len(order) + cols - 1) // cols
    head = 46
    for name, bg, metric, title in (
            ("sheet-light.png", M.LIGHT_BG["white"], "light_best",
             "LIGHT backdrop #FFFFFF — best light-usable cut per brand (102 brands)"),
            ("sheet-dark.png", M.DARK_BG["ink"], "dark_best",
             "DARK backdrop #1C1D1F — best dark-usable cut per brand (102 brands)")):
        W = cols * cw
        H = head + rows * ch + 6
        canvas = Image.new("RGBA", (W, H), tuple(bg) + (255,))
        d = ImageDraw.Draw(canvas)
        f = font(12)
        fg = (20, 20, 20, 255) if sum(bg) > 384 else (235, 235, 235, 255)
        d.text((10, 6), title, font=font(14), fill=fg)
        d.text((10, 26), "label = brandKey + best WCAG contrast on this backdrop "
                         "(red = below 3.0, grey = no bitmap for this side)", font=f, fill=fg)
        for i, b in enumerate(order):
            r, c = divmod(i, cols)
            x, y = c * cw, head + r * ch
            mine = by_brand.get(b["key"], [])
            if not mine:
                d.text((x + 8, y + 30), "NO BITMAP", font=f, fill=(200, 70, 60, 255))
                d.text((x + 5, y + ch - 18), b["key"], font=f, fill=fg)
                continue
            best = max(mine, key=lambda a: a[metric])
            ok = best[metric] >= 3.0
            draw_logo(canvas, best, (x + 4, y + 6, x + cw - 4, y + ch - 20))
            tag = "%s  %.1f" % (b["key"], best[metric])
            d.text((x + 5, y + ch - 17), tag, font=f,
                   fill=fg if ok else (215, 70, 60, 255))
            if not ok:
                d.rectangle([x + 2, y + 3, x + cw - 2, y + ch - 3], outline=(200, 70, 60, 255))
        canvas.convert("RGB").save(os.path.join(outdir, name))
        print("wrote", name, canvas.size)


def risk_sheet(pool, outdir, n=16, scale=4):
    assets = [a for a in pool.values() if a["src"] == "png"]
    ranked, seen = [], set()
    for a in sorted(assets, key=lambda x: x["t200_psnr_2tap"]):
        sig = (a["brandKey"], round(a["t200_psnr_2tap"], 1), tuple(a["bbox"]))
        if sig in seen:
            continue
        seen.add(sig)
        ranked.append(a)
        if len(ranked) >= n:
            break
    cw = 200 * scale
    W = cw * 2 + 30
    rows = [(max(1, round(a["h"] * 200 / a["w"])) * scale + 30) for a in ranked]
    H = 58 + sum(rows)
    canvas = Image.new("RGB", (W, H), (248, 248, 248))
    d = ImageDraw.Draw(canvas)
    f = ImageFont.truetype(r"C:/Windows/Fonts/arial.ttf", 12) if os.path.exists(
        r"C:/Windows/Fonts/arial.ttf") else ImageFont.load_default(12)
    d.text((10, 6), "MOTTLING / ALIASING — '2tap bilinear' (IR model, left) vs LANCZOS (right), "
                    "drawn at the real 200 px width, x4 nearest", font=font(14), fill=(10, 10, 10))
    d.text((10, 26), "left = what a 5x-downscale draw without a mipmap produces; right = a correct "
                     "resample. PSNR/MAD compare the two at 200 px.", font=f, fill=(70, 70, 70))
    y = 58
    for a, rh in zip(ranked, rows):
        arr = load_rgba(a)
        bg = np.array(M.DARK_BG["ink"] if a["psnr_bg"] == "ink" else M.LIGHT_BG["white"], float)
        flat = M.flat_rgb(arr.astype(np.float64), bg)
        oh = max(1, round(a["h"] * 200 / a["w"]))
        for k, mode in enumerate(("2tap", "lanczos")):
            img = M.resample(flat, 200, oh, mode)
            big = Image.fromarray(np.clip(img, 0, 255).astype(np.uint8)).resize(
                (cw, oh * scale), Image.NEAREST) if oh > 3 else None
            if big:
                canvas.paste(big, (10 + k * (cw + 10), y))
        d.text((10, y + rh - 22),
               "%s  ·  %dx%d → 200x%d  ·  PSNR_2tap=%.2f dB  MAD=%.2f  ·  thinnest stroke "
               "%.2f px @200  ·  %s" % (a["stem"], a["w"], a["h"], oh, a["t200_psnr_2tap"],
                                        a["t200_mad_2tap"], a["stroke_at_200"],
                                        "white ink" if a["psnr_bg"] == "ink" else "dark ink"),
               font=f, fill=(25, 25, 25))
        d.line([(10, y + rh - 4), (W - 10, y + rh - 4)], fill=(210, 210, 210))
        y += rh
    canvas.save(os.path.join(outdir, "sheet-risk.png"))
    print("wrote sheet-risk.png", canvas.size)


def main():
    outdir = sys.argv[1] if len(sys.argv) > 1 else os.path.join(HERE, "out")
    os.makedirs(outdir, exist_ok=True)
    png = json.load(open(jpath("analysis.json"), encoding="utf-8"))["cuts"]
    webp = json.load(open(jpath("versions.json"), encoding="utf-8"))["cuts"]
    cat = json.load(open(os.path.join(ROOT, "ui/composeApp/src/commonMain/composeResources/"
                                            "files/data/logos.json"), encoding="utf-8"))
    pool = {c["stem"]: dict(c, src="webp") for c in webp}
    for c in png:
        mine = dict(c, src="png")
        if c["stem"] in pool:
            mine["brandKey"] = c["brandKey"] or pool[c["stem"]]["brandKey"]
        pool[c["stem"]] = mine
    brand_sheets(pool, cat["brands"], outdir)
    risk_sheet(pool, outdir)


if __name__ == "__main__":
    main()
