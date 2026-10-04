# EldenCraft — continuation handoff

Prepared 4 October 2026 for continuing this local prototype with Claude.
Read this document, `README.md`, `roadmap.MD`, and the current Git diff before editing.

## 1. Purpose and scope

EldenCraft lets a Minecraft player explore, build and fight inside Elden Ring's world.
It adapts the SkyCraft concept from Skyrim to Elden Ring. Both games still run: an
injected Rust DLL in Elden Ring exchanges data with a Fabric mod in a hidden Minecraft
instance, then draws Minecraft content over Elden Ring.

Work on **EldenCraft only**:

```text
/run/media/alex/a0da83cf-b021-4cfe-9104-edb6dd726a80/EldenCraft
```

The Codex chat's working directory was the adjacent `Skycraft` project. That is not the
project being developed. Some inherited filenames/classes/comments inside EldenCraft
still say Sky/Skyrim; do not mistake them for the separate Skycraft checkout.
Skycraft's existing Java, Gradle cache and Windows SDK are reused for local builds.

The user wants local development. An earlier explicit request published the initial
EldenCraft repository to https://github.com/wargamereview-ship-it/EldenCraft.
There is no new request to commit or push the current fixes. Preserve existing local
changes and saves. The modded game uses me3's offline launch without Easy Anti-Cheat.

## 2. Current prototype state

This is a playable local prototype, with implemented rendering, movement and combat
bridges. It is not yet a reliable complete playthrough. Several difficult bugs improved
over repeated user game sessions; movement and combat acceptance remain open.

| Area | Implemented | Evidence / remaining limits |
| --- | --- | --- |
| Launch and connection | me3 loads the DLL; DLL launches portable Prism/Minecraft in the same Wine session; shared-memory transport | User has repeatedly launched both games successfully. Earlier Proton runtime discovery trouble was resolved locally. |
| Blocks and entities | Minecraft texture/mesh export, native drawing, terrain occlusion, block placement, entity rendering including the requested dropped-item path | Visuals were exercised by the user. Full visual/performance coverage and entity types are not accepted as complete. |
| HUD and camera | MC hearts, armour, hunger, hotbar, hands, crosshair; enemy health/hit feedback; first-person/F5 view integration | Redundant player Life/Tarnished bar removed. Hands are gated separately from HUD. Cave map changes still briefly expose the ER view. |
| Movement | ER controls by default; optional MC movement; native body synchronization, teleport acknowledgements, floor patches and recovery | Earlier falling, snapback and camera instability prompted substantial fixes. Stationary grace respawns, MC-block support and repeated transitions still require regression checks. |
| Terrain collision | Native ray sampling, floors/ceilings, confirmed wall segments, self-body skipping, foliage filtering and diagnostics | Church tunnel movement improved; previous reports included jagged movement and doorway walls extending into passages. Do not assume all caves/tunnels are solved. |
| Cave streaming | Matching map/position coordinates, Havok rebasing, native ground-contact allowance and play-region collision refresh | Latest user entered Groveside in MC mode and stayed in the cave. Earlier gray barrier/missing lower geometry improved, but the transition camera flash remains. |
| Outgoing combat | MC weapon damage applied to live ER enemy HP; hit identity/range/map/protection checks; native AI damage notice | Forest soldier opening-hit fix worked according to user. Cave wolves remain excluded until they attack. ER stagger/defence/guard/stance pipeline is not integrated. |
| Incoming combat/life | MC hearts authoritative; ER HP observes hits; MC armour/absorption/shields/totems; MC death triggers native ER death/respawn | Directional shield source capture and block feedback implemented. Enemy AoE/shield/death edge cases need wider gameplay confirmation. |
| Arrows | Accepted bow impacts retain MC arrows attached to enemy body position/yaw | Root attachment, not animated bones. Bows/tridents and lifecycle edge cases need manual coverage. |
| Loot/progression/dimensions | Future design work | User explicitly wants enemy/boss drops changed to MC items, and much later Nether/End/end portals. No completed ER loot replacement or dimension bridge is claimed. |

