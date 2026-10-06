#!/usr/bin/env python3
"""Rebuild explicit mine eligibility from the checked-in region rules, without network access."""
import json
from pathlib import Path
root=Path(__file__).resolve().parents[1]
data=root/'fabric/src/main/resources/data/eldencraft'
loot=json.loads((data/'loot_rules.json').read_text())
# Area 32 is the actual mine interior. Overworld labels mention entrances, not mining zones.
mines={key:value for key,value in loot['maps'].items() if int(key)>>24==32}
(data/'resource_rules.json').write_text(json.dumps({'version':1,'rock_bonus_percent':10,'mines':mines},indent=2)+'\n')
print(f'Generated {len(mines)} explicit mine interiors')
