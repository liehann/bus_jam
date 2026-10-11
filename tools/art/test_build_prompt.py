"""Tests for tools/art/build_prompt.py.

    python3 -m unittest tools/art/test_build_prompt.py
"""
import json
import sys
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import build_prompt as bp  # noqa: E402

GOLDEN = Path(__file__).resolve().parent / "testdata" / "golden"
TEMPLATE = bp.TEMPLATE.read_text(encoding="utf-8")

# Invariant text that must reach every prompt unchanged.
TICKET_RULES = [
    "Card sizes, in roughly equal numbers: about 12 SINGLE-cell reservation cards, about 12 DOUBLE-cell reservation cards and about 10 TRIPLE-cell reservation cards.",
    "A single-cell reservation card is a small square exactly one grid cell in size, with the animal icon in its centre and a small arrow tucked against one of its edges.",
    "Double- and triple-cell reservation cards are rectangles exactly 2 or 3 grid cells long, lying perfectly horizontal or perfectly vertical.",
    "Every reservation card has exactly one bold white chevron arrow at its front end, pointing towards a board edge (up, down, left or right). Never two arrows on one reservation card.",
    "A thin gap separates every reservation card from all of its neighbours, so two reservation cards of the same colour next to each other are always clearly separate.",
    "No writing, numbers or text on any reservation card.",
]
TEN_COLOURS = "(pink, mint green, sky blue, butter yellow, lavender, tangerine, cherry red, blueberry blue, plum purple, charcoal grey)"
TEXT_ALLOWLIST = 'text other than "Level 12" and the bench numbers'
CORE_AVOID = (
    "Avoid: realistic style, 3D render, perspective camera, dark conveyor belts, dark or busy backgrounds behind the "
    "reservation cards, animals on the board, detailed or coloured animal drawings on reservation cards, more than one icon per reservation card, "
    "two arrows on one reservation card, no single-cell reservation cards, text or numbers on reservation cards, reservation cards touching each other, a "
    "reservation card without an arrow, crumpled or curled reservation cards, diagonal reservation cards, overlapping reservation cards, a loop with a gap or "
    "open ends, an incoming belt that is short or hidden, more than 4 benches, plates without clear coloured rims, "
    "text labels under buttons, extra buttons, a bottom button bar, " + TEXT_ALLOWLIST
)


def literal_chunks(template):
    """Literal text between placeholders, also split at the night anchor (the night paragraph goes there)."""
    out = []
    for c in bp.PLACEHOLDER.split(template)[::2]:
        head, sep, tail = c.partition(bp.NIGHT_ANCHOR)
        out += [head, sep + tail] if sep else [c]
    return [c for c in out if c]


def naive_render(slots):
    """Plain substitution into the verbatim template, independent of build_prompt."""
    out = TEMPLATE
    for k, v in slots.items():
        if k != "extra_avoid":
            out = out.replace("{{" + k + "}}", v)
    return out.replace("{{extra_avoid}}", ", " + slots["extra_avoid"] if slots.get("extra_avoid") else "")