Compilation confirms a build, not gameplay correctness. Some helper/multiplayer/digging
code was inherited; its presence does not establish a completed feature.

## 3. Exact stopping point: three latest bugs

The latest request was:

> Entering the cave briefly returned the view to ER mode. Wolves could not be attacked
> until they hit the player or the player blocked an attack. They only aggroed when the
> player was right in their field of view.

These issues were **investigated but not patched** before the user requested this
handoff. The currently compiled DLL still has them.

### A. Wolves have a confirmed target-filter problem

The latest native log identifies cave wolves as `c4070` and `c4071`, team `48`, type
`Npc`, active, with valid cave map and HP. Normal targeting rejects them for `team`.
The observed-attacker fallback publishes their MC hitbox only after a captured attack:

```text
[1791145842.105] combat: unexported native attacker c4070 npc 40700010 team 48 type Npc active true ...; normal filter team; observed-attacker filter accepted; publishing a target this frame
[1791145857.038] combat: unexported native attacker c4071 npc 40710010 team 48 type Npc active true ...; normal filter team; observed-attacker filter accepted; publishing a target this frame
[1791145860.955] combat: actor 13 c4071 weapon 1 MC 7.00 -> 175 ER HP; 466 -> 291 flags 0x0; aggro notified
```

Start in `game/src/combat.rs`, `hostile_character()` and `exclusion()`.
The current extra team-48 allowance covers only forest soldiers `(48, 4311)`.
The logs now provide evidence to add the wolf model/team pairs narrowly, so MC targets
exist before their first attack. Preserve friendly NPC/summon filtering, live identity,
map, health and unloaded/disabled checks. Do not blindly treat every team-48 actor as hostile.

### B. Brief ER camera flash: strong code-path explanation, pending fix

At the cave boundary the position map changes from exterior `m60_42_37_00` to
Groveside `m31_03_00_00`. The same native body remains live. `anchor()` waits for a
new mapping to settle; `run()` calls `publish_loading(Some(player))` in the meantime.
That function restores model/HUD/input state and calls `camera::set(None)`, exposing
the ER camera. The log records a fresh load/handoff at `1791145814.959`.

Relevant files: `game/src/lib.rs` (`anchor`, `publish_loading`, main frame flow,
`set_model_hidden`) and `game/src/camera.rs` (`set`, `apply`, view history).

Design a bounded handoff for a live connected interior transition which holds MC
movement safely, preserves camera/input ownership, and rebases the eye correctly.
Keep genuine loading, missing/replaced bodies, death and respawn paths distinct.
Do not retain stale coordinates or permanently suppress loading recovery to hide the flash.

### C. Narrow passive aggro: cause not established

Accepted nonlethal hits already reach the verified ER AI damage-notification path;
the latest wolf log says `aggro notified`. Fix A should allow opening hits to alert
wolves. Passive detection still needs investigation and comparison with ER mode.

Possible leads, **not conclusions**: MC-driven movement lacks native walking/footstep
signals; native vision may interact with the hidden player's zero base transparency;
sleeping/cave enemies may have ordinary scripted vision restrictions. Keep visual
hiding separate from native simulation eligibility. Do not make all enemies aggro
through walls or send fake damage notices to simulate ordinary detection.

## 4. Important fixes already made — preserve these

### Movement and handoffs

- Native movement uses MC's actual tick feet; camera uses interpolated render position.
- Camera conventions are learned only when ER owns the view, avoiding sign flips.
- Collision/teleport epochs refresh on loads and grace respawns, including reused bodies/maps.
- Teleport readiness checks the player's footprint/body and integrated-server acknowledgement.
- A delayed handoff rescans after 2 seconds and returns ER controls after 8 seconds.
- Fresh local native floor samples expire after 150 ms and are tied to map/epoch.
- Small floor corrections preserve camera/input; repeated deep falls can return to ER.
- Native raycasts skip the player's own body, which previously looked like a floor above feet.

### Tunnels and targeting

