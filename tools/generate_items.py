#!/usr/bin/env python3
"""Generates EldenCraft's Elden Ring item data from the game's own params.

Usage: tools/generate_items.py [ELDEN RING folder]   (or set ELDEN_RING)

Reads regulation.bin through tools/er_params.py and writes compact JSON under
fabric/src/main/resources/data/eldencraft/er/: every weapon (with its infusions), armour piece,
talisman, goods item, spell and Ash of War, plus the tables the Minecraft side needs to compute
attack rating, upgrades, absorption and effects exactly as Elden Ring does.
"""
import json
import os
import re
import sys
from collections import Counter
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
from er_params import Regulation  # noqa: E402

ROOT = Path(__file__).resolve().parents[1]
OUT = ROOT / "fabric/src/main/resources/data/eldencraft/er"
DEFAULT_GAME = "/run/media/alex/a0da83cf-b021-4cfe-9104-edb6dd726a80/SteamLibrary/steamapps/common/ELDEN RING"

STATS = ["Strength", "Agility", "Magic", "Faith", "Luck"]  # Elden Ring's internal names for STR DEX INT FAI ARC
ELEMENTS = ["Physics", "Magic", "Fire", "Thunder", "Dark"]
INFUSIONS = ["", "Heavy", "Keen", "Quality", "Fire", "Flame Art", "Lightning", "Sacred", "Magic", "Cold",
             "Poison", "Blood", "Occult"]

# Elden Ring weapon type -> (Minecraft behaviour, art). Behaviours: melee, bow, crossbow, shield,
# staff, seal, ammo, torch, throw.
WEAPON_TYPES = {
    1: ("melee", "dagger"), 3: ("melee", "straight_sword"), 5: ("melee", "greatsword"), 7: ("melee", "colossal_sword"),
    9: ("melee", "curved_sword"), 11: ("melee", "curved_greatsword"), 13: ("melee", "katana"), 14: ("melee", "twinblade"),
    15: ("melee", "thrusting_sword"), 16: ("melee", "heavy_thrusting_sword"), 17: ("melee", "axe"), 19: ("melee", "greataxe"),
    21: ("melee", "hammer"), 23: ("melee", "great_hammer"), 24: ("melee", "flail"), 25: ("melee", "spear"),
    28: ("melee", "great_spear"), 29: ("melee", "halberd"), 31: ("melee", "reaper"), 35: ("melee", "fist"),
    37: ("melee", "claw"), 39: ("melee", "whip"), 41: ("melee", "colossal_weapon"), 50: ("bow", "light_bow"),
    51: ("bow", "bow"), 53: ("bow", "greatbow"), 55: ("crossbow", "crossbow"), 56: ("crossbow", "ballista"),
    57: ("staff", "staff"), 61: ("seal", "seal"), 65: ("shield", "small_shield"), 67: ("shield", "medium_shield"),
    69: ("shield", "greatshield"), 81: ("ammo", "arrow"), 83: ("ammo", "greatarrow"), 85: ("ammo", "bolt"),
    86: ("ammo", "greatbolt"), 87: ("torch", "torch"), 88: ("melee", "fist"), 89: ("throw", "perfume_bottle"),
    90: ("shield", "thrusting_shield"), 91: ("melee", "dagger"), 92: ("melee", "backhand_blade"),
    93: ("melee", "light_greatsword"), 94: ("melee", "great_katana"), 95: ("melee", "claw"), 0: ("throw", "throwing"),
}
CLASS_NAMES = {
    1: "Dagger", 3: "Straight Sword", 5: "Greatsword", 7: "Colossal Sword", 9: "Curved Sword", 11: "Curved Greatsword",
    13: "Katana", 14: "Twinblade", 15: "Thrusting Sword", 16: "Heavy Thrusting Sword", 17: "Axe", 19: "Greataxe",
    21: "Hammer", 23: "Great Hammer", 24: "Flail", 25: "Spear", 28: "Great Spear", 29: "Halberd", 31: "Reaper",
    35: "Fist", 37: "Claw", 39: "Whip", 41: "Colossal Weapon", 50: "Light Bow", 51: "Bow", 53: "Greatbow",
    55: "Crossbow", 56: "Ballista", 57: "Glintstone Staff", 61: "Sacred Seal", 65: "Small Shield", 67: "Medium Shield",
    69: "Greatshield", 81: "Arrow", 83: "Greatarrow", 85: "Bolt", 86: "Greatbolt", 87: "Torch", 88: "Hand-to-Hand Art",
    89: "Perfume Bottle", 90: "Thrusting Shield", 91: "Throwing Blade", 92: "Backhand Blade", 93: "Light Greatsword",
    94: "Great Katana", 95: "Beast Claw", 0: "Tool",
}
GOODS_TYPES = {0: "consumable", 1: "key", 2: "material", 3: "remembrance", 5: "sorcery", 7: "spirit", 8: "spirit",
               9: "physick", 10: "tear", 11: "pot", 12: "info", 13: "key", 14: "upgrade", 15: "great_rune",
               16: "incantation", 17: "sorcery", 18: "incantation"}

