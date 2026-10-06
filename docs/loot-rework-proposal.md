# EldenCraft loot rework proposal

Approved design, implemented 4 October 2026. These are EldenCraft rewards, not Elden Ring's original drops.
The exhaustive generated [reward sheet](loot-boss-rewards.md) is the authoritative current item
assignment. Earlier signature examples below describe themes; runtime rewards currently use the
named vanilla weapon/armour assignments in that sheet. Native gameplay verification is pending.

## Agreed decisions

- Fresh Minecraft characters start with nothing; no generous testing preset.
- Gathering/crafting and enemy equipment drops both provide progression.
- Enemy family determines drop types; region determines tier. HP adjusts quantities within a tier.
- Every eligible ordinary enemy kill drops at least one relevant item.
- Equipment-capable ordinary enemies: 9% regional-tier gear, 1% one-tier-higher gear, 90% no gear.
- Ordinary gear has 30–70% durability remaining; boss gear has full durability.
- Each boss encounter guarantees a fixed named enchanted gear reward, a tier-appropriate enchanted
  book, and materials. Bonus supplies are separate.
- Sets are collected across multiple bosses. Set-completion bonuses are deferred.
- Cover the entire base game and Shadow of the Erdtree in the initial rework.
- Base game tiers 1–5; DLC tiers 6–7.

## Regional defaults

Interior caves, tunnels, catacombs and evergaols inherit their surrounding region unless explicitly
listed below. Legacy dungeons have explicit mappings. Access routes do not determine reward tier.

| Tier | Regions and interiors | Normal equipment |
|---|---|---|
| 1 | Limgrave, Stormhill, Weeping Peninsula, Chapel of Anticipation, tutorial, early local dungeons | Leather armour; copper/stone weapons and tools |
| 2 | Stormveil Castle; ordinary Liurnia; Raya Lucaria; Caria Manor; Siofra River below the well; lower Ainsel River | Iron and chainmail; low enchantments |
| 3 | Southern/central Caelid, Sellia, Redmane Castle; Nokron, upper Ainsel River/Nokstella; Altus Plateau, Shaded Castle, Capital Outskirts | Enchanted iron; occasional diamond equipment |
| 4 | Dragonbarrow; Mt. Gelmir and Volcano Manor; Leyndell Royal Capital; Subterranean Shunning-Grounds; Deeproot Depths; Lake of Rot/Grand Cloister; Moonlight Altar | Diamond |
| 5 | Mountaintops of the Giants, Castle Sol, Consecrated Snowfield, Hidden Path to the Haligtree, Haligtree/Elphael; Mohgwyn Palace; Farum Azula; Ashen Capital and final arena | Netherite; best base-game enchantments |
| 6 | Gravesite Plain, Belurat, Castle Ensis; Scadu Altus outside Shadow Keep; Rauh Base; Cerulean Coast; Charo's Hidden Grave; Stone Coffin Fissure; Finger Ruins of Rhia/Dheo; Dragon's Pit and lower Jagged Peak | Curated enchanted netherite |
| 7 | Shadow Keep/Specimen Storehouse; Scaduview/Hinterland; Ancient Ruins of Rauh; Abyssal Woods/Midra's Manse; upper Jagged Peak/Bayle arena; Enir-Ilim | Best specialised named netherite equipment |

Exceptions: Margit T2, Godrick T2, Tree Sentinel T2, Crucible Knight (Stormhill) T2,
Radahn T4, Astel (Naturalborn) T4, Regal Ancestor Spirit T3. These bosses receive the listed tier,
even where their entrance or surrounding overworld has a different default.
Lower/upper underground areas must use separate map/position rules, not just a river name.

The ordinary 1% upgrade stays inside its expansion: T5 does not roll T6, and T7 stays T7.
At these caps, the upgrade roll instead awards a better enchantment package at the same tier.
Rare T3 diamond drops count as that tier's upper-quality equipment; T3's 1% upgrade uses the full
T4 package. Difficulty/NG+ HP scaling does not promote an early region into a late tier.

## Ordinary enemy rewards

