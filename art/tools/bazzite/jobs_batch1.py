import json

# Style A: short (roughly what the laptop runs likely used)
STYLE_A = "Cute flat 2D mobile game art, pastel colours, outlines."
# Style B: prescriptive, describes the Gemini look explicitly
STYLE_B = ("Art style: polished cozy casual mobile game illustration, flat 2D. Every shape has a thick, even, "
           "dark chocolate-brown outline (#4a2e22) about 1% of the image width, with slightly rounded line ends. "
           "Soft cel shading: one gentle darker shadow tone on the side away from the light and one small soft "
           "highlight, no hard black shadows, no gradients to black. Warm desaturated pastel palette: cream, ivory, "
           "honey and blonde wood, soft peach, muted coral, sage. Subtle hand-painted texture: faint wood grain lines "
           "in a slightly darker tone, never noisy. Rounded chunky proportions, clean vector-like edges, moderate detail "
           "(small bolts, plank seams, fabric folds), consistent top-left lighting. Not photorealistic, not 3D, not "
           "pixel art, not watercolour, not sketchy.")
REF_KIT = "Use exactly the art style of the reference image: same outline thickness and colour, same cel shading, same pastel palette, same amount of detail. Draw a new object, do not copy the reference's content. "
REF_CHAR = ("Use exactly the art style and character design language of the reference images: same chibi proportions "
            "(big round head about half the body height, large glossy dark eyes with one white highlight, small pink nose, "
            "tiny smile), same thick dark brown outline, same soft cel shading, same muted pastel fur colours, the same "
            "light blue collar with a small gold bell. Draw a different animal, sitting facing the viewer, centred on a "
            "plain pure white background, full body, nothing else in the image. ")
WHITE = " Centred on a plain pure white background, nothing else in the image, no ground shadow, no text."

KIT = {
 "floor": (1024, 1024, "A seamless tileable square texture of a pale light-wood plank floor seen straight down. Planks run horizontally, soft subtle grain, very low contrast, no objects, no shadows, no border."),
 "board": (1024, 1024, "A seamless tileable square texture of a very pale smooth light-wood counter top seen straight down: almost plain cream wood with a faint soft grain, no planks, no seams, no objects."),
 "belt": (2048, 512, "One long perfectly straight horizontal strip of a sushi conveyor belt seen straight down: pale wooden slats running across the strip, a light wooden rim with a dark brown outline along top and bottom edges. Empty. The strip fills the full width of the image, plain white above and below."),
 "counter": (2048, 512, "One long straight horizontal strip of a light natural wooden sushi counter seen from slightly above: flat counter top with its front edge. Empty, nothing on it. Fills the full image width, plain white above and below."),
 "bench": (1376, 768, "One single short empty bench in natural light wood, no backrest, three-quarter top-down view." + WHITE),
 "stand": (1024, 1024, "One single small empty wooden ticket stand: a little wooden base with a slot holding nothing, three-quarter view." + WHITE),
 "hatch": (1024, 1024, "One single kitchen hatch in a wooden wall frame with a short cream noren curtain split into three panels, seen from the front; the opening behind the curtain is dark." + WHITE),
 "decor": (1024, 1376, "A sprite sheet on a plain white background with these separate items spaced well apart in a loose grid, none touching: a small round paper lantern on a short cord, a wide cream noren curtain on a wooden rod, a narrow cream shoji paper screen panel in a light wood frame, a small potted green plant, a small blank wooden sign. No shadows, no text."),
}
CRIT = {
 "bunny": "A cute chibi bunny with long upright ears, cream and soft grey fur, white muzzle and belly.",
 "bear": "A cute chibi brown bear cub with small round ears, warm caramel fur, light tan muzzle and belly.",
 "raccoon": "A cute chibi raccoon with a dark mask around the eyes, soft grey fur, ringed bushy tail curled beside it.",
 "penguin": "A cute chibi penguin chick with soft dark slate feathers, white face and belly, tiny orange beak and feet.",
}
BACKDROP = ("A portrait phone game background, seen straight down from above, for a cozy sushi bar where cute animals eat. "
 "Top 10%: cream plaster wall with a light wood wainscot, a shoji window on the right with a cream noren curtain. "
 "Top-left: a small kitchen hatch with a cream noren curtain; out of it a narrow pale wooden conveyor belt with "
 "cross slats runs right then down into a large rounded rectangular conveyor loop that fills the width of the screen "
 "between 15% and 40% of the height, with a pale wooden table surface inside the loop. Below the loop a long light "
 "wooden counter with four small empty wooden ticket stands, then a row of four short red cushioned benches. The "
 "bottom 55% of the screen is an even, plain sage-green tatami floor with nothing on it. Empty: no animals, no food, no text, no UI.")
