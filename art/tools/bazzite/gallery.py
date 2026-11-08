#!/usr/bin/env python3
"""Build a self-contained comparison page: Gemini reference vs bazzite variants."""
import base64, glob, os, re, subprocess, sys, html

srcs, out = sys.argv[1:-1], sys.argv[-1]
REFS = os.environ.get("GEMINI_REFS", "refs")  # flattened copies of art/raw, e.g. sushi_kit_bench.jpeg
REF = {
 "kit_floor": "sushi_kit_floor", "kit_board": "sushi_kit_board", "kit_belt": "sushi_kit_belt",
 "kit_counter": "sushi_kit_counter", "kit_bench": "sushi_kit_bench", "kit_stand": "sushi_kit_stand",
 "kit_hatch": "sushi_kit_hatch", "kit_decor": "sushi_kit_decor", "backdrop": "sushi_backdrop_v2",
 "crit_bunny": "fox_fox_front", "crit_bear": "red_panda_red_panda_front", "crit_raccoon": "wolf_wolf_front",
 "crit_penguin": "kitten_kitten_front", "luau_hatch": "sushi_kit_hatch", "luau_bench": "sushi_kit_bench",
 "luau_decor": "sushi_kit_decor",
}
LABEL = {"kleinref": "Klein 9B + Gemini style ref", "kleintxtB": "Klein 9B, detailed style prompt",
         "kleintxtA": "Klein 9B, short style prompt", "zimageB": "Z-Image Turbo, detailed style prompt"}
ORDER = ["kleinref_s1", "kleinref_s2", "kleintxtB_s1", "kleintxtA_s1", "zimageB_s1"]


def thumb(path, px=520):
    b = subprocess.run(["convert", path, "-resize", f"{px}x{px}>", "-quality", "82", "jpg:-"], capture_output=True, check=True).stdout
    return "data:image/jpeg;base64," + base64.b64encode(b).decode()


files = sorted(f for s_ in srcs for f in glob.glob(os.path.join(s_, "*.png")))
groups = {}
for f in files:
    m = re.match(r"(.+)_(kleinref_s\d|kleintxt[AB]_s\d|zimageB_s\d)\.png$", os.path.basename(f))
    if m:
        groups.setdefault(m.group(1), {})[m.group(2)] = f
rows = []
for g, vs in groups.items():
    cells = []
    r = REF.get(g.replace("kit3_", "kit_"))
    m2 = re.match(r"crit3?_(\w+?)_(front|left)$", g)
    if m2:
        sp, view = m2.groups()
        r = f"{sp}_{sp}_{view}" if os.path.exists(f"{REFS}/{sp}_{sp}_{view}.jpeg") else f"kitten_kitten_{view}"
    if r:
        rp = f"{REFS}/{r}" + (".jpg" if r.startswith("kitten") else ".jpeg")
        same = m2 and not r.startswith("kitten")
        note = "Gemini (same piece)" if (not g.startswith(("crit", "luau")) or (m2 and same)) else "Gemini (style ref, different subject)"
        cells.append(f'<figure class="gem"><img src="{thumb(rp)}"><figcaption>{note}</figcaption></figure>')
    for k in ORDER:
        if k in vs:
            base, seed = k.rsplit("_", 1)
            cells.append(f'<figure><img src="{thumb(vs[k])}"><figcaption>{LABEL[base]} · {seed}<br><code>{html.escape(os.path.basename(vs[k]))}</code></figcaption></figure>')
    rows.append(f'<section><h2>{html.escape(g)}</h2><div class="row">{"".join(cells)}</div></section>')

page = f"""<!doctype html><meta charset="utf-8"><title>Critter Café: bazzite vs Gemini</title>
<style>body{{font:14px system-ui;background:#f6f1e8;color:#3a2a20;margin:24px}}
.row{{display:flex;gap:12px;overflow-x:auto;padding-bottom:8px}}figure{{margin:0;background:#fff;padding:8px;border-radius:10px;flex:0 0 auto}}
figure.gem{{outline:3px solid #c98a4b}}img{{max-height:340px;max-width:420px;display:block}}figcaption{{font-size:12px;margin-top:6px;max-width:420px}}
h2{{font-size:16px;margin:28px 0 8px}}code{{font-size:11px;color:#8a7a6a}}</style>
<h1>Critter Café art: bazzite (local) vs Gemini</h1>
<p><b>Rounds:</b> <code>kit_*</code>, <code>backdrop</code>, <code>luau_*</code> and <code>crit_bunny/bear/raccoon/penguin</code> (natural colours, fox + red panda as refs) = round 1.
<code>crit_&lt;species&gt;_front/left</code> = round 2, the real pipeline recipe (kitten front + side as refs, cream fur). <code>crit3_*</code> and <code>kit3_*</code> = round 3, prompt fixes for the round-2 failures.</p>
<p>Orange outline = existing Gemini art. Bazzite: RX 9060 XT 16 GB, ComfyUI, models already on the box. Sizes are exact (set per asset).</p>
{''.join(rows)}"""
open(out, "w").write(page)
print(out, len(groups), "groups", os.path.getsize(out) // 1024, "KB")
