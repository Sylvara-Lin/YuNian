# -*- coding: utf-8 -*-
"""YuNian (予念) app-icon generator.

Source artwork: an RGBA rounded-square app-icon mockup. This script trims the
transparent/ragged outer rim and emits every launcher asset the project needs:

  * app/src/main/res/mipmap-{mdpi,hdpi,xhdpi,xxhdpi,xxxhdpi}/ic_launcher.png
        square launcher icon at 48 / 72 / 96 / 144 / 192 px (alpha preserved)
  * app/src/main/res/mipmap-{...}/ic_launcher_round.png
        circular launcher icon (transparent corners, no white ring)
  * feature/profile/src/main/res/drawable/app_logo.png
        in-app logo, 192x192 rounded square
  * logo/logo.png
        repository logo, 512x512 (alpha preserved)

Usage:
    python tools/gen_icons_yunian.py [--source PATH]

Requires Pillow (PIL).
"""
from __future__ import annotations

import argparse
import os
import sys

from PIL import Image, ImageDraw

# Repository root = parent of this script's directory (tools/).
REPO_ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))

# Vendored source artwork (repo-relative, CWD-independent). Override with --source.
DEFAULT_SOURCE = os.path.join(REPO_ROOT, "tools", "icons", "yunian_icon_source.png")

# Density bucket -> launcher icon edge length in pixels.
MIPMAP_SIZES = {
    "mdpi": 48,
    "hdpi": 72,
    "xhdpi": 96,
    "xxhdpi": 144,
    "xxxhdpi": 192,
}

# Alpha values below this are treated as fully transparent (removes speckle).
ALPHA_FLOOR = 8
# Fraction of the (cropped) edge trimmed from every side to drop the frayed rim.
INSET_RATIO = 0.015
# Corner radius of the rounded-square outputs, as a fraction of the edge.
ROUNDED_RADIUS_RATIO = 0.22


def load_master(source: str) -> Image.Image:
    """Load the source artwork and trim transparent + ragged outer edges.

    Returns a square, centred, RGBA image whose content fills the canvas as
    tightly as possible.
    """
    if not os.path.exists(source):
        raise FileNotFoundError("source artwork not found: %s" % source)

    im = Image.open(source).convert("RGBA")

    # Drop near-transparent speckle so the bounding box is not inflated.
    alpha = im.getchannel("A").point(lambda v: 0 if v < ALPHA_FLOOR else v)
    im.putalpha(alpha)

    bbox = alpha.getbbox()
    if bbox:
        im = im.crop(bbox)

    # Trim the frayed outer rim.
    w, h = im.size
    inset = int(min(w, h) * INSET_RATIO)
    if inset > 0 and w - 2 * inset > 0 and h - 2 * inset > 0:
        im = im.crop((inset, inset, w - inset, h - inset))
        # Re-crop the bounding box after the inset (edges may now be empty).
        a2 = im.getchannel("A").getbbox()
        if a2:
            im = im.crop(a2)

    return _square_pad(im)


