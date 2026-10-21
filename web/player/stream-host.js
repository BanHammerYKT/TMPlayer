// The page's half of the Service Worker's streaming (sw.js): each read the worker asks for is
// answered from TDLib through TmTd.readRange, the bytes transferred, not copied.
//
// A source is one file or a video cut into pieces (`Name.mkv.001` and on): `{ids, sizes}`, the
// pieces in order. An offset into the joined video maps onto one piece; a read never crosses into
// the next piece, it is answered short and the element asks again from there.
(function () {
  'use strict';

  /** [piece index, offset in it] for an offset into the joined bytes. */
  function locate(sizes, offset) {
    let base = 0;
    for (let i = 0; i < sizes.length; i++) {
      if (offset < base + sizes[i]) return [i, offset - base];
      base += sizes[i];
    }
    return [-1, 0];
  }

  /** A background read (read-ahead) takes only bytes TDLib already has, or none. */
  async function read(source, offset, length, background) {
    const [i, at] = locate(source.sizes, offset);
    if (i < 0) return new ArrayBuffer(0);
    const n = Math.min(length, source.sizes[i] - at);
    return window.TmTd.readRange(source.ids[i], at, n, !!background);
  }

  if ('serviceWorker' in navigator) {
    navigator.serviceWorker.addEventListener('message', async (event) => {
      const data = event.data;
      if (!data || data.type !== 'tm-read' || !event.ports[0]) return;
      const port = event.ports[0];
      try {
        const buffer = await read(data.source, data.offset, data.length);
        port.postMessage({ buffer: buffer }, [buffer]);
      } catch (e) {
        port.postMessage({ error: String((e && e.message) || e) });
      }
    });
  }

  window.TmStream = { read: read, locate: locate };
})();
