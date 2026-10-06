#!/usr/bin/env python3
"""Generate the seven-tier loot data and native encounter catalog from the checked-in snapshot.
No network/game install required. Re-run after changing the mappings below, then tools/test_loot.py.
"""
import collections, json, pathlib, re
ROOT = pathlib.Path(__file__).resolve().parents[1]
DATA = ROOT / 'fabric/src/main/resources/data/eldencraft'
SOURCE = json.loads((ROOT/'tools/loot_catalog.json').read_text())

REGIONS = {
 'Limgrave':1,'Stormhill':1,'Weeping Peninsula':1,'Chapel of Anticipation':1,'Stranded Graveyard':1,
 'Stormveil Castle':2,'Liurnia':2,'Liurnia of the Lake':2,'Liurnia of the Lakes':2,'Bellum Highway':2,
 'Academy of Raya Lucaria':2,'Caria Manor':2,'Siofra River':2,'Siofra River Start':2,'Siofra River - Boss':2,'Ainsel River':2,
 'Caelid':3,'Sellia':3,'Redmane Castle':3,'Altus Plateau':3,'Capital Outskirts':3,'Nokron, Eternal City':3,
 'Nokron, Eternal City - Boss':3,'Ainsel River Main':3,'Nokstella, Eternal City':3,
 "Greyoll's Dragonbarrow":4,'Mt. Gelmir':4,'Volcano Manor':4,'Leyndell':4,'Leyndell, Royal Capital':4,
 'Subterranean Shunning-Grounds':4,'Deeproot Depths':4,'Lake of Rot':4,'Grand Cloister':4,'Ainsel River - Boss':4,'Moonlight Altar':4,
 'Southeast Mountaintops':5,'Northeast Mountaintops':5,'Southwest Mountaintops':5,'Northwest Mountaintops':5,'Southeast Caelid':3,'Southwest Caelid':3,'Northeast Caelid':4,'Northwest Caelid':3,'Northeast Altus Plateau':3,'Northwest Altus Plateau':3,'Southeast Altus Plateau':3,'Southwest Altus Plateau':3,'Northwest Liurnia':2,'Northeast Liurnia':2,'Southwest Liurnia':2,'Southeast Liurnia':2,'Northeast Limgrave':1,'Northwest Limgrave':1,'Southeast Limgrave':1,'Southwest Limgrave':1,'Mountaintops of the Giants':5,'Flame Peak':5,'Forbidden Lands':5,'Consecrated Snowfield':5,
 "Miquella's Haligtree":5,"Miquella's Haligtree - Elphael":5,'Crumbling Farum Azula':5,
 'Mohgwyn Palace':5,'Leyndell, Ashen Capital':5,'Leyndell, Capital of Ash':5,'Stone Platform':5,
 'Gravesite Plain':6,'Belurat, Tower Settlement':6,'Castle Ensis':6,'Scadu Altus':6,'Rauh Base':6,
 'Cerulean Coast':6,"Charo's Hidden Grave":6,'Stone Coffin Fissure':6,'Finger Ruins of Rhia':6,
 'Finger Ruins of Dheo':6,'Foot of the Jagged Peak':6,'Jagged Peak':6,
 'Shadow Keep':7,'Specimen Storehouse - Shadow Tower':7,'Shadow Keep - West Rampart':7,
 'Scaduview':7,'Ancient Ruins of Rauh':7,'Abyssal Woods':7,"Midra's Manse":7,'Enir-Ilim':7,'Finger Birthing Grounds':7,
}
# Independent interior maps; no inference from HP or the entrance the player last used.
INTERIORS = {
 'm30_00':1,'m30_01':1,'m30_02':1,'m30_03':2,'m30_04':1,'m30_05':2,'m30_06':2,
 'm30_07':3,'m30_08':3,'m30_09':4,'m30_10':3,'m30_11':1,'m30_12':3,'m30_13':3,
 'm30_14':3,'m30_15':3,'m30_16':3,'m30_17':5,'m30_18':5,'m30_19':5,'m30_20':5,
 'm31_00':1,'m31_01':1,'m31_02':1,'m31_03':1,'m31_04':2,'m31_05':2,'m31_06':2,
 'm31_07':4,'m31_09':4,'m31_10':4,'m31_11':3,'m31_12':5,'m31_15':1,'m31_17':1,
 'm31_18':3,'m31_19':3,'m31_20':3,'m31_21':3,'m31_22':5,
 'm32_00':1,'m32_01':1,'m32_02':2,'m32_04':3,'m32_05':3,'m32_07':3,'m32_08':3,'m32_11':5,
 'm34_10':2,'m34_11':2,'m34_12':3,'m34_13':4,'m34_14':4,'m34_15':5,'m35_00':4,'m39_20':2,
 'm40_00':6,'m40_01':6,'m40_02':7,'m41_00':6,'m41_01':6,'m41_02':6,
 'm42_00':6,'m42_02':6,'m42_03':6,'m43_00':6,'m43_01':6,
 'm45_00':4,'m45_01':3,'m45_02':1,
}

