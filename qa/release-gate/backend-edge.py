#!/usr/bin/env python3
"""FrameMark release gate D (engines): hostile inputs through the shipped CLIs.

Every case runs the exact engine binaries the app launches.  The gate asserts
that a bad input produces a clean non-zero exit with a diagnostic — never a
Rust panic, never a silent success, never an empty/corrupt PNG.  Decoder
coverage is measured with the real sample photo re-encoded into the formats a
user can import.
"""
import json
import os
import random
import shutil
import subprocess
import sys
import time

from PIL import Image

RT = os.environ.get(
    "FM_RT",
    r"E:/FrameMark/execution-ui-v2-20261106/build/runs/20261117055959864/artifacts/desktop-runtime",
)
OUT = os.environ.get("FM_EDGE_OUT", r"E:/FrameMark/qa/release-gate-20261117/backend")
DATA = os.path.join(RT, "data")
E2B = os.environ.get("FM_E2B") or os.path.join(RT, "bin", "e2b.exe")
E4 = os.environ.get("FM_E4") or os.path.join(RT, "bin", "e4hand.exe")
STYLE = os.path.join(DATA, "styles", "oppo-gallery", "hassel_text_style_2.json")
CARDS = os.path.join(DATA, "hand", "cards.json")

PANIC_MARKERS = ["panicked at", "thread 'main' panicked", "stack overflow", "RUST_BACKTRACE"]


def setup():
    fixtures = os.path.join(OUT, "fixtures")
    os.makedirs(fixtures, exist_ok=True)
    src = Image.open(os.path.join(DATA, "photo.png")).convert("RGB")
    src.thumbnail((1600, 1600), Image.LANCZOS)
    exif_bytes = Image.open(os.path.join(DATA, "exif.jpg")).info.get("exif")

    def save(name, img, fmt, **kw):
        p = os.path.join(fixtures, name)
        img.save(p, fmt, **kw)
        return p

    photo_png = save("photo.png", src, "PNG")
    photo_jpg = save("photo.jpg", src, "JPEG", quality=92, exif=exif_bytes or b"")
    photo_webp = save("photo.webp", src, "WEBP", quality=92)
    photo_tiff = save("photo.tiff", src, "TIFF", compression="tiff_deflate")
    photo_gray = save("gray.png", src.convert("L"), "PNG")
    photo_16 = save("16bit.png", src.convert("I;16"), "PNG")
    photo_cmyk = save("cmyk.jpg", src.convert("CMYK") if src.mode == "RGB" else src, "JPEG", quality=90)
    photo_rgba = save("alpha.png", src.convert("RGBA"), "PNG")
    # A file with an EXIF orientation tag: users' phone photos are rotated this way.
    rotated_bytes = None
    try:
        ex = Image.Exif()
        ex[274] = 6  # rotate 90 CW
        rotated_bytes = ex.tobytes()
    except Exception:
        rotated_bytes = None
    photo_rot = save("rotated.jpg", src, "JPEG", quality=92, exif=rotated_bytes or b"")

    tiny = save("tiny.png", Image.new("RGB", (2, 2), (200, 30, 30)), "PNG")
    blank = save("blank.png", Image.new("RGB", (1600, 1200), (255, 255, 255)), "PNG")
    huge_path = os.path.join(fixtures, "huge.png")
    huge = Image.new("RGB", (8000, 8000))
    # cheap gradient so the file compresses but the decoder really allocates
    band = Image.new("RGB", (8000, 1))
    for x in range(8000):
        band.putpixel((x, 0), (x % 256, (x * 3) % 256, (x * 7) % 256))
    for y in range(8000):
        huge.paste(band, (0, y))
    huge.save(huge_path, "PNG")

    with open(os.path.join(fixtures, "corrupt.jpg"), "wb") as fh:
        fh.write(bytes(random.Random(7).getrandbits(8) for _ in range(65536)))
    with open(os.path.join(fixtures, "empty.jpg"), "wb") as fh:
        fh.write(b"")
    with open(os.path.join(DATA, "photo.png"), "rb") as fh:
        raw = fh.read(400)
    with open(os.path.join(fixtures, "truncated.png"), "wb") as fh:
        fh.write(raw)
    with open(os.path.join(fixtures, "style-bad.json"), "w", encoding="utf-8") as fh:
        fh.write("{ this is not json")
    with open(os.path.join(fixtures, "style-empty.json"), "w", encoding="utf-8") as fh:
        fh.write("{}")
    with open(os.path.join(fixtures, "design-bad.json"), "w", encoding="utf-8") as fh:
        fh.write('{"text":{"fontScale":"huge"}}')
    with open(os.path.join(fixtures, "cards-bad.json"), "w", encoding="utf-8") as fh:
        fh.write("not-json")
    cards = json.load(open(CARDS, encoding="utf-8"))
    broken = json.loads(json.dumps(cards))
    broken["cards"] = broken["cards"][:1]
    broken["cards"][0]["design"] = "designs/does-not-exist.json"
    with open(os.path.join(fixtures, "cards-missing-design.json"), "w", encoding="utf-8") as fh:
        json.dump(broken, fh, ensure_ascii=False)

    unicode_dir = os.path.join(fixtures, "路径 带空格")
    os.makedirs(unicode_dir, exist_ok=True)
    unicode_photo = os.path.join(unicode_dir, "照片 样本.jpg")
    shutil.copyfile(photo_jpg, unicode_photo)

    return {
        "png": photo_png, "jpg": photo_jpg, "webp": photo_webp, "tiff": photo_tiff,
        "gray": photo_gray, "i16": photo_16, "cmyk": photo_cmyk, "alpha": photo_rgba,
        "rotated": photo_rot, "tiny": tiny, "blank": blank, "huge": huge_path,
        "corrupt": os.path.join(fixtures, "corrupt.jpg"),
        "empty": os.path.join(fixtures, "empty.jpg"),
        "truncated": os.path.join(fixtures, "truncated.png"),
        "badstyle": os.path.join(fixtures, "style-bad.json"),
        "emptystyle": os.path.join(fixtures, "style-empty.json"),
        "baddesign": os.path.join(fixtures, "design-bad.json"),
        "badcards": os.path.join(fixtures, "cards-bad.json"),
        "missingdesign": os.path.join(fixtures, "cards-missing-design.json"),
        "unicode": unicode_photo,
    }


