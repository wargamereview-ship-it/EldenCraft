# Elden Ring items port

Branch: `ER-Items-port-proto`. Goal: a 1:1 port of Elden Ring's weapons, armour, talismans and goods into Minecraft.

## Decisions

| Question | Choice |
| --- | --- |
| Stats | Real Elden Ring attributes (Vigor, Mind, Endurance, Strength, Dexterity, Intelligence, Faith, Arcane), levelled at graces with experience. Weapons use real requirements and scaling; armour uses real absorption, weight and poise. |
| Art | Generated Minecraft-style pixel art, no game assets. |
| Goods | All of them: consumables and materials, spells, Ashes of War and spirit ashes, key items and lore. |
| Obtaining | Mirror Elden Ring: whatever the hidden character receives also appears as the Minecraft item. The earlier custom loot is replaced: enemies and bosses drop only Elden Ring's own loot (through the mirror), and villager merchants sell their Elden Ring stock. |

## Data

`tools/generate_items.py` reads the game's own `regulation.bin` (through `tools/er_params.py`, with Paramdex's field layouts and row names cached in `tools/.paramdex`) and writes `fabric/src/main/resources/data/eldencraft/er/`:

| File | Contents |
| --- | --- |
| `weapons.json` | 3,404 weapons with every infusion: base damage, scaling, requirements, correction graphs, status effects, guard, weight, class |
| `armour.json` | 727 pieces: the eight negations, the seven resistances, poise, weight, special effects |
| `talismans.json` | 155 talismans and their SpEffects |
| `goods.json` | 2,181 goods: kind, effect, stack size; throwables carry their attack |
| `spells.json` | 217 sorceries and incantations: FP, slots, requirements, attack (bullet → AtkParam) and effects |
| `ashes.json`, `arts.json` | Ashes of War and their skills |
| `reinforce.json`, `graphs.json`, `element_correct.json`, `materials.json` | Upgrade rates, scaling curves, which attributes scale which element, smithing stone costs |
| `speffects.json` | Every SpEffect an item refers to |
| `classes.json` | The ten starting classes |

Regenerate after a game patch: `python3 tools/generate_items.py` (then `python3 tools/generate_item_art.py` if new weapon classes or goods kinds appear).

## Done

