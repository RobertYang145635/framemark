# -*- coding: utf-8 -*-
"""Shared pixel metrics for the FrameMark logo audit (pure numpy + PIL).

`measure(rgba)` is the single implementation used for the rendered PNG batch
(files/logos) and the packaged catalog webp cuts (files/logov), so both tables
are directly comparable.
"""
import numpy as np
from PIL import Image

# Card backdrops from the theme enum (EngineArgs.THEMES).
LIGHT_BG = {"white": (255, 255, 255), "paper": (247, 245, 240),
            "cream": (244, 239, 230), "stone": (220, 216, 208)}
DARK_BG = {"ink": (28, 29, 31), "black": (0, 0, 0)}


def srgb_lin(c):
    c = c / 255.0
    return np.where(c <= 0.04045, c / 12.92, ((c + 0.055) / 1.055) ** 2.4)


def luminance(rgb):
    r, g, b = srgb_lin(np.asarray(rgb, dtype=np.float64))
    return 0.2126 * r + 0.7152 * g + 0.0722 * b


def contrast(rgb, bg):
    a, b = luminance(rgb), luminance(bg)
    hi, lo = max(a, b), min(a, b)
    return (hi + 0.05) / (lo + 0.05)


def run_lengths(mask, axis):
    """Per-pixel run length of the contiguous True run along `axis`."""
    m = mask if axis == 0 else mask.T
    out = np.zeros(m.shape, dtype=np.int32)
    h = m.shape[0]
    for r in range(h):
        row = m[r]
        if not row.any():
            continue
        d = np.diff(np.concatenate(([0], row.view(np.int8), [0])))
        starts = np.flatnonzero(d == 1)
        ends = np.flatnonzero(d == -1)
        for s, e in zip(starts, ends):
            out[r, s:e] = e - s
    return out if axis == 0 else out.T


def flat_rgb(rgba, bg):
    """Straight RGB of the RGBA composite over `bg` (uint8)."""
    a = rgba[..., 3:4] / 255.0
    return np.clip(rgba[..., :3] * a + bg[None, None, :] * (1 - a), 0, 255).astype(np.uint8)


def resample(flat, ow, oh, mode):
    """Resample an already-composited RGB image.

    'pil'     Pillow BILINEAR (what the brief names; Pillow scales the triangle
              support with the reduction factor, i.e. it pre-filters)
    'lanczos' Pillow LANCZOS (reference)
    '2tap'    true 2x2 bilinear point sampling — the model of an IR draw of the
              1024 px bitmap into a ~200 px box with no mipmap
    """
    h, w = flat.shape[:2]
    if mode == "2tap":
        sx = (np.arange(ow) + 0.5) * w / ow - 0.5
        sy = (np.arange(oh) + 0.5) * h / oh - 0.5
        x0 = np.clip(np.floor(sx).astype(int), 0, w - 1)
        y0 = np.clip(np.floor(sy).astype(int), 0, h - 1)
        x1 = np.clip(x0 + 1, 0, w - 1)
        y1 = np.clip(y0 + 1, 0, h - 1)
        fx = np.clip(sx - x0, 0, 1)[None, :, None]
        fy = np.clip(sy - y0, 0, 1)[:, None, None]
        src = flat.astype(np.float64)
        top = src[np.ix_(y0, x0)] * (1 - fx) + src[np.ix_(y0, x1)] * fx
        bot = src[np.ix_(y1, x0)] * (1 - fx) + src[np.ix_(y1, x1)] * fx
        return top * (1 - fy) + bot * fy
    img = Image.fromarray(flat)
    filt = Image.BILINEAR if mode == "pil" else Image.LANCZOS
    return np.asarray(img.resize((ow, oh), filt)).astype(np.float64)


def psnr(a, b):
    d = a - b
    mse = float((d.astype(np.float64) ** 2).mean())
    if mse <= 1e-12:
        return 99.0
    return float(10 * np.log10(255.0 ** 2 / mse))


def mad(a, b):
    return float(np.abs(a.astype(np.float64) - b.astype(np.float64)).mean())


