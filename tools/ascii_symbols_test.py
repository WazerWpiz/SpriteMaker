#!/usr/bin/env python3
"""Mirror of get_canvas_ascii's symbol assignment.

The bug the user reported: a colour's character changed between frames, so a grid
copied from one reply next to a legend from another reply painted wrong colours.
The fix derives a preferred slot from the colour VALUE, then probes for a free
slot — never from the order colours happen to appear in.
"""
import sys

SYMBOLS = "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz"


def preferred_slot(argb):
    a = (argb >> 24) & 0xFF
    r = (argb >> 16) & 0xFF
    g = (argb >> 8) & 0xFF
    b = argb & 0xFF
    return ((a * 7 + r * 13 + g * 17 + b * 23) + (r ^ (g << 2) ^ b)) % len(SYMBOLS)


def assign(colours, w=64, h=64):
    """Mirror of the Kotlin: downsample until the palette fits the alphabet, then
    assign by preferred slot with a bounded probe (never an infinite loop)."""
    distinct = list(dict.fromkeys(colours))
    # Downsample guard: coarsen the sample grid until the palette fits.
    step_x, step_y = (w + 63) // 64, (h + 63) // 64
    while True:
        xs = list(range(0, w, max(step_x, 1)))
        ys = list(range(0, h, max(step_y, 1)))
        sampled = [colours[(y * w + x) % len(colours)] if colours else 0
                   for y in ys for x in xs]
        distinct = list(dict.fromkeys(sampled))
        if len(distinct) <= len(SYMBOLS) or (step_x >= w and step_y >= h):
            break
        step_x += 1
        step_y += 1

    out, taken = {}, set()
    for argb in distinct:
        if argb in out:
            continue
        slot = preferred_slot(argb)
        probes = 0
        while slot in taken and probes < len(SYMBOLS):
            slot = (slot + 1) % len(SYMBOLS)
            probes += 1
        taken.add(slot)
        out[argb] = SYMBOLS[slot]
    return out


RED = 0xFFFF0000
BLUE = 0xFF0000FF
GREEN = 0xFF00FF00
YELLOW = 0xFFFFFF00
PURPLE = 0xFF800080
WHITE = 0xFFFFFFFF

fails = 0


def check(label, cond):
    global fails
    print(("PASS  " if cond else "FAIL  ") + label)
    if not cond:
        fails += 1


# 1. The same colour set in a DIFFERENT scan order must give the same map.
order_a = assign([RED, BLUE, GREEN])
order_b = assign([GREEN, BLUE, RED])
order_c = assign([BLUE, GREEN, RED])
check("symbol map is independent of scan order", order_a == order_b == order_c)

# 2. Adding a colour later must not renumber the colours already assigned.
base = assign([RED, BLUE, GREEN])
grown = assign([RED, BLUE, GREEN, YELLOW])
unchanged = all(base[c] == grown[c] for c in base)
check("existing symbols survive a new colour being added", unchanged)

# 3. Reordering AND exting at the same time still keeps symbols stable.
shifted = assign([RED, PURPLE, GREEN, WHITE])
check("symbols stay put when the set grows and shuffles together",
      base[RED] in shifted.values() and base[GREEN] == shifted[GREEN])

# 4. No two colours ever share a symbol.
palette = [RED, BLUE, GREEN, YELLOW, PURPLE, WHITE,
           0xFF123456, 0xFF654321, 0xFF0A0A0A, 0xFFABCDEF]
m = assign(palette)
check(f"all {len(palette)} colours get distinct symbols",
      len(set(m.values())) == len(palette))

# 5. A wider palette than the alphabet must resolve (downsample) instead of hang.
#    64x64 grid saturated with distinct colours, as a photo-like sprite would be.
w = h = 64
many = [0xFF000000 | ((i * 2654435761) & 0xFFFFFF) for i in range(w * h)]
m2 = assign(many, w, h)
check("a 4096-colour canvas resolves instead of hanging, and never shares a symbol",
      len(m2) >= 1 and len(set(m2.values())) == len(m2))

print(f"\n{('ALL PASSED' if fails == 0 else str(fails) + ' FAILED')}")
sys.exit(1 if fails else 0)