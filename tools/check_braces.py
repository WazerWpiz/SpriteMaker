#!/usr/bin/env python3
"""Static sanity check for Kotlin sources.

Counts { } ( ) [ ] per file, ignoring braces inside string literals and
line/block comments. Reports any file where the delimiters don't balance.
Not a compiler — a smoke detector for the class of bug that hits first.

Usage:  python3 tools/check_braces.py
"""
import os
import sys

ROOT = os.path.join(os.path.dirname(os.path.abspath(__file__)), "..")
SRC = os.path.join(ROOT, "app", "src", "main", "java")

PAIRS = {"{": "}", "(": ")", "[": "]"}
OPENS = set(PAIRS)
CLOSES = {v: k for k, v in PAIRS.items()}


def strip_code(text):
    """Yield code chars with comments and string/char literals removed."""
    i, n = 0, len(text)
    while i < n:
        c = text[i]
        if c == "/" and i + 1 < n and text[i + 1] == "/":
            while i < n and text[i] != "\n":
                i += 1
        elif c == "/" and i + 1 < n and text[i + 1] == "*":
            i += 2
            while i + 1 < n and not (text[i] == "*" and text[i + 1] == "/"):
                i += 1
            i += 2
        elif c in ('"', "'"):
            quote = c
            triple = text[i:i + 3] == quote * 3
            i += 3 if triple else 1
            while i < n:
                if triple and text[i:i + 3] == quote * 3:
                    i += 3
                    break
                if not triple and text[i] == quote and text[i - 1] != "\\":
                    i += 1
                    break
                i += 1
            else:
                pass
            yield " "
        else:
            yield c
            i += 1


def check_file(path):
    with open(path, "r", encoding="utf-8") as f:
        code = "".join(strip_code(f.read()))
    stack = []
    line = 1
    for ch in code:
        if ch == "\n":
            line += 1
        elif ch in OPENS:
            stack.append((ch, line))
        elif ch in CLOSES:
            if not stack:
                return f"line {line}: unexpected '{ch}'"
            op, ol = stack.pop()
            if op != CLOSES[ch]:
                return f"line {line}: '{ch}' closes '{op}' opened line {ol}"
    if stack:
        op, ol = stack[-1]
        return f"unclosed '{op}' opened line {ol}"
    return None


def main():
    failures = []
    count = 0
    for base, _, files in os.walk(SRC):
        for name in files:
            if not name.endswith(".kt"):
                continue
            path = os.path.join(base, name)
            count += 1
            err = check_file(path)
            if err:
                rel = os.path.relpath(path, ROOT)
                failures.append((rel, err))
    print(f"checked {count} kotlin files")
    if failures:
        print(f"FAIL: {len(failures)} file(s) with unbalanced delimiters")
        for rel, err in failures:
            print(f"  {rel}: {err}")
        return 1
    print("OK: all files balanced")
    return 0


if __name__ == "__main__":
    sys.exit(main())