| Family | Guaranteed relevant pool | Separate supply opportunities | Gear eligibility |
|---|---|---|---|
| Beasts, wolves, bears | Leather, bones, appropriate meat | Extra food/hides | None |
| Soldiers, knights, hostile humanoid fighters | Metal nuggets/ingots, cloth/string | Food, arrows, repair materials | Weapons/armour suited to the actor |
| Archers/crossbowmen | Ammunition or string | Feathers, flint, food | Bow/crossbow; suitable light armour |
| Miners/stone creatures | Stone, coal, region-allowed minerals | Smelting fuel | Miners may drop picks; stone creatures do not |
| Sorcerers, magical constructs | Lapis, paper/book materials, amethyst | Tier-appropriate books | Suitable equipment; no pretend functional staff |
| Undead/catacomb creatures | Bones, cloth/string | Arrows, enchanting supplies | Armed humanoids only |
| Plants/fungi/rot creatures | Plant materials, mushrooms, appropriate organic ingredients | Food or brewing ingredients | None |
| Dragons, large special beasts | Larger relevant material stacks | Rare regional materials | Ordinary beasts do not drop humanoid equipment |
| Unmapped hostile enemies | Conservative guaranteed regional material | Small supply pool | No gear until family is classified |

Initial guaranteed stack targets by tier: 1–2, 1–3, 2–4, 2–5, 3–6, 3–7, 4–8.
These are ordinary bulk-material counts, not diamond/netherite counts. HP can scale within these
ranges. Rare materials use separate restricted pools. Cooked food mainly comes from humanoid
supplies; beast meat is raw. Equipment and books should not crowd out guaranteed materials.
Use only registered items and enchantments in the installed Minecraft version.

## Boss armour collections

Each row is a four-piece collection. The four columns identify the guaranteed piece for each
encounter. A duo or multi-phase fight counts as one encounter, with one book and one gear reward.
All pieces in a row use the same material tier. Names are original EldenCraft collection names.
The specific encounter/location is part of identity; repeated boss models are not interchangeable.

| Tier / collection | Helmet boss | Chestplate boss | Leggings boss | Boots boss | Enchantment theme |
|---|---|---|---|---|---|
| 1 — Wayfarer's Hide | Beastman, Groveside Cave | Demi-Human Chiefs, Coastal Cave | Burial Watchdog, Stormfoot Catacombs | Guardian Golem, Highroad Cave | General defence, durable boots |
| 1 — Morne Wanderer | Cemetery Shade, Tombsward Catacombs | Leonine Misbegotten | Scaly Misbegotten, Morne Tunnel | Ancient Hero of Zamor, Weeping Evergaol | Projectile defence, fall protection |
| 2 — Stormgate Vanguard | Margit | Godrick | Crucible Knight, Stormhill Evergaol | Tree Sentinel, Limgrave | General defence and durability |
| 2 — Carian Lakeguard | Red Wolf of Radagon | Rennala | Royal Knight Loretta | Crystalian, Raya Lucaria Crystal Tunnel | Projectile defence, water mobility |
| 3 — Redmane Exile | Battlemage Hugues | Commander O'Neil | Cleanrot Knights, Abandoned Cave | Magma Wyrm, Gael Tunnel | Projectile/fire defence |
| 3 — Eternal Pathfinder | Mimic Tear | Regal Ancestor Spirit | Valiant Gargoyles | Dragonkin Soldier of Nokstella | Exploration and durability |
| 3 — Amber Roadwarden | Demi-Human Queen Gilika | Elemer of the Briar | Ancient Hero of Zamor, Sainted Hero's Grave | Fallingstar Beast, Altus Plateau | General/blast defence |
| 4 — Gilded Omenward | Godfrey, Golden Shade | Morgott | Mohg, the Omen | Draconic Tree Sentinel, Capital Outskirts | Durable general defence |
| 4 — Cinder Pilgrim | Demi-Human Queen Maggie | Rykard | Godskin Noble, Volcano Manor | Full-Grown Fallingstar Beast | Fire/blast defence |
| 4 — Duskbound Seeker | Lichdragon Fortissax | Astel, Naturalborn of the Void | Alecto | Glintstone Dragon Adula, final Moonlight Altar encounter | Projectile/blast defence, water mobility |
| 5 — Winterbound Sentinel | Commander Niall | Fire Giant | Loretta, Knight of the Haligtree | Great Wyrm Theodorix | General/fire defence, fall protection |
| 5 — Bloodroot Sovereign | Mohg, Lord of Blood | Malenia | Putrid Avatar boss encounter, Consecrated Snowfield | Putrid Grave Warden Duelist, Consecrated Snowfield Catacombs | Durable general defence |
| 5 — Last Age Champion | Godskin Duo | Maliketh | Dragonlord Placidusax | Radagon/Elden Beast encounter | Strong general/blast defence |
| 6 — Nameless Oath | Blackgaol Knight | Rellana | Demi-Human Swordmaster Onze | Chief Bloodfiend | Durable general defence |
| 6 — Veiled Coastkeeper | Demi-Human Queen Marigga | Putrescent Knight | Dancer of Ranah | Death Rite Bird, Charo's Hidden Grave | Water mobility, projectile defence |
| 7 — Ashen Crucible | Commander Gaius | Messmer | Romina | Scadutree Avatar | Fire/blast defence, durability |
| 7 — Eclipse Sovereign | Midra | Promised Consort Radahn | Metyr | Bayle | Best specialised defence and utility |

