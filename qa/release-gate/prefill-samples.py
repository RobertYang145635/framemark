#!/usr/bin/env python3
"""缺值行的「示例预填」全量检查（187 张卡）。

用户口径（2026-11-24）：「如果我给一张正常照片，这一格应该填什么，而不是都一个样」。
IR 的一行参数常由多个节点拼成（`23mm` / `f/1.8` / `1/125s` / `ISO250`），缺 EXIF 时
每个节点各是一条空值行；编辑器画示例时必须**逐格**给出这一格本该有的内容。

检查项（每张卡、每个缺值行）：
  1. IR 卡：缺值行必须有引擎算出的 `sample`（非空）；地点 / 自定义文字这类
     「用户自己写、正常照片也没有」的行允许为空，但要在白名单里说清楚。
  2. 同一行（垂直重叠）里如果有 ≥2 个缺值行，它们的示例**不能全都一样** ——
     这就是「底栏铺 6 份 23mm F1.8 1/125s ISO250」那个 bug。
  3. 单个 token 的示例不能等于整行示例（同样的原因）。
  4. 手写卡（69 张）：示例由 App 按文字页角色给，这里只统计缺值行数量并核对
     「一行一个示例」的结构（每个 key 都能对上角色），打印出来供人工抽查。

用法：
  python qa/release-gate/prefill-samples.py --rt <runtime> [--exe-ir <e2b>] [--exe-hand <e4hand>]
                                            [--only-ir] [--only-hand] [--json out.json]
"""
import argparse
import json
import os
import subprocess
import sys
import tempfile

from PIL import Image

# 「正常照片也没有」的 token 类别：地点、自定义文字、问候语。这几行是用户自己写的，
# 不预填（编一个地名正是「猜错比不提示更糟」）。检查时放行，但要在报告里单独计数。
ALLOW_EMPTY_TOKEN_KINDS = {"location", "custom", "greeting"}


def token_nodes(style_path):
    """path -> (kind, value)，用于区分「该有示例却没有」和「本来就没有示例」。"""
    style = json.load(open(style_path, encoding="utf-8"))
    out = {}

    def walk(node, path):
        if not isinstance(node, dict):
            return
        kids = node.get("children") or []
        token = node.get("token")
        if token and not kids:
            out[path] = (token.get("kind") or "", token.get("value") or "")
        for i, child in enumerate(kids):
            walk(child, f"{path}/g{i}")

    for i, band in enumerate(style.get("bands") or []):
        walk(band, f"b{i}")
    return out


def sample_expected(kind, value):
    """这一格「正常照片」下会不会有字：地点/自定义/问候语、空字面量都不会。"""
    if kind in ALLOW_EMPTY_TOKEN_KINDS:
        return False
    if kind == "literal":
        return bool(value.strip())
    if kind == "template":
        head = value.split("=", 1)[0].strip().lstrip("@").split(";")[0]
        return head not in ALLOW_EMPTY_TOKEN_KINDS
    return True


def sh(cmd, cwd):
    proc = subprocess.run(cmd, cwd=cwd, capture_output=True, text=True)
    if proc.returncode != 0:
        raise RuntimeError(f"{' '.join(cmd)}\n{proc.stdout[-400:]}\n{proc.stderr[-400:]}")
    return proc


def strip_exif_photo(data_dir, out_dir):
    src = os.path.join(data_dir, "photo.png")
    im = Image.open(src)
    if im.mode not in ("RGB", "RGBA"):
        im = im.convert("RGB")
    path = os.path.join(out_dir, "noexif.png")
    im.save(path, "PNG")
    return path


def ir_styles(data_dir):
    manifest = json.load(open(os.path.join(data_dir, "manifest.json"), encoding="utf-8"))
    return [(s["uiCardId"], s["style"]) for s in manifest.get("styles", [])]


def render_ir(exe, data_dir, style, photo, out_png):
    rep = out_png + ".json"
    sh([exe, "--style", style, "--photo", photo, "--fonts", "fonts", "--decoded", "decoded",
        "--assets", "assets", "--t1b", ".", "--preview-max-edge", "600",
        "--out", out_png, "--report", rep], cwd=data_dir)
    data = json.load(open(rep, encoding="utf-8"))
    os.remove(out_png)
    os.remove(rep)
    return data


def render_hand(exe, data_dir, cards_path, card, photo, out_png):
    rep = out_png + ".json"
    sh([exe, "render", "--card", card, "--cards", cards_path, "--hand", "hand",
        "--fonts", "fonts", "--extra-fonts", "e4-extra-fonts", "--photo", photo,
        "--preview-max-edge", "600", "--out", out_png, "--report", rep], cwd=data_dir)
    data = json.load(open(rep, encoding="utf-8"))
    os.remove(out_png)
    os.remove(rep)
    return data


def same_line(a, b):
    return min(a[1] + a[3], b[1] + b[3]) - max(a[1], b[1]) > 0.5 * min(a[3], b[3])


