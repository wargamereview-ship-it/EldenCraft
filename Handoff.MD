# EldenCraft — current-state handoff

Updated 6 October 2026 (America/Toronto). This replaces the previous handoff. Read it with
`roadmap.MD` (gameplay priorities) and `README.md` before changing code. A successful build is not
evidence of successful gameplay: say "built, not yet seen in play" until the user has seen it.

## Project and working rules

EldenCraft runs a native Rust DLL inside Elden Ring and a Fabric mod in a hidden Minecraft instance.
The bridge exports ER collision and actors, renders MC content over ER, and lets the player move,
fight, gather, craft and build as a Minecraft player inside ER's world.

- Workspace: `/run/media/alex/a0da83cf-b021-4cfe-9104-edb6dd726a80/EldenCraft`. The separate
  `../Skycraft` checkout only supplies the JDK, Gradle cache and Windows SDK; inherited Sky/Skyrim
  class names do not mean it should be edited.
- Repository: `https://github.com/wargamereview-ship-it/EldenCraft`. Development is offline through
  me3 without Easy Anti-Cheat; never take the modded game online.
- The user plays on Linux (Proton, vkd3d-proton). They prefer one decision at a time, asked in plain
  chat; the question widget has not worked reliably for them. Continue authorized work without
  re-asking. They usually test in game and send screenshots and logs.
- Commit only when asked. Git has no identity configured; earlier commits use
  `git -c user.name=alex -c user.email=wargamereview@gmail.com commit ...`. Never reset or rewrite history.
- Never replace the DLL or JAR while either game runs. Check with `ps -eo comm,args | grep -i -E
  "eldenring.exe|prismlauncher|net.minecraft"` (excluding your own shell): `pgrep -f eldenring.exe`
  matches the shell running it and gives a false "running".
- Do not reset either save unless the user asks.

### Git state

The player-status mirror and documentation updates are committed as `5e87e7d` on `main` after
`7cef9fc`. On 6 October the duplicate GitHub "Looting V1" commit (`15739af`) was reconciled with the
local copy (`728d002`) through a merge retaining the current local files and both histories. Their
original trees were verified identical (`712992dd62eb157511be38a65235671b667836dc`). `main` can now
be pushed normally without rewriting history.

## Builds in use

- DLL: me3 loads `game/target/x86_64-pc-windows-msvc/release/eldencraft.dll` directly (built 6 Oct 2026).
- JAR: the instance's `mods/eldencraft-0.0.1.jar` matches `fabric/build/libs/eldencraft-0.0.1.jar`,
  SHA-256 `baa6d4bb28ed2d428cbb28dbdc7f48f448825b71302e7d392e6ed5e4c004e35d`.
- Protocol version 3 (`protocol/eldencraft_protocol.h` is the source of truth; `game/src/proto.rs` and
  `Proto.java` mirror it; change all three together). The shared-memory name `Local\EldenCraft_v1` is
  not the version. Additions that kept version 3 (old binaries ignore them): input `IN_HURT_ELEMENTS`
  (17), `IN_HURT` codes `HURT_PROJECTILE`, and `PlayerVitals.statusWards` (was padding). The optional 136-byte `PlayerStatuses` snapshot at 0x980
  is also a compatible version-3 extension; old DLLs leave its validity flag zero.

## What works, and how sure we are

| Area | State | Evidence |
| --- | --- | --- |
| Launch, compositing, HUD, cameras | DLL starts Prism/MC in the same Wine session; MC is drawn over ER | Used in play |
| Blocks hidden by ER scenery | Occlusion by the game's own depth buffer | Confirmed 6 Oct (pillars, grass, dithered fades) |
| Block lighting from the game picture | Blocks sample ER's finished frame for light | Built; copy path not separately confirmed |
| Movement, caves, lifts | MC feet drive the native body; lifts carried | Confirmed (caves, lifts 5 Oct) |
| ER interactions (R) | Event Action taps; doors, levers, pickups, graces | Confirmed |
| Enemy targeting and hearing | Hostiles attackable; MC footsteps replay ER sounds | Confirmed |
| Outgoing combat | Native carrier bullet gives ER guard, poise, reactions and AI response with MC damage | Confirmed; tuning remains |
| Knockback feeding stagger | MC push adds poise damage and reaction size | Built 6 Oct, not seen |
| Incoming damage and death | MC hearts, armour and shields; MC death drives ER death and respawn | Played; edge cases open |
| Element of each incoming hit | Read from the attack's AtkParam row | Confirmed 6 Oct (physical and fire) |
| Wards, Projectile Protection, Smite | See Combat | Built 6 Oct, not seen |
| Player statuses in Minecraft | Seven custom effects, native timers and buildup HUD | Built 6 Oct, not seen |
| Weapon elements and statuses | MC-side damage and status meters on enemies | Built, not seen |
| Loot (regular and boss) | Regional tiers, durable boss parcels | Confirmed before the 6 Oct reward redesign |
| Boss reward redesign, XP orbs | Wards, trims, weapon types, signatures, orbs | Built 6 Oct, not seen |
| Gathering and empty start | Nodes, mine ores, grace replenishment | Confirmed 5 Oct |
| Native digging | Disabled at the user's request | — |

