# Boss rewards

What each boss gives: its named item with every enchantment on it, and its enchanted book. Written from the
reward code itself by `RewardsSheetTest`. Besides these, every boss gives regional materials and drops XP orbs
where it dies (first kill only). Rewards already claimed are not changed.

- **Armour** is enchanted by tier, carries its boss's ward (I-VI) and its set's armour trim. Projectile sets have
  Projectile Protection (ER arrows, spells, thrown objects) instead of Protection.
- **Weapons** are the kind the boss fights with in Elden Ring (sword, axe, spear, mace, trident, bow, crossbow; a
  pickaxe from stone and crystal bosses). They carry their boss's element or status (I-V, none for a physical boss)
  and a *signature* enchantment: Smite (undead bosses; counts ER's undead), Fire Aspect (fire bosses), otherwise
  one that suits the weapon: Sweeping Edge (swords), Knockback (axes and big bruisers; adds stagger in ER), Lunge
  (spears), Wind Burst (maces), Loyalty (tridents), Flame or Infinity (bows), Multishot (crossbows). Where two
  different bosses would still give the same weapon, the later one gets an extra enchantment (+) to tell them apart.
- **Books** are the boss's ward (VII only from the one boss marked), or a random book for a boss without one.

## Armour sets

| Set | Tier | Trim | Pieces |
|---|---|---|---|
| Wayfarer's Hide | 1 | Wild pattern, copper | Helm (Beastman of Farum Azula (Limgrave)), Raiment (Demi-Human Chiefs), Greaves (Erdtree Burial Watchdog (Limgrave)), Boots (Guardian Golem) |
| Morne Wanderer | 1 | Coast pattern, emerald | Boots (Ancient Hero of Zamor (Weeping Peninsula)), Helm (Cemetery Shade (Weeping Peninsula)), Raiment (Leonine Misbegotten), Greaves (Scaly Misbegotten) |
| Carian Lakeguard | 2 | Tide pattern, lapis | Helm (Red Wolf of Radagon), Raiment (Rennala, Queen of the Full Moon), Boots (Crystalian), Greaves (Royal Knight Loretta) |
| Stormgate Vanguard | 2 | Sentry pattern, iron | Boots (Tree Sentinel), Greaves (Crucible Knight), Helm (Margit, the Fell Omen), Raiment (Godrick the Grafted) |
| Amber Roadwarden | 3 | Raiser pattern, resin | Greaves (Ancient Hero of Zamor (Altus Plateau)), Helm (Demi-Human Queen Gilika), Raiment (Elemer of the Briar), Boots (Fallingstar Beast (Altus Plateau)) |
| Redmane Exile | 3 | Dune pattern, redstone | Raiment (Commander O'Niel), Boots (Magma Wyrm (Caelid)), Helm (Battlemage Hugues), Greaves (Cleanrot Knight (Greyoll's Dragonbarrow)) |
| Eternal Pathfinder | 3 | Wayfinder pattern, amethyst | Boots (Dragonkin Soldier of Nokstella), Helm (Mimic Tear), Raiment (Regal Ancestor Spirit), Greaves (Valiant Gargoyles) |
| Gilded Omenward | 4 | Host pattern, gold | Boots (Draconic Tree Sentinel), Helm (Godfrey, First Elden Lord), Greaves (Mohg, the Omen), Raiment (Morgott, the Omen King) |
| Duskbound Seeker | 4 | Eye pattern, diamond | Greaves (Alecto, Black Knife Ringleader), Boots (Glintstone Dragon Adula), Raiment (Astel, Naturalborn of the Void), Helm (Lichdragon Fortissax) |
| Cinder Pilgrim | 4 | Snout pattern, netherite | Helm (Demi-Human Queen Maggie), Boots (Full-Grown Fallingstar Beast), Greaves (Godskin Noble), Raiment (Rykard, Lord of Blasphemy) |
| Winterbound Sentinel | 5 | Ward pattern, quartz | Boots (Great Wyrm Theodorix), Raiment (Fire Giant), Greaves (Lorretta, Knight of the Haligtree), Helm (Commander Niall) |
| Bloodroot Sovereign | 5 | Vex pattern, redstone | Greaves (Putrid Avatar (Consecrated Snowfield)), Boots (Putrid Grave Warden Duelist), Raiment (Malenia, Blade of Miquella), Helm (Mohg, Lord of Blood) |
| Last Age Champion | 5 | Bolt pattern, emerald | Greaves (Dragonlord Placidusax), Helm (Godskin Duo), Raiment (Maliketh, the Black Blade), Boots (Elden Beast) |
| Veiled Coastkeeper | 6 | Flow pattern, lapis | Greaves (Dancer of Ranah), Helm (Demi-Human Queen Marigga), Raiment (Putrescent Knight), Boots (Death Rite Bird (Charo's Hidden Grave)) |
| Nameless Oath | 6 | Silence pattern, iron | Boots (Chief Bloodfiend), Greaves (Demi-Human Swordmaster Onze), Helm (Knight of the Solitary Gaol), Raiment (Rellana, Twin Moon Knight) |
| Eclipse Sovereign | 7 | Spire pattern, gold | Helm (Midra, Lord of Frenzied Flame), Raiment (Promised Consort Radahn), Boots (Bayle, the Dread), Greaves (Metyr, Mother of Fingers) |
| Ashen Crucible | 7 | Rib pattern, resin | Greaves (Romina, Saint of the Bud), Helm (Commander Gaius), Raiment (Messmer the Impaler), Boots (Scadutree Avatar) |

## Every boss

| Tier | Boss | Region | Item | Enchantments on the item | Book |
|---|---|---|---|---|---|
| 1 | Beastman of Farum Azula (Limgrave) | Limgrave | Wayfarer's Hide Helm (leather helmet; wild trim) | Protection I, Unbreaking I | Random (tier 1) |
| 1 | Bloodhound Knight Darriwil | Limgrave | Bloodhound Knight Darriwil's Blade (stone sword) | Sharpness I, Unbreaking I, Bloodletting I, *Sweeping Edge I* | **Bleed Ward I** |
| 1 | Demi-Human Chiefs | Limgrave | Wayfarer's Hide Raiment (leather chestplate; wild trim) | Protection I, Unbreaking I | Random (tier 1) |
| 1 | Erdtree Burial Watchdog (Limgrave) | Limgrave | Wayfarer's Hide Greaves (leather leggings; wild trim) | Protection I, Unbreaking I, Flame Ward I | **Flame Ward I** |
| 1 | Flying Dragon Agheel | Limgrave | Flying Dragon Agheel's Cleaver (stone axe) | Sharpness I, Unbreaking I, Flame (element) I, *Fire Aspect I* | **Flame Ward I** |
| 1 | Grave Warden Duelist (Limgrave) | Limgrave | Grave Warden Duelist's Cleaver (stone axe) | Sharpness I, Unbreaking I, *Knockback I*, +Efficiency I | Random (tier 1) |
| 1 | Guardian Golem | Limgrave | Wayfarer's Hide Boots (leather boots; wild trim) | Protection I, Feather Falling I, Unbreaking I | Random (tier 1) |
| 1 | Mad Pumpkin Head | Limgrave | Mad Pumpkin Head's Maul (mace) | Density I, Unbreaking I, *Wind Burst I* | Random (tier 1) |
| 1 | Night's Cavalry (Limgrave) | Limgrave | Night's Cavalry's Lance (stone spear) | Sharpness I, Unbreaking I, *Lunge I* | Random (tier 1) |
| 1 | Patches (Murkwater Cave) | Limgrave | Patches's Lance (stone spear) | Sharpness I, Unbreaking I, *Lunge I*, +Knockback I | Random (tier 1) |
| 1 | Soldier of Godrick | Limgrave | Soldier of Godrick's Blade (stone sword) | Sharpness I, Unbreaking I, *Sweeping Edge I* | Random (tier 1) |
| 1 | Stonedigger Troll (Limgrave) | Limgrave | Stonedigger Troll's Maul (mace) | Density I, Unbreaking I, *Wind Burst I*, +Knockback I | Random (tier 1) |
| 1 | Tibia Mariner (Limgrave) | Limgrave | Tibia Mariner's Trident (trident) | Sharpness I, Unbreaking I, Glintstone I, *Loyalty I* | **Glintstone Ward I** |
| 1 | Ulcerated Tree Spirit (Limgrave) | Limgrave | Ulcerated Tree Spirit's Cleaver (stone axe) | Sharpness I, Unbreaking I, Flame (element) I, *Fire Aspect I*, +Efficiency I | **Flame Ward I** |
| 1 | Bell Bearing Hunter (Stormhill) | Stormhill | Bell Bearing Hunter's Cleaver (stone axe) | Sharpness I, Unbreaking I, *Knockback I* | Random (tier 1) |
| 1 | Black Knife Assassin (Stormhill) | Stormhill | Black Knife Assassin's Blade (stone sword) | Sharpness I, Unbreaking I, Sacred I, *Sweeping Edge I* | **Sacred Ward I** |
| 1 | Deathbird (Stormhill) | Stormhill | Deathbird's Maul (mace) | Density I, Unbreaking I, *Smite I* | Random (tier 1) |
| 1 | Ancient Hero of Zamor (Weeping Peninsula) | Weeping Peninsula | Morne Wanderer Boots (leather boots; coast trim) | Projectile Protection II, Feather Falling I, Unbreaking I, Frost Ward I | **Frost Ward I** |
| 1 | Cemetery Shade (Weeping Peninsula) | Weeping Peninsula | Morne Wanderer Helm (leather helmet; coast trim) | Projectile Protection II, Unbreaking I | Random (tier 1) |
| 1 | Deathbird (Weeping Peninsula) | Weeping Peninsula | Deathbird's Maul (mace) | Density I, Unbreaking I, *Smite I* | Random (tier 1) |
| 1 | Erdtree Avatar (Weeping Peninsula) | Weeping Peninsula | Erdtree Avatar's Maul (mace) | Density I, Unbreaking I, Sacred I, *Wind Burst I* | **Sacred Ward I** |
| 1 | Erdtree Burial Watchdog (Weeping Peninsula) | Weeping Peninsula | Erdtree Burial Watchdog's Blade (stone sword) | Sharpness I, Unbreaking I, Flame (element) I, *Fire Aspect I* | **Flame Ward I** |
| 1 | Leonine Misbegotten | Weeping Peninsula | Morne Wanderer Raiment (leather chestplate; coast trim) | Projectile Protection II, Unbreaking I | Random (tier 1) |
| 1 | Miranda the Blighted Bloom | Weeping Peninsula | Miranda the Blighted Bloom's Cleaver (stone axe) | Sharpness I, Unbreaking I, Venom I, *Knockback I* | **Venom Ward I** |
| 1 | Night's Cavalry (Weeping Peninsula) | Weeping Peninsula | Night's Cavalry's Lance (stone spear) | Sharpness I, Unbreaking I, *Lunge I* | Random (tier 1) |
| 1 | Runebear | Weeping Peninsula | Runebear's Cleaver (stone axe) | Sharpness I, Unbreaking I, *Knockback I*, +Smite I | Random (tier 1) |
| 1 | Scaly Misbegotten | Weeping Peninsula | Morne Wanderer Greaves (leather leggings; coast trim) | Projectile Protection II, Unbreaking I | Random (tier 1) |
| 2 | Red Wolf of Radagon | Academy of Raya Lucaria | Carian Lakeguard Helm (iron helmet; tide trim) | Projectile Protection III, Respiration I, Unbreaking I, Glintstone Ward II | **Glintstone Ward II** |
| 2 | Rennala, Queen of the Full Moon | Academy of Raya Lucaria | Carian Lakeguard Raiment (iron chestplate; tide trim) | Projectile Protection III, Unbreaking I, Glintstone Ward II | **Glintstone Ward II** |
| 2 | Black Knife Assassin (Bellum Highway) | Bellum Highway | Black Knife Assassin's Blade (iron sword) | Sharpness II, Unbreaking I, Sacred I, *Sweeping Edge I* | **Sacred Ward II** |
| 2 | Cemetery Shade (Bellum Highway) | Bellum Highway | Cemetery Shade's Blade (iron sword) | Sharpness II, Unbreaking I, *Smite II* | Random (tier 2) |
| 2 | Night's Cavalry (Bellum Highway) | Bellum Highway | Night's Cavalry's Lance (iron spear) | Sharpness II, Unbreaking I, *Lunge I* | Random (tier 2) |
| 2 | Tree Sentinel | Limgrave | Stormgate Vanguard Boots (iron boots; sentry trim) | Protection II, Feather Falling II, Unbreaking I, Sacred Ward II | **Sacred Ward II** |
| 2 | Adan, Thief of Fire | Liurnia of the Lakes | Adan, Thief of Fire's Arbalest (crossbow) | Quick Charge II, Piercing II, Unbreaking I, Flame (element) I, *Multishot* | **Flame Ward II** |
| 2 | Bell Bearing Hunter (Liurnia of the Lakes) | Liurnia of the Lakes | Bell Bearing Hunter's Cleaver (iron axe) | Sharpness II, Unbreaking I, *Knockback I* | Random (tier 2) |
| 2 | Bloodhound Knight | Liurnia of the Lakes | Bloodhound Knight's Blade (iron sword) | Sharpness II, Unbreaking I, Bloodletting I, *Sweeping Edge I* | **Bleed Ward II** |
| 2 | Bols, Carian Knight | Liurnia of the Lakes | Bols, Carian Knight's Blade (iron sword) | Sharpness II, Unbreaking I, Glintstone I, *Sweeping Edge I* | **Glintstone Ward II** |
| 2 | Cleanrot Knight (Liurnia of the Lakes) | Liurnia of the Lakes | Cleanrot Knight's Lance (iron spear) | Sharpness II, Unbreaking I, Scarlet Rot I, *Lunge I* | **Rot Ward II** |
| 2 | Crystalian | Liurnia of the Lakes | Carian Lakeguard Boots (iron boots; tide trim) | Projectile Protection III, Feather Falling II, Unbreaking I, Glintstone Ward II | **Glintstone Ward II** |
| 2 | Crystalian Duo (Liurnia of the Lakes) | Liurnia of the Lakes | Crystalian Duo's Lance (iron spear) | Sharpness II, Unbreaking I, Glintstone I, *Lunge I* | **Glintstone Ward II** |
| 2 | Death Rite Bird (Liurnia of the Lakes) | Liurnia of the Lakes | Death Rite Bird's Maul (mace) | Density II, Unbreaking I, Frostbite I, *Smite II* | **Frost Ward II** |
| 2 | Deathbird (Liurnia of the Lakes) | Liurnia of the Lakes | Deathbird's Maul (mace) | Density II, Unbreaking I, *Smite II* | Random (tier 2) |
| 2 | Erdtree Avatar (NE) | Liurnia of the Lakes | Erdtree Avatar's Maul (mace) | Density II, Unbreaking I, Sacred I, *Wind Burst I* | **Sacred Ward II** |
| 2 | Erdtree Avatar (SW) | Liurnia of the Lakes | Erdtree Avatar's Maul (mace) | Density II, Unbreaking I, Sacred I, *Wind Burst I* | **Sacred Ward II** |
| 2 | Erdtree Burial Watchdog (Liurnia of the Lakes) | Liurnia of the Lakes | Erdtree Burial Watchdog's Blade (iron sword) | Sharpness II, Unbreaking I, Flame (element) I, *Fire Aspect I* | **Flame Ward II** |
| 2 | Glintstone Dragon Smarag | Liurnia of the Lakes | Glintstone Dragon Smarag's Cleaver (iron axe) | Sharpness II, Unbreaking I, Glintstone I, *Knockback I* | **Glintstone Ward II** |
| 2 | Magma Wyrm Makar | Liurnia of the Lakes | Magma Wyrm Makar's Blade (iron sword) | Sharpness II, Unbreaking I, Flame (element) I, *Fire Aspect I*, +Sweeping Edge I | **Flame Ward II** |
| 2 | Night's Cavalry (Liurnia of the Lakes) | Liurnia of the Lakes | Night's Cavalry's Lance (iron spear) | Sharpness II, Unbreaking I, *Lunge I* | Random (tier 2) |
| 2 | Omenkiller | Liurnia of the Lakes | Omenkiller's Cleaver (iron axe) | Sharpness II, Unbreaking I, *Knockback I*, +Efficiency II | Random (tier 2) |
| 2 | Onyx Lord (Liurnia of the Lakes) | Liurnia of the Lakes | Onyx Lord's Blade (iron sword) | Sharpness II, Unbreaking I, Glintstone I, *Sweeping Edge I*, +Knockback I | **Glintstone Ward II** |
| 2 | Royal Knight Loretta | Liurnia of the Lakes | Carian Lakeguard Greaves (iron leggings; tide trim) | Projectile Protection III, Unbreaking I, Glintstone Ward II | **Glintstone Ward II** |
| 2 | Royal Revenant | Liurnia of the Lakes | Royal Revenant's Cleaver (iron axe) | Sharpness II, Unbreaking I, *Smite II* | Random (tier 2) |
| 2 | Spirit-Caller Snail (Liurnia of the Lakes) | Liurnia of the Lakes | Spirit-Caller Snail's Trident (trident) | Sharpness II, Unbreaking I, *Loyalty I* | Random (tier 2) |
| 2 | Tibia Mariner (Liurnia of the Lakes) | Liurnia of the Lakes | Tibia Mariner's Trident (trident) | Sharpness II, Unbreaking I, Glintstone I, *Loyalty I*, +Knockback I | **Glintstone Ward II** |
| 2 | Crucible Knight | Stormhill | Stormgate Vanguard Greaves (iron leggings; sentry trim) | Protection II, Unbreaking I | Random (tier 2) |
| 2 | Margit, the Fell Omen | Stormhill | Stormgate Vanguard Helm (iron helmet; sentry trim) | Protection II, Respiration I, Unbreaking I, Sacred Ward II | **Sacred Ward II** |
| 2 | Godrick the Grafted | Stormveil Castle | Stormgate Vanguard Raiment (iron chestplate; sentry trim) | Protection II, Unbreaking I, Flame Ward II | **Flame Ward II** |
| 2 | Grafted Scion | Stormveil Castle | Grafted Scion's Blade (iron sword) | Sharpness II, Unbreaking I, *Knockback I* | Random (tier 2) |
| 2 | Ancestor Spirit | Underground | Ancestor Spirit's Trident (trident) | Sharpness II, Unbreaking I, Glintstone I, *Loyalty I* | **Glintstone Ward II** |
| 2 | Dragonkin Soldier (Siofra River) | Underground | Dragonkin Soldier's Maul (mace) | Density II, Unbreaking I, Frostbite I, *Wind Burst I* | **Frost Ward II** |
| 3 | Ancient Dragon Lansseax | Altus Plateau | Ancient Dragon Lansseax's Lance (iron spear) | Sharpness III, Unbreaking II, Lightning II, *Lunge II* | **Storm Ward III** |
| 3 | Ancient Hero of Zamor (Altus Plateau) | Altus Plateau | Amber Roadwarden Greaves (iron leggings; raiser trim) | Protection III, Unbreaking II, Frost Ward III | **Frost Ward III** |
| 3 | Black Knife Assassin (Sage's Cave) | Altus Plateau | Black Knife Assassin's Blade (iron sword) | Sharpness III, Unbreaking II, Sacred II, *Sweeping Edge II* | **Sacred Ward III** |
| 3 | Black Knife Assassin (Sainted Hero's Grave) | Altus Plateau | Black Knife Assassin's Blade (iron sword) | Sharpness III, Unbreaking II, Sacred II, *Sweeping Edge II* | **Sacred Ward III** |
| 3 | Crystalian Duo (Altus Plateau) | Altus Plateau | Crystalian Duo's Lance (iron spear) | Sharpness III, Unbreaking II, Glintstone II, *Lunge II* | **Glintstone Ward III** |
| 3 | Demi-Human Queen Gilika | Altus Plateau | Amber Roadwarden Helm (iron helmet; raiser trim) | Protection III, Respiration II, Unbreaking II, Glintstone Ward III | **Glintstone Ward III** |
| 3 | Elemer of the Briar | Altus Plateau | Amber Roadwarden Raiment (iron chestplate; raiser trim) | Protection III, Unbreaking II, Bleed Ward III | **Bleed Ward III** |
| 3 | Erdtree Burial Watchdog (Altus Plateau) | Altus Plateau | Erdtree Burial Watchdog's Blade (iron sword) | Sharpness III, Unbreaking II, Flame (element) II, *Fire Aspect I* | **Flame Ward III** |
| 3 | Fallingstar Beast (Altus Plateau) | Altus Plateau | Amber Roadwarden Boots (iron boots; raiser trim) | Protection III, Feather Falling III, Unbreaking II, Glintstone Ward III | **Glintstone Ward III** |
| 3 | Godefroy the Grafted | Altus Plateau | Godefroy the Grafted's Cleaver (iron axe) | Sharpness III, Unbreaking II, *Knockback I*, +Smite III | Random (tier 3) |
| 3 | Godskin Apostle (Altus Plateau) | Altus Plateau | Godskin Apostle's Lance (iron spear) | Sharpness III, Unbreaking II, Flame (element) II, *Fire Aspect I* | **Flame Ward III** |
| 3 | Necromancer Garris | Altus Plateau | Necromancer Garris's Maul (mace) | Density III, Unbreaking II, *Wind Burst II*, +Smite III | Random (tier 3) |
| 3 | Night's Cavalry (Altus Plateau) | Altus Plateau | Night's Cavalry's Lance (iron spear) | Sharpness III, Unbreaking II, *Lunge II* | Random (tier 3) |
| 3 | Omenkiller & Miranda the Blighted Bloom | Altus Plateau | Omenkiller & Miranda the Blighted Bloom's Cleaver (iron axe) | Sharpness III, Unbreaking II, Venom II, *Knockback I* | **Venom Ward III** |
| 3 | Perfumer Tricia & Misbegotten Warrior | Altus Plateau | Perfumer Tricia & Misbegotten Warrior's Arbalest (crossbow) | Quick Charge III, Piercing III, Unbreaking II, Venom II, *Multishot* | **Venom Ward III** |
| 3 | Sanguine Noble | Altus Plateau | Sanguine Noble's Lance (iron spear) | Sharpness III, Unbreaking II, Bloodletting II, *Lunge II* | **Bleed Ward III** |
| 3 | Stonedigger Troll (Altus Plateau) | Altus Plateau | Stonedigger Troll's Maul (mace) | Density III, Unbreaking II, *Wind Burst II*, +Fire Aspect I | Random (tier 3) |
| 3 | Tibia Mariner (Altus Plateau) | Altus Plateau | Tibia Mariner's Trident (trident) | Sharpness III, Unbreaking II, Glintstone II, *Loyalty II* | **Glintstone Ward III** |
| 3 | Tree Sentinel Duo | Altus Plateau | Tree Sentinel Duo's Lance (iron spear) | Sharpness III, Unbreaking II, Sacred II, *Lunge II* | **Sacred Ward III** |
| 3 | Wormface | Altus Plateau | Wormface's Cleaver (iron axe) | Sharpness III, Unbreaking II, *Knockback I*, +Efficiency III, +Smite III | Random (tier 3) |
| 3 | Cemetery Shade (Caelid) | Caelid | Cemetery Shade's Blade (iron sword) | Sharpness III, Unbreaking II, *Smite III* | Random (tier 3) |
| 3 | Commander O'Niel | Caelid | Redmane Exile Raiment (iron chestplate; dune trim) | Protection III, Unbreaking II, Rot Ward III | **Rot Ward III** |
| 3 | Death Rite Bird (Caelid) | Caelid | Death Rite Bird's Maul (mace) | Density III, Unbreaking II, Frostbite II, *Smite III* | **Frost Ward III** |
| 3 | Decaying Ekzykes | Caelid | Decaying Ekzykes's Cleaver (iron axe) | Sharpness III, Unbreaking II, Scarlet Rot II, *Knockback I* | **Rot Ward III** |
| 3 | Erdtree Burial Watchdog (Caelid) | Caelid | Erdtree Burial Watchdog's Blade (iron sword) | Sharpness III, Unbreaking II, Flame (element) II, *Fire Aspect I* | **Flame Ward III** |
| 3 | Fallingstar Beast (Caelid) | Caelid | Fallingstar Beast's Delver (iron pickaxe) | Efficiency III, Fortune I, Unbreaking II | **Glintstone Ward III** |
| 3 | Frenzied Duelist | Caelid | Frenzied Duelist's Maul (mace) | Density III, Unbreaking II, *Wind Burst II* | Random (tier 3) |
| 3 | Mad Pumpkin Head Duo | Caelid | Mad Pumpkin Head Duo's Maul (mace) | Density III, Unbreaking II, *Wind Burst II*, +Knockback I | Random (tier 3) |
| 3 | Magma Wyrm (Caelid) | Caelid | Redmane Exile Boots (iron boots; dune trim) | Protection III, Feather Falling III, Unbreaking II, Flame Ward III | **Flame Ward III** |
| 3 | Night's Cavalry (Caelid) | Caelid | Night's Cavalry's Lance (iron spear) | Sharpness III, Unbreaking II, *Lunge II* | Random (tier 3) |
| 3 | Nox Swordstress & Nox Priest | Caelid | Nox Swordstress & Nox Priest's Blade (iron sword) | Sharpness III, Unbreaking II, Glintstone II, *Sweeping Edge II* | **Glintstone Ward III** |
| 3 | Putrid Avatar (Caelid) | Caelid | Putrid Avatar's Maul (mace) | Density III, Unbreaking II, Scarlet Rot II, *Wind Burst II* | **Rot Ward III** |
| 3 | Putrid Crystallian Trio | Caelid | Putrid Crystallian Trio's Delver (iron pickaxe) | Efficiency III, Fortune I, Unbreaking II | **Rot Ward III** |
| 3 | Bell Bearing Hunter (Capital Outskirts) | Capital Outskirts | Bell Bearing Hunter's Cleaver (iron axe) | Sharpness III, Unbreaking II, *Knockback I* | Random (tier 3) |
| 3 | Crucible Knight & Crucible Knight Ordovis | Capital Outskirts | Crucible Knight & Crucible Knight Ordovis's Blade (iron sword) | Sharpness III, Unbreaking II, *Knockback I* | Random (tier 3) |
| 3 | Deathbird (Capital Outskirts) | Capital Outskirts | Deathbird's Maul (mace) | Density III, Unbreaking II, *Smite III* | Random (tier 3) |
| 3 | Fell Twins | Capital Outskirts | Fell Twins's Cleaver (iron axe) | Sharpness III, Unbreaking II, *Knockback I*, +Efficiency III | Random (tier 3) |
| 3 | Grave Warden Duelist (Capital Outskirts) | Capital Outskirts | Grave Warden Duelist's Cleaver (iron axe) | Sharpness III, Unbreaking II, *Knockback I*, +Fire Aspect I | Random (tier 3) |
| 3 | Onyx Lord (Capital Outskirts) | Capital Outskirts | Onyx Lord's Blade (iron sword) | Sharpness III, Unbreaking II, Glintstone II, *Sweeping Edge II*, +Knockback I | **Glintstone Ward III** |
| 3 | Battlemage Hugues | Greyoll's Dragonbarrow | Redmane Exile Helm (iron helmet; dune trim) | Protection III, Respiration II, Unbreaking II, Glintstone Ward III | **Glintstone Ward III** |
| 3 | Cleanrot Knight (Greyoll's Dragonbarrow) | Greyoll's Dragonbarrow | Redmane Exile Greaves (iron leggings; dune trim) | Protection III, Unbreaking II, Rot Ward III | **Rot Ward III** |
| 3 | Crucible Knight & Misbegotten Warrior | Redmane Castle | Crucible Knight & Misbegotten Warrior's Blade (iron sword) | Sharpness III, Unbreaking II, *Knockback I*, +Sweeping Edge II | Random (tier 3) |
| 3 | Putrid Tree Spirit | Redmane Castle | Putrid Tree Spirit's Cleaver (iron axe) | Sharpness III, Unbreaking II, Scarlet Rot II, *Knockback I*, +Efficiency III | **Rot Ward III** |
| 3 | Dragonkin Soldier of Nokstella | Underground | Eternal Pathfinder Boots (iron boots; wayfinder trim) | Protection III, Feather Falling III, Unbreaking II, Storm Ward III | **Storm Ward III** |
| 3 | Mimic Tear | Underground | Eternal Pathfinder Helm (iron helmet; wayfinder trim) | Protection III, Respiration II, Unbreaking II | Random (tier 3) |
| 3 | Regal Ancestor Spirit | Underground | Eternal Pathfinder Raiment (iron chestplate; wayfinder trim) | Protection III, Unbreaking II, Glintstone Ward III | **Glintstone Ward III** |
| 3 | Valiant Gargoyles | Underground | Eternal Pathfinder Greaves (iron leggings; wayfinder trim) | Protection III, Unbreaking II, Venom Ward III | **Venom Ward III** |
| 4 | Draconic Tree Sentinel | Capital Outskirts | Gilded Omenward Boots (diamond boots; host trim) | Depth Strider II, Protection IV, Feather Falling IV, Unbreaking II, Storm Ward IV | **Storm Ward IV** |
| 4 | Beastman of Farum Azula (Greyoll's Dragonbarrow) | Greyoll's Dragonbarrow | Beastman of Farum Azula's Blade (diamond sword) | Sharpness IV, Unbreaking II, *Knockback I* | Random (tier 4) |
| 4 | Bell Bearing Hunter (Greyoll's Dragonbarrow) | Greyoll's Dragonbarrow | Bell Bearing Hunter's Cleaver (diamond axe) | Sharpness IV, Unbreaking II, *Knockback I* | Random (tier 4) |
| 4 | Black Blade Kindred (Greyoll's Dragonbarrow) | Greyoll's Dragonbarrow | Black Blade Kindred's Cleaver (diamond axe) | Sharpness IV, Unbreaking II, *Knockback I*, +Efficiency IV | Random (tier 4) |
| 4 | Elder Dragon Greyoll | Greyoll's Dragonbarrow | Elder Dragon Greyoll's Cleaver (diamond axe) | Sharpness IV, Unbreaking II, Flame (element) III, *Fire Aspect II* | **Flame Ward IV** |
| 4 | Flying Dragon Greyll | Greyoll's Dragonbarrow | Flying Dragon Greyll's Cleaver (diamond axe) | Sharpness IV, Unbreaking II, Flame (element) III, *Fire Aspect II*, +Efficiency IV | **Flame Ward IV** |
| 4 | Godskin Apostle (Greyoll's Dragonbarrow) | Greyoll's Dragonbarrow | Godskin Apostle's Lance (diamond spear) | Sharpness IV, Unbreaking II, Flame (element) III, *Fire Aspect II* | **Flame Ward IV** |
| 4 | Night's Cavalry (Greyoll's Dragonbarrow) | Greyoll's Dragonbarrow | Night's Cavalry's Lance (diamond spear) | Sharpness IV, Unbreaking II, *Lunge II*, +Knockback I | Random (tier 4) |
| 4 | Putrid Avatar (Greyoll's Dragonbarrow) | Greyoll's Dragonbarrow | Putrid Avatar's Maul (mace) | Density IV, Unbreaking II, Scarlet Rot III, *Wind Burst II* | **Rot Ward IV** |
| 4 | Esgar, Priest of Blood | Leyndell, Royal Capital | Esgar, Priest of Blood's Blade (diamond sword) | Sharpness IV, Unbreaking II, Bloodletting III, *Sweeping Edge II* | **Bleed Ward IV** |
| 4 | Godfrey, First Elden Lord | Leyndell, Royal Capital | Gilded Omenward Helm (diamond helmet; host trim) | Protection IV, Respiration III, Unbreaking II, Sacred Ward IV | **Sacred Ward IV** |
| 4 | Mohg, the Omen | Leyndell, Royal Capital | Gilded Omenward Greaves (diamond leggings; host trim) | Protection IV, Unbreaking II, Bleed Ward IV | **Bleed Ward IV** |
| 4 | Morgott, the Omen King | Leyndell, Royal Capital | Gilded Omenward Raiment (diamond chestplate; host trim) | Protection IV, Unbreaking II, Sacred Ward IV | **Sacred Ward IV** |
| 4 | Alecto, Black Knife Ringleader | Moonlight Altar | Duskbound Seeker Greaves (diamond leggings; eye trim) | Projectile Protection IV, Unbreaking II, Glintstone Ward IV | **Glintstone Ward IV** |
| 4 | Glintstone Dragon Adula | Moonlight Altar | Duskbound Seeker Boots (diamond boots; eye trim) | Projectile Protection IV, Depth Strider II, Feather Falling IV, Unbreaking II, Glintstone Ward IV | **Glintstone Ward IV** |
| 4 | Demi-Human Queen Maggie | Mt. Gelmir | Cinder Pilgrim Helm (diamond helmet; snout trim) | Protection IV, Respiration III, Unbreaking II, Glintstone Ward IV | **Glintstone Ward IV** |
| 4 | Demi-Human Queen Margot | Mt. Gelmir | Demi-Human Queen Margot's Maul (mace) | Density IV, Unbreaking II, Glintstone III, *Wind Burst II* | **Glintstone Ward IV** |
| 4 | Full-Grown Fallingstar Beast | Mt. Gelmir | Cinder Pilgrim Boots (diamond boots; snout trim) | Depth Strider II, Protection IV, Feather Falling IV, Unbreaking II, Glintstone Ward IV | **Glintstone Ward IV** |
| 4 | Kindred of Rot Duo | Mt. Gelmir | Kindred of Rot Duo's Lance (diamond spear) | Sharpness IV, Unbreaking II, Venom III, *Lunge II* | **Venom Ward IV** |
| 4 | Magma Wyrm (Mt. Gelmir) | Mt. Gelmir | Magma Wyrm's Blade (diamond sword) | Sharpness IV, Unbreaking II, Flame (element) III, *Fire Aspect II* | **Flame Ward IV** |
| 4 | Red Wolf of the Champion | Mt. Gelmir | Red Wolf of the Champion's Blade (diamond sword) | Sharpness IV, Unbreaking II, Glintstone III, *Sweeping Edge II* | **Glintstone Ward IV** |
| 4 | Ulcerated Tree Spirit (Mt. Gelmir) | Mt. Gelmir | Ulcerated Tree Spirit's Cleaver (diamond axe) | Sharpness IV, Unbreaking II, Flame (element) III, *Fire Aspect II*, +Knockback I | **Flame Ward IV** |
| 4 | Starscourge Radahn | Redmane Castle | Starscourge Radahn's Blade (diamond sword) | Sharpness IV, Unbreaking II, Glintstone III, *Knockback I* | **Glintstone Ward IV** |
| 4 | Astel, Naturalborn of the Void | Underground | Duskbound Seeker Raiment (diamond chestplate; eye trim) | Projectile Protection IV, Unbreaking II, Glintstone Ward IV | **Glintstone Ward IV** |
| 4 | Crucible Knight Siluria | Underground | Crucible Knight Siluria's Lance (diamond spear) | Sharpness IV, Unbreaking II, *Lunge II* | Random (tier 4) |
| 4 | Dragonkin Soldier (Lake of Rot) | Underground | Dragonkin Soldier's Maul (mace) | Density IV, Unbreaking II, Frostbite III, *Wind Burst II* | **Frost Ward IV** |
| 4 | Fia's Champions | Underground | Fia's Champions's Blade (diamond sword) | Sharpness IV, Unbreaking II, *Sweeping Edge II* | Random (tier 4) |
| 4 | Lichdragon Fortissax | Underground | Duskbound Seeker Helm (diamond helmet; eye trim) | Projectile Protection IV, Respiration III, Unbreaking II, Storm Ward IV | **Storm Ward IV** |
| 4 | Abductor Virgins | Volcano Manor | Abductor Virgins's Delver (diamond pickaxe) | Efficiency IV, Fortune II, Unbreaking II | Random (tier 4) |
| 4 | Godskin Noble | Volcano Manor | Cinder Pilgrim Greaves (diamond leggings; snout trim) | Protection IV, Unbreaking II, Flame Ward IV | **Flame Ward IV** |
| 4 | Rykard, Lord of Blasphemy | Volcano Manor | Cinder Pilgrim Raiment (diamond chestplate; snout trim) | Protection IV, Unbreaking II, Flame Ward IV | **Flame Ward IV** |
| 5 | Astel, Stars of Darkness | Consecrated Snowfield | Astel, Stars of Darkness's Longbow (bow) | Power V, Unbreaking III, Glintstone IV, *Infinity* | **Glintstone Ward V** |
| 5 | Death Rite Bird (Consecrated Snowfield) | Consecrated Snowfield | Death Rite Bird's Maul (mace) | Density V, Unbreaking III, Frostbite IV, *Smite V* | **Frost Ward V** |
| 5 | Great Wyrm Theodorix | Consecrated Snowfield | Winterbound Sentinel Boots (netherite boots; ward trim) | Depth Strider III, Protection IV, Feather Falling IV, Unbreaking III, Flame Ward V | **Flame Ward V** |
| 5 | Misbegotten Crusader | Consecrated Snowfield | Misbegotten Crusader's Blade (netherite sword) | Sharpness V, Unbreaking III, Sacred IV, *Knockback II* | **Sacred Ward V** |
| 5 | Night's Cavalry (Consecrated Snowfield) | Consecrated Snowfield | Night's Cavalry's Lance (netherite spear) | Sharpness V, Unbreaking III, *Lunge III* | Random (tier 5) |
| 5 | Putrid Avatar (Consecrated Snowfield) | Consecrated Snowfield | Bloodroot Sovereign Greaves (netherite leggings; vex trim) | Protection IV, Unbreaking III, Rot Ward V | **Rot Ward V** |
| 5 | Putrid Grave Warden Duelist | Consecrated Snowfield | Bloodroot Sovereign Boots (netherite boots; vex trim) | Depth Strider III, Protection IV, Feather Falling IV, Unbreaking III, Rot Ward V | **Rot Ward V** |
| 5 | Stray Mimic Tear | Consecrated Snowfield | Stray Mimic Tear's Blade (netherite sword) | Sharpness V, Unbreaking III, *Sweeping Edge III* | Random (tier 5) |
| 5 | Dragonlord Placidusax | Crumbling Farum Azula | Last Age Champion Greaves (netherite leggings; bolt trim) | Protection IV, Unbreaking III, Storm Ward V | **Storm Ward V** |
| 5 | Godskin Duo | Crumbling Farum Azula | Last Age Champion Helm (netherite helmet; bolt trim) | Protection IV, Respiration III, Unbreaking III, Flame Ward V | **Flame Ward V** |
| 5 | Maliketh, the Black Blade | Crumbling Farum Azula | Last Age Champion Raiment (netherite chestplate; bolt trim) | Protection IV, Unbreaking III, Sacred Ward V | **Sacred Ward V** |
| 5 | Ancient Hero of Zamor (Flame Peak) | Flame Peak | Ancient Hero of Zamor's Blade (netherite sword) | Sharpness V, Unbreaking III, Frostbite IV, *Sweeping Edge III* | **Frost Ward V** |
| 5 | Fire Giant | Flame Peak | Winterbound Sentinel Raiment (netherite chestplate; ward trim) | Protection IV, Unbreaking III, Flame Ward V | **Flame Ward V** |
| 5 | Black Blade Kindred (Forbidden Lands) | Forbidden Lands | Black Blade Kindred's Cleaver (netherite axe) | Sharpness V, Unbreaking III, *Knockback II* | Random (tier 5) |
| 5 | Night's Cavalry (Forbidden Lands) | Forbidden Lands | Night's Cavalry's Lance (netherite spear) | Sharpness V, Unbreaking III, *Lunge III* | Random (tier 5) |
| 5 | Elden Beast | Leyndell, Capital of Ash | Last Age Champion Boots (netherite boots; bolt trim) | Depth Strider III, Protection IV, Feather Falling IV, Unbreaking III, Sacred Ward V | **Sacred Ward V** |
| 5 | Hoarah Loux, Warrior | Leyndell, Capital of Ash | Hoarah Loux, Warrior's Cleaver (netherite axe) | Sharpness V, Unbreaking III, *Knockback II*, +Efficiency V | Random (tier 5) |
| 5 | Sir Gideon Ofnir, the All-Knowing | Leyndell, Capital of Ash | Sir Gideon Ofnir, the All-Knowing's Longbow (bow) | Power V, Unbreaking III, Glintstone IV, *Infinity*, +Flame | **Glintstone Ward V** |
| 5 | Lorretta, Knight of the Haligtree | Miquella's Haligtree | Winterbound Sentinel Greaves (netherite leggings; ward trim) | Protection IV, Unbreaking III, Sacred Ward V | **Sacred Ward V** |
| 5 | Malenia, Blade of Miquella | Miquella's Haligtree | Bloodroot Sovereign Raiment (netherite chestplate; vex trim) | Protection IV, Unbreaking III, Rot Ward V | **Rot Ward V** |
| 5 | Borealis the Freezing Fog | Mountaintops of the Giants | Borealis the Freezing Fog's Cleaver (netherite axe) | Sharpness V, Unbreaking III, Frostbite IV, *Knockback II* | **Frost Ward V** |
| 5 | Commander Niall | Mountaintops of the Giants | Winterbound Sentinel Helm (netherite helmet; ward trim) | Protection IV, Respiration III, Unbreaking III, Frost Ward V | **Frost Ward V** |
| 5 | Death Rite Bird (Mountaintops of the Giants) | Mountaintops of the Giants | Death Rite Bird's Maul (mace) | Density V, Unbreaking III, Frostbite IV, *Smite V* | **Frost Ward V** |
| 5 | Erdtree Avatar (Mountaintops of the Giants) | Mountaintops of the Giants | Erdtree Avatar's Maul (mace) | Density V, Unbreaking III, Sacred IV, *Wind Burst III* | **Sacred Ward V** |
| 5 | Roundtable Knight Vyke | Mountaintops of the Giants | Roundtable Knight Vyke's Lance (netherite spear) | Sharpness V, Unbreaking III, Flame (element) IV, *Fire Aspect II* | **Flame Ward V** |
| 5 | Spirit-Caller Snail (Mountaintops of the Giants) | Mountaintops of the Giants | Spirit-Caller Snail's Trident (trident) | Sharpness V, Unbreaking III, *Loyalty III* | Random (tier 5) |
| 5 | Ulcerated Tree Spirit (Mountaintops of the Giants) | Mountaintops of the Giants | Ulcerated Tree Spirit's Cleaver (netherite axe) | Sharpness V, Unbreaking III, Flame (element) IV, *Fire Aspect II* | **Flame Ward V** |
| 5 | Mohg, Lord of Blood | Underground | Bloodroot Sovereign Helm (netherite helmet; vex trim) | Protection IV, Respiration III, Unbreaking III, Bleed Ward V | **Bleed Ward V** |
| 6 | Dancer of Ranah | Cerulean Coast | Veiled Coastkeeper Greaves (netherite leggings; flow trim) | Mending, Projectile Protection V, Unbreaking III, Frost Ward VI | **Frost Ward VI** |
| 6 | Demi-Human Queen Marigga | Cerulean Coast | Veiled Coastkeeper Helm (netherite helmet; flow trim) | Mending, Projectile Protection V, Respiration III, Unbreaking III, Glintstone Ward VI | **Glintstone Ward VI** |
| 6 | Ghostflame Dragon (Cerulean Coast) | Cerulean Coast | Ghostflame Dragon's Cleaver (netherite axe) | Mending, Sharpness VI, Unbreaking III, Frostbite V, *Smite V* | **Frost Ward VI** |
| 6 | Putrescent Knight | Cerulean Coast | Veiled Coastkeeper Raiment (netherite chestplate; flow trim) | Mending, Projectile Protection V, Unbreaking III, Frost Ward VI | **Frost Ward VI** |
| 6 | Death Rite Bird (Charo's Hidden Grave) | Charo's Hidden Grave | Veiled Coastkeeper Boots (netherite boots; flow trim) | Mending, Projectile Protection V, Depth Strider III, Feather Falling IV, Unbreaking III, Frost Ward VI | **Frost Ward VI** |
| 6 | Lamenter | Charo's Hidden Grave | Lamenter's Maul (mace) | Mending, Density V, Unbreaking III, *Wind Burst III*, +Knockback II | Random (tier 6) |
| 6 | Ancient Dragon-Man | Gravesite Plain | Ancient Dragon-Man's Blade (netherite sword) | Mending, Sharpness VI, Unbreaking III, *Knockback II* | Random (tier 6) |
| 6 | Chief Bloodfiend | Gravesite Plain | Nameless Oath Boots (netherite boots; silence trim) | Mending, Protection V, Depth Strider III, Feather Falling IV, Unbreaking III, Bleed Ward VI | **Bleed Ward VII** (the only VII) |
| 6 | Death Knight (Gravesite Plain) | Gravesite Plain | Death Knight's Cleaver (netherite axe) | Mending, Sharpness VI, Unbreaking III, Lightning V, *Knockback II* | **Storm Ward VI** |
| 6 | Demi-Human Swordmaster Onze | Gravesite Plain | Nameless Oath Greaves (netherite leggings; silence trim) | Mending, Protection V, Unbreaking III | Random (tier 6) |
| 6 | Divine Beast Dancing Lion (Gravesite Plain) | Gravesite Plain | Divine Beast Dancing Lion's Cleaver (netherite axe) | Mending, Sharpness VI, Unbreaking III, Frostbite V, *Knockback II* | **Frost Ward VI** |
| 6 | Ghostflame Dragon (Gravesite Plain) | Gravesite Plain | Ghostflame Dragon's Cleaver (netherite axe) | Mending, Sharpness VI, Unbreaking III, Frostbite V, *Knockback II*, +Efficiency V | **Frost Ward VI** |
| 6 | Knight of the Solitary Gaol | Gravesite Plain | Nameless Oath Helm (netherite helmet; silence trim) | Mending, Protection V, Respiration III, Unbreaking III | Random (tier 6) |
| 6 | Red Bear | Gravesite Plain | Red Bear's Cleaver (netherite axe) | Mending, Sharpness VI, Unbreaking III, *Knockback II*, +Efficiency V | Random (tier 6) |
| 6 | Rellana, Twin Moon Knight | Gravesite Plain | Nameless Oath Raiment (netherite chestplate; silence trim) | Mending, Protection V, Unbreaking III, Glintstone Ward VI | **Glintstone Ward VII** (the only VII) |
| 6 | Jagged Peak Drake | Jagged Peak | Jagged Peak Drake's Cleaver (netherite axe) | Mending, Sharpness VI, Unbreaking III, Flame (element) V, *Fire Aspect II* | **Flame Ward VI** |
| 6 | Jagged Peak Drake Duo | Jagged Peak | Jagged Peak Drake Duo's Cleaver (netherite axe) | Mending, Sharpness VI, Unbreaking III, Flame (element) V, *Fire Aspect II*, +Efficiency V | **Flame Ward VI** |
| 6 | Death Knight (Rauh Base) | Rauh Base | Death Knight's Cleaver (netherite axe) | Mending, Sharpness VI, Unbreaking III, Lightning V, *Knockback II* | **Storm Ward VI** |
| 6 | Rugalea the Great Red Bear | Rauh Base | Rugalea the Great Red Bear's Cleaver (netherite axe) | Mending, Sharpness VI, Unbreaking III, *Knockback II*, +Smite V | Random (tier 6) |
| 6 | Black Knight Edreed | Scadu Altus | Black Knight Edreed's Blade (netherite sword) | Mending, Sharpness VI, Unbreaking III, Sacred V, *Sweeping Edge III* | **Sacred Ward VI** |
| 6 | Black Knight Garrew | Scadu Altus | Black Knight Garrew's Maul (mace) | Mending, Density V, Unbreaking III, Sacred V, *Wind Burst III* | **Sacred Ward VI** |
| 6 | Count Ymir, Mother of Fingers | Scadu Altus | Count Ymir, Mother of Fingers's Maul (mace) | Mending, Density V, Unbreaking III, Glintstone V, *Wind Burst III* | **Glintstone Ward VI** |
| 6 | Curseblade Labirith | Scadu Altus | Curseblade Labirith's Blade (netherite sword) | Mending, Sharpness VI, Unbreaking III, Bloodletting V, *Sweeping Edge III* | **Bleed Ward VI** |
| 6 | Dryleaf Dane | Scadu Altus | Dryleaf Dane's Maul (mace) | Mending, Density V, Unbreaking III, *Wind Burst III* | Random (tier 6) |
| 6 | Ghostflame Dragon (Scadu Altus) | Scadu Altus | Ghostflame Dragon's Cleaver (netherite axe) | Mending, Sharpness VI, Unbreaking III, Frostbite V, *Knockback II*, +Efficiency V | **Frost Ward VI** |
| 6 | Rakshasa | Scadu Altus | Rakshasa's Blade (netherite sword) | Mending, Sharpness VI, Unbreaking III, Flame (element) V, *Fire Aspect II* | **Flame Ward VI** |
| 6 | Ralva the Great Red Bear | Scadu Altus | Ralva the Great Red Bear's Cleaver (netherite axe) | Mending, Sharpness VI, Unbreaking III, *Knockback II* | Random (tier 6) |
| 7 | Jori, Elder Inquisitor | Abyssal Woods | Jori, Elder Inquisitor's Longbow (bow) | Power VII, Mending, Unbreaking III, *Infinity* | Random (tier 7) |
| 7 | Midra, Lord of Frenzied Flame | Abyssal Woods | Eclipse Sovereign Helm (netherite helmet; spire trim) | Mending, Protection VI, Respiration III, Unbreaking III, Flame Ward VI | **Flame Ward VI** |
| 7 | Divine Beast Dancing Lion (Ancient Ruins of Rauh) | Ancient Ruins of Rauh | Divine Beast Dancing Lion's Cleaver (netherite axe) | Mending, Sharpness VII, Unbreaking III, Frostbite V, *Knockback II* | **Frost Ward VII** (the only VII) |
| 7 | Romina, Saint of the Bud | Ancient Ruins of Rauh | Ashen Crucible Greaves (netherite leggings; rib trim) | Mending, Protection VI, Unbreaking III, Rot Ward VI | **Rot Ward VII** (the only VII) |
| 7 | Promised Consort Radahn | Enir-Ilim | Eclipse Sovereign Raiment (netherite chestplate; spire trim) | Mending, Protection VI, Unbreaking III, Sacred Ward VI | **Sacred Ward VII** (the only VII) |
| 7 | Ancient Dragon Senessax | Jagged Peak | Ancient Dragon Senessax's Lance (netherite spear) | Mending, Sharpness VII, Unbreaking III, Lightning V, *Lunge III* | **Storm Ward VI** |
| 7 | Bayle, the Dread | Jagged Peak | Eclipse Sovereign Boots (netherite boots; spire trim) | Mending, Protection VI, Depth Strider III, Feather Falling IV, Unbreaking III, Storm Ward VI | **Storm Ward VII** (the only VII) |
| 7 | Metyr, Mother of Fingers | Scadu Altus | Eclipse Sovereign Greaves (netherite leggings; spire trim) | Mending, Protection VI, Unbreaking III, Glintstone Ward VI | **Glintstone Ward VI** |
| 7 | Commander Gaius | Scaduview | Ashen Crucible Helm (netherite helmet; rib trim) | Mending, Protection VI, Respiration III, Unbreaking III, Glintstone Ward VI | **Glintstone Ward VI** |
| 7 | Fallingstar Beast (Scaduview) | Scaduview | Fallingstar Beast's Delver (netherite pickaxe) | Efficiency VII, Mending, Fortune III, Unbreaking III | **Glintstone Ward VI** |
| 7 | Tree Sentinel (Ambush) | Scaduview | Tree Sentinel's Lance (netherite spear) | Mending, Sharpness VII, Unbreaking III, Sacred V, *Lunge III* | **Sacred Ward VI** |
| 7 | Tree Sentinel (Exposed) | Scaduview | Tree Sentinel's Lance (netherite spear) | Mending, Sharpness VII, Unbreaking III, Sacred V, *Lunge III* | **Sacred Ward VI** |
| 7 | Golden Hippopotamus | Shadow Keep | Golden Hippopotamus's Maul (mace) | Mending, Density V, Unbreaking III, *Wind Burst III* | Random (tier 7) |
| 7 | Messmer the Impaler | Shadow Keep | Ashen Crucible Raiment (netherite chestplate; rib trim) | Mending, Protection VI, Unbreaking III, Flame Ward VI | **Flame Ward VII** (the only VII) |
| 7 | Scadutree Avatar | Shadow Keep | Ashen Crucible Boots (netherite boots; rib trim) | Mending, Protection VI, Depth Strider III, Feather Falling IV, Unbreaking III, Sacred Ward VI | **Sacred Ward VI** |

## Random book (bosses without a ward)

One enchantment: two times in three one of the list below, otherwise a weapon element or status
(Glintstone, Flame, Lightning, Sacred, Bloodletting, Frostbite, Venom or Scarlet Rot) at the level shown. From tier 5, Mending
can replace the list pick (1 in 10 at tier 5, 1 in 4 above).

| Tier | Protection | Sharpness / Power / Efficiency | Unbreaking / Fortune / Respiration | Feather Falling | Weapon element |
|---|---|---|---|---|---|
| 1 | I | I | I | I | I |
| 2 | II | II | II | II | I |
| 3 | III | III | III | III | II |
| 4 | IV | IV | III | IV | III |
| 5 | IV | V | III | IV | IV |
| 6 | V | VI | III | IV | V |
| 7 | VI | VII | III | IV | V |
