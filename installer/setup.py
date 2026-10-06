#!/usr/bin/env python3
"""EldenCraft installer. Standard library only; no account tokens or game files are bundled."""
from __future__ import annotations

import argparse
import configparser
import hashlib
import json
import os
import platform
import re
import shlex
import shutil
import ssl
import stat
import struct
import subprocess
import sys
import tarfile
import tempfile
import time
import urllib.parse
import urllib.request
import uuid
import zipfile
from dataclasses import dataclass, asdict
from pathlib import Path, PurePosixPath, PureWindowsPath

APP_ID = "1245620"
HERE = Path(__file__).resolve().parent
SIGS = {0x448910: bytes.fromhex("4c8bdc5553565741564157498d6b88"),
        0x448A54: bytes.fromhex("418b9628020000"), 0x43D250: bytes.fromhex("488b4108c3")}


class SetupError(Exception):
    pass


def digest(path: Path) -> str:
    with path.open("rb") as f:
        return hashlib.file_digest(f, "sha256").hexdigest()


def load_json(path: Path):
    return json.loads(path.read_text(encoding="utf-8"))


def atomic_write(path: Path, content: bytes):
    path.parent.mkdir(parents=True, exist_ok=True)
    with tempfile.NamedTemporaryFile(dir=path.parent, delete=False) as f:
        temporary = Path(f.name)
        f.write(content)
    try:
        os.replace(temporary, path)
    finally:
        temporary.unlink(missing_ok=True)


def pe_regions(exe: Path):
    """Read PE headers and map RVAs to disk without loading the executable."""
    with exe.open("rb") as f:
        dos = f.read(64)
        if len(dos) != 64 or dos[:2] != b"MZ":
            raise SetupError(f"Not a Windows executable: {exe}")
        offset = struct.unpack_from("<I", dos, 60)[0]
        f.seek(offset)
        header = f.read(24)
        if len(header) != 24 or header[:4] != b"PE\0\0":
            raise SetupError("The game executable has an invalid PE header.")
        machine, count = struct.unpack_from("<HH", header, 4)
        if machine != 0x8664 or not 0 < count < 100:
            raise SetupError("EldenCraft needs the 64-bit Windows edition of Elden Ring.")
        optional_size = struct.unpack_from("<H", header, 20)[0]
        f.seek(optional_size, 1)
        regions = []
        for _ in range(count):
            section = f.read(40)
            if len(section) != 40:
                raise SetupError("The executable section table is incomplete.")
            size, rva, raw_size, raw = struct.unpack_from("<IIII", section, 8)
            regions.append((rva, raw_size, raw))
        return regions


def check_game(exe: Path):
    regions = pe_regions(exe)
    with exe.open("rb") as f:
        for rva, expected in SIGS.items():
            region = next((r for r in regions if r[0] <= rva and rva + len(expected) <= r[0] + r[1]), None)
            if region is None:
                raise SetupError("This game build is unsupported. This package targets Elden Ring 2.7.1.")
            f.seek(region[2] + rva - region[0])
            if f.read(len(expected)) != expected:
                raise SetupError("This game build is unsupported. Install the EldenCraft release matching your game; this package targets 2.7.1.")


def vdf(text: str) -> dict:
    tokens = re.findall(r'"((?:\\.|[^"\\])*)"|([{}])', re.sub(r"(?m)^\s*//.*$", "", text))
    stream = iter([brace if brace else value.replace(r"\\", "\\").replace(r'\"', '"') for value, brace in tokens])

    def group():
        out = {}
        for key in stream:
            if key == "}":
                return out
            value = next(stream, None)
            if value is None:
                raise SetupError("Steam's configuration file is incomplete.")
            out[key] = group() if value == "{" else value
        return out
    return group()


def find_key(tree: dict, key: str):
    for k, value in tree.items():
        if k.casefold() == key.casefold():
            return value
        if isinstance(value, dict):
            found = find_key(value, key)
            if found is not None:
                return found
    return None