## Rendering

**Occlusion by ER's depth** (`depth.rs`, `depth_math.rs`, shader `HiddenByGame`; on by default, F3
falls back to the scanned-collision occluders). A MinHook on `ID3D12GraphicsCommandList::ResourceBarrier`
(installed on the first frame) takes the first full-size single-sample depth the game moves into
DEPTH_WRITE as the scene depth (`D32_FLOAT_S8X24_UINT` at back-buffer size) and records a
`CopyResource` into our texture (SRV slot 65) inside the game's own command list: when the game makes
the whole depth readable (state 0xc0), and at the end of its frame (a back buffer moved to PRESENT,
from plane states tracked through its barriers). The encoding is calibrated at run time against a native
ray (first person, 3-100 m): reversed-Z, near 0.05, far 10000. Blocks, the target outline and contact
shadows discard fragments more than 0.1 m (+2%) behind ER's depth; nothing nearer than 1.5 m is tested
(the hidden player model). In a D3D pixel shader `SV_Position.w` is clip w (the forward distance), not
OpenGL's 1/w: taking 1/w was the bug that made occlusion look broken. Failures log `depth: ...` and
keep the collision occluders; `depth: blocks are now / are not hidden by the game's depth (...)` logs
each change. Investigation history: `docs/depth-occlusion-handoff.md`.

**Accepted limit:** ER's translucent effects (grace glow, particles) write no depth, so blocks are drawn
over them. Layering ER's VFX over blocks was considered and declined by the user (6 Oct).

**Block lighting** (`gpu.rs` `FrameCopy`, SRV slot 64; `GameLight` in the shader): ER's finished frame
is copied before our pass and each vertex averages 32 pixels around it (scaled by 0.42, half-strength
tint), shaded per face by the clock's sun, times MC sky light, plus torch light. F4 switches to the
clock-only model in `lighting.rs`, also the fallback if the copy fails (`gpu: cannot copy ...`).
Blocks over dark soil read a little dark; tune `look` in `draw_world`.

Every HLSL entry point must compile under Wine's d3dcompiler (the game compiles them at start-up; one
error disables all block rendering): run `tools/shader_check/check_shaders.sh` after any shader edit and
add new entry points to `compile_shaders.c`.

## Collision and movement

`scan.rs` has full columns (half-block grid, wall fitting) within 3 regions and a floor-only ring of
1 m samples out to 10 regions (~80 m), nearest first; far columns are rescanned in full on approach.
F7 draws collision edges near the player. Lifts are not static collision: `risen_floor` finds a floor
0.4-1.0 m above the feet, `SkyCollider` drops scanned floors a lift left and carries the player onto a
risen patch, and `Scanner::carried_rise` keeps recovery from fighting a rising lift. A stall watchdog
logs a thread dump after 10 s without a server tick; recovery without a restart is not guaranteed.

## Combat

### Outgoing (MC hits ER enemies)

Each enemy has an invisible MC stand-in (`SkyrimActorEntity`, family from its model) that collects every
hit per tick (melee, sweeps, arrows, fire ticks) and forwards it. The DLL spawns ER's Ruin Fragment
bullet (BulletParam / AtkParam_Pc 10176000) into the enemy so ER runs guard, poise, reaction and AI; the
HP hook substitutes MC damage (x25 ER HP), keeping smaller guarded damage. Melee reach is 4.25 m; a bullet
not delivered within 600 ms falls back to direct HP. `native_damage::reaction(weapon, critical, knockback)`
sets reaction size and poise per MC weapon class; MC knockback (`McEvent.d`: 0.4 plain, 0.5 per Knockback
level or sprint) adds half the poise per 0.5 step and one reaction size at two steps.

