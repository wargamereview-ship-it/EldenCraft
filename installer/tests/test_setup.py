import argparse
import hashlib
import importlib.util
import io
import json
import os
from pathlib import Path
import struct
import sys
import tarfile
import tempfile
import unittest
from unittest.mock import patch
import zipfile

SOURCE = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(SOURCE))
import setup as s


def fake_pe(file, signatures=False):
    image = bytearray(0x449000 if signatures else 512)
    image[:2] = b"MZ"
    struct.pack_into("<I", image, 60, 128)
    image[128:132] = b"PE\0\0"
    struct.pack_into("<HH", image, 132, 0x8664, 1)
    struct.pack_into("<H", image, 148, 0)
    struct.pack_into("<IIII", image, 160, len(image) - 512, 512, len(image) - 512, 512)
    if signatures:
        for at, data in s.SIGS.items():
            image[at:at + len(data)] = data
    file.parent.mkdir(parents=True, exist_ok=True)
    file.write_bytes(image)


def zip_at(file, entries):
    file.parent.mkdir(parents=True, exist_ok=True)
    with zipfile.ZipFile(file, "w") as z:
        for name, content in entries.items():
            z.writestr(name, content)


class InstallerTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory(prefix="eldencraft-installer-test-")
        self.root = Path(self.temp.name)

    def tearDown(self):
        self.temp.cleanup()

    def fixture(self):
        game = self.root / "Steam Library/steamapps/common/ELDEN RING/Game/eldenring.exe"
        fake_pe(game, True)
        steam = self.root / "Steam Client"
        steam.mkdir()
        prefix = self.root / "Steam Library/steamapps/compatdata/1245620/pfx"
        (prefix / "drive_c/users/steamuser/AppData/Local").mkdir(parents=True)
        target = s.Target("Linux", str(game), str(steam), str(self.root / "Steam Library"), str(prefix),
                          proton=str(self.root / "Proton Test/proton"),
                          local_appdata=str(prefix / "drive_c/users/steamuser/AppData/Local"))
        bundle = self.root / "package"
        (bundle / "payload").mkdir(parents=True)
        fake_pe(bundle / "payload/eldencraft.dll")
        zip_at(bundle / "payload/eldencraft.jar", {"fabric.mod.json": json.dumps({"id": "eldencraft", "version": "0.0.1"})})
        manifest = {"format": 1, "mod_version": "0.0.1", "protocol": 3, "java_version": "25.0.4.1",
                    "minecraft_version": "26.3", "fabric_loader": "0.19.5",
                    "files": {name: s.digest(bundle / "payload" / name) for name in ("eldencraft.dll", "eldencraft.jar")}}
        (bundle / "payload/manifest.json").write_text(json.dumps(manifest))
        for name in ("setup.py", "dependencies.json", "README.md", "THIRD_PARTY.md"):
            (bundle / name).write_text("fixture")
        archives = {}
        for key, entries in (("prism", {"prismlauncher.exe": "prism", "portable.txt": ""}),
                             ("java", {"jdk-25/bin/javaw.exe": "java"}),
                             ("fabric_api", {"fabric.mod.json": json.dumps({"id": "fabric-api"})})):
            archives[key] = self.root / (key + ".zip")
            zip_at(archives[key], entries)
        archives["me3_linux"] = self.root / "me3.tar.gz"
        with tarfile.open(archives["me3_linux"], "w:gz") as t:
            for name in ("bin/me3", "bin/win64/me3-launcher.exe", "bin/win64/me3_mod_host.dll"):
                entry = tarfile.TarInfo(name); entry.size = 3; entry.mode = 0o755
                t.addfile(entry, io.BytesIO(b"me3"))
        runtime = self.root / "python"
        (runtime / "bin").mkdir(parents=True)
        (runtime / "bin/python3").write_text("fixture")
        (runtime / "INSTALLER_RUNTIME").touch()
        deps = {key: {"key": key} for key in archives}
        return target, bundle, manifest, archives, runtime, deps

    def test_one_path_accepts_game_root_game_folder_and_executable(self):
        t, *_ = self.fixture()
        exe = Path(t.game)
        for path in (exe, exe.parent, exe.parent.parent):
            self.assertEqual(s.resolve_game(str(path)), exe)

    def test_game_fingerprints_reject_wrong_build_before_install(self):
        t, *_ = self.fixture()
        s.check_game(Path(t.game))
        with Path(t.game).open("r+b") as f:
            f.seek(0x448910); f.write(b"\0")
        with self.assertRaisesRegex(s.SetupError, "unsupported"):
            s.check_game(Path(t.game))

    def test_game_path_derives_correct_library_and_steam_root(self):
        t, *_ = self.fixture()
        args = argparse.Namespace(steam=t.steam, prefix=None, bottle=None)
        found = s.target_from_path(Path(t.game), "Linux", self.root, args)
        self.assertEqual(found.library, t.library)
        self.assertEqual(found.prefix, t.prefix)

    def test_vdf_preserves_windows_paths_and_nested_config(self):
        parsed = s.vdf(r'"libraryfolders" { "1" { "path" "D:\\Steam Library" } }')
        self.assertEqual(parsed["libraryfolders"]["1"]["path"], r"D:\Steam Library")
        self.assertEqual(s.find_key(s.vdf('"InstallConfig" { "CompatToolMapping" { "1245620" { "name" "proton_experimental" } } }'), "CompatToolMapping")["1245620"]["name"], "proton_experimental")

    def test_external_steam_library_is_found_from_selected_er_path(self):
        t, *_ = self.fixture()
        steam = Path(t.steam)
        (steam / "steamapps").mkdir()
        (steam / "steamapps/libraryfolders.vdf").write_text('"libraryfolders" { "1" { "path" "' + t.library + '" } }')
        args = argparse.Namespace(steam=None, prefix=None, bottle=None)
        with patch.object(s, "steam_roots", return_value=[steam]):
            found = s.target_from_path(Path(t.game), "Linux", self.root, args)
        self.assertEqual(found.steam, t.steam)

    def test_mac_bottle_and_windows_user_derive_without_another_question(self):
        bottle = self.root / "Library/Application Support/CrossOver/Bottles/Steam"
        steam = bottle / "drive_c/Program Files (x86)/Steam"
        game = steam / "steamapps/common/ELDEN RING/Game/eldenring.exe"
        fake_pe(game, True)
        (bottle / "cxbottle.conf").touch()
        (bottle / "drive_c/users/crossover").mkdir(parents=True)
        args = argparse.Namespace(steam=None, prefix=None, bottle=None)
        with patch.object(s, "steam_roots", return_value=[steam]):
            found = s.target_from_path(game, "Darwin", self.root, args)
        self.assertEqual(found.bottle, str(bottle))
        self.assertEqual(s.local_appdata(found, "", True), bottle / "drive_c/users/crossover/AppData/Local")

    def test_linux_uses_steam_selected_proton_without_asking(self):
        t, *_ = self.fixture()
        steam = Path(t.steam)
        (steam / "config").mkdir()
        (steam / "config/config.vdf").write_text('"CompatToolMapping" { "1245620" { "name" "GE-Proton-test" } }')
        selected = steam / "compatibilitytools.d/GE-Test/proton"
        selected.parent.mkdir(parents=True); selected.touch()
        (selected.parent / "compatibilitytool.vdf").write_text('"compatibilitytools" { "GE-Proton-test" { "install_path" "." } }')
        self.assertEqual(s.find_proton(t, "", True), selected)

    def test_zip_traversal_and_symlinks_are_rejected(self):
        archive = self.root / "bad.zip"
        zip_at(archive, {"../escaped": "bad"})
        with self.assertRaises(s.SetupError):
            s.extract(archive, self.root / "extract")
        self.assertFalse((self.root / "escaped").exists())
        for name in ("/absolute", "C:/absolute", r"..\bad"):
            with self.assertRaises(s.SetupError): s.safe_member(name)
        with zipfile.ZipFile(archive, "w") as z:
            entry = zipfile.ZipInfo("link"); entry.external_attr = 0o120777 << 16
            z.writestr(entry, "elsewhere")
        with self.assertRaises(s.SetupError): s.extract(archive, self.root / "extract")

    def test_tar_links_are_rejected(self):
        archive = self.root / "bad.tar.gz"
        with tarfile.open(archive, "w:gz") as t:
            link = tarfile.TarInfo("link"); link.type = tarfile.SYMTYPE; link.linkname = "../../outside"
            t.addfile(link)
        with self.assertRaises(s.SetupError): s.extract(archive, self.root / "extract")

    def test_checksum_failure_and_offline_missing_never_install(self):
        spec = {"url": "https://example.test/a.zip", "sha256": "0" * 64}
        with self.assertRaisesRegex(s.SetupError, "Offline cache"):
            s.download(spec, self.root / "cache", True)
        cache = self.root / "cache"
        archive = cache / ("0" * 16 + "-a.zip"); archive.write_text("bad")
        with self.assertRaises(s.SetupError): s.download(spec, cache, True)

    def test_payload_mod_pair_is_verified(self):
        _, bundle, *_ = self.fixture()
        s.verify_payload(bundle)
        (bundle / "payload/eldencraft.jar").write_text("modified")
        with self.assertRaisesRegex(s.SetupError, "modified"):
            s.verify_payload(bundle)

    def test_ini_update_preserves_unrelated_settings(self):
        root = self.root / "App"
        root.mkdir()
        file = root / "instance.cfg"
        file.write_text("[General]\nname=Old\nCustomSetting=100%\n[UI]\nwidth=123\n")
        s.update_ini(file, {"name": "EldenCraft"}, s.Changes(root))
        self.assertIn("CustomSetting=100%", file.read_text())
        self.assertIn("width=123", file.read_text())

    def test_managed_replacements_rollback_without_touching_saves_or_accounts(self):
        root = self.root / "App"
        root.mkdir()
        file = root / "instance.cfg"; file.write_text("old")
        changes = s.Changes(root)
        changes.write(file, b"new"); changes.write(root / "new.dll", b"new")
        for protected in (root / "accounts.json", root / "saves/world/level.dat"):
            with self.assertRaises(s.SetupError): changes.write(protected, b"bad")
        changes.rollback()
        self.assertEqual(file.read_text(), "old")
        self.assertFalse((root / "new.dll").exists())

    def test_full_install_and_repair_preserve_worlds_and_accounts(self):
        t, bundle, manifest, archives, runtime, deps = self.fixture()
        root = Path(t.local_appdata) / "EldenCraft"
        save = root / "Prism/instances/EldenCraft/.minecraft/saves/My world/level.dat"
        save.parent.mkdir(parents=True); save.write_text("do not reset")
        account = root / "Prism/accounts.json"; account.write_text("do not read or replace")
        old = root / "Prism/instances/EldenCraft/.minecraft/mods/eldencraft-old.jar"
        zip_at(old, {"fabric.mod.json": json.dumps({"id": "eldencraft"})})
        with patch.object(s, "check_closed"), patch.object(s, "download", side_effect=lambda spec, *_: archives[spec["key"]]), \
             patch.object(s, "shortcuts"), patch.dict(os.environ, {"ELDENCRAFT_SETUP_RUNTIME": str(runtime)}):
            for _ in range(2):
                self.assertEqual(s.install(bundle, t, deps, manifest, self.root / "cache"), root)
                s.doctor(root, t, manifest)
        self.assertEqual(save.read_text(), "do not reset")
        self.assertEqual(account.read_text(), "do not read or replace")
        self.assertFalse(old.exists())
        self.assertEqual(len(list((root / "Prism/instances/EldenCraft/.minecraft/mods").glob("*.jar"))), 2)
        self.assertIn("-Deldencraft.startHidden=true", (root / "Prism/instances/EldenCraft/instance.cfg").read_text())
        self.assertIn("start_online = false", (root / "eldencraft.me3").read_text())

    def test_commands_use_same_environment_explicit_prism_root_and_offline_launch(self):
        t, *_ = self.fixture()
        root = Path(t.local_appdata) / "EldenCraft"
        args, env = s.commands(root, t, True)
        self.assertEqual(args[0], t.proton)
        self.assertIn("--dir", args)
        self.assertEqual(env["STEAM_COMPAT_DATA_PATH"], str(Path(t.prefix).parent))
        args, env = s.commands(root, t)
        self.assertEqual(args[-2:], ["--online", "false"])
        self.assertIn("--windows-binaries-dir", args)
        t.system = "Darwin"; t.bottle = "/Bottles/Steam with spaces"; t.crossover = "/Applications/CrossOver.app/wine"
        args, _ = s.commands(root, t)
        self.assertEqual(args[:3], [t.crossover, "--bottle", "Steam with spaces"])

    def test_failure_while_staging_leaves_existing_install_untouched(self):
        t, bundle, manifest, archives, runtime, deps = self.fixture()
        root = Path(t.local_appdata) / "EldenCraft"
        root.mkdir(); marker = root / "installation.json"; marker.write_text("original")
        zip_at(archives["prism"], {"../bad.exe": "bad"})
        with patch.object(s, "check_closed"), patch.object(s, "download", side_effect=lambda spec, *_: archives[spec["key"]]):
            with self.assertRaises(s.SetupError): s.install(bundle, t, deps, manifest, self.root / "cache")
        self.assertEqual(marker.read_text(), "original")

    def test_failed_prepare_does_not_mark_ready_and_restores_hidden_mode(self):
        t, bundle, manifest, archives, runtime, deps = self.fixture()
        root = Path(t.local_appdata) / "EldenCraft"; root.mkdir()
        (root / "installation.json").write_text(json.dumps({"prepared": False}))
        with patch.object(s.subprocess, "run"):
            with self.assertRaises(s.SetupError): s.prepare(root, t, manifest)
        self.assertFalse(s.load_json(root / "installation.json")["prepared"])
        self.assertIn("-Deldencraft.startHidden=true", (root / "Prism/instances/EldenCraft/instance.cfg").read_text())

    def test_normal_flow_only_asks_for_er_path(self):
        t, bundle, manifest, *_ = self.fixture()
        with patch.object(s, "verify_payload", return_value=manifest), patch.object(s, "load_json", return_value={}), \
             patch.object(s, "target_from_path", return_value=t), patch.object(s, "finish_target", side_effect=lambda t, _: t), \
             patch("builtins.input", return_value=str(Path(t.game).parent.parent)) as question, patch("builtins.print"):
            self.assertEqual(s.main(["plan", "--bundle", str(bundle)]), 0)
        question.assert_called_once()


if __name__ == "__main__":
    unittest.main()