def steam_roots(system: str, home: Path) -> list[Path]:
    if system == "Windows":
        roots = []
        try:
            import winreg
            with winreg.OpenKey(winreg.HKEY_CURRENT_USER, r"Software\Valve\Steam") as key:
                roots.append(Path(winreg.QueryValueEx(key, "SteamPath")[0]))
        except (ImportError, OSError):
            pass
        roots += [Path(os.environ.get("ProgramFiles(x86)", "C:/Program Files (x86)")) / "Steam",
                  Path(os.environ.get("ProgramFiles", "C:/Program Files")) / "Steam"]
        return roots
    if system == "Linux":
        return [home / ".local/share/Steam", home / ".steam/steam", home / ".steam/root",
                home / ".var/app/com.valvesoftware.Steam/.local/share/Steam"]
    return list((home / "Library/Application Support/CrossOver/Bottles").glob("*/drive_c/Program Files (x86)/Steam"))


def windows_to_host(path: str, prefix: Path) -> Path:
    p = PureWindowsPath(path)
    if not p.drive:
        raise SetupError(f"Expected an absolute Windows path: {path}")
    drive = p.drive.rstrip(":").lower()
    root = prefix / ("drive_c" if drive == "c" else "dosdevices/" + drive + ":")
    return root.joinpath(*p.parts[1:]).resolve()


def libraries(root: Path, prefix: Path | None = None) -> list[Path]:
    out = [root]
    file = root / "steamapps/libraryfolders.vdf"
    if file.exists():
        data = vdf(file.read_text(encoding="utf-8"))
        folder = find_key(data, "libraryfolders") or {}
        for value in folder.values():
            if isinstance(value, dict) and "path" in value:
                p = windows_to_host(value["path"], prefix) if prefix else Path(value["path"])
                if p not in out:
                    out.append(p)
    return out


def bottle_for(root: Path) -> Path:
    return next((p for p in root.parents if (p / "cxbottle.conf").exists()), root.parents[2])


@dataclass
class Target:
    system: str
    game: str
    steam: str
    library: str
    prefix: str = ""
    bottle: str = ""
    proton: str = ""
    crossover: str = ""
    local_appdata: str = ""


def choose(items: list, label: str, noninteractive: bool = True):
    if len(items) != 1:
        raise SetupError(f"Expected exactly one {label}; found {len(items)}. Use a path override for a custom installation.")
    return items[0]


def find_proton(target: Target, override: str, noninteractive: bool) -> Path:
    if override:
        return Path(override).expanduser().resolve()
    root = Path(target.steam)
    candidates = list((root / "compatibilitytools.d").glob("*/proton"))
    for library in libraries(root):
        candidates += list((library / "steamapps/common").glob("Proton*/proton"))
    # Respect the per-game Steam override when present.
    config = root / "config/config.vdf"
    preferred = ""
    if config.exists():
        mapping = find_key(vdf(config.read_text(encoding="utf-8")), "CompatToolMapping") or {}
        preferred = mapping.get(APP_ID, mapping.get("0", {})).get("name", "")
    for candidate in candidates:
        tool = candidate.parent / "compatibilitytool.vdf"
        if preferred and tool.exists() and find_key(vdf(tool.read_text(encoding="utf-8")), preferred) is not None:
            return candidate.resolve()
    # An unset Steam override normally means Proton Experimental. No extra installer question.
    version_file = Path(target.prefix).parent / "version"
    used = version_file.read_text(errors="replace").strip().casefold() if version_file.exists() else ""
    for candidate in candidates:
        if used and candidate.parent.name.casefold() in used:
            return candidate.resolve()
    experimental = [p for p in candidates if "experimental" in p.parent.name.casefold()]
    if experimental:
        return experimental[0].resolve()
    if len(set(candidates)) == 1:
        return candidates[0].resolve()
    raise SetupError("Steam's selected Proton could not be determined. Select a compatibility tool for Elden Ring in Steam, or use --proton.")


def local_appdata(target: Target, override: str, noninteractive: bool) -> Path:
    if override:
        return Path(override).expanduser().resolve()
    if target.system == "Windows":
        value = os.environ.get("LOCALAPPDATA")
        if not value:
            raise SetupError("LOCALAPPDATA is not set. Run the installer as your normal Windows user.")
        return Path(value).resolve()
    users = Path(target.prefix) / "drive_c/users"
    preferred = users / ("steamuser" if target.system == "Linux" else "crossover")
    if preferred.is_dir():
        return preferred / "AppData/Local"
    choices = [p / "AppData/Local" for p in users.iterdir() if p.is_dir() and p.name.casefold() not in ("public", "default", "default user", "all users")]
    return Path(choose(choices, "Windows user folder", True))


