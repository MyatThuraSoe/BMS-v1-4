"""
View-binding audit for LumiPOS.

Catches the two bug classes that have historically crashed this app at runtime:

  A) findViewById(R.id.X) where X is declared on an <include> tag.
     An android:id on <include> resolves to the INCLUDED ROOT VIEW, so
     findViewById<TextView>(R.id.stat_foo) returns a MaterialCardView and
     throws ClassCastException on the first access.

  B) findViewById(R.id.X) where X does not exist in the fragment's layout
     (typo, renamed id, or a view that only exists in another layout).
     Returns null -> NPE, or silently does nothing.

Usage (from Android/LumiPOS):
    python tools/audit_views.py
Exit code 0 = clean.
"""

import os
import re
import sys
import xml.etree.ElementTree as ET
from collections import defaultdict

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from kt_source import strip_comments as _strip_comments  # noqa: E402

A = "{http://schemas.android.com/apk/res/android}"
P = "{http://schemas.android.com/apk/res-auto}"
RES = os.path.join("src", "main", "res")
LAYOUTS = os.path.join(RES, "layout")
JAVA = os.path.join("src", "main", "java")

# java.lang.Object-ish superclasses we cannot verify against
UNVERIFIABLE = {"View", "ViewGroup", "Object", "Any"}


# ------------------------------------------------------------------ layout map

class Layouts:
    def __init__(self):
        self.own_ids = {}        # layout -> {id: tag}
        self.include_ids = {}    # layout -> {include_id: included_root_tag}
        self.includes = {}       # layout -> [included layout names]
        self._load()
        self._resolve()

    def _load(self):
        for fn in os.listdir(LAYOUTS):
            if not fn.endswith(".xml"):
                continue
            name = fn[:-4]
            path = os.path.join(LAYOUTS, fn)
            try:
                root = ET.parse(path).getroot()
            except ET.ParseError as exc:
                print("  !! parse error in %s: %s" % (path, exc))
                continue
            self.own_ids[name] = {}
            self.include_ids[name] = {}
            self.includes[name] = []
            for el in root.iter():
                if el.tag == "include":
                    inc = el.get("layout")
                    if inc:
                        self.includes[name].append(inc.split("/")[-1])
                    iid = el.get(A + "id")
                    if iid:
                        self.include_ids[name][iid.split("/")[-1]] = None
                    continue
                vid = el.get(A + "id")
                if vid:
                    self.own_ids[name][vid.split("/")[-1]] = el.tag

    def root_tag(self, layout):
        path = os.path.join(LAYOUTS, layout + ".xml")
        try:
            return ET.parse(path).getroot().tag
        except Exception:
            return "?"

    def _resolve(self):
        """Fill in the real tag behind each include id, then compute transitive ids."""
        for layout in list(self.own_ids):
            for iid, inc_layout in [(i, l) for i, l in self._include_pairs(layout)]:
                self.include_ids[layout][iid] = self.root_tag(inc_layout)

        self.all_ids = {}
        for layout in list(self.own_ids):
            seen, ids = set(), {}
            self._walk(layout, ids, seen)
            self.all_ids[layout] = ids

    def _include_pairs(self, layout):
        path = os.path.join(LAYOUTS, layout + ".xml")
        pairs = []
        try:
            root = ET.parse(path).getroot()
        except Exception:
            return pairs
        for el in root.iter("include"):
            iid = el.get(A + "id")
            inc = el.get("layout")
            if iid and inc:
                pairs.append((iid.split("/")[-1], inc.split("/")[-1]))
        return pairs

    def _walk(self, layout, ids, seen):
        if layout in seen:
            return
        seen.add(layout)
        for vid, tag in self.own_ids.get(layout, {}).items():
            ids.setdefault(vid, tag)
        for iid, tag in self.include_ids.get(layout, {}).items():
            ids.setdefault(iid, tag or "?")
        for inc in self.includes.get(layout, []):
            self._walk(inc, ids, seen)

    def lookup(self, layout, vid):
        return self.all_ids.get(layout, {}).get(vid)


# ------------------------------------------------------------------- kotlin map

FIND_RE = re.compile(r"findViewById<\s*([A-Za-z0-9_.]+)\s*>\s*\(\s*R\.id\.([A-Za-z0-9_]+)\s*\)")
ANYID_RE = re.compile(r"R\.id\.([A-Za-z0-9_]+)")
INFLATE_RE = re.compile(r"(?:inflate|setContentView)\(\s*R\.layout\.([A-Za-z0-9_]+)")
BINDING_INFLATE_RE = re.compile(r"R\.layout\.([A-Za-z0-9_]+)\s*,")

