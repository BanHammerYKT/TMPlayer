#!/usr/bin/env python3
"""Crops a screenshot and draws hand-drawn marks into it, the same way for every shot.

One small JSON spec per image, in scripts/annotations/<name>.json, says which raw capture to start
from, what part of it to keep, how big to make it and what to mark. A retaken capture is
re-annotated with one command, and every annotated picture on the site and in the app's onboarding
wears the same arrows, circles and underlines.

Usage:
  scripts/annotate-shot.py scripts/annotations/tv-sign-in.json   one spec
  scripts/annotate-shot.py --all                                 every spec in scripts/annotations/
  scripts/annotate-shot.py --raw DIR SPEC...                     raw captures from DIR

Raw captures are the full PNGs off the device or emulator (1920x1080 for the TV). They are not
committed (see AGENTS.md: only WebP goes into the repository); keep them in build/shots/, the
default --raw directory, under the file name the spec's "source" gives.

Spec:
  {
    "source": "tv-sign-in-dark.png",          raw capture, relative to --raw
    "output": "site/public/screenshots/tv-sign-in.webp",   relative to the repository
    "crop": [x, y, width, height],            in the raw capture's pixels; default the whole frame
    "width": 1200,                            output width; default the crop's own width. Height
                                              follows the crop's aspect ratio. Going past the
                                              crop's width upscales, which a shot should not need.
    "quality": 88,                            WebP quality; 88 is the settled figure (AGENTS.md)
    "marks": [ ... ]                          drawn in order, coordinates in raw capture pixels
  }

Marks (every coordinate in the raw capture's pixels, so a mark stays put when the crop changes):
  {"type": "arrow", "from": [x, y], "to": [x, y], "bend": 0.25}
      A curved arrow with an open head at "to". "bend" bows it sideways by that share of its
      length; negative bows the other way.
  {"type": "circle", "box": [x, y, width, height]}
      A loose ellipse around the box, a little bigger than it, whose ends overshoot and do not meet.
  {"type": "underline", "from": [x, y], "to": [x, y]}
      A slightly curved stroke under something.
  Any mark takes "seed" (an integer) to change its wobble, and "weight" (default 1.0) to scale
  its stroke.

Nothing here writes text into a picture. A label belongs in the page's HTML beside the image,
where it is translated with everything else.

Needs Pillow and ImageMagick's `magick`, which does the WebP encode.

The marks are drawn at twice the output size and scaled down, which is the antialiasing. The
stroke is a share of the output width, so a mark looks the same weight on every shot.
"""

import argparse
import json
import math
import random
import subprocess
import sys
import tempfile
from pathlib import Path

from PIL import Image, ImageDraw

ROOT = Path(__file__).resolve().parent.parent
SPECS = ROOT / "scripts" / "annotations"
RAW = ROOT / "build" / "shots"

# One accent for every shot, warm so it reads against the app's blues, with a dark edge so it holds
# on a white light-theme screen and on a black player alike.
ACCENT = (255, 178, 36, 255)
EDGE = (24, 16, 4, 170)
SUPERSAMPLE = 2
# Stroke as a share of the output width, and the edge's extra width on each side of it.
STROKE = 0.0095
EDGE_RATIO = 0.35


def bezier(p0, c, p1, steps):
    """A quadratic Bezier from p0 through control c to p1, as a list of points."""
    out = []
    for i in range(steps + 1):
        t = i / steps
        a = (1 - t) ** 2
        b = 2 * (1 - t) * t
        d = t ** 2
        out.append((a * p0[0] + b * c[0] + d * p1[0], a * p0[1] + b * c[1] + d * p1[1]))
    return out


def wobble(points, amount, rng):
    """Moves each point sideways along a slow wave, so the line looks drawn by hand."""
    if len(points) < 3 or amount <= 0:
        return points
    phase = rng.uniform(0, math.tau)
    phase2 = rng.uniform(0, math.tau)
    out = []
    n = len(points) - 1
    for i, (x, y) in enumerate(points):
        j0, j1 = max(i - 1, 0), min(i + 1, n)
        tx, ty = points[j1][0] - points[j0][0], points[j1][1] - points[j0][1]
        length = math.hypot(tx, ty) or 1.0
        nx, ny = -ty / length, tx / length
        t = i / n
        # Fades in and out, so the ends land where they were asked to.
        envelope = math.sin(math.pi * t)
        offset = amount * envelope * (0.7 * math.sin(2 * math.pi * 1.3 * t + phase) + 0.3 * math.sin(2 * math.pi * 3.1 * t + phase2))
        out.append((x + nx * offset, y + ny * offset))
    return out


def stroke(draw, points, width, colour):
    """A polyline with round joins and round caps."""
    if len(points) < 2:
        return
    draw.line(points, fill=colour, width=int(round(width)), joint="curve")
    r = width / 2
    for x, y in (points[0], points[-1]):
        draw.ellipse((x - r, y - r, x + r, y + r), fill=colour)


def arrow_paths(mark, to_canvas, unit, rng):
    p0 = to_canvas(mark["from"])
    p1 = to_canvas(mark["to"])
    dx, dy = p1[0] - p0[0], p1[1] - p0[1]
    length = math.hypot(dx, dy) or 1.0
    bend = float(mark.get("bend", 0.25))
    mid = ((p0[0] + p1[0]) / 2, (p0[1] + p1[1]) / 2)
    control = (mid[0] - dy / length * bend * length, mid[1] + dx / length * bend * length)
    shaft = wobble(bezier(p0, control, p1, 64), unit * 0.35, rng)
    # The head follows the curve's last stretch, so it points the way the line arrives.
    tail = shaft[-6]
    angle = math.atan2(p1[1] - tail[1], p1[0] - tail[0])
    head = unit * 4.2
    paths = [shaft]
    for side, spread in ((-1, 0.52), (1, 0.46)):
        a = angle + math.pi + side * (spread + rng.uniform(-0.05, 0.05))
        end = (p1[0] + math.cos(a) * head * rng.uniform(0.92, 1.05), p1[1] + math.sin(a) * head * rng.uniform(0.92, 1.05))
        paths.append(wobble(bezier(p1, ((p1[0] + end[0]) / 2, (p1[1] + end[1]) / 2), end, 12), unit * 0.12, rng))
    return paths