def resolve_game(value: str) -> Path:
    path = Path(value.strip().strip('"').strip("'")).expanduser().resolve()
    if path.is_dir():
        if (path / "eldenring.exe").is_file():
            path /= "eldenring.exe"
        elif (path / "Game/eldenring.exe").is_file():
            path /= "Game/eldenring.exe"
    if path.name.casefold() != "eldenring.exe" or not path.is_file():
        raise SetupError("Choose the Elden Ring folder containing Game/eldenring.exe, the Game folder, or eldenring.exe itself.")
    return path


def target_from_path(exe: Path, system: str, home: Path, args) -> Target:
    steamapps = next((p for p in exe.parents if p.name.casefold() == "steamapps"), None)
    if steamapps is None:
        raise SetupError("The selected game is outside a Steam library. EldenCraft requires the Steam edition.")
    library = steamapps.parent
    root = Path(args.steam).expanduser().resolve() if args.steam else None
    bottle = Path(args.bottle).expanduser().resolve() if args.bottle else None
    if root is None:
        for candidate in steam_roots(system, home):
            if candidate.is_dir():
                candidate_bottle = bottle_for(candidate) if system == "Darwin" else None
                if library.resolve() in [p.resolve() for p in libraries(candidate, candidate_bottle)]:
                    root, bottle = candidate.resolve(), bottle or candidate_bottle
                    break
    if root is None and (library / "steam.exe").is_file():
        root = library
    if root is None:
        raise SetupError("Steam's installation could not be found for that game path. Open Steam once, or provide --steam for a custom installation.")
    prefix = Path(args.prefix).expanduser().resolve() if args.prefix else library / f"steamapps/compatdata/{APP_ID}/pfx"
    return Target(system, str(exe), str(root), str(library), str(bottle or "") if system == "Darwin" else str(prefix), str(bottle or ""))


def check_closed(system: str):
    """Executable names, never our command-line text or shell arguments."""
    if system == "Windows":
        raw = subprocess.check_output(["tasklist", "/FO", "CSV", "/NH"], text=True, errors="replace")
        import csv
        names = [r[0].casefold() for r in csv.reader(raw.splitlines()) if r]
    else:
        raw = subprocess.check_output(["ps", "-eo", "comm="], text=True)
        names = [Path(line.strip()).name.casefold() for line in raw.splitlines()]
    if any(any(name.startswith(prefix) for prefix in ("eldenring", "prismlauncher", "javaw", "java.exe", "me3-launcher")) for name in names):
        raise SetupError("Close Elden Ring, Prism and its Minecraft window before installing or repairing.")


def finish_target(target: Target, args):
    check_game(Path(target.game))
    if target.system == "Linux":
        if not (Path(target.prefix) / "drive_c").is_dir():
            raise SetupError("Start Elden Ring once from Steam, then close it and run setup again. Steam must create its Proton environment first.")
        target.proton = str(find_proton(target, args.proton, True))
        if not Path(target.proton).is_file():
            raise SetupError("The selected Proton executable is missing.")
    if target.system == "Darwin":
        target.crossover = str(Path(args.crossover or "/Applications/CrossOver.app/Contents/SharedSupport/CrossOver/bin/wine").expanduser())
        if not Path(target.crossover).is_file() or not (Path(target.bottle) / "cxbottle.conf").is_file():
            raise SetupError("Install CrossOver and Steam/Elden Ring in a CrossOver bottle first. Supply --bottle and --crossover for custom locations.")
    target.local_appdata = str(local_appdata(target, args.local_appdata, True).resolve())
    return target