**Weapon elements and statuses** (`ElementalRules`, `StatusMeters`, `ElementalHit`): Glintstone, Flame,
Lightning and Sacred add 12% of the hit per level, scaled by enemy family; Bloodletting, Frostbite, Venom
and Scarlet Rot fill an ER-style meter (100, bosses 250, harder after each proc, draining when idle).
Hemorrhage and frostbite proc a burst of max HP, poison and rot tick for 18 s, frostbite also leaves the
enemy taking 20% more. These are MC-side damage (`HIT_STATUS` events go straight to HP), not ER's own
status effects or visuals.

### Incoming (ER hits the player)

ER's HP becomes a sensor: the HP-stage hook (`native_hits.rs`) records each hit on the local player and
`combat.rs` sends it to MC (`IN_HURT_ORIGIN`, `IN_HURT_ELEMENTS`, `IN_HURT`), where vanilla armour,
shields and death apply; ER's HP is then restored. Falls and status ticks outside the attack call arrive
as undirected damage.

The damage record names its attack (AtkParam row at +0x40, table at +0x44: 0 `AtkParam_Npc`,
1 `AtkParam_Pc`); the row's `atk_phys/mag/fire/thun/dark` give the hit's make-up (confirmed in play for
physical and fire). The record holds only the total (+0x228), not the per-type damage. A hit whose row
is used by any BulletParam row (`Attack::projectile`, built once from the params) is a projectile and is
sent as `HURT_PROJECTILE`; Java gives it the `mob_projectile` damage type. Spells, breath and thrown pots
count. Each hit logs `life: damage call lost ... attack AtkParam_Npc <id> phys .. fire .. [, projectile]`.

**Status buildup:** seven f32 at +0x158 that the HP stage passes to the attack's status effects, assumed
in `PlayerGameData::resistance_gauges` order (poison, scarlet rot, blood loss, deathblight, frostbite,
sleep, madness). Not yet confirmed: `life: status buildup on you: <status> a -> b` names them, so one hit
by a known status confirms or corrects the order.

### Ward enchantments (armour)

`WardRules`, `Wards`, data `enchantment/*_ward.json`, exclusive set `ward` (one ward per piece). Eight
wards, levels I-VII at 10% each; only the strongest piece of a ward counts.
- Glintstone, Flame, Storm and Sacred Ward cut their element's share of an ER hit, in
  `SkyCombat.hurtNativePlayer`, before vanilla armour (logged `[physical ..% ..., wards cut x of y]`).
- Rot, Bleed, Frost and Venom Ward cut that buildup: Java writes the levels into the vitals every tick
  and `native_hits` scales the record before the game copies it. Buildup from ground (rot lakes, poison
  swamps) applies no attack and is not cut.
- VII exists only as a book from one DLC boss per ward; `AnvilMenuMixin` stops two VIs making a VII. Wards
  are not in the enchanting table, trades or chests.

### Player statuses mirrored in Minecraft (6 Oct build)

`player_status.rs` reads the live local `PlayerIns.player_game_data` on PostPhysicsSafe: seven
`resistance_gauges`, `resistance_gauge_max`, `proc_status_timers` and `proc_status_timer_max`, in order
poison, scarlet rot, blood loss, deathblight, frostbite, sleep, madness. Compile-time offset guards:
0x9cc, 0x9e8, 0xa20, 0xa3c. The installed 2.7.1 function at 0x659e10 fills them from SpEffect categories
2, 5, 6, death's 116/117, 260, 436, 437. Native inactive timers are negative; deathblight's active
999/1000 sentinel becomes an untimed active effect, not a 16-minute countdown.

The DLL publishes optional `PlayerStatuses` at 0x980 (136 bytes, independent seqlock, life epoch,
world and timestamp). Loading/inactive life invalidates it; Java rejects wrong-life, wrong-world,
future, >1-second-old and invalid snapshots. `PlayerStatusBridge` syncs seven `eldencraft:<status>`
MobEffects on the integrated-server host, with durations corrected both upwards and downwards.
Native cures, death, loading and disconnect clear them. Separate custom IDs preserve vanilla potion
effects. These are display effects: ER already supplies status damage and mitigation; no extra MC
damage, slowdown or other penalties were added. Additional MC mechanics remain a user decision.

