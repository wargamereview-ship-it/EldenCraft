# EldenCraft — current roadmap

Updated 5 October 2026 (America/Toronto). This replaces the previous milestone history.
`Handoff.MD` describes the installed build and operational details. Mark gameplay as confirmed
only when observed; builds and automated tests have their own status.

## Confirmed in play

- MC exploration and connected cave entry, with the camera retained through connected changes.
- Opening attacks against ordinary enemies; footsteps detected by native enemy AI.
- ER interactions through R, with native interaction animations and corrected body alignment.
- MC hits delivered through the native combat path.
- Regular enemy drops and boss drops from the new loot system, after the zero-ID boss fix.
- Elevators: riding lifts up and down without falling through or hovering (user confirmed 5 October 2026).
- Empty start and the gathering system: log piles, loose stone, rocks, mine ores in the eight mapped
  interiors, and replenishment after a grace rest (user confirmed 5 October 2026).

These confirmations cover tested examples, not every enemy, region or encounter variant.

## Built and installed

The current matching DLL/JAR use protocol version 3.

- Empty starting inventory; starter, builder and test-guest kits removed.
- Seven loot tiers: base game T1–T5 and Shadow of the Erdtree T6–T7.
- Guaranteed family materials; 10% eligible worn equipment, including the capped upgrade roll.
- 208 mapped boss encounters with named enchanted gear, books and materials.
- 17 collections / 68 fixed boss pieces; persistent pending boss rewards and player receipts.
- Visible log piles, bare-handed loose stone and pickaxe deposits with 10% copper/iron bonuses.
- Visible regional mine ores in eight explicitly mapped interiors; valuable gathering materials
  restricted to mines.
- Persistent resource depletion and replenishment from native grace-rest transitions.
- Native digging explicitly disabled at the user's request.

Latest checks pass: DLL/JAR builds, 40 Java tests, five standalone Rust identity/rest tests,
Minecraft parsing of 106 loot tables, and 700,000 simulated kills. Existing worlds receive
gathering nodes without a reset.

## Immediate next: tune gathering and verify crafting

Gathering and the empty start are confirmed. Remaining from the checklist below: the crafting
route, tuning, and the player-built-block check. Do not erase saves to test.

1. Find visible log piles and low mossy loose-stone slabs outdoors; collect both bare-handed.
2. Craft a workbench and wooden pickaxe, then harvest larger stone and make a furnace/stone tools.
3. Check that ordinary rocks supply stone and occasional copper/iron, without valuable ores.
4. Visit a supported mine, verify visible ore and correct tool requirements, then smelt materials.
5. Harvest a deposit, travel/reload and verify it stays depleted; rest at a grace and verify it returns.
6. Put a player-built block in a depleted node position and verify replenishment leaves it alone.

Adjust visibility, density, placement, yields and replenishment from results. Check crafting,
furnaces and storage on native terrain, including persistence after leaving and returning.

**Complete when:** an empty character can gather, craft a meaningful equipment upgrade, and
repeat the resource loop without a world reset or loss of player-built blocks.

## Following priorities

### 1. Crafting and building progression

Balance the material demand of early tools, armour, repair and enchanting against drops/nodes.
Add ER-themed recipes where they improve progression. Verify smithing, uneven-ground placement,
standing on MC blocks, and storage/furnace persistence through loads and rebases.
Shared-map mines need precise zone definitions rather than enabling ore across protected locations.

**Complete when:** a useful small base and its inventories survive travel, and regional resources
produce a clear crafting progression.

### 2. Survival and combat balance

Review hunger, food and healing against ER damage. Current death rules keep inventory and respawn
immediately; design changes need a user decision. Tune weapon reactions and poise, including spears,
mace and mace smash. Check ranged/crowded hits, carrier visibility and guarded-hit feedback.

**Complete when:** gathering, food, equipment and fighting support a coherent survival loop.

### 3. Villager traders — scope with the user first

The user asked whether selected ER NPCs could become custom villager traders. Start by discussing
which merchants, their inventories and payment materials. Prefer preserving native quest/dialogue
logic behind a custom trader presentation. No trader implementation has been authorized yet.

**Complete when:** a chosen trader offers useful, tier-appropriate trades without breaking native
NPC behavior or progression.

### 4. Loot refinement and collections

Tune regional rewards from play and inspect the generated boss reward sheet. Verify collection
pieces across different encounters, improve signature reward variety, then design collection bonuses.
Looting support, rarity/pickup feedback and custom visuals remain follow-ups.

**Complete when:** the regional upgrade curve and boss collections are useful and understandable
across a longer route, including the DLC.

### 5. Interaction, stability and lifecycle

Improve native prompt visibility, readable enemy names and interaction-key configuration. Check
ladders, lifts and less common interactions. Strengthen recovery after server/game stalls and
restarts; add a build/install helper that checks processes and matching protocol versions.

**Complete when:** ordinary play and relaunching do not require manual repair.

## Deferred verification — user chose to test later

- Boss rewards with a full inventory and subsequent space becoming available.
- Simultaneous boss/player death, delivery after respawn and restart during partial delivery.
- Duo, multi-phase, field and surrender encounters; no duplicate or missing completion rewards.
- Regional boundaries, shared underground maps, later tiers and NG+ behavior.
- Fresh-start and death-transition edge cases beyond the examples already played.

Keep these open. A regular boss payout does not confirm all encounter/recovery paths.

## Longer-term work

- ER elemental/status enchantments: native Magic/Fire/Lightning/Holy and appropriate buildup.
  Implement actual native effects before adding their books or claims to named gear.
- Seamless co-op: hook compatibility, per-player native hits, guest loot/gathering and coherent
  death/respawn rules. Current resource and loot routing is host-only.
- Nether/End feasibility, then a polished route: empty start -> gathering -> crafted upgrade ->
  early boss -> regional progression.

Native terrain excavation is out of scope unless the user explicitly requests it again.
