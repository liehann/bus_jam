#!/usr/bin/env python3
"""Render Critter Café level-mock image prompts from theme files.

    python3 tools/art/build_prompt.py luau            # render one theme, print it
    python3 tools/art/build_prompt.py sushi spa       # several
    python3 tools/art/build_prompt.py --all           # every art/themes/*.json

Each theme (art/themes/<id>.json) is:

    {"name": "...", "variant": "day" | "night", "status": "draft" | "final",
     "slots": {"palette": "...", ...}}

The shared template is art/prompts/level_mock.template.txt (never edited per theme).
Night themes also fill night_sky, light_sources and effects. Those go through
art/prompts/level_mock.night.template.txt, which is inserted as its own paragraph
just before "Layout from top to bottom:", and a fixed list of night avoid items is
prepended to extra_avoid. Day themes therefore render exactly as the plain template.

Output goes to art/prompts/generated/<id>.txt. Stdlib only.
"""
import argparse
import json
import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
THEMES_DIR = ROOT / "art" / "themes"
TEMPLATE = ROOT / "art" / "prompts" / "level_mock.template.txt"
NIGHT_TEMPLATE = ROOT / "art" / "prompts" / "level_mock.night.template.txt"
OUT_DIR = ROOT / "art" / "prompts" / "generated"

# Files in art/themes/ that are not themes.
NON_THEME_FILES = {"worlds.json"}

PLACEHOLDER = re.compile(r"\{\{\s*([^{}]*?)\s*\}\}")
LEFTOVER = re.compile(r"\{\{|\}\}")

OPTIONAL_SLOTS = {"extra_avoid"}
NIGHT_SLOTS = ("night_sky", "light_sources", "effects")
VARIANTS = ("day", "night")

# Anchor in the invariant template text; the night paragraph goes right before it.
NIGHT_ANCHOR = "Layout from top to bottom:"
# Prepended to extra_avoid for night themes.
NIGHT_AVOID = (
    "a dark or night-tinted board, darkness or shadow over the belts, the loop, the counter or the board, "
    "plate rims, reservation cards or animals tinted by coloured light, light sources or night effects covering any plate, "
    "reservation card or bench"
)


class PromptError(Exception):
    """A theme or template problem; the message says what to fix."""


def template_slots(text):
    return [m.group(1) for m in PLACEHOLDER.finditer(text)]


def _fill(text, values, where):
    def sub(m):
        name = m.group(1)
        if name not in values:
            raise PromptError(f"{where}: no value for {{{{{name}}}}}")
        return values[name]

    return PLACEHOLDER.sub(sub, text)


def load_theme(theme_id, themes_dir=THEMES_DIR):
    path = Path(themes_dir) / f"{theme_id}.json"
    if not path.exists():
        raise PromptError(f"theme '{theme_id}': {path} does not exist")
    try:
        data = json.loads(path.read_text(encoding="utf-8"))
    except json.JSONDecodeError as e:
        raise PromptError(f"theme '{theme_id}': invalid JSON in {path}: {e}") from e
    if not isinstance(data, dict) or not isinstance(data.get("slots"), dict):
        raise PromptError(f"theme '{theme_id}': expected an object with a 'slots' object")
    return data


def render(theme_id, theme, template_text=None, night_text=None):
    """Return the prompt text for one theme dict. Raises PromptError on any problem."""
    if template_text is None:
        template_text = TEMPLATE.read_text(encoding="utf-8")
    where = f"theme '{theme_id}'"
    variant = theme.get("variant", "day")
    if variant not in VARIANTS:
        raise PromptError(f"{where}: variant must be one of {VARIANTS}, got {variant!r}")
    slots = theme["slots"]

    for k, v in slots.items():
        if not isinstance(v, str):
            raise PromptError(f"{where}: slot '{k}' must be a string")
        if LEFTOVER.search(v):
            raise PromptError(f"{where}: slot '{k}' contains '{{{{' or '}}}}': {v!r}")

    main_slots = set(template_slots(template_text))
    allowed = set(main_slots)
    if variant == "night":
        if night_text is None:
            night_text = NIGHT_TEMPLATE.read_text(encoding="utf-8")
        night_slots = set(template_slots(night_text))
        allowed |= night_slots
        required = (main_slots | night_slots) - OPTIONAL_SLOTS
    else:
        required = main_slots - OPTIONAL_SLOTS

    unknown = sorted(set(slots) - allowed)
    if unknown:
        hint = " (night-only slots need \"variant\": \"night\")" if set(unknown) & set(NIGHT_SLOTS) else ""
        raise PromptError(f"{where}: unknown slot(s) {', '.join(unknown)}{hint}")
    missing = sorted(s for s in required if not slots.get(s, "").strip())
    if missing:
        raise PromptError(f"{where}: missing slot(s) {', '.join(missing)}")

    values = {k: v.strip() for k, v in slots.items()}
    extra = [values["extra_avoid"]] if values.get("extra_avoid") else []
    if variant == "night":
        extra.insert(0, NIGHT_AVOID)
    values["extra_avoid"] = "".join(", " + e for e in extra)

    out = _fill(template_text, values, where)

    if variant == "night":
        if out.count(NIGHT_ANCHOR) != 1:
            raise PromptError(f"template: expected '{NIGHT_ANCHOR}' exactly once for the night block")
        block = _fill(night_text.strip(), values, where + " (night block)")
        out = out.replace(NIGHT_ANCHOR, block + "\n\n" + NIGHT_ANCHOR, 1)

    left = LEFTOVER.search(out)
    if left:
        line = out[: left.start()].count("\n") + 1
        raise PromptError(f"{where}: leftover placeholder near line {line}")
    return out


def all_theme_ids(themes_dir=THEMES_DIR):
    return sorted(p.stem for p in Path(themes_dir).glob("*.json") if p.name not in NON_THEME_FILES)


def build(theme_id, themes_dir=THEMES_DIR, out_dir=OUT_DIR):
    text = render(theme_id, load_theme(theme_id, themes_dir))
    out_dir = Path(out_dir)
    out_dir.mkdir(parents=True, exist_ok=True)
    (out_dir / f"{theme_id}.txt").write_text(text, encoding="utf-8")
    return text


def main(argv=None):
    ap = argparse.ArgumentParser(description=__doc__.split("\n")[0])
    ap.add_argument("themes", nargs="*", help="theme ids (file names in art/themes without .json)")
    ap.add_argument("--all", action="store_true", help="render every theme")
    ap.add_argument("--quiet", action="store_true", help="write files but do not print prompts")
    args = ap.parse_args(argv)

    ids = all_theme_ids() if args.all else args.themes
    if not ids:
        ap.error("give one or more theme ids, or --all")

    failed = False
    for i, tid in enumerate(ids):
        try:
            text = build(tid)
        except PromptError as e:
            print(f"error: {e}", file=sys.stderr)
            failed = True
            continue
        if args.quiet:
            print(f"wrote art/prompts/generated/{tid}.txt", file=sys.stderr)
        else:
            if len(ids) > 1:
                print(("\n" if i else "") + f"===== {tid} =====")
            sys.stdout.write(text)
    return 1 if failed else 0


if __name__ == "__main__":
    sys.exit(main())
