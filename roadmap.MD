# EldenCraft — gameplay roadmap

Updated 6 October 2026 (America/Toronto). This roadmap lists gameplay only; rendering, collision,
stability and tooling live in `Handoff.MD` and are done when a feature needs them. Mark something
confirmed only once it has been seen in play.

## Confirmed in play

- Exploring Elden Ring's world as Minecraft, including connected caves and riding lifts.
- Minecraft blocks hidden behind Elden Ring's own scenery (pillars, ruins, grass).
- Attacking ordinary enemies, with native staggers and reactions; enemies hearing your footsteps.
- Elden Ring interactions through R: doors, levers, pickups, graces.
- Regular enemy drops and boss drops (before the 6 October reward redesign).
- Starting with an empty inventory.
- Gathering: log piles, loose stone, rocks and mine ores, depleting and coming back after a grace rest.

These cover tested examples, not every enemy, region or encounter.

## In the game now

- **Loot:** seven regional tiers (base game T1-T5, Shadow of the Erdtree T6-T7). Every ordinary kill
  drops materials by enemy family; 10% of kills with worn equipment also drop gear. Enemies and bosses
  drop **XP orbs** where they die.
- **Boss rewards** (full list: `rewards.md`): every boss gives a named item, an enchanted book and
  materials, and no two different bosses give the same item.
  - 17 four-piece armour sets, each with its own armour trim.
  - Weapons of the kind the boss fights with in Elden Ring (swords, axes, spears, maces, tridents,
    bows, crossbows), carrying the boss's element or status and a signature enchantment.
  - Rewards wait safely if your inventory is full or you are dead.
- **Wards** (armour enchantments, I-VII, 10% a level): Glintstone, Flame, Storm and Sacred Ward cut that
  element's damage from Elden Ring hits; Rot, Bleed, Frost and Venom Ward slow that status building up
  on you. Themed bosses put their ward on their armour and give it as their book; VII is a book from
  one Shadow of the Erdtree boss per ward and cannot be made on the anvil.
- **Weapon elements and statuses:** Glintstone, Flame, Lightning and Sacred add damage, scaled by enemy
  kind; Bloodletting, Frostbite, Venom and Scarlet Rot build up on enemies like Elden Ring's.
- **Vanilla enchantments that now meet Elden Ring:** Projectile Protection (arrows, spells, thrown
  objects), Smite (Elden Ring's undead) and Knockback (adds stagger). Enchantments that do nothing in
  Elden Ring are no longer given as rewards.
- **Gathering:** log piles, bare-handed loose stone, rocks that sometimes hold copper or iron, and
  regional ore in eight mines. Nodes come back after a grace rest.
- **Player statuses:** poison, scarlet rot, blood loss, deathblight, frostbite, sleep and madness are
  mirrored as Minecraft effects with icons and native durations. A left-side HUD shows buildup
  percentages and active timers; native cures and inactive/loading/death states clear the mirror.
  This is built, not yet seen in play.
- Native terrain digging is off, by request.

## Next: play-check the 6 October build

Built but not yet seen in play. The logs record each check.

1. Wear Flame Ward (`/enchant @s eldencraft:flame_ward 7` on a held chestplate) and take a fire hit:
   about 70% less of its fire damage.
2. Wear Rot Ward and take a rot hit; the native log names the status. This also confirms which
   buildup is which (the order is still an assumption).
3. Projectile Protection against an arrow or spell; Smite against a skeleton or Death Rite Bird; Knockback
   making enemies stagger more.
4. Kill an enemy and a boss: XP orbs where they die, Mending repairing from them.
5. A boss reward: the right trim on a set piece, the right weapon type and enchantments (`rewards.md`).
6. Two ward VI books on the anvil must not make a VII.
7. The new player status mirror: take known status hits and check buildup, matching effect/icon and
   native duration. Check a native cure/grace rest, travel/loading, death/respawn and disconnect clear
   the display. Compare warded attack buildup. Blood loss, deathblight and madness may appear briefly.

**Complete when:** each item behaves as described, or what differs is understood and fixed.

## Built: Elden Ring statuses on you, shown in Minecraft (requested 6 October)

Poison, scarlet rot, blood loss, frostbite, deathblight, sleep and madness on your character become
Minecraft status effects with their icons and timers, read from Elden Ring's own status bars. Built
6 October, not yet seen in play. The HUD also shows buildup so wards can be compared. Their damage
already reaches your hearts; these effects add no extra damage or penalties. Decide with you what
else each should do in Minecraft (for example any additional frostbite behaviour).

**Complete when:** every Elden Ring status you suffer shows as the matching Minecraft effect for as long
as it lasts, and the wards visibly slow it.

## Then: finish the survival loop from an empty start

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
Add Elden Ring-themed recipes where they help. Check smithing, building on uneven ground and standing
on placed blocks. Mines that share a map with protected areas (such as Sealed Tunnel) need exact zones
before they hold ore.

**Complete when:** a small base survives travel, and each region's resources lead to a clear
crafting progression.

### 2. Survival and combat balance

Review hunger, food and healing against Elden Ring's damage. Death currently keeps your inventory and
respawns you at once; changing that is your decision. Tune weapon feel, poise and the new knockback
stagger, including spears, maces, tridents and ranged and crowded fights.

**Complete when:** gathering, food, equipment and fighting form one coherent survival loop.

### 3. Loot and collections

Tune regional rewards from play and review `rewards.md`: the 21 boss themes marked unsure, whether
each set comes from the intended fights, and the Venom Ward ceiling (no Shadow of the Erdtree poison
boss, so it stops at IV). Then design set bonuses.

**Complete when:** the upgrade curve and boss collections feel clear and rewarding over a long route,
including the DLC.

### 4. Villager traders — scope with you first

Replace or overlay chosen Elden Ring merchants with Minecraft villager trades. Decide which merchants,
what they sell and what pays for it; keep their native quests and dialogue. Nothing is authorized yet.

**Complete when:** a chosen trader offers useful, tier-appropriate trades without breaking its native
behaviour.

### 5. Elden Ring interactions

Check ladders and the less common interactions (lift controls, chests, bonfire-style actions) and make
anything that fails work under Minecraft control.

**Complete when:** every common way of moving through and using the world works as a Minecraft player.

## Open gameplay checks — you chose to test these later

- Boss rewards with a full inventory, and then space becoming available.
- A boss and the player dying together; rewards arriving after respawn.
- Duo, multi-phase, field and surrender bosses: exactly one reward each.
- Region boundaries, shared underground maps, later tiers and NG+.
- Death and respawn cases beyond the ones already played.
- Gathering nodes that look like they float (they follow Elden Ring's collision floor).

A normal boss payout does not confirm these.

## Decided against, for now

- **Elden Ring effects over blocks:** the grace glow and particles are drawn under Minecraft blocks.
  Layering them on top was considered and declined.
- **Native terrain digging:** out of scope unless you ask for it again.

## Longer term

- **Native elemental and status effects:** weapon elements and statuses work through Minecraft damage;
  making them Elden Ring's own damage types, buildup and visuals is optional once these feel right.
- **Seamless co-op:** per-player hits, guest loot and gathering, shared death and respawn rules. Loot
  and gathering currently go to the host only.
- **Nether and End:** decide whether they fit.
