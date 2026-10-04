# EldenCraft

Play Elden Ring as a Minecraft player: SkyCraft's idea, with The Lands Between in place of Skyrim.

Neither game is rewritten. A native DLL inside Elden Ring and a Fabric mod inside a hidden
Minecraft talk through shared memory (`Local\EldenCraft_v1`).

> **Offline only.** Mods only run with Easy Anti-Cheat off. me3 starts the game without it.
> Never take a modded game online.

## Current local prototype

- `game/` (Rust, [fromsoftware-rs](https://github.com/vswarte/fromsoftware-rs)): maps ER physics
  into Minecraft coordinates, exports terrain collision, draws Minecraft blocks and entities,
  composites the HUD, and drives the ER character controller when Minecraft has movement.
  Logs to `eldencraft.log` next to the DLL, including movement recovery and combat results.
- `fabric/`: the Minecraft side (`dev.eldencraft`). Uses exported terrain, builds and renders
  blocks/items/HUD, and creates invisible hitboxes for nearby ER enemies.
- `protocol/eldencraft_protocol.h`: shared memory layout. `game/src/proto.rs` and `Proto.java`
  mirror it. Actor IDs are temporary session IDs; they are cleared on map loads and respawns.

ER movement is the default. **F8** gives movement and attacks to Minecraft; **F10** switches
input ownership, **F9** toggles the first-person camera, **F5** cycles Minecraft camera modes.
Terrain recovery has recently changed and still needs gameplay confirmation at grace respawns.
The player also receives a fresh local floor patch from native raycasts. Its samples expire after
150 ms and match the current map/collision epoch. Small slope corrections preserve Minecraft's
camera and input ownership while waiting for the teleport acknowledgement; repeated deep falls
still return control to ER.

Walls proposed from stacked downward crossings are confirmed with short sideways native rays
in half-block height bands. Roofs above open passages cannot create tall guessed walls through
empty space. Floor and ceiling surfaces remain based on actual ray hits. Confirmation shares
the existing scan budget; collision logs count confirmed wall hits and rejected guesses. Buried
terrain recovery excludes finite guessed volumes between roofs and floors.

### Combat

- Nearby hostile ER characters become Minecraft weapon targets. Allies, spirit summons and
  friendly/neutral NPCs are excluded. Capsule hitboxes follow the ER physics position each frame.
- Equip a Minecraft sword or axe, aim at the enemy, and **left-click**. Minecraft computes attack
  cooldown, criticals, enchantments and damage. Existing projectile/fire hit events use the same
  damage bridge; bows and tridents also need gameplay confirmation.
- **1 Minecraft damage point = 25 ER HP** (`HP_PER_DAMAGE` in `game/src/combat.rs`). A fully
  charged 6-damage hit therefore removes 150 HP before any enemy regeneration.
- The native bridge checks live identity, map, mode, melee reach, death and invulnerability before
  changing HP. It drains attacks during loading and mode changes so they cannot hit a respawned actor.
  An undocumented event-module byte is no longer treated as unconditional immunity. Remaining
  rejections log the individual native protection flags and raw values for diagnosis.
- Accepted damage reports the local player to the enemy's native AI damage-notification
  path, so melee, projectile and fire hits can alert enemies and update their attack target.
  This does not apply HP damage a second time. The bridge checks the installed 2.7.1 code,
  live controller ownership and local AI manipulator; unsupported builds/AI skip the notice
  and log the reason. Scripted AI restrictions remain owned by ER; aggro needs gameplay confirmation.
- Minecraft hearts are authoritative while the local bridge is connected, including ER controls.
  ER HP is a hit sensor held at full health with its no-death bit; native HP losses are scaled as
  `20 * lost HP / maximum ER HP`, then Minecraft applies armour, absorption, damage cooldowns,
  totems and shield rules. This uses post-ER-mitigation damage; native damage types/overkill and
  status effects are not yet exported separately. A captured attacker position enables directional
  shield blocking. The 2.7.1 damage-call observer captures the actual attacker before ER's
  death-only last-attacker field; it checks executable signatures and observes HP losses without
  changing native attacks. Unidentified/environmental damage remains undirected. Physical slams
  use the attacker's position for the shield's front-facing check too. Hold **right mouse** with
  an MC shield in MC mode; shield delay, durability and rear-hit rules are vanilla Minecraft.
  Confirmed blocks play the vanilla block sound and show a short shield recoil and BLOCKED flash.
  Incoming logs include source position, shield angle and the amount actually blocked.
- At zero Minecraft hearts, ER performs its ordinary death/rune loss and grace respawn. Minecraft
  respawns after the native loading cycle finishes. Life epochs discard old hits across loading;
  the native no-death bit is restored when the bridge releases a live body.
- Vanilla Minecraft hearts display player health. The combat overlay shows target health,
  accepted damage, critical indicators, immunity and confirmed shield blocks.
  The Minecraft HUD also stays visible while ER controls movement. First-person hands and the
  crosshair are gated separately by the native camera mode, so ER controls / F9 / grace respawns
  do not overlay MC equipment on ER's third-person body. The native render flag is reapplied
  each frame while the Minecraft camera owns the view.
- Bow arrows (plain, tipped and spectral) retain their real MC entity at accepted proxy impacts.
  They follow the enemy's body position and yaw, expire after 60 seconds, and disappear when the
  enemy unloads/dies. Limits are 32 per enemy and 128 overall. Piercing projectiles retain vanilla
  behaviour. Attachments follow the actor root, not animated limbs, and do not survive restarts.
  Native and Minecraft logs both report accepted hits.

**Limits:** outgoing hits change bound native HP fields, last-attacker attribution and send a
verified native AI damage notice. They do not invoke ER's full damage pipeline, so defence/absorption, guarding, stagger, stance breaks, knockback
and native hit sounds are not yet connected. ER owns death handling; rune rewards, loot and boss
phase transitions need gameplay confirmation. Enemy labels currently use model IDs (`Enemy c####`). Combat is
implemented for the local integrated world; inherited multiplayer/skill helpers remain scaffolding.

The native DLL and Fabric JAR can be compiled locally. Compilation is not gameplay verification.

## Building (Linux)

```bash
tools/build_dll.sh
```

Needs `rustup target add x86_64-pc-windows-msvc`, `lld-link`, and an xwin Windows SDK
(`XWIN_SYSROOT`, defaults to SkyCraft's `.install/xwin-out`).

```bash
cd fabric && ./gradlew build
```

Gives `fabric/build/libs/eldencraft-<version>.jar`. Needs Java 25.

## Running

1. Install [me3](https://github.com/garyttierney/me3) (Linux: its `installer.sh`).
2. Put a portable Prism Launcher at `%LOCALAPPDATA%\EldenCraft\Prism` in Elden Ring's prefix
   (`compatdata/1245620/pfx/drive_c/users/steamuser/AppData/Local/EldenCraft`). It needs an
   `EldenCraft` instance with Minecraft 26.3, Fabric, Fabric API and `eldencraft-<version>.jar` in
   `.minecraft/mods`, and a signed-in Microsoft account. SkyCraft's bundle, copied over, works.
3. `me3 launch -p me3/eldencraft.me3`. The DLL starts Prism's `EldenCraft` instance from inside
   the game, because `Local\` shared memory is only visible within one Wine session.
4. Load a save. `eldencraft.log` (next to the DLL) should say `Minecraft connected` and show both
   positions every 2 seconds.
