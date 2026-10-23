#!/usr/bin/env python3
"""Tells IndexNow which tmplayer.org pages a push to main changed.

The IndexNow workflow runs this once Vercel has deployed the push, so Bing, Yandex, Seznam and
Naver (and through Bing, ChatGPT search and Copilot) hear about a changed page within hours
instead of on their next crawl. Google does not take part and reads the sitemap.

A page counts as changed when its entry in site/src/data/lastmod.json is new or has a new date
between the two commits: site/AGENTS.md asks for that date to move whenever the page's content does,
so it is the one signal that already means "this page changed" and nothing else. A translation
catalog that changed counts every page in that language. Each English page brings its
translations along, read from the live sitemap's hreflang alternates, so the language list here
is never a second copy of the site's. Release pages are left to the release job, which pings them
itself. When nothing indexable changed, it says so and sends nothing.

    scripts/indexnow-site.py --before <sha> --after <sha> [--dry-run]
"""
import argparse
import json
import pathlib
import re
import subprocess
import sys
import urllib.request
import xml.etree.ElementTree as ET

ROOT = pathlib.Path(__file__).resolve().parent.parent
ORIGIN = "https://tmplayer.org"
HOST = "tmplayer.org"
ENDPOINT = "https://api.indexnow.org/indexnow"
LASTMOD = "site/src/data/lastmod.json"
NS = {"s": "http://www.sitemaps.org/schemas/sitemap/0.9", "x": "http://www.w3.org/1999/xhtml"}


def git(*args, check=True):
    r = subprocess.run(["git", *args], cwd=ROOT, capture_output=True, text=True)
    if check and r.returncode:
        sys.exit(f"git {' '.join(args)}: {r.stderr.strip()}")
    return r


def have(rev):
    return git("cat-file", "-e", f"{rev}^{{commit}}", check=False).returncode == 0


def resolve_before(before, after):
    """The commit to compare with. A shallow checkout fetches it; a new branch or a force push
    whose old head is gone falls back to the parent of the pushed commit."""
    if before and not re.fullmatch(r"0+", before):
        if have(before) or git("fetch", "--quiet", "--depth=1", "origin", before, check=False).returncode == 0:
            return before
    if not have(f"{after}^"):
        git("fetch", "--quiet", "--deepen=1", "origin", check=False)
    return f"{after}^" if have(f"{after}^") else None


def lastmod(rev):
    r = git("show", f"{rev}:{LASTMOD}", check=False)
    return {e["path"]: e.get("lastmod") for e in json.loads(r.stdout)} if r.returncode == 0 else {}


def catalogs(rev):
    r = git("ls-tree", rev, "site/i18n/", check=False)
    return {line.split("\t")[1]: line.split()[2] for line in r.stdout.splitlines()} if r.returncode == 0 else {}


def lang_paths():
    """Catalog tag to URL prefix, from LANGS in langs.ts (es-419 is /es/, zh-TW is /zh-tw/)."""
    src = (ROOT / "site/src/i18n/langs.ts").read_text()
    return dict(re.findall(r"tag: '([^']+)', path: '([^']+)'", src))


def sitemap(url):
    with urllib.request.urlopen(urllib.request.Request(url, headers={"User-Agent": "tmplayer-indexnow"}), timeout=30) as r:
        root = ET.fromstring(r.read())
    for u in root.findall("s:url", NS):
        loc = u.findtext("s:loc", namespaces=NS).strip()
        alts = {a.get("href") for a in u.findall("x:link", NS)}
        yield loc, alts


def key():
    keys = [p.stem for p in (ROOT / "site/public").glob("*.txt") if re.fullmatch(r"[0-9a-f]{32}", p.stem)]
    if len(keys) != 1:
        sys.exit(f"expected one IndexNow key file in site/public/, found {len(keys)}")
    return keys[0]


def main():
    ap = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    ap.add_argument("--before", default="")
    ap.add_argument("--after", default="HEAD")
    ap.add_argument("--sitemap", default=f"{ORIGIN}/sitemap.xml")
    ap.add_argument("--dry-run", action="store_true", help="print the URLs and send nothing")
    args = ap.parse_args()

    before = resolve_before(args.before, args.after)
    old, new = (lastmod(before) if before else {}), lastmod(args.after)
    pages = {ORIGIN + p for p, day in new.items() if old.get(p) != day}
    old_cat, new_cat = (catalogs(before) if before else {}), catalogs(args.after)
    paths = lang_paths()
    langs = {paths[f.rsplit("/", 1)[-1][:-5]] for f, blob in new_cat.items()
             if old_cat.get(f) != blob and f.rsplit("/", 1)[-1][:-5] in paths}
    if not pages and not langs:
        print("No page's lastmod and no translation changed; nothing to tell IndexNow.")
        return 0

    urls = []
    for loc, alts in sitemap(args.sitemap):
        if loc in pages or alts & pages or any(loc.startswith(f"{ORIGIN}/{p}/") for p in langs):
            urls.append(loc)
    if not urls:
        print("The changed pages are not in the sitemap; nothing to tell IndexNow.")
        return 0
    print(f"{len(urls)} changed URLs:", *urls, sep="\n  ")
    if args.dry_run:
        return 0

    k = key()
    body = json.dumps({"host": HOST, "key": k, "keyLocation": f"{ORIGIN}/{k}.txt", "urlList": urls}).encode()
    req = urllib.request.Request(ENDPOINT, data=body, method="POST",
                                 headers={"Content-Type": "application/json; charset=utf-8"})
    try:
        with urllib.request.urlopen(req, timeout=30) as r:
            print(f"IndexNow answered {r.status}")
    except Exception as e:  # noqa: BLE001
        # The ping is a courtesy: the sitemap still tells every engine on its next crawl, so a
        # refusal or an outage is reported without failing the push's checks.
        print(f"::warning::IndexNow did not take the URLs: {e}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