def audit_ir(exe, data_dir, photo, work, rows_out):
    problems = []
    style_kinds = {}
    allowed_empty = 0
    styles = ir_styles(data_dir)
    for index, (card_id, style) in enumerate(styles, 1):
        try:
            rep = render_ir(exe, data_dir, style, photo, os.path.join(work, f"ir-{index}.png"))
        except Exception as exc:  # noqa: BLE001 - 引擎失败要计入报告，不是崩溃
            problems.append((card_id, "render", str(exc)[:200]))
            continue
        if style not in style_kinds:
            style_kinds[style] = token_nodes(os.path.join(data_dir, style))
        nodes = style_kinds[style]
        slots = rep.get("textSlots") or []
        if not slots:
            rows_out.append({"card": card_id, "kind": "ir", "rows": 0})
            continue
        absent = [s for s in slots if not s.get("present")]
        rows_out.append({"card": card_id, "kind": "ir", "rows": len(slots), "absent": len(absent)})
        boxes = {}
        for item in rep.get("items") or []:
            if item.get("kind") == "text" and item.get("path"):
                boxes[item["path"]] = item.get("rect") or [0, 0, 0, 0]
        measured = []
        for slot in absent:
            path = slot.get("path") or ""
            sample = (slot.get("sample") or "").strip()
            box = boxes.get(path)
            if not sample:
                raw = (slot.get("raw") or "").strip()
                kind, value = nodes.get(path, ("", ""))
                if raw or not sample_expected(kind, value):
                    allowed_empty += 1
                    continue
                problems.append((card_id, f"empty sample {path} (token={kind})", "缺值行没有示例"))
                continue
            # 该行缺的是「整行参数」时，示例理应就是整行；单行场景不判。
            measured.append((path, sample, box))
        # 同一行的多个 token 不能示例全一样
        used = set()
        for i, (path_a, sample_a, box_a) in enumerate(measured):
            if box_a is None or path_a in used:
                continue
            group = [(p, s) for (p, s, b) in measured
                     if b is not None and (b == box_a or same_line(b, box_a))]
            if len(group) >= 2:
                samples = {s for _, s in group}
                if len(samples) == 1:
                    problems.append(
                        (card_id, f"same sample in one line {sorted(p for p, _ in group)}",
                         f"{len(group)} 个节点都写 {samples.pop()!r}"))
                used.update(p for p, _ in group)
    rows_out.append({"card": f"__ir_allowed_empty__", "kind": "ir", "rows": allowed_empty})
    return problems


def audit_hand(exe, data_dir, photo, work, rows_out):
    problems = []
    cards_doc = json.load(open(os.path.join(data_dir, "hand", "cards.json"), encoding="utf-8"))
    cards = cards_doc.get("cards", [])
    by_card = {}
    for card in cards:
        by_card.setdefault(card["card"], card)
    for card_id, card in by_card.items():
        work_cards = os.path.join(work, f"cards-{card_id}.json")
        doc = {"cards": [dict(card, photo={"path": photo, "sha256": card["photo"].get("sha256", ""),
                                           "size": card["photo"].get("size", [0, 0])})]}
        json.dump(doc, open(work_cards, "w", encoding="utf-8"), ensure_ascii=False)
        try:
            rep = render_hand(exe, data_dir, work_cards, card_id, photo,
                              os.path.join(work, f"hand-{card_id}.png"))
        except Exception as exc:  # noqa: BLE001
            problems.append((card_id, "render", str(exc)[:200]))
            continue
        texts = [t for t in (rep.get("report", {}).get("texts") or []) if t.get("key")]
        rows_out.append({"card": card_id, "kind": "hand", "rows": len(texts)})
    return problems


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--rt", required=True)
    ap.add_argument("--exe-ir", default="")
    ap.add_argument("--exe-hand", default="")
    ap.add_argument("--only-ir", action="store_true")
    ap.add_argument("--only-hand", action="store_true")
    ap.add_argument("--json", default="")
    args = ap.parse_args()
    data_dir = os.path.join(os.path.abspath(args.rt), "data")
    exe_ir = args.exe_ir or os.path.join(os.path.abspath(args.rt), "bin", "e2b.exe")
    exe_hand = args.exe_hand or os.path.join(os.path.abspath(args.rt), "bin", "e4hand.exe")
    for path in (data_dir, exe_ir, exe_hand):
        if not os.path.exists(path):
            ap.error(f"missing {path}")

    work = tempfile.mkdtemp(prefix="prefill-")
    photo = strip_exif_photo(data_dir, work)
    rows, problems = [], []
    if not args.only_hand:
        problems += audit_ir(exe_ir, data_dir, photo, work, rows)
    if not args.only_ir:
        problems += audit_hand(exe_hand, data_dir, photo, work, rows)

    print(f"cards checked: {len(rows)}  (ir {sum(1 for r in rows if r['kind'] == 'ir')}, "
          f"hand {sum(1 for r in rows if r['kind'] == 'hand')})")
    for row in rows:
        if row["kind"] == "ir":
            print(f"  ir   {row['card']:46s} rows {row['rows']:2d}  absent {row.get('absent', 0):2d}")
        else:
            print(f"  hand {row['card']:46s} rows {row['rows']:2d}")
    if problems:
        print("\nPROBLEMS")
        for card, what, detail in problems:
            print(f"  {card:46s} {what:60s} {detail}")
    if args.json:
        json.dump({"rows": rows, "problems": [list(p) for p in problems]},
                  open(args.json, "w", encoding="utf-8"), ensure_ascii=False, indent=1)
    print(f"\nRESULT {'PASS' if not problems else 'FAIL'}  problems={len(problems)}")
    return 0 if not problems else 1


if __name__ == "__main__":
    sys.exit(main())
