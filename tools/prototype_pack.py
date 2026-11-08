#!/usr/bin/env python3
"""Builds the prototype campaign (app/src/prototype/assets/levels.json) from the mechanics preview pack.

Source: levelgen/prototype/mechanics-preview-levels.json (32 levels: per mechanic an intro then 3 harder ones, in
blocks of four, then 4 mixes). The prototype takes per mechanic the intro and the next two, in mechanic order, then
the mixes. Each pack entry gets a "theme" (a CafeThemes id; the app draws the level in that theme) and a "group"
(the level list's heading); both sit beside "level" and are ignored by core's LevelPackCodec.

After those 25 comes "Rebuilt levels (new rules)": for each slot of the rebuild sample (the slots of
levelgen/out/rebuild-preview-levels.json) the campaign level of that slot before the fitness5 rebuild
(levelgen/prototype/campaign-v1-levels.json, the pack as of commit 8ce5435) and then the level the shipped campaign
(app/src/main/assets/levels.json: fitness5, tier floors, belt dial, clued squeezes) has there now, in a section of
their own ("... · L72: old, new") so a tester can play them side by side.

    python3 tools/prototype_pack.py
    ./gradlew :app:assembleDebug -Pprototype
"""
import json
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
SRC = ROOT / 'levelgen/prototype/mechanics-preview-levels.json'
OUT = ROOT / 'app/src/prototype/assets/levels.json'

# mechanic block (1-based first level in the preview pack), theme id, list heading
BLOCKS = [
    (1, 'spa', 'Bamboo Spa · Frozen tickets'),
    (5, 'brunch', 'Sunny Brunch · Tied tickets'),
    (9, 'luau_night', 'Luau Night · Mystery tickets'),
    (13, 'dimsum', 'Dim Sum · Big parties'),
    (17, 'hanami', 'Hanami · Reserved benches'),
    (21, 'ramen_night', 'Ramen Night · Turntables'),
    (25, 'camping_night', 'Campfire · Gates'),
]
PER_BLOCK = 3
MIXES = (29, 32, 'jungle', 'Jungle Café · Mixes')
REBUILT = ROOT / 'levelgen/out/rebuild-preview-levels.json'
# the campaign as shipped now: the "new" level of each pair
SHIPPED = ROOT / 'app/src/main/assets/levels.json'
# the campaign before the fitness5 rebuild (OfficialPackF5 replaced most slots of app/src/main/assets/levels.json)
CAMPAIGN = ROOT / 'levelgen/prototype/campaign-v1-levels.json'
REBUILT_THEME = 'luau_night'
REBUILT_GROUP = 'Rebuilt levels (new rules)'


def slot_of(entry):
    """The official slot a rebuilt level replaces (its "Slot: L72" tag)."""
    for tag in entry.get('motifs', []):
        if tag.startswith('Slot: L'):
            return int(tag[len('Slot: L'):])
    raise ValueError('rebuilt level without a Slot tag')


def main():
    src = json.loads(SRC.read_text())['levels']
    picked = []
    for first, theme, group in BLOCKS:
        for n in range(first, first + PER_BLOCK):
            picked.append((src[n - 1], theme, group))
    lo, hi, theme, group = MIXES
    for n in range(lo, hi + 1):
        picked.append((src[n - 1], theme, group))
    if REBUILT.exists():
        campaign = json.loads(CAMPAIGN.read_text())['levels']
        shipped = json.loads(SHIPPED.read_text())['levels']
        for n in [slot_of(e) for e in json.loads(REBUILT.read_text())['levels']]:
            group = f'{REBUILT_GROUP} · L{n}: old, new'
            old = dict(campaign[n - 1])
            old['motifs'] = list(old.get('motifs', [])) + [f'Slot: L{n}', 'Prototype: old (campaign before the fitness5 rebuild)']
            new = dict(shipped[n - 1])
            new['motifs'] = list(new.get('motifs', [])) + [f'Slot: L{n}', 'Prototype: new (shipped campaign)']
            picked.append((old, REBUILT_THEME, group))
            picked.append((new, REBUILT_THEME, group))
    levels = []
    for i, (entry, theme, group) in enumerate(picked, 1):
        e = dict(entry)
        e['level'] = dict(e['level'], id=i)
        e['theme'] = theme
        e['group'] = group
        levels.append(e)
    OUT.parent.mkdir(parents=True, exist_ok=True)
    OUT.write_text(json.dumps({'version': 2, 'levels': levels}, separators=(',', ':')))
    print(f'{len(levels)} levels -> {OUT.relative_to(ROOT)}')


if __name__ == '__main__':
    main()
