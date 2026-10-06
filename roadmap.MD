# EldenCraft — gameplay roadmap

Updated 5 October 2026 (America/Toronto). This roadmap lists gameplay features only. Rendering,
collision, stability, tooling and other technical work are tracked in `Handoff.MD` and are done
when a feature needs them. Mark gameplay as confirmed only when observed in play.

## Confirmed in play

- Exploring Elden Ring's world as Minecraft, including entering connected caves.
- Attacking ordinary enemies; enemies noticing the player's footsteps.
- Elden Ring interactions through R: doors, levers, pickups, graces.
- Minecraft hits landing on Elden Ring enemies with native staggers and reactions.
- Regular enemy drops and boss drops from the new loot system.
- Riding lifts up and down.
- Starting with an empty inventory.
- Gathering: log piles, loose stone, rocks and mine ores, depleting and replenishing after a rest at a grace.

These confirmations cover tested examples, not every enemy, region or encounter.

## In the game now

- **Loot:** seven regional tiers (base game T1–T5, Shadow of the Erdtree T6–T7). Every ordinary kill
  drops materials by enemy family; 10% of kills with worn equipment also drop gear, with a small
  chance of a tier higher. Bosses give fixed named enchanted gear, a curated book, materials and XP.
- **Boss collections:** 17 four-piece sets (68 pieces) spread over fixed boss encounters across the
  208 mapped fights. Rewards wait safely if the inventory is full or the player is dead.
- **Gathering:** visible log piles, bare-handed loose stone, rocks that sometimes hold copper or iron,
  and regional ore in eight mapped mines. Nodes come back after a grace rest.
- **Elemental enchantments:** four elements (Glintstone, Flame, Lightning, Sacred) add damage, scaled by
  enemy kind. Four statuses (Bloodletting, Frostbite, Venom, Scarlet Rot) build up on enemies like Elden
  Ring's. Boss weapons carry one, and about a third of boss books hold one. Not yet played.
- Native terrain digging is off, by request.

Not yet played on the current build: node placement and density after the latest changes, the
elemental enchantments, and the crafting route below.

## Next: finish the survival loop from an empty start

Test on a fresh world.

1. Collect logs and loose stone bare-handed.
2. Craft a workbench and wooden pickaxe, then mine larger stone and make a furnace and stone tools.
3. Mine copper and iron, smelt them, and craft a first meaningful equipment upgrade.
4. Put a player-built block where a node was and check replenishment leaves it alone.
5. Leave and return: furnaces, chests and the base should still be there.

Tune node density, yields and what each region offers from what you find.

**Complete when:** an empty character can gather, craft a meaningful upgrade and repeat the loop
without a world reset or losing built blocks.

## Priorities after that

### 1. Crafting and building progression

Balance what early tools, armour, repair and enchanting cost against what drops and nodes supply.
Add Elden Ring-themed recipes where they improve progression. Check smithing, building on uneven
ground and standing on placed blocks. Mines that share a map with protected areas (such as Sealed
Tunnel) need exact zones before they hold ore.

**Complete when:** a small base survives travel, and each region's resources lead to a clear
crafting progression.

### 2. Survival and combat balance

Review hunger, food and healing against Elden Ring's damage. Death currently keeps your inventory
and respawns you at once; changing that is a design decision for you. Tune weapon feel and poise,
including spears, mace and mace smash, plus ranged and crowded fights.

**Complete when:** gathering, food, equipment and fighting form one coherent survival loop.

### 3. Villager traders — scope with you first

Replace or overlay chosen Elden Ring merchants with Minecraft villager trades. Start by deciding
which merchants, what they sell, and which materials pay for it. Keep the native quest and dialogue
behaviour behind the trader presentation. Nothing is authorized yet.

**Complete when:** a chosen trader offers useful, tier-appropriate trades without breaking its
native behaviour or progression.

### 4. Loot and collections

Tune regional rewards from play and review the boss reward sheet (`docs/loot-boss-rewards.md`).
Check that collection pieces come from the intended encounters, add variety to signature rewards,
then design set bonuses. Looting enchantment support and rarity cues are follow-ups.

**Complete when:** the upgrade curve and boss collections are clear and rewarding over a long
route, including the DLC.

### 5. Elden Ring interactions

Check ladders and the less common interactions Elden Ring offers, such as lift controls, chests and
bonfire-style actions, and make anything that fails work under Minecraft control.

**Complete when:** every common way of moving through and using the world works as a Minecraft player.

## Open gameplay checks — you chose to test these later

- Boss rewards with a full inventory, and then space becoming available.
- A boss and the player dying together; rewards arriving after respawn.
- Duo, multi-phase, field and surrender bosses: exactly one reward each.
- Region boundaries, shared underground maps, later tiers and NG+.
- Death and respawn cases beyond the ones already played.

A normal boss payout does not confirm these.

## Longer term

- **Native elemental and status effects:** the enchantments work through Minecraft damage. Making them
  true Elden Ring effects (its own damage types and resistances, real status buildup, its visuals)
  would need game-data work and is optional once the current ones feel right.
- **Seamless co-op:** per-player hits, guest loot and gathering, and shared death and respawn rules.
  Loot and gathering currently go to the host only.
- **Nether and End:** decide whether they fit, then polish the route from empty start through
  gathering, a crafted upgrade and an early boss to regional progression.

Native terrain digging stays out of scope unless you ask for it again.