def download(spec: dict, cache: Path, offline=False) -> Path:
    if not re.fullmatch(r"[a-f0-9]{64}", spec["sha256"]):
        raise SetupError("A dependency has an invalid checksum.")
    cache.mkdir(parents=True, exist_ok=True)
    name = Path(urllib.parse.unquote(urllib.parse.urlparse(spec["url"]).path)).name
    file = cache / (spec["sha256"][:16] + "-" + name)
    if file.is_file() and digest(file) == spec["sha256"]:
        return file
    if offline:
        raise SetupError(f"Offline cache is missing {name}. Run once with internet access or use --download-only to prepare the cache.")
    if not spec["url"].startswith("https://"):
        raise SetupError("Installer downloads must use HTTPS.")
    print(f"Downloading {name} …", flush=True)
    request = urllib.request.Request(spec["url"], headers={"User-Agent": "EldenCraft-Setup/1"})
    part = file.with_name(file.name + ".part")
    try:
        context = ssl.create_default_context()
        if not context.get_ca_certs():
            # A portable Python on macOS may have no OpenSSL CA store. Its bundled pip
            # supplies Mozilla roots; keep certificate verification enabled.
            runtimes = [Path(sys.prefix)]
            if os.environ.get("ELDENCRAFT_SETUP_RUNTIME"):
                runtimes.insert(0, Path(os.environ["ELDENCRAFT_SETUP_RUNTIME"]))
            candidates = [p for runtime in runtimes for pattern in
                          ("Lib/site-packages/pip/_vendor/certifi/cacert.pem", "lib/python*/site-packages/pip/_vendor/certifi/cacert.pem")
                          for p in runtime.glob(pattern)]
            if not candidates:
                raise SetupError("The private Python runtime has no trusted CA certificates. Download a complete package again.")
            context.load_verify_locations(cafile=str(candidates[0]))
        with urllib.request.urlopen(request, timeout=60, context=context) as response, part.open("wb") as output:
            if not response.url.startswith("https://"):
                raise SetupError("A download redirected to an insecure URL.")
            shutil.copyfileobj(response, output)
        if digest(part) != spec["sha256"]:
            raise SetupError(f"Checksum mismatch for {name}. Nothing from this download was installed.")
        os.replace(part, file)
        return file
    finally:
        part.unlink(missing_ok=True)


def safe_member(name: str) -> PurePosixPath:
    p = PurePosixPath(name.replace("\\", "/"))
    if p.is_absolute() or ".." in p.parts or any(":" in part for part in p.parts):
        raise SetupError(f"Unsafe archive member: {name}")
    return p


def extract(archive: Path, destination: Path):
    destination.mkdir(parents=True, exist_ok=True)
    if zipfile.is_zipfile(archive):
        with zipfile.ZipFile(archive) as z:
            for entry in z.infolist():
                path = destination.joinpath(*safe_member(entry.filename).parts)
                if stat.S_ISLNK(entry.external_attr >> 16):
                    raise SetupError("Dependency archives may not contain symbolic links.")
                if entry.is_dir():
                    path.mkdir(parents=True, exist_ok=True)
                else:
                    atomic_write(path, z.read(entry))
    else:
        with tarfile.open(archive, "r:gz") as t:
            for entry in t:
                path = destination.joinpath(*safe_member(entry.name).parts)
                if entry.isdir():
                    path.mkdir(parents=True, exist_ok=True)
                elif entry.isfile():
                    with t.extractfile(entry) as f:
                        atomic_write(path, f.read())
                    path.chmod(entry.mode & 0o777)
                else:
                    raise SetupError("Dependency archives may contain only regular files and folders.")


class Changes:
    """Rollback only managed files written by this run; saves and accounts are never selected."""
    def __init__(self, root: Path):
        self.root = root.resolve()
        self.backup = root / "backups" / (time.strftime("%Y%m%d-%H%M%S") + "-" + uuid.uuid4().hex[:8])
        self.changed = []

    def before(self, path: Path):
        path = path.resolve()
        if not path.is_relative_to(self.root) or "saves" in path.parts or path.name == "accounts.json":
            raise SetupError(f"Refusing to replace an unmanaged file: {path}")
        if any(p == path for p, _ in self.changed):
            return
        old = self.backup / path.relative_to(self.root) if path.exists() else None
        if old:
            old.parent.mkdir(parents=True, exist_ok=True)
            shutil.copy2(path, old)
        self.changed.append((path, old))

    def write(self, path: Path, data: bytes):
        if path.is_file() and path.read_bytes() == data:
            return
        self.before(path)
        atomic_write(path, data)

    def copy(self, src: Path, dst: Path):
        if dst.is_file() and digest(src) == digest(dst):
            return
        self.before(dst)
        dst.parent.mkdir(parents=True, exist_ok=True)
        temporary = dst.with_name(dst.name + ".eldencraft-new")
        try:
            shutil.copy2(src, temporary)
            os.replace(temporary, dst)
        finally:
            temporary.unlink(missing_ok=True)

    def remove(self, path: Path):
        self.before(path)
        path.unlink()

    def tree(self, src: Path, dst: Path):
        for file in sorted(src.rglob("*")):
            if file.is_file():
                self.copy(file, dst / file.relative_to(src))

    def rollback(self):
        for file, old in reversed(self.changed):
            if old:
                shutil.copy2(old, file)
            else:
                file.unlink(missing_ok=True)