def region_tier(location, mp=''):
    if mp[:6] in INTERIORS: return INTERIORS[mp[:6]]
    for name in sorted(REGIONS, key=len, reverse=True):
        if location == name or location.startswith(name+' - '): return REGIONS[name]
    if mp.startswith('m61'): return 6
    return 1

def block(mp): return int.from_bytes(bytes(int(x) for x in mp[1:].split('_')), 'big')

# Four stable encounters per collection, ordered helmet/chest/legs/boots.
SETS = [
 (1,"Wayfarer's Hide",[31030800,31150800,30020800,31170800],'general'),
 (1,'Morne Wanderer',[30000800,1043300800,32000800,1042330800],'projectile'),
 (2,'Stormgate Vanguard',[10000850,10000800,1042370800,1042360800],'general'),
 (2,'Carian Lakeguard',[14000850,14000800,1035500800,32020800],'projectile'),
 (3,'Redmane Exile',[1049390850,1049380800,31200800,32070800],'fire'),
 (3,'Eternal Pathfinder',[12020850,12090800,12020800,12010800],'general'),
 (3,'Amber Roadwarden',[1038510800,1039540800,30080800,1041500800],'blast'),
 (4,'Gilded Omenward',[11000850,11000800,35000800,1045520800],'general'),
 (4,'Cinder Pilgrim',[1037530800,16000800,16000850,1036540800],'fire'),
 (4,'Duskbound Seeker',[12030850,12040800,1033420800,1034420800],'projectile'),
 (5,'Winterbound Sentinel',[1051570800,1252520800,15000850,1050560800],'fire'),
 (5,'Bloodroot Sovereign',[12050800,15000800,1050570850,30190800],'general'),
 (5,'Last Age Champion',[13000850,13000800,13000830,19000800],'blast'),
 (6,'Nameless Oath',[2046410800,2048440800,41000800,43000800],'general'),
 (6,'Veiled Coastkeeper',[2046400800,22000800,2046380800,2047390800],'projectile'),
 (7,'Ashen Crucible',[2049480800,21010800,2044450800,2050480800],'fire'),
 (7,'Eclipse Sovereign',[28000800,20010800,25000800,2054390800],'general'),
]
EXCEPTIONS={1252380800:4,12040800:4,12090800:3,12020800:3,12010800:3,
            12020850:3,12030800:4,12030850:4,12030390:4,12050800:5,
            12010850:4,12020830:2,12080800:2,2054390850:7}

