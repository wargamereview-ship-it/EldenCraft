# Download sources

The small installer packages contain EldenCraft and the installer source. Third-party programs are
downloaded from their publishers and verified against pinned SHA-256 checksums in dependencies.json.
Their notices and licenses are retained in the installed program folders.

- me3 0.13.0: https://github.com/garyttierney/me3 — MIT / Apache-2.0.
- Prism Launcher 11.1.1 (Windows MinGW portable build, used on every platform inside the game environment):
  https://github.com/PrismLauncher/PrismLauncher — GPL-3.0-only; corresponding source is available at tag 11.1.1.
- Eclipse Temurin Windows x64 JRE 25.0.4.1+1: https://github.com/adoptium/temurin25-binaries —
  GPL-2.0 with the Classpath Exception; source and notices are available in that release.
- Fabric API 0.161.0+26.3: https://github.com/FabricMC/fabric — Apache-2.0.
- CPython 3.13.16 standalone installer runtime, build 20261003:
  https://github.com/astral-sh/python-build-standalone — Python and bundled dependency licenses
  are included in the runtime archive; see that release's source and license information.

Prism downloads Minecraft, Fabric Loader and their libraries after the user signs in. Minecraft is
not bundled. Neither Steam, Elden Ring, CrossOver, saves nor Microsoft/Steam account data is included.
