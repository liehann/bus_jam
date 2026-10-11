#!/usr/bin/env python3
"""Render a manual prompt sheet (Markdown) to HTML with attachment previews and copy buttons, then open it.

Usage: python3 tools/art/md_preview.py art/prompts/manual/<name>.md [out.html]
Attachments are the repo paths in the list under "Attach"; every `path` that exists becomes a file:// link.
"""
import re
import subprocess
import sys
import tempfile
from pathlib import Path

import markdown

ROOT = Path(__file__).resolve().parents[2]


def main(argv):
    src = ROOT / argv[1]
    out = Path(argv[2]) if len(argv) > 2 else Path(tempfile.gettempdir()) / (src.stem + ".html")
    md = src.read_text(encoding="utf-8")
    attach = re.findall(r"^\d+\. `([^`]+)`", md.split("## ")[0], flags=re.M)
    body = markdown.markdown(md, extensions=["extra"])

    def link(m):
        f = ROOT / m.group(1)
        return f'<a href="file://{f}"><code>{m.group(1)}</code></a>' if f.exists() else m.group(0)

    body = re.sub(r"<code>([^<]+)</code>", link, body)
    thumbs = "".join(f'<figure><img src="file://{ROOT / p}"><figcaption>{p}</figcaption></figure>' for p in attach)
    html = f"""<!doctype html><meta charset="utf-8"><title>{src.stem}</title>
<style>
body{{font:16px/1.55 -apple-system,system-ui,sans-serif;max-width:820px;margin:32px auto;padding:0 16px;color:#3b2a20;background:#fbf6ee}}
h1,h2{{color:#6b3f24}} code{{background:#f0e6d8;padding:1px 4px;border-radius:4px}}
.att{{display:flex;gap:16px;flex-wrap:wrap}} figure{{margin:0}} figure img{{height:320px;border:1px solid #d8c8b0;border-radius:6px}}
figcaption{{font-size:12px;color:#7a6650}}
.copy{{float:right;font-size:13px;padding:4px 10px;border-radius:6px;border:1px solid #b8946a;background:#fff;cursor:pointer}}
section.prompt{{background:#fff;border:1px solid #e3d5c0;border-radius:8px;padding:4px 16px 12px;margin:12px 0}}
</style>
<div class="att">{thumbs}</div>
{body}
<script>
document.querySelectorAll('h2').forEach(h=>{{
  if(!/^Prompt/.test(h.textContent))return;
  const box=document.createElement('section');box.className='prompt';
  let n=h.nextElementSibling;const nodes=[];
  while(n&&n.tagName!=='H2'){{nodes.push(n);n=n.nextElementSibling;}}
  h.after(box);nodes.forEach(x=>box.appendChild(x));
  const b=document.createElement('button');b.className='copy';b.textContent='Copy prompt';
  b.onclick=()=>{{navigator.clipboard.writeText(box.innerText.replace('Copy prompt','').trim());b.textContent='Copied';setTimeout(()=>b.textContent='Copy prompt',1500)}};
  box.prepend(b);
}});
</script>"""
    out.write_text(html, encoding="utf-8")
    subprocess.run(["open", "-a", "Google Chrome", str(out)], check=False)
    print(out)


if __name__ == "__main__":
    main(sys.argv)
