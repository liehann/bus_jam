import json
W = " Centred on a plain pure white background (#FFFFFF), nothing else in the image, no ground shadow, no text."
CH = (" Cute chibi proportions: big round head about half its body height, simple round dark eyes with one small white dot, "
      "tiny nose, tiny smile, light blue collar with a small gold bell, sitting facing the viewer, full body." + W)
ASSETS = {
 "kitten": (896, 1200, "A kitten with cream fur, white muzzle and chest, pink inner ears." + CH),
 "fox": (896, 1200, "A fox with cream fur, pointed muzzle, big pointed ears with dark-brown backs, white chest, dark-brown socks, bushy tail with a white tip." + CH),
 "kitchen": (1024, 1024, "A small sushi kitchen hatch: a light wooden wall frame with a cream noren curtain split into three panels, the opening behind it dark, seen from the front." + W),
 "props": (1376, 1024, "A sprite sheet of separate items spaced well apart in a 3 by 2 grid, none touching: a round paper lantern, a small potted green plant, a teapot, a pair of chopsticks on a rest, a small blank wooden sign, a bowl of rice." + W),
}
FLAT = {
 "F1_flatcel": ("Flat 2D vector game illustration. Solid flat colour fills. Very minimal cel shading: at most one flat, slightly darker "
                "shadow shape per area, hard-edged, no gradients, no soft shading, no highlights except the eye dot. No texture, no fur "
                "strands, no wood grain, no noise. Even dark-brown outline (#4a2e22) of medium thickness on every shape. Soft pastel "
                "palette: cream, ivory, honey wood, peach, coral, sage, light blue."),
 "F2_clipart": ("Simple flat vector clip-art icon style, like a clean Adobe Illustrator drawing: every area filled with ONE solid flat "
                "colour, absolutely no shading, no shadows, no gradients, no highlights, no texture, no lighting effects. Clean uniform "
                "dark-brown outlines, simple geometric rounded shapes, low detail, no fur lines, no whiskers. Muted pastel palette: cream, "
                "honey, peach, sage, light blue."),
 "F3_geminisoft": ("Cozy casual mobile game art, flat 2D, matching a soft pastel children's picture book. Flat soft pastel colour fills "
                   "with only a very subtle single-tone cel shadow (barely darker than the base colour), no gradients, no glossy "
                   "highlights, no fur texture, no hatching. Smooth medium-weight dark-brown outlines with rounded ends. Desaturated "
                   "warm palette: cream, ivory, blonde wood, soft peach, muted coral, sage. Simple, calm, low detail."),
}
NEG = ("shading, shadows, gradient, soft shading, airbrush, glossy, shiny highlights, specular, fur texture, fur strands, hair detail, "
       "whiskers, wood grain, texture, noise, painterly, realistic, 3d render, volumetric lighting, drop shadow, grey background, phone frame, text")
jobs = []
for f, sp in FLAT.items():
    for a, (w, h, p) in ASSETS.items():
        jobs.append(dict(name=f"flat_{f}_{a}_light", model="qwenfast", steps=8, w=w, h=h, seed=5, prompt=sp + " Subject: " + p))
json.dump(jobs, open("batch7a.json", "w"))
full = []
for f, sp in FLAT.items():
    for a, (w, h, p) in ASSETS.items():
        full.append(dict(name=f"flat_{f}_{a}_full", model="qwen", w=w, h=h, seed=5, prompt=sp + " Subject: " + p, neg=NEG))
json.dump(full, open("batch7b.json", "w"))
print(len(jobs), len(full))
