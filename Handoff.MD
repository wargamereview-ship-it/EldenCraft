# EldenCraft — current-state handoff

Updated 5 October 2026 (America/Toronto). This replaces the previous handoff and its accumulated
history. Read this, `roadmap.MD`, `README.md`, and the working-tree diff before changing code.
A successful build is not evidence of successful gameplay.

## Project and working rules

EldenCraft runs a native Rust DLL inside Elden Ring and a Fabric mod in a hidden Minecraft
instance. The bridge exports ER collision and actors, renders MC content over ER, and allows MC
movement, combat, inventory, crafting and building in ER's world.

Workspace: `/run/media/alex/a0da83cf-b021-4cfe-9104-edb6dd726a80/EldenCraft`.
The separate `../Skycraft` checkout supplies the existing JDK, Gradle cache and Windows SDK;
inherited Sky/Skyrim class names do not mean that checkout should be edited.
Repository: `https://github.com/wargamereview-ship-it/EldenCraft`.
Local development uses me3's offline launch without Easy Anti-Cheat.

The user prefers decisions one step at a time and questions in ordinary chat; the question widget
has not worked reliably for them. Continue work already authorized without repeated confirmation.
Preserve unrelated local changes. Current local HEAD is `728d002 Looting V1`; the combat, loot,
gathering and documentation work is still uncommitted, including new files. Build outputs are ignored.
Do not reset the checkout to remove the duplicate Looting V1 commit.

## Current build and gameplay evidence

The current DLL and installed JAR use **protocol version 3**. The shared-memory name remains
`Local\EldenCraft_v1`; its suffix is not the protocol version. Both binaries must match.
The installed JAR matches `fabric/build/libs/eldencraft-0.0.1.jar`, SHA-256:
`a5d35f2ee521800991e18c635f69368d45bd886088a44472996ed4886f552906` (6 Oct 2026, wards, trims, XP orbs, varied boss weapons). Protocol additions that keep
version 3 (old binaries ignore them): input `IN_HURT_ELEMENTS` (17) and `PlayerVitals.statusWards` (was padding).
me3 loads the DLL directly from `game/target/x86_64-pc-windows-msvc/release/eldencraft.dll`.

| Area | Current state | Evidence / remaining check |
| --- | --- | --- |
| Launch, rendering, HUD and cameras | DLL starts Prism/MC in the same Wine session; MC content is composited over ER | Used in play |
| Movement and connected caves | MC tick feet drive the native body; camera stays in MC through connected map changes | User confirmed cave entry; recovery edge cases remain |
| Enemy targeting | Hostile factions can be attacked before damaging the player | User confirmed wolves and soldiers |
| Hearing | MC footsteps replay player AiSoundParam IDs learned in ER mode | User confirmed detection from behind |
| ER interactions | R taps Event Action; body faces MC look and native animations temporarily own movement | User confirmed, including the rubberbanding fix |
| Outgoing combat | Native carrier bullet invokes ER guarding, stagger, reactions and AI response with MC damage | User confirmed hits work; weapon tuning remains |
| Incoming damage and death | MC hearts/armour/shields participate; MC death drives ER death and grace respawn | Played previously; transition/recovery cases remain |
| Loot rework | Regional family drops and guaranteed boss gear/books/materials | User confirmed regular NPC drops and boss drops after the hotfix |
| Empty start | Starter, builder and test-guest kits removed; existing inventories preserved | User confirmed (5 Oct 2026) |
| Visible gathering | Log piles, loose stone, rocks, visible mine ores and grace replenishment | User confirmed gathering works in play, including mine ores and grace replenishment (5 Oct 2026); tuning of yields/placement and the player-built-block check not separately reported |
| Native digging | Explicitly disabled | User withdrew digging from the requested scope |
| Villager traders | Discussed as possible replacements/overlays for selected ER merchants | Not implemented or yet scoped |
| Seamless co-op | Feasibility investigated only | Not implemented |

The Minecraft world was reset without backup at the user's explicit request before loot testing.
The ER save was untouched. Gathering was installed without another reset; existing worlds should
receive nodes. Do not reset either save unless the user asks.