FAMILIES = ['beast','soldier','archer','miner','magic','undead','plant','dragon','stone','unknown']
def family(name):
    n=name.lower()
    if any(x in n for x in ['dummy','two fingers','corpse of','defeated']): return 'unknown'
    if any(x in n for x in ['dragon','wyrm']): return 'dragon'
    if any(x in n for x in ['miner','smith golem']): return 'miner'
    if any(x in n for x in ['archer','marionette','avionette']): return 'archer'
    if any(x in n for x in ['sorcerer','battlemage','scholar','oracle','shaman','inquisitor','graven','clayman','lamprey']): return 'magic'
    if any(x in n for x in ['skeleton','shade','corpse','death rite','gravebird']): return 'undead'
    if any(x in n for x in ['miranda','slug','squirt','rot','wormface','putrid flesh','jar innards']): return 'plant'
    if any(x in n for x in ['crystalian','golem','watchdog','fallingstar','sentry stone','living jar','abductor','chariot']): return 'stone'
    if any(x in n for x in ['wolf','stray','rat','bear','crab','bat','hawk','ant','octopus','fingercreeper','dog','crow','basilisk','scorpion','snail','jellyfish','scarab','eel','hippopotamus','fly']): return 'beast'
    if any(x in n for x in ['soldier','knight','militia','sellsword','noble','highwayman','misbegotten','demi-human','beastman','duelist','omen','nox','guardian','monk','prelate','perfumer','commoner','bloodfiend','curseblade','warrior','grafted','troll','godskin','assassin','cavalry','bell bearing','commander','imp','albinauric','follower','pumpkin']): return 'soldier'
    return 'unknown'

MATERIALS={
 'beast':[('leather',5),('bone',4),('beef',2)],
 'soldier':[('iron_nugget',6),('string',3),('copper_ingot',2)],
 'archer':[('arrow',6),('string',3),('feather',2)],
 'miner':[('cobblestone',5),('coal',4),('raw_copper',3)],
 'magic':[('lapis_lazuli',4),('paper',4),('amethyst_shard',2)],
 'undead':[('bone',6),('string',4)],
 'plant':[('brown_mushroom',4),('red_mushroom',4),('moss_block',2)],
 'dragon':[('bone',5),('leather',5),('coal',2)],
 'stone':[('cobblestone',6),('coal',3),('raw_copper',2)],
 'unknown':[('cobblestone',1)],
}
SUPPLIES={
 'beast':['beef','leather'],'soldier':['bread','cooked_beef','arrow','coal'],
 'archer':['arrow','flint','string'],'miner':['coal','oak_log'],
 'magic':['lapis_lazuli','book'],'undead':['bone','arrow'],'plant':['brown_mushroom','red_mushroom'],
 'dragon':['gold_ingot','coal'],'stone':['coal','raw_copper'],'unknown':['coal'],
}

def write(path,value):
    path.parent.mkdir(parents=True,exist_ok=True)
    path.write_text(json.dumps(value,indent=2,ensure_ascii=False)+'\n')

def item(name, weight=1, count=None, worn=False):
    e={'type':'minecraft:item','name':'minecraft:'+name,'weight':weight}
    if count: e['modifier']={'type':'minecraft:set_count','count':{'type':'minecraft:uniform','min':count[0],'max':count[1]}}
    if worn: e['modifier']={'type':'minecraft:set_damage','damage':{'type':'minecraft:uniform','min':0.3,'max':0.7}}
    return e

def table(path,pools):write(DATA/'loot_table'/f'{path}.json',{'type':'minecraft:chest','pools':pools,'random_sequence':'eldencraft:'+path})

def pool(entries,chance=None):
    p={'rolls':1,'entries':entries}
    if chance is not None:p['conditions']=[{'condition':'minecraft:random_chance','chance':chance}]
    return p

maps={str(block(mp)):{'name':label,'tier':region_tier(label,mp)} for mp,label in SOURCE['maps'].items()}
# Region-sensitive placements and shared underground maps use the placement's own labels.
placements={}; npcs=collections.defaultdict(collections.Counter)
for e in SOURCE['enemies']:
    t=region_tier(e['location'],e['map'])
    if e['map']=='m12_02_00_00' and e['npc']%100 in (64,65): t=3
    if e['map']=='m12_01_00_00' and e['npc']%100==62 and t==2:t=3
    if e['entity']:placements[str(e['entity'])]=t
    npcs[e['npc']][t]+=1
npc_tiers={str(n):ts.most_common(1)[0][0] for n,ts in npcs.items() if n%100!=0}
models={m:{'name':n,'family':family(n)} for m,n in SOURCE['models'].items()}
# Hostile Tarnished use their NpcParam name, not the shared player model c0000.
npc_families={str(e['npc']):family(e['name']) for e in SOURCE['enemies'] if e['model']==0}
roles={'soldier':'fighter','archer':'ranged','miner':'tools','undead':'fighter','magic':'magic'}

