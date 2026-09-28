# -*- coding: utf-8 -*-
"""Fetch and process team member avatars from lianyu.chat.

Downloads the 6 core team member avatars, center-crops them to a square,
resizes to 256x256 and saves them as PNG into the profile module's
drawable resource directory.

Usage:
    python tools/fetch_team_avatars.py
"""

import io
import os
import sys
import urllib.request

from PIL import Image

# Target drawable directory (relative to repo root).
REPO_ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
DRAWABLE_DIR = os.path.join(
    REPO_ROOT, "feature", "profile", "src", "main", "res", "drawable"
)

# Output avatar size in pixels.
AVATAR_SIZE = 256

# (output filename without extension, source URL)
AVATARS = [
    ("team_linruoxo", "https://lianyu.chat/assets/team/linruoxo666.png"),
    ("team_qiyuanxiaosu", "https://lianyu.chat/assets/team/qiyuanxiaosu.png"),
    ("team_yuansi", "https://lianyu.chat/assets/team/yuansi.png"),
    ("team_qingsiyu", "https://lianyu.chat/assets/team/qingsiyu.jpg"),
    ("team_clove", "https://lianyu.chat/assets/clove.jpg"),
    ("team_hanfuqing", "https://q1.qlogo.cn/g?b=qq&nk=1721822150&s=640"),
]

USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) fetch_team_avatars/1.0"


def download(url: str) -> bytes:
    """Download raw bytes from a URL with a browser-like user agent."""
    request = urllib.request.Request(url, headers={"User-Agent": USER_AGENT})
    with urllib.request.urlopen(request, timeout=30) as response:
        data = response.read()
    if not data:
        raise RuntimeError(f"empty response body: {url}")
    return data


def process(raw: bytes) -> Image.Image:
    """Center-crop to square and resize to AVATAR_SIZE x AVATAR_SIZE."""
    image = Image.open(io.BytesIO(raw))
    image.load()
    # Keep alpha channel when present, otherwise normalize to RGB.
    if image.mode in ("RGBA", "LA", "PA"):
        image = image.convert("RGBA")
    else:
        image = image.convert("RGB")
    width, height = image.size
    side = min(width, height)
    left = (width - side) // 2
    top = (height - side) // 2
    image = image.crop((left, top, left + side, top + side))
    return image.resize((AVATAR_SIZE, AVATAR_SIZE), Image.LANCZOS)


def verify(path: str) -> tuple:
    """Re-open the saved file with PIL to prove it is a valid image."""
    with Image.open(path) as image:
        image.load()
        return image.size, image.mode


def main() -> int:
    os.makedirs(DRAWABLE_DIR, exist_ok=True)
    failures = []
    for name, url in AVATARS:
        out_path = os.path.join(DRAWABLE_DIR, name + ".png")
        try:
            raw = download(url)
            image = process(raw)
            image.save(out_path, format="PNG")
            size, mode = verify(out_path)
            file_bytes = os.path.getsize(out_path)
            if file_bytes <= 0:
                raise RuntimeError("saved file is empty")
            print(f"OK   {name}.png  {size[0]}x{size[1]} {mode} {file_bytes}B  <- {url}")
        except Exception as exc:  # noqa: BLE001 - report and continue others
            failures.append((name, url, str(exc)))
            print(f"FAIL {name}  <- {url}  ERROR: {exc}")
    if failures:
        print("\n%d avatar(s) failed:" % len(failures))
        for name, url, err in failures:
            print(f"  - {name}: {err}")
        return 1
    print("\nAll 6 avatars fetched and verified.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
