#!/usr/bin/env python3
"""Reads Elden Ring's params straight from the game's regulation.bin, using Paramdex's field layouts.

    from er_params import Regulation
    reg = Regulation(game_dir)          # the ELDEN RING or ELDEN RING/Game folder
    rows = reg.rows("EquipParamWeapon")  # {row id: {field: value}}
    names = reg.names("EquipParamWeapon")

Needs the `cryptography` module and the `zstd` program. Paramdex files are downloaded once into
tools/.paramdex (not committed).
"""
import os
import re
import struct
import subprocess
import sys
import urllib.request
import xml.etree.ElementTree as ET
from pathlib import Path

# The regulation key every Elden Ring modding tool uses (SoulsFormats' SFUtil).
KEY = bytes.fromhex("99BFFC366A6BC8C6F5827D093602D676C42892A01C207FB024D3AF4E493FEF99")
PARAMDEX = "https://raw.githubusercontent.com/soulsmods/Paramdex/master/ER"
CACHE = Path(__file__).resolve().parent / ".paramdex"
# Param file name -> Paramdex def / names file, where they differ.
DEF_NAME = {"SpEffectParam": "SpEffect", "Magic": "MagicParam", "Bullet": "BulletParam", "AtkParam_Pc": "AtkParam",
            "AtkParam_Npc": "AtkParam",
            "ItemLotParam_map": "ItemLotParam", "ItemLotParam_enemy": "ItemLotParam"}

SCALAR = {"s8": "b", "u8": "B", "s16": "h", "u16": "H", "s32": "i", "u32": "I", "b32": "i",
          "f32": "f", "angle32": "f", "f64": "d", "dummy8": "B"}


def fetch(rel: str) -> Path:
    path = CACHE / rel
    if not path.exists():
        path.parent.mkdir(parents=True, exist_ok=True)
        with urllib.request.urlopen(f"{PARAMDEX}/{rel}") as response:
            path.write_bytes(response.read())
    return path


def find_regulation(game_dir: str) -> Path:
    for candidate in (Path(game_dir) / "regulation.bin", Path(game_dir) / "Game" / "regulation.bin"):
        if candidate.is_file():
            return candidate
    raise FileNotFoundError(f"no regulation.bin under {game_dir}")


def decrypt(path: Path) -> bytes:
    from cryptography.hazmat.primitives.ciphers import Cipher, algorithms, modes
    data = path.read_bytes()
    decryptor = Cipher(algorithms.AES(KEY), modes.CBC(data[:16])).decryptor()
    dcx = decryptor.update(data[16:]) + decryptor.finalize()
    if dcx[:4] != b"DCX\0" or dcx[0x28:0x2C] != b"ZSTD":
        raise ValueError("regulation.bin is not a zstd DCX after decryption")
    # DCS block: big-endian uncompressed and compressed sizes; the AES padding follows the frame.
    _, packed = struct.unpack_from(">II", dcx, 0x1C)
    out = subprocess.run(["zstd", "-d", "-c"], input=dcx[0x4C:0x4C + packed], capture_output=True, check=True).stdout
    if out[:4] != b"BND4":
        raise ValueError("regulation.bin did not unpack to a BND4")
    return out


def bnd4_files(b: bytes) -> dict[str, bytes]:
    count, = struct.unpack_from("<i", b, 0x0C)
    entry, = struct.unpack_from("<q", b, 0x20)
    files = {}
    for i in range(count):
        o = 0x40 + i * entry
        size, = struct.unpack_from("<q", b, o + 8)
        offset, _, name_at = struct.unpack_from("<IiI", b, o + 24)
        end = name_at
        while b[end:end + 2] != b"\0\0":
            end += 2
        name = b[name_at:end].decode("utf-16le").split("\\")[-1].removesuffix(".param")
        files[name] = b[offset:offset + size]
    return files