`PlayerStatusHud` shows only buildup/active statuses on the left: icon, translated name, buildup
percentage/bar or native remaining time/bar. Actual MobEffects also appear in vanilla's HUD and
inventory. Wards therefore show their reduced attack buildup through the native gauge; ground buildup
still has the existing ward limitation. Pixel icons are regenerated by `tools/generate_status_icons.py`.
Native logs say `life: player statuses: ...` (at most once per second except active transitions), Java
logs active/cleared effects. All seven proc timers, cures, ward buildup and respawn cleanup still need
play confirmation. Buildup alone never guesses a proc, and an instantaneous native status can be brief.

### Vanilla enchantments against Elden Ring

From reading the code (6 Oct), not from play:

| Works | Partly | Does nothing (never given) |
| --- | --- | --- |
| Sharpness, Sweeping Edge, Fire Aspect, Power, Flame, Infinity, Multishot, Piercing, Quick Charge, Loyalty, Lunge, Density, Wind Burst, Protection, Unbreaking, Depth Strider and Respiration (ER water is MC water), Efficiency/Fortune/Silk Touch (MC blocks), Projectile Protection, Smite (ER undead, `EnchantmentHelperMixin`), Knockback (stagger), Mending (kills drop orbs) | Feather Falling (MC's own falls; any fall damage ER deals itself arrives as plain damage) | Fire and Blast Protection (ER fire/explosions arrive as attacks), Thorns (post-attack effects only fire from an attacker's own code), Looting (ER kills roll chest-style loot), Bane of Arthropods, Impaling, Punch (arrows `push`), Breach, Soul Speed, Frost Walker, Aqua Affinity |

## Loot and boss rewards

- Fresh characters start empty. Enemy family sets the materials, region/placement the tier: base game
  T1-T5, Shadow of the Erdtree T6-T7. HP changes only bulk quantity. Equipment-capable enemies have a
  10% gear chance (9% regional tier, 1% one higher, capped T5 base / T7 DLC; 30-70% durability).
- Enemies and bosses drop **XP orbs** where they die, held in the air like loot until collision there is
  known; a boss drops them only on its first reward (`BossRewards.Enqueued`). Mending repairs from them.
- Each boss gives one named item at full durability, an enchanted book and regional materials, once per
  world per encounter (not per NG+ cycle). Original ER drops and runes are unchanged.
- **Armour:** 17 four-piece sets (68 pieces), each spread over four fights and with its own armour trim.
  Projectile sets get Projectile Protection, the rest Protection.
- **Ward themes:** 142 of 208 bosses have one (21 marked unsure in the generator). Their armour carries it
  at level = tier (max VI) and their book is that ward. VII books: Messmer (Flame), Bayle (Storm), Rellana
  (Glintstone), Promised Consort Radahn (Sacred), Romina (Rot), Divine Beast Dancing Lion of Rauh (Frost),
  Chief Bloodfiend (Bleed). Venom Ward has no DLC boss and stops at IV. Unthemed bosses give a random
  book (two in three a vanilla enchantment, otherwise a weapon element or status).
- **Weapons:** each weapon boss gives the kind it fights with in ER (sword, axe, spear, mace, trident, bow,
  crossbow; pickaxe for stone and crystal bosses), its theme's element or status (none for physical bosses)
  and a signature enchantment (Smite for undead, Fire Aspect for fire, otherwise one suited to the weapon).
  Where two different bosses would still match, the later gets one or two extra enchantments; the
  generator refuses to build if two different bosses share a weapon reward (repeat fights may).

Data flow: `tools/loot_catalog.json` (public-data snapshot: 446 maps, 372 models, 208 encounters) ->
`tools/generate_loot.py` (`SETS`, `WARDS`, `WARD_SEVEN`, `WEAPONS`, `FLAIRS`, families) -> `loot_rules.json`,
105 loot tables, `game/src/loot_catalog.rs`, `docs/loot-boss-rewards.md`. Regenerate only when changing
mappings, then run `tools/generate_resources.py` and the tests. `LootRules`, `LootEquipment`, `SkyLoot`
and `BossRewards` implement the MC side. **`rewards.md`** lists every boss's item, enchantments and book,
written from the reward code by `RewardsSheetTest` (on request only, see Verification); regenerate it after
reward changes.