LUAU = {
 "luau_hatch": (1024, 1024, "One single small tiki grill hut with a thatched palm-leaf roof and bamboo frame, front view, a little grill inside." + WHITE),
 "luau_bench": (1376, 768, "One single short empty bench made of pale bamboo poles lashed with rope, no backrest, three-quarter top-down view." + WHITE),
 "luau_decor": (1024, 1376, "A sprite sheet on a plain white background with separate items spaced well apart, none touching: a tiki torch with a soft flame, a red hibiscus flower, a palm frond, a coconut drink with a paper umbrella, a small blank bamboo sign. No shadows, no text."),
}

jobs = []
for name, (w, h, p) in KIT.items():
    for seed in (1, 2):
        jobs.append(dict(name=f"kit_{name}_kleinref_s{seed}", model="klein", w=w, h=h, seed=seed,
                         refs=["refsm/sushi_backdrop_v2.jpg"], prompt=REF_KIT + STYLE_B + " Subject: " + p))
    jobs.append(dict(name=f"kit_{name}_kleintxtB_s1", model="klein", w=w, h=h, seed=1, prompt=STYLE_B + " Subject: " + p))
    jobs.append(dict(name=f"kit_{name}_kleintxtA_s1", model="klein", w=w, h=h, seed=1, prompt=STYLE_A + " " + p))
    jobs.append(dict(name=f"kit_{name}_zimageB_s1", model="zimage", w=w, h=h, seed=1, prompt=STYLE_B + " Subject: " + p))
for name, p in CRIT.items():
    for seed in (1, 2):
        jobs.append(dict(name=f"crit_{name}_kleinref_s{seed}", model="klein", w=896, h=1200, seed=seed,
                         refs=["refsm/fox_fox_front.jpg", "refsm/red_panda_red_panda_front.jpg"], prompt=REF_CHAR + STYLE_B + " Subject: " + p))
    jobs.append(dict(name=f"crit_{name}_kleintxtB_s1", model="klein", w=896, h=1200, seed=1,
                     prompt=STYLE_B + " Subject: " + p + " Chibi proportions, big head, big glossy dark eyes, light blue collar with a small gold bell, sitting facing the viewer, full body." + WHITE))
    jobs.append(dict(name=f"crit_{name}_zimageB_s1", model="zimage", w=896, h=1200, seed=1,
                     prompt=STYLE_B + " Subject: " + p + " Chibi proportions, big head, big glossy dark eyes, light blue collar with a small gold bell, sitting facing the viewer, full body." + WHITE))
for seed in (1, 2):
    jobs.append(dict(name=f"backdrop_kleinref_s{seed}", model="klein", w=1024, h=2288, seed=seed, refs=["refsm/sushi_backdrop_v2.jpg"], prompt=REF_KIT + STYLE_B + " Subject: " + BACKDROP))
jobs.append(dict(name="backdrop_kleintxtB_s1", model="klein", w=1024, h=2288, seed=1, prompt=STYLE_B + " Subject: " + BACKDROP))
jobs.append(dict(name="backdrop_zimageB_s1", model="zimage", w=1024, h=2288, seed=1, prompt=STYLE_B + " Subject: " + BACKDROP))
for name, (w, h, p) in LUAU.items():
    jobs.append(dict(name=f"{name}_kleinref_s1", model="klein", w=w, h=h, seed=1, refs=["refsm/sushi_kit_decor.jpg"], prompt=REF_KIT + STYLE_B + " Subject: " + p))
    jobs.append(dict(name=f"{name}_zimageB_s1", model="zimage", w=w, h=h, seed=1, prompt=STYLE_B + " Subject: " + p))
json.dump(jobs, open("batch1.json", "w"), indent=1)
print(len(jobs), "jobs")
