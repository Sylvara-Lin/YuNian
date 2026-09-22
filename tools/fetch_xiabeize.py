# -*- coding: utf-8 -*-
"""下载并处理「下北泽传奇」团队头像。

流程：
  1. 下载 https://lianyu.chat/assets/team/xiabeize.png 到临时文件
  2. 中心裁剪为正方形
  3. 缩放到 256x256（LANCZOS）
  4. 保存到 feature/profile/src/main/res/drawable/team_xiabeize.png
  5. 回读校验：尺寸 = 256x256、mode=RGBA、非全透明/非空
"""
from __future__ import annotations

import io
import os
import sys
import urllib.request

from PIL import Image

URL = "https://lianyu.chat/assets/team/xiabeize.png"
OUT = r"H:\susu\feature\profile\src\main\res\drawable\team_xiabeize.png"
TARGET = 256
UA = (
    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
    "(KHTML, like Gecko) Chrome/124.0 Safari/537.36"
)


def download(url: str) -> bytes:
    """带 UA 下载图片，返回原始字节。"""
    req = urllib.request.Request(url, headers={"User-Agent": UA, "Accept": "image/*"})
    with urllib.request.urlopen(req, timeout=60) as resp:
        status = getattr(resp, "status", 200)
        data = resp.read()
    if status != 200:
        raise RuntimeError(f"HTTP {status} while downloading {url}")
    if not data:
        raise RuntimeError(f"Empty response body from {url}")
    return data


def process(raw: bytes) -> Image.Image:
    """中心裁剪正方形并缩放到 TARGET x TARGET。"""
    img = Image.open(io.BytesIO(raw))
    img.load()
    img = img.convert("RGBA")
    w, h = img.size
    side = min(w, h)
    left = (w - side) // 2
    top = (h - side) // 2
    img = img.crop((left, top, left + side, top + side))
    img = img.resize((TARGET, TARGET), Image.LANCZOS)
    return img


def main() -> int:
    raw = download(URL)
    print(f"[download] {len(raw)} bytes")
    img = process(raw)
    os.makedirs(os.path.dirname(OUT), exist_ok=True)
    img.save(OUT, format="PNG")
    print(f"[save] {OUT}")

    # 回读校验
    check = Image.open(OUT)
    check.load()
    size = check.size
    mode = check.mode
    extrema = check.convert("RGBA").getextrema()
    alpha_min = extrema[3][0]
    print(f"[verify] size={size} mode={mode} extrema={extrema}")
    if size != (TARGET, TARGET):
        raise RuntimeError(f"Size mismatch: {size} != ({TARGET}, {TARGET})")
    if alpha_min == 0 and extrema[0] == (0, 0) and extrema[1] == (0, 0) and extrema[2] == (0, 0):
        raise RuntimeError("Image is fully transparent / blank")
    print("[ok] team_xiabeize.png verified 256x256 readable, non-empty")
    return 0


if __name__ == "__main__":
    sys.exit(main())
