# -*- coding: utf-8 -*-
"""Report builder — coverage.csv / cuts.csv / cuts-catalog-474.csv / dims-audit.csv
/ summary.json, all derived from analysis.json (166 PNG), versions.json (474 webp)
and the catalog. Reads only.

Run:  python report.py <evidence-dir>
"""
import csv, json, os, sys
import numpy as np

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import metrics as M  # noqa: E402

ROOT = r"E:/FrameMark/work/ui-v2"
CAT = os.path.join(ROOT, "ui/composeApp/src/commonMain/composeResources/files/data/logos.json")
LOGO_DIR = os.path.join(ROOT, "ui/composeApp/src/commonMain/composeResources/files/logos")
HERE = os.path.dirname(os.path.abspath(__file__))


def jpath(name):
    """Metrics JSON lives next to the script, or in ./data for the published tree."""
    for p in (os.path.join(HERE, name), os.path.join(HERE, "data", name),
              os.path.join(HERE, os.pardir, "data", name)):
        if os.path.exists(p):
            return p
    raise SystemExit("missing %s (run the tools/analyze*.py steps first)" % name)

WEAK = 4.0          # "marginal" band: usable but thin
THRESH = 3.0        # WCAG large-text / this audit's threshold
PSNR_RISK = 26.0


def bbox_str(b):
    return "%d,%d,%dx%d" % (b[0], b[1], b[2], b[3])


def rgb_str(v):
    return "-".join(str(int(x)) for x in v) if v else ""


def polarity(c):
    if c["light_best"] >= THRESH and c["dark_best"] >= THRESH:
        return "both"
    if c["light_best"] >= THRESH:
        return "light"
    if c["dark_best"] >= THRESH:
        return "dark"
    return "none"