# Colour words in names, for tinting the generated art.
COLOURS = [
    (r"crimson|blood|red|scarlet|flame|fire|magma|lava|hot|ember|bloody|mohg", 0xB8312F),
    (r"cerulean|blue|glintstone|carian|azur|lazuli|moon|sorcer|magic", 0x3F6FD8),
    (r"frost|cold|ice|snow|zamor|chill|freez", 0x9ED8F0),
    (r"gold|golden|erdtree|sacred|holy|godfrey|radiant|sun|leyndell|lordsworn|morgott|marika", 0xE3B341),
    (r"lightning|storm|thunder|dragon|ancient dragon|lansseax|fortissax", 0xE8D85A),
    (r"rot|poison|venom|malenia|cleanrot|kindred|toxic|pest", 0x8E9E2F),
    (r"black|night|death|godskin|noir|dark|shadow|knife|maliketh|nox|occult|abyss", 0x2E2B33),
    (r"silver|white|pure|cuckoo|banished|knight", 0xC9CCD3),
    (r"bronze|copper|rust|rusted|brass|beast|beastman|bull|goat|old", 0x9A6A3A),
    (r"crystal|gem|azure", 0x7FD6D9),
    (r"bone|ivory|pale|ash", 0xD8CDAE),
    (r"leaf|tree|wood|wooden|green|grass|bush|root|herb|flower", 0x5E8A3A),
    (r"purple|violet|frenzy|madness|yellow flame|three fingers|frenzied", 0x8A45B5),
]
INFUSION_COLOURS = {4: 0xC2482B, 5: 0xD8733A, 6: 0xE8D85A, 7: 0xE3B341, 8: 0x3F6FD8, 9: 0x9ED8F0, 10: 0x8E9E2F,
                    11: 0x9E1F2E, 12: 0x6B5A8A}


def colour(name: str, default: int) -> int:
    lower = name.lower()
    for pattern, rgb in COLOURS:
        if re.search(rf"\b({pattern})", lower):
            return rgb
    return default


def keep(name: str | None) -> bool:
    return bool(name) and not name.startswith("[NPC]") and not name.startswith("[ERROR]") and "(Unused)" not in name


def nonzero(d: dict) -> dict:
    return {k: v for k, v in d.items() if v not in (0, 0.0, None, [], "")}


def write(name: str, value) -> None:
    OUT.mkdir(parents=True, exist_ok=True)
    path = OUT / f"{name}.json"
    path.write_text(json.dumps(value, separators=(",", ":"), ensure_ascii=False), encoding="utf-8")
    print(f"{path.relative_to(ROOT)}: {len(value)} entries, {path.stat().st_size // 1024} KB")


