# EldenCraft setup

Extract the entire package into a writable folder, then open the installer for your operating system:

- Windows 10/11 x64: double-click **Install.cmd**.
- Linux x86-64, including Steam Deck desktop mode: run **Install.sh** (or `sh Install.sh`).
- macOS Intel or Apple Silicon: double-click **Install.command**.

No developer tools or system Python are needed. Setup downloads its own private Python runtime and
verified versions of me3, Prism Launcher, Windows Java 25 and Fabric API. The DLL and Minecraft mod
are included together. Internet is needed for dependencies and Minecraft's first start.

## Before setup

Install and launch Elden Ring through Steam once, then close it. Keep Steam available for launching
the mod. A Microsoft account owning Minecraft Java Edition is required. Windows and Linux use the
Windows game; Linux also needs Steam's Proton installed and selected for Elden Ring.

On macOS, install CrossOver and install Steam/Elden Ring in a CrossOver bottle first. That is a paid
compatibility product with its own setup/sign-in; EldenCraft does not install or license it. The
installer detects ordinary CrossOver bottles and creates a launch command inside the selected bottle.
**macOS compatibility is experimental:** installation logic is covered by tests, but EldenCraft's
graphics hooks and Windows Minecraft renderer have not yet been tested on a Mac. A native macOS
Minecraft launcher cannot share the bridge with the Windows game.

The current package targets **Elden Ring executable 2.7.1**, Minecraft **26.3**, Fabric Loader
**0.19.5** and protocol **3**. Setup stops before replacing files if the native combat signatures
do not match. Use a release matching a future game patch.

## First start

Setup asks for the Elden Ring folder or eldenring.exe path, then installs into
the game's Windows Local AppData/EldenCraft folder. It opens Prism visibly. Sign in through Prism's
Microsoft login; let EldenCraft download and reach Minecraft's title screen, then close Minecraft
and Prism. This first preparation downloads the licensed game through its normal launcher.

Use **Play EldenCraft** on your desktop or in the installation folder afterward. Minecraft starts
automatically with Elden Ring and normally remains hidden. The launch profile keeps Elden Ring
offline with Easy Anti-Cheat bypassed by me3. Never launch this mod through the online game shortcut.

If you close sign-in early, use **Finish Minecraft setup**. **Check EldenCraft** verifies the game
build, native DLL/JAR pair and required files. Setup never supplies/reset saves or reads/copies your
account tokens. Repair preserves worlds, inventory, accounts and unrelated mods; changed managed files
are backed up under EldenCraft/backups. A failed file installation restores those managed files.

## Custom locations and offline preparation

Each Install launcher accepts the options shown by `--help`. `plan` detects and prints the selected
target without installing anything. Examples:

```text
Install.cmd --game "D:\SteamLibrary\steamapps\common\ELDEN RING\Game\eldenring.exe" --steam "C:\Program Files (x86)\Steam"
sh Install.sh --game "/games/steamapps/common/ELDEN RING/Game/eldenring.exe" --steam "$HOME/.local/share/Steam" --proton "/games/steamapps/common/Proton - Experimental/proton"
./Install.command --bottle "$HOME/Library/Application Support/CrossOver/Bottles/Steam" --game "/path/to/Game/eldenring.exe"
```

On Linux `--prefix` means the `pfx` folder, not its compatdata parent. `--local-appdata` can select a
nonstandard Windows user folder; the DLL's LOCALAPPDATA must resolve to that same location. An
explicit --proton is used for first-run Minecraft preparation; me3 follows Steam's selected tool
for actual game launch. Keep them the same. `--crossover` can override CrossOver's wine executable.

`--no-prepare` installs files without opening Prism; sign-in/downloads still need the finish shortcut.
`--download-only` fills the downloads cache, and `--offline` uses only verified cached dependencies.
The private installer runtime must also be present or cached for an offline start. Minecraft and
account login still need an initial online preparation. The normal installer has only one path question; the other paths are derived.

No installer executable is code-signed/notarized yet. OS prompts may require explicitly opening an
extracted script. Don't install packages from an untrusted source. The installers are built and tested
as packages; Windows, Linux and macOS end-to-end installation/gameplay still need platform acceptance.
