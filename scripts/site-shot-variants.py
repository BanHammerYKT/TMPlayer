#!/usr/bin/env python3
"""Make the smaller copies of the site's screenshots that phones are sent instead of the originals.

Every WebP in site/public/screenshots/ gets a copy at each width that site/src/data/shot-widths.json
lists for its own width, named <name>-<width>w.webp beside it. Shot.astro reads the same table to
write the srcset, so the two cannot disagree about which files exist. The originals are never
touched: each copy is made from the original with the house encoding (AGENTS.md).

Only missing copies are made by default. File times cannot say which copies are stale, because a
checkout gives every file the time of the checkout, so a retaken screenshot is named on the command
line and its copies are made again.

  python3 scripts/site-shot-variants.py                     make the missing copies
  python3 scripts/site-shot-variants.py tv-home phone-show  remake the copies of these originals
  python3 scripts/site-shot-variants.py --check             list the missing ones, exit 1 if any
"""
import json
import re
import subprocess
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
SHOTS = ROOT / "site" / "public" / "screenshots"
WIDTHS = json.loads((ROOT / "site" / "src" / "data" / "shot-widths.json").read_text())
VARIANT = re.compile(r"-\d+w\.webp$")


def width_of(path: Path) -> int:
    out = subprocess.run(["magick", "identify", "-format", "%w", str(path)], check=True, capture_output=True, text=True)
    return int(out.stdout.strip())


def due(remake: set[str]) -> list[tuple[Path, Path, int]]:
    todo = []
    for original in sorted(SHOTS.glob("*.webp")):
        if VARIANT.search(original.name):
            continue
        for w in WIDTHS.get(str(width_of(original)), []):
            copy = original.with_name(f"{original.stem}-{w}w.webp")
            if not copy.exists() or original.stem in remake:
                todo.append((original, copy, w))
    return todo


def main() -> int:
    names = {a.removesuffix(".webp") for a in sys.argv[1:] if not a.startswith("-")}
    todo = due(names)
    if "--check" in sys.argv:
        for _, copy, _ in todo:
            print(f"missing: {copy.relative_to(ROOT)}")
        return 1 if todo else 0
    for original, copy, w in todo:
        subprocess.run([
            "magick", str(original), "-resize", f"{w}x",
            "-define", "webp:method=6", "-define", "webp:sharp-yuv=true", "-quality", "88", str(copy),
        ], check=True)
        print(f"made {copy.relative_to(ROOT)} ({copy.stat().st_size} bytes)")
    return 0


if __name__ == "__main__":
    sys.exit(main())
