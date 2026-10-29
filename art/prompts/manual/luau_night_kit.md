# Luau night kit

The first theme kit, made by hand on 2026-10-15 from `art/raw/luau_night/mock.jpeg` (the user's level mock). Its
prompts became the shared kit template: `art/prompts/kit.template.txt`, rendered for this theme into
`art/prompts/generated/luau_night_kit.md` (`python3 tools/art/build_prompt.py --kit luau_night`). What each piece is
for and how the app draws it: docs/ART-PIPELINE.md, "Level themes".

Differences between this run and the template:
- The pieces were saved as `hut` and `lagoon`; they are now `kitchen` and `loop`.
- The props sheet was a 4 x 3 grid of 12 items (stand, lantern, torch, coconut / pineapple, ukulele, hibiscus,
  frond / stone, corner joint, festoon bulbs, candle). `art/raw/luau_night/props.json` maps its cells to the
  template's names; the torch, stone, bulbs and candle are not used (the lantern and torch kept a pale glow halo
  from the white background).