def circle_paths(mark, to_canvas, unit, rng):
    x, y, w, h = mark["box"]
    (x0, y0), (x1, y1) = to_canvas((x, y)), to_canvas((x + w, y + h))
    cx, cy = (x0 + x1) / 2, (y0 + y1) / 2
    # A little room around the box, as a pen would leave.
    rx = (x1 - x0) / 2 * 1.12 + unit * 1.5
    ry = (y1 - y0) / 2 * 1.18 + unit * 1.5
    start = rng.uniform(-2.6, -2.0)
    sweep = math.tau + rng.uniform(0.35, 0.55)
    tilt = rng.uniform(-0.05, 0.05)
    steps = 120
    points = []
    for i in range(steps + 1):
        t = i / steps
        a = start + sweep * t
        # Grows a touch as it goes round, so the ends pass each other instead of closing.
        grow = 1 + 0.07 * t + 0.025 * math.sin(3 * a + start)
        px, py = math.cos(a) * rx * grow, math.sin(a) * ry * grow
        points.append((cx + px * math.cos(tilt) - py * math.sin(tilt), cy + px * math.sin(tilt) + py * math.cos(tilt)))
    return [points]


def underline_paths(mark, to_canvas, unit, rng):
    p0 = to_canvas(mark["from"])
    p1 = to_canvas(mark["to"])
    dx, dy = p1[0] - p0[0], p1[1] - p0[1]
    length = math.hypot(dx, dy) or 1.0
    sag = length * rng.uniform(0.025, 0.045)
    mid = ((p0[0] + p1[0]) / 2 + dy / length * sag, (p0[1] + p1[1]) / 2 - dx / length * -sag)
    return [wobble(bezier(p0, mid, p1, 48), unit * 0.25, rng)]


SHAPES = {"arrow": arrow_paths, "circle": circle_paths, "underline": underline_paths}


def render(spec_path: Path, raw_dir: Path) -> Path:
    spec = json.loads(spec_path.read_text())
    source = raw_dir / spec["source"]
    if not source.is_file():
        sys.exit(f"{spec_path.name}: raw capture not found: {source}")
    image = Image.open(source).convert("RGB")
    cx, cy, cw, ch = spec.get("crop", [0, 0, image.width, image.height])
    out_w = int(spec.get("width", cw))
    out_h = round(out_w * ch / cw)
    big_w, big_h = out_w * SUPERSAMPLE, out_h * SUPERSAMPLE
    canvas = image.crop((cx, cy, cx + cw, cy + ch)).resize((big_w, big_h), Image.LANCZOS)
    scale = big_w / cw

    def to_canvas(point):
        return ((point[0] - cx) * scale, (point[1] - cy) * scale)

    layer = Image.new("RGBA", canvas.size, (0, 0, 0, 0))
    draw = ImageDraw.Draw(layer)
    base = out_w * STROKE * SUPERSAMPLE
    for index, mark in enumerate(spec.get("marks", [])):
        shape = SHAPES.get(mark.get("type"))
        if shape is None:
            sys.exit(f"{spec_path.name}: unknown mark type {mark.get('type')!r}")
        unit = base * float(mark.get("weight", 1.0))
        rng = random.Random(mark.get("seed", index + 1))
        paths = shape(mark, to_canvas, unit, rng)
        # Every edge first, then every stroke, so a mark's own crossings stay clean.
        for path in paths:
            stroke(draw, path, unit * (1 + 2 * EDGE_RATIO), EDGE)
        for path in paths:
            stroke(draw, path, unit, ACCENT)

    canvas = Image.alpha_composite(canvas.convert("RGBA"), layer).convert("RGB")
    canvas = canvas.resize((out_w, out_h), Image.LANCZOS)
    output = ROOT / spec["output"]
    output.parent.mkdir(parents=True, exist_ok=True)
    # Encoded by ImageMagick with the settings AGENTS.md gives for every screenshot, sharp YUV
    # included, which Pillow's encoder does not offer.
    with tempfile.NamedTemporaryFile(suffix=".png") as temp:
        canvas.save(temp.name, "PNG")
        subprocess.run(
            ["magick", temp.name, "-define", "webp:method=6", "-define", "webp:sharp-yuv=true",
             "-quality", str(int(spec.get("quality", 88))), str(output)],
            check=True,
        )
    return output


def main():
    parser = argparse.ArgumentParser(description=__doc__.split("\n\n")[0])
    parser.add_argument("specs", nargs="*", type=Path, help="spec files")
    parser.add_argument("--all", action="store_true", help="every spec in scripts/annotations/")
    parser.add_argument("--raw", type=Path, default=RAW, help="where the raw captures are (default build/shots)")
    args = parser.parse_args()
    specs = sorted(SPECS.glob("*.json")) if args.all else args.specs
    if not specs:
        parser.error("name a spec, or pass --all")
    for spec in specs:
        out = render(spec, args.raw)
        with Image.open(out) as done:
            print(f"{spec.name} -> {out.relative_to(ROOT)} {done.width}x{done.height} {out.stat().st_size // 1024} KB")


if __name__ == "__main__":
    main()