Exceptions implied by these collections: Valiant Gargoyles T3; Draconic Tree Sentinel at Capital
Outskirts T4; Putrescent Knight T6; Metyr T7; Dancing Lion at Belurat T6; Dancing Lion at Rauh T7.
The final Radagon/Elden Beast pair must pay only after Elden Beast is defeated.
Boss encounter classification must distinguish the Snowfield Putrid Avatar from ordinary respawning
avatars elsewhere. Set locations are deliberate hunting routes, not claims about original ER sets.

## Other bosses: complete coverage without duplicate collection pieces

Every remaining confirmed boss receives one fixed signature gear item plus its book/materials.
Use a named per-encounter override where known; otherwise a deterministic family-and-tier default.
Do not assign extra bosses replacement pieces from the collections above. This preserves each hunt.

| Boss family | Default signature reward | Useful theme |
|---|---|---|
| Dragons/magma wyrms | Named axe or chestplate | Damage/durability or fire defence |
| Knights/cavalry/duelists | Named sword, axe, spear or shield | Damage/durability; actor-appropriate class |
| Assassins/shades | Named sword or boots | Damage or mobility |
| Watchdogs/golems/crystalians | Named pickaxe, axe or chestplate | Mining utility or defence |
| Avatars/tree spirits | Named chestplate or axe | Defence/durability |
| Beast/demi-human bosses | Named axe or boots | Damage or fall protection |
| Magical/gravity bosses | Named bow or helmet | Ranged damage or utility |
| Mariners/deathbirds | Named sword or helmet | Useful damage or projectile defence |
| Rot/plant bosses | Named boots or leggings | Defence/durability; no unsupported rot immunity |
| Unclassified confirmed boss | Named regional weapon | Reliable damage/durability |

Specific signature examples: Agheel — Emberfang Axe (T1); Dragonkin Soldier, Siofra — Riverwarden
Spear (T2); Lansseax — Stormcrest Bow (T3); Black Blade Kindred, Dragonbarrow — Blackstone Cleaver
(T4); Hoarah Loux — Earthshaker Axe (T5); Belurat Dancing Lion — Stormdance Boots (T6);
Fog Rift Death Knight — Gravebolt Axe (T6); Rakshasa — Red Harvest Sword (T6);
Rauh Dancing Lion — Tempest Crown (T7); Ancient Dragon Senessax — Stormscar Spear (T7).
Verify spear item availability and compatible enchantments before selecting its actual base item.

Surrender/friendly transitions (Patches, Rennala) require encounter-completion handling rather than
an actual NPC death. No reward should encourage murdering a surrendered or friendly NPC.
NPC invasions may receive signature rewards when confirmed hostile encounters; never use a broad
"named NPC" filter. Summons, mounts, adds and phase bodies must not each pay a boss reward.

## Enchantment progression

All guaranteed boss equipment receives a useful primary enchantment and Unbreaking, plus a
slot-appropriate utility where the tier allows. Select compatible combinations; never use conflicting
protection types, Silk Touch/Fortune or Infinity/Mending together.

| Tier | Armour primary | Weapon primary | Unbreaking | Book target |
|---|---|---|---|---|
| 1 | Protection I or specialist II | Sharpness I / Power I | I | Useful level I–II |
| 2 | Protection II or specialist III | Sharpness II / Power II | I–II | Useful level II |
| 3 | Protection III or specialist IV | Sharpness III / Power III | II | Useful level II–III |
| 4 | Protection III–IV or specialist IV | Sharpness IV / Power IV | III | Useful level III–IV |
| 5 | Protection IV or specialist IV | Sharpness V / Power V | III | Strong book, occasional Mending |
| 6 | Protection V or specialist V plus utilities | Sharpness/Power/Efficiency VI | III | Strong curated book; Mending opportunity |
| 7 | Protection VI or specialist VI plus utilities | Sharpness/Power/Efficiency VII | III | Best curated books; Mending opportunity |

