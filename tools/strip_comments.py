#!/usr/bin/env python3
"""Strip comments from Kotlin/Java source files.

Removes line comments (//), block comments (/* */) and KDoc (/** */)
while correctly skipping string literals, char literals and raw strings
so that content like "https://x" or '//' is never corrupted.

Usage:
    python strip_comments.py [--apply] [paths...]

Without --apply the script only prints a per-file summary.
"""
import os
import sys

EXTENSIONS = (".kt", ".java")
EXCLUDE_DIRS = {"build", ".gradle", ".idea", "generated", "kspCaches", "sources", "obj"}


def strip_comments(text: str) -> str:
    out = []
    i = 0
    n = len(text)
    while i < n:
        c = text[i]
        nxt = text[i + 1] if i + 1 < n else ""
        nnxt = text[i + 2] if i + 2 < n else ""

        if c == "/" and nxt == "/":
            while i < n and text[i] != "\n":
                i += 1
            continue

        if c == "/" and nxt == "*":
            j = i + 2
            while j < n and not (text[j] == "*" and (text[j + 1] if j + 1 < n else "") == "/"):
                j += 1
            i = j + 2
            continue

        if c == '"' and nxt == '"' and nnxt == '"':
            j = i + 3
            while j < n:
                if text[j] == '"' and (text[j + 1] if j + 1 < n else "") == '"' and (text[j + 2] if j + 2 < n else "") == '"':
                    j += 3
                    break
                j += 1
            out.append(text[i:j])
            i = j
            continue

        if c == '"':
            j = i + 1
            while j < n:
                if text[j] == "\\":
                    j += 2
                    continue
                if text[j] == '"':
                    j += 1
                    break
                j += 1
            out.append(text[i:j])
            i = j
            continue

        if c == "'":
            j = i + 1
            while j < n:
                if text[j] == "\\":
                    j += 2
                    continue
                if text[j] == "'":
                    j += 1
                    break
                j += 1
            out.append(text[i:j])
            i = j
            continue

        out.append(c)
        i += 1

    return "".join(out)


def collapse_blank_lines(text: str) -> str:
    lines = text.split("\n")
    result = []
    blank = 0
    for line in lines:
        line = line.rstrip()
        if line.strip() == "":
            blank += 1
            if blank <= 1:
                result.append("")
            continue
        blank = 0
        result.append(line)
    while result and result[-1] == "":
        result.pop()
    return "\n".join(result)


def iter_source_files(paths):
    for path in paths:
        if os.path.isfile(path):
            if path.endswith(EXTENSIONS):
                yield path
            continue
        for root, dirs, files in os.walk(path):
            dirs[:] = [d for d in dirs if d not in EXCLUDE_DIRS]
            for name in files:
                if name.endswith(EXTENSIONS):
                    yield os.path.join(root, name)


def main():
    args = [a for a in sys.argv[1:] if not a.startswith("--")]
    apply = "--apply" in sys.argv
    paths = args or ["."]

    files = list(iter_source_files(paths))
    total_removed = 0
    changed = 0
    for path in files:
        try:
            with open(path, "r", encoding="utf-8") as fh:
                original = fh.read()
        except (UnicodeDecodeError, OSError):
            continue
        stripped = collapse_blank_lines(strip_comments(original))
        removed = len(original) - len(stripped)
        if removed == 0:
            continue
        total_removed += removed
        changed += 1
        if apply:
            with open(path, "w", encoding="utf-8", newline="\n") as fh:
                fh.write(stripped + "\n")
        print(f"{path}: -{removed} chars")

    print(f"\nFiles changed: {changed}/{len(files)}")
    print(f"Total chars removed: {total_removed}")
    if not apply:
        print("DRY RUN — re-run with --apply to write changes.")


if __name__ == "__main__":
    main()