def update_ini(file: Path, values: dict[str, str], changes: Changes):
    # Qt INI contains % escapes and case-sensitive names; preserve unrelated sections/keys.
    config = configparser.ConfigParser(interpolation=None, strict=False)
    config.optionxform = str
    if file.exists():
        config.read(file, encoding="utf-8")
    if "General" not in config:
        config.add_section("General")
    config["General"].update(values)
    import io
    output = io.StringIO()
    config.write(output, space_around_delimiters=False)
    changes.write(file, output.getvalue().encode())


def windows_path(path: Path, target: Target) -> str:
    if target.system == "Windows":
        return str(path).replace("/", "\\")
    drive = (Path(target.prefix) / "drive_c").resolve()
    path = path.resolve()
    return str(PureWindowsPath("C:/") / str(path.relative_to(drive))) if path.is_relative_to(drive) else "Z:" + str(path).replace("/", "\\")


def instance(changes: Changes, root: Path, target: Target, manifest: dict, preparing=False):
    folder = root / "Prism/instances/EldenCraft"
    java = windows_path(root / "Java/bin/javaw.exe", target)
    update_ini(folder / "instance.cfg", {
        "InstanceType": "OneSix", "name": "EldenCraft", "iconKey": "default", "ConfigVersion": "1.3",
        "OverrideJavaLocation": "true", "JavaPath": java.replace("\\", "/"),
        "AutomaticJava": "false", "JavaVersion": manifest["java_version"],
        "JavaArchitecture": "64", "JavaRealArchitecture": "amd64",
        "OverrideJavaArgs": "true", "JvmArgs": "--enable-native-access=ALL-UNNAMED -Deldencraft.startHidden=" + ("false" if preparing else "true"),
        "OverrideMemory": "true", "MinMemAlloc": "512", "MaxMemAlloc": "4096",
        "OverrideConsole": "true", "ShowConsole": "false", "ShowConsoleOnError": "true", "AutoCloseConsole": "true",
    }, changes)
    pack = {"formatVersion": 1, "components": [
        {"uid": "net.minecraft", "version": manifest["minecraft_version"], "important": True},
        {"uid": "net.fabricmc.fabric-loader", "version": manifest["fabric_loader"]},
    ]}
    changes.write(folder / "mmc-pack.json", (json.dumps(pack, indent=2) + "\n").encode())
    # Native bridge opens the world itself. No world/saves folder is supplied by the installer.


def verify_payload(bundle: Path) -> dict:
    manifest = load_json(bundle / "payload/manifest.json")
    for name, expected in manifest["files"].items():
        safe_member(name)
        file = bundle / "payload" / name
        if not file.is_file() or digest(file) != expected:
            raise SetupError(f"The EldenCraft package is incomplete or modified: {name}. Download it again.")
    pe_regions(bundle / "payload/eldencraft.dll")
    with zipfile.ZipFile(bundle / "payload/eldencraft.jar") as z:
        mod = json.loads(z.read("fabric.mod.json"))
        if mod["id"] != "eldencraft" or mod["version"] != manifest["mod_version"]:
            raise SetupError("The bundled Minecraft mod does not match the package.")
    return manifest


def commands(root: Path, target: Target, prepare=False) -> tuple[list[str], dict]:
    env = os.environ.copy()
    prism = root / "Prism/prismlauncher.exe"
    if prepare:
        args = [windows_path(prism, target), "--dir", windows_path(prism.parent, target), "--launch", "EldenCraft", "--show-window"]
    else:
        me3 = root / "me3/bin/me3.exe"
        args = [windows_path(me3, target), "launch", "-g", "eldenring", "-p", windows_path(root / "eldencraft.me3", target),
                "-e", windows_path(Path(target.game), target), "--online", "false"]
    if target.system == "Windows":
        return args, env
    if target.system == "Darwin":
        return [target.crossover, "--bottle", Path(target.bottle).name, *args], env
    env.update({"STEAM_COMPAT_DATA_PATH": str(Path(target.prefix).parent), "STEAM_COMPAT_CLIENT_INSTALL_PATH": target.steam,
                "SteamAppId": APP_ID, "SteamGameId": APP_ID})
    if prepare:
        return [target.proton, "run", *args], env
    # Linux me3 detects Steam's selected Proton and owns the game/Wine session.
    return [str(root / "me3/bin/me3"), "--steam-dir", target.steam,
            "--windows-binaries-dir", str(root / "me3/bin/win64"), "launch", "-g", "eldenring", "-p", str(root / "eldencraft.me3"),
            "-e", target.game, "--online", "false"], env