## Loot: current rules and implementation

- Fresh MC characters receive nothing; no optional generous testing preset.
- Enemy family determines supplies; region/placement determines quality. HP only changes bulk
  material quantities, not the tier. Every eligible ordinary kill has a guaranteed material item.
- Base game tiers 1–5; Shadow of the Erdtree tiers 6–7. Early gear stays early and late gear stays late.
- Equipment-capable ordinary enemies have a 10% gear chance: 9% regional tier, 1% one tier higher,
  capped at T5 in the base game and T7 in the DLC. The capped upgrade uses a stronger same-tier
  enchantment package. Ordinary equipment has 30–70% durability remaining.
- Boss completion gives a fixed named enchanted item at full durability, a curated enchanted book,
  regional materials and XP. There are 17 four-piece collections (68 pieces), each spread over
  multiple fixed boss encounters. Collection bonuses and custom visuals are deferred.
- DLC boss primaries include Protection V/VI and Sharpness VI/VII plus compatible utilities.
  Custom ER elemental/status effects have not been implemented or advertised on these rewards.
- Original ER drops and runes remain unchanged. MC boss claims are per world/encounter flag, not
  per NG+ cycle. Old V1 text claims are not migrated.

`tools/loot_catalog.json` contains the public-data reference snapshot: 446 map defaults, 372 enemy
models and 208 boss encounters. `tools/generate_loot.py` writes loot rules, 105 enemy/gear/boss
tables, `game/src/loot_catalog.rs` and `docs/loot-boss-rewards.md`. The generated reward sheet is the
current item assignment; the proposal document contains earlier illustrative signature examples.
Public-data coverage is not proof that every runtime identity or boundary has been verified.

Native `loot.rs` observes completion flags after seeing a nearby encounter alive. Known boss
actors suppress ordinary payout so duo members and phases do not each generate a boss reward.
Radagon/Elden Beast share one completion. Unmapped team-7 actors have a generic fallback reward.
Four-event loot batches carry position, identity, raw map/play region and completion flag.
`LootRules`, `LootEquipment`, `SkyLoot` and `BossRewards` implement the MC reward path.

The no-drop hotfix removed zero actor IDs from boss lists. `Boss::matches` requires a nonzero
actor identity or an exact map/NPC fallback; zero must never act as a wildcard. Keep the regression
checks in `tools/test_loot_identity.rs` and `tools/test_loot.py`.

Boss parcels persist in the world's `eldencraft_boss_rewards_v2.json` before delivery. Player
attachments track item/partial-stack/XP receipts. Full inventories hold outstanding rewards;
player death delays delivery until respawn. These recovery paths are implemented but the user
explicitly postponed their gameplay tests. Ordinary delayed events and retries during boss-ledger
I/O failure are in memory, not durable across every possible interruption.

## Gathering: current rules and implementation

**No native terrain digging or native tree/structure destruction.** MC deposits are additional
visible blocks placed in available space; ER geometry is retained.

- Outdoors: oak log piles, low mossy loose-stone slabs and larger stone deposits.
- Logs and loose stone are harvestable bare-handed. Loose stone drops cobblestone; a wooden
  pickaxe then enables harvesting larger stone. Normal tool speed/requirements apply.
- Ordinary rocks have a 10% bonus: raw copper at T1, raw iron at T2+. They never generate valuable
  gathering ores. This restriction concerns gathering nodes, not the approved enemy/boss loot.
- Mine ore is visible immediately. T1 has copper/coal; T2 adds iron; T3 adds gold/rare diamonds;
  T4 increases diamonds; T5+ adds ancient debris. Vanilla tool gates, Fortune, Silk Touch,
  smelting and netherite upgrading apply.
- Harvested nodes replenish after a native grace rest. Travel/reload alone does not replenish them.
  Replenishment waits for a clear loaded position and never replaces player-built MC blocks.

