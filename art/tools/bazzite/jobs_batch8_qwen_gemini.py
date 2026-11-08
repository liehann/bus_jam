import json
W = " Full body, sitting, facing the viewer, centred on a plain pure white background (#FFFFFF), nothing else in the image, no ground shadow, no text."
AN = {
 "kitten": "a kitten: cream fur, white muzzle, cheeks and chest, pink inner ears, a long tail curled beside it",
 "fox": "a fox: cream-peach fur, pointed ears with dark-brown backs and pink insides with white fluff, white cheeks and chest, dark-brown socks on the legs, a big bushy tail with a white tip",
 "red_panda": "a red panda: cream fur, white ear rims, white eyebrow spots and muzzle, dark-brown tear stripes under the eyes, dark-brown legs, a bushy cream tail with dark-brown rings",
 "wolf": "a wolf pup: cream fur, tall pointed ears, a dark-brown patch on top of the head, a white muzzle and fluffy white chest ruff, a bushy tail with a dark-brown tip",
}
COMMON = " It wears a light blue-grey collar with a small gold bell."
P = {
 "P1_described": ("Cute chibi animal character for a cozy mobile game, in this exact art style: smooth flat pastel colour fills with only very soft, "
     "barely visible airbrushed shading at the edges, no hard shadows, no texture. Medium-thick warm dark-brown outlines (#5a3a2c) around "
     "the silhouette, thinner and lighter brown inner lines. Big round head (about half the body height), chubby rounded body, short legs. "
     "Eyes: small solid dark-brown circles with one soft light-brown highlight dot, widely spaced. Tiny rounded pink nose, a thin brown "
     "'w'-shaped mouth. No whiskers, no fur strands; only a few soft white fluff tufts at the cheeks, chest and inner ears. Desaturated warm "
     "pastel palette: peach-cream, ivory, soft pink, dark chocolate brown. Subject: {a}." + COMMON + W),
 "P2_flatsoft": ("Flat vector clip-art style, kawaii: every area is one flat soft pastel colour with at most a very faint lighter or darker "
     "tint near the edges, no shadows, no gradients, no glossy highlights, no texture, no whiskers, no fur lines. Uniform medium-thick "
     "warm dark-brown outline, simple rounded shapes, chibi proportions with a big head, small dark dot eyes with one tiny highlight, "
     "tiny pink nose, small 'w' mouth. Muted peach-cream and ivory palette. Subject: {a}." + COMMON + W),
 "P3_short": ("A cute, simple kawaii chibi {a}, soft pastel colours, flat and smooth, thick brown outline, tiny dot eyes, like a sticker in a "
     "children's game." + COMMON + W),
 "P4_ref": ("Draw a NEW character in exactly the same art style as the reference image: same outline colour and thickness, same smooth flat "
     "pastel colouring with very soft shading, same eye, nose and mouth style, same chibi proportions and sitting pose, same collar and bell. "
     "The new character is {a}. It must clearly be that animal, not the animal in the reference." + COMMON + W),
}
NEG = ("hard shadows, cel shading, gradient, glossy, shiny highlights, big sparkly eyes, white eye highlights, fur texture, fur strands, "
       "whiskers, hatching, texture, noise, painterly, realistic, 3d render, drop shadow, grey background, phone frame, text, orange fur")
REF_FOR = {"kitten": "fox_fox_front", "fox": "red_panda_red_panda_front", "red_panda": "wolf_wolf_front", "wolf": "fox_fox_front"}
jobs = []
for p, tpl in P.items():
    for a, d in AN.items():
        j = dict(name=f"g_{p}_{a}", model="qwen", w=896, h=1200, seed=7, prompt=tpl.format(a=d), neg=NEG)
        if p == "P4_ref":
            j["refs"] = [f"refsm/{REF_FOR[a]}.jpg"]
        jobs.append(j)
json.dump(jobs, open("batch8.json", "w"), indent=1); print(len(jobs))
