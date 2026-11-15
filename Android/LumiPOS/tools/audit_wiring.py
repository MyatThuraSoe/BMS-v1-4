"""
UI-wiring audit for LumiPOS.

Finds interactive views that nothing ever handles — the "incomplete function" class of
defect. These never crash and never log; the control simply does nothing when tapped.

Check: UNWIRED — an interactive view (button / chip / switch / checkbox / FAB /
RecyclerView) whose id is referenced by no Kotlin file anywhere in the project.

Two things this deliberately does NOT report:

  * A RadioButton that sits in a RadioGroup where a sibling *is* referenced. The group is
    normally read as a whole (`checkedRadioButtonId == R.id.rb_credit`), so the other
    members are wired through it and flagging them is pure noise.
  * Whether a RecyclerView is actually given an adapter. That cannot be decided from ids
    alone — the adapter is frequently assigned to a field in a different statement — so
    claiming to check it would produce confident false positives.

Usage (from Android/LumiPOS):
    python tools/audit_wiring.py
Exit code 0 = clean.
"""

import os
import re
import sys
import xml.etree.ElementTree as ET
from collections import defaultdict

A = "{http://schemas.android.com/apk/res/android}"
P = "{http://schemas.android.com/apk/res-auto}"
RES = os.path.join("src", "main", "res")
LAYOUTS = os.path.join(RES, "layout")
JAVA = os.path.join("src", "main", "java")

INTERACTIVE = {
    "Button", "MaterialButton", "AppCompatButton", "ImageButton",
    "FloatingActionButton", "ExtendedFloatingActionButton",
    "Chip", "ChipGroup", "SwitchMaterial", "SwitchCompat", "Switch",
    "CheckBox", "MaterialCheckBox", "MaterialSwitch", "RadioButton",
    "MaterialRadioButton", "ToggleButton", "AutoCompleteTextView",
    "MaterialAutoCompleteTextView",
}

# ids whose handling lives in a framework/library rather than our Kotlin
IGNORE_ID_PREFIXES = ("nav_", "action_")

INFLATE_RE = re.compile(r"(?:inflate|setContentView)\(\s*R\.layout\.([A-Za-z0-9_]+)")
BINDING_INFLATE_RE = re.compile(r"R\.layout\.([A-Za-z0-9_]+)\s*,")
ANYID_RE = re.compile(r"R\.id\.([A-Za-z0-9_]+)")


def simple(tag):
    return tag.split(".")[-1]


class Layouts:
    def __init__(self):
        self.ids = {}            # layout -> {id: tag}
        self.includes = {}       # layout -> [included layout]
        self.radio_siblings = {} # layout -> {radio_id: set(sibling radio ids in same group)}
        self._load()

    def _load(self):
        for fn in sorted(os.listdir(LAYOUTS)):
            if not fn.endswith(".xml"):
                continue
            name = fn[:-4]
            self.ids[name] = {}
            self.includes[name] = []
            self.radio_siblings[name] = {}
            try:
                root = ET.parse(os.path.join(LAYOUTS, fn)).getroot()
            except ET.ParseError:
                continue
            for el in root.iter():
                if el.tag == "include":
                    inc = el.get("layout")
                    if inc:
                        self.includes[name].append(inc.split("/")[-1])
                    iid = el.get(A + "id")
                    if iid:
                        # an id on <include> belongs to the INCLUDED ROOT
                        inc_layout = (inc or "").split("/")[-1]
                        tag = self._root_tag(inc_layout) if inc_layout else "?"
                        self.ids[name][iid.split("/")[-1]] = tag
                    continue
                vid = el.get(A + "id")
                if vid:
                    self.ids[name][vid.split("/")[-1]] = el.tag

                # Record sibling RadioButtons so a group read as a whole is not reported.
                if simple(el.tag) == "RadioGroup":
                    group = [
                        c.get(A + "id").split("/")[-1]
                        for c in el.iter()
                        if c is not el
                        and simple(c.tag).endswith("RadioButton")
                        and c.get(A + "id")
                    ]
                    for rid in group:
                        self.radio_siblings[name][rid] = {r for r in group if r != rid}

    def _root_tag(self, layout):
        try:
            return ET.parse(os.path.join(LAYOUTS, layout + ".xml")).getroot().tag
        except Exception:
            return "?"

    def parents_of(self, layout):
        """layouts that <include> this one, transitively."""
        out, stack = set(), [layout]
        while stack:
            cur = stack.pop()
            for parent, kids in self.includes.items():
                if cur in kids and parent not in out:
                    out.add(parent)
                    stack.append(parent)
        return out


def nav_layouts():
    mapping = {}
    nav = os.path.join(RES, "navigation", "mobile_navigation.xml")
    for f in ET.parse(nav).getroot().iter("fragment"):
        name, layout = f.get(A + "name"), f.get(P + "layout")
        if name and layout:
            mapping[name.split(".")[-1]] = layout.split("/")[-1]
    return mapping


def kotlin_index():
    """Every R.id.<name> referenced from any Kotlin file, anywhere.

    Deliberately global rather than per-layout. The question this check answers is
    "is this control handled by *anything*?", and a dialog or adapter item layout is
    routinely inflated from a file that also owns a different layout, so scoping the
    search to one layout per file produces false positives and no extra signal.
    Layout-scoped correctness is what audit_views.py is for.
    """
    referenced = set()
    for root, _dirs, files in os.walk(JAVA):
        for fn in files:
            if not fn.endswith(".kt"):
                continue
            with open(os.path.join(root, fn), "r", encoding="utf-8", errors="replace") as fh:
                referenced |= set(ANYID_RE.findall(fh.read()))
    return referenced


def main():
    layouts = Layouts()
    referenced = kotlin_index()

    unwired = []

    for layout, ids in layouts.ids.items():
        for vid, tag in ids.items():
            if vid.startswith(IGNORE_ID_PREFIXES):
                continue
            kind = simple(tag)
            if kind in INTERACTIVE or kind == "RecyclerView":
                if vid in referenced:
                    continue
                # A RadioButton is handled through its group: if any sibling is referenced,
                # the group is being read via checkedRadioButtonId and this one is wired.
                siblings = layouts.radio_siblings.get(layout, {}).get(vid, set())
                if siblings & referenced:
                    continue
                unwired.append((layout, vid, kind))

    print("layouts: %d | distinct R.id.* referenced in Kotlin: %d"
          % (len(layouts.ids), len(referenced)))
    print()

    if not unwired:
        print("OK - every interactive view is referenced by some Kotlin file.")
        return 0

    by_layout = defaultdict(list)
    for layout, vid, kind in unwired:
        by_layout[layout].append((vid, kind))

    print("UNWIRED CONTROLS: %d across %d layouts" % (len(unwired), len(by_layout)))
    for layout in sorted(by_layout):
        print("\n%s.xml" % layout)
        for vid, tag in sorted(by_layout[layout]):
            print("  %-34s <%s>" % (vid, tag))
    return 1


if __name__ == "__main__":
    sys.exit(main())
