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
While Minecraft has the controls, **R** taps ER's Event Action (E): open doors, pull levers, pick
up items, rest at graces and confirm item/message popups. The ER body turns with Minecraft's look,
and an interaction animation owns the body until it ends while Minecraft follows it.

Player kills produce family-appropriate Minecraft materials and XP. Region and encounter mappings
select tiers 1–5 in the base game and 6–7 in Shadow of the Erdtree. Equipment-capable enemies
have a 10% gear chance (9% regional tier, 1% upgrade within the expansion), with 30–70% durability.
Boss completion flags award one named enchanted item at full durability, one enchanted book and
materials. Seventeen four-piece collections span different bosses; completion bonuses come later.
Boss parcels persist in `eldencraft_boss_rewards_v2.json` in the Minecraft world and wait for
inventory space or respawn. Original ER rewards remain unchanged. Loot data is generated offline
by `tools/generate_loot.py`; see [the complete reward sheet](docs/loot-boss-rewards.md).
The rebuilt protocol version 3 DLL and JAR must be installed together. Automated checks pass;
native completion flags, region boundaries and gameplay balance still need in-game confirmation.

Terrain recovery has recently changed and still needs gameplay confirmation at grace respawns.
Native movement now uses Minecraft's actual tick feet; the camera keeps its interpolated render
position. Camera direction is calibrated only while ER owns the view. Loads and grace respawns
start a fresh collision epoch, including when the native player object and map are reused.
Teleport acknowledgements wait for collision across the player's footprint/body and for the local
Minecraft server's teleport to settle. Spawn lifting only uses walkable surfaces within step
height. A delayed handoff refreshes its destination scan after two seconds and returns ER controls
after eight seconds instead of leaving the Minecraft view frozen. New logs distinguish missing
terrain, missing support and server teleport readiness.
The player also receives a fresh local floor patch from native raycasts. Its samples expire after
150 ms and match the current map/collision epoch. Small slope corrections preserve Minecraft's
camera and input ownership while waiting for the teleport acknowledgement; repeated deep falls
still return control to ER.

Map-local coordinates now use the matching `PlayerIns.current_block_id`, rather than the
character's resource/LOD block. Map overrides also apply when filtering combat targets.
A steady world-offset discontinuity parks Minecraft and refreshes collision/teleport state;
a pure Havok origin shift keeps the existing world collision. Every two seconds, `maps:` logs
record position/body/origin map IDs, play regions, local/physics coordinates, model/physics lag,
update omission mode, pending native warps and geometry-container counts for streamed interiors.
Container presence does not prove that all geometry has loaded. The cave session showed native
physics/model positions following Minecraft while play-region tracking stayed behind; handing
back to ER immediately advanced it and loaded Groveside geometry. Installed-code analysis found
that `no_gravity` skips native ground-contact processing. MC mode now permits native contact
only when a fresh five-ray floor patch is within 0.45 m of acknowledged feet and the body is
within 0.5 m. It keeps suspension above Minecraft blocks, in air, and during teleport waits.
Native physics remains responsible for ground flags, map ownership and last-safe save positions.
Settled native-region changes refresh nearby collision immediately and again after two seconds,
keeping previous surfaces until replacements arrive. `maps:` logs now include native ground
flags and suspension state. Cave entry, deeper rendering and MC-block support need gameplay
confirmation with this build.

Walls proposed from stacked downward crossings are confirmed with short sideways native rays
in half-block height bands. Each wall segment follows confirmed native hits at its centre and
endpoints, including angled walls; curved corners keep their centre vertex. Door-frame segments
are shortened to confirmed endpoints instead of extending a flat sampling strip into the passage.
Roofs above open passages cannot create tall guessed walls through
empty space. Floor and ceiling surfaces remain based on actual ray hits. Confirmation shares
the existing scan budget; collision logs count confirmed wall hits and rejected guesses. Buried
terrain recovery excludes finite guessed volumes between roofs and floors.

Fresh Minecraft characters start with an empty inventory and no equipped items. Automatic starter,
builder and test-guest equipment grants have been removed; existing inventories are preserved.

### Gathering

Visible Minecraft resource deposits appear on clear, gently sloping scanned surfaces near the
host player. Outdoors there are oak log piles, loose-stone slabs and stone deposits. Logs and
loose stone can be harvested bare-handed; loose stone gives cobblestone. Larger stone requires a
pickaxe and has a 10% copper bonus in tier 1 or raw-iron bonus in later regions.
Visible ore deposits spawn in the eight explicitly mapped mine interiors. Copper/coal come first,
then iron, then gold and rare diamonds from tier 3; tier 5 adds ancient debris. Valuable gathering
materials do not spawn in ordinary outdoor rocks. Vanilla mining tool requirements, smelting,
Fortune and Silk Touch apply to the ore blocks.

Harvested deposits remain depleted across travel and reloads. A native grace-rest flag transition
replenishes them; nearby loaded deposits return when clear. Player-built blocks prevent a node
from reappearing at that position. Node identities and the rest cycle are saved in the world as
`eldencraft_resources_v1.json`. No native digging: ER terrain, trees and structures are preserved.
Existing worlds receive nodes without a reset. This gathering build uses protocol version 3 and
requires its matching DLL/JAR. Placement and grace detection await in-game confirmation.

### Combat

Elden Ring's seven player statuses also mirror into Minecraft effects with icons and native timers.
A left-side status HUD shows buildup percentages and active durations. ER continues to apply their
damage through the HP sensor; the custom effects do not add damage or attribute penalties. Native
cures, loading, death and disconnect clear the mirror. Built 6 October; not yet seen in play.

- Nearby hostile ER characters become Minecraft weapon targets. Allies, spirit summons and
  friendly/neutral NPCs are excluded. Capsule hitboxes follow the ER physics position each frame.
  A character which actually damages the native player can also become a target when its
  team/type/activity metadata would otherwise exclude it. This fallback still checks the current
  live list, map, health pool and disabled/unloaded flags, and clears across loads. Logs record
  the excluded attacker's model, team, type and filter reason, newly published target dimensions,
  and outgoing hits dropped for stale targets or melee reach. The forest capture identified three
  active `c4311` soldiers on team `48`, previously omitted by the normal enemy filter. That
  model/team combination now publishes a target before aggro, enabling opening melee and bow
  attacks without first taking or blocking an enemy hit. Other undocumented team values retain
  the observed-attacker fallback until their hostility is confirmed.
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
  do not overlay MC equipment on ER's third-person body. While the Minecraft camera owns the
  view, the ER model stays enabled with zero base transparency; MC movement also requests its
  normal character updates each frame. Handback/loading restores the body's original transparency
  and render flag. The latest cave fix also permits native terrain contact beside fresh ER
  floors; suspension remains active on MC blocks and during unacknowledged teleports.
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

### Movement milestone: manual acceptance

After restarting both games with matching DLL/JAR builds:

1. Respawn at a grace, stay still, press F8, and confirm the handoff releases without falling.
2. Sprint across slopes and stairs, then stop: no repeated floor rescues, snapback or camera spin.
3. Walk through the church passage and bushes, and walk against an actual stone wall.
4. Switch F8/F9/F10 and Minecraft's F5 views, then die and repeat the stationary grace handoff.
5. Place a block, stand on it, switch controls and check that both positions remain consistent.
6. Approach Groveside Cave in MC mode, wait at any gray barrier, enter and turn around inside;
   compare ER mode if blocked or geometry is missing, then leave and enter again.

The movement milestone remains open until these scenarios have been observed in game.

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
