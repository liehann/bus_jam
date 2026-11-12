#!/usr/bin/env python3
"""MiniMax H3 (open-weights video model) on bazzite's ComfyUI, native nodes.

Mirrors the "Image to Video (MiniMax H3)" template subgraph: UNETLoader -> turbo LoRA -> BasicGuider,
res_multistep / simple, MiniMaxH3ImageToVideo (optional first frame), video + audio VAE decode.

  COMFY=http://192.168.1.164:8188 python3 h3_gen.py jobs.json out/

Job fields: name, prompt, w, h, seconds (or frames), seed, steps (8 with the turbo LoRA), first_frame / last_frame (paths; the same image in both gives a loop),
still (true -> 5 frames, saves frame 0 as PNG via SaveImage). Video jobs save an MP4 with audio.
"""
import json
import os
import sys
import time
import urllib.parse

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from comfy_gen import get, post, upload  # noqa: E402

UNET = os.environ.get("H3_UNET", "minimax_h3_fl2va_pruned_fp8_scaled.safetensors")
CLIP = os.environ.get("H3_CLIP", "qwen3vl_32b_minimax_h3_nvfp4_awq.safetensors")
VVAE = os.environ.get("H3_VAE", "minimax_h3_video_vae_fp16.safetensors")
AVAE = "minimax_h3_audio_vae_fp32.safetensors"
LORA = "minimax_h3_fl2v_turbo_8step_v1.0_comfyui_bf16.safetensors"


def frames_for(seconds):
    n = max(5, round(seconds * 24))
    return n + (5 - n % 17) % 17  # the model's 17k+5 grid


def graph(job, first=None, last=None):
    still = job.get("still", False)
    length = 5 if still else job.get("frames") or frames_for(job.get("seconds", 2))
    steps = job.get("steps", 8)
    lora = job.get("lora", True)
    g = {
        "unet": {"class_type": "UNETLoader", "inputs": {"unet_name": UNET, "weight_dtype": "default"}},
        "clip": {"class_type": "CLIPLoader", "inputs": {"clip_name": CLIP, "type": "minimax", "device": "default"}},
        "vvae": {"class_type": "VAELoader", "inputs": {"vae_name": VVAE}},
        "i2v": {"class_type": "MiniMaxH3ImageToVideo", "inputs": {"clip": ["clip", 0], "vae": ["vvae", 0], "prompt": job["prompt"],
                                                                  "width": job["w"], "height": job["h"], "length": length}},
        "noise": {"class_type": "RandomNoise", "inputs": {"noise_seed": job.get("seed", 1)}},
        "sampler": {"class_type": "KSamplerSelect", "inputs": {"sampler_name": "res_multistep"}},
        "guider": {"class_type": "BasicGuider", "inputs": {"model": ["lora", 0] if lora else ["unet", 0], "conditioning": ["i2v", 0]}},
        "sched": {"class_type": "BasicScheduler", "inputs": {"model": ["lora", 0] if lora else ["unet", 0], "scheduler": "simple",
                                                             "steps": steps if lora else job.get("steps", 20), "denoise": 1.0}},
        "sca": {"class_type": "SamplerCustomAdvanced", "inputs": {"noise": ["noise", 0], "guider": ["guider", 0], "sampler": ["sampler", 0],
                                                                  "sigmas": ["sched", 0], "latent_image": ["i2v", 1]}},
        "dec": {"class_type": "VAEDecode", "inputs": {"samples": ["sca", 0], "vae": ["vvae", 0]}},
    }
    if lora:
        g["lora"] = {"class_type": "LoraLoaderModelOnly", "inputs": {"model": ["unet", 0], "lora_name": LORA, "strength_model": 1.0}}
    if first:
        g["ff"] = {"class_type": "LoadImage", "inputs": {"image": first}}
        g["i2v"]["inputs"]["first_frame"] = ["ff", 0]
    if last:
        g["lf"] = {"class_type": "LoadImage", "inputs": {"image": last}}
        g["i2v"]["inputs"]["last_frame"] = ["lf", 0]
    prefix = "h3_" + job["name"]
    if still:
        g["one"] = {"class_type": "ImageFromBatch", "inputs": {"image": ["dec", 0], "batch_index": 0, "length": 1}}
        g["save"] = {"class_type": "SaveImage", "inputs": {"images": ["one", 0], "filename_prefix": prefix}}
    else:
        g["avae"] = {"class_type": "VAELoader", "inputs": {"vae_name": AVAE}}
        g["adec"] = {"class_type": "VAEDecodeAudio", "inputs": {"samples": ["sca", 0], "vae": ["avae", 0]}}
        g["vid"] = {"class_type": "CreateVideo", "inputs": {"images": ["dec", 0], "audio": ["adec", 0], "fps": 24}}
        g["save"] = {"class_type": "SaveVideo", "inputs": {"video": ["vid", 0], "filename_prefix": "video/" + prefix,
                                                           "format": "auto", "codec": "auto"}}
    return g


def run(job, outdir):
    first = upload(job["first_frame"]) if job.get("first_frame") else None
    last = upload(job["last_frame"]) if job.get("last_frame") else None
    t0 = time.time()
    pid = post("/prompt", json.dumps({"prompt": graph(job, first, last), "client_id": "critter-cafe-h3"}).encode())["prompt_id"]
    while True:
        time.sleep(3)
        h = json.loads(get(f"/history/{pid}"))
        if pid in h:
            break
    st = h[pid]["status"]
    if st.get("status_str") != "success":
        print("FAIL", job["name"], json.dumps(st)[:2000], flush=True)
        return
    for o in h[pid]["outputs"].values():
        for im in o.get("images", []) + o.get("videos", []) + o.get("gifs", []):
            q = urllib.parse.urlencode({"filename": im["filename"], "subfolder": im["subfolder"], "type": im["type"]})
            dst = os.path.join(outdir, f"h3_{job['name']}{os.path.splitext(im['filename'])[1]}")
            open(dst, "wb").write(get(f"/view?{q}"))
            print(f"ok {job['name']} {time.time() - t0:.0f}s -> {dst}", flush=True)


if __name__ == "__main__":
    jobs = json.load(open(sys.argv[1]))
    os.makedirs(sys.argv[2], exist_ok=True)
    for j in jobs:
        run(j, sys.argv[2])
