from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
SOURCE_ROOTS = (
    ROOT / "bms-backend",
    ROOT / "frontend",
    ROOT / "electron",
    ROOT / "scripts",
    ROOT / ".github",
)
SOURCE_SUFFIXES = {
    ".java", ".js", ".jsx", ".ts", ".tsx", ".json", ".yml", ".yaml",
    ".sql", ".html", ".css", ".xml", ".properties", ".py",
}
SKIPPED_DIRECTORY_NAMES = {
    "node_modules", "target", "build", "dist", "out", "coverage",
    "__pycache__", ".idea", ".vscode",
}


def offenders() -> list:
    found = []
    for source_root in SOURCE_ROOTS:
        if not source_root.exists():
            continue
        for path in source_root.rglob("*"):
            if not path.is_file() or path.suffix not in SOURCE_SUFFIXES:
                continue
            if SKIPPED_DIRECTORY_NAMES.intersection(path.relative_to(ROOT).parts):
                continue
            if b"\r\n" in path.read_bytes():
                found.append(path.relative_to(ROOT))
    return found


def main() -> int:
    found = offenders()
    if found:
        print("Source files with CRLF line endings (the repository uses LF):")
        print("\n".join(f"  {path.as_posix()}" for path in found))
        print("Save them with LF endings; .gitattributes and .editorconfig enforce it.")
        return 1
    print("Source line ending check passed.")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
