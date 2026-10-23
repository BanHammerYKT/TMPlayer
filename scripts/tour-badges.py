#!/usr/bin/env python3
"""Writes where the quick tour's numbered badges sit on each tour picture.

Every tour spec in scripts/annotations/ (tour-android-<page>.json for the phone, tour-android-<page>-tv.json
for the television, tour-desktop-<page>-light.json for the desktop) marks the things its page talks about with
one "circle" each, in the order of the page's numbered lines. The app draws the numbers itself, over the
picture, so no number or word is ever drawn into an image (AGENTS.md). This script turns each circle's box
into the badge's place, as fractions of the cropped picture, and writes them into TourBadges.kt, so a
retaken shot moves its numbers with:

  scripts/tour-badges.py          rewrite ui/.../onboarding/TourBadges.kt
  scripts/tour-badges.py --check  exit 1 if TourBadges.kt is out of date

The dark specs are not read: a light and dark pair share their crop and marks.
"""

import argparse
import json
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
SPECS = ROOT / "scripts" / "annotations"
OUT = ROOT / "ui" / "src" / "shared" / "kotlin" / "com" / "tmplayer" / "ui" / "onboarding" / "TourBadges.kt"

PAGES = ["videos", "home", "player", "controls", "downloads", "tvhome", "settings"]
FORMS = {"Phone": "tour-android-{}.json", "Tv": "tour-android-{}-tv.json", "Desktop": "tour-desktop-{}-light.json"}

# The badge sits just outside the circle's top left corner; the app keeps it inside the picture.
EDGE = 0.0


def badges(spec_path: Path):
    spec = json.loads(spec_path.read_text())
    crop = spec.get("crop")
    if crop is None:
        raise SystemExit(f"{spec_path.name}: a tour spec needs a crop, to place its badges")
    cx, cy, cw, ch = crop
    out = []
    for mark in spec.get("marks", []):
        if mark.get("type") != "circle":
            continue
        x, y, _, _ = mark["box"]
        fx = min(max((x - cx) / cw, EDGE), 1 - EDGE)
        fy = min(max((y - cy) / ch, EDGE), 1 - EDGE)
        out.append((round(fx, 3), round(fy, 3)))
    return out


def render() -> str:
    rows = []
    for page in PAGES:
        for form, pattern in FORMS.items():
            path = SPECS / pattern.format(page)
            if not path.exists():
                continue
            points = ", ".join(f"Offset({x}f, {y}f)" for x, y in badges(path))
            enum_page = "TvHome" if page == "tvhome" else page.capitalize()
            rows.append(f"        (TourPage.{enum_page} to DeviceForm.{form}) to listOf({points}),")
    body = "\n".join(rows)
    return f'''package com.tmplayer.ui.onboarding

import androidx.compose.ui.geometry.Offset
import com.tmplayer.data.DeviceForm

// Written by scripts/tour-badges.py from the tour specs in scripts/annotations/. Do not edit by hand.

/**
 * Where each numbered badge sits on a tour page's picture, as fractions of its width and height, in
 * the order of the page's lines. One list per page and form; a light and a dark picture share it.
 */
internal object TourBadges {{
    fun of(page: TourPage, form: DeviceForm): List<Offset> = all[page to form].orEmpty()

    private val all: Map<Pair<TourPage, DeviceForm>, List<Offset>> = mapOf(
{body}
    )
}}
'''


def main():
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--check", action="store_true")
    args = parser.parse_args()
    text = render()
    if args.check:
        if not OUT.exists() or OUT.read_text() != text:
            print(f"{OUT.relative_to(ROOT)} is out of date: run scripts/tour-badges.py", file=sys.stderr)
            sys.exit(1)
        return
    OUT.write_text(text)
    print(f"wrote {OUT.relative_to(ROOT)}")


if __name__ == "__main__":
    main()
