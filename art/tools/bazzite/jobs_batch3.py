import os
HERE = os.path.dirname(os.path.abspath(__file__))
import json
exec(open(os.path.join(HERE, 'jobs_batch2.py')).read().split('jobs = []')[0])  # STYLE_B, SP, VIEW
CREAM = (" COLOUR RULE: every furred area that is not white or dark brown is one flat pale cream colour (#F2E3C8), the same cream as the "
         "kitten reference. Absolutely no orange, red, ginger, grey or tan fur anywhere.")
SP2 = {
 "red_panda": SP["red_panda"] + ". It is NOT orange: a pale cream red panda, like a colouring-book version waiting to be tinted",
 "bunny": "a bunny rabbit with cream fur: a rounder, pear-shaped body than the kitten, very long upright ears (as tall as its head) with pink insides, a short rounded muzzle with a tiny Y-shaped mouth, no whiskers, large hind feet sticking forward, a round white cotton-ball tail, white muzzle and belly. Not a cat",
 "wolf": "a wolf pup with cream fur: a longer pointed muzzle than the kitten with a dark-brown nose, tall pointed triangular ears with dark-brown backs, a thick fluffy white neck ruff and chest, a dark-brown saddle patch on its back, and a long bushy tail with a dark-brown tip. Not a dog, not a raccoon",
}
jobs = []
for sp, d in SP2.items():
    for v, vd in VIEW.items():
        for seed in (3, 4):
            jobs.append(dict(name=f"crit3_{sp}_{v}_kleinref_s{seed}", model="klein", w=896, h=1200, seed=seed,
                refs=["refsm/kitten_kitten_front.jpg", "refsm/kitten_kitten_left.jpg"],
                prompt=("The reference images show a chibi kitten character from the front and from the side. Use them ONLY for art style, "
                        "proportions and colouring; the new animal's anatomy must follow the description, not the kitten. "
                        "Same thick dark-brown outline, same soft cel shading, same big glossy eyes, same light blue collar with gold bell. "
                        f"The new character is {d}.{CREAM} Pose: {vd}. Full body, centred on a plain pure white background, nothing else, "
                        "no shadow, no text. " + STYLE_B)))
REF_KIT = "Use exactly the art style of the reference image: same outline thickness and colour, same cel shading, same pastel palette, same amount of detail. Draw a new object, do not copy the reference's content. "
KIT3 = {
 "stand": (1024, 1024, "One single tiny wooden ticket holder for a restaurant counter, like a place-card holder: a low, wide wooden block, about three times wider than tall, with one narrow slot cut along its top edge. It is EMPTY: no card, no paper. It is not a box and not a crate and has no lid. Three-quarter view, small in the frame, centred on a plain pure white background, nothing else, no shadow, no text."),
 "belt": (2048, 512, "A straight horizontal section of a sushi conveyor belt seen straight down, cut out of a longer belt: evenly spaced pale wooden cross slats, a light wooden rim with a dark brown outline along the top and bottom edges only. The belt runs off both the left and the right edge of the image with NO rounded ends, NO end caps and no outline on the left or right edge, so it tiles seamlessly left to right. Plain white above and below the belt, nothing on it."),
 "floor": (1024, 1024, "A seamless tileable texture of a pale honey-blonde wooden plank floor seen straight down. All planks the same pale honey colour with only tiny variations, no pink or red planks, horizontal planks of equal height with staggered joints, faint grain. No objects, no shadows, no border; edges tile seamlessly."),
}
for name, (w, h, p) in KIT3.items():
    for seed in (3, 4):
        jobs.append(dict(name=f"kit3_{name}_kleinref_s{seed}", model="klein", w=w, h=h, seed=seed, refs=["refsm/sushi_backdrop_v2.jpg"], prompt=REF_KIT + STYLE_B + " Subject: " + p))
json.dump(jobs, open("batch3.json", "w"), indent=1); print(len(jobs))