def base_e2b(photo, out, extra=None):
    args = [
        E2B,
        "--style", STYLE,
        "--photo", photo,
        "--exif-source", os.path.join(DATA, "exif.jpg"),
        "--fonts", os.path.join(DATA, "fonts"),
        "--decoded", os.path.join(DATA, "decoded"),
        "--assets", os.path.join(DATA, "assets"),
        "--t1b", DATA,
        "--out", out,
        "--report", out + ".json",
    ]
    return args + (extra or [])


def base_e4(photo, out, extra=None):
    args = [
        E4, "render",
        "--card", "blur-classic",
        "--cards", CARDS,
        "--hand", os.path.join(DATA, "hand"),
        "--fonts", os.path.join(DATA, "fonts"),
        "--extra-fonts", os.path.join(DATA, "e4-extra-fonts"),
        "--out", out,
        "--report", out + ".json",
    ]
    if photo:
        args += ["--photo", photo]
    return args + (extra or [])


def override(argv, pairs):
    out = list(argv)
    for flag, value in pairs:
        if flag in out:
            out[out.index(flag) + 1] = value
        else:
            out += [flag, value]
    return out


def cases(fx):
    out = os.path.join(OUT, "out")
    os.makedirs(out, exist_ok=True)
    o = lambda name: os.path.join(out, name + ".png")

    rows = [
        # happy-path decoder coverage
        ("e2b png", base_e2b(fx["png"], o("e2b-png")), 90, "ok"),
        ("e2b jpeg", base_e2b(fx["jpg"], o("e2b-jpg")), 90, "ok"),
        ("e2b webp (no codec; app normalises)", base_e2b(fx["webp"], o("e2b-webp")), 90, "fail"),
        ("e2b tiff (no codec; app normalises)", base_e2b(fx["tiff"], o("e2b-tiff")), 90, "fail"),
        ("e2b grayscale", base_e2b(fx["gray"], o("e2b-gray")), 90, "ok"),
        ("e2b 16-bit", base_e2b(fx["i16"], o("e2b-i16")), 90, "ok"),
        ("e2b cmyk jpeg", base_e2b(fx["cmyk"], o("e2b-cmyk")), 90, "ok"),
        ("e2b rgba", base_e2b(fx["alpha"], o("e2b-alpha")), 90, "ok"),
        ("e2b exif rotated", base_e2b(fx["rotated"], o("e2b-rotated")), 90, "ok"),
        ("e2b 2x2 photo", base_e2b(fx["tiny"], o("e2b-tiny")), 90, "ok"),
        ("e2b blank white", base_e2b(fx["blank"], o("e2b-blank")), 90, "ok"),
        ("e2b unicode path", base_e2b(fx["unicode"], o("e2b-unicode")), 90, "ok"),
        ("e4 jpg", base_e4(fx["jpg"], o("e4-jpg")), 90, "ok"),
        ("e4 webp (no codec; app normalises)", base_e4(fx["webp"], o("e4-webp")), 90, "fail"),
        ("e4 tiff (no codec; app normalises)", base_e4(fx["tiff"], o("e4-tiff")), 90, "fail"),
        ("e4 grayscale", base_e4(fx["gray"], o("e4-gray")), 90, "ok"),
        ("e4 rgba", base_e4(fx["alpha"], o("e4-alpha")), 90, "ok"),
        ("e4 unicode path", base_e4(fx["unicode"], o("e4-unicode")), 90, "ok"),
        # hostile / broken input
        ("e2b missing photo", base_e2b(os.path.join(DATA, "nope.png"), o("x1")), 60, "fail"),
        ("e2b empty file", base_e2b(fx["empty"], o("x2")), 60, "fail"),
        ("e2b corrupt jpeg", base_e2b(fx["corrupt"], o("x3")), 60, "fail"),
        ("e2b truncated png", base_e2b(fx["truncated"], o("x4")), 60, "any"),
        ("e2b corrupt style", override(base_e2b(fx["png"], o("x5")), [("--style", fx["badstyle"])]), 60, "fail"),
        ("e2b empty style", override(base_e2b(fx["png"], o("x6")), [("--style", fx["emptystyle"])]), 60, "fail"),
        ("e2b missing style", override(base_e2b(fx["png"], o("x7")), [("--style", os.path.join(DATA, "nope.json"))]), 60, "fail"),
        ("e2b font dir missing", override(base_e2b(fx["png"], o("x8")), [("--fonts", os.path.join(DATA, "nope"))]), 60, "any"),
        ("e4 empty file", base_e4(fx["empty"], o("y1")), 60, "fail"),
        ("e4 corrupt jpeg", base_e4(fx["corrupt"], o("y2")), 60, "fail"),
        ("e4 truncated png", base_e4(fx["truncated"], o("y3")), 60, "any"),
        ("e4 corrupt cards", override(base_e4(fx["jpg"], o("y4")), [("--cards", fx["badcards"])]), 60, "fail"),
        ("e4 missing design", override(base_e4(fx["jpg"], o("y5")), [("--cards", fx["missingdesign"]), ("--card", "blur-classic")]), 60, "fail"),
        ("e4 unknown card", override(base_e4(fx["jpg"], o("y6")), [("--card", "no-such-card")]), 60, "fail"),
        ("e4 bad preview edge", base_e4(fx["jpg"], o("y7"), ["--preview-source-edge", "abc"]), 60, "fail"),
        ("e4 font dir missing", override(base_e4(fx["jpg"], o("y8")), [("--fonts", os.path.join(DATA, "nope"))]), 60, "any"),
        # resource-heavy but valid
        ("e2b 8000x8000", base_e2b(fx["huge"], o("z1")), 240, "ok"),
    ]
    return rows


