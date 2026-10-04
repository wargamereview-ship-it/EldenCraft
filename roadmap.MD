# EldenCraft — next 10 milestones

Proposed continuation plan, 4 October 2026. This is the recommended sequence from the
current prototype, not a claim that all earlier functionality is complete.
See `Handoff.MD` for the actual local state, build instructions and latest bug evidence.

The intended experience is Minecraft movement, building, survival and equipment in
Elden Ring's world, with ER enemies/bosses awarding Minecraft items. Multiplayer and
other inherited scaffolding are outside this first local prototype plan.

## 1. Make exploration and opening attacks reliable

**Start here.** Close the current cave/wolf issues and finish the movement milestone.

- Add narrowly verified hostile model/team classification for team-48 wolves `c4070`
  and `c4071`, so they have MC targets before dealing their first hit.
- Preserve the MC view during connected cave map changes while safely holding/rebasing movement.
- Investigate poor passive detection: compare ER/MC visibility, movement noise and scripted AI.
- Recheck native body/camera alignment, terrain contact, stale-hit handling and recovery.
- Complete stationary grace -> F8, slopes/stairs, church tunnel, bushes, MC-block support,
  cave entry/deep streaming/re-entry, death and view-toggle scenarios.

**Done when:** repeated sessions allow these routes without falling, sustained snapback,
camera flips or requiring ER mode to load the cave; wolves and soldiers accept opening
hits and respond to accepted damage, with friendly NPCs protected.

## 2. Complete the core combat interaction

Move from HP changes plus AI notices to a convincing combat loop.

- Integrate appropriate ER hit reactions, stagger and stance effects through verified native paths.
- Define how MC attacks interact with ER guarding, defence and bosses without applying damage twice.
- Cover swords/axes, bow draw/projectiles, attached-arrow cleanup, shields and front/rear/AoE cases.
- Refine incoming scaling, damage attribution, overkill and statuses; document remaining limitations.
- Validate MC-heart death -> ER death/runes/grace -> MC respawn across multiple encounters.
- Improve readable enemy names, target feedback and guard/immune/rejected-hit explanations.

**Done when:** a soldier, a wolf, Tree Sentinel and a cave boss can be fought using MC
equipment; damage, blocking, reactions, aggro and death are consistent and understandable.

## 3. Replace enemy and boss drops with Minecraft items

This is an explicit user goal. Build it on authoritative native death detection.

- Map ER enemy/boss identities to configurable MC loot tables, quantities and probabilities.
- Make normal enemy and boss rewards distinct, with a small initial progression-oriented set.
- Spawn real MC item entities at defeated enemies and support pickup/inventory stacking.
- Deduplicate deaths/rewards across frame retries, loads, respawns and corpse unloading.
- Decide how existing ER loot/runes are suppressed or retained without breaking quest/boss flags.
- Persist one-time boss reward claims and keep native event progression working.

**Done when:** killing and looting enemies works, a boss awards its intended MC reward once,
and reloading/re-entering cannot duplicate rewards or prevent native progression.

## 4. Finish survival, equipment and death rules

- Define food/hunger, regeneration, armour, shield durability and consumables consistently.
- Decide inventory retention versus dropped inventory on MC death; distinguish ER rune loss.
- Handle recovery of dropped items, despawn limits and deaths during transitions.
- Verify armour/enchantments/absorption/totems and avoid accidental double mitigation.
- Create a small starting kit and communicate control/use rules in the game.

**Done when:** the player can survive, take damage, heal, consume equipment and recover
from death using a coherent set of MC rules without losing or duplicating state.

## 5. Make building and world interaction dependable

- Finish precise placement previews, face selection and support on uneven ER terrain.
- Make walking/jumping on placed blocks reliable in both movement modes and after loading.
- Define which MC blocks/items are supported first, including partial-height shapes and interactions.
- Cover breaking, dropped block items, containers and a useful subset of crafting stations.
- Keep saved player structures anchored through map transitions and physics-origin rebases.
- Treat destruction of native ER terrain as a separate feasibility decision; existing dig helpers
  do not establish that ER geometry is safely editable.

