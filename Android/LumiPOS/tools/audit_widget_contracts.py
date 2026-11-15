"""
Widget-contract audit for LumiPOS.

Catches attribute combinations that are perfectly legal to compile and merge, but that
break at runtime. A green build says nothing about any of these — they surface as an
exception during inflation or as a control that silently renders wrong.

Checks
------

1. DROPDOWN_MENU_CHILD (error)
   `app:endIconMode="dropdown_menu"` on a TextInputLayout whose direct child is not an
   AutoCompleteTextView.

   Material's DropdownMenuEndIconDelegate casts its child via
   `castAutoCompleteTextViewOrThrow` from `onEditTextAttached`, and EndCompoundLayout
   calls that straight out of its own constructor. So a TextInputEditText child throws
   `IllegalStateException: EditText needs to be an AutoCompleteTextView if an Exposed
   Dropdown Menu is being used.` while the layout is being inflated — the screen crashes
   on open, before any user interaction.

   A field that opens a picker dialog instead of a real dropdown wants
   `endIconMode="custom"` plus `app:endIconDrawable`.

2. CUSTOM_ICON_NO_DRAWABLE (warning)
   `app:endIconMode="custom"` with no `app:endIconDrawable`. The end icon slot renders
   nothing at all, so the affordance the layout is trying to advertise is invisible.

3. MAXHEIGHT_ON_WRONG_VIEW (warning)
   `android:maxHeight` on anything that is not an ImageView/TextView. The attribute is
   read only by those two, so on e.g. a RecyclerView it is silently ignored and the
   height constraint the author expected simply does not exist.

Usage (from Android/LumiPOS):
    python tools/audit_widget_contracts.py
Exit code 0 = clean (warnings still exit 0; errors exit 1).
"""

import os
import sys
import xml.etree.ElementTree as ET

A = "{http://schemas.android.com/apk/res/android}"
P = "{http://schemas.android.com/apk/res-auto}"
RES = os.path.join("src", "main", "res")
LAYOUTS = os.path.join(RES, "layout")

AUTOCOMPLETE = {"AutoCompleteTextView", "MaterialAutoCompleteTextView"}

# maxHeight is only honoured by ImageView and TextView.
MAXHEIGHT_OK = ("ImageView", "TextView")


def simple(tag):
    return tag.split(".")[-1]


def short(ref):
    return (ref or "").split("/")[-1]


def check_layout(name, root):
    """returns (errors, warnings) for one layout"""
    errors, warnings = [], []

    for el in root.iter():
        kind = simple(el.tag)

        if kind == "TextInputLayout":
            mode = el.get(P + "endIconMode")
            children = list(el)

            if mode == "dropdown_menu":
                child_kinds = [simple(c.tag) for c in children]
                if not any(c in AUTOCOMPLETE for c in child_kinds):
                    shown = ", ".join(child_kinds) if child_kinds else "no child"
                    errors.append(
                        "%s: endIconMode=\"dropdown_menu\" but the child is <%s>. Material casts "
                        "the child to AutoCompleteTextView in onEditTextAttached and throws "
                        "IllegalStateException during inflation. Use an AutoCompleteTextView, or "
                        "endIconMode=\"custom\" with app:endIconDrawable."
                        % (el.get(A + "id", "TextInputLayout").split("/")[-1], shown)
                    )

            elif mode == "custom" and el.get(P + "endIconDrawable") is None:
                warnings.append(
                    "%s: endIconMode=\"custom\" without app:endIconDrawable - the end icon "
                    "renders nothing."
                    % el.get(A + "id", "TextInputLayout").split("/")[-1]
                )

        # maxHeight is silently ignored outside ImageView/TextView.
        if el.get(A + "maxHeight") is not None:
            if not any(suffix in kind for suffix in MAXHEIGHT_OK):
                warnings.append(
                    "%s: android:maxHeight on <%s> is ignored - only ImageView and TextView "
                    "honour it." % (el.get(A + "id", kind).split("/")[-1], kind)
                )

    return errors, warnings


def main():
    if not os.path.isdir(LAYOUTS):
        print("run from Android/LumiPOS (no %s)" % LAYOUTS)
        return 2

    total_errors, total_warnings = [], []

    for fn in sorted(os.listdir(LAYOUTS)):
        if not fn.endswith(".xml"):
            continue
        path = os.path.join(LAYOUTS, fn)
        try:
            root = ET.parse(path).getroot()
        except ET.ParseError as exc:
            print("%s: XML parse error: %s" % (fn, exc))
            return 2

        errors, warnings = check_layout(fn, root)
        total_errors += [("%s.xml" % fn[:-4], e) for e in errors]
        total_warnings += [("%s.xml" % fn[:-4], w) for w in warnings]

    print("layouts scanned: %d" % len([f for f in os.listdir(LAYOUTS) if f.endswith(".xml")]))
    print()

    if total_warnings:
        print("WARNINGS (%d)" % len(total_warnings))
        for layout, msg in total_warnings:
            print("  %-34s %s" % (layout, msg))
        print()

    if total_errors:
        print("ERRORS (%d) - these crash at runtime" % len(total_errors))
        for layout, msg in total_errors:
            print("  %-34s %s" % (layout, msg))
        return 1

    print("OK - no widget attribute contract violations.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
