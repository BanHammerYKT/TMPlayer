// The site keeps every URL, header and byte it had before Astro (site/README.md):
// directories with a trailing slash, HTML left as written, assets hashed under /_astro/.
import { defineConfig } from 'astro/config';
import { mkdirSync, writeFileSync } from 'node:fs';
import { createRequire } from 'node:module';
import { pathToFileURL } from 'node:url';
import markdownTwins from './integrations/markdown-twins.mjs';

// src/scripts/app.js reaches the page through a ?url import, which Vite copies as a file rather
// than bundling, so it would ship with every comment in it. This minifies that copy with the
// minifier Vite itself bundles with (rolldown's oxc), reached through astro's own dependency so
// no extra package is installed. It stays a classic script: top-level names are left alone, and
// the output keeps to ES2015 syntax (no optional catch bindings and the like).
// The file name keeps the hash of the source, which still changes exactly when the source does.
const fromVite = createRequire(createRequire(import.meta.url).resolve('astro/package.json'));
const rolldownUtils = createRequire(fromVite.resolve('vite/package.json')).resolve('rolldown/utils');
const minifyAppJs = {
  name: 'tm-minify-app-js',
  apply: 'build',
  async generateBundle(_, bundle) {
    const { minifySync } = await import(pathToFileURL(rolldownUtils).href);
    for (const file of Object.values(bundle)) {
      if (file.type !== 'asset' || !/^_astro\/app\.[\w-]+\.js$/.test(file.fileName)) continue;
      const out = minifySync('app.js', String(file.source), { module: false, compress: { target: 'es2015' }, mangle: { toplevel: false } });
      if (out.errors.length) throw new Error(`app.js did not minify: ${out.errors.map((e) => e.message).join('; ')}`);
      file.source = out.code;
    }
  },
};

// Writes the catalog keys the pages looked up, so scripts/site-i18n-check.py can tell which
// en.json strings nothing uses any more.
const usedKeys = {
  name: 'tm-used-keys',
  hooks: {
    'astro:build:done': () => {
      const keys = [...(globalThis.__tmUsedKeys ?? [])].sort();
      mkdirSync(new URL('./.astro/', import.meta.url), { recursive: true });
      writeFileSync(new URL('./.astro/used-keys.json', import.meta.url), JSON.stringify(keys, null, 1) + '\n');
    },
  },
};

export default defineConfig({
  site: 'https://tmplayer.org',
  // The dev server takes either form. Vercel serves a release page without the slash
  // (/changelog/1.27.1, see releaseUrl()), and a strict dev server answered that link with a 404.
  trailingSlash: process.argv.includes('dev') ? 'ignore' : 'always',
  build: { format: 'directory', inlineStylesheets: 'never' },
  compressHTML: false,
  outDir: 'dist',
  integrations: [usedKeys, markdownTwins()],
  devToolbar: { enabled: false },
  // The stylesheet blocks the first paint, so it ships minified; the comments stay in
  // src/styles/styles.css, where they are read. app.js is minified the same way (minifyAppJs).
  vite: { build: { cssMinify: true }, plugins: [minifyAppJs] },
});