def run_case(name, argv, timeout, expect):
    started = time.time()
    try:
        res = subprocess.run(argv, cwd=DATA, capture_output=True, timeout=timeout)
        code = res.returncode
        stdout = res.stdout.decode("utf-8", "replace")
        stderr = res.stderr.decode("utf-8", "replace")
        timedout = False
    except subprocess.TimeoutExpired as exc:
        code = -9
        stdout = (exc.stdout or b"").decode("utf-8", "replace")
        stderr = (exc.stderr or b"").decode("utf-8", "replace")
        timedout = True
    ms = int((time.time() - started) * 1000)
    panicked = [m for m in PANIC_MARKERS if m in stderr or m in stdout]
    out_path = argv[argv.index("--out") + 1] if "--out" in argv else None
    produced = bool(out_path and os.path.exists(out_path) and os.path.getsize(out_path) > 0)
    ok = True
    reasons = []
    if timedout:
        ok = False; reasons.append("timeout")
    if panicked:
        ok = False; reasons.append("panic marker: " + panicked[0])
    if expect == "ok":
        if code != 0:
            ok = False; reasons.append(f"exit={code}")
        if not produced:
            ok = False; reasons.append("no png")
        if out_path and produced:
            try:
                with Image.open(out_path) as im:
                    im.verify()
            except Exception as exc:
                ok = False; reasons.append(f"png verify: {exc}")
    elif expect == "fail":
        if code == 0:
            ok = False; reasons.append("expected failure but exit=0")
    else:
        if code == 0 and not produced:
            ok = False; reasons.append("exit=0 without output")
    # a failure must say something; silence is a bug for every mode
    if code != 0 and not (stderr.strip() or stdout.strip()):
        ok = False; reasons.append("no diagnostic text")
    return {
        "name": name, "argv": [os.path.basename(a) if a == argv[0] else a for a in argv],
        "expect": expect, "exit": code, "ms": ms, "ok": ok, "reasons": reasons,
        "png": produced, "stderr_tail": stderr.strip().splitlines()[-3:],
        "stdout_tail": stdout.strip().splitlines()[-2:],
    }


