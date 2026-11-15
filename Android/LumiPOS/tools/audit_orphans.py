"""
Orphaned-resource audit for LumiPOS.

A resource is "orphaned" when nothing in the project can reach it:
  * a layout that no Kotlin file inflates, no other layout <include>s, and the nav graph
    does not reference via app:layout
  * a drawable/mipmap that no XML attribute, menu, or Kotlin `R.drawable` reference names
  * a menu that no `app:menu=` points at

Orphans are not bugs, but they are dead weight in the APK and they mislead whoever reads
the project next (LumiPOS still carries layouts from an unrelated template).

Usage (from Android/LumiPOS):
    python tools/audit_orphans.py
Exit code 0 always; this is a report, not a gate.
"""

import os
import re
import xml.etree.ElementTree as ET
from collections import defaultdict

A = "{http://schemas.android.com/apk/res/android}"
P = "{http://schemas.android.com/apk/res-auto}"
RES = os.path.join("src", "main", "res")
JAVA = os.path.join("src", "main", "java")

REF_RE = re.compile(r"@(?:\+)?([a-z_]+)/([A-Za-z0-9_.]+)")
KOTLIN_RE = re.compile(r"(?<![\w.])R\.([a-z_]+)\.([A-Za-z0-9_]+)")


def all_files(*folders):
    for folder in folders:
        base = os.path.join(RES, folder)
        if os.path.isdir(base):
            for fn in sorted(os.listdir(base)):
                if fn.endswith(".xml"):
                    yield folder, fn[:-4], os.path.join(base, fn)


def collect_references():
    """(kind, name) pairs referenced from anywhere that can reach a resource."""
    used = defaultdict(set)

    for root, _dirs, files in os.walk(RES):
        for fn in files:
            if not fn.endswith(".xml"):
                continue
            with open(os.path.join(root, fn), "r", encoding="utf-8", errors="replace") as fh:
                for kind, name in REF_RE.findall(fh.read()):
                    used[kind].add(name)

    for root, _dirs, files in os.walk(JAVA):
        for fn in files:
            if not fn.endswith(".kt"):
                continue
            with open(os.path.join(root, fn), "r", encoding="utf-8", errors="replace") as fh:
                for kind, name in KOTLIN_RE.findall(fh.read()):
                    used[kind].add(name)
    return used


def collect_declarations():
    """every resource name that exists in the project (incl. density variants)."""
    declared = defaultdict(set)
    for root, _dirs, files in os.walk(RES):
        for fn in files:
            path = os.path.join(root, fn)
            folder = os.path.basename(root)
            if fn.endswith(".xml"):
                stem = fn[:-4]
                declared[folder.split("-")[0]].add(stem)
                try:
                    r = ET.parse(path).getroot()
                except ET.ParseError:
                    continue
                if r.tag == "resources":
                    for child in r:
                        if child.get("name") is not None:
                            declared[child.get("type", child.tag)].add(child.get("name"))
            elif fn.endswith((".png", ".webp", ".jpg", ".svg")):
                stem = re.sub(r"\.9\.png$", "", fn)
                stem = re.sub(r"\.(png|webp|jpg|svg)$", "", stem)
                declared["drawable"].add(stem)
                declared["mipmap"].add(stem)
    return declared


def main():
    used = collect_references()
    declared = collect_declarations()

    # layouts reachable from the nav graph are used by definition
    nav = os.path.join(RES, "navigation", "mobile_navigation.xml")
    nav_layouts = set()
    for el in ET.parse(nav).getroot().iter("fragment"):
        lay = el.get(P + "layout")
        if lay:
            nav_layouts.add(lay.split("/")[-1])

    # anything an <include> names is used
    included = set()
    for _folder, _name, path in all_files("layout"):
        try:
            for el in ET.parse(path).getroot().iter("include"):
                inc = el.get("layout")
                if inc:
                    included.add(inc.split("/")[-1])
        except ET.ParseError:
            pass

    orphan_layouts = sorted(
        name for folder, name, _p in all_files("layout")
        if name not in used.get("layout", set())
        and name not in included
        and name not in nav_layouts
    )

    orphan_menus = sorted(
        name for folder, name, _p in all_files("menu")
        if name not in used.get("menu", set())
    )

    # drawables: ignore anything referenced as @drawable/x or R.drawable.x
    orphan_drawables = []
    for folder in sorted(d for d in os.listdir(RES) if d.startswith("drawable")):
        for fn in sorted(os.listdir(os.path.join(RES, folder))):
            if fn.endswith(".xml"):
                stem = fn[:-4]
            elif fn.endswith((".png", ".webp", ".jpg", ".svg")):
                stem = re.sub(r"\.9\.png$", "", fn)
                stem = re.sub(r"\.(png|webp|jpg|svg)$", "", stem)
            else:
                continue
            if stem not in used.get("drawable", set()):
                orphan_drawables.append(stem)
    orphan_drawables = sorted(set(orphan_drawables))

    print("layout files: %d" % len(list(all_files("layout"))))
    print()
    print("ORPHANED LAYOUTS (%d):" % len(orphan_layouts))
    for n in orphan_layouts:
        print("   %s.xml" % n)
    print()
    print("ORPHANED MENUS (%d):" % len(orphan_menus))
    for n in orphan_menus:
        print("   %s.xml" % n)
    print()
    print("UNREFERENCED DRAWABLES (%d):" % len(orphan_drawables))
    for n in orphan_drawables:
        print("   %s" % n)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