`ResourceBlocks`, `ResourceNodes`, `ResourceRules` and `ResourceSurface` own the system. The
custom loose-stone block has slab geometry, low hardness and no correct-tool requirement.
Deposits rest at the ground's exact height (`ResourceRules.restHeight`): cubes sink up to 0.6 into it or
rise to the next block (never floating more than 0.4); loose stone is a slab placed in half-block steps
(bottom or top half, persisted as kind `loose_top`). Each block follows the ground separately. Nodes
placed before this change keep their old, sometimes floating, positions. Each cell tries up to 6 candidate points (a tunnel is narrow; one point per cell mostly landed in rock),
mines look 3 cells around the player, and a cell with no usable floor is skipped for 90 s. Support is the
scan skin or a walkable floor surface at that height. Placement runs 4 times a second (up to 12 deposits per pass, one save per pass) and logs every 30 s
why cells were skipped (no native floor / blocked / no support). Outdoors use a deterministic 12-block placement grid; mines use an 8-block grid. Placement needs
five native floor samples covering the footprint, <=0.45 m height variation, known collision,
loaded clear air and entity clearance. Positions are saved before blocks are placed.

The world's `eldencraft_resources_v1.json` stores node identities, generated cells, harvest cycles
and the grace cycle. `ResourceRemovalMixin` wraps `LevelChunk.setBlockState` inside `Level.setBlock`
so mining, explosions and pistons deplete deposits instead of triggering immediate refill.

`resources.rs` reads event flag 9000. `grace_edges.rs` turns false->true into `IN_GRACE_REST` (16),
avoiding repeat refreshes while seated and refreshes from attaching already at a grace. The
client forwards this to the integrated server. Reference: the public grace talk script at
`https://github.com/lraty-li/Elden-Ring-Mods/blob/main/Gacha_Ring/t000001000.py`.
Grace-rest replenishment was confirmed in play.

`tools/generate_resources.py` derives eight explicit area-32 mine interiors from the region rules:
Morne, Limgrave, Raya Lucaria Crystal, Old Altus, Altus, Gael, Sellia Crystal and Yelough Anix.
Overworld labels mentioning a tunnel entrance are not mines. Shared-map mines such as Sealed
Tunnel need a more precise zone definition before enabling deposits there.

Limits: placement-only native rays cannot identify natural versus constructed floors, so nodes
may need location/density adjustments. They do not modify either floor type. Harvesting and
rest events currently use the local host's native link; guest gathering is not implemented.

## Combat and stability details to preserve

MC damage is multiplied by 25 ER HP; melee reach is 4.25 m. Accepted hits use Ruin Fragment
BulletParam/AtkParam_Pc 10176000. ER processes guard/poise/reaction; the HP hook substitutes MC
damage and retains smaller guarded damage. Undelivered bullets fall back to direct HP after
600 ms. Spears, mace, mace smash and criticals have distinct reaction/poise settings but need tuning.

Native bridge references for the currently pinned SDK / ER 2.7.1:

| Module | Native reference | Purpose |
| --- | --- | --- |
| `movement.rs` | ChrCtrl +0x100 | Queues MC destination |
| `native_hits.rs` | 0x448910 HP hook | Attacker/origin capture and MC damage substitution |
| `native_damage.rs` | 0x3a2cb0 SpawnBullet | Outgoing carrier hits |
| `aggro.rs` | 0x2c50d0; ComManipulator 0x3ce9a0 | Direct-hit fallback AI notice |
| `ai_sound.rs` | hook 0x37a6c0; call 0x5f2c50; global 0x3d66548 | Learned native footsteps |
| `dinput.rs` | DirectInput vtable patches | Input ownership and Event Action E injection |

Preserve existing startup fingerprints/guards when changing native bridges. Carrier visuals and
crowded/ranged delivery need further play review. Looting does not yet add reward rolls.
`KEEP_INVENTORY` and `IMMEDIATE_RESPAWN` are currently true. Incoming life/health edge cases
and hunger/healing balance remain open. ER HUD is hidden while MC drives, which may hide prompts.

A prior integrated-server stall led to a watchdog: after 10 seconds without a server tick, MC
logs a thread dump. Recovery without a manual restart is not yet guaranteed. Map/load transitions
refresh collision epochs and teleport readiness; falling, rebases and recovery still merit testing.