- Stacked downward crossings no longer create tall guessed walls under roofs.
- Sideways rays confirm walls in half-block height bands; angled walls follow real hits.
- Door-frame ends are shortened to confirmed endpoints; floor/ceiling rays remain independent.
- Real incoming attackers can bypass incomplete team/type/activity metadata, with other
  identity/map/health/unloaded protections retained and fallback cleared across loads.
- Forest soldiers `c4311`, team `48`, are exported before attacking; user said this worked.
- An undocumented event byte is no longer assumed to mean unconditional immunity.

### Cave/map streaming

- `Space::of` pairs `PlayerIns.current_block_id` with its matching block-local position.
- Effective map overrides apply to combat filtering; physics-origin rebases are distinguished
  from actual world/map discontinuities.
- ER model remains enabled with `base_transparency = 0` in MC camera mode, and MC movement
  requests native character updates. Original transparency/render flags restore on handback.
- Installed-executable analysis found `no_gravity` skips native contact processing, stalling
  safe-ground/play-region updates. This caused streaming to resume only in ER mode.
- MC mode now permits native contact when fresh five-ray floor support is within 0.45 m
  of acknowledged feet and body lag is within 0.5 m. Suspension stays on in air, on MC blocks,
  and during unacknowledged teleports.
- Native code owns ground flags, map/safe positions and region updates. Do not manually
  force save/safe-position fields or play-region IDs.
- A native region change stable for 200 ms refreshes nearby collision immediately and
  again after 2 seconds, keeping old surfaces while replacements arrive.

Latest cave logs show native body/model following MC, native contact enabled on floors,
and native player/field play-region `3103001` matching. Geometry-container presence alone
does not prove complete loading.

## 5. Combat rules and limitations

- Nearby live hostile characters within 64 m become invisible MC proxy hitboxes.
- Outgoing scale is `HP_PER_DAMAGE = 25`: one MC damage point removes 25 ER HP.
- MC computes weapon cooldown, criticals and enchantment damage; bridge validates live
  identity, mode, map, reach (4.25 m melee tolerance), death and native protection flags.
- Accepted hits modify HP/last-attacker attribution, then `aggro.rs` reports damage to local AI
  without applying HP twice. Native function fingerprints and controller ownership are checked.
- This is not ER's complete damage pipeline: native guarding, absorption/defence, poise,
  stagger, stance breaks, knockback and physical hit animations/sounds remain future work.
- MC owns player health while the local bridge is connected, including ER controls.
  ER HP is held full with a saved/restored no-death bit and used as a hit sensor.
- Incoming scale is `20 * native HP lost / maximum native HP`. It is already mitigated by
  ER before MC applies its rules; damage types, overkill and statuses are incomplete.
- A guarded native damage-call observer captures the attacker position for directional
  MC shields, including physical slams. Unknown/environmental damage remains undirected.
- Right mouse with an MC shield uses vanilla delay, durability and rear-hit rules, plus
  confirmed block sound, recoil and BLOCKED feedback.
- At zero MC hearts, ER death/rune-loss/grace respawn is requested. Life epochs reject stale hits.
- Attached arrows expire at 60 s, with limits 32/enemy and 128 total; removed on death/unload.
  They follow actor root/yaw, not bones, and are not persisted. Piercing remains vanilla.
- Names are currently model labels such as `Enemy c4071`. Boss phases/rewards/death lifecycle
  are not broadly verified. Do not equate a zero-HP log with completed boss progression.

## 6. Code navigation

Paths below are relative to the EldenCraft root.

