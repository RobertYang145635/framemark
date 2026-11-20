# -*- coding: utf-8 -*-
"""Step 2a: metric extraction for the rendered PNG batch (files/logos/*.png).

Joins each bitmap to the catalog (assetKey == file stem), then runs metrics.measure().
Reads only; writes JSON.
"""
import argparse, hashlib, json, os, sys

import numpy as np
from PIL import Image

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import metrics as M  # noqa: E402

ROOT = r"E:/FrameMark/work/ui-v2"
LOGO_DIR = os.path.join(ROOT, "ui/composeApp/src/commonMain/composeResources/files/logos")
CAT = os.path.join(ROOT, "ui/composeApp/src/commonMain/composeResources/files/data/logos.json")

# stems that do not follow <brandKey>_<variant> (catalog keys vs. file names)
STEM_OVERRIDE = {
    "hassel_h": "hasu", "hassel_h_white_official": "hasu",
    "hassel_wordmark": "hasu", "hasu_long": "hasu",
    "huawe": "huawei", "xiaomi_loing": "xiaomi",
    "lumix": "panasionic",
    "canon": "canon", "canon_eos": "canon", "canon_red": "canon",
    "zeiss": "caisi", "laika": "laika", "laika2": "laika", "laika_long": "laika",
}
NON_BRAND = {"logo", "logo_empty"}


def load_catalog():
    cat = json.load(open(CAT, encoding="utf-8"))
    brands = {b["key"]: b for b in cat["brands"]}
    cut_by_asset = {}
    for v in cat["versions"]:
        for c in v["cuts"]:
            cut_by_asset.setdefault(c["assetKey"], (v, c))
    return cat, brands, cut_by_asset


def brand_for(stem, brands):
    if stem in NON_BRAND or stem.startswith("hand-"):
        return None
    cand = [stem]
    for suffix in ("_white_official", "_official", "_black", "_white", "_red",
                   "_blue", "_green", "_yellow", "_long"):
        if stem.endswith(suffix):
            cand.append(stem[: -len(suffix)])
    for c in cand:
        if c in STEM_OVERRIDE:
            return STEM_OVERRIDE[c]
    best = None
    for c in cand:
        for k in brands:
            if c == k or c.startswith(k + "_"):
                if best is None or len(k) > len(best):
                    best = k
    return best


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--out", required=True)
    args = ap.parse_args()
    cat, brands, cut_by_asset = load_catalog()

    pngs = sorted(f for f in os.listdir(LOGO_DIR) if f.endswith(".png"))
    out, hashes = [], {}
    for fn in pngs:
        stem = fn[:-4]
        im = Image.open(os.path.join(LOGO_DIR, fn)).convert("RGBA")
        arr = np.asarray(im).astype(np.float64)
        rec = dict(stem=stem, file=fn, source="png")
        rec.update(M.measure(arr))
        h_db = hashlib.sha256(np.ascontiguousarray(arr.astype(np.uint8))).hexdigest()
        rec["dup_of"] = hashes.get(h_db)
        hashes.setdefault(h_db, stem)

        bkey = brand_for(stem, brands)
        rec["brandKey"] = bkey
        if bkey and bkey in brands:
            b = brands[bkey]
            rec["label"], rec["cn"], rec["group"] = b.get("label"), b.get("cn"), b.get("group")
        else:
            rec["label"] = rec["cn"] = rec["group"] = None
        cut = cut_by_asset.get(stem)
        rec["cat"] = None if not cut else dict(
            surface=cut[1]["surface"], ink=cut[1]["ink"], status=cut[1]["status"],
            version=cut[0]["id"], declared=[cut[1]["declaredWidth"], cut[1]["declaredHeight"]],
            actual=[cut[1]["actualWidth"], cut[1]["actualHeight"]])
        out.append(rec)

    json.dump(dict(root=ROOT, catalog=CAT, n=len(out), cuts=out),
              open(args.out, "w", encoding="utf-8"), ensure_ascii=False, indent=1)
    print("wrote", args.out, len(out), "assets")


if __name__ == "__main__":
    main()