Moving platforms (lifts) are not static collision. The scan is a snapshot, so a lift's old
position stays baked into its column. Live probes cover it: `scan.rs` `risen_floor` finds a floor
0.4-1.0 m above the feet (a rising lift outruns the 0.35 m probe start) with 1.6 m of headroom,
used by the ground patch and the fall-recovery sweep. `SkyCollider` drops scanned walkable floors
more than 0.3 m above the live patch (a lift that left) and raises the step height to carry the
player up onto a flat risen patch (<= 1.0 m). A recovery teleport parks Minecraft for ~85 ms, during which a lift
rises ~0.35 m, so a rising lift must not trigger recovery: `Scanner::carried_rise` makes
`fallen_floor` leave flat risen floors (<= 1.0 m) to the Java carry. Built and unit-tested
(`ridesAPlatformThatRoseAboveTheStepHeight`); a first lift test still fell through (the recovery loop
above); after the `carried_rise` follow-up the user confirmed lifts work (5 Oct 2026). Columns are still not rescanned for moving geometry.

Elemental and status enchantments (`ElementalRules`, `StatusMeters`, `ElementalHit`; data in
`data/eldencraft/enchantment/*.json`): Glintstone (magic), Flame, Lightning and Sacred (holy) add
12% of the hit per level, scaled by the enemy family (holy vs undead, fire vs plant, ...); Bloodletting,
Frostbite, Venom and Scarlet Rot fill an Elden Ring-style meter on the enemy (100, bosses 250, harder
after each proc, draining when hits stop). Hemorrhage and frostbite proc a burst of the enemy's max HP,
poison and rot tick for 18 s, frostbite also leaves the enemy taking 20% more for 20 s. These are
Minecraft-side effects delivered as ordinary damage: the DLL sends `ActorRecord.level` as the enemy's max
HP / 25 and treats events flagged `HIT_STATUS` as direct HP with no bullet or stagger. They are NOT
native Elden Ring buildup or elemental damage types (no ER status effects, resistances or visuals). Weapons
get them from bosses (name-themed, level 1-5 by tier) and from about a third of boss books; they
are not in the enchanting table. Unconfirmed in play.

Element of incoming hits (research, 6 Oct 2026; for planned elemental armour enchantments): the damage record at
the HP stage (`native_hits`, 0x448910) names its attack: AtkParam row id at +0x40, table at +0x44 (0 AtkParam_Npc,
1 AtkParam_Pc). The game itself looks the pair up as param slot table+7 (fn 0x140d247d0, called from 0x140449dfd)
and reads `throw_type_id` (+0x68) of the row. The row's `atk_phys/atk_mag/atk_fire/atk_thun/atk_dark` give the
attack's base damage by type. The record does not hold the final per-type damage: the core calculation 0x140685820
(called from 0x140448325) returns the total, stored at +0x228. Seven per-status buildups scaled by +0x120..+0x138 are
stored at +0x158..+0x170 (likely poison, rot, bleed, deathblight, frost, sleep, madness, the order of
`PlayerGameData::resistance_gauges`; unverified). Each captured hit is logged as
`life: damage call lost ... attack AtkParam_Npc <id> phys .. magic .. fire .. lightning .. holy ..`. Confirmed in play
6 Oct 2026: 20 hits, all resolved to AtkParam_Npc rows; physical attacks read pure physical (e.g. 4382200 phys 210) and
fire attacks pure fire (4382140 fire 180, 4500232 fire 360), one enemy (actor 131) using both. Magic, lightning and
holy not yet seen but come from the same row fields.