def main() -> None:
    game = sys.argv[1] if len(sys.argv) > 1 else os.environ.get("ELDEN_RING", DEFAULT_GAME)
    reg = Regulation(game)
    p = {name: reg.rows(name) for name in [
        "EquipParamWeapon", "EquipParamProtector", "EquipParamAccessory", "EquipParamGoods", "EquipParamGem",
        "ReinforceParamWeapon", "ReinforceParamProtector", "CalcCorrectGraph", "AttackElementCorrectParam",
        "SpEffectParam", "Magic", "SwordArtsParam", "CharaInitParam", "EquipMtrlSetParam", "Bullet", "AtkParam_Pc",
        "ShopLineupParam"]}
    names = {name: reg.names(name) for name in p}
    effects: set[int] = set()

    # ---- weapons --------------------------------------------------------------------------------
    weapons, material_sets, reinforce_types, graphs, element_tables = {}, set(), set(), set(), set()
    for wid, w in p["EquipParamWeapon"].items():
        name = names["EquipParamWeapon"].get(wid)
        if not keep(name) or w["wepType"] == 33 or w["wepType"] not in WEAPON_TYPES:
            continue
        behaviour, art = WEAPON_TYPES[w["wepType"]]
        infusion = (wid % 10000) // 100 if w["wepType"] < 81 or w["wepType"] > 87 else 0
        base_id = wid - (wid % 10000) if infusion < len(INFUSIONS) else wid
        sp = [w[f"spEffectBehaviorId{i}"] for i in range(3)]
        resident = [w["residentSpEffectId"], w["residentSpEffectId1"], w["residentSpEffectId2"]]
        effects.update(x for x in sp + resident if x > 0)
        material_sets.add(w["materialSetId"]); reinforce_types.add(w["reinforceTypeId"])
        element_tables.add(w["attackElementCorrectId"])
        correct_types = [w[f"correctType_{e}"] for e in ELEMENTS] + [w["correctType_Poison"], w["correctType_Blood"],
                                                                     w["correctType_Sleep"], w["correctType_Madness"]]
        graphs.update(correct_types)
        weapons[wid] = nonzero({
            "n": name, "base": base_id if base_id != wid else 0, "inf": infusion, "t": w["wepType"],
            "cls": CLASS_NAMES.get(w["wepType"], "Weapon"), "b": behaviour, "art": art,
            "tint": INFUSION_COLOURS.get(infusion) or colour(name, 0xB9BDC4),
            "w": round(w["weight"], 2),
            "atk": [w["attackBasePhysics"], w["attackBaseMagic"], w["attackBaseFire"], w["attackBaseThunder"], w["attackBaseDark"]],
            "cor": [round(w[f"correct{s}"], 2) for s in STATS],
            "req": [w[f"proper{s}"] for s in STATS],
            "ct": correct_types, "rt": w["reinforceTypeId"], "aec": w["attackElementCorrectId"], "ms": w["materialSetId"],
            "sp": [x if x > 0 else 0 for x in sp], "res": [x if x > 0 else 0 for x in resident],
            "art_id": w["swordArtsParamId"] if w["swordArtsParamId"] > 0 else 0, "gem": w["gemMountType"],
            "dmg": (w["isNormalAttackType"] | w["isSlashAttackType"] << 1 | w["isBlowAttackType"] << 2 | w["isThrustAttackType"] << 3),
            "poise": round(w["saWeaponDamage"], 1), "stam": w["attackBaseStamina"],
            "guard": [round(w[f"{e}GuardCutRate"], 1) for e in ("phys", "mag", "fire", "thun", "dark")] if behaviour == "shield" or w["enableGuard"] else [],
            "stab": w["staminaGuardDef"], "dual": w["isDualBlade"], "rar": w["rarity"], "icon": w["iconId"], "sort": w["sortId"],
            "somber": 1 if w["reinforceTypeId"] in (2200, 2400, 3200, 3300, 8300, 8500) or w["materialSetId"] == 2200 else 0,
        })
    write("weapons", weapons)

    reinforce = {}
    for rid, r in p["ReinforceParamWeapon"].items():
        if rid - rid % 100 not in reinforce_types and rid not in reinforce_types:
            continue
        reinforce[rid] = {
            "atk": [round(r[k], 4) for k in ("physicsAtkRate", "magicAtkRate", "fireAtkRate", "thunderAtkRate", "darkAtkRate")],
            "cor": [round(r[k], 4) for k in ("correctStrengthRate", "correctAgilityRate", "correctMagicRate", "correctFaithRate", "correctLuckRate")],
            "sp": [r["spEffectId1"], r["spEffectId2"], r["spEffectId3"]],
            "res": [r["residentSpEffectId1"], r["residentSpEffectId2"], r["residentSpEffectId3"]],
            "ms": r["materialSetId"], "max": r["maxReinforceLevel"],
            "guard": [round(r[k], 4) for k in ("physicsGuardCutRate", "magicGuardCutRate", "fireGuardCutRate", "thunderGuardCutRate", "darkGuardCutRate")],
            "stab": round(r["staminaGuardDefRate"], 4), "poise": round(r["saWeaponAtkRate"], 4),
        }
    write("reinforce", reinforce)

    write("graphs", {gid: {"x": [g[f"stageMaxVal{i}"] for i in range(5)], "y": [g[f"stageMaxGrowVal{i}"] for i in range(5)],
                           "e": [round(g[f"adjPt_maxGrowVal{i}"], 4) for i in range(5)]}
                     for gid, g in p["CalcCorrectGraph"].items()})

    short = ["Strength", "Dexterity", "Magic", "Faith", "Luck"]
    elements = ["Physics", "Magic", "Fire", "Thunder", "Dark"]
    write("element_correct", {aid: [[a[f"is{s}Correct_by{e}"] for s in short] for e in elements]
                              for aid, a in p["AttackElementCorrectParam"].items() if aid in element_tables})

    sets = {}
    for mid, m in p["EquipMtrlSetParam"].items():
        mats = [[m[f"materialId0{i}"], m[f"itemNum0{i}"]] for i in range(1, 7)
                if m.get(f"materialId0{i}", -1) > 0 and m.get(f"itemNum0{i}", 0) > 0]
        if mats:
            sets[mid] = mats
    write("materials", sets)

    # ---- armour ---------------------------------------------------------------------------------
    armour = {}
    for aid, a in p["EquipParamProtector"].items():
        name = names["EquipParamProtector"].get(aid)
        if not keep(name):
            continue
        slot = 0 if a["headEquip"] else 1 if a["bodyEquip"] else 2 if a["armEquip"] else 3 if a["legEquip"] else -1
        if slot < 0:
            continue
        resident = [a.get(k, -1) for k in ("residentSpEffectId", "residentSpEffectId2", "residentSpEffectId3")]
        effects.update(x for x in resident if x > 0)
        armour[aid] = nonzero({
            "n": name, "slot": slot, "w": round(a["weight"], 2), "tint": colour(name, 0x8B8F96),
            # Damage negation in percent: physical, strike, slash, pierce, magic, fire, lightning, holy.
            "neg": [round((1 - a[k]) * 100, 1) for k in ("neutralDamageCutRate", "blowDamageCutRate", "slashDamageCutRate",
                    "thrustDamageCutRate", "magicDamageCutRate", "fireDamageCutRate", "thunderDamageCutRate", "darkDamageCutRate")],
            # Immunity (poison, rot), robustness (bleed, frost), focus (sleep, madness), vitality (deathblight).
            "res": [a["resistPoison"], a["resistDisease"], a["resistBlood"], a["resistFreeze"], a["resistSleep"],
                    a["resistMadness"], a["resistCurse"]],
            "poise": round(a["toughnessCorrectRate"] * 1000), "sp": [x if x > 0 else 0 for x in resident],
            "rar": a["rarity"], "icon": a["iconIdM"], "sort": a["sortId"],
        })
    write("armour", armour)

    # ---- talismans ------------------------------------------------------------------------------
    talismans = {}
    for tid, t in p["EquipParamAccessory"].items():
        name = names["EquipParamAccessory"].get(tid)
        if not keep(name):
            continue
        if t["refId"] > 0:
            effects.add(t["refId"])
        talismans[tid] = nonzero({"n": name, "w": round(t["weight"], 2), "sp": max(t["refId"], 0), "grp": t["accessoryGroup"],
                                  "tint": colour(name, 0xC9A55C), "rar": t["rarity"], "icon": t["iconId"], "sort": t["sortId"]})
    write("talismans", talismans)

    # ---- spells ---------------------------------------------------------------------------------
    bullets, attacks = p["Bullet"], p["AtkParam_Pc"]

    def projectile(start: int) -> dict:
        """A spell's bullet and the bullets it spawns on hit: the strongest attack and every SpEffect it applies."""
        best, buffs, seen, queue, speed, life, count = [0] * 5, [], set(), [start], 0.0, 0.0, 1
        while queue and len(seen) < 8:
            bid = queue.pop(0)
            if bid in seen or bid not in bullets:
                continue
            seen.add(bid)
            b = bullets[bid]
            if not speed:
                speed, life, count = b["initVellocity"], b["life"], max(1, b["numShoot"])
            a = attacks.get(b["atkId_Bullet"])
            if a:
                dmg = [a["atkPhys"], a["atkMag"], a["atkFire"], a["atkThun"], a["atkDark"]]
                if sum(dmg) > sum(best):
                    best = dmg
            for k in range(5):
                if b[f"spEffectId{k}"] > 0:
                    buffs.append(b[f"spEffectId{k}"])
            if b["spEffectIDForShooter"] > 0:
                buffs.append(b["spEffectIDForShooter"])
            if b["HitBulletID"] > 0:
                queue.append(b["HitBulletID"])
        return {"dmg": best, "fx": buffs, "speed": round(speed, 2), "life": round(life, 2), "count": count}

    spells = {}
    for sid, m in p["Magic"].items():
        name = names["Magic"].get(sid)
        if not keep(name) or not name.startswith(("[Sorcery]", "[Incantation]")):
            continue
        school = "sorcery" if name.startswith("[Sorcery]") else "incantation"
        refs = [m.get(f"refId{i}", -1) for i in range(1, 11)]
        kinds = [m.get(f"refCategory{i}", 0) for i in range(1, 11)]
        effects.update(r for r, k in zip(refs, kinds) if r > 0 and k == 2)
        shot = next((projectile(r) for r, k in zip(refs, kinds) if r > 0 and k == 1 and r in bullets), None)
        if shot:
            effects.update(shot["fx"])
        spells[sid] = nonzero({
            "n": name.split("] ", 1)[1], "school": school, "fp": m["mp"], "stam": m["stamina"], "slots": m["slotLength"],
            "req": [m["requirementIntellect"], m["requirementFaith"], m["requirementLuck"]],
            "dmg": shot["dmg"] if shot else [], "fx": shot["fx"] if shot else [],
            "speed": shot["speed"] if shot else 0, "count": shot["count"] if shot else 0, "cat": m["spEffectCategory"],
            "tint": colour(name, 0x3F6FD8 if school == "sorcery" else 0xE3B341), "icon": m["iconId"], "sort": m["sortId"],
        })
    write("spells", spells)

    # ---- goods ----------------------------------------------------------------------------------
    goods, seen = {}, {}
    for gid, g in p["EquipParamGoods"].items():
        name = names["EquipParamGoods"].get(gid)
        kind = GOODS_TYPES.get(g["goodsType"])
        if not keep(name) or kind is None:
            continue
        if name.startswith(("[Sorcery]", "[Incantation]")):
            name = name.split("] ", 1)[1]
        level = 0
        match = re.match(r"(.*) \+(\d+)$", name)
        if kind == "spirit" and match:
            level = int(match.group(2))
        ref = g["refId_default"]
        if ref > 0 and g["refCategory"] == 2:
            effects.add(ref)
        canonical = gid if 1000 <= gid <= 1075 or gid in (250, 251) else seen.setdefault(name, gid)
        # Throwables (pots, knives, darts) shoot a bullet: keep its attack.
        thrown = projectile(ref) if g["refCategory"] == 1 and ref in bullets else None
        if thrown:
            effects.update(thrown["fx"])
        goods[gid] = nonzero({
            "n": name, "k": kind, "ref": max(ref, 0), "refk": g["refCategory"], "max": g["maxNum"],
            "use": 1 if g["isConsume"] else 0, "lv": level, "same": canonical if canonical != gid else 0,
            "dmg": thrown["dmg"] if thrown and sum(thrown["dmg"]) > 0 else [], "speed": thrown["speed"] if thrown else 0,
            "tint": colour(name, 0xA8A29A), "rar": g["rarity"], "icon": g["iconId"], "sort": g["sortId"],
        })
    write("goods", goods)

    # ---- Ashes of War ---------------------------------------------------------------------------
    arts = p["SwordArtsParam"]
    ashes = {}
    for aid, g in p["EquipParamGem"].items():
        name = names["EquipParamGem"].get(aid)
        if not keep(name):
            continue
        mounts = [k[len("canMountWep_"):] for k, v in g.items() if k.startswith("canMountWep_") and v]
        art = arts.get(g["swordArtsParamId"], {})
        ashes[aid] = nonzero({
            "n": name.removeprefix("Ash of War: "), "art": g["swordArtsParamId"], "mount": mounts,
            "inf": g.get("defaultWepAttr", 0), "infs": [g.get(f"configurableWepAttr{i:02d}", 0) for i in range(24)],
            "fp": art.get("useMagicPoint_L1", 0) or art.get("useMagicPoint_R1", 0), "tint": colour(name, 0xB0B4BA),
            "icon": g["iconId"], "sort": g["sortId"],
        })
    write("ashes", ashes)
    write("arts", {aid: nonzero({"n": names["SwordArtsParam"].get(aid, ""), "fp": a.get("useMagicPoint_L1", 0) or a.get("useMagicPoint_R1", 0)})
                   for aid, a in arts.items() if names["SwordArtsParam"].get(aid)})

    # ---- merchants' shops -----------------------------------------------------------------------
    # The merchants' lineups (which merchant sells which: tools/generate_merchants.py, by Bell Bearing). Items unlocked
    # later by an event flag are left out.
    kinds = {0: 0, 1: 1, 2: 2, 3: 4, 4: 8}
    shops = {}
    for base in [100500 + 25 * k for k in range(20)]:
        items = []
        for row_id in range(base, base + 25):
            r = p["ShopLineupParam"].get(row_id)
            if not r or r["equipId"] <= 0 or r["eventFlag_forRelease"] > 0 or r["equipType"] not in kinds or r["costType"] != 0:
                continue
            items.append(nonzero({"row": row_id, "k": kinds[r["equipType"]], "id": r["equipId"], "price": max(0, r["value"]),
                                  "stock": r["sellQuantity"], "n": max(1, r["setNum"])}))
        if items:
            shops[base] = items
    write("shops", shops)

    # ---- starting classes -----------------------------------------------------------------------
    classes = {}
    for cid in range(3000, 3010):
        c = p["CharaInitParam"].get(cid)
        if not c:
            continue
        classes[cid] = {"n": names["CharaInitParam"].get(cid, "").removeprefix("Class - "), "lv": c["soulLv"],
                        "stats": [c[k] for k in ("baseVit", "baseWil", "baseEnd", "baseStr", "baseDex", "baseMag", "baseFai", "baseLuc")]}
    write("classes", classes)

    # ---- SpEffects referenced by any item (only fields that differ from the usual value) ---------
    table = p["SpEffectParam"]
    for rid, r in list(reinforce.items()):
        for sp in r["sp"] + r["res"]:
            pass
    # Weapon status effects step up with reinforcement (base id + the level's offset).
    for w in weapons.values():
        for sp in w.get("sp", []):
            if sp > 0:
                effects.update(sp + k for k in range(26) if sp + k in table)
    usual = {}
    sample = list(table.values())
    for field in sample[0]:
        usual[field] = Counter(row[field] for row in sample if not isinstance(row[field], list)).most_common(1)[0][0] \
            if not isinstance(sample[0][field], list) else None
    out = {}
    for sid in sorted(effects):
        row = table.get(sid)
        if row is None:
            continue
        out[sid] = {k: (round(v, 4) if isinstance(v, float) else v) for k, v in row.items()
                    if usual.get(k) is not None and v != usual[k] and not k.startswith(("pad", "unk", "reserve", "vfx", "dummy"))}
        if names["SpEffectParam"].get(sid):
            out[sid]["_n"] = names["SpEffectParam"][sid]
    write("speffects", out)


if __name__ == "__main__":
    main()
