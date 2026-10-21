// A small static server for trying a built web app locally or on a phone on the same network.
//
//   node web/tools/serve.mjs <dir> [--port 8090] [--host 127.0.0.1] [--cert c.pem --key k.pem]
//
// Serves <dir> (web/build/dist/wasmJs/productionExecutable, or the development one) with the
// MIME types and headers web/vercel.json gives the real site: application/wasm, byte ranges, and
// no-cache for the page and the Service Worker. With --cert and --key it serves HTTPS, which a
// phone needs for a Service Worker, OPFS and installing the app (mkcert makes a local pair).
import fs from 'node:fs';
import http from 'node:http';
import https from 'node:https';
import path from 'node:path';

const args = process.argv.slice(2);
const opt = (name, fallback) => {
  const i = args.indexOf('--' + name);
  return i >= 0 ? args[i + 1] : fallback;
};
const root = path.resolve(args.find((a, i) => !a.startsWith('--') && !(i > 0 && args[i - 1].startsWith('--'))) || '.');
const port = Number(opt('port', 8090));
const host = opt('host', '127.0.0.1');
const cert = opt('cert');
const key = opt('key');

const TYPES = {
  '.html': 'text/html; charset=utf-8',
  '.js': 'text/javascript; charset=utf-8',
  '.mjs': 'text/javascript; charset=utf-8',
  '.css': 'text/css; charset=utf-8',
  '.json': 'application/json; charset=utf-8',
  '.webmanifest': 'application/manifest+json',
  '.wasm': 'application/wasm',
  '.svg': 'image/svg+xml',
  '.png': 'image/png',
  '.ico': 'image/x-icon',
  '.ttf': 'font/ttf',
  '.woff2': 'font/woff2',
  '.txt': 'text/plain; charset=utf-8',
  '.mp4': 'video/mp4',
  '.webm': 'video/webm',
  '.mkv': 'video/x-matroska',
};

function handler(req, res) {
  const url = new URL(req.url, 'http://x');
  let rel = decodeURIComponent(url.pathname);
  if (rel.endsWith('/')) rel += 'index.html';
  const file = path.join(root, path.normalize(rel).replace(/^(\.\.[/\\])+/, ''));
  if (!file.startsWith(root) || !fs.existsSync(file) || !fs.statSync(file).isFile()) {
    // A route inside the app falls back to the page itself.
    if (!path.extname(rel)) return send(path.join(root, 'index.html'), req, res);
    res.writeHead(404, { 'content-type': 'text/plain' });
    return res.end('not found');
  }
  send(file, req, res);
}

function send(file, req, res) {
  const size = fs.statSync(file).size;
  const ext = path.extname(file);
  const headers = {
    'content-type': TYPES[ext] || 'application/octet-stream',
    'accept-ranges': 'bytes',
    'cache-control': ext === '.html' || path.basename(file) === 'sw.js' ? 'no-cache' : 'max-age=60',
    'service-worker-allowed': '/',
    'x-content-type-options': 'nosniff',
  };
  const m = /^bytes=(\d*)-(\d*)$/.exec(req.headers.range || '');
  if (m) {
    const start = m[1] === '' ? Math.max(0, size - Number(m[2])) : Number(m[1]);
    const end = m[1] !== '' && m[2] !== '' ? Math.min(Number(m[2]), size - 1) : size - 1;
    if (start >= size || start > end) {
      res.writeHead(416, { 'content-range': `bytes */${size}` });
      return res.end();
    }
    res.writeHead(206, { ...headers, 'content-range': `bytes ${start}-${end}/${size}`, 'content-length': end - start + 1 });
    return fs.createReadStream(file, { start, end }).pipe(res);
  }
  res.writeHead(200, { ...headers, 'content-length': size });
  if (req.method === 'HEAD') return res.end();
  fs.createReadStream(file).pipe(res);
}

const server = cert && key
  ? https.createServer({ cert: fs.readFileSync(cert), key: fs.readFileSync(key) }, handler)
  : http.createServer(handler);
server.listen(port, host, () => {
  console.log(`serving ${root} on ${cert ? 'https' : 'http'}://${host}:${port}/`);
});
