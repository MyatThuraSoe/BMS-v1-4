#!/usr/bin/env python3
"""Find ViewModel members that nothing outside the declaring file ever touches.

This is the check that found `totalItemsSold` never being computed, `loadInventorySummary()`
discarding its result, and `selectedPayments` never being observed. Those are the hardest
bugs to spot by reading: the code compiles, the feature looks implemented, and the UI is
simply blank.

Usage:
    python tools/audit_vm.py [--src src/main/java/com/example/lumipos]

Exit code is 0 always (this is a report, not a gate). Read the output.
"""

import argparse
import os
import re
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from kt_source import strip_comments  # noqa: E402

# Members every AndroidViewModel legitimately owns; never report these.
IGNORED_NAMES = {
    "onCleared", "getApplication", "setApplication", "equals", "hashCode", "toString",
}

FUN_RE = re.compile(r"^    (?:override\s+)?(?:public\s+|internal\s+)?fun\s+([A-Za-z_]\w*)\s*\(")
VAL_RE = re.compile(r"^    (?:internal\s+|public\s+)?val\s+([A-Za-z_]\w*)\s*[:=]")

# Deliberately *not* matching `private` members: they are file-local by design, so "never
# referenced from another file" is the normal, correct state for them. The public twin of a
# `private val _x` is what gets reported.
#
# Matching exactly four leading spaces is what keeps this honest: the codebase indents class
# bodies by four, so anything deeper is a local inside a function and is none of our business.


def collect_kotlin_files(root: str):
    for dirpath, _dirnames, filenames in os.walk(root):
        for name in filenames:
            if name.endswith(".kt"):
                yield os.path.join(dirpath, name)


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--src", default="src/main/java/com/example/lumipos")
    args = parser.parse_args()

    files = list(collect_kotlin_files(args.src))
    if not files:
        print(f"no Kotlin files under {args.src}")
        return

    # name -> (declaring file, kind, line)
    declarations = {}
    viewmodel_files = []

    for path in files:
        with open(path, "r", encoding="utf-8") as handle:
            raw = handle.read()
        if "ViewModel" not in os.path.basename(path):
            continue
        viewmodel_files.append(path)
        clean = strip_comments(raw)
        for lineno, line in enumerate(clean.splitlines(), start=1):
            m = FUN_RE.match(line)
            if m and m.group(1) not in IGNORED_NAMES:
                declarations.setdefault(m.group(1), (path, "fun", lineno))
                continue
            m = VAL_RE.match(line)
            if m:
                declarations.setdefault(m.group(1), (path, "val", lineno))

    if not viewmodel_files:
        print("no *ViewModel.kt files found")
        return

    problems = []
    for name, (path, kind, lineno) in sorted(declarations.items()):
        used_outside = False
        for other in files:
            if os.path.abspath(other) == os.path.abspath(path):
                continue
            with open(other, "r", encoding="utf-8") as handle:
                body = strip_comments(handle.read())
            if re.search(r"\b" + re.escape(name) + r"\b", body):
                used_outside = True
                break

        if not used_outside:
            problems.append((os.path.basename(path), lineno, kind, name))

    print(f"scanned {len(viewmodel_files)} ViewModel file(s), "
          f"{len(declarations)} member(s) declared\n")
    if not problems:
        print("OK - every ViewModel member is referenced from outside its own file")
        return

    print(f"{len(problems)} member(s) referenced by no other file "
          f"(dead, or wired only inside the ViewModel):\n")
    for filename, lineno, kind, name in problems:
        print(f"  {filename}:{lineno}  {kind:3}  {name}")
    print("\nNote: a member used only within its own ViewModel still shows up here. "
          "Confirm before deleting.")


if __name__ == "__main__":
    main()