Example T2 Stormgate Vanguard Boots: iron boots, full durability, Protection II, Unbreaking I,
Feather Falling II. Example T7 Eclipse Sovereign Boots: netherite boots, full durability,
Protection VI, Unbreaking III, Feather Falling IV, Depth Strider III, Mending.
Other armour pieces have appropriately different utilities; do not enchant helmets with boot effects.

The approved T6/T7 implementation exceeds vanilla primary caps modestly to keep DLC upgrades
meaningful. It uses compatible vanilla effects; ER elemental/status effects remain a follow-up.

Boss books use curated tier pools, not the unrestricted random-enchantment tag. Exclude curses
and enchantments with no relevant use. Elemental/status books enter these pools only when their
native combat effects work; do not advertise unsupported magic, bleed or rot effects on named gear.
Custom names/lore and existing equipment are the first implementation; unique models are separate.

## Materials, crafting and XP

- Guarantee ordinary bulk supplies; never put diamonds/netherite in every family's common pool.
- Region-gate generated mining ores as well as loot: early-region mining currently permits diamonds.
  Preserve an empty-handed route to wood, workbench, wooden pickaxe, stone tools and furnace.
- T1 supplies leather/copper/stone; T2 iron; T3 more iron and scarce diamonds; T4 diamonds and
  restricted scrap; T5 scrap/ingots and upgrade templates; DLC adds repair, enchanting and building
  supplies alongside netherite. Keep the gold requirement for netherite crafting supplied too.
- Bosses guarantee enough relevant material to visibly advance a craft; named equipment need not
  be craftable initially. Vanilla crafting/repair remain useful alongside collected sets.
- Initial ordinary XP targets: 2/4/8/16/30/45/60. Boss XP targets: 60/120/220/350/500/700/1000.
  Explicit encounter weights adjust trivial or unusually difficult fights. These are starting values.
- Keep totems and enchanted golden apples out of routine farmable pools initially; rare boss bonuses.
- Retain ER's original rewards unchanged. Ordinary loot is renewable; boss rewards are one-time.

## Implementation and verification

1. Export an actual loot-region identifier (raw map block/play region plus position where needed).
   Current `world_id` collapses all tiles in each overworld and cannot identify Limgrave versus Caelid.
2. Extract/verify map, NpcParam and encounter identifiers against installed game data. Names alone
   are not implementation keys. Keep the design mapping separate from verified runtime identifiers.
3. Implement regional defaults, enemy families and explicit encounter overrides in data files.
4. Generate/check all seven tiers, named collections, signature rewards, book pools and materials.
5. Treat multi-actor/multi-phase encounters as one completion. Persist pending reward bundles before
   delivery, then claim them; deliver after respawn if the player dies simultaneously.
6. Validate region boundaries, dungeon inheritance, field bosses, no missing rewards, gear-family
   restrictions, tier caps, wear bounds, enchantment compatibility and exactly-once boss claims.
7. Simulate many rewards per family/tier and report equipment rates, upgrade rates, material yield,
   expected XP, duplicate set assignments and boss guarantees. Simulation does not prove native
   IDs or encounter completion work in game; logs and representative gameplay checks still matter.

Implemented coverage: 208 catalogued encounters, 446 map defaults, 372 model families, 17 sets
and 105 tables. Automated checks and simulation pass. Source identifiers are public-data mappings;
live native completion, boundary behavior and exactly-once recovery still need gameplay checks.
Boss parcels are delivered to inventory, held when full, and retried after respawn.

## Reference material

Boss names/locations checked against public catalogs; tier assignments, collections, loot rates and
enchantments above are original design proposals. Catalogs can contain errors; installed game data
is required to verify implementation identifiers.

- [Base-game boss checklist](https://game8.co/games/Elden-Ring/archives/352362)
- [Eldenpedia boss catalog](https://eldenring.wiki.gg/wiki/Bosses)
- [PowerPyx DLC boss guide](https://www.powerpyx.com/elden-ring-shadow-of-the-erdtree-dlc-boss-guide-all-bosses/)
