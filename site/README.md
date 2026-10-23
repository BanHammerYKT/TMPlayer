# tmplayer.org

The site is an [Astro](https://astro.build) project. Vercel builds it from `main` (`pnpm build`,
output `dist/`); everything else about serving it, headers, redirects and caching, is in
`vercel.json`.

```sh
pnpm -C site install
pnpm -C site dev        # http://localhost:4321/, every language at its own URL, hot reload
pnpm -C site build      # writes site/dist/
```

## Where things are

| Path | What it is |
|---|---|
| `src/pages/[...lang]/` | the localized pages; each renders in English at `/` and once per published language (`/es/`, `/zh-tw/`, ...) |
| `src/pages/<page>/`, `src/pages/404.astro` | the English-only pages (legal, privacy, security, signing, support, changelog, and the one-app comparisons in `alternatives/[slug].astro`) |
| `src/pages/sitemap.xml.ts`, `src/pages/changelog/feed.xml.ts` | the sitemap and the release feed |
| `integrations/markdown-twins.mjs` | after the build, the Markdown twin of every English page and `llms-full.txt` (below) |
| `src/layouts/Base.astro` | `<html>`, the whole `<head>` (SEO, Open Graph, hreflang), the notice bar and the scripts |
| `src/components/` | Header, Footer, the language picker, `<T>` and `<Icon>`, and the pieces pages share |
| `src/icons/*.svg` | every inline SVG, once, used as `<Icon name="...">` |
| `src/i18n/` | languages and pages (`langs.ts`), catalog lookups (`t.ts`), localized links, JSON-LD translation |
| `src/data/releases.json` | the releases, written by `scripts/release-data.py` (the release job runs it) |
| `src/data/lastmod.json` | each English page's sitemap entry: `lastmod`, `changefreq`, `priority`, images |
| `src/styles/styles.css`, `src/scripts/app.js` | the stylesheet and the script, served under `/_astro/` with a content hash |
| `i18n/<tag>.json` | the string catalogs; `en.json` is the source, the others are translations |
| `public/` | files served as they are: `latest.json` (the apps' update feed), `install.ps1`, screenshots, fonts, icons, `llms.txt`, `robots.txt` |

## Markdown for assistants

After each build, `integrations/markdown-twins.mjs` turns the `<main>` of every built English
page into Markdown next to it: `/download/` at `/download/index.md`, and a release page, whose
address has no slash, at `/changelog/1.26.0.md`. Navigation, the notice bar, scripts and controls
are left out; headings, text, lists, tables, links (made absolute) and image alt text stay. They
come from `dist/`, not from the sources, so they never say anything the page does not, and a new
English page gets one with no extra work. `dist/llms-full.txt` is all of them joined in sitemap
order. Translated pages have none.

Each English page links its twin with `<link rel="alternate" type="text/markdown">` (`Base.astro`),
and `vercel.json` redirects a page request that sends `Accept: text/markdown` to it. That is a
redirect, not a rewrite, because Vercel applies rewrites only after looking for a file, and every
page is a file. The rule takes a two letter first segment for a language, so an English page is
never named with two letters (`site-links.py` checks). The twins are served as
`text/markdown; charset=utf-8` with `Vary: Accept` and a `Link: rel="canonical"` header pointing
at the page.

## IndexNow

`.github/workflows/indexnow.yml` runs on every push to `main` that touches `site/`: it waits for
Vercel's commit status to say the push is deployed, then `scripts/indexnow-site.py` posts the
pages whose `lastmod` in `src/data/lastmod.json` changed (with their translations, from the live
sitemap) and every page of a language whose catalog changed. So a page reaches IndexNow when its
`lastmod` moves, which is one more reason to move it with the content. Releases ping from the
release job. `--dry-run` prints the URLs without sending them.

## Adding a string

Add the key and its English text to `i18n/en.json`, then use it: `{t('page.key')}` for plain text
and attributes, or `<T k="page.key">` when the sentence holds markup, with each piece as a named
slot matching a `{placeholder}` in the string:

```astro
<p><T k="home.quiet"><a slot="a1" href={l('/install/')}>{t('home.quiet__a1')}</a></T></p>
```

Translations go into the other catalogs under the same key, with the same placeholders. A missing
or broken translation falls back to English for that one string. A language is published only
once its catalog covers 95 per cent of the keys.

## Adding a page

A localized page is `src/pages/[...lang]/<path>/index.astro` with `export const getStaticPaths =
langPaths`, plus its id in `PAGES` in `src/i18n/langs.ts`. An English-only page is
`src/pages/<path>/index.astro`. The one-app comparisons under `/alternatives/` are English only
but still read their words from `en.json`, under `vs.*`: a new one is an entry in
`src/data/vs.json` plus its `vs.<id>.*` strings, and `vs.*` does not count towards a language's
share, so adding one never unpublishes a language. Either way: pass the head to `<Base>` (title, description, and the
page id or path), give it JSON-LD with at least a BreadcrumbList, add its sitemap entry to
`src/data/lastmod.json`, and mention it in `public/llms.txt`.

## Adding a language

Add it to `LANGS` in `src/i18n/langs.ts` and put its catalog at `i18n/<tag>.json`. It appears in
the picker, the hreflang alternates and the sitemap once the catalog is complete enough.

## Checks

```sh
python3 scripts/site-i18n-check.py   # catalogs against the pages and app.js (after a build)
python3 scripts/site-links.py        # every internal link in dist/ resolves, every English page has its twin
```

## Performance

Lighthouse runs locally against headless Chromium, on the home page, `/features/` and
`/download/`, mobile and desktop, one at a time (in parallel they slow each other down). Against
the live site, or a local build served by `pnpm -C site preview` (which sends none of
`vercel.json`'s headers, so the cache audit only means something live):

```sh
export CHROME_PATH=/usr/bin/chromium-browser
for page in "" features/ download/; do
  npx -y lighthouse@12.8.2 "https://tmplayer.org/$page" --quiet \
    --chrome-flags="--headless=new --no-sandbox --disable-gpu" \
    --form-factor=mobile --output=json --output-path="lh-${page%/}-mobile.json"
  # desktop: the same with --preset=desktop
done
```

Keep the reports out of the repository. Reading them:

- Each run spends GitHub API calls (60 an hour per address). Once they run out the download page
  falls back to `latest.json` and logs a 403, so check `curl -s https://api.github.com/rate_limit`
  first. Locally `/_vercel/insights/script.js` is a 404, which costs Best Practices a few points
  the live site does not lose.
- "Improve image delivery" ignores the screen's pixel ratio. A srcset is right when a copy just
  covers the drawn width times the ratio (Lighthouse's phone is 412 px at 1.75x). Widths are in
  `src/data/shot-widths.json`, `sizes` in `src/components/shot-sizes.ts`, new copies come from
  `scripts/site-shot-variants.py`.
- A layout shift on `/download/` usually means something above the platform list changed after
  the first paint: a font weight that is not preloaded, or a note app.js shows once GitHub answers.

The audit of 2026-10-18 brought every page to 100 for Performance, Accessibility and SEO on a
local build, mobile and desktop (the median of three interleaved runs).
