#!/usr/bin/env python3
"""Import public ER facts without executing downloaded Python. Normal build uses the saved snapshot.
Usage: python3 tools/import_loot_reference.py /tmp/eldencraft-loot-reference
Sources: soulsmods.github.io/data/er/entities.html and Hapfel1/er-save-manager boss_data.py.
"""
import ast, collections, html, json, pathlib, re, sys
root = pathlib.Path(__file__).resolve().parents[1]
ref = pathlib.Path(sys.argv[1])
module = ast.parse((ref / 'boss_data.py').read_text())
bosses = next(ast.literal_eval(n.value) for n in module.body if isinstance(n, ast.Assign)
              and any(isinstance(t, ast.Name) and t.id == 'BOSSES' for t in n.targets))
rows, maps, models = [], collections.defaultdict(collections.Counter), collections.defaultdict(collections.Counter)
for line in html.unescape((ref / 'entities.html').read_text()).splitlines():
    m = re.search(r'(m\d+_\d+_\d+_\d+) .*?\((.*?)\)', line)
    if m and m[1].endswith('_00'): maps[m[1]][m[2]] += 1
    m = re.search(r'(\d+): (m\d+_\d+_\d+_\d+) (?:((?:m\d+_\d+_\d+_\d+))-)?(c\d+)_\d+ \((.*?)\) Enemy \((.*?)\).*?npc (\d+)', line)
    if m:
        entity, mp, local_map, model, location, name, npc = m.groups()
        mp = local_map or mp
        if mp.endswith('_00'): maps[mp][location] += 1
        models[int(model[1:])][name] += 1
        rows.append(dict(entity=int(entity), map=mp, model=int(model[1:]), location=location, name=name, npc=int(npc), groups=[int(g) for g in re.findall(r'group ([0-9,]+)',line)[0].split(',')] if 'group ' in line else []))
# The respawn list includes the first Lansseax appearance. The actual final arena uses this flag.
bosses['Ancient Dragon Lansseax']['flags'][0] = 1041520800
# Patches' encounter completion must work on surrender, not require murder.
bosses['Patches (Murkwater Cave)'] = {'flags': [31000800], 'category': 'Limgrave'}
source = dict(sources=['https://soulsmods.github.io/data/er/entities.html',
                      'https://github.com/Hapfel1/er-save-manager/blob/main/src/er_save_manager/data/boss_data.py',
                      'https://soulsmods.github.io/elden-ring-eventparam/'],
              maps={mp: counts.most_common(1)[0][0] for mp, counts in sorted(maps.items())},
              models={str(m): names.most_common(1)[0][0] for m, names in sorted(models.items())},
              enemies=rows, bosses=[dict(name=n, region=b['category'], flag=b['flags'][0]) for n,b in bosses.items()])
(root / 'tools/loot_catalog.json').write_text(json.dumps(source, separators=(',', ':'), ensure_ascii=False)+'\n')
print(f'Imported {len(rows)} placements, {len(maps)} maps, {len(models)} models, {len(bosses)} encounters')