def prepare(root: Path, target: Target, manifest: dict):
    print("\nPrism will open. Sign in with the Microsoft account that owns Minecraft Java Edition.")
    print("Let EldenCraft download and reach Minecraft's title screen, then close Minecraft and Prism.")
    changes = Changes(root)
    instance(changes, root, target, manifest, preparing=True)
    try:
        cmd, env = commands(root, target, prepare=True)
        subprocess.run(cmd, env=env, cwd=root, check=True)
        # Confirm an actual Minecraft start, rather than just a closed sign-in dialog.
        log = root / "Prism/instances/EldenCraft/.minecraft/logs/latest.log"
        if not log.exists() or log.stat().st_mtime < manifest.get("prepare_started", time.time()) - 5:
            raise SetupError("Minecraft did not finish its first start. Run the 'Finish Minecraft setup' shortcut to retry.")
        content = log.read_text(encoding="utf-8", errors="replace")
        if not re.search(r"Sound engine started|OpenAL initialized", content) or re.search(r"ReportedException|Crash report saved|Uncaught exception|Failed to create.*(?:window|device)", content, re.I):
            raise SetupError("Minecraft's first start did not complete cleanly. Check its latest.log, then use Finish Minecraft setup to retry.")
        check_closed(target.system)
        state = load_json(root / "installation.json")
        state["prepared"] = True
        atomic_write(root / "installation.json", (json.dumps(state, indent=2) + "\n").encode())
    finally:
        instance(changes, root, target, manifest, preparing=False)


def installed_command(root: Path, action: str) -> str:
    runtime = root / "Setup/python"
    python = runtime / ("python.exe" if os.name == "nt" else "bin/python3")
    return " ".join(shlex.quote(str(p)) for p in (python, root / "Setup/setup.py", action, "--installation", root / "installation.json"))


def shortcuts(root: Path, target: Target, changes: Changes):
    if target.system == "Windows":
        for action, name in (("play", "Play EldenCraft"), ("prepare", "Finish Minecraft setup"), ("doctor", "Check EldenCraft")):
            text = f'@echo off\r\n"%~dp0Setup\\python\\python.exe" "%~dp0Setup\\setup.py" {action} --installation "%~dp0installation.json"\r\nif errorlevel 1 pause\r\n'
            changes.write(root / (name + ".cmd"), text.encode())
        # Shell COM resolves localized Desktop/Start Menu paths and creates real .lnk shortcuts.
        quote = lambda value: "'" + str(value).replace("'", "''") + "'"
        script = "$w=New-Object -ComObject WScript.Shell;" + ";".join(
            f"$s=$w.CreateShortcut((Join-Path $w.SpecialFolders.Item({quote(location)}) 'EldenCraft.lnk'));$s.TargetPath={quote(root / 'Play EldenCraft.cmd')};$s.WorkingDirectory={quote(root)};$s.Save()"
            for location in ("Desktop", "Programs"))
        subprocess.run(["powershell.exe", "-NoProfile", "-Command", script], check=True)
    else:
        for action, name in (("play", "Play EldenCraft"), ("prepare", "Finish Minecraft setup"), ("doctor", "Check EldenCraft")):
            file = root / (name + (".command" if target.system == "Darwin" else ".sh"))
            changes.write(file, ("#!/bin/sh\n" + installed_command(root, action) + '\ncode=$?\nif [ "$code" -ne 0 ]; then printf "Press Enter to close. "; read -r answer; fi\nexit "$code"\n').encode())
            file.chmod(0o755)
        if target.system == "Linux":
            for directory in (Path.home() / ".local/share/applications", Path.home() / "Desktop"):
                directory.mkdir(parents=True, exist_ok=True)
                file = directory / "EldenCraft.desktop"
                # Desktop Exec is not shell syntax. Quote each argument, escape its reserved chars.
                esc = lambda s: '"' + str(s).replace("\\", "\\\\").replace('"', '\\"').replace("$", "\\$").replace("`", "\\`").replace("%", "%%") + '"'
                file.write_text("[Desktop Entry]\nType=Application\nName=EldenCraft\nComment=Play EldenCraft offline\nTerminal=true\nExec=" + esc(root / "Play EldenCraft.sh") + "\nCategories=Game;\n")
                file.chmod(0o755)
        else:
            desktop = Path.home() / "Desktop/Play EldenCraft.command"
            desktop.write_text("#!/bin/sh\nexec " + shlex.quote(str(root / "Play EldenCraft.command")) + "\n")
            desktop.chmod(0o755)


