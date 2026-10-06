#!/usr/bin/env python3
"""Minecraft-style 18px status sprites from editable pixel masks (no external assets)."""
from pathlib import Path
from PIL import Image

ROOT = Path(__file__).resolve().parents[1]
OUT = ROOT / "fabric/src/main/resources/assets/eldencraft/textures/gui/sprites/mob_effect"
ICONS = {
    "poison": (0x87A83C, [
        "     ####     ", "     #++#     ", "     ####     ", "      ##      ",
        "     ####     ", "    #++++#    ", "   #++++++#   ", "  #++++++++#  ",
        "  #+++##+++#  ", "  #++####++#  ", "  #+++##+++#  ", "  #++++++++#  ", "   ########   "]),
    "scarlet_rot": (0xD35A49, [
        "   ##    ##   ", "  #++#  #++#  ", "  #++####++#  ", "   #++++++#   ",
        " ##++####++## ", "#+++##++##+++#", "#+++#+##+#+++#", " ##++#++#++## ",
        "   #++##++#   ", "  #++####++#  ", "  #++#  #++#  ", "   ##    ##   ", "      ##      "]),
    "blood_loss": (0xB52D42, [
        "      ##      ", "      ##      ", "     ####     ", "     #++#     ",
        "    #++++#    ", "   #++++++#   ", "   #++++++#   ", "  #++++++++#  ",
        "  #++++++++#  ", "  #++++++++#  ", "   #++++++#   ", "    ######    ", "              "]),
    "deathblight": (0x897247, [
        "    ######    ", "   #++++++#   ", "  #++++++++#  ", "  #++##++###  ",
        "  #++##++###  ", "   #++##++#   ", "    ######    ", "   #+####+#   ",
        "  #+##  ##+#  ", " ##+##  ##+## ", "#++# #  # #++#", " ##  #  #  ## ", "    ##  ##    "]),
    "frostbite": (0x8BCEE8, [
        "      ##      ", "  #   ##   #  ", "  ##  ##  ##  ", "   ## ## ##   ",
        "    ######    ", " #   ####   # ", "##############", "##############",
        " #   ####   # ", "    ######    ", "   ## ## ##   ", "  ##  ##  ##  ", "  #   ##   #  ", "      ##      "]),
    "sleep": (0xA692D2, [
        "     ####     ", "   ###  #     ", "  #++#        ", " #++#     ##  ",
        " #++#    #### ", "#++#      ##  ", "#++#          ", "#++#          ",
        "#++#      #   ", " #++#    ###  ", " #+++#    #   ", "  #+++##      ", "   #++++###   ", "    ######    "]),
    "madness": (0xF0BA42, [
        "      #       ", "     ##       ", "     #+#      ", "  # #++#      ",
        "  ###++## #   ", " #++++++###   ", " #+++##+++#   ", "#+++####+++#  ",
        "#++##++##++#  ", "#++#+##+#++#  ", " #++####++#   ", "  #++##++#    ", "   ######     "]),
}

def generate():
    OUT.mkdir(parents=True, exist_ok=True)
    for name, (color, rows) in ICONS.items():
        image = Image.new("RGBA", (18, 18))
        rgb = tuple((color >> shift) & 255 for shift in (16, 8, 0))
        light = tuple(min(255, int(c * 0.65 + 100)) for c in rgb)
        dark = tuple(int(c * 0.45) for c in rgb)
        for y, row in enumerate(rows, (18 - len(rows)) // 2):
            for x, char in enumerate(row, (18 - len(row)) // 2):
                if char != " ":
                    image.putpixel((x, y), (*((light if char == "+" else dark) if name != "frostbite" else rgb), 255))
        image.save(OUT / f"{name}.png")

if __name__ == "__main__":
    generate()
