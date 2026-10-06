#!/usr/bin/env python3
"""Package the compiled DLL/JAR and three installer entry points, never local accounts/worlds."""
from pathlib import Path
import argparse
import hashlib
import json
import re
import sys
import zipfile

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / "installer"))
from setup import pe_regions


def package(output: Path, dll: Path, jar: Path, release_version: str | None = None):
    for artifact, source in ((dll, ROOT / "game/src"), (jar, ROOT / "fabric/src")):
        if any(p.stat().st_mtime > artifact.stat().st_mtime for p in source.rglob("*") if p.is_file()):
            raise ValueError(f"Rebuild {artifact.name}: it is older than source files.")
    pe_regions(dll)
    with zipfile.ZipFile(jar) as z:
        mod = json.loads(z.read("fabric.mod.json"))
        if mod["id"] != "eldencraft":
            raise ValueError("Expected the EldenCraft JAR.")
    deps = json.loads((ROOT / "installer/dependencies.json").read_text())
    properties = dict(line.split("=", 1) for line in (ROOT / "fabric/gradle.properties").read_text().splitlines() if "=" in line and not line.startswith("#"))
    if mod["version"] != properties["version"]:
        raise ValueError("The JAR does not match gradle.properties; rebuild it.")
    protocol = int(re.search(r"pub const VERSION: u32 = (\d+)", (ROOT / "game/src/proto.rs").read_text()).group(1))
    manifest = {"format": 1, "mod_version": mod["version"], "protocol": protocol, "game_version": "2.7.1",
                "minecraft_version": properties["minecraft_version"], "fabric_loader": properties["loader_version"], "java_version": "25.0.4.1",
                "files": {"eldencraft.dll": hashlib.sha256(dll.read_bytes()).hexdigest(),
                          "eldencraft.jar": hashlib.sha256(jar.read_bytes()).hexdigest()}}
    if release_version:
        if not re.fullmatch(r"[0-9]+(?:\.[0-9]+){1,2}(?:-[A-Za-z0-9.-]+)?", release_version):
            raise ValueError("Invalid release version.")
        manifest["release_version"] = release_version
    script = (ROOT / "installer/Install.sh").read_text()
    for key in ("python_linux", "python_macos_arm", "python_macos_intel"):
        for field, suffix in (("url", "URL"), ("sha256", "SHA")):
            script = script.replace("@" + key.upper() + "_" + suffix + "@", deps[key][field])
    if "@PYTHON_" in script:
        raise ValueError("Unresolved runtime placeholder.")
    output.mkdir(parents=True, exist_ok=True)
    names = {"Windows": ("Install.cmd", "Install.ps1"), "Linux": ("Install.sh",), "macOS": ("Install.command", "Install.sh")}
    for system, entries in names.items():
        filename = output / f"EldenCraft-{release_version or mod['version']}-{system}-Setup.zip"
        with zipfile.ZipFile(filename, "w", zipfile.ZIP_DEFLATED, compresslevel=9) as z:
            for name in ("setup.py", "dependencies.json", "README.md", "THIRD_PARTY.md", *entries):
                content = script.encode() if name == "Install.sh" else (ROOT / "installer" / name).read_bytes()
                info = zipfile.ZipInfo("EldenCraft-Setup/" + name)
                info.external_attr = (0o100755 if name.endswith((".sh", ".command", ".py")) else 0o100644) << 16
                info.compress_type = zipfile.ZIP_DEFLATED
                z.writestr(info, content)
            z.write(ROOT / "LICENSE", "EldenCraft-Setup/LICENSE")
            z.write(dll, "EldenCraft-Setup/payload/eldencraft.dll")
            z.write(jar, "EldenCraft-Setup/payload/eldencraft.jar")
            z.writestr("EldenCraft-Setup/payload/manifest.json", json.dumps(manifest, indent=2) + "\n")
        print(filename)
    checksums = "".join(hashlib.sha256(p.read_bytes()).hexdigest() + "  " + p.name + "\n" for p in sorted(output.glob("*-Setup.zip")))
    (output / "SHA256SUMS.txt").write_text(checksums)


if __name__ == "__main__":
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument("--output", type=Path, default=ROOT / "dist/installers")
    p.add_argument("--dll", type=Path, default=ROOT / "game/target/x86_64-pc-windows-msvc/release/eldencraft.dll")
    p.add_argument("--jar", type=Path, default=ROOT / "fabric/build/libs/eldencraft-0.0.1.jar")
    p.add_argument("--release-version", help="Installer release label; the bundled mod retains its own version")
    args = p.parse_args()
    package(args.output, args.dll, args.jar, args.release_version)