Native `loot.rs` watches completion flags after seeing an encounter alive; known boss actors suppress
ordinary payouts (duo members and phases give one reward; Radagon/Elden Beast share one). `Boss::matches`
needs a nonzero actor identity or an exact map/NPC fallback: zero must never act as a wildcard
(`tools/test_loot_identity.rs`). Boss parcels persist in the world's `eldencraft_boss_rewards_v2.json`
before delivery, with per-player receipts; full inventories and death delay delivery. Those recovery
paths are built but their play tests were postponed by the user.

## Gathering

No native terrain or structure destruction: deposits are extra MC blocks in free space. Outdoors: log
piles, loose-stone slabs (bare-handed, drop cobblestone) and stone (rocks: 10% raw copper at T1, raw iron
at T2+). Eight mapped mine interiors (`tools/generate_resources.py`) show ore by tier (copper/coal, iron,
gold/diamond, ancient debris at T5+). Nodes replenish after a native grace rest (event flag 9000 ->
`grace_edges.rs` -> `IN_GRACE_REST`), never over player-built blocks, and keep 7 m from graces the player
has rested at. Placement: `ResourceNodes`, `ResourceRules.restHeight` (cubes sink up to 0.6 or rise to
the next block, slabs in half steps), 12-block outdoor / 8-block mine grids, five floor samples, known
collision and clearance. Some nodes look like they float: placement follows ER's collision floor, which
can sit above the visible ground (deferred by the user). State lives in `eldencraft_resources_v1.json`;
`ResourceRemovalMixin` depletes nodes on any block removal. Guest gathering is not implemented.

## Native references (ER app 2.7.1, offsets from the image base)

| Where | Reference | Purpose |
| --- | --- | --- |
| `movement.rs` | ChrCtrl +0x100 | Queues the MC destination |
| `native_hits.rs` | 0x448910 HP apply (hook) | Hit capture, MC damage substitution, status ward scaling |
| damage record | +0x40 atk id, +0x44 table, +0x158 7 x f32 buildup, +0x228 total, +0x258 guarded | Read/written by the hook |
| game functions | 0x447810 pre-apply, 0x685820 core damage calc, 0xd247d0 AtkParam lookup (slot = table + 7) | Research only |
| `native_damage.rs` | 0x3a2cb0 SpawnBullet | Outgoing carrier hits |
| `depth.rs` | `ID3D12GraphicsCommandList::ResourceBarrier` (vtable, MinHook) | Scene depth copy |
| `aggro.rs` | 0x2c50d0; ComManipulator 0x3ce9a0 | Direct-hit fallback AI notice |
| `ai_sound.rs` | hook 0x37a6c0; call 0x5f2c50; global 0x3d66548 | Learned native footsteps |
| `dinput.rs` | DirectInput vtable patches | Input ownership, Event Action injection |

fromsoftware-rs (pinned in `game/Cargo.toml`) also maps `PlayerGameData::resistance_gauges`,
`resistance_gauge_max` and `proc_status_timers`: the player's status bars, used by the MC status mirror.
Preserve startup fingerprints and guards when changing native bridges.

## Saves

