#!/usr/bin/env python3
"""Draws EldenCraft's branding (banner, icon, wordmark) as SVG, and as PNG when rsvg-convert is installed.

Original artwork made of square blocks: a golden tree over a dark night, with a small blocky silhouette at
its foot. No Elden Ring or Minecraft logos, fonts or textures are used; the lettering is a built-in 5x7 grid.
Usage: tools/generate_branding.py   (writes branding/*.svg and branding/*.png)
"""
from pathlib import Path
import math
import random
import shutil
import subprocess

OUT = Path(__file__).resolve().parents[1] / "branding"

GOLD = ["#fbeab0", "#f3d584", "#e9bd55", "#d9a238", "#bd8329", "#8f5f1d"]
FONT = {
    "A": ".###. #...# #...# ##### #...# #...# #...#", "B": "####. #...# #...# ####. #...# #...# ####.",
    "C": ".###. #...# #.... #.... #.... #...# .###.", "D": "####. #...# #...# #...# #...# #...# ####.",
    "E": "##### #.... #.... ####. #.... #.... #####", "F": "##### #.... #.... ####. #.... #.... #....",
    "G": ".###. #...# #.... #.### #...# #...# .###.", "H": "#...# #...# #...# ##### #...# #...# #...#",
    "I": "##### ..#.. ..#.. ..#.. ..#.. ..#.. #####", "L": "#.... #.... #.... #.... #.... #.... #####",
    "M": "#...# ##.## #.#.# #.#.# #...# #...# #...#", "N": "#...# ##..# #.#.# #..## #...# #...# #...#",
    "O": ".###. #...# #...# #...# #...# #...# .###.", "P": "####. #...# #...# ####. #.... #.... #....",
    "R": "####. #...# #...# ####. #.#.. #..#. #...#", "S": ".#### #.... #.... .###. ....# ....# ####.",
    "T": "##### ..#.. ..#.. ..#.. ..#.. ..#.. ..#..", "U": "#...# #...# #...# #...# #...# #...# .###.",
    "V": "#...# #...# #...# #...# #...# .#.#. ..#..", "W": "#...# #...# #...# #.#.# #.#.# ##.## #...#",
    "Y": "#...# #...# .#.#. ..#.. ..#.. ..#.. ..#..", " ": "..... ..... ..... ..... ..... ..... .....",
    "'": "..#.. ..#.. .#... ..... ..... ..... .....",
}


