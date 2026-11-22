#!/usr/bin/env python3
"""Re-render the 187 library previews with one sample photo.

Facts this script encodes (2026-11, rc57):
  * the app reads `previews/<cardId>.webp` (480 long edge, WebP q92);
  * `previews.json` exists twice — `engine/data/` and the composeResources copy —
    and both must stay byte-identical in the fields the app reads;
  * IR cards render with `e2b --style <relative path>`, hand cards with
    `e4hand render --card <id>`;
  * the sample photo must carry EXIF, otherwise hand-card footers lose the
    brand / model / exposure rows (that is exactly what happened when the
    mountain sample was rebuilt from a render: it had no EXIF).

Example (mountain sample rebuilt from the surviving 1440-wide render):

  python scripts/qa/rerender-previews.py \
    --data "E:/FrameMark/execution-ui-v2-20261106/build/runs/<id>/artifacts/desktop-runtime/data" \
    --sample "D:/dev/tmp/mountain-sample.jpg"
"""
import argparse
import hashlib
import json
import os
import subprocess
import sys

from PIL import Image


def run(data, cmd):
    proc = subprocess.run(cmd, cwd=data, capture_output=True, text=True)
    if proc.returncode != 0:
        return f"{proc.returncode}: {proc.stdout[-300:]} {proc.stderr[-300:]}"
    return None


def render(data, backend, style_rel, sample, png):
    report = png + ".json"
    if backend == "ir":
        cmd = [
            os.path.join(data, "..", "bin", "e2b.exe"),
            "--style", style_rel,
            "--photo", sample,
            "--exif-source", "exif.jpg",
            "--fonts", "fonts",
            "--decoded", "decoded",
            "--assets", "assets",
            "--t1b", ".",
            "--preview-max-edge", "480",
            "--out", png,
            "--report", report,
        ]
    else:
        cmd = [
            os.path.join(data, "..", "bin", "e4hand.exe"), "render",
            "--card", os.path.basename(png)[:-4],
            "--cards", "hand/cards.json",
            "--hand", "hand",
            "--fonts", "fonts",
            "--extra-fonts", "e4-extra-fonts",
            "--photo", sample,
            "--exif-source", "exif.jpg",
            "--preview-max-edge", "480",
            "--out", png,
            "--report", report,
        ]
    return run(data, cmd)


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--data", required=True, help="packaged desktop-runtime/data directory")
    parser.add_argument("--sample", required=True, help="sample photo (must carry EXIF)")
    parser.add_argument("--repo", default=os.path.dirname(os.path.dirname(os.path.dirname(
        os.path.abspath(__file__)))))
    parser.add_argument("--manifest", default=None)
    parser.add_argument("--work", default=None)
    args = parser.parse_args()

    repo = args.repo
    manifest = args.manifest or os.path.join(repo, "bridge/desktop/cards-175.json")
    work = args.work or os.path.join(repo, "build", "preview-batch")
    preview_dir = os.path.join(
        repo, "ui/composeApp/src/commonMain/composeResources/files/previews")
    os.makedirs(work, exist_ok=True)
    os.makedirs(preview_dir, exist_ok=True)

    cards = json.load(open(manifest, encoding="utf-8"))["cards"]
    rows, failures = {}, []
    for i, card in enumerate(cards, 1):
        cid = card["cardId"]
        png = os.path.join(work, cid + ".png")
        style_rel = (card.get("style") or "").replace("data/", "", 1)
        error = render(args.data, card.get("backend"), style_rel, args.sample, png)
        if error or not os.path.isfile(png):
            failures.append((cid, error))
            continue
        im = Image.open(png).convert("RGB")
        if max(im.size) > 480:
            k = 480 / max(im.size)
            im = im.resize((round(im.width * k), round(im.height * k)), Image.LANCZOS)
        target = os.path.join(preview_dir, cid + ".webp")
        im.save(target, "WEBP", quality=92, method=6)
        blob = open(target, "rb").read()
        rows[cid] = {
            "path": f"previews/{cid}.webp",
            "sha256": hashlib.sha256(blob).hexdigest(),
            "bytes": len(blob),
            "format": "webp",
            "width": im.width,
            "height": im.height,
        }
        os.remove(png)
        os.remove(png + ".json")
        if i % 20 == 0:
            print(f"rendered {i}/{len(cards)}", flush=True)

    for rel in ("engine/data/previews.json",
                "ui/composeApp/src/commonMain/composeResources/files/data/previews.json"):
        path = os.path.join(repo, rel)
        doc = json.load(open(path, encoding="utf-8"))
        hit = 0
        for row in doc["rows"]:
            upd = rows.get(row.get("cardId"))
            if upd:
                row.update(upd)
                row["layer"] = "rendered"
                hit += 1
        with open(path, "w", encoding="utf-8", newline="\n") as handle:
            json.dump(doc, handle, ensure_ascii=False, indent=2)
            handle.write("\n")
        print(rel, "rows updated", hit)

    print("rendered", len(rows), "failed", len(failures))
    for cid, why in failures[:10]:
        print("  FAIL", cid, why)
    return 1 if failures else 0


if __name__ == "__main__":
    sys.exit(main())