- **Items** (`dev.eldencraft.items`): one registered item per behaviour (`er_weapon`, `er_bow`, `er_crossbow`, `er_ammo`, `er_shield`, `er_catalyst`, `er_armour`, `er_talisman`, `er_goods`, `er_ash`). Each stack carries an `eldencraft:er_item` component (category, row, level), and name, art, stack size, slot and blocking follow from the row. Five creative tabs list everything.
- **Art** (`tools/generate_item_art.py`): 44 weapon classes, 12 armour icons (four slots × light, medium, heavy), the talisman and 37 goods kinds. A tint layer gives every item its own colour from its name or infusion. Worn armour uses three dyeable textures; gauntlets are worn invisibly in the boots slot (Minecraft has no arm slot).
- **Attributes** (`ErPlayer`, `ErStats`): saved per character world and synced to the client. A new character takes its Elden Ring class's attributes (reported by the bridge). Vigor sets max health on Elden Ring's HP curve at the bridge's scale (5 Elden Ring HP to one health point). Mind sets FP and Endurance sets equip load; overloaded halves walking speed. `/levelup` within 90 seconds of a grace rest opens a level-up screen priced on Elden Ring's rune curve (as experience, √runes × 4 × 0.8).
- **Weapons** (`ErWeapons`): attack rating exactly as the game computes it, with upgrade rates, correction graphs, element-to-attribute links, the 60% penalty for unmet requirements, and two-handing (an empty off hand) counting Strength ×1.5. Damage goes to Elden Ring enemies without the vanilla tier factor (`ErHits`, `SkyrimActorEntity.addExactDamage`). Bleed, frost, poison and rot buildup comes from the weapon's own effects with Arcane scaling. Class sets swing speed and reach. Upgrades are +0 to +25 or +10 at Hewg's anvil, with the smithing stones Elden Ring asks for. Bows and crossbows use Elden Ring arrows, greatarrows, bolts and greatbolts.
- **Armour** (`ErArmour`): negation per element multiplies across worn pieces as in Elden Ring, with talisman and buff rates, before the existing wards.
- **Talismans**: `/talismans` holds one, plus one per Talisman Pouch received. A generic SpEffect reader covers attack and damage-taken rates, absorption, attribute bonuses, max HP/FP/stamina, equip load, resistances and periodic HP/FP regeneration. A talisman of the same group does not stack. Conditional talismans (on kill, on crit, at low HP) are listed as "not yet reproduced".
- **Flasks** (`ErFlasks`): charges, Cerulean split (`/flasks <n>` at a grace), Sacred Tear level and refill at grace rests. Heal amounts come from each flask level's own SpEffect (250 to 810 HP, 80 to 220 FP). Golden Seeds and Sacred Tears work on right-click.
- **Goods** (`ErGoods`, `ErBuffs`): healing and FP items, timed buffs with Elden Ring's durations (greases and boluses included), runes to experience, and thrown pots, knives and darts with their real attack. Memory Stones and Talisman Pouches add slots.
- **Spells** (`ErSpells`): right-click a spell to memorise it in a free slot (two, plus one per Memory Stone). A staff casts sorceries and a seal incantations, and sneaking switches spells. Each cast costs FP, needs its Int/Fai/Arc, and deals its bullet's attack × the catalyst's spell scaling. Heals and buffs apply their effects to the caster. An FP bar and the selected spell show at the top left.
- **Mirroring** (`game/src/inventory.rs`, `ErMirror`): the DLL compares the Elden Ring inventory twice a second and sends each gain as `IN_ER_ITEM`; the first look at a character only learns what it already has. The class goes over once as `IN_ER_CLASS`.
- **HUD and stamina** (`ErHud`, `ErStamina`): Elden Ring-style HP, FP and stamina bars at the top left replace the hearts. Each is as long as its maximum, and HP shows a lingering yellow damage trail. Stamina follows Elden Ring's Endurance curve (96 at 10, 170 at 99) and recovers 45 a second after a short pause (slower behind a shield; Green Turtle-style talismans add to it). Sprinting costs 12 a second, a swing 8 to 24 by weapon class, an Elden Ring bow shot 12 to 16, and a spell its own stamina cost. A blocked hit costs stamina by the shield's guard boost, and emptying the pool breaks the guard. At zero you cannot attack or cast, and sprinting stays locked until 12 is back.
- **Loot replaced** (`SkyLoot`, `ErShops`): the custom enemy drops (family materials, regional gear) and the 208 themed boss rewards are gone. Elden Ring's own drops arrive through the mirror when picked up, and kills and first boss kills still give experience. Each merchant villager sells its own Elden Ring lineup (ShopLineupParam). `tools/generate_merchants.py` finds the trading merchants in the game's NpcParam by the Bell Bearing each drops (map item lots 119000-119090), which also names its lineup (100525 onward; Kalé 100500), and writes `merchants.json` and `game/src/merchants.rs`. The merchant-named NPCs without a Bell Bearing (NpcParam 32008xxx-32009xxx) never trade and are no longer turned into villagers. Shadow of the Erdtree's traders (Moore, Thiollier, Count Ymir) are talking NPCs: their native shops still work, and what they sell arrives through the mirror. Prices are Elden Ring's runes as experience (√runes × 0.8), limited stock stays sold per character, and items Elden Ring unlocks later by event flag are left out. Roderika's spawn eggs and Hewg's anvil stay.
- **Tooltips** (`ErTooltips`): attack rating by element, scaling letters, requirements (red when unmet), status buildup, guard, next upgrade; armour negation, resistances and poise; talisman effects; spell costs; flask charges.
- **Tests** (`ErMathTest`): HP/FP/equip-load curves and rune cost against the game's numbers, attack rating rules, upgrade costs, talisman reading, flask heals, spell attack, and art for every item.

## Not yet

1. **Ashes of War and weapon skills:** the ash items exist, but applying them and the skills themselves do not.
2. **Spirit ashes:** inventory items only; summoning needs Minecraft allies that fight Elden Ring enemies.
3. **Crafting:** cookbooks and the crafting kit's recipes.
4. **Great Runes, the Physick and its tears:** inventory items only.
5. **Infusions by whetblade:** infused weapons arrive already infused from Elden Ring; changing one in Minecraft is missing.
6. **Spell visuals:** every spell flies as its own icon with an element trail; area, beam and melee spells all fire as projectiles.
7. **The dodge roll**, and stamina for swings that miss (only swings that hit an entity cost stamina so far).
8. **Descriptions:** Paramdex has names but not item text.

## Open questions

1. **Existing saves:** only items gained after the first look are mirrored, so a world keeps the empty start. Should an existing character's current inventory come over once instead?
2. **Level cost:** the experience price per level (`ErStats.LEVEL_COST_SCALE`) needs play-testing against kill experience and trader prices.

## Not verified in game

Everything above compiles and its maths is tested, but none of it has been played: the inventory mirror, the attack-rating conversion (`ErWeapons.AR_TO_MC`), shields against native hits, the menus, the art in game, and the worn armour textures.