def _square_pad(im: Image.Image) -> Image.Image:
    """Centre ``im`` on a transparent square canvas of side = max(w, h)."""
    w, h = im.size
    side = max(w, h)
    if w == h:
        return im
    canvas = Image.new("RGBA", (side, side), (0, 0, 0, 0))
    canvas.paste(im, ((side - w) // 2, (side - h) // 2), im)
    return canvas


def square_icon(master: Image.Image, size: int) -> Image.Image:
    """Full (alpha-preserving) square icon at ``size`` x ``size``."""
    return master.resize((size, size), Image.LANCZOS)


def _masked(master: Image.Image, size: int, mask: Image.Image) -> Image.Image:
    base = master.resize((size, size), Image.LANCZOS)
    out = Image.new("RGBA", (size, size), (0, 0, 0, 0))
    out.paste(base, (0, 0), mask)
    return out


def round_icon(master: Image.Image, size: int) -> Image.Image:
    """Circular icon whose corners are fully transparent (no white ring)."""
    mask = Image.new("L", (size, size), 0)
    ImageDraw.Draw(mask).ellipse((0, 0, size - 1, size - 1), fill=255)
    return _masked(master, size, mask)


def rounded_icon(master: Image.Image, size: int,
                 radius_ratio: float = ROUNDED_RADIUS_RATIO) -> Image.Image:
    """Rounded-square icon (transparent outside the rounded rectangle)."""
    mask = Image.new("L", (size, size), 0)
    radius = max(1, int(size * radius_ratio))
    ImageDraw.Draw(mask).rounded_rectangle(
        (0, 0, size - 1, size - 1), radius=radius, fill=255)
    return _masked(master, size, mask)


def _save(im: Image.Image, abs_path: str) -> None:
    os.makedirs(os.path.dirname(abs_path), exist_ok=True)
    im.save(abs_path, "PNG", optimize=True)


def generate(source: str = DEFAULT_SOURCE, verbose: bool = True) -> list[str]:
    """Generate every icon asset; return the list of written absolute paths."""
    master = load_master(source)
    written: list[str] = []

    # Launcher icons (square + round) for every density bucket.
    for bucket, size in MIPMAP_SIZES.items():
        res_dir = os.path.join(REPO_ROOT, "app", "src", "main", "res",
                               "mipmap-%s" % bucket)
        sq = square_icon(master, size)
        rd = round_icon(master, size)
        p_sq = os.path.join(res_dir, "ic_launcher.png")
        p_rd = os.path.join(res_dir, "ic_launcher_round.png")
        _save(sq, p_sq)
        _save(rd, p_rd)
        written += [p_sq, p_rd]
        if verbose:
            print("  mipmap-%-7s ic_launcher.png %dx%d, ic_launcher_round.png %dx%d"
                  % (bucket, size, size, size, size))

    # In-app logo (rounded square, 192x192).
    logo192 = rounded_icon(master, 192)
    p_logo = os.path.join(REPO_ROOT, "feature", "profile", "src", "main", "res",
                          "drawable", "app_logo.png")
    _save(logo192, p_logo)
    written.append(p_logo)
    if verbose:
        print("  feature/profile drawable/app_logo.png 192x192 (rounded)")

    # Repository logo (512x512).
    repo_logo = square_icon(master, 512)
    p_repo = os.path.join(REPO_ROOT, "logo", "logo.png")
    _save(repo_logo, p_repo)
    written.append(p_repo)
    if verbose:
        print("  logo/logo.png 512x512")

    return written


def verify() -> None:
    """Read back every written asset and report size + corner/centre alpha."""
    checks = []
    for bucket, size in MIPMAP_SIZES.items():
        res_dir = os.path.join(REPO_ROOT, "app", "src", "main", "res",
                               "mipmap-%s" % bucket)
        checks.append((os.path.join(res_dir, "ic_launcher.png"), size, False))
        checks.append((os.path.join(res_dir, "ic_launcher_round.png"), size, True))

    print("\n== verify ==")
    ok = True
    for path, size, is_round in checks:
        with Image.open(path) as im:
            im = im.convert("RGBA")
            corner = im.getpixel((0, 0))
            centre = im.getpixel((size // 2, size // 2))
        flag = ""
        if im.size != (size, size):
            ok = False
            flag = "  << SIZE MISMATCH"
        if is_round and corner[3] != 0:
            ok = False
            flag += "  << ROUND CORNER NOT TRANSPARENT"
        print("  %-58s %s cornerA=%d centreA=%d%s"
              % (os.path.relpath(path, REPO_ROOT), im.size, corner[3],
                 centre[3], flag))

    # Non-mipmap assets.
    for rel, size in [("feature/profile/src/main/res/drawable/app_logo.png", 192),
                      ("logo/logo.png", 512)]:
        path = os.path.join(REPO_ROOT, rel)
        with Image.open(path) as im:
            im = im.convert("RGBA")
            print("  %-58s %s cornerA=%d"
                  % (rel, im.size, im.getpixel((0, 0))[3]))
            if im.size != (size, size):
                ok = False
                print("     << SIZE MISMATCH (expected %dx%d)" % (size, size))
    print("== verify %s ==" % ("PASS" if ok else "FAIL"))


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description="Generate YuNian app icons.")
    parser.add_argument("--source", default=DEFAULT_SOURCE,
                        help="path to the source RGBA artwork")
    parser.add_argument("--no-verify", action="store_true",
                        help="skip the read-back verification step")
    args = parser.parse_args(argv)

    print("source: %s" % args.source)
    written = generate(args.source, verbose=True)
    print("wrote %d files" % len(written))
    if not args.no_verify:
        verify()
    return 0


if __name__ == "__main__":
    sys.exit(main())
