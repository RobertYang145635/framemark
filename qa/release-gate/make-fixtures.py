#!/usr/bin/env python3
"""Build the export-sweep photo fixtures from the bundled sample photo.

Canonical stays untouched (data/photo.png); the extra fixtures are centre crops
of data/exif.jpg so every derivative keeps the original EXIF block.  EXIF matters:
the hand cards only read EXIF from the photo when the path differs from the
card's bundled sample, and the IR renderer reads --exif-source separately.
"""
import os
import sys
from PIL import Image

RT = os.environ.get(
    "FM_RT",
    r"E:/FrameMark/execution-ui-v2-20261106/build/runs/20261117055959864/artifacts/desktop-runtime",
)
DATA = os.path.join(RT, "data")
OUT = os.environ.get("FM_FIXTURES", r"E:/FrameMark/qa/release-gate-20261117/fixtures")


def centre_crop(im, ratio):
    w, h = im.size
    target_w = int(h * ratio)
    if target_w <= w:
        x = (w - target_w) // 2
        return im.crop((x, 0, x + target_w, h))
    target_h = int(w / ratio)
    y = (h - target_h) // 2
    return im.crop((0, y, w, y + target_h))


def main():
    os.makedirs(OUT, exist_ok=True)
    src = Image.open(os.path.join(DATA, "exif.jpg"))
    exif = src.info.get("exif")
    if not exif:
        sys.exit("fixture source has no EXIF block")
    plan = [
        ("portrait.jpg", 3 / 4),
        ("square.jpg", 1.0),
        ("wide.jpg", 16 / 9),
    ]
    for name, ratio in plan:
        out = os.path.join(OUT, name)
        im = centre_crop(src, ratio)
        im.save(out, "JPEG", quality=95, subsampling=0, exif=exif)
        with Image.open(out) as check:
            ok = check.info.get("exif") is not None
            print(f"{name} {check.size[0]}x{check.size[1]} exif={ok} bytes={os.path.getsize(out)}")
    print("fixtures ->", OUT)


if __name__ == "__main__":
    main()
