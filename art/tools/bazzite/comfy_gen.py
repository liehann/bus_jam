#!/usr/bin/env python3
"""Drive bazzite's ComfyUI over HTTP: Flux.2 Klein 9B (optionally with reference
images) or Z-Image Turbo, explicit width/height, download results.

usage: gen.py JOBS.json OUTDIR   (JOBS = list of {name, model, prompt, w, h, refs?, seed?, steps?})
"""
import json, sys, time, uuid, urllib.request, urllib.parse, os, random

B = os.environ.get("COMFY", "http://192.168.1.164:8188")


def post(path, data, ctype="application/json"):
    req = urllib.request.Request(B + path, data=data, headers={"Content-Type": ctype})
    return json.load(urllib.request.urlopen(req, timeout=60))


def get(path):
    return urllib.request.urlopen(B + path, timeout=60).read()


def upload(path):
    bnd = uuid.uuid4().hex
    name = os.path.basename(path)
    body = (f"--{bnd}\r\nContent-Disposition: form-data; name=\"image\"; filename=\"{name}\"\r\n"
            f"Content-Type: application/octet-stream\r\n\r\n").encode() + open(path, "rb").read() + \
           f"\r\n--{bnd}\r\nContent-Disposition: form-data; name=\"overwrite\"\r\n\r\ntrue\r\n--{bnd}--\r\n".encode()
    return post("/upload/image", body, f"multipart/form-data; boundary={bnd}")["name"]


def klein(prompt, w, h, seed, refs=(), steps=4, prefix="cc"):
    g = {
        "unet": {"class_type": "UNETLoader", "inputs": {"unet_name": "flux-2-klein-9b-fp8.safetensors", "weight_dtype": "default"}},
        "clip": {"class_type": "CLIPLoader", "inputs": {"clip_name": "qwen_3_8b_fp8mixed.safetensors", "type": "flux2"}},
        "vae": {"class_type": "VAELoader", "inputs": {"vae_name": "flux2-vae.safetensors"}},
        "pos": {"class_type": "CLIPTextEncode", "inputs": {"text": prompt, "clip": ["clip", 0]}},
        "neg": {"class_type": "CLIPTextEncode", "inputs": {"text": "", "clip": ["clip", 0]}},
        "lat": {"class_type": "EmptyFlux2LatentImage", "inputs": {"width": w, "height": h, "batch_size": 1}},
    }
    cond = ["pos", 0]
    ncond = ["neg", 0]
    for i, r in enumerate(refs):
        g[f"li{i}"] = {"class_type": "LoadImage", "inputs": {"image": r}}
        g[f"sc{i}"] = {"class_type": "ImageScaleToTotalPixels", "inputs": {"image": [f"li{i}", 0], "upscale_method": "lanczos", "megapixels": 1.0, "resolution_steps": 16}}
        g[f"en{i}"] = {"class_type": "VAEEncode", "inputs": {"pixels": [f"sc{i}", 0], "vae": ["vae", 0]}}
        g[f"rl{i}"] = {"class_type": "ReferenceLatent", "inputs": {"conditioning": cond, "latent": [f"en{i}", 0]}}
        g[f"rn{i}"] = {"class_type": "ReferenceLatent", "inputs": {"conditioning": ncond, "latent": [f"en{i}", 0]}}
        cond, ncond = [f"rl{i}", 0], [f"rn{i}", 0]
    g.update({
        "noise": {"class_type": "RandomNoise", "inputs": {"noise_seed": seed}},
        "guider": {"class_type": "CFGGuider", "inputs": {"model": ["unet", 0], "positive": cond, "negative": ncond, "cfg": 1.0}},
        "smp": {"class_type": "KSamplerSelect", "inputs": {"sampler_name": "euler"}},
        "sig": {"class_type": "Flux2Scheduler", "inputs": {"steps": steps, "width": w, "height": h}},
        "run": {"class_type": "SamplerCustomAdvanced", "inputs": {"noise": ["noise", 0], "guider": ["guider", 0], "sampler": ["smp", 0], "sigmas": ["sig", 0], "latent_image": ["lat", 0]}},
        "dec": {"class_type": "VAEDecode", "inputs": {"samples": ["run", 0], "vae": ["vae", 0]}},
        "save": {"class_type": "SaveImage", "inputs": {"images": ["dec", 0], "filename_prefix": prefix}},
    })
    return g


def zimage(prompt, w, h, seed, steps=8, prefix="cc", **_):
    return {
        "unet": {"class_type": "UNETLoader", "inputs": {"unet_name": "z_image_turbo_bf16.safetensors", "weight_dtype": "default"}},
        "clip": {"class_type": "CLIPLoader", "inputs": {"clip_name": "qwen_3_4b_fp8_mixed.safetensors", "type": "lumina2"}},
        "vae": {"class_type": "VAELoader", "inputs": {"vae_name": "ae.safetensors"}},
        "ms": {"class_type": "ModelSamplingAuraFlow", "inputs": {"model": ["unet", 0], "shift": 3.0}},
        "pos": {"class_type": "CLIPTextEncode", "inputs": {"text": prompt, "clip": ["clip", 0]}},
        "neg": {"class_type": "CLIPTextEncode", "inputs": {"text": "", "clip": ["clip", 0]}},
        "lat": {"class_type": "EmptySD3LatentImage", "inputs": {"width": w, "height": h, "batch_size": 1}},
        "ks": {"class_type": "KSampler", "inputs": {"model": ["ms", 0], "positive": ["pos", 0], "negative": ["neg", 0], "latent_image": ["lat", 0],
                                                     "seed": seed, "steps": steps, "cfg": 1.0, "sampler_name": "res_multistep" if False else "euler", "scheduler": "simple", "denoise": 1.0}},
        "dec": {"class_type": "VAEDecode", "inputs": {"samples": ["ks", 0], "vae": ["vae", 0]}},
        "save": {"class_type": "SaveImage", "inputs": {"images": ["dec", 0], "filename_prefix": prefix}},
    }


def run(job, outdir, uploaded):
    seed = job.get("seed", random.randint(1, 2**31))
    for r in job.get("refs", []):
        if r not in uploaded:
            uploaded[r] = upload(r)
    refs = [uploaded[r] for r in job.get("refs", [])]
    if job["model"] == "klein":
        g = klein(job["prompt"], job["w"], job["h"], seed, refs, job.get("steps", 4), "cc_" + job["name"])
    else:
        g = zimage(job["prompt"], job["w"], job["h"], seed, job.get("steps", 8), "cc_" + job["name"])
    t0 = time.time()
    pid = post("/prompt", json.dumps({"prompt": g, "client_id": "critter-cafe"}).encode())["prompt_id"]
    while True:
        time.sleep(1.5)
        h = json.loads(get(f"/history/{pid}"))
        if pid in h:
            break
    st = h[pid]["status"]
    if st.get("status_str") != "success":
        print("FAIL", job["name"], json.dumps(st)[:1500]); return
    for o in h[pid]["outputs"].values():
        for im in o.get("images", []):
            q = urllib.parse.urlencode({"filename": im["filename"], "subfolder": im["subfolder"], "type": im["type"]})
            dst = os.path.join(outdir, f"{job['name']}.png")
            open(dst, "wb").write(get(f"/view?{q}"))
            print(f"ok {job['name']} {time.time()-t0:.1f}s seed={seed} -> {dst}", flush=True)


if __name__ == "__main__":
    jobs = json.load(open(sys.argv[1]))
    os.makedirs(sys.argv[2], exist_ok=True)
    up = {}
    for j in jobs:
        run(j, sys.argv[2], up)