def install(bundle: Path, target: Target, deps: dict, manifest: dict, cache: Path, offline=False):
    check_closed(target.system)
    root = Path(target.local_appdata) / "EldenCraft"
    downloads = {key: download(deps[key], cache, offline) for key in
                 ("prism", "java", "fabric_api", "me3_linux" if target.system == "Linux" else "me3_windows")}
    # Stage verified archives before touching an existing installation.
    with tempfile.TemporaryDirectory(prefix="eldencraft-stage-") as temp:
        temp = Path(temp)
        for key in ("prism", "java", "me3_linux" if target.system == "Linux" else "me3_windows"):
            extract(downloads[key], temp / key)
        prism = choose(list((temp / "prism").rglob("prismlauncher.exe")), "Prism executable", True).parent
        java = choose(list((temp / "java").rglob("javaw.exe")), "Java runtime", True).parent.parent
        loader = temp / ("me3_linux" if target.system == "Linux" else "me3_windows")
        check_closed(target.system)
        changes = Changes(root)
        try:
            changes.tree(prism, root / "Prism")
            changes.tree(java, root / "Java")
            changes.tree(loader, root / "me3")
            changes.copy(bundle / "payload/eldencraft.dll", root / "native/eldencraft.dll")
            mods = root / "Prism/instances/EldenCraft/.minecraft/mods"
            mods.mkdir(parents=True, exist_ok=True)
            managed = {"eldencraft.jar", "fabric-api.jar"}
            for existing in mods.glob("*.jar"):
                try:
                    with zipfile.ZipFile(existing) as z:
                        mod = json.loads(z.read("fabric.mod.json"))
                    if mod.get("id") in ("eldencraft", "fabric-api") and existing.name not in managed:
                        changes.remove(existing)
                except (KeyError, zipfile.BadZipFile, json.JSONDecodeError):
                    pass
            changes.copy(bundle / "payload/eldencraft.jar", mods / "eldencraft.jar")
            changes.copy(downloads["fabric_api"], mods / "fabric-api.jar")
            instance(changes, root, target, manifest)
            changes.write(root / "eldencraft.me3", b'profileVersion = "v1"\nstart_online = false\n\n[[supports]]\ngame = "eldenring"\n\n[[natives]]\npath = "native/eldencraft.dll"\n')
            for name in ("setup.py", "dependencies.json", "README.md", "THIRD_PARTY.md"):
                changes.copy(bundle / name, root / "Setup" / name)
            # Bootstraps download a private Python. Persist it so shortcuts need no system Python.
            runtime = Path(os.environ.get("ELDENCRAFT_SETUP_RUNTIME", sys.prefix))
            if not (runtime / "INSTALLER_RUNTIME").is_file():
                raise SetupError("Use this package's Install launcher so setup has a private Python runtime for its shortcuts.")
            changes.tree(runtime, root / "Setup/python")
            changes.copy(bundle / "payload/manifest.json", root / "Setup/manifest.json")
            previous = load_json(root / "installation.json") if (root / "installation.json").exists() else {}
            state = {"format": 1, "target": asdict(target), "root": str(root), "manifest": manifest,
                     "prepared": previous.get("prepared", False), "installed_at": time.time()}
            changes.write(root / "installation.json", (json.dumps(state, indent=2) + "\n").encode())
            shortcuts(root, target, changes)
        except BaseException:
            changes.rollback()
            raise
    print(f"\nInstalled EldenCraft in {root}")
    return root


def doctor(root: Path, target: Target, manifest: dict):
    check_game(Path(target.game))
    pairs = {"eldencraft.dll": root / "native/eldencraft.dll", "eldencraft.jar": root / "Prism/instances/EldenCraft/.minecraft/mods/eldencraft.jar"}
    for name, path in pairs.items():
        if not path.is_file() or digest(path) != manifest["files"][name]:
            raise SetupError(f"{name} is missing or changed. Run Install to repair.")
    for path in (root / "Prism/prismlauncher.exe", root / "Java/bin/javaw.exe", root / "eldencraft.me3",
                 root / "Prism/instances/EldenCraft/.minecraft/mods/fabric-api.jar",
                 root / ("me3/bin/me3" if target.system == "Linux" else "me3/bin/me3.exe")):
        if not path.is_file():
            raise SetupError(f"A required component is missing: {path}. Run Install to repair.")
    print("Game build, matching mod files, Java, Prism, Fabric API and mod loader: OK.")