bosses=[]
for b in SOURCE['bosses']:
    # Match actor/group: completion flags usually share the placed actor ID or its 5800 group.
    flag=b['flag']; actual=flag-200_000_000 if 1_200_000_000 <= flag < 1_300_000_000 else flag; prefix=actual//10000
    matches=[e for e in SOURCE['enemies'] if flag <= e['entity'] < flag+50 or actual <= e['entity'] < actual+50 or flag+5000 in e.get('groups',[]) or actual+5000 in e.get('groups',[])]
    if not matches:
        matches=[e for e in SOURCE['enemies'] if e['entity']//10000==prefix and 800<=e['entity']%10000<900]
    if not matches:
        target=3150 if 'Cavalry' in b['name'] else 4980 if 'Death' in b['name'] and 'bird' in b['name'].lower() else 4950 if 'Mariner' in b['name'] else -1
        matches=[e for e in SOURCE['enemies'] if e['entity']//10000==prefix and e['model']==target]
    t=region_tier(b['region'])
    if b['region']=='Underground':
        t={1201:2,1202:3,1203:4,1204:4,1205:5,1208:2,1209:3}.get(prefix,3)
    if b['region']=='Jagged Peak':t=7 if flag in (2054390800,2054390850) else 6
    if b['region']=='Greyoll\'s Dragonbarrow' and flag in (1049390850,31200800):t=3
    t=EXCEPTIONS.get(flag,t)
    primary=matches[0] if matches else None
    fam=family(primary['name'] if primary else b['name'])
    signature='axe' if fam in ('beast','dragon','plant') else 'sword'
    if fam in ('miner','stone'):signature='pickaxe'
    if fam=='magic':signature='bow'
    reward={'slot':signature,'name':b['name'].split(' (')[0]+"'s "+{'axe':'Cleaver','sword':'Blade','pickaxe':'Delver','bow':'Longbow'}[signature],'theme':'general'}
    for tier,setname,flags,theme in SETS:
        if flag in flags:
            t=tier;slot=['helmet','chestplate','leggings','boots'][flags.index(flag)]
            reward={'slot':slot,'name':setname+' '+{'helmet':'Helm','chestplate':'Raiment','leggings':'Greaves','boots':'Boots'}[slot],'set':setname,'theme':theme}
    actor_ids=sorted(set(e['entity'] for e in matches if e['entity'] != 0))
    # Radagon is a phase of the Elden Beast encounter, not another boss reward.
    if flag==19000800:actor_ids.append(19000810)
    if flag==14000800:actor_ids.append(14000801)
    if flag==13000850:actor_ids.extend([13000851,13000852,13000853,13000854])
    bosses.append(dict(flag=flag,name=b['name'],region=b['region'],tier=t,reward=reward,actors=sorted(set(actor_ids)),
                       map=block(primary['map']) if primary else 0,model=primary['model'] if primary else 0,npc=primary['npc'] if primary else 0))

# Important bosses missing from the public respawn list: exact placed identity/completion flag.
extra=[('Spiritcaller Cave completion',31220800)]
# This one is already in the catalog; assertion guards an accidental duplicate.
assert len({b['flag'] for b in bosses})==len(bosses)
assert all(sum(flag in [b['flag'] for b in bosses] for flag in row[2])==4 for row in SETS)

for t in range(1,8):
    lo,hi=[(1,2),(1,3),(2,4),(2,5),(3,6),(3,7),(4,8)][t-1]
    for fam in FAMILIES:
        entries=list(MATERIALS[fam])
        if t>=2 and fam in ('soldier','miner','stone'):entries.append(('iron_ingot' if fam=='soldier' else 'raw_iron',3))
        if t>=3 and fam in ('soldier','miner','stone','dragon'):entries.append(('gold_ingot',2))
        guaranteed=pool([item(n,w,(lo,hi)) for n,w in entries])
        supplies=pool([item(n,count=(1,3)) for n in SUPPLIES[fam]],0.25)
        table(f'enemy/{fam}/tier_{t}',[guaranteed,supplies])
    toolmat=['stone','iron','iron','diamond','netherite','netherite','netherite'][t-1]
    armormat=['leather','chainmail','iron','diamond','netherite','netherite','netherite'][t-1]
    for role in ['fighter','ranged','tools','magic']:
        gear=[]
        if role=='fighter':gear=[toolmat+'_sword',toolmat+'_axe']+[armormat+'_'+s for s in ['helmet','chestplate','leggings','boots']]
        if role=='ranged':gear=['bow','crossbow']+[armormat+'_'+s for s in ['helmet','boots']]
        if role=='tools':gear=[toolmat+'_pickaxe',toolmat+'_shovel',toolmat+'_axe']
        if role=='magic':gear=[armormat+'_helmet',armormat+'_boots']
        if t==1 and role in ('fighter','tools'):gear.append('copper_'+('sword' if role=='fighter' else 'pickaxe'))
        if t==3 and role=='fighter':gear.append('diamond_sword')
        table(f'gear/{role}/tier_{t}',[pool([item(n,worn=True) for n in gear])])
    # Boss materials and books/gear are separate guaranteed rewards.
    main=['copper_ingot','iron_ingot','iron_ingot','diamond','netherite_scrap','netherite_ingot','netherite_ingot'][t-1]
    count=[(4,8),(6,12),(10,18),(2,4),(2,4),(1,2),(2,3)][t-1]
    p=[pool([item(main,count=count)])]
    if t>=5:p.append(pool([item('netherite_upgrade_smithing_template')]))
    if t>=3:p.append(pool([item('gold_ingot',count=(2,5))]))
    p.append(pool([item('cooked_beef',count=(3,6)),item('golden_carrot',count=(2,4)),item('experience_bottle',count=(3,6))],0.5))
    table(f'boss/tier_{t}',p)
# Remove V1 generic/model tables so they cannot accidentally restore unrelated drops.
for p in (DATA/'loot_table/enemy').glob('tier_*.json'):p.unlink()
for p in (DATA/'loot_table/enemy/model').glob('*.json'):p.unlink()
rules=dict(version=2,sources=SOURCE['sources'],maps=maps,placement_tiers=placements,npc_tiers=npc_tiers,
           models=models,npc_families=npc_families,roles=roles,bosses=bosses)
write(DATA/'loot_rules.json',rules)
# Native only needs completion flags and actor suppression; rewards remain data-driven in Fabric.
rs=['// Generated by tools/generate_loot.py. Do not edit by hand.',
    'pub struct Boss { pub flag: u32, pub map: u32, pub model: u32, pub npc: i32, pub actors: &\'static [u32] }',
    'impl Boss {\n    pub fn matches(&self, entity: u32, map: u32, npc: i32) -> bool {\n        (entity != 0 && self.actors.contains(&entity))\n            || (entity == 0 && self.npc != 0 && self.npc == npc && self.map == map)\n    }\n}',
    'pub static BOSSES: &[Boss] = &[']
for b in bosses:rs.append(f'    Boss {{ flag: {b["flag"]}, map: {b["map"]}, model: {b["model"]}, npc: {b["npc"]}, actors: &{b["actors"]} }},')
rs.append('];')
(ROOT/'game/src/loot_catalog.rs').write_text('\n'.join(rs)+'\n')
# Human-readable complete reward sheet, kept in sync with runtime data.
lines=['# Implemented boss rewards', '', 'Generated by `tools/generate_loot.py`. These are EldenCraft rewards, not original ER drops.',
       'All rows also grant a curated enchanted book and regional materials. Named gear has full durability.',
       'Completion flags and actor mappings come from the checked-in public-data snapshot; in-game confirmation is pending.', '',
       '| Region | Boss | Tier | Guaranteed gear | Collection |', '|---|---|---|---|---|']
for b in sorted(bosses,key=lambda b:(b['tier'],b['region'],b['name'])):
    r=b['reward']
    lines.append(f"| {b['region']} | {b['name']} | {b['tier']} | {r['name']} | {r.get('set','Signature')} |")
(ROOT/'docs/loot-boss-rewards.md').write_text('\n'.join(lines)+'\n')
print(f'Generated {len(maps)} region maps, {len(models)} enemy families, {len(bosses)} bosses, {len(SETS)*4} fixed set pieces')
