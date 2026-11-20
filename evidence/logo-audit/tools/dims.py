# -*- coding: utf-8 -*-
"""Catalog dimension consistency: declared vs catalog-actual vs real file pixels.

Reads only. Writes dims-audit.csv + a JSON summary.
"""
import json, os, collections
import numpy as np
from PIL import Image

ROOT = r"E:/FrameMark/work/ui-v2"
CAT = os.path.join(ROOT, "ui/composeApp/src/commonMain/composeResources/files/data/logos.json")
LOGOV = os.path.join(ROOT, "ui/composeApp/src/commonMain/composeResources/files/logov")


def packaged_name(resolved):
    marker = "/assets/"
    i = resolved.index(marker)
    return resolved[i + len(marker):].replace("/", "__")


def main():
    import argparse
    ap = argparse.ArgumentParser()
    ap.add_argument("--out", default="")
    args = ap.parse_args()
    cat = json.load(open(CAT, encoding="utf-8"))
    rows = []
    cache = {}
    for v in cat["versions"]:
        for c in v["cuts"]:
            pn = packaged_name(c["resolvedPath"])
            p = os.path.join(LOGOV, pn)
            fw = fh = None
            if pn not in cache:
                if os.path.isfile(p):
                    try:
                        with Image.open(p) as im:
                            cache[pn] = im.size
                    except Exception:
                        cache[pn] = None
                else:
                    cache[pn] = None
            real = cache[pn]
            if real:
                fw, fh = real
            dw, dh = c["declaredWidth"], c["declaredHeight"]
            aw, ah = c["actualWidth"], c["actualHeight"]
            rows.append(dict(
                brandKey=v["brandKey"], versionId=v["id"], cutId=c["id"], assetKey=c["assetKey"],
                file=c["file"], resolvedPath=c["resolvedPath"], packaged=pn,
                declared=f"{dw}x{dh}", actual=f"{aw}x{ah}",
                real=f"{fw}x{fh}" if real else "",
                declared_vs_actual=("mismatch" if (dw, dh) != (aw, ah) else "ok"),
                actual_vs_real=("mismatch" if real and (aw, ah) != real else
                                ("missing" if not real else "ok")),
                aspect_declared=round(dw / dh, 3) if dh else None,
                aspect_actual=round(aw / ah, 3) if ah else None,
                surface=c["surface"], ink=c["ink"],
            ))
    out = args.out or os.path.join(os.path.dirname(os.path.abspath(__file__)), "dims.json")
    n_da = sum(1 for r in rows if r["declared_vs_actual"] == "mismatch")
    n_ar = sum(1 for r in rows if r["actual_vs_real"] == "mismatch")
    n_miss = sum(1 for r in rows if r["actual_vs_real"] == "missing")
    json.dump(dict(n=len(rows), declared_vs_actual=n_da, actual_vs_real=n_ar, missing=n_miss,
                   catalog_counts=cat["counts"], rows=rows),
              open(out, "w", encoding="utf-8"), ensure_ascii=False, indent=1)
    print("cuts", len(rows), "declared!=actual", n_da, "actual!=real", n_ar, "missing", n_miss)
    print("catalog says", cat["counts"]["declaredVsActualDimensionMismatches"])
    # aspect distortion magnitude
    d = [abs(r["aspect_declared"] - r["aspect_actual"]) for r in rows
         if r["aspect_declared"] and r["aspect_actual"]]
    print("aspect drift median %.3f p90 %.3f max %.3f" % (np.median(d), np.percentile(d, 90), max(d)))
    # biggest offenders
    big = sorted(rows, key=lambda r: -(abs(r["aspect_declared"] - r["aspect_actual"])
                                       if r["aspect_declared"] and r["aspect_actual"] else 0))[:25]
    for r in big[:10]:
        print(f"  {r['brandKey']:14s} {r['cutId']:32s} declared={r['declared']:12s} actual={r['actual']:10s} real={r['real']}")


if __name__ == "__main__":
    main()