class Svg:
    def __init__(self, width, height):
        self.w, self.h, self.parts = width, height, []

    def rect(self, x, y, w, h, fill, opacity=1.0, rx=0):
        o = f' opacity="{opacity:.2f}"' if opacity < 1 else ""
        r = f' rx="{rx}"' if rx else ""
        self.parts.append(f'<rect x="{x:g}" y="{y:g}" width="{w:g}" height="{h:g}" fill="{fill}"{o}{r}/>')

    def block(self, x, y, size, fill):
        """One lit block: the colour, a light top edge and a dark bottom edge."""
        e = max(1, size // 6)
        self.rect(x, y, size, size, fill)
        self.rect(x, y, size, e, "#ffffff", 0.28)
        self.rect(x, y + size - e, size, e, "#000000", 0.30)

    def save(self, path, defs=""):
        body = "\n".join(self.parts)
        path.write_text(
            f'<svg xmlns="http://www.w3.org/2000/svg" width="{self.w}" height="{self.h}" viewBox="0 0 {self.w} {self.h}" '
            f'shape-rendering="crispEdges">\n<defs>{defs}</defs>\n{body}\n</svg>\n', encoding="utf-8")


def text_cells(text):
    """(column, row) of every lit cell of the text, and its width in cells."""
    cells, x = [], 0
    for ch in text.upper():
        rows = FONT[ch].split()
        for y, row in enumerate(rows):
            cells += [(x + i, y) for i, c in enumerate(row) if c == "#"]
        x += 6
    return cells, x - 1


def draw_text(svg, text, x, y, cell, gradient=None, shadow=True):
    cells, width = text_cells(text)
    for cx, cy in cells:
        colour = gradient[min(len(gradient) - 1, cy * len(gradient) // 7)] if gradient else "#f3d584"
        if shadow:
            svg.rect(x + cx * cell + cell // 3, y + cy * cell + cell // 3, cell, cell, "#000000", 0.55)
    for cx, cy in cells:
        colour = gradient[min(len(gradient) - 1, cy * len(gradient) // 7)] if gradient else "#f3d584"
        svg.rect(x + cx * cell, y + cy * cell, cell, cell, colour)
        svg.rect(x + cx * cell, y + cy * cell, cell, max(1, cell // 6), "#ffffff", 0.30)
    return width * cell


def background(svg, rng, stars, cell):
    cols, rows = svg.w // cell, svg.h // cell
    for _ in range(stars):
        x, y = rng.randrange(cols), rng.randrange(int(rows * 0.7))
        svg.rect(x * cell, y * cell, cell, cell, "#fff2c8", rng.choice([0.25, 0.4, 0.6]))


def tree(svg, rng, cell, cx, cy, rx, ry, trunk_top, ground):
    """A golden crown of overlapping lobes on arching branches over a straight trunk. Positions in cells."""
    bark = ["#a37a3c", "#8b6530", "#74511f", "#9a7236"]
    # lobes of the crown: wide and flat, tallest in the middle
    lobes = []
    n = 7
    for i in range(n):
        u = (i / (n - 1)) * 2 - 1  # -1 .. 1
        lobes.append((cx + u * rx * 0.78, cy + abs(u) ** 1.6 * ry * 0.9 - (1 - abs(u)) * ry * 0.15, ry * (0.78 + 0.32 * (1 - abs(u)))))
    # branches arch from the top of the trunk out under each lobe
    top = trunk_top
    for lx, ly, lr in lobes:
        steps = int(abs(lx - cx)) + int(top - ly) + 1
        for k in range(steps + 1):
            t = k / max(steps, 1)
            x = cx + (lx - cx) * t
            y = top - (top - (ly + lr * 0.55)) * (t ** 0.6)
            for dy in (0, 1):
                svg.block(round(x) * cell, round(y + dy) * cell, cell, bark[(round(x) + dy) % 4])
    # trunk: straight, widening a little toward the roots
    height = ground - trunk_top
    for row in range(height):
        y = trunk_top + row
        t = row / height
        half = 2 + int(t * 2.4)
        for x in range(cx - half, cx + half + 1):
            svg.block(x * cell, y * cell, cell, bark[(abs(x - cx) * 2 + row // 2) % 4])
        if row > height - 3:
            for side in (-1, 1):
                for k in range(1, (row - (height - 3)) * 2 + 1):
                    svg.block((cx + side * (half + k)) * cell, y * cell, cell, bark[2])
    # crown: every cell inside a lobe, ragged underneath, lit from the upper left
    for y in range(int(cy - ry * 1.5), int(cy + ry * 1.6)):
        for x in range(int(cx - rx - 6), int(cx + rx + 7)):
            depth = min(((x - lx) ** 2 + (y - ly) ** 2) / (lr * lr) for lx, ly, lr in lobes)
            ragged = depth + rng.uniform(-0.16, 0.16) + (0.10 if y > cy else 0)
            if ragged > 1.0 or rng.random() < 0.04 * (1 + depth):
                continue
            light = (cx - x) / (2 * rx) * 0.5 + (cy - y) / (2 * ry) * 0.5
            idx = round(2.2 - light * 3 + depth * 1.8 + rng.uniform(-0.8, 0.8))
            svg.block(x * cell, y * cell, cell, GOLD[max(0, min(len(GOLD) - 1, idx))])
    # drifting golden motes
    for _ in range(36):
        x = cx + rng.randint(-rx - 8, rx + 8)
        y = rng.randint(int(cy + ry - 2), ground - 1)
        svg.rect(x * cell + cell // 4, y * cell + cell // 4, cell // 2, cell // 2, rng.choice(GOLD[:3]), rng.choice([0.5, 0.75, 1]))


def ground_rows(svg, rng, cell, top, cols, rows):
    for y in range(top, rows):
        for x in range(cols):
            if y == top:
                colour = rng.choice(["#8a6a2a", "#9a7830", "#7a5c24"])
            else:
                colour = rng.choice(["#1d1620", "#221a26", "#181219", "#26202a"]) if y > top + 1 else rng.choice(["#2c2218", "#33271a"])
            svg.rect(x * cell, y * cell, cell, cell, colour)
        if y == top:
            for x in range(cols):
                svg.rect(x * cell, y * cell, cell, max(1, cell // 3), "#f3d584", 0.7)


def figure(svg, cell, x, ground, face):
    """A small blocky silhouette (8 wide, 16 tall) rim-lit on the side facing the tree."""
    body = "#0b0910"
    layout = [(2, 0, 4, 4), (2, 4, 4, 6), (0, 4, 2, 6), (6, 4, 2, 6), (2, 10, 2, 6), (4, 10, 2, 6)]
    top = ground - 16
    for dx, dy, w, h in layout:
        svg.rect((x + dx) * cell, (top + dy) * cell, w * cell, h * cell, body)
    rim_x = x + (7 if face > 0 else 0)
    for dy in range(0, 10):
        if dy < 4 and True:
            svg.rect((x + (5 if face > 0 else 2)) * cell, (top + dy) * cell, cell, cell, "#c9a24a")
        elif dy >= 4:
            svg.rect(rim_x * cell, (top + dy) * cell, cell, cell, "#c9a24a", 0.8)
    # a tiny sword held out toward the tree
    for k in range(5):
        svg.rect((x + (8 + k if face > 0 else -1 - k)) * cell, (top + 7) * cell, cell, cell, "#e9e2cf")


def glow_defs(gid, cx, cy, r):
    return (f'<radialGradient id="{gid}" cx="{cx}" cy="{cy}" r="{r}" gradientUnits="userSpaceOnUse">'
            '<stop offset="0" stop-color="#f3c35a" stop-opacity="0.55"/><stop offset="0.45" stop-color="#b8782a" stop-opacity="0.22"/>'
            '<stop offset="1" stop-color="#b8782a" stop-opacity="0"/></radialGradient>')


def sky_defs():
    return ('<linearGradient id="sky" x1="0" y1="0" x2="0" y2="1"><stop offset="0" stop-color="#06050a"/>'
            '<stop offset="0.6" stop-color="#150f1c"/><stop offset="1" stop-color="#2a1a1a"/></linearGradient>')


def banner():
    cell, W, H = 8, 1280, 640
    svg, rng = Svg(W, H), random.Random(11)
    svg.parts.append(f'<rect width="{W}" height="{H}" fill="url(#sky)"/>')
    tcx, tcy = 126, 26
    svg.parts.append(f'<rect width="{W}" height="{H}" fill="url(#glow)"/>')
    background(svg, rng, 150, cell)
    ground = 66
    tree(svg, rng, cell, tcx, tcy, 24, 11, tcy + 17, ground)
    ground_rows(svg, rng, cell, ground, W // cell, H // cell)
    figure(svg, cell, 106, ground, face=1)
    # title and subtitle
    tx = 64
    draw_text(svg, "ELDENCRAFT", tx, 196, 12, gradient=GOLD[:5])
    for i in range(0, 59 * 12, 12):
        svg.rect(tx + i, 296, 12, 4, GOLD[3], 0.8 if (i // 12) % 2 == 0 else 0.35)
    draw_text(svg, "MINECRAFT SURVIVAL", tx, 330, 5, gradient=["#f1ead8"], shadow=True)
    draw_text(svg, "IN THE LANDS BETWEEN", tx, 378, 5, gradient=["#f1ead8"], shadow=True)
    svg.save(OUT / "banner.svg", sky_defs() + glow_defs("glow", tcx * cell, tcy * cell, 520))


def icon():
    cell, S = 8, 512
    svg, rng = Svg(S, S), random.Random(5)
    svg.parts.append(f'<rect width="{S}" height="{S}" rx="72" fill="url(#sky)"/>')
    svg.parts.append(f'<rect width="{S}" height="{S}" rx="72" fill="url(#glow)"/>')
    background(svg, rng, 40, cell)
    ground = 51
    tree(svg, rng, cell, 32, 21, 21, 9, 36, ground)
    # clip the ground to the rounded corners by drawing it, then masking with corner pieces
    ground_rows(svg, rng, cell, ground, S // cell, S // cell)
    svg.save(OUT / "icon.svg", sky_defs() + glow_defs("glow", 256, 190, 330)
             + f'<clipPath id="r"><rect width="{S}" height="{S}" rx="72"/></clipPath>')
    text = (OUT / "icon.svg").read_text(encoding="utf-8")
    text = text.replace('shape-rendering="crispEdges">', 'shape-rendering="crispEdges">', 1)
    head, body = text.split("</defs>\n", 1)
    (OUT / "icon.svg").write_text(head + '</defs>\n<g clip-path="url(#r)">\n' + body.replace("</svg>", "</g>\n</svg>"), encoding="utf-8")


def wordmark():
    cell = 12
    width = 59 * cell + 16
    svg = Svg(width, 7 * cell + 22)
    draw_text(svg, "ELDENCRAFT", 4, 4, cell, gradient=GOLD[:5])
    svg.save(OUT / "wordmark.svg")


def rasterize():
    exe = shutil.which("rsvg-convert")
    if not exe:
        print("rsvg-convert not found: SVGs only")
        return
    for name in ("banner", "icon", "wordmark"):
        subprocess.run([exe, "-o", str(OUT / f"{name}.png"), str(OUT / f"{name}.svg")], check=True)
    subprocess.run([exe, "-w", "256", "-h", "256", "-o", str(OUT / "icon-256.png"), str(OUT / "icon.svg")], check=True)


if __name__ == "__main__":
    OUT.mkdir(exist_ok=True)
    banner()
    icon()
    wordmark()
    rasterize()
    print("wrote", ", ".join(sorted(p.name for p in OUT.iterdir())))