def parser():
    p = argparse.ArgumentParser(description="Install EldenCraft for Windows, Linux/Proton or macOS/CrossOver.")
    p.add_argument("action", nargs="?", default="install", choices=("install", "plan", "doctor", "prepare", "play"))
    p.add_argument("--bundle", type=Path, default=HERE)
    p.add_argument("--installation", type=Path)
    p.add_argument("--game", help="Elden Ring folder, Game folder or eldenring.exe path")
    p.add_argument("--steam", help="Steam installation directory")
    p.add_argument("--prefix", help="Linux Proton pfx directory")
    p.add_argument("--bottle", help="Full path to a CrossOver bottle")
    p.add_argument("--proton", default="", help="Explicit path to Steam's selected Proton script")
    p.add_argument("--crossover", default="", help="Explicit CrossOver wine executable")
    p.add_argument("--local-appdata", default="", help="Override Windows Local AppData folder")
    p.add_argument("--cache", type=Path, default=HERE / "downloads")
    p.add_argument("--offline", action="store_true")
    p.add_argument("--download-only", action="store_true")
    p.add_argument("--no-prepare", action="store_true", help="Install files now; finish Minecraft sign-in/downloads later")
    return p


def main(argv=None):
    args = parser().parse_args(argv)
    try:
        if args.installation:
            state = load_json(args.installation)
            root, target, manifest = Path(state["root"]), Target(**state["target"]), state["manifest"]
            doctor(root, target, manifest)
            if args.action == "prepare":
                check_closed(target.system)
                manifest["prepare_started"] = time.time()
                prepare(root, target, manifest)
            elif args.action == "play":
                if not state["prepared"]:
                    raise SetupError("Finish Minecraft setup once using the matching shortcut, then select Play EldenCraft.")
                check_closed(target.system)
                cmd, env = commands(root, target)
                return subprocess.call(cmd, env=env, cwd=root)
            return 0
        system = platform.system()
        if system not in ("Windows", "Linux", "Darwin") or system != "Darwin" and platform.machine().casefold() not in ("amd64", "x86_64"):
            raise SetupError("This mod requires an x86-64 PC, or a Mac running the Windows game through CrossOver.")
        manifest = verify_payload(args.bundle)
        deps = load_json(args.bundle / "dependencies.json")
        if args.download_only:
            for key in ("prism", "java", "fabric_api", "me3_linux" if system == "Linux" else "me3_windows"):
                download(deps[key], args.cache, args.offline)
            return 0
        value = args.game or input("Elden Ring folder or eldenring.exe path: ")
        target = target_from_path(resolve_game(value), system, Path.home(), args)
        target = finish_target(target, args)
        if args.action == "plan":
            print(json.dumps({"target": asdict(target), "destination": str(Path(target.local_appdata) / "EldenCraft"),
                              "downloads": {k: v for k, v in deps.items() if not k.startswith("python_")}}, indent=2))
            return 0
        print("EldenCraft setup — offline game launch only. Existing saves and accounts are preserved.")
        if system == "Darwin":
            print("macOS/CrossOver compatibility is experimental until tested with your graphics backend.")
        print(f"Game: {target.game}\nInstall folder: {Path(target.local_appdata) / 'EldenCraft'}")
        root = install(args.bundle, target, deps, manifest, args.cache, args.offline)
        doctor(root, target, manifest)
        if not args.no_prepare and not load_json(root / "installation.json")["prepared"]:
            manifest["prepare_started"] = time.time()
            prepare(root, target, manifest)
        print("Setup complete. Use Play EldenCraft; Steam must be running. Minecraft setup can be retried with its shortcut.")
        return 0
    except (SetupError, OSError, ValueError, KeyError, subprocess.CalledProcessError, zipfile.BadZipFile, tarfile.TarError) as error:
        print(f"\nSetup needs attention: {error}", file=sys.stderr)
        return 1
    except (KeyboardInterrupt, EOFError):
        print("\nSetup cancelled.")
        return 1


if __name__ == "__main__":
    sys.exit(main())