def measure(arr):
    """All pixel metrics for one RGBA asset (float64 HxWx4 array)."""
    h, w = arr.shape[:2]
    alpha = arr[..., 3]
    opaque = alpha >= 250
    semi = (alpha > 8) & (alpha < 250)
    ink = alpha > 8
    rec = dict(w=w, h=h, opaque_px=int(opaque.sum()), semi_px=int(semi.sum()),
               opaque_frac=round(float(opaque.mean()), 4), semi_frac=round(float(semi.mean()), 4))

    ys, xs = np.nonzero(ink)
    if len(xs):
        x0, x1, y0, y1 = int(xs.min()), int(xs.max()), int(ys.min()), int(ys.max())
    else:
        x0 = y0 = 0
        x1, y1 = w - 1, h - 1
    rec["bbox"] = [x0, y0, x1 - x0 + 1, y1 - y0 + 1]

    base = None
    if opaque.any():
        base = arr[opaque][:, :3]
    elif semi.any():
        base = arr[semi][:, :3]
    if base is not None:
        mean = base.mean(0)
        rec["ink_mean"] = [round(float(x), 1) for x in mean]
        rec["ink_std"] = [round(float(x), 1) for x in base.std(0)]
        rec["ink_lum"] = round(float(luminance(mean)), 4)
        rec["contrast"] = {k: round(float(contrast(mean, bg)), 2)
                           for k, bg in list(LIGHT_BG.items()) + list(DARK_BG.items())}
        rec["light_best"] = round(max(rec["contrast"][k] for k in LIGHT_BG), 2)
        rec["light_best_bg"] = max(LIGHT_BG, key=lambda k: rec["contrast"][k])
        rec["dark_best"] = round(max(rec["contrast"][k] for k in DARK_BG), 2)
        rec["dark_best_bg"] = max(DARK_BG, key=lambda k: rec["contrast"][k])
        lum_all = (0.2126 * srgb_lin(base[:, 0]) + 0.7152 * srgb_lin(base[:, 1])
                   + 0.0722 * srgb_lin(base[:, 2]))
        q_lo, q_hi = float(np.quantile(lum_all, 0.10)), float(np.quantile(lum_all, 0.90))
        rec["ink_lum_p10"] = round(q_lo, 4)
        rec["ink_lum_p90"] = round(q_hi, 4)
        bgw = luminance(np.array(LIGHT_BG["white"]))
        bgd = luminance(np.array(DARK_BG["ink"]))
        rec["light_p10_contrast"] = round(float((bgw + 0.05) / (q_lo + 0.05) if bgw > q_lo
                                                else (q_lo + 0.05) / (bgw + 0.05)), 2)
        rec["dark_p90_contrast"] = round(float((q_hi + 0.05) / (bgd + 0.05) if q_hi > bgd
                                               else (bgd + 0.05) / (q_hi + 0.05)), 2)
    else:
        rec.update(ink_mean=None, ink_lum=None, contrast=None, light_best=0.0, dark_best=0.0,
                   light_best_bg=None, dark_best_bg=None)

    if opaque.any() and semi.any():
        core = arr[opaque][:, :3].mean(0)
        edge = arr[semi][:, :3]
        rec["edge_rgb"] = [round(float(x), 1) for x in edge.mean(0)]
        rec["edge_dist"] = round(float(np.sqrt(((edge.mean(0) - core) ** 2).sum())), 1)
        rec["edge_lum_mean"] = round(float(luminance(edge.mean(0))), 4)
        rec["core_lum"] = round(float(luminance(core)), 4)
        rec["edge_darker"] = bool(rec["edge_lum_mean"] < rec["core_lum"])
    else:
        rec["edge_rgb"] = None
        rec["edge_dist"] = 0.0
        rec["edge_darker"] = False

    geo = alpha >= 128
    if geo.any():
        hr = run_lengths(geo, 0)
        vr = run_lengths(geo, 1)
        thin = np.minimum(hr, vr)[geo]
        rec["stroke_min"] = int(thin.min())
        rec["stroke_p5"] = float(np.percentile(thin, 5))
        rec["stroke_p25"] = float(np.percentile(thin, 25))
    else:
        rec["stroke_min"] = rec["stroke_p5"] = rec["stroke_p25"] = 0.0

    k = 0.2 if w >= 800 else 0.4
    ow = max(8, int(round(w * k)))
    oh = max(1, int(round(h * k)))
    rec["down_scale"] = k
    rec["down_size"] = [ow, oh]
    rec["stroke_at_200"] = round(rec["stroke_p5"] * ow / w, 3)
    surface_dark = rec.get("contrast") is not None and rec["dark_best"] > rec["light_best"]
    bg = np.array(DARK_BG["ink"] if surface_dark else LIGHT_BG["white"], dtype=np.float64)
    rec["psnr_bg"] = "ink" if surface_dark else "white"

    def compare(ow, oh, tag):
        flat = flat_rgb(arr, bg)
        p2 = resample(flat, ow, oh, "2tap")
        pb = resample(flat, ow, oh, "pil")
        pl = resample(flat, ow, oh, "lanczos")
        rec[tag + "_size"] = [ow, oh]
        rec[tag + "_psnr_2tap"] = round(psnr(p2, pl), 2)
        rec[tag + "_mad_2tap"] = round(mad(p2, pl), 2)
        rec[tag + "_psnr_pil"] = round(psnr(pb, pl), 2)
        rec[tag + "_mad_pil"] = round(mad(pb, pl), 2)

    compare(ow, oh, "k")
    ow2 = 200
    oh2 = max(1, int(round(h * ow2 / w)))
    compare(ow2, oh2, "t200")
    rec["psnr_2tap"] = rec["k_psnr_2tap"]
    rec["mad_2tap"] = rec["k_mad_2tap"]
    rec["psnr_pil"] = rec["k_psnr_pil"]
    rec["mad_pil"] = rec["k_mad_pil"]
    return rec
