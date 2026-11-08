import os
HERE = os.path.dirname(os.path.abspath(__file__))
import json
STYLE_B = open(os.path.join(HERE, 'jobs_batch1.py')).read().split('STYLE_B = (')[1].split(')\n')[0]
STYLE_B = eval('(' + STYLE_B + ')')
SP = {
 "red_panda": "a red panda with cream fur, white ear tips, white cheek and eyebrow markings, dark-brown tear stripes from the eyes, dark-brown legs and a cream bushy tail with dark-brown rings",
 "fox": "a fox with cream fur, a pointed muzzle, large pointed ears with dark-brown backs and pink insides, a white chest and cheeks, dark-brown socks on the paws and a big bushy cream tail with a white tip",
 "wolf": "a wolf with cream fur, a longer muzzle, upright pointed ears, a white muzzle and chest, dark-brown fur on the back of the ears, a thick ruff and a bushy tail",
 "bunny": "a bunny with cream fur, long upright ears with pink insides, a white muzzle and belly, a round white cotton tail",
 "bear": "a bear cub with cream fur, small round ears, a white muzzle and dark-brown nose, chunky paws with dark-brown pads",
 "raccoon": "a raccoon with cream fur, a dark-brown mask around the eyes, white eyebrows and muzzle, and a bushy cream tail with dark-brown rings",
 "penguin": "a penguin chick with cream feathers on the back and head, a white face and belly, a small dark-brown beak and dark-brown feet",
}
VIEW = {"front": "sitting, front view, facing the viewer", "left": "sitting, side view facing left, in profile, exactly the same pose as the side reference"}
jobs = []
for sp, d in SP.items():
    for v, vd in VIEW.items():
        for seed in (1, 2):
            jobs.append(dict(name=f"crit_{sp}_{v}_kleinref_s{seed}", model="klein", w=896, h=1200, seed=seed,
                refs=["refsm/kitten_kitten_front.jpg", "refsm/kitten_kitten_left.jpg"],
                prompt=("The reference images show a chibi kitten character from the front and from the side. Draw a new character in exactly "
                        "the same chibi style: same proportions, same head size, same eyes, same thick dark-brown outline, same soft cel shading, "
                        f"same light blue collar with gold bell. The new character is {d}. It must clearly read as that animal, not as a kitten. "
                        f"Pose: {vd}. Full body, centred on a plain pure white background, nothing else, no shadow, no text. " + STYLE_B)))
json.dump(jobs, open("batch2.json", "w"), indent=1); print(len(jobs))