| Concern | Files |
| --- | --- |
| Frame tasks, ownership, lifecycle, mapping | `game/src/lib.rs`, `world.rs`, `movement.rs` |
| Camera and render synchronization | `game/src/camera.rs`, `scene.rs` |
| Collision scanning | `game/src/scan.rs`; Fabric `world/SkyCollision.java`, `TriCollider.java`; client `SkyCollider.java` |
| Native combat, HP and incoming source capture | `game/src/combat.rs`, `native_hits.rs`, `aggro.rs` |
| Input / launch | `game/src/input.rs`, `dinput.rs`, `launcher.rs` |
| Native graphics / HUD | `game/src/gpu.rs`, `render.rs`, `hud.rs` |
| Shared protocol | `protocol/eldencraft_protocol.h`, `game/src/proto.rs`, Fabric `link/Proto.java` and `SkyLink.java` |
| MC handoffs / body and render positions | `fabric/src/client/java/dev/eldencraft/client/SkyClient.java` |
| MC proxies / outgoing and incoming damage | client `ProxySync.java`; main `combat/SkyCombat.java`, `SkyrimActorEntity.java`, `NativeDamageSource.java` |
| Arrows / shield / feedback | main `combat/AttachedArrow.java`; client `ShieldImpact.java`, `CombatHud.java`; relevant mixins |
| Meshes, textures, entities and avatar export | client `render/WorldExporter.java`, `SkyAtlas.java`, `AvatarExporter.java`, `FrameExporter.java` |

All Java short paths in this table are inside `fabric/src/{main,client}/java/dev/eldencraft/`.
Protocol layout changes must agree in C, Rust and Java and use matching deployed builds.
Native simulation work runs in `FramePostPhysicsSafe`; camera replacement in `DrawParamUpdate`.
Never dereference actor pointers retained from an earlier frame; match current live identities.

## 7. Build, deployment, launch and logs

### Build native DLL on Linux

From the EldenCraft root:

```bash
tools/build_dll.sh --offline
```

Uses Rust `x86_64-pc-windows-msvc`, `lld-link`, `clang-cl`, `llvm-lib`, and the existing
Windows sysroot (default `../Skycraft/.install/xwin-out`; configurable via `XWIN_SYSROOT`).
me3 loads `game/target/x86_64-pc-windows-msvc/release/eldencraft.dll` directly.

### Build Fabric JAR

From `EldenCraft/fabric`:

```bash
JAVA_HOME='/run/media/alex/a0da83cf-b021-4cfe-9104-edb6dd726a80/Skycraft/.install/jdk-25.0.4.1+1' GRADLE_USER_HOME='/run/media/alex/a0da83cf-b021-4cfe-9104-edb6dd726a80/Skycraft/.install/gradle' ./gradlew jar --offline --no-daemon --no-configuration-cache
```

Current configuration: Minecraft 26.3, Fabric Loader 0.19.5, Fabric API
0.161.0+26.3, Java 25, mod version 0.0.1. Output:
`fabric/build/libs/eldencraft-0.0.1.jar`. Recent work compiled artifacts and used user
game sessions; no automated tests were run for the latest changes.

Copy a changed JAR into the instance's `.minecraft/mods/eldencraft-0.0.1.jar` and restart
both games so DLL/JAR/protocol versions agree. Building the JAR alone does not deploy it.
The DLL must launch Prism inside ER's Wine session: a separate Linux Minecraft process
does not share the `Local\EldenCraft_v1` mapping.

Instance location:

```text
/run/media/alex/a0da83cf-b021-4cfe-9104-edb6dd726a80/SteamLibrary/steamapps/compatdata/1245620/pfx/drive_c/users/steamuser/AppData/Local/EldenCraft/Prism/instances/EldenCraft/.minecraft
```

Launch command the user has been using:

```bash
cd /run/media/alex/a0da83cf-b021-4cfe-9104-edb6dd726a80/EldenCraft && ~/.local/bin/me3 launch -p me3/eldencraft.me3
```

Controls: ER movement initially; F8 toggles MC movement; F9 first-person camera;
F10 input ownership; F5 MC camera modes; F7 collision debug; F6 native ray/column probes;
F11 camera/render history delay diagnostic. MC left click attacks, right click uses/shields.

Logs (copy before a new launch overwrites them):

- Native: `game/target/x86_64-pc-windows-msvc/release/eldencraft.log`.
- Minecraft: the instance path above plus `/logs/latest.log`.
- me3: `/home/alex/.local/share/me3/logs/eldencraft/`.
- A snapshot of the logs used for this handoff is in
  `/tmp/eldencraft-handoff-docs/logs/`; `/tmp` is temporary, so preserve anything needed.

### Exact artifact state at handoff