Ward enchantments (built 6 Oct 2026, not yet seen in play; `WardRules`, `Wards`, `AnvilMenuMixin`, data
`enchantment/*_ward.json`): eight armour enchantments, levels I-VII at 10% each. Glintstone/Flame/Storm/Sacred Ward cut
their element's share of an ER hit; Rot/Bleed/Frost/Venom Ward cut that status's buildup on the player. Only the
strongest piece of a ward counts (no stacking); one ward per piece (exclusive set `ward`). Not in the enchanting
table, trades or chests. Element path: the DLL turns each captured hit's AtkParam base values into percentages and
sends `IN_HURT_ELEMENTS` before `IN_HURT`; `SkyCombat.hurtNativePlayer` cuts the damage before vanilla armour and logs
`[physical ..% magic ..% ..., wards cut x of y]`. Status path: Java writes the wearer's status ward levels into the
vitals every tick; `native_hits` scales the record's buildup (+0x158..) for hits on the player before the HP stage
copies it, and logs `life: status buildup on you: <status> a -> b`. The status order (poison, scarlet rot, blood
loss, deathblight, frostbite, sleep, madness) is assumed: confirm from a log line after a known status hit. Wards
do not cut buildup from ground (rot lakes, poison swamps), which applies no attack. Distribution
(`tools/generate_loot.py` `WARDS`/`WARD_SEVEN`, written to `loot_rules.json` as `ward`/`ward_seven`, listed in
`docs/loot-boss-rewards.md`): 142 of 208 bosses have a ward theme (21 marked unsure in the generator). Their armour
carries it at level = tier (max VI), their book is that ward (same level; replaces the random book), and their
weapon gets the matching weapon enchantment (unthemed bosses keep the name-based one). VII is only the book of one
DLC boss per ward: Messmer (Flame), Bayle (Storm), Rellana (Glintstone), Promised Consort Radahn (Sacred), Romina
(Rot), Divine Beast Dancing Lion of Rauh (Frost), Chief Bloodfiend (Bleed); Venom Ward has no DLC boss and stops at IV.
The anvil cannot make VII from two VIs (`WardRules.anvilLevel`). Existing claimed boss rewards are not changed.

Vanilla enchantments against Elden Ring (6 Oct 2026, built, not yet seen in play). Dead ones are no longer given by
boss rewards: Fire and Blast Protection (ER fire/explosions arrive as attacks; those sets now get Protection), Aqua
Affinity. Still dead and never given: Thorns (vanilla post_attack effects only fire from an attacker's own attack
code; ER hits call `hurtServer` directly), Looting (ER kills roll chest-style loot), Bane of Arthropods, Impaling,
Punch (arrows `push`, not `knockback`), Breach, Soul Speed, Frost Walker. Made to work:
- Projectile Protection: the DLL builds the set of AtkParam ids that BulletParam rows use (`Attack::projectile`) and
  sends such hits as `HURT_PROJECTILE`; Java gives them the `mob_projectile` damage type even without a shooter.
  Spells, breath and thrown pots are bullets too, so they count as projectiles. Logged as `..., projectile`.
- Smite: `EnchantmentHelperMixin` adds 2.5 a level in `modifyDamage` when the target stand-in's family is undead
  (the generator's undead now includes deathbird, revenant, tibia mariner; this also changed those models' drops).
- Knockback: `native_damage::reaction` takes the hit's strongest MC push (`McEvent.d`; 0.4 plain, 0.5 per Knockback
  level or sprint): each 0.5 adds half the poise damage, two or more steps one reaction size. Sprint hits stagger more too.
Boss gear: each set has its own armour trim (`SETS` in the generator, `reward.trim`). Each weapon boss gives the kind
of weapon it fights with in ER (`WEAPONS` in the generator: sword, axe, spear, mace, trident, bow, crossbow, pickaxe;
maces get Density, crossbows Piercing, the rest Sharpness/Power) and a signature enchantment (`reward.signature`:
Smite for undead, Fire Aspect for fire bosses, else Sweeping Edge/Knockback/Lunge/Wind Burst/Loyalty/Infinity/
Multishot by weapon; level in `LootEquipment.signatureLevel`). Where two different bosses would still give the same
weapon, the later (by name) gets one or two extra enchantments (`reward.flair`, `FLAIRS`); the generator asserts no two
different bosses share a weapon reward (repeat fights of one boss do). Physical bosses' weapons get no element. XP: enemies and bosses drop XP orbs at the death spot (held in the air
like loot until collision there is known); a boss drops them only on its first reward (`BossRewards.Enqueued`).
The full list is `rewards.md`, written from the reward code by `RewardsSheetTest`, which only runs on request:
`ELDENCRAFT_REWARDS=1 ./gradlew cleanTest test --tests dev.eldencraft.combat.RewardsSheetTest` (in `fabric`, with the
usual JAVA_HOME/GRADLE_USER_HOME). Regenerate it after changing rewards.

