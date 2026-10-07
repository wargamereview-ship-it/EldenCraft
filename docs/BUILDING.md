# Building and installing from source

Build the Windows DLL and Minecraft JAR from the same checkout, then package them with the installer. This gives a source-built installation the same dependency setup, sign-in flow and shortcuts as the release packages. Keep both games closed while building or replacing their mod binaries.

## Build prerequisites

All build hosts need Git, a current Rust toolchain with the `x86_64-pc-windows-msvc` target, JDK **25**, and Python **3.11 or newer** for packaging. Set `JAVA_HOME` to your JDK 25 installation. Gradle is provided by the repository’s wrapper.

- **Windows:** Visual Studio Build Tools with the C++ workload and Windows SDK. Run the commands in an x64 Developer PowerShell so the native compiler and linker are available.
- **Linux:** LLVM tools including `clang-cl`, `lld-link` and `llvm-lib`, plus an xwin Windows SDK/CRT. The build script uses that SDK through `XWIN_SYSROOT`.
- **macOS:** the native component is a Windows DLL. The documented native build paths are Windows and Linux; build there and use the generated macOS installer in your CrossOver bottle.

Clone the source:

```sh
git clone https://github.com/wargamereview-ship-it/EldenCraft.git
cd EldenCraft
rustup target add x86_64-pc-windows-msvc
```

To reproduce a release, check out its tag (for example `0.3`) before building. Otherwise the commands build the current source, whose behaviour may differ from that release.

## Windows build

From the repository root in x64 Developer PowerShell:

```powershell
cargo build --manifest-path game/Cargo.toml --release --target x86_64-pc-windows-msvc
.\fabric\gradlew.bat -p fabric build
py -3 tools/package_installer.py --release-version 0.3
```

## Linux build

For a fresh checkout, create an SDK in this repository instead of relying on the developer’s sibling Skycraft checkout:

```bash
cargo install xwin
xwin --accept-license splat --use-winsysroot-style --output .install/xwin
export XWIN_SYSROOT="$PWD/.install/xwin"
tools/build_dll.sh
./fabric/gradlew -p fabric build
python3 tools/package_installer.py --release-version 0.3
```

The first build downloads dependencies. Add `--offline` to the DLL or Gradle build only after their caches have been populated. Keep the SDK outside version control if you choose the `.install/xwin` location above.

## Install your build

The build outputs are:

- `game/target/x86_64-pc-windows-msvc/release/eldencraft.dll`
- `fabric/build/libs/eldencraft-0.0.1.jar`
- Three platform setup ZIPs and checksums under `dist/installers/` (or the folder given to `--output`)

Extract the ZIP for your play platform and follow the installer steps above. Packaging reads the compiled DLL/JAR, checks their identity and source freshness, and includes their hashes in the package. No game installation, account files or saves from the build machine are included.

For manual deployment, configure the Windows portable Prism launcher at `%LOCALAPPDATA%\EldenCraft\Prism` **inside the game’s Windows/Proton/CrossOver environment**. Create an `EldenCraft` instance using the versions listed above, add Fabric API and your built JAR to its `.minecraft/mods` folder, and sign in. Install [me3](https://github.com/garyttierney/me3), then launch the repository profile:

```sh
me3 launch -p me3/eldencraft.me3
```

That profile loads the DLL directly from the build output. Minecraft must run in the same Windows or Wine environment as Elden Ring; a separate native Linux/macOS Minecraft process cannot use this bridge. The installer is the simpler way to configure that environment correctly.

## Source layout and checks

`game/` contains the Rust native bridge, using pinned [fromsoftware-rs](https://github.com/vswarte/fromsoftware-rs) bindings. `fabric/` contains the Minecraft mod. `protocol/eldencraft_protocol.h` defines their shared-memory layout, mirrored in Rust and Java. `installer/` contains setup and `tools/` contains packaging, generators and validation helpers.

Useful checks from the repository root:

```bash
python3 -m unittest discover -s installer/tests -v
./fabric/gradlew -p fabric test
python3 tools/test_loot.py
```

On Windows, use `py -3` and `fabric\gradlew.bat` for those checks. After editing HLSL, also run `tools/shader_check/check_shaders.sh` in the Linux/Wine build environment; shader compilation errors can disable block rendering at game startup.