| Artifact | Size / last modified UTC | SHA-256 |
| --- | --- | --- |
| DLL | 1,285,632 bytes; 2026-10-04 20:24:30 | `535eea60c3a67a0e5fab54aaa6542a640ad8b2c66b1f7e4ecb10907f24d85555` |
| Built and installed JAR (matching) | 272,215 bytes; 2026-10-04 18:24:33 | `ba3edbb8ce8c2b859742cb11cff2c957fa383a0ae68c595639d5234b0131c8fa` |

The last cave fix changed only Rust/docs, so the earlier JAR date is expected.

## 8. Git and local investigation state

Branch `main`; HEAD `f901293` (Add staged EldenCraft project), preceded by `cc89589`.
Local `origin/main` also points to `f901293`; no remote network check was made during
this handoff. Eleven existing files were modified before adding these documents:

```text
README.md
fabric/src/client/java/dev/eldencraft/client/SkyClient.java
fabric/src/client/java/dev/eldencraft/client/SkyCollider.java
fabric/src/main/java/dev/eldencraft/world/SkyCollision.java
fabric/src/main/java/dev/eldencraft/world/TriCollider.java
game/src/camera.rs
game/src/combat.rs
game/src/lib.rs
game/src/link.rs
game/src/scan.rs
game/src/world.rs
```

These contain working local fixes beyond the pushed initial project. Do not reset them
or treat the GitHub snapshot as the latest prototype. Build outputs are ignored.

Previous investigation/backups in `/tmp` include:

- `eldencraft-movement-stability`: tick-vs-render movement, bounded handoffs and spawn fixes.
- `eldencraft-tunnel-targeting-fix`: tunnel walls and observed-attacker fallback.
- `eldencraft-opening-hit-fix`: forest soldier model/team allowance.
- `eldencraft-cave-map-fix`: map pairing, render/update eligibility, diagnostics;
  includes a pre-change MC world backup.
- `eldencraft-streaming-region-fix`: latest native contact/region-refresh source baseline,
  DLL/log backups, build report and executable analysis.

Native SDK checkout:
`/home/alex/.cargo/git/checkouts/fromsoftware-rs-66ca903120e84447/59fbd3b`
(commit `59fbd3b3b7daaf14aca47c9f73530493dba6bc79`).
Installed `eldenring.exe` is in the same Steam library under
`steamapps/common/ELDEN RING/Game/`. Native bridges are tied to inspected code and guarded
signatures. me3 reports game version 1.17.1.0; native bridge comments use WW 2.7.1 labels.
Recheck actual code/signatures for a changed executable rather than relying on labels.

Useful verified native details, if streaming investigation resumes:

- `0x467130`: `ChrIns.no_gravity` feeds physics contact/gravity suppression.
- `0x3f6750`, `0x3feb00`, `0x402530`: native ground/contact processing and flags.
- `0x65b180`: safe block position/ID and player play-region update; native ground gating
  occurs in caller `0xb02ba0` (call at `0xb02cfb`).
- AI damage notice at `0x2c50d0`, manipulator accessor `0x3f0560`; `aggro.rs` carries guards.
- `llvm-objdump` and Python `pefile` were available; `capstone` was not. If reading PE
  runtime-function ranges, filter `.pdata` entries to valid `.text` bounds and reasonable
  sizes before disassembly; padding produced misleading huge ranges during investigation.

## 9. Resume plan

1. Read the diff and snapshot logs; back up current source/artifacts before editing.
2. Publish verified team-48 wolf targets before attacks. Confirm opening melee and bow hits.
3. Separate connected cave transition holding from true loading so the MC camera does not flash.
4. Investigate passive detection separately, then validate native aggro after opening damage.
5. Repeat movement/collision regressions: stationary grace -> F8, slopes/stairs, church tunnel,
   foliage, placed-block support, cave entry/deeper geometry/re-entry, death and view toggles.
6. Continue the ten milestones in `roadmap.MD`; full combat and MC loot follow stabilization.

For acceptance, use user-observed sessions and focused log evidence. A previous optimistic
claim of “no more falling” was corrected by the user: stationary grace -> F8 could still
fall. Report what was observed, what is only implemented, and what remains unverified.
