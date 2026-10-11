---
name: level-theme
description: Creates or updates a themed Kitten Jumble level-art image prompt from a theme name or short description (e.g. "/level-theme ski lodge"). Renders art/themes/<id>.json through the shared level-mock template and returns a ready-to-paste prompt.
---

# level-theme

Every Kitten Jumble theme (sushi bar, spa, luau, brunch, ...) shares one identical game layout. A theme only swaps
the setting, food and decorations. The layout and rules live in the shared template; a theme is a small JSON file of
slot values.

Files:

| Path | What |
|---|---|
| `art/prompts/level_mock.template.txt` | shared template with `{{slot}}` placeholders. Everything outside the placeholders is invariant. |
| `art/prompts/level_mock.night.template.txt` | night paragraph, inserted only for `"variant": "night"` themes |
| `art/themes/<id>.json` | one theme: `name`, `variant` (`day`/`night`), `status` (`draft`/`final`), `slots` |
| `art/themes/worlds.json` | worlds in play order (status: proposed). Not a theme. |
| `art/prompts/generated/<id>.txt` | rendered prompt (output, regenerate rather than edit) |
| `tools/art/build_prompt.py` | renderer: `python3 tools/art/build_prompt.py <id>` or `--all` |
| `tools/art/test_build_prompt.py` | `python3 -m unittest tools/art/test_build_prompt.py` |

## Slots

Required for every theme: `palette`, `setting`, `decorations`, `background_tones`, `kitchen`, `belt_material`,
`loop_interior`, `foods`, `counter`, `bench_material`, `accessory`, `board_surface`, `obstacles`, `mood`, `lighting`.

Optional: `extra_avoid` (theme-specific avoid items; the script prefixes it with ", ").

Night themes (`"variant": "night"`) also require `night_sky`, `light_sources` and `effects`. The script inserts the
night paragraph before "Layout from top to bottom:" and adds fixed night avoid items (no darkness over the play
areas, no tinted gameplay colours) ahead of `extra_avoid`. Day themes must not use these slots.

How each slot reads in the template (match the grammar):

- `palette`: "warm pastel palette" (follows "... thick dark brown outlines,").
- `setting`: lower-case noun phrase, "a cozy Japanese sushi bar for cute hungry animals".
- `decorations`: one or more full sentences ending with "around the edges of the screen."
- `background_tones`: "warm (cream, light wood)" (follows "Background colours are pale and").
- `kitchen`: noun phrase, sits "in the top left corner".
- `belt_material`: "pale wooden slats" (follows "made of").
- `loop_interior`: noun phrase (follows "Inside the loop:").
- `foods`: "a nigiri, a maki roll or a gunkan".
- `counter`, `board_surface`: noun phrases. `bench_material`: adjective before "benches".
- `accessory`: "a small napkin bib" (follows "each animal wearing").
- `obstacles`: exactly three items, "a X, a Y and a Z".
- `mood`: lower-case phrase. `lighting`: capitalised phrase, "Soft natural light".

## Behaviour

When invoked with a theme name or idea:

1. Derive a snake_case id (`ski lodge` -> `ski_lodge`; add `_night` for a night variant). Check `art/themes/` for
   an existing theme with that id or the same name.
2. If `art/themes/<id>.json` exists: run `python3 tools/art/build_prompt.py <id>` and show the prompt in a fenced
   block, ready to paste.
3. Otherwise draft every slot following the rules below, write `art/themes/<id>.json` with `"status": "draft"`,
   render it, and show the user both the slot values (as a short table) and the final prompt. Ask the user to confirm
   before treating the theme as final; on confirmation set `"status": "final"`.
4. If the user asks for tweaks, edit only the theme file and re-render.
5. Never edit the template's invariant parts to suit a theme. If a theme seems to need a layout change, tell the user
   instead.
6. If the renderer reports a missing slot, unknown slot or leftover `{{...}}`, fix the theme file, never the template.

## Rules for drafting slots

- The board surface must be pale and low-contrast (light wood, linen, tatami, woven mat). Never grainy or patterned
  textures such as sand, gravel or busy prints, because the pastel tickets must stand out.
- The background is pale and neutral, and theme colour goes in the frame decorations around the edges, never behind
  the board.
- The loop interior is one calm element (water, a garden bed, a fire pit) and must not compete with the plates.
- Foods are small, simple and iconic (one item per plate), so the coloured plate rim stays the dominant feature.
  They must be recognisable at about 30 px. Plates are always round white plates with coloured rims (template
  invariant); the food sits on the plate.
- Obstacles are 3 small props that fit inside one grid cell. The game rules have no obstacles yet, so they are
  decor-only until a blocker mechanic exists.
- The animal accessory is one small item that can be drawn as a separate layer (lei, towel, hat, scarf).
- Belts are always light-coloured.
- Fires are small and contained. No text on signs or decorations (the template allows only "Level 12", the bench
  numbers and "15/20").
- Night themes: darkness only as an edge vignette, never over a play area; the board stays pale (lit by warm light);
  gameplay colours are never tinted by the lighting. Put light sources at the edges or above the belts, not over the
  board.
- Use `extra_avoid` for theme-specific failure modes (e.g. "animals in the water", "steam covering the plates").

## Worlds

`art/themes/worlds.json` lists the worlds in play order with world number, theme id, name, levels, variant and
`introduces`. The mechanics in `introduces` are proposed, not decided rules. Seasonal event themes (e.g.
`halloween_night`, `valentines`) have theme files but are not in `worlds.json`.

## Adding a theme by hand

Copy an existing `art/themes/<id>.json`, change the values, then run
`python3 tools/art/build_prompt.py <id>` and `python3 -m unittest tools/art/test_build_prompt.py`.
