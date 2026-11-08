import json
exec(open('mkjobs.py').read().split('REF_KIT')[0])  # STYLE_A, STYLE_B
STYLES = {
 "A_geminilike": STYLE_B,
 "B_sticker": ("Art style: bold, clean sticker-style 2D game art. Every shape has one uniform very thick dark brown outline (#3b2418) "
               "like a die-cut sticker, flat solid colour fills, exactly one flat shadow tone per colour and a single small white "
               "highlight, no texture, no gradients. Bright but soft candy palette: warm cream, butter yellow, peach, coral, mint, sky blue. "
               "Very rounded, chunky, simplified shapes with big readable silhouettes, generous negative space, crisp vector look. "
               "Not photorealistic, not 3D, not painterly."),
 "C_storybook": ("Art style: soft storybook illustration for a cozy mobile game. Fine warm-brown ink outlines of varying weight, "
                 "gentle gouache-like painted shading with soft edges, a light paper-grain texture, warm afternoon light from the top left. "
                 "Muted warm palette: oatmeal, honey wood, terracotta, sage green, dusty pink, cream. Charming rounded proportions, "
                 "moderate detail, calm and cozy. Not photorealistic, not 3D, not vector-flat, not sketchy."),
}
W = " Centred on a plain pure white background, nothing else in the image, no ground shadow, no text."
CH = (" A cute chibi animal character: big round head about half its body height, large glossy dark eyes with one white highlight, "
      "small nose, tiny smile, light blue collar with a small gold bell, sitting facing the viewer, full body." + W)
ASSETS = {
 "kitchen": (1024, 1024, "A small sushi kitchen hatch: a light wooden wall frame with a cream noren curtain split into three panels, the opening behind it dark, seen from the front." + W),
 "bench": (1376, 768, "One short empty wooden bench with no backrest, natural honey wood, three-quarter top-down view." + W),
 "stand": (1024, 1024, "One tiny wooden place-card holder for a restaurant counter: a low wide wooden block with a narrow slot along its top, empty, three-quarter view, small in the frame." + W),
 "props": (1376, 1024, "A sprite sheet of separate items spaced well apart in a 3 by 2 grid, none touching: a round paper lantern, a small potted green plant, a teapot, a pair of chopsticks on a rest, a small blank wooden sign, a bowl of rice." + W),
 "kitten": (896, 1200, "A kitten with cream fur, white muzzle and chest, pink inner ears." + CH),
 "fox": (896, 1200, "A fox with cream fur, pointed muzzle, big pointed ears with dark-brown backs, white chest, dark-brown socks, bushy tail with a white tip." + CH),
 "bunny": (896, 1200, "A bunny rabbit with cream fur, very long upright ears with pink insides, pear-shaped body, big hind feet, round white cotton tail." + CH),
 "bear": (896, 1200, "A bear cub with cream fur, small round ears, white muzzle, dark-brown nose and paw pads." + CH),
 "backdrop": (1024, 2288, "A portrait phone game background seen straight down from above for a cozy sushi bar: at the top a cream wall with a wood wainscot and a small kitchen hatch with a noren curtain in the top left; a narrow pale wooden conveyor belt runs from the hatch into a large rounded-rectangle conveyor loop across the width of the screen between 15% and 40% of the height, with a pale wooden table inside the loop; below it a long wooden counter with four small empty ticket holders and a row of four short red cushioned benches; the bottom half is an even plain sage-green tatami floor. Empty: no animals, no food, no text, no UI."),
}
jobs = []
for st, sp in STYLES.items():
    for a, (w, h, p) in ASSETS.items():
        for seed in (5, 6):
            jobs.append(dict(name=f"clean_{st}_{a}_s{seed}", model="klein", w=w, h=h, seed=seed, prompt=sp + " Subject: " + p))
json.dump(jobs, open("batch4.json", "w"), indent=1); print(len(jobs))