**Done when:** the player can build a small functional base, use it, leave, reload and return
with correct visuals, collision and inventories.

## 6. Finish rendering, HUD polish and performance

- Resolve remaining floaty block alignment, render-history delays and terrain occlusion errors.
- Validate textures, lighting, transparency, dropped items and first/third-person MC presentation.
- Keep redundant player bars removed; make target, shield, pickup and inventory feedback consistent.
- Check resolution/GUI scaling and native/MC HUD overlap across modes and transitions.
- Measure scan/render/shared-memory costs and place sensible budgets/limits on meshes and entities.
- Add useful diagnostic counters without exposing implementation details in normal play.

**Done when:** a dense small build and a combat encounter render clearly at the user's
normal resolution with acceptable frame pacing and no growing resource backlog.

## 7. Build a progression and balance loop

- Connect milestone-3 loot to recipes, tools, armour, consumables and meaningful upgrades.
- Balance MC damage/health against a defined ER area and a small boss sequence.
- Make loot tiers and boss rewards create reasons to explore, craft and fight.
- Define interactions with native quests/keys/progression and preserve required ER flags.
- Replace an unlimited debug kit with a configurable playable start while retaining developer tools.

**Done when:** a fresh character can complete a short Limgrave -> cave boss route, earn
resources, craft an upgrade and feel a meaningful improvement without manual intervention.

## 8. Harden saves, lifecycle handling and local setup

- Version bridge/save data; persist structures, inventory, reward claims and settings consistently.
- Back up/migrate saves and recover cleanly after either game exits or the link disconnects.
- Reject stale actor/damage/render data after reloads, deaths and reconnects.
- Provide a repeatable local install/build/deploy/launch path with actionable diagnostics.
- Document exact supported game/mod versions and detect incompatible native signatures.
- Separate developer-only tools and local caches from distributable project files.

**Done when:** the user can restart, reload and recover a session through the documented
local workflow without manual repair or state duplication.

## 9. Investigate and prototype Nether, End and portals

This is deliberately late, as requested. **Feasibility first:** decide whether these
dimensions can be presented convincingly by the existing MC renderer/collision bridge,
require an ER map context, or need another clearly explained approach.

- Explore dimension IDs/coordinate transforms, native player simulation and safe ownership of
  collisions, streaming, camera, enemies and damage while outside the ER overworld context.
- Prototype one reversible portal round trip with stable inventory, health and return coordinates.
- Then expand to Nether terrain/environment and appropriate MC entities, subject to performance.
- Prototype End portal activation and End access, including safe spawn/return/death handling.
- Add dimension progression/boss content only after the transport/rendering model proves viable.
- Preserve ER-world state while away; avoid pretending an unsupported native map can load.

**Done when:** a documented technical approach survives repeated portal/return/death/reload
cycles; at least a small Nether/End demonstration is playable, or infeasibility is documented
with concrete evidence and a viable alternative agreed with the user.

## 10. Deliver a cohesive local vertical slice

- Package the supported features into one clearly bounded experience with simple onboarding.
- Run the complete exploration -> building -> crafting -> combat -> loot -> death/recovery route.
- Exercise repeated loading, long sessions, bosses and the dimension prototype if viable.
- Fix the highest-impact regressions and record known limitations and unsupported combinations.
- Produce concise setup, controls, troubleshooting and continuation documentation.
- Tag/package a reproducible local prototype; publish only if the user requests it.

**Done when:** the user can play the defined route from a fresh setup without code changes,
and the handoff distinguishes demonstrated features from experimental ones.

## How to use this roadmap

Finish milestone 1 before broadening native hooks. Milestones 2–5 form the main gameplay
loop; presentation, balance and lifecycle work then make it repeatable. Milestone 9 is a
research gate with uncertain architecture, so do not promise full dimensions in advance.
Agree on concrete manual acceptance scenarios and record observed results for each milestone.
