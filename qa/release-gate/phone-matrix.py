#!/usr/bin/env python3
"""FrameMark release gate C (phone): drive the installed QA build over adb and
capture the UI matrix.

The driver never guesses coordinates: every tap resolves a uiautomator node by
its visible text or accessibility label, so the same script works when the card
layout shifts.  Each state is captured to a PNG plus the UI dump it was taken
from, which lets the checker verify that every labelled node stayed inside the
screen and that the expected controls were present.

  python phone-matrix.py --out E:/FrameMark/qa/release-gate-20261117/ui/phone
"""
import argparse
import os
import re
import shutil
import subprocess
import sys
import time
import xml.etree.ElementTree as ET

ADB_DEFAULT = r"D:/dev/android-sdk/platform-tools/adb.exe"
PKG_DEFAULT = "com.framemark.native.qa20261106uiv2"
SERIAL_DEFAULT = "8267fe3e"

PALETTES = ["暖灰墨绿", "石墨琥珀", "瓷白靛青", "纸白陶土", "经典蓝"]
MODES = ["浅色", "深色"]
EDITOR_TABS = ["样式", "文字", "Logo", "水印"]


class Driver:
    def __init__(self, adb, serial, out):
        self.adb = adb
        self.serial = serial
        self.out = out
        self.shots = []
        self.dumps = {}
        self.env = {**os.environ, "MSYS_NO_PATHCONV": "1"}
        os.makedirs(out, exist_ok=True)

    def shell(self, *args, check=True):
        cmd = [self.adb, "-s", self.serial, "shell", *args]
        res = subprocess.run(cmd, capture_output=True, text=True, env=self.env, timeout=120)
        if check and res.returncode != 0:
            raise RuntimeError(f"adb shell {' '.join(args)}: {res.stderr.strip()}")
        return res.stdout

    def dump(self):
        self.shell("uiautomator", "dump", "/sdcard/ui.xml")
        local = os.path.join(self.out, "_ui.xml")
        subprocess.run(
            [self.adb, "-s", self.serial, "pull", "/sdcard/ui.xml", local],
            capture_output=True, text=True, env=self.env, timeout=120, check=True,
        )
        root = ET.parse(local).getroot()
        nodes = []
        for n in root.iter("node"):
            bounds = n.get("bounds") or ""
            m = re.match(r"\[(\d+),(\d+)\]\[(\d+),(\d+)\]", bounds)
            if not m:
                continue
            x1, y1, x2, y2 = map(int, m.groups())
            nodes.append({
                "text": (n.get("text") or "").strip(),
                "desc": (n.get("content-desc") or "").strip(),
                "class": n.get("class") or "",
                "clickable": n.get("clickable") == "true",
                "enabled": n.get("enabled") == "true",
                "selected": n.get("selected") == "true",
                "bounds": (x1, y1, x2, y2),
                "cx": (x1 + x2) // 2,
                "cy": (y1 + y2) // 2,
            })
        return nodes

    @staticmethod
    def find(nodes, label, exact=True, clickable=False):
        hits = []
        for n in nodes:
            if clickable and not n["clickable"]:
                continue
            for value in (n["text"], n["desc"]):
                if not value:
                    continue
                if (exact and value == label) or (not exact and label in value):
                    hits.append(n)
                    break
        if not hits:
            return None
        # the largest matching box is the card/row, not its inner label
        return max(hits, key=lambda n: (n["bounds"][2] - n["bounds"][0]) * (n["bounds"][3] - n["bounds"][1]))

    def tap(self, label, exact=True, clickable=False, label_name=None):
        nodes = self.dump()
        node = self.find(nodes, label, exact=exact, clickable=clickable)
        if node is None:
            raise RuntimeError(f"tap: no node for {label_name or label!r}; labels={[n['text'] or n['desc'] for n in nodes if n['text'] or n['desc']][:40]}")
        self.shell("input", "tap", str(node["cx"]), str(node["cy"]))
        return node

    def tap_xy(self, x, y):
        self.shell("input", "tap", str(x), str(y))

    def shot(self, name, settle=0.9):
        time.sleep(settle)
        path = os.path.join(self.out, name + ".png")
        with open(path, "wb") as fh:
            res = subprocess.run(
                [self.adb, "-s", self.serial, "exec-out", "screencap", "-p"],
                stdout=fh, stderr=subprocess.PIPE, env=self.env, timeout=120,
            )
        if res.returncode != 0 or os.path.getsize(path) < 1024:
            raise RuntimeError(f"screencap failed for {name}: {res.stderr.decode(errors='replace')}")
        nodes = self.dump()
        self.dumps[name] = nodes
        try:
            shutil.copyfile(os.path.join(self.out, "_ui.xml"), os.path.join(self.out, name + ".xml"))
        except Exception:
            pass
        self.shots.append({"name": name, "path": path, "nodes": len(nodes)})
        print(f"  shot {name} ({len(nodes)} nodes)")

    def launch(self, extras=None):
        self.shell("am", "force-stop", PKG_DEFAULT)
        args = ["am", "start", "-W", "-n", f"{PKG_DEFAULT}/com.framemark.native.MainActivity"]
        args += list(extras or [])
        self.shell(*args)
        time.sleep(4.5)

    def wait_editor(self, timeout=25):
        deadline = time.time() + timeout
        while time.time() < deadline:
            nodes = self.dump()
            labels = {n["text"] for n in nodes} | {n["desc"] for n in nodes}
            if "边框" in labels and ("样式" in labels or "水印" in labels):
                return nodes
            if "设置" in labels:  # home, open the recent frame
                node = self.find(nodes, "打开 hdr.heic", exact=False) or self.find(nodes, "打开 ", exact=False)
                if node:
                    self.tap_xy(node["cx"], node["cy"])
                    time.sleep(2.5)
                    continue
            time.sleep(1)
        raise RuntimeError("editor did not appear within timeout")

    def goto_home(self):
        nodes = self.dump()
        if self.find(nodes, "返回设置") is not None:
            self.tap("返回设置")
            time.sleep(1.0)
            nodes = self.dump()
        if self.find(nodes, "返回首页") is not None:
            self.tap("返回首页")
        elif self.find(nodes, "返回") is not None and self.find(nodes, "设置") is None:
            self.tap("返回")
        time.sleep(1.2)
        # home is identified by the settings gear and the recent list
        for _ in range(5):
            nodes = self.dump()
            if self.find(nodes, "设置") is not None and self.find(nodes, "最近编辑") is not None:
                return nodes
            if self.find(nodes, "返回首页") is not None:
                self.tap("返回首页")
                time.sleep(1.2)
                continue
            if self.find(nodes, "返回设置") is not None:
                self.tap("返回设置")
                time.sleep(1.0)
                continue
            if self.find(nodes, "返回") is not None:
                self.tap("返回")
                time.sleep(1.2)
                continue
            break
        return self.dump()

    def goto_settings(self):
        self.goto_home()
        self.tap("设置")
        time.sleep(1.0)
        return self.dump()

    def open_editor(self):
        self.goto_home()
        nodes = self.dump()
        node = self.find(nodes, "打开 hdr.heic", exact=False)
        if node is None:
            raise RuntimeError("recent hdr.heic row not found on home")
        self.tap_xy(node["cx"], node["cy"])
        time.sleep(2.2)
        self.wait_editor()


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--adb", default=ADB_DEFAULT)
    ap.add_argument("--serial", default=SERIAL_DEFAULT)
    ap.add_argument("--out", default=r"E:/FrameMark/qa/release-gate-20261117/ui/phone")
    ap.add_argument("--skip-palettes", action="store_true")
    ap.add_argument("--start", choices=["full", "fontscale"], default="full")
    args = ap.parse_args()

    d = Driver(args.adb, args.serial, args.out)
    version = d.shell("dumpsys", "package", PKG_DEFAULT).splitlines()
    vname = next((l.strip() for l in version if "versionName" in l), "?")
    print("device version:", vname)
    d.launch()
    d.wait_editor()
    d.shot("00-editor-start", settle=0.5)

    if args.start == "full" and not args.skip_palettes:
        for palette in PALETTES:
            for mode in MODES:
                print(f"palette {palette} / {mode}")
                d.goto_settings()
                d.tap(palette)
                d.tap(mode)
                d.shot(f"settings-{palette}-{mode}", settle=1.2)
                d.goto_home()
                d.shot(f"home-{palette}-{mode}")
                d.open_editor()
                d.shot(f"editor-{palette}-{mode}", settle=1.6)

    if args.start == "full":
        # page matrix at the default (Forest) light appearance
        d.goto_settings()
        d.tap("暖灰墨绿")
        d.tap("浅色")
        d.goto_home()
        d.open_editor()
        for tab in EDITOR_TABS:
            d.tap(tab)
            d.shot(f"page-{tab}-light", settle=1.3)
        d.tap("预设")
        d.shot("sheet-presets-light", settle=1.2)
        d.shell("input", "keyevent", "4")
        time.sleep(0.8)
        d.tap("导出")
        d.shot("sheet-export-light", settle=1.2)
        d.shell("input", "keyevent", "4")
        time.sleep(0.8)

        # dark page matrix
        d.goto_settings()
        d.tap("深色")
        d.shot("settings-dark", settle=1.0)
        d.goto_home()
        d.shot("home-dark")
        d.open_editor()
        for tab in ["边框"] + EDITOR_TABS:
            d.tap(tab)
            d.shot(f"page-{tab}-dark", settle=1.3)

        # about page
        d.goto_settings()
        d.tap("隐私、权限与许可", exact=False)
        d.shot("about-dark", settle=1.0)
        d.shell("input", "keyevent", "4")
        time.sleep(0.8)

        # restore light
        d.goto_settings()
        d.tap("浅色")

    # font scale matrix: relaunch per scale, merge text.fontScale into the render
    for scale in ["1.0", "1.3", "1.5", "2.0"]:
        design = '{"text":{"fontScale":%s}}' % scale
        d.launch(["--es", "fmCard", "stock-oppo-hassel_text_style_2", "--es", "fmDesignJson", design])
        d.wait_editor()
        d.shot(f"fontscale-{scale}", settle=2.0)

    # landscape
    d.shell("settings", "put", "system", "accelerometer_rotation", "0")
    d.shell("settings", "put", "system", "user_rotation", "1")
    time.sleep(2.0)
    d.launch(["--es", "fmCard", "stock-oppo-hassel_text_style_2"])
    d.wait_editor()
    d.shot("landscape-editor", settle=2.0)
    d.tap("样式")
    d.shot("landscape-style", settle=1.2)
    d.goto_settings()
    d.shot("landscape-settings", settle=1.2)
    d.shell("settings", "put", "system", "user_rotation", "0")
    time.sleep(1.5)

    print(f"captured {len(d.shots)} states -> {args.out}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
