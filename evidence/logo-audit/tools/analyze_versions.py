# -*- coding: utf-8 -*-
"""Step 2b: metric extraction for every catalog cut's packaged WebP (files/logov).

The UI/renderer stages `readCutBytes(assetKey)` (the logov webp) as a PNG for the
engine, so these 474 cuts are renderable too — the 361 `ver:` cuts are the only
art the 41 brands without a `files/logos` baseline have.

Reads only; writes JSON.
"""
import argparse, json, os, sys

import numpy as np
from PIL import Image

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import metrics as M  # noqa: E402

ROOT = r"E:/FrameMark/work/ui-v2"
LOGOV = os.path.join(ROOT, "ui/composeApp/src/commonMain/composeResources/files/logov")
CAT = os.path.join(ROOT, "ui/composeApp/src/commonMain/composeResources/files/data/logos.json")


def packaged_name(resolved):
    marker = "/assets/"
    i = resolved.index(marker)
    return resolved[i + len(marker):].replace("/", "__")


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--out", required=True)
    args = ap.parse_args()
    cat = json.load(open(CAT, encoding="utf-8"))
    brands = {b["key"]: b for b in cat["brands"]}
    out = []
    for v in cat["versions"]:
        for c in v["cuts"]:
            pn = packaged_name(c["resolvedPath"])
            p = os.path.join(LOGOV, pn)
            with Image.open(p) as im:
                arr = np.asarray(im.convert("RGBA")).astype(np.float64)
            rec = dict(stem=c["assetKey"], file=pn, source="webp",
                       brandKey=v["brandKey"], versionId=v["id"], cutId=c["id"],
                       label=brands.get(v["brandKey"], {}).get("label"),
                       cn=brands.get(v["brandKey"], {}).get("cn"),
                       group=brands.get(v["brandKey"], {}).get("group"),
                       cat=dict(surface=c["surface"], ink=c["ink"], status=c["status"],
                                version=v["id"],
                                declared=[c["declaredWidth"], c["declaredHeight"]],
                                actual=[c["actualWidth"], c["actualHeight"]]))
            rec.update(M.measure(arr))
            rec["dup_of"] = None
            out.append(rec)

    json.dump(dict(root=ROOT, catalog=CAT, n=len(out), cuts=out),
              open(args.out, "w", encoding="utf-8"), ensure_ascii=False, indent=1)
    print("wrote", args.out, len(out), "cuts")


if __name__ == "__main__":
    main()