- **Elden Ring:** `.../compatdata/1245620/pfx/drive_c/users/steamuser/AppData/Roaming/EldenRing/76561198056309953/ER0000.sl2`,
  the same file the online game and Steam Cloud use. On 6 Oct the user asked for one weapon of every ER
  weapon type; 42 were added to the held inventory of the only character ('sdasd', level 9). Backup beside
  it: `ER0000.sl2.before-weapons-20261006-092126`. Layout notes from that edit: ClayAmore's ER-Save-Editor
  layout matches this game version; empty GaItem entries are `(0, 0xFFFFFFFF)`; the acquired-items list is
  8-byte `(item id, 1)` pairs (the editor's 16-byte write would land in the wrong place); new weapon handles
  follow the highest existing one; recompute the slot MD5.
- **Minecraft:** the world was reset once at the user's request; gathering and later changes were installed
  without another reset.

## Installer (6 October build; not play-confirmed)

`installer/setup.py` and the platform launchers install a pinned portable me3, Windows Prism,
Windows Java 25, Fabric API, and the paired DLL/JAR from a package. The normal installer asks only
for the ER folder/executable path; Steam, Proton/bottle and Local AppData are derived. It opens
Prism visibly for Minecraft sign-in and first download, then uses hidden MC for normal offline play.
`game/src/launcher.rs` now passes Prism's root explicitly with `--dir`. Saves/accounts are preserved;
managed files are backed up and rolled back on file-install errors. macOS requires CrossOver and is
experimental until tested on a Mac. Windows/macOS installation and all platform gameplay remain
unverified; 18 installer tests, real dependency extraction, an isolated full Linux file install,
Linux preflight and DLL build pass. Windows bootstrap syntax is checked with PowerShell.

Run `python3 tools/package_installer.py` after DLL/JAR builds; output is `dist/installers/` (ignored).
Installer source is included with the release. Use `--release-version 0.1` for the first installer
release label; the bundled Minecraft mod remains version 0.0.1. See `installer/README.md`.

## Verification, build and deployment

Latest checks (6 Oct 2026): DLL and JAR build; 71 Java tests pass (`RewardsSheetTest` is skipped unless
asked); standalone Rust tests pass (3 boss identity, 2 grace edge, 6 lighting, 4 depth, 4 player status); loot simulation of
700,000 kills passes; all shaders compile.

From the workspace root:

```bash
tools/build_dll.sh --offline
tools/shader_check/check_shaders.sh
python3 tools/test_loot.py
rustc --edition 2021 --test tools/test_loot_identity.rs -o /tmp/eldencraft-loot-identity-test && /tmp/eldencraft-loot-identity-test
rustc --edition 2021 --test tools/test_grace_edges.rs -o /tmp/eldencraft-grace-test && /tmp/eldencraft-grace-test
rustc --edition 2021 --test tools/test_lighting.rs -o /tmp/eldencraft-lighting-test && /tmp/eldencraft-lighting-test
rustc --edition 2021 --test tools/test_depth.rs -o /tmp/eldencraft-depth-test && /tmp/eldencraft-depth-test
rustc --edition 2021 --test tools/test_player_status.rs -o /tmp/eldencraft-player-status-test && /tmp/eldencraft-player-status-test
```

Java (from `fabric`; add `ELDENCRAFT_REWARDS=1` and `cleanTest test --tests dev.eldencraft.combat.RewardsSheetTest`
to rewrite `rewards.md`):

```bash
JAVA_HOME='/run/media/alex/a0da83cf-b021-4cfe-9104-edb6dd726a80/Skycraft/.install/jdk-25.0.4.1+1' GRADLE_USER_HOME='/run/media/alex/a0da83cf-b021-4cfe-9104-edb6dd726a80/Skycraft/.install/gradle' ./gradlew test jar --offline --no-daemon --no-configuration-cache
```

Minecraft 26.3 (unobfuscated: Mojang names in mixins), Fabric Loader 0.19.5, Fabric API 0.161.0+26.3,
Java 25. To check a 26.3 class or method, `javap` the jar under `fabric/.gradle/loom-cache/minecraftMaven/`.

Install `fabric/build/libs/eldencraft-0.0.1.jar` into the instance's `mods/` only while both games are
closed; the DLL needs no copying. Instance:
`/run/media/alex/a0da83cf-b021-4cfe-9104-edb6dd726a80/SteamLibrary/steamapps/compatdata/1245620/pfx/drive_c/users/steamuser/AppData/Local/EldenCraft/Prism/instances/EldenCraft/.minecraft`.
Launch from the workspace root: `~/.local/bin/me3 launch -p me3/eldencraft.me3`.

Controls: F8 MC movement, F9 first person, F10 input owner, F5 MC camera, F7 collision view, F4 block light
(game picture / clock), F3 block occlusion (game depth / collision), F6 ray probes, F11 render-history delay,
R native interaction (assumes ER's Event Action is E).

Logs (capture before relaunching): native `game/target/x86_64-pc-windows-msvc/release/eldencraft.log`;
Minecraft `<instance>/logs/latest.log`; me3 `~/.local/share/me3/logs/eldencraft/`.

## Open items

1. Play-check the 6 Oct build (list in `roadmap.MD`), including the status buildup order.
2. Play-check the new player status mirror: all seven effects, native timers, buildup/wards, cures,
   loading/death/respawn/disconnect cleanup. It is built, not yet seen in play. Any additional MC status
   penalties still need the user's decision.
3. Deferred by the user: floating gathering nodes, full-inventory / simultaneous-death / multi-phase boss
   tests. Not yet scoped: villager traders, seamless co-op.
