#!/usr/bin/env python3
"""Mirror of draw_sprite_from_ascii's normalisation + validation.

The Kotlin code does three things before it paints anything:
  1. normalise \r\n, \r and the literal two-character sequence \\n into real newlines
  2. collect points per colour, skipping '.' and ' ' and unknown characters
  3. validate: ragged rows, row count vs canvas height, and nothing-painted
This script reproduces exactly that so the rules can be checked without a device.
"""
import sys

SYMBOL = '.'


def normalise(raw: str) -> str:
    # Order matters: real CRLF first (so a real CR is not split), then real CR,
    # then the literal backslash forms. In Python "\r" is one character and
    # "\\r" is two, which is the distinction the Kotlin code makes too.
    return (raw.replace("\r\n", "\n").replace("\r", "\n")
               .replace("\\r\\n", "\n").replace("\\r", "\n").replace("\\n", "\n"))


def draw(ascii: str, legend: dict, canvas_w=32, canvas_h=32, clear=True,
         offset_x=0, offset_y=0):
    ascii = normalise(ascii)
    lines = ascii.split('\n')

    by_colour = {}
    for row, line in enumerate(lines):
        for col, ch in enumerate(line):
            if ch == SYMBOL or ch == ' ':
                continue
            if ch not in legend:
                continue
            x, y = offset_x + col, offset_y + row
            if x < 0 or y < 0 or x >= canvas_w or y >= canvas_h:
                continue
            by_colour.setdefault(legend[ch], []).append((x, y))

    warnings = []
    lengths = [len(l) for l in lines]
    if len(set(lengths)) > 1:
        warnings.append(f"rows have different lengths {sorted(set(lengths))}")
    if clear and offset_x == 0 and offset_y == 0 and len(lines) < canvas_h:
        warnings.append(f"grid has {len(lines)} rows but the canvas is {canvas_h} tall")
    painted = sum(len(v) for v in by_colour.values())
    if painted == 0:
        warnings.append("nothing painted: legend has no entry for the characters used")
    return painted, len(by_colour), len(lines), warnings


HEART = "..R.\nRRRR\n.RR.\n.RR."


CASES = [
    ("clean newline", HEART, {"R": "FFFF0000"}, 32, 32),
    ("real CRLF", HEART.replace("\n", "\r\n"), {"R": "FFFF0000"}, 32, 32),
    ("bare real CR", HEART.replace("\n", "\r"), {"R": "FFFF0000"}, 32, 32),
    ("literal backslash-n", HEART.replace("\n", "\\n"), {"R": "FFFF0000"}, 32, 32),
    ("literal backslash-r-n", HEART.replace("\n", "\\r\\n"), {"R": "FFFF0000"}, 32, 32),
    ("ragged rows", "..R.\nRRRRRR\n.RR.", {"R": "FFFF0000"}, 32, 32),
    ("fewer rows than canvas height", "..R.", {"R": "FFFF0000"}, 32, 32),
    ("character missing from legend", "..R.\nGGGG", {"R": "FFFF0000"}, 32, 32),
    ("all transparent", "....\n....", {"R": "FFFF0000"}, 32, 32),
]

fails = 0
for name, ascii, legend, w, h in CASES:
    painted, colours, rows, warns = draw(ascii, legend, w, h)
    print(f"\n{name}")
    print(f"  painted={painted} colours={colours} rows={rows}")
    for x in warns:
        print(f"  warning: {x}")

print("\n=== assertions ===")


def check(label, cond):
    global fails
    print(("PASS  " if cond else "FAIL  ") + label)
    if not cond:
        fails += 1


# Every newline variant must yield the identical pixel count and row count.
variants = [draw(c[1], c[2])[0:3] for c in CASES[0:5]]
check("newline, real CRLF, real CR, \\n, and \\r\\n all give the same result",
      len(set(variants)) == 1 and variants[0][:3] == (9, 1, 4))

p, c, r, warns = draw(CASES[5][1], CASES[5][2])
check("ragged rows are reported", any("different lengths" in x for x in warns))

p, c, r, warns = draw(CASES[6][1], CASES[6][2])
check("short grid is reported against the canvas height",
      any("but the canvas is" in x for x in warns))

p, c, r, warns = draw(CASES[7][1], CASES[7][2])
check("characters with no legend entry are skipped, the rest still paints",
      p == 1 and not any("nothing painted" in x for x in warns))

p, c, r, warns = draw(CASES[8][1], CASES[8][2])
check("all-transparent grid says nothing was painted",
      p == 0 and any("nothing painted" in x for x in warns))

print(f"\n{('ALL PASSED' if fails == 0 else str(fails) + ' FAILED')}")
sys.exit(1 if fails else 0)