class AllThemes(unittest.TestCase):
    def setUp(self):
        self.ids = bp.all_theme_ids()

    def test_seed_and_world_themes_exist(self):
        for tid in ("sushi", "spa", "luau", "brunch", "luau_night"):
            self.assertIn(tid, self.ids)

    def test_every_theme_renders_without_placeholders(self):
        for tid in self.ids:
            with self.subTest(theme=tid):
                out = bp.render(tid, bp.load_theme(tid))
                self.assertNotIn("{{", out)
                self.assertNotIn("}}", out)

    def test_invariant_text_unchanged(self):
        chunks = literal_chunks(TEMPLATE)
        for tid in self.ids:
            with self.subTest(theme=tid):
                out = bp.render(tid, bp.load_theme(tid))
                pos = 0
                for chunk in chunks:
                    i = out.find(chunk, pos)
                    self.assertNotEqual(i, -1, f"invariant chunk missing or out of order: {chunk[:60]!r}")
                    pos = i + len(chunk)
                for rule in TICKET_RULES:
                    self.assertIn(rule, out)
                self.assertIn(TEN_COLOURS, out)
                self.assertIn(TEXT_ALLOWLIST, out)
                self.assertIn(CORE_AVOID, out)

    def test_day_themes_match_plain_template(self):
        for tid in self.ids:
            theme = bp.load_theme(tid)
            if theme.get("variant", "day") != "day":
                continue
            with self.subTest(theme=tid):
                self.assertEqual(bp.render(tid, theme), naive_render(theme["slots"]))

    def test_seed_themes_match_golden(self):
        # Goldens were rendered before night support existed: day output must stay byte-identical.
        for golden in sorted(GOLDEN.glob("*.txt")):
            with self.subTest(theme=golden.stem):
                out = bp.render(golden.stem, bp.load_theme(golden.stem))
                self.assertEqual(out, golden.read_text(encoding="utf-8"))

    def test_night_themes_have_night_block(self):
        night = [t for t in self.ids if bp.load_theme(t).get("variant") == "night"]
        self.assertTrue(night)
        for tid in night:
            with self.subTest(theme=tid):
                theme = bp.load_theme(tid)
                out = bp.render(tid, theme)
                self.assertIn("\n\nNight: " + theme["slots"]["night_sky"], out)
                self.assertLess(out.index("Night: "), out.index("Layout from top to bottom:"))
                self.assertIn(bp.NIGHT_AVOID, out)
                # Without the night paragraph, it is the plain template with night avoid items prepended.
                start = out.index("Night: ")
                end = out.index("Layout from top to bottom:")
                slots = dict(theme["slots"])
                slots["extra_avoid"] = ", ".join(x for x in (bp.NIGHT_AVOID, slots.get("extra_avoid")) if x)
                self.assertEqual(out[:start] + out[end:], naive_render(slots))


class Errors(unittest.TestCase):
    def base(self):
        return json.loads(json.dumps(bp.load_theme("sushi")))

    def test_missing_slot(self):
        t = self.base()
        del t["slots"]["foods"]
        with self.assertRaisesRegex(bp.PromptError, "missing slot.*foods"):
            bp.render("t", t)

    def test_empty_slot_counts_as_missing(self):
        t = self.base()
        t["slots"]["mood"] = "  "
        with self.assertRaisesRegex(bp.PromptError, "missing slot.*mood"):
            bp.render("t", t)

    def test_unknown_slot(self):
        t = self.base()
        t["slots"]["sauce"] = "soy"
        with self.assertRaisesRegex(bp.PromptError, "unknown slot.*sauce"):
            bp.render("t", t)

    def test_night_slot_on_day_theme(self):
        t = self.base()
        t["slots"]["night_sky"] = "stars"
        with self.assertRaisesRegex(bp.PromptError, "unknown slot.*night_sky"):
            bp.render("t", t)

    def test_night_theme_missing_night_slot(self):
        t = json.loads(json.dumps(bp.load_theme("luau_night")))
        del t["slots"]["effects"]
        with self.assertRaisesRegex(bp.PromptError, "missing slot.*effects"):
            bp.render("t", t)

    def test_placeholder_in_value(self):
        t = self.base()
        t["slots"]["foods"] = "a {{nigiri}}"
        with self.assertRaisesRegex(bp.PromptError, "foods"):
            bp.render("t", t)

    def test_leftover_placeholder_in_template(self):
        with self.assertRaisesRegex(bp.PromptError, "leftover placeholder"):
            bp.render("t", self.base(), template_text=TEMPLATE + "{{garnish")

    def test_unknown_template_slot(self):
        with self.assertRaisesRegex(bp.PromptError, "missing slot.*garnish"):
            bp.render("t", self.base(), template_text=TEMPLATE + "{{garnish}}")

    def test_bad_variant(self):
        t = self.base()
        t["variant"] = "dusk"
        with self.assertRaisesRegex(bp.PromptError, "variant"):
            bp.render("t", t)


class Worlds(unittest.TestCase):
    def test_worlds_reference_themes(self):
        data = json.loads((bp.THEMES_DIR / "worlds.json").read_text(encoding="utf-8"))
        self.assertEqual(data["status"], "proposed")
        worlds = data["worlds"]
        self.assertEqual([w["world"] for w in worlds], list(range(1, len(worlds) + 1)))
        for w in worlds:
            with self.subTest(world=w["world"]):
                theme = bp.load_theme(w["theme"])
                self.assertEqual(w["variant"], theme["variant"])
                self.assertEqual(w["name"], theme["name"])
                self.assertEqual(w["levels"], 20)
                self.assertIn("introduces", w)


if __name__ == "__main__":
    unittest.main()
