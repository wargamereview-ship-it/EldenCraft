#!/usr/bin/env python3
"""Regenerates fabric/src/main/resources/data/eldencraft/npc_names.tsv from Paramdex's ER NpcParam row names.

The game reports each enemy's NpcParam row id; its real name text is not reachable from the native SDK,
so the HUD looks the row id up in this table instead. Usage: tools/generate_npc_names.py [NpcParam.txt]
(with no argument the file is downloaded).
"""
from pathlib import Path
import re
import sys
import urllib.request

URL = "https://raw.githubusercontent.com/soulsmods/Paramdex/master/ER/Names/NpcParam.txt"
OUT = Path(__file__).resolve().parents[1] / "fabric/src/main/resources/data/eldencraft/npc_names.tsv"
SKIP = re.compile(r"dummy|^human$|^buddystone$|^\s*$", re.I)


def clean(name: str) -> str:
    name = re.sub(r"\s*\([^)]*\)\s*$", "", name.strip())  # "(Boss)", "(Siofra River)": where it is, not what it is
    return re.sub(r"\s+", " ", name)


def main():
    text = Path(sys.argv[1]).read_text(encoding="utf-8") if len(sys.argv) > 1 else urllib.request.urlopen(URL, timeout=60).read().decode("utf-8")
    rows = []
    for line in text.splitlines():
        row, _, name = line.partition(" ")
        if not row.isdigit():
            continue
        name = clean(name)
        if name and not SKIP.search(name) and "\t" not in name:
            rows.append((int(row), name))
    OUT.parent.mkdir(parents=True, exist_ok=True)
    OUT.write_text("# NpcParam row id -> name, from " + URL + " (tools/generate_npc_names.py)\n"
                   + "".join(f"{row}\t{name}\n" for row, name in rows), encoding="utf-8")
    print(f"{len(rows)} names -> {OUT}")


if __name__ == "__main__":
    main()
