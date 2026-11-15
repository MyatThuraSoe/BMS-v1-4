"""Shared Kotlin source helpers for the audit scripts.

`strip_comments` is deliberately shared rather than copy-pasted: an earlier per-script copy
had a bug where the MIME type string `"image/*"` was mistaken for a block-comment opener and
silently blanked the rest of the file. That turned live code into "unused" findings, which is
worse than no finding at all. Keep one implementation.
"""


def strip_comments(text: str) -> str:
    """Blank out Kotlin comments while preserving byte offsets and line numbers.

    String-aware: `//` and `/*` inside a string or char literal are left alone, which is what
    keeps `"image/*"` and `"https://..."` from truncating the file.
    """
    out = []
    i = 0
    n = len(text)
    in_line_comment = False
    in_block_comment = False
    quote = None          # '"' or "'" while inside a single-delimited literal
    in_raw_string = False

    while i < n:
        c = text[i]
        nxt = text[i + 1] if i + 1 < n else ""

        if in_line_comment:
            if c == "\n":
                in_line_comment = False
                out.append(c)
            else:
                out.append(" ")
            i += 1
            continue

        if in_block_comment:
            if c == "*" and nxt == "/":
                out.append("  ")
                i += 2
                in_block_comment = False
                continue
            out.append("\n" if c == "\n" else " ")
            i += 1
            continue

        if in_raw_string:
            if text.startswith('"""', i):
                out.append('"""')
                i += 3
                in_raw_string = False
                continue
            out.append("\n" if c == "\n" else c)
            i += 1
            continue

        if quote:
            out.append(c)
            if c == "\\" and nxt:
                out.append(nxt)
                i += 2
                continue
            if c == quote:
                quote = None
            i += 1
            continue

        # Outside any comment or literal.
        if c == "/" and nxt == "/":
            in_line_comment = True
            out.append("  ")
            i += 2
            continue
        if c == "/" and nxt == "*":
            in_block_comment = True
            out.append("  ")
            i += 2
            continue
        if text.startswith('"""', i):
            in_raw_string = True
            out.append('"""')
            i += 3
            continue
        if c == '"' or c == "'":
            quote = c
            out.append(c)
            i += 1
            continue

        out.append(c)
        i += 1

    return "".join(out)
