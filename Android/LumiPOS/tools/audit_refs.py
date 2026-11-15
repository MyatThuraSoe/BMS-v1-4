"""
Static resource-reference audit for LumiPOS.

Every @type/name reference in res/**.xml and every R.type.name reference in
src/main/java/**.kt is checked against the resource table of the *built APK*.
Using the APK table (rather than the project's own res/ folder) means library
resources from Material Components / AndroidX are included, so there are no
false positives for @style/Widget.MaterialComponents.* and friends.

Usage (from Android/LumiPOS, after ./gradlew assembleDebug):
    python tools/audit_refs.py [path/to/app.apk]
Exit code 0 = clean.
"""

import glob
import os
import re
import subprocess
import sys
import tempfile
from collections import defaultdict

RES = os.path.join("src", "main", "res")
JAVA = os.path.join("src", "main", "java")
DEFAULT_APK = os.path.join("build", "outputs", "apk", "debug", "LumiPOS-debug.apk")

# "@string/foo", "@+id/bar", "@style/Baz" — but NOT "@android:drawable/..."
REF_RE = re.compile(r"@(\+)?(android:)?([a-z_]+)/([A-Za-z0-9_.]+)")
# "R.string.foo" — but not "com.google.android.material.R.attr.foo"
KOTLIN_RE = re.compile(r"(?<![\w.])R\.([a-z_]+)\.([A-Za-z0-9_]+)")

# resource types we can meaningfully verify
CHECK_TYPES = {
    "string", "drawable", "color", "dimen", "style", "array", "menu",
    "navigation", "mipmap", "bool", "integer", "layout", "plurals", "anim",
}


def find_aapt2():
    home = os.path.expanduser("~")
    patterns = [
        os.path.join(home, "AppData", "Local", "Android", "Sdk", "build-tools", "*", "aapt2.exe"),
        os.path.join(home, "Library", "Android", "sdk", "build-tools", "*", "aapt2"),
        os.path.join(home, "Android", "Sdk", "build-tools", "*", "aapt2"),
        "/usr/local/lib/android/sdk/build-tools/*/aapt2",
    ]
    for pattern in patterns:
        hits = sorted(glob.glob(pattern))
        if hits:
            return hits[-1]
    raise SystemExit("aapt2 not found; pass the APK path explicitly")


def apk_resource_table(apk):
    """Return {type: {name, ...}} from the built APK."""
    with tempfile.NamedTemporaryFile("w+", suffix=".txt", delete=False, encoding="utf-8") as tmp:
        dump_path = tmp.name
    with open(dump_path, "w", encoding="utf-8") as fh:
        subprocess.run([find_aapt2(), "dump", "resources", apk],
                       stdout=fh, stderr=subprocess.DEVNULL, check=False)

    table = defaultdict(set)
    row = re.compile(r"^\s+resource 0x[0-9a-f]+ ([a-z_]+)/(\S+)\s*$")
    with open(dump_path, "r", encoding="utf-8", errors="replace") as fh:
        for line in fh:
            m = row.match(line)
            if m:
                table[m.group(1)].add(m.group(2))
    os.unlink(dump_path)
    if not table:
        raise SystemExit("empty resource table - is %s a valid APK?" % apk)
    return table


def scan(apk):
    table = apk_resource_table(apk)
    problems = []

    for root, _dirs, files in os.walk(RES):
        for fn in files:
            if not fn.endswith(".xml"):
                continue
            path = os.path.join(root, fn)
            with open(path, "r", encoding="utf-8", errors="replace") as fh:
                for lineno, line in enumerate(fh, 1):
                    if line.lstrip().startswith("<!--"):
                        continue
                    for _plus, android_ns, kind, name in REF_RE.findall(line):
                        if android_ns:          # @android:... -> framework, not ours
                            continue
                        if kind not in CHECK_TYPES:
                            continue
                        if name not in table.get(kind, set()):
                            problems.append((path, lineno, "@%s/%s" % (kind, name), line.strip()[:100]))

    for root, _dirs, files in os.walk(JAVA):
        for fn in files:
            if not fn.endswith(".kt"):
                continue
            path = os.path.join(root, fn)
            with open(path, "r", encoding="utf-8", errors="replace") as fh:
                for lineno, line in enumerate(fh, 1):
                    for kind, name in KOTLIN_RE.findall(line):
                        if kind == "id" or kind not in CHECK_TYPES:
                            continue
                        if name not in table.get(kind, set()):
                            problems.append((path, lineno, "R.%s.%s" % (kind, name), line.strip()[:100]))
    return table, problems


def main():
    apk = sys.argv[1] if len(sys.argv) > 1 else DEFAULT_APK
    if not os.path.exists(apk):
        raise SystemExit("APK not found: %s  (run ./gradlew assembleDebug first)" % apk)

    table, problems = scan(apk)
    print("APK: %s" % apk)
    print("resource table: %s" % ", ".join("%s=%d" % (k, len(v)) for k, v in sorted(table.items())))
    print()

    if not problems:
        print("OK - every resource reference resolves against the built APK.")
        return 0

    by_file = defaultdict(list)
    for path, lineno, ref, snippet in problems:
        by_file[path].append((lineno, ref, snippet))

    print("UNRESOLVED REFERENCES: %d across %d files" % (len(problems), len(by_file)))
    for path in sorted(by_file):
        print("\n%s" % path)
        for lineno, ref, snippet in by_file[path]:
            print("  %5d  %-40s %s" % (lineno, ref, snippet))
    return 1


if __name__ == "__main__":
    sys.exit(main())
