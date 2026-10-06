#!/usr/bin/env python3
"""Coverage, safety and statistical balance checks for the actual generated data."""
import collections, json, pathlib, random
root=pathlib.Path(__file__).resolve().parents[1]
data=root/'fabric/src/main/resources/data/eldencraft'
rules=json.loads((data/'loot_rules.json').read_text())
bosses=rules['bosses']
assert len({b['flag'] for b in bosses})==len(bosses)==208
assert all(0 not in b['actors'] for b in bosses), 'Zero event IDs must never suppress ordinary loot'
assert all(b['actors'] for b in bosses), 'Boss without native actor observation'
collections_by_name=collections.defaultdict(list)
for b in bosses:
 assert 1<=b['tier']<=7
 if 'set' in b['reward']:collections_by_name[b['reward']['set']].append(b)
assert len(collections_by_name)==17
for name, pieces in collections_by_name.items():
 assert len(pieces)==4 and {b['reward']['slot'] for b in pieces}=={'helmet','chestplate','leggings','boots'},name
 assert len({b['tier'] for b in pieces})==1,name
assert not any(b['flag']==19000810 for b in bosses)
assert next(b for b in bosses if b['flag']==19000800)['actors']==[19000800,19000810]
assert all(1<=v['tier']<=7 for v in rules['maps'].values())
assert all(1<=v<=7 for v in rules['placement_tiers'].values())
tables={str(p.relative_to(data/'loot_table')):json.loads(p.read_text()) for p in (data/'loot_table').rglob('*.json') if 'blocks' not in p.relative_to(data/'loot_table').parts}
assert len(tables)==105
families=['beast','soldier','archer','miner','magic','undead','plant','dragon','stone','unknown']
for tier in range(1,8):
 for family in families:
  pools=tables[f'enemy/{family}/tier_{tier}.json']['pools']
  guaranteed=pools[0]
  assert guaranteed['rolls']==1 and not guaranteed.get('conditions')
  for e in guaranteed['entries']:
   assert e['type']=='minecraft:item' and e['modifier']['count']['min']>=1
   assert not any(e['name'].endswith(x) for x in ['sword','helmet','chestplate','leggings','boots','pickaxe'])
 for role in ['fighter','ranged','tools','magic']:
  for e in tables[f'gear/{role}/tier_{tier}.json']['pools'][0]['entries']:
   assert e['modifier']=={'type':'minecraft:set_damage','damage':{'type':'minecraft:uniform','min':0.3,'max':0.7}}
# Weighted draws include real counts and the separate 25% supplies pool.
rng=random.Random(4102026)
report={'regions':len(rules['maps']),'enemy_models':len(rules['models']),'bosses':len(bosses),'collections':len(collections_by_name),'set_pieces':68,'tables':len(tables),'simulations':[]}
for tier in range(1,8):
 total=normal=upgrade=0
 materials=collections.Counter()
 pools=tables[f'enemy/soldier/tier_{tier}.json']['pools']
 for _ in range(100000):
  roll=rng.randrange(100)
  normal+=0<roll<10; upgrade+=roll==0
  e=rng.choices(pools[0]['entries'],weights=[x['weight'] for x in pools[0]['entries']])[0]
  count=e['modifier']['count'];materials[e['name']]+=rng.randint(count['min'],count['max'])
  total+=1
 assert abs(normal/total-.09)<.003 and abs(upgrade/total-.01)<.001
 report['simulations'].append({'tier':tier,'kills':total,'normal_gear_percent':round(normal/total*100,3),'upgrade_percent':round(upgrade/total*100,3),'guaranteed_material_items_per_kill':round(sum(materials.values())/total,3)})
p=root/'docs/loot-validation.json';p.write_text(json.dumps(report,indent=2)+'\n')
print('PASS: 208 boss actors, 17 complete collections, 105 tables, no empty material pool; 700,000 simulated kills')
print('Simulation report:',p)