def main():
    os.makedirs(OUT, exist_ok=True)
    print("building fixtures ...")
    fx = setup()
    rows = cases(fx)
    results = []
    for name, argv, timeout, expect in rows:
        print(f"  {name} ...", end="", flush=True)
        row = run_case(name, argv, timeout, expect)
        results.append(row)
        print(f" {'OK' if row['ok'] else 'FAIL'} exit={row['exit']} {row['ms']}ms")
    bad = [r for r in results if not r["ok"]]
    summary = {
        "when": time.strftime("%Y-%m-%dT%H:%M:%S"),
        "engines": {
            "e2b": {"path": E2B, "sha256": __import__("hashlib").sha256(open(E2B, "rb").read()).hexdigest()},
            "e4hand": {"path": E4, "sha256": __import__("hashlib").sha256(open(E4, "rb").read()).hexdigest()},
        },
        "total": len(results), "ok": len(results) - len(bad), "failed": len(bad),
        "cases": results,
    }
    with open(os.path.join(OUT, "backend-report.json"), "w", encoding="utf-8") as fh:
        json.dump(summary, fh, ensure_ascii=False, indent=2)
    lines = [
        "# FrameMark 引擎边界校验", "",
        f"- 时间：{summary['when']}",
        f"- 用例：{summary['total']}，通过 {summary['ok']}，失败 {summary['failed']}", "",
        "| 用例 | 预期 | 退出码 | 耗时 | 结果 |", "| --- | --- | --- | --- | --- |",
    ]
    for r in results:
        lines.append(f"| {r['name']} | {r['expect']} | {r['exit']} | {r['ms']}ms | {'PASS' if r['ok'] else 'FAIL: ' + '; '.join(r['reasons'])} |")
    if bad:
        lines += ["", "## 失败详情", ""]
        for r in bad:
            lines.append(f"- **{r['name']}** exit={r['exit']} reasons={r['reasons']}")
            for line in r["stderr_tail"]:
                lines.append(f"  - stderr: `{line[:300]}`")
    with open(os.path.join(OUT, "backend-report.md"), "w", encoding="utf-8") as fh:
        fh.write("\n".join(lines) + "\n")
    print(f"backend: {summary['ok']}/{summary['total']} ok, {summary['failed']} failed -> {OUT}")
    return 0 if not bad else 2


if __name__ == "__main__":
    sys.exit(main())
