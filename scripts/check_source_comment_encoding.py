from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
SOURCE_ROOTS = (ROOT / "bms-backend" / "src", ROOT / "frontend" / "src")
SOURCE_SUFFIXES = {".java", ".js", ".jsx", ".ts", ".tsx", ".css"}
FORBIDDEN = (b"\xc3\x82", b"\xc3\x83\xc2\x82")
COMMENT_MARKERS = (b"//", b"/*", b"<!--")


def has_forbidden_sequence(line: bytes) -> bool:
    stripped = line.lstrip()
    if any(stripped.startswith(marker) for marker in (b"//", b"/*", b"*", b"*/", b"<!--", b"*-->")):
        return any(sequence in line for sequence in FORBIDDEN)
    for marker in COMMENT_MARKERS:
        position = line.find(marker)
        if position >= 0 and any(sequence in line[position:] for sequence in FORBIDDEN):
            return True
    return False


def main() -> int:
    failures = []
    for source_root in SOURCE_ROOTS:
        if not source_root.exists():
            continue
        for path in source_root.rglob("*"):
            if path.is_file() and path.suffix in SOURCE_SUFFIXES:
                for line_number, line in enumerate(path.read_bytes().splitlines(), start=1):
                    if has_forbidden_sequence(line):
                        failures.append(f"{path.relative_to(ROOT)}:{line_number}")
    if failures:
        print("Forbidden UTF-8 mojibake sequence found in source comments:")
        print("\n".join(failures))
        return 1
    print("Source comment encoding check passed.")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