Graces: nodes keep 7 m from any grace the player has rested at or arrived beside (the DLL sends the
position with `IN_GRACE_REST`, code 0 for a rest, 1 for the arrival after a load); the world file keeps a
`graces` list and nodes already there are removed when it is first noted. Known limit: Elden Ring's
effects (the grace glow, particles) write no depth, so a block farther away than such an effect but not behind
anything solid is drawn over it. Floating nodes make this
worse; the Minecraft log prints native ground vs block bottom for the first few nodes of each 30 s.

Occlusion by the game's real depth: WORKS (seen in play 6 Oct 2026). Pillars, ruins and grass hide blocks, and
geometry the game fades out near the camera (a dithered pillar) lets blocks show through in the same pattern. History and
the investigation are in `docs/depth-occlusion-handoff.md`. On by default; F3 switches back to the collision occluders
as a fallback. Files: `depth.rs`, `depth_math.rs`, shader `PSBlock`.
How it works: a MinHook on `ID3D12GraphicsCommandList::ResourceBarrier` (installed on the first frame) takes the first
full-size single-sample depth texture the game moves into the depth-write state as the scene depth (it is
`D32_FLOAT_S8X24_UINT`, format 19, at back-buffer size). It records a `CopyResource` of it into our texture (SRV
slot 65) inside the game's own command list, so the game's queue orders the copy and the depth's state is known:
once when the game makes the whole depth readable (state 0xc0), and again at the end of the game's frame (when it
moves a swap-chain back buffer to PRESENT, using the planes' states tracked from its barriers). The encoding
(standard / reversed / reversed-infinite) is learned at run time: a row of 64 values from the copy is read back and
the centre is compared with a native ray's distance straight ahead (first person, 3-100 m, with the camera's
near/far planes), logged as `depth: the game's depth is encoded as mode N`. It is reversed-Z, near 0.05, far 10000;
modes 2 and 3 both fit. Once known, the collision occluders are skipped and a block fragment more than 0.1 m (+2%)
behind the game's depth is discarded; fragments nearer than 1.5 m are never tested (the hidden player model).
`depth: blocks are now / are not hidden by the game's depth (...)` logs every change of that state. Any failure
(no barrier seen, unsupported format, no fit after 20 s) logs `depth: ...` and leaves the collision occlusion in
place; F3 off and on tries again. In the pixel shader the block's distance is `SV_Position.w` itself: D3D gives clip
w there, not OpenGL's 1/w (taking 1/w was the bug that made it look broken). The targeted-block outline and the
contact shadows use the same test (`HiddenByGame` in the shader), so a sunk block's outline stops at the ground.
Translucent effects (the grace glow, particles) write no depth, so blocks are drawn over them. Accepted limit (the
user's call, 6 Oct 2026): putting the game's VFX over blocks was considered (copy the scene colour before and after
the game's effects pass, add the difference over blocks) and not pursued.

Block lighting: the renderer copies Elden Ring's finished frame (`gpu.rs` `FrameCopy`, taken before our
pass, SRV slot 64) and the block vertex shader averages 32 of its pixels around each vertex's screen
position (`GameLight`), so blocks pick up the game's shadows, fog, weather, colour grading, lamp light and
indoor brightness. The average is scaled by 0.42 (roughly a sunlit scene), tinted at half strength, shaded
per face from the clock's sun and multiplied by Minecraft's own sky light; torch light is added. F4
switches to the clock-only model (`lighting.rs`: sun/moon and ambient from `WorldAreaTime`, dim even
ambient indoors) which is also the fallback if the copy cannot be made (unsupported back buffer format,
multisampling, or a creation error, logged as `gpu: cannot copy ...`). Averaging a picture cannot tell
a shadow from dark ground, so blocks over dark soil read a little dark; tune `look` in `draw_world`
(reference 0.42, tint 0.5, radius 12% of the picture height). The shader compiled under Wine's d3dcompiler;
the D3D12 copy path (barriers, root parameter 2, `FRAME_READ`) is unverified in the game. The 2 s
native log line `lighting:` shows the clock model.

Collision scanning has two tiers (`scan.rs`): full columns (half-block grid, wall fitting) within 3
regions, and a floor-only ring of 1 m samples out to 10 regions (about 80 m), nearest-first with
full columns always taking priority. Far columns send triangles and voxel skin but no walls, do
not feed the half-block floor lookups, and do not register renderer occluders; they are
rescanned in full when the player approaches, and retried up to 3 times if they came back
incomplete. F7 (`render::CollisionDraw`) caches deduplicated edges for ~12 m around the player and
rebuilds only when the scan changes or the player moves 3 m. Built, not yet checked in play; the
protocol version is unchanged, so only the DLL changed.

## Verification, build and deployment

Latest recorded checks: both builds succeed; 40 Java tests pass, including resource policy/floor
placement and Minecraft codec parsing for all 106 tables (105 loot + loose stone). Two standalone
Rust grace-edge tests and three boss-identity tests pass. Loot simulation covers 700,000 kills.
Custom slab models were checked against MC assets and the removal mixin target against bytecode.
In-game gathering was later confirmed by the user.

From the workspace root:

```bash
tools/build_dll.sh --offline
python3 tools/test_loot.py
rustc --test tools/test_loot_identity.rs -o /tmp/eldencraft-loot-identity-test
/tmp/eldencraft-loot-identity-test
rustc --test tools/test_grace_edges.rs -o /tmp/eldencraft-grace-test
/tmp/eldencraft-grace-test
rustc --test tools/test_lighting.rs -o /tmp/eldencraft-lighting-test
/tmp/eldencraft-lighting-test
rustc --edition 2021 --test tools/test_depth.rs -o /tmp/eldencraft-depth-test
/tmp/eldencraft-depth-test
```

For the Java build:

```bash
cd fabric
JAVA_HOME='/run/media/alex/a0da83cf-b021-4cfe-9104-edb6dd726a80/Skycraft/.install/jdk-25.0.4.1+1' GRADLE_USER_HOME='/run/media/alex/a0da83cf-b021-4cfe-9104-edb6dd726a80/Skycraft/.install/gradle' ./gradlew test jar --offline --no-daemon --no-configuration-cache
```

Minecraft 26.3, Fabric Loader 0.19.5, Fabric API 0.161.0+26.3, Java 25. The shared Gradle cache
and installed instance are outside workspace write roots and may require tool escalation.
Regenerate design data only when changing mappings: `tools/generate_loot.py`, then
`tools/generate_resources.py`; revalidate after generation.

Install `fabric/build/libs/eldencraft-0.0.1.jar` in `<instance>/mods/` only while both games are
closed. The DLL is already at me3's configured load path. Instance:
`/run/media/alex/a0da83cf-b021-4cfe-9104-edb6dd726a80/SteamLibrary/steamapps/compatdata/1245620/pfx/drive_c/users/steamuser/AppData/Local/EldenCraft/Prism/instances/EldenCraft/.minecraft`.

Launch from the root with `~/.local/bin/me3 launch -p me3/eldencraft.me3`.
Controls: F8 MC movement, F9 first person, F10 input owner, F5 MC camera, F7 collision view, F4 block light source (game picture / clock), F3 block occlusion (game depth / collision),
F6 ray probes, F11 render-history delay, R native interaction. R assumes ER Event Action is E.

Capture logs before relaunching:

- Native: `game/target/x86_64-pc-windows-msvc/release/eldencraft.log`.
- Minecraft: `<instance>/logs/latest.log`.
- me3: `~/.local/share/me3/logs/eldencraft/`.

## Next work

Gathering and the empty start are confirmed. Next: tune yields and placement from play, and check the crafting route, base/storage persistence and player-built blocks in depleted node positions.
Follow `roadmap.MD` for current priorities. The user postponed full-inventory, simultaneous-death
and multi-phase boss testing; keep those open without treating them as the immediate next task.
Villager traders are a possible next design discussion, not an already-approved implementation.
