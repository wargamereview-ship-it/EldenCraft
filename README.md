# EldenCraft

**Minecraft survival, combat and building inside Elden Ring’s actual world.**

Explore the Lands Between with Minecraft movement, hearts, armour, tools and inventory. Gather wood and ore, craft equipment, place blocks among Elden Ring’s ruins, and fight its enemies with Minecraft weapons. Elden Ring continues to run the world, bosses, quests, interactions and grace respawns.

EldenCraft runs both games together. A native mod inside Elden Ring connects to a hidden Minecraft instance, then draws Minecraft’s blocks, equipment and HUD into the game you are playing.

[Download release 0.1](https://github.com/wargamereview-ship-it/EldenCraft/releases/tag/0.1) · [Boss reward list](rewards.md) · [Gameplay roadmap](roadmap.MD)

> **Offline only.** Launch through EldenCraft’s shortcut or me3 so Easy Anti-Cheat is bypassed and Elden Ring stays offline. Never take the modded game online.

## What makes EldenCraft different

### Build in the Lands Between

Minecraft blocks occupy real positions in Elden Ring’s world. Place structures around the terrain, use crafting tables, furnaces and chests, and bring Minecraft’s building tools into Limgrave, underground caves and the DLC’s regions.

The bridge reads Elden Ring’s collision to give Minecraft a surface to walk and build on. Connected caves and moving lifts work with the shared player position. Blocks are hidden behind Elden Ring’s own scenery using its depth buffer, so pillars, ruins and grass can cover your creations correctly. Block lighting can sample the game’s rendered picture, alongside Minecraft sky and torch light.

Elden Ring’s native terrain and structures remain intact. Gathering uses additional Minecraft resource deposits rather than destroying the game’s scenery.

### Start empty and gather your equipment

Fresh Minecraft characters start with an empty inventory. Find outdoor log piles, loose stone and rocks, then work toward tools, smelting and better equipment through Minecraft’s crafting system.

- Logs and loose stone can be gathered bare-handed; loose stone supplies cobblestone.
- Larger rocks need a pickaxe and can yield copper in early regions or iron in later ones.
- Eight mapped mine interiors supply regional ore, progressing through coal, copper, iron, gold, diamonds and ancient debris in higher tiers.
- Depleted deposits replenish after an Elden Ring grace rest. Replenishment leaves player-built blocks in place, and deposits stay clear of graces you have rested at.

Enemy drops and gathering share the same regional progression: five tiers in the base game and two more in Shadow of the Erdtree. The complete crafting route and resource balance are still being tested.

### Fight Elden Ring enemies with Minecraft weapons

Minecraft calculates your weapon damage, attack cooldowns, critical hits and enchantments. The native bridge delivers those hits through Elden Ring’s combat system, connecting them to guarding, poise and hit reactions. Enemies also hear Minecraft footsteps through native Elden Ring sounds.

Minecraft hearts own your health. Incoming Elden Ring hits pass through Minecraft armour, absorption and directional shield blocking. At zero hearts, the bridge triggers Elden Ring’s normal death and grace respawn. The current rules keep your Minecraft inventory; Elden Ring’s rune-loss behaviour remains native.

The combat HUD shows the targeted enemy’s health, accepted hits, criticals and confirmed shield blocks. Arrows can remain visibly attached to enemies after impact.

Several familiar enchantments connect the two combat systems: Projectile Protection covers native arrows, spells and thrown attacks; Smite targets Elden Ring’s undead; Knockback adds stagger; and kill XP orbs can repair Mending equipment. These newer integrations still need play confirmation.

### Collect boss rewards with a reason to explore

Ordinary enemies drop materials according to their family and region. Equipment-bearing enemies also have a chance to drop worn gear, giving useful upgrades without making every kill identical.

The reward catalogue maps **208 boss encounters**. Each gives a named item at full durability, an enchanted book and regional materials, once per encounter per Minecraft world. Original Elden Ring drops and runes remain available.

- **17 four-piece armour collections**, with 68 pieces distributed across different fights and a distinct armour trim for each set.
- **Boss-themed weapons**: swords, axes, spears, maces, tridents, bows and crossbows reflect the boss’s weapon style, element or status, and signature enchantments.
- **Distinct weapon rewards** for different bosses; repeat versions of an encounter may share one.
- **Persistent reward parcels** designed to wait through death or a full inventory until delivery is possible.

See [every boss’s item, enchantments and book](rewards.md). The latest reward redesign, XP orbs and delivery edge cases are built but not yet fully confirmed in play. Rewards are tracked per world, not reset for each NG+ cycle.

### Equip Elden Ring-themed enchantments

Weapon enchantments add **Glintstone, Flame, Lightning or Sacred** damage, scaled by enemy family. **Bloodletting, Frostbite, Venom and Scarlet Rot** build status meters on enemies: bleed and frostbite cause bursts, poison and rot deal damage over time, and frostbite leaves the target more vulnerable. These weapon statuses are calculated by the Minecraft bridge; native Elden Ring status visuals are not implemented for them.

Eight armour wards offer specialised protection:

| Ward | Protection |
| --- | --- |
| Glintstone, Flame, Storm, Sacred | Reduce the matching share of an incoming elemental hit |
| Rot, Bleed, Frost, Venom | Reduce the matching status buildup from attacks |

Each ward level provides 10% protection, up to 70% at VII. Only your strongest piece for a particular ward counts, and each armour piece can carry one ward. Themed bosses supply warded armour and books; selected DLC bosses hold the rare VII books. Combining two VI books cannot make a VII. Venom Ward currently stops at IV. Status wards do not reduce environmental buildup from poison swamps or rot lakes.

### See native statuses in Minecraft

Poison, scarlet rot, blood loss, deathblight, frostbite, sleep and madness on your Elden Ring character are mirrored as Minecraft effects with their own icons. A status HUD shows native buildup percentages and active timers, making it possible to compare ward protection before a status triggers.

The mirror follows native cures and clears across loading, death and disconnects. Elden Ring already supplies the status damage; the displayed Minecraft effects add no second damage tick or extra attribute penalties. This feature is built, not yet seen in play.

### Keep Elden Ring’s interactions

Use **R** under Minecraft control to open doors, pull levers, pick up native items and rest at graces. Interaction animations temporarily take control of the native body while Minecraft follows it. You can also hand movement and camera control back to Elden Ring without leaving the shared world.

## Install with the installer

### Requirements

- The **Steam edition of Elden Ring**, installed and launched once normally.
- A Microsoft account that owns **Minecraft: Java Edition**.
- Internet access for dependency downloads and Minecraft’s first preparation.
- The game build supported by this release: **Elden Ring executable 2.7.1**.

| Platform | Setup route |
| --- | --- |
| Windows 10/11 x64 | Windows installer |
| Linux x86-64 / Steam Deck desktop mode | Linux installer, with Steam’s Proton selected for Elden Ring |
| macOS Intel / Apple Silicon | macOS installer, with Steam and Elden Ring already installed in a CrossOver bottle; experimental |

CrossOver is a separate paid compatibility product. The macOS installer configures the mod inside an existing bottle; it does not install or license CrossOver. Windows/macOS end-to-end installation and gameplay have not yet been confirmed.

### Setup steps

1. Download your platform’s ZIP from [release 0.1](https://github.com/wargamereview-ship-it/EldenCraft/releases/tag/0.1).
2. Extract the **entire ZIP** into a writable folder. Close Elden Ring, Prism and Minecraft.
3. Run **Install.cmd** on Windows, **Install.sh** on Linux, or **Install.command** on macOS. On Linux, you can open a terminal in the extracted folder and run `sh Install.sh`.
4. Enter your **Elden Ring folder**, its **Game folder**, or the full **eldenring.exe path** when asked. For example: `D:\SteamLibrary\steamapps\common\ELDEN RING`, or `/games/steamapps/common/ELDEN RING/Game/eldenring.exe`.
5. Setup downloads verified dependencies, installs the matching DLL and JAR, and creates shortcuts. Prism opens visibly: sign in with your Minecraft account and let the instance reach Minecraft’s title screen, then close Minecraft and Prism.
6. With Steam running, use **Play EldenCraft**. Minecraft starts automatically alongside Elden Ring and normally remains hidden. Load your Elden Ring save and press **F8** to use Minecraft movement.

The installer includes the mod binaries and supplies private runtimes, so no system Python, Java, Rust or Gradle installation is needed. It derives the other paths from your ER path and preserves existing saves, accounts and unrelated Minecraft mods.

Use **Finish Minecraft setup** if sign-in or the first download was interrupted. Use **Check EldenCraft** to check the game build and installed files. Running the installer again repairs managed files, with backups under `EldenCraft/backups`. Advanced path overrides, offline caching and other options are covered in the [installer guide](installer/README.md). Release checksums are supplied as `SHA256SUMS.txt`.

Release **0.1** is the installer release label. Its bundled mod remains version **0.0.1**, using Minecraft **26.3**, Fabric Loader **0.19.5**, Fabric API **0.161.0+26.3**, Java **25** and bridge protocol **3**.

## Build and install from source

Build the Windows DLL and Minecraft JAR from the same checkout, then package them with the installer. This gives a source-built installation the same dependency setup, sign-in flow and shortcuts as the release packages. Keep both games closed while building or replacing their mod binaries.

### Build prerequisites

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

To reproduce release 0.1 specifically, check out tag `0.1` before building. Otherwise the commands build the current source, whose behaviour may differ from that release.

### Windows build

From the repository root in x64 Developer PowerShell:

```powershell
cargo build --manifest-path game/Cargo.toml --release --target x86_64-pc-windows-msvc
.\fabric\gradlew.bat -p fabric build
py -3 tools/package_installer.py --release-version 0.1
```

### Linux build

For a fresh checkout, create an SDK in this repository instead of relying on the developer’s sibling Skycraft checkout:

```bash
cargo install xwin
xwin --accept-license splat --use-winsysroot-style --output .install/xwin
export XWIN_SYSROOT="$PWD/.install/xwin"
tools/build_dll.sh
./fabric/gradlew -p fabric build
python3 tools/package_installer.py --release-version 0.1
```

The first build downloads dependencies. Add `--offline` to the DLL or Gradle build only after their caches have been populated. Keep the SDK outside version control if you choose the `.install/xwin` location above.

### Install your build

The build outputs are:

- `game/target/x86_64-pc-windows-msvc/release/eldencraft.dll`
- `fabric/build/libs/eldencraft-0.0.1.jar`
- Three platform setup ZIPs and checksums under `dist/installers/`

Extract the ZIP for your play platform and follow the installer steps above. Packaging reads the compiled DLL/JAR, checks their identity and source freshness, and includes their hashes in the package. No game installation, account files or saves from the build machine are included.

For manual deployment, configure the Windows portable Prism launcher at `%LOCALAPPDATA%\EldenCraft\Prism` **inside the game’s Windows/Proton/CrossOver environment**. Create an `EldenCraft` instance using the versions listed above, add Fabric API and your built JAR to its `.minecraft/mods` folder, and sign in. Install [me3](https://github.com/garyttierney/me3), then launch the repository profile:

```sh
me3 launch -p me3/eldencraft.me3
```

That profile loads the DLL directly from the build output. Minecraft must run in the same Windows or Wine environment as Elden Ring; a separate native Linux/macOS Minecraft process cannot use this bridge. The installer is the simpler way to configure that environment correctly.

## Controls

| Key | Action |
| --- | --- |
| F8 | Open Minecraft's pause menu (options, video settings, GUI scale) |
| F9 | Toggle the Minecraft first-person camera |
| F10 | Switch input ownership |
| F5 | Cycle Minecraft camera modes |
| R | Native Elden Ring interaction under Minecraft control |
| F3 | Switch block occlusion between game depth and scanned collision |
| F4 | Switch block lighting between the game picture and clock model |
| F7 | Show nearby collision edges for debugging |

Minecraft owns movement by default; Escape still opens Elden Ring's own menu. The R bridge assumes Elden Ring’s Event Action is bound to **E**. Minecraft’s usual inventory, crafting, mining, placement and weapon controls apply when it owns input.

## Current state and limitations

This is an early playable project. Exploration, connected caves, lifts, native interactions, ordinary combat, gathering and block occlusion have been seen in play on Linux/Proton. Earlier loot and boss payouts also work in tested encounters.

The latest boss rewards, wards, weapon elements/statuses, player status mirror, XP/Mending and knockback improvements are **built, not yet fully seen in play**. Full-inventory boss rewards, simultaneous deaths, multi-phase encounters, distant regions, NG+ and base persistence during travel still need broader testing. Installer tests and a successful build do not establish gameplay support on every platform.

- Gameplay rewards and gathering currently belong to the local host; seamless co-op is not implemented.
- Native terrain digging is disabled. Some gathering deposits sit above the visible ground because placement follows Elden Ring’s collision floor.
- Translucent native effects such as grace glows and particles can appear underneath Minecraft blocks.
- Enemy labels can still show model IDs, and combat, resource density and survival balance need tuning.
- Armour set bonuses, villager traders, Nether and End integration remain future work.

See the [roadmap](roadmap.MD) for gameplay priorities and [handoff](Handoff.MD) for technical details.

## Troubleshooting and development

Start with **Check EldenCraft**, then confirm Steam is running, the supported game build is installed, and Minecraft completed its first preparation. Capture logs before relaunching:

- Native bridge: `eldencraft.log` beside the loaded DLL; installer deployments use `%LOCALAPPDATA%\EldenCraft\native\eldencraft.log`.
- Minecraft: `Prism/instances/EldenCraft/.minecraft/logs/latest.log` inside the installation folder.
- Source builds: the native log is beside `game/target/x86_64-pc-windows-msvc/release/eldencraft.dll`.

`game/` contains the Rust native bridge, using pinned [fromsoftware-rs](https://github.com/vswarte/fromsoftware-rs) bindings. `fabric/` contains the Minecraft mod. `protocol/eldencraft_protocol.h` defines their shared-memory layout, mirrored in Rust and Java. `installer/` contains setup and `tools/` contains packaging, generators and validation helpers.

Useful checks from the repository root:

```bash
python3 -m unittest discover -s installer/tests -v
./fabric/gradlew -p fabric test
python3 tools/test_loot.py
```

On Windows, use `py -3` and `fabric\gradlew.bat` for those checks. After editing HLSL, also run `tools/shader_check/check_shaders.sh` in the Linux/Wine build environment; shader compilation errors can disable block rendering at game startup.

EldenCraft is licensed under [MIT](LICENSE). Dependency sources and licences are listed in [the installer’s third-party notices](installer/THIRD_PARTY.md). Elden Ring and Minecraft remain separate games owned by their respective rights holders.
