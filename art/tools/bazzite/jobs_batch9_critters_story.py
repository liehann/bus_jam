"""Batch 9 (2026-11-11): six new cream critters for the tint pipeline, plus storybook scenes.

Critters use the batch-3 recipe (kitten front + side as klein references, COLOUR RULE).
Story scenes use the Gemini home hero as the style reference (klein) and a text-only Qwen
Lightning variant for comparison. Run from a directory holding refsm/ (see BAZZITE-ART.md).
"""
import json
import os

HERE = os.path.dirname(os.path.abspath(__file__))
exec(open(os.path.join(HERE, "jobs_batch2.py")).read().split("jobs = []")[0])  # STYLE_B, SP, VIEW

CREAM = (" COLOUR RULE: every furred area that is not white or dark brown is one flat pale cream colour (#F2E3C8), the same cream as the "
         "kitten reference. Absolutely no orange, red, ginger, grey or tan fur anywhere.")
NEW = {
    "hedgehog": "a hedgehog: a round cream face and belly, a short pointed snout with a dark-brown button nose, small round ears, and a dome "
                "of soft rounded spines on its back and head drawn as chunky cream-and-dark-brown scalloped tufts, not sharp needles. Not a cat",
    "otter": "a river otter: a long smooth sleek body, a flat round head with tiny round ears, a broad white muzzle with a dark-brown nose "
             "and a few short whisker dots, small webbed paws, a long thick tapering tail. Not a cat, not a ferret",
    "koala": "a koala: a big round head, very large round fluffy ears with white fur tufts, a large dark-brown oval nose, a stout round body, "
             "white chest, no visible tail. Not a bear, not a cat",
    "hamster": "a hamster: a very round chubby pear-shaped body with no neck, puffy white cheek pouches, small round ears with pink insides, "
               "tiny pink paws held in front of the chest, a tiny stub tail. Not a cat, not a mouse",
    "panda": "a baby giant panda: a round white-cream body, dark-brown round ears, dark-brown oval eye patches, dark-brown arms and legs and a "
             "dark-brown band across the shoulders, a small black nose. Not a bear with brown fur, not a cat",
    "fawn": "a deer fawn: long slender legs folded under it, large oval upright ears with pink insides, a slim muzzle with a dark-brown nose, "
            "white dapple spots on its back, a short white fluffy tail. Not a cat, not a dog",
}

jobs = []
for sp, d in NEW.items():
    for v, vd in VIEW.items():
        for seed in (3, 4):
            jobs.append(dict(name=f"crit9_{sp}_{v}_s{seed}", model="klein", w=896, h=1200, seed=seed,
                refs=["refsm/kitten_kitten_front.jpg", "refsm/kitten_kitten_left.jpg"],
                prompt=("The reference images show a chibi kitten character from the front and from the side. Use them ONLY for art style, "
                        "proportions and colouring; the new animal's anatomy must follow the description, not the kitten. "
                        "Same thick dark-brown outline, same soft cel shading, same eyes, same light blue collar with gold bell. "
                        f"The new character is {d}.{CREAM} Pose: {vd}. Full body, centred on a plain pure white background, nothing else, "
                        "no shadow, no text. " + STYLE_B)))

# Storybook: a short "how the café began" arc, one picture per beat.
CAST = ("The critters are chibi animals, each in one soft pastel colour like the reference: a pink kitten, an orange-and-brown red panda, "
        "a butter-yellow fox, a sky-blue wolf pup; all wear light blue collars with small gold bells.")
STORY_STYLE = ("Children's picture-book illustration in exactly the art style of the reference image: same warm brown outlines, same soft "
               "flat pastel colours with gentle airbrushed shading, same cosy warm cream lighting, same chibi proportions. Draw a new scene, "
               "do not copy the reference's composition. No text, no letters, no speech bubbles, no border.")
SCENES = {
    "s1_dawn": "Dawn in a quiet seaside town: the pink kitten, wearing a small white apron, unlocks the round wooden door of a tiny empty "
               "sushi café, pink morning sky, seagulls, a paper lantern over the door just lighting up.",
    "s2_first_customer": "Inside the little café: the pink kitten stands behind the counter beaming as the first customer, the butter-yellow "
                         "fox, peeks shyly around the noren curtain at the door. A sushi conveyor belt with a few colourful plates curves "
                         "past empty wooden stools.",
    "s3_rush": "Lunch rush chaos in the sushi café: a long line of critters of every colour wiggles out the door, the sky-blue wolf pup and "
               "the red panda squabble over the same stool, plates of sushi pile up on the conveyor belt, the pink kitten juggles three "
               "plates at once. Funny, busy, joyful.",
    "s4_sorting": "The pink kitten, standing on a little step stool, cheerfully points the critters to stools that match their colours: "
                  "the blue wolf pup to a blue cushion, the yellow fox to a yellow cushion, everyone lining up neatly. Bright, orderly, happy.",
    "s5_closing": "Closing time at night: the café glows warmly from inside, the kitten, fox, red panda and wolf pup sit together on the "
                  "front step sharing one plate of dango under a starry sky and paper lanterns, sleepy and content.",
    "s6_journey": "Morning: the four critter friends ride a little cream-coloured cart piled with a folded noren and cooking pots along a "
                  "winding road toward a jungle treehouse café in the distance, with snowy mountains and a cherry-blossom hill on the "
                  "horizon hinting at new places to open cafés.",
}
for k, s in SCENES.items():
    for seed in (1, 2):
        jobs.append(dict(name=f"story9_{k}_klein_s{seed}", model="klein", w=1344, h=896, seed=seed,
                         refs=["refsm/home_hero.jpg"], prompt=f"{STORY_STYLE} {CAST} Scene: {s}"))
    jobs.append(dict(name=f"story9_{k}_qwenfast_s1", model="qwenfast", w=1344, h=896, seed=1, steps=8,
                     prompt=("Cute cosy children's picture-book illustration, flat 2D, soft pastel colours, warm brown outlines, gentle "
                             f"airbrushed shading, warm cream lighting, chibi animals. No text. {CAST} Scene: {s}")))

if __name__ == "__main__":
    json.dump(jobs, open("batch9.json", "w"), indent=1)
    print(len(jobs))