BLOCK_COMMENT_RE = re.compile(r"/\*.*?\*/", re.S)
LINE_COMMENT_RE = re.compile(r"//[^\n]*")


def strip_comments(text):
    """Blank out comments while preserving line numbers.

    Delegates to the shared, string-aware implementation. The previous regex version treated
    `//` inside a string literal as a comment, and would have truncated the file at a MIME
    type like `"image/*"` if that literal had ever been paired with a later `*/`.
    """
    return _strip_comments(text)


def nav_destination_ids():
    """ids declared on <fragment>/<dialog>/<activity> in the nav graph."""
    ids = set()
    nav = os.path.join(RES, "navigation", "mobile_navigation.xml")
    for el in ET.parse(nav).getroot().iter():
        if el.tag in ("fragment", "dialog", "activity"):
            vid = el.get(A + "id")
            if vid:
                ids.add(vid.split("/")[-1])
    return ids


def fragment_layouts():
    """fragment class simple-name -> layout name, from the nav graph."""
    mapping = {}
    nav = os.path.join(RES, "navigation", "mobile_navigation.xml")
    root = ET.parse(nav).getroot()
    for f in root.iter("fragment"):
        name, layout = f.get(A + "name"), f.get(P + "layout")
        if name and layout:
            mapping[name.split(".")[-1]] = layout.split("/")[-1]
    return mapping


def layout_for_file(path, text, frag_map):
    stem = os.path.basename(path)[:-3]
    if stem in frag_map:
        return frag_map[stem]
    m = INFLATE_RE.search(text) or BINDING_INFLATE_RE.search(text)
    return m.group(1) if m else None


def main():
    layouts = Layouts()
    frag_map = fragment_layouts()
    nav_ids = nav_destination_ids()

    # ids that exist in *some* layout - referenced from a dialog/adapter/included
    # layout rather than the fragment's own layout. Not a bug.
    known_anywhere = set()
    for ids in layouts.all_ids.values():
        known_anywhere |= set(ids)

    problems = []
    audited = 0

    for root, _dirs, files in os.walk(JAVA):
        for fn in files:
            if not fn.endswith(".kt"):
                continue
            path = os.path.join(root, fn)
            with open(path, "r", encoding="utf-8", errors="replace") as fh:
                text = fh.read()
            layout = layout_for_file(path, text, frag_map)
            if not layout or layout not in layouts.all_ids:
                continue
            audited += 1
            lines = strip_comments(text).splitlines()

            for lineno, line in enumerate(lines, 1):
                if not line.strip():
                    continue

                # --- bug class A: casting an <include> id to a non-root type
                for cast, vid in FIND_RE.findall(line):
                    simple = cast.split(".")[-1]
                    if simple in UNVERIFIABLE:
                        continue
                    tag = layouts.lookup(layout, vid)
                    if tag is None:
                        continue
                    tag_simple = tag.split(".")[-1]
                    if tag_simple != simple and not tag_simple.endswith(simple):
                        problems.append((
                            path, lineno, "CAST",
                            "findViewById<%s>(R.id.%s) but R.id.%s is a <%s>"
                            % (cast, vid, vid, tag_simple)))

                # --- bug class B: id that exists in NO layout and is not a nav destination
                for vid in ANYID_RE.findall(line):
                    if vid in nav_ids or vid in known_anywhere:
                        continue
                    problems.append((
                        path, lineno, "MISSING",
                        "R.id.%s is not declared in any layout and is not a nav destination" % vid))

    print("layouts parsed: %d" % len(layouts.all_ids))
    print("nav destination ids: %d" % len(nav_ids))
    print("kotlin files mapped to a layout: %d" % audited)
    print()

    if not problems:
        print("OK - no view-binding problems found.")
        return 0

    by_file = defaultdict(list)
    for path, lineno, kind, msg in problems:
        by_file[path].append((lineno, kind, msg))

    print("PROBLEMS: %d across %d files" % (len(problems), len(by_file)))
    for path in sorted(by_file):
        print("\n%s" % path)
        for lineno, kind, msg in by_file[path]:
            print("  %5d  [%s] %s" % (lineno, kind, msg))
    return 1


if __name__ == "__main__":
    sys.exit(main())