class Layout:
    """A Paramdex paramdef: decodes one row into {field: value}."""

    FIELD = re.compile(r"(\w+)\s+(\w+)(?:\[(\d+)\])?(?::(\d+))?")

    def __init__(self, xml: Path, reg_version: int):
        self.fields = []  # (name, kind, count, bits)
        for field in ET.parse(xml).getroot().iter("Field"):
            first, removed = field.findtext("FirstRegVersion"), field.findtext("RemovedRegVersion")
            if first and int(first) > reg_version or removed and int(removed) <= reg_version:
                continue
            kind, name, count, bits = self.FIELD.match(field.get("Def").split("=")[0].strip()).groups()
            self.fields.append((name, kind, int(count or 1), int(bits) if bits else None))

    @staticmethod
    def _bytes(kind: str) -> int:
        return struct.calcsize(SCALAR[kind]) if kind in SCALAR else 1

    def decode(self, data: bytes, at: int) -> dict:
        row, pos, unit = {}, at, None  # unit: [bytes, value, bits used] of the open bitfield
        for name, kind, count, bits in self.fields:
            if bits is not None:
                width = self._bytes(kind)
                if unit is None or unit[0] != width or unit[2] + bits > width * 8:
                    unit = [width, int.from_bytes(data[pos:pos + width], "little"), 0]
                    pos += width
                if kind != "dummy8":
                    row[name] = (unit[1] >> unit[2]) & ((1 << bits) - 1)
                unit[2] += bits
                continue
            unit = None
            if kind == "fixstr":
                row[name] = data[pos:pos + count].split(b"\0")[0].decode("shift_jis", "replace"); pos += count
            elif kind == "fixstrW":
                row[name] = data[pos:pos + count * 2].decode("utf-16le", "replace").split("\0")[0]; pos += count * 2
            elif kind == "dummy8":
                pos += count
            else:
                fmt, size = SCALAR[kind], self._bytes(kind)
                values = struct.unpack_from(f"<{count}{fmt}", data, pos)
                pos += size * count
                row[name] = values[0] if count == 1 else list(values)
        return row

    def size(self) -> int:
        total, unit = 0, None
        for _, kind, count, bits in self.fields:
            width = self._bytes(kind)
            if bits is not None:
                if unit is None or unit[0] != width or unit[1] + bits > width * 8:
                    total += width
                    unit = [width, 0]
                unit[1] += bits
                continue
            unit = None
            total += count * (2 if kind == "fixstrW" else width)
        return total


class Regulation:
    def __init__(self, game_dir: str):
        self.path = find_regulation(game_dir)
        self.files = bnd4_files(decrypt(self.path))
        self.version = self._version()

    def _version(self) -> int:
        # The BND4 header's version string, e.g. "11610000" for regulation 1.16.1.
        return 99999999

    def rows(self, param: str) -> dict[int, dict]:
        data = self.files[param]
        layout = Layout(fetch(f"Defs/{DEF_NAME.get(param, param)}.xml"), self.version)
        count, = struct.unpack_from("<H", data, 0x0A)
        entries = [struct.unpack_from("<iiq", data, 0x40 + i * 24) for i in range(count)]
        if count > 1:
            stride = entries[1][2] - entries[0][2]
            if stride != layout.size():
                raise ValueError(f"{param}: Paramdex layout is {layout.size()} bytes, rows are {stride}")
        return {row_id: layout.decode(data, offset) for row_id, _, offset in entries}

    def names(self, param: str) -> dict[int, str]:
        try:
            text = fetch(f"Names/{param}.txt").read_text(encoding="utf-8-sig")
        except Exception:
            return {}
        out = {}
        for line in text.splitlines():
            row_id, _, name = line.partition(" ")
            if row_id.lstrip("-").isdigit() and name.strip():
                out[int(row_id)] = name.strip()
        return out


if __name__ == "__main__":
    reg = Regulation(sys.argv[1] if len(sys.argv) > 1 else os.environ.get("ELDEN_RING", "."))
    for param in sys.argv[2:] or ["EquipParamWeapon"]:
        rows = reg.rows(param)
        print(param, len(rows), "rows")