def main():
    outdir = sys.argv[1] if len(sys.argv) > 1 else HERE
    os.makedirs(outdir, exist_ok=True)
    png = json.load(open(jpath("analysis.json"), encoding="utf-8"))["cuts"]
    webp = json.load(open(jpath("versions.json"), encoding="utf-8"))["cuts"]
    dims = json.load(open(jpath("dims.json"), encoding="utf-8"))
    cat = json.load(open(CAT, encoding="utf-8"))
    brands = cat["brands"]
    by_key = {b["key"]: b for b in brands}

    versions_by_brand = {}
    cut_by_asset = {}
    for v in cat["versions"]:
        versions_by_brand.setdefault(v["brandKey"], []).append(v)
        for c in v["cuts"]:
            cut_by_asset.setdefault(c["assetKey"], (v, c))

    png_by_stem = {c["stem"]: c for c in png}
    webp_by_asset = {c["stem"]: c for c in webp}

    # ---------------------------------------------------------------- unified pool
    # One entry per distinct asset: the baseline PNG when it exists (it is the file
    # the engine's brand registry draws), else the catalog webp cut.
    pool = {}
    for c in webp:
        pool[c["stem"]] = dict(c, src="webp")
    for c in png:
        key = c["stem"]
        if key in pool:                     # same art as a brand-key catalog cut
            merged = dict(c)
            merged["src"] = "png"           # baseline wins for measurements
            merged["brandKey"] = c["brandKey"] or pool[key]["brandKey"]
            pool[key] = merged
        else:
            pool[key] = dict(c, src="png")
    assets = list(pool.values())

    stems_by_brand = {}
    for a in assets:
        if a["brandKey"]:
            stems_by_brand.setdefault(a["brandKey"], []).append(a)

    # ------------------------------------------------------------------ autoCutKey
    def png_size(stem):
        p = os.path.join(LOGO_DIR, stem + ".png")
        if not os.path.isfile(p):
            return None
        from PIL import Image
        with Image.open(p) as im:
            return im.size

    def tone_rank(stem, dark):
        white, black = stem.endswith("_white"), stem.endswith("_black")
        if dark:
            return 0 if white else (2 if black else 1)
        return 0 if black else (2 if white else 1)

    def choose_brand_slot(key, dark):
        """engine/e2b/src/brand.rs choose_brand_slot()"""
        cands = [s for s in png_by_stem if s == key or s.startswith(key + "_")]
        cands = [s for s in cands if not (dark and s.endswith("_black"))
                 and not (not dark and s.endswith("_white"))]
        if not cands:
            return None
        def aspect(s):
            sz = png_size(s)
            return sz[0] / sz[1] if sz and sz[1] else 0.0
        cands.sort(key=lambda s: (-(aspect(s) >= 1.6), tone_rank(s, dark), -aspect(s)))
        return cands[0]

    def auto_cut_key(b):
        for stem in (b["key"] + "_black", b["key"] + "_white"):
            for v in versions_by_brand.get(b["key"], []):
                for c in v["cuts"]:
                    if c["assetKey"] == stem:
                        return stem, "brand-cut"
        for v in versions_by_brand.get(b["key"], []):
            if v["id"] == b.get("canonicalVersion") and v["cuts"]:
                return v["cuts"][0]["assetKey"], "canonical-first-cut"
        vs = versions_by_brand.get(b["key"], [])
        if vs and vs[0]["cuts"]:
            return vs[0]["cuts"][0]["assetKey"], "first-cut"
        return None, "none"

    # ---------------------------------------------------------------- coverage rows
    cover_rows = []
    for b in brands:
        key = b["key"]
        mine = stems_by_brand.get(key, [])
        pngs = [c for c in mine if c["src"] == "png"]
        light_best = max([c["light_best"] for c in mine], default=0.0)
        dark_best = max([c["dark_best"] for c in mine], default=0.0)
        light_n = sum(1 for c in mine if c["light_best"] >= THRESH)
        dark_n = sum(1 for c in mine if c["dark_best"] >= THRESH)
        weak = min(light_best, dark_best)
        cov = {"both": "ok", "light": "light-only", "dark": "dark-only",
               "none": "none"}[polarity(dict(light_best=light_best, dark_best=dark_best))]

        best_light = max(mine, key=lambda c: c["light_best"], default=None)
        best_dark = max(mine, key=lambda c: c["dark_best"], default=None)

        auto_key, auto_kind = auto_cut_key(b)
        auto_art = webp_by_asset.get(auto_key) or png_by_stem.get(auto_key)
        auto_white = auto_art["contrast"]["white"] if auto_art and auto_art["contrast"] else None
        engine_pick = choose_brand_slot(key, dark=False) if pngs else None
        engine_white = (png_by_stem[engine_pick]["contrast"]["white"]
                        if engine_pick else None)

        risk = sum(1 for c in mine
                   if c["t200_psnr_2tap"] < PSNR_RISK or c["stroke_at_200"] < 1.0)
        risk_png = sum(1 for c in pngs
                       if c["t200_psnr_2tap"] < PSNR_RISK or c["stroke_at_200"] < 1.0)

        meta_bad = [c for c in mine if c["cat"] and
                    ((c["cat"]["surface"] == "light" and c["light_best"] < THRESH) or
                     (c["cat"]["surface"] == "dark" and c["dark_best"] < THRESH))]
        notes = []
        if not pngs:
            notes.append("无基线PNG(仅目录cut)")
        if cov == "light-only":
            notes.append("缺深底可用(最高%.2f)" % dark_best)
        elif cov == "dark-only":
            notes.append("缺浅底可用(最高%.2f)" % light_best)
        elif cov == "none":
            notes.append("两侧都不可辨认")
        elif weak < WEAK:
            notes.append("一侧近临界(%.2f)" % weak)
        if auto_white is not None and auto_white < THRESH:
            notes.append("auto选出低对比%.2f" % auto_white)
        if auto_kind == "canonical-first-cut":
            notes.append("auto回退canonical首枚")
        if meta_bad:
            notes.append("极性元数据不符x%d" % len(meta_bad))
        if risk_png:
            notes.append("基线毛刺风险x%d" % risk_png)

        cover_rows.append({
            "brandKey": key, "label": b.get("label", ""), "cn": b.get("cn", ""),
            "group": b.get("group", ""),
            "浅底最高对比度": round(light_best, 2),
            "浅底可用cut数": light_n,
            "深底最高对比度": round(dark_best, 2),
            "深底可用cut数": dark_n,
            "两侧是否覆盖": cov,
            "autoCut选中项": auto_key or "",
            "autoCut在浅底对比度": round(auto_white, 2) if auto_white is not None else "",
            "高风险毛刺cut数": risk,
            "问题备注": "；".join(notes),
            # extras
            "autoCut选中方式": auto_kind,
            "autoCut实际位图": auto_art["file"] if auto_art else "",
            "autoCut对比度口径": "目标白底#FFFFFF",
            "浅底最佳资产": best_light["stem"] if best_light else "",
            "深底最佳资产": best_dark["stem"] if best_dark else "",
            "基线PNG数": len(pngs),
            "目录cut数": len(mine) - len(pngs),
            "仅靠目录cut覆盖": "是" if (mine and not pngs) else "否",
            "引擎浅底自选(choose_brand_slot)": engine_pick or "",
            "引擎自选对比白": round(engine_white, 2) if engine_white is not None else "",
            "高风险毛刺(基线PNG)": risk_png,
            "最强密度": "ok" if weak >= WEAK else ("marginal" if weak >= THRESH else "fail"),
        })

    # -------------------------------------------------------------------- cuts rows
    def tag_of(c):
        t = []
        if c["t200_psnr_2tap"] < PSNR_RISK:
            t.append("毛刺高")
        elif c["t200_psnr_2tap"] < 30:
            t.append("毛刺中")
        if c["t200_psnr_pil"] < PSNR_RISK:
            t.append("PIL-BILINEAR低")
        if c["stroke_at_200"] < 1.0:
            t.append("细笔画<1px")
        if c["edge_dist"] > 40:
            t.append("边色距离大")
        if c["light_best"] < THRESH:
            t.append("浅底不可辨")
        if c["dark_best"] < THRESH:
            t.append("深底不可辨")
        if c.get("dup_of"):
            t.append("重复:" + c["dup_of"])
        if c["w"] < 200:
            t.append("画布过小")
        if not c["brandKey"]:
            t.append("非品牌资产")
        if c["cat"] is None:
            t.append("目录无此cut")
        if c["cat"] and ((c["cat"]["surface"] == "light" and c["light_best"] < THRESH) or
                         (c["cat"]["surface"] == "dark" and c["dark_best"] < THRESH)):
            t.append("极性元数据不符")
        return ";".join(t)

    cut_rows = []
    for c in sorted(png, key=lambda x: (x["brandKey"] or "~", x["stem"])):
        cut_rows.append({
            "brandKey": c["brandKey"] or "",
            "assetKey": c["stem"],
            "file": c["file"],
            "surface": c["cat"]["surface"] if c["cat"] else "",
            "ink": c["cat"]["ink"] if c["cat"] else "",
            "像素尺寸": "%dx%d" % (c["w"], c["h"]),
            "墨迹bbox": bbox_str(c["bbox"]),
            "最细笔画px": round(c["stroke_p5"], 1),
            "0.2× PSNR": c["t200_psnr_2tap"],
            "半透明边RGB距离": c["edge_dist"],
            "问题标签": tag_of(c),
            "PSNR_PIL_BILINEAR": c["t200_psnr_pil"],
            "MAD_2tap": c["t200_mad_2tap"],
            "PSNR@字面0.2×": c["k_psnr_2tap"],
            "MAD@字面0.2×": c["k_mad_2tap"],
            "最细笔画@200px": round(c["stroke_at_200"], 2),
            "墨迹均值RGB": rgb_str(c["ink_mean"]),
            "墨迹亮度": c["ink_lum"],
            "对比白#FFFFFF": c["contrast"]["white"] if c["contrast"] else "",
            "对比纸白#F7F5F0": c["contrast"]["paper"] if c["contrast"] else "",
            "对比奶油#F4EFE6": c["contrast"]["cream"] if c["contrast"] else "",
            "对比石色#DCD8D0": c["contrast"]["stone"] if c["contrast"] else "",
            "对比墨色#1C1D1F": c["contrast"]["ink"] if c["contrast"] else "",
            "对比黑#000000": c["contrast"]["black"] if c["contrast"] else "",
            "浅底最高": c["light_best"], "深底最高": c["dark_best"],
            "实测极性": polarity(c),
            "半透明边RGB": rgb_str(c["edge_rgb"]),
            "边比芯暗": c["edge_darker"],
            "重复于": c.get("dup_of") or "",
            "目录declared": "%dx%d" % tuple(c["cat"]["declared"]) if c["cat"] else "",
            "目录actual": "%dx%d" % tuple(c["cat"]["actual"]) if c["cat"] else "",
        })

    cat_rows = []
    for c in webp:
        cat_rows.append({
            "brandKey": c["brandKey"], "assetKey": c["stem"], "file": c["file"],
            "versionId": c["versionId"], "surface": c["cat"]["surface"],
            "ink": c["cat"]["ink"], "像素尺寸": "%dx%d" % (c["w"], c["h"]),
            "墨迹bbox": bbox_str(c["bbox"]), "最细笔画px": round(c["stroke_p5"], 1),
            "0.2× PSNR": c["t200_psnr_2tap"], "半透明边RGB距离": c["edge_dist"],
            "问题标签": tag_of(c),
            "PSNR_PIL_BILINEAR": c["t200_psnr_pil"], "PSNR@字面0.2×": c["k_psnr_2tap"],
            "MAD@字面0.2×": c["k_mad_2tap"], "最细笔画@200px": round(c["stroke_at_200"], 2),
            "墨迹均值RGB": rgb_str(c["ink_mean"]),
            "对比白#FFFFFF": c["contrast"]["white"] if c["contrast"] else "",
            "对比纸白#F7F5F0": c["contrast"]["paper"] if c["contrast"] else "",
            "对比奶油#F4EFE6": c["contrast"]["cream"] if c["contrast"] else "",
            "对比石色#DCD8D0": c["contrast"]["stone"] if c["contrast"] else "",
            "对比墨色#1C1D1F": c["contrast"]["ink"] if c["contrast"] else "",
            "对比黑#000000": c["contrast"]["black"] if c["contrast"] else "",
            "浅底最高": c["light_best"], "深底最高": c["dark_best"], "实测极性": polarity(c),
            "目录declared": "%dx%d" % tuple(c["cat"]["declared"]),
            "目录actual": "%dx%d" % tuple(c["cat"]["actual"]),
        })

    dim_rows = []
    for r in dims["rows"]:
        dim_rows.append({
            "brandKey": r["brandKey"], "versionId": r["versionId"], "cutId": r["cutId"],
            "assetKey": r["assetKey"], "packaged": r["packaged"],
            "declared": r["declared"], "actual": r["actual"], "real": r["real"],
            "declared_vs_actual": r["declared_vs_actual"],
            "actual_vs_real": r["actual_vs_real"],
            "aspect_declared": r["aspect_declared"], "aspect_actual": r["aspect_actual"],
            "aspect_drift": round(abs((r["aspect_declared"] or 0) - (r["aspect_actual"] or 0)), 3),
        })

    def write_csv(name, rows, cols=None):
        if not rows:
            return
        cols = cols or list(rows[0].keys())
        with open(os.path.join(outdir, name), "w", encoding="utf-8-sig", newline="") as f:
            w = csv.DictWriter(f, fieldnames=cols, extrasaction="ignore")
            w.writeheader()
            for r in rows:
                w.writerow(r)
        print("wrote", name, len(rows))

    cov_cols = ["brandKey", "label", "cn", "group", "浅底最高对比度", "浅底可用cut数",
                "深底最高对比度", "深底可用cut数", "两侧是否覆盖", "autoCut选中项",
                "autoCut在浅底对比度", "高风险毛刺cut数", "问题备注",
                "autoCut选中方式", "autoCut实际位图", "autoCut对比度口径",
                "浅底最佳资产", "深底最佳资产", "基线PNG数", "目录cut数", "仅靠目录cut覆盖",
                "引擎浅底自选(choose_brand_slot)", "引擎自选对比白", "高风险毛刺(基线PNG)",
                "最强密度"]
    write_csv("coverage.csv", cover_rows, cov_cols)
    write_csv("cuts.csv", cut_rows)
    write_csv("cuts-catalog-474.csv", cat_rows)
    write_csv("dims-audit.csv", dim_rows)

    # ------------------------------------------------------------------- summary
    cov_counts = {}
    for r in cover_rows:
        cov_counts[r["两侧是否覆盖"]] = cov_counts.get(r["两侧是否覆盖"], 0) + 1
    auto_bad = [r for r in cover_rows if r["autoCut在浅底对比度"] != "" and
                float(r["autoCut在浅底对比度"]) < THRESH]
    risky_png = [c for c in png if c["t200_psnr_2tap"] < PSNR_RISK]
    risky_all = [c for c in assets if c["t200_psnr_2tap"] < PSNR_RISK]
    edge_bad = [c for c in png if c["edge_dist"] > 40]
    edge_bad_all = [c for c in assets if c["edge_dist"] > 40]
    meta_bad = [c for c in webp if (c["cat"]["surface"] == "light" and c["light_best"] < THRESH)
                or (c["cat"]["surface"] == "dark" and c["dark_best"] < THRESH)]
    ink_mismatch = [c for c in webp if (c["cat"]["ink"] == "dark" and c["light_best"] < THRESH)
                    or (c["cat"]["ink"] == "light" and c["dark_best"] < THRESH)]
    dups = {}
    for c in png:
        if c.get("dup_of"):
            dups.setdefault(c["dup_of"], []).append(c["stem"])

    summary = dict(
        assets_png=len(png), cuts_catalog=len(webp), assets_unified=len(assets),
        brands=len(brands), brands_with_baseline=len([r for r in cover_rows if r["基线PNG数"] > 0]),
        brands_without_baseline_png=[r["brandKey"] for r in cover_rows
                                       if r["仅靠目录cut覆盖"] == "是"],
        coverage_list={k: [r["brandKey"] for r in cover_rows if r["两侧是否覆盖"] == k]
                       for k in ("light-only", "dark-only", "none")},
        coverage=cov_counts,
        coverage_lists={k: [r["brandKey"] for r in cover_rows if r["两侧是否覆盖"] == k]
                        for k in ("light-only", "dark-only", "none")},
        marginal=[(r["brandKey"], r["浅底最高对比度"], r["深底最高对比度"])
                  for r in cover_rows if 3.0 <= min(r["浅底最高对比度"], r["深底最高对比度"]) < WEAK],
        psnr_pil_below26_png=sum(1 for c in png if c["t200_psnr_pil"] < PSNR_RISK),
        psnr_pil_below26_all=sum(1 for c in assets if c["t200_psnr_pil"] < PSNR_RISK),
        psnr_2tap_below26_png=len(risky_png),
        psnr_2tap_below26_all=len(risky_all),
        psnr_2tap_below22_png=sum(1 for c in png if c["t200_psnr_2tap"] < 22),
        stroke_below_1px_png=sum(1 for c in png if c["stroke_at_200"] < 1.0),
        stroke_below_3px_png=sum(1 for c in png if c["stroke_at_200"] < 3.0),
        edge_over_40_png=len(edge_bad), edge_over_40_all=len(edge_bad_all),
        light_unusable_png=sum(1 for c in png if c["light_best"] < THRESH),
        dark_unusable_png=sum(1 for c in png if c["dark_best"] < THRESH),
        meta_surface_contradiction=len(meta_bad),
        ink_contradiction=len(ink_mismatch),
        meta_contradiction_list=[(c["brandKey"], c["stem"], c["cat"]["surface"],
                                  c["light_best"], c["dark_best"]) for c in meta_bad],
        auto_below3=[(r["brandKey"], r["autoCut选中项"], r["autoCut在浅底对比度"])
                     for r in auto_bad],
        auto_kind={},
        top_risk_png=[(c["stem"], c["t200_psnr_2tap"], c["t200_mad_2tap"],
                       round(c["stroke_at_200"], 2)) for c in
                      sorted(risky_png, key=lambda x: x["t200_psnr_2tap"])[:20]],
        dups=dups,
        dims=dict(declared_vs_actual=dims["declared_vs_actual"],
                  actual_vs_real=dims["actual_vs_real"]),
    )
    for r in cover_rows:
        summary["auto_kind"][r["autoCut选中方式"]] = summary["auto_kind"].get(r["autoCut选中方式"], 0) + 1
    json.dump(summary, open(os.path.join(outdir, "summary.json"), "w", encoding="utf-8"),
              ensure_ascii=False, indent=1)
    print(json.dumps(summary, ensure_ascii=False, indent=1, default=str)[:6000])


if __name__ == "__main__":
    main()
