#!/usr/bin/env python3
"""Turns the promo artwork into the promo build's WebP assets.

The source images are 6 wide (3:2, 1536x1024), text-free pictures, saved as
promo-art-src/<name>.png at the repository root (git-ignored):

  films   promo_saltwater_lanterns, promo_quiet_orbit, promo_monsoon_express, promo_paper_kingdoms
  shows   promo_harbour_lines, promo_skybound_academy

Today they are Unsplash photos, credited in docs/promo-art/CREDITS.md; a replacement only needs
saving under the same name and running this again. From each one this writes, into
app/src/promo/res/drawable-nodpi/:

  films and shows   <name>_backdrop.webp   16:9 centre crop, 1920x1080
                    <name>_poster.webp     2:3 centre crop, 500x750, the title lettered near the top
                                           in Poppins (site/public/fonts) over a soft scrim
  shows             <name>_e1..eN.webp     episode thumbnails, 640x360: distinct crops of the key art

Lesson thumbnails, channel avatars, clips and the demo titles without art are drawn by the app
(its generated covers), as a real Telegram file without a picture is, so nothing is made for them.
Lettering the posters needs fontTools with brotli (`pip install fonttools brotli`) to read the
site's woff2. Every file is WebP at quality 88 with the settings AGENTS.md gives for screenshots.
Run it from anywhere:

  scripts/promo-art.py [--src promo-art-src] [--out app/src/promo/res/drawable-nodpi]

The desktop's promo render test reads the same directory, so both fixtures pick the files up.
"""
import argparse
import shutil
import subprocess
import sys
import tempfile
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
FONT = ROOT / "site" / "fonts" / "poppins-700.woff2"
WEBP = ["-define", "webp:method=6", "-define", "webp:sharp-yuv=true", "-quality", "88"]

# name: title lettered on the poster
FILMS = {
    "promo_saltwater_lanterns": "Saltwater Lanterns",
    "promo_quiet_orbit": "The Quiet Orbit",
    "promo_monsoon_express": "Monsoon Express",
    "promo_paper_kingdoms": "Paper Kingdoms",
}

# name: (title, episodes)
SHOWS = {
    "promo_harbour_lines": ("Harbour Lines", 4),
    "promo_skybound_academy": ("Skybound Academy", 4),
}

# Episode crops of the key art, as (width share, x centre, y centre) of a 16:9 window: the whole
# frame, then closer views left, right and centre, so no two episodes share a thumbnail.
CROPS = [(1.0, 0.5, 0.5), (0.62, 0.3, 0.55), (0.62, 0.7, 0.45), (0.5, 0.5, 0.6)]


def magick(*args):
    subprocess.run(["magick", *map(str, args)], check=True)


def size(path):
    out = subprocess.run(["magick", "identify", "-format", "%w %h", str(path)], check=True, capture_output=True, text=True)
    w, h = out.stdout.split()
    return int(w), int(h)


def ttf_font(tmp):
    """Poppins Bold as a TTF: ImageMagick cannot read the site's woff2 directly."""
    from fontTools.ttLib import TTFont

    font = TTFont(str(FONT))
    font.flavor = None
    path = Path(tmp) / "poppins-700.ttf"
    font.save(str(path))
    return path


def crop(src, out, width, height, share=1.0, cx=0.5, cy=0.5):
    """A window of the target's shape, [share] of the widest that fits, centred on (cx, cy)."""
    w, h = size(src)
    aspect = width / height
    cw = min(w, h * aspect) * share
    ch = cw / aspect
    x = min(max(cx * w - cw / 2, 0), w - cw)
    y = min(max(cy * h - ch / 2, 0), h - ch)
    magick(src, "-crop", f"{round(cw)}x{round(ch)}+{round(x)}+{round(y)}", "+repage",
           "-resize", f"{width}x{height}!", *WEBP, out)


def poster(src, out, title, font, tmp):
    base = Path(tmp) / "poster.png"
    crop_png = Path(tmp) / "poster-crop.png"
    w, h = size(src)
    cw = min(w, h * 2 / 3)
    ch = cw * 3 / 2
    magick(src, "-crop", f"{round(cw)}x{round(ch)}+{round((w - cw) / 2)}+{round((h - ch) / 2)}", "+repage",
           "-resize", "500x750!", crop_png)
    # A soft dark scrim over the top half, so white lettering reads on any sky; the title sits a
    # little below the top edge, clear of the resolution chip a card puts in its corner.
    scrim = Path(tmp) / "scrim.png"
    magick("-size", "500x380", "gradient:black-none", "-channel", "A", "-evaluate", "multiply", "0.6", "+channel", scrim)
    lettering = Path(tmp) / "title.png"
    magick("-background", "none", "-fill", "white", "-font", font, "-gravity", "center",
           "-size", "440x", "-pointsize", "46", "-interline-spacing", "-6", f"caption:{title.upper()}", lettering)
    shadow = Path(tmp) / "title-shadow.png"
    magick(lettering, "-background", "black", "-shadow", "60x6+0+2", shadow)
    magick(crop_png, scrim, "-gravity", "north", "-composite",
           shadow, "-gravity", "north", "-geometry", "+0+92", "-composite",
           lettering, "-gravity", "north", "-geometry", "+0+98", "-composite", base)
    magick(base, *WEBP, out)


def main():
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--src", type=Path, default=ROOT / "promo-art-src")
    parser.add_argument("--out", type=Path, default=ROOT / "app" / "src" / "promo" / "res" / "drawable-nodpi")
    args = parser.parse_args()
    if shutil.which("magick") is None:
        sys.exit("ImageMagick 7 (magick) is needed")
    wanted = list(FILMS) + list(SHOWS)
    missing = [n for n in wanted if not (args.src / f"{n}.png").exists()]
    if missing:
        sys.exit("missing in %s: %s" % (args.src, ", ".join(f"{n}.png" for n in missing)))
    args.out.mkdir(parents=True, exist_ok=True)
    with tempfile.TemporaryDirectory() as tmp:
        font = ttf_font(tmp)
        titled = {**FILMS, **{name: title for name, (title, _) in SHOWS.items()}}
        for name, title in titled.items():
            src = args.src / f"{name}.png"
            crop(src, args.out / f"{name}_backdrop.webp", 1920, 1080)
            poster(src, args.out / f"{name}_poster.webp", title, font, tmp)
        for name, (_, count) in SHOWS.items():
            for n, view in zip(range(1, count + 1), CROPS):
                crop(args.src / f"{name}.png", args.out / f"{name}_e{n}.webp", 640, 360, *view)
    print(f"wrote the promo art into {args.out.relative_to(ROOT) if args.out.is_relative_to(ROOT) else args.out}")


if __name__ == "__main__":
    main()
