// The page's one door to TDLib (tdweb), for the Kotlin/Wasm app and for the player layer.
//
// Everything crosses into Wasm as TDLib JSON text, so Kotlin parses it with
// kotlinx-serialization and no generated classes are needed. Video bytes never enter Wasm: the
// player and the Service Worker read them here, in JavaScript.
//
//   TmTd.start(optionsJson, onUpdate)     one client per page; onUpdate gets each update as text
//   TmTd.send(queryJson)                  Promise<string>: the result, or an "error" object as text
//   TmTd.readBase64(fileId, priority)     Promise<string>: a small file downloaded whole, base64
//   TmTd.readRange(fileId, offset, count) Promise<ArrayBuffer>: up to count bytes at offset
//   TmTd.fileInfo(fileId)                 Promise<object>: TDLib's file object
//   TmTd.warm(fileId, offset, count)      asks TDLib for a stretch ahead of time (card hover)
//   TmTd.forget(fileId)                   drops what TDLib holds of a file (cache trimming)
//   TmTd.close()
//
// Streaming follows the apps' TdByteWindow: TDLib is pointed at the byte the player wants with a
// bounded window ahead of it (downloadFile with offset and limit), reads wait for TDLib's file
// updates until the bytes are there, and the window moves on as the reads do. Reads of one file
// are serialized: TDLib ends a waiting download when another with different bounds arrives.
(function () {
  'use strict';

  const ios = /iPhone|iPad|iPod/.test(navigator.userAgent) ||
    (navigator.platform === 'MacIntel' && navigator.maxTouchPoints > 1);
  const mobile = ios || /Android/.test(navigator.userAgent);
  // How far ahead of the read TDLib downloads. Smaller on phones, whose storage and memory are
  // tighter; the window moves on as playback does, so this is not a ceiling on what plays.
  const WINDOW = (mobile ? 24 : 64) * 1024 * 1024;
  const MIN_READ = 256 * 1024;
  const WAIT_MS = 30000;

  let client = null;
  const queues = new Map();
  const windows = new Map();
  const waiters = new Map();
  const listeners = new Set();

  function serial(fileId, job) {
    const before = queues.get(fileId) || Promise.resolve();
    const next = before.catch(() => {}).then(job);
    queues.set(fileId, next);
    next.finally(() => {
      if (queues.get(fileId) === next) queues.delete(fileId);
    }).catch(() => {});
    return next;
  }

  function errorText(e) {
    if (e && e['@type'] === 'error') {
      return JSON.stringify({ '@type': 'error', code: e.code || 0, message: String(e.message || '') });
    }
    return JSON.stringify({ '@type': 'error', code: 0, message: String((e && e.message) || e) });
  }

  // tdweb hands Blobs back inside some objects (filePart); they are never sent as text.
  function replacer(key, value) {
    if (typeof Blob !== 'undefined' && value instanceof Blob) return undefined;
    if (value instanceof ArrayBuffer || ArrayBuffer.isView(value)) return undefined;
    return value;
  }

  function call(query) {
    if (!client) return Promise.reject({ '@type': 'error', code: 0, message: 'TDLib is not started' });
    return client.send(query);
  }

  function onFile(file) {
    const set = waiters.get(file.id);
    if (set) {
      waiters.delete(file.id);
      for (const resolve of set) resolve(file);
    }
    for (const l of listeners) {
      try {
        l(file);
      } catch (e) {}
    }
  }

  function waitFile(fileId, ms) {
    return new Promise((resolve) => {
      let set = waiters.get(fileId);
      if (!set) waiters.set(fileId, (set = new Set()));
      const done = (f) => {
        clearTimeout(timer);
        resolve(f);
      };
      const timer = setTimeout(() => {
        set.delete(done);
        resolve(null);
      }, ms);
      set.add(done);
    });
  }

  async function file(fileId) {
    return call({ '@type': 'getFile', file_id: fileId });
  }

  async function prefix(fileId, offset) {
    try {
      const r = await call({ '@type': 'getFileDownloadedPrefixSize', file_id: fileId, offset: offset });
      // TDLib 1.8.40 and on answer fileDownloadedPrefixSize { size }; older ones { count }.
      return (r.size != null ? r.size : r.count) || 0;
    } catch (e) {
      return 0;
    }
  }

  async function pointAt(fileId, offset, size, priority) {
    const limit = size > 0 ? Math.min(WINDOW, size - offset) : WINDOW;
    windows.set(fileId, { start: offset, end: offset + limit });
    await call({
      '@type': 'downloadFile', file_id: fileId, priority: priority || 32,
      offset: offset, limit: limit, synchronous: false,
    });
  }

  function covered(fileId, offset, need) {
    const w = windows.get(fileId);
    // Move the window on once the reads are past its middle, so TDLib keeps ahead of them.
    return w && offset >= w.start && offset + need <= w.end && offset - w.start < (w.end - w.start) / 2;
  }

  async function readPart(fileId, offset, n) {
    const part = await call({ '@type': 'readFilePart', file_id: fileId, offset: offset, count: n });
    return part.data.arrayBuffer();
  }

  async function readRangeNow(fileId, offset, count, retried) {
    let f = await file(fileId);
    const size = f.size || f.expected_size || 0;
    if (size > 0 && offset >= size) return new ArrayBuffer(0);
    if (size > 0) count = Math.min(count, size - offset);
    const want = Math.min(count, MIN_READ);
    let have = 0;
    if (f.local && f.local.is_downloading_completed) {
      have = count;
    } else {
      const started = Date.now();
      while (true) {
        have = await prefix(fileId, offset);
        if (have >= want) break;
        if (!covered(fileId, offset, want)) await pointAt(fileId, offset, size, 32);
        const updated = await waitFile(fileId, 2000);
        if (updated && updated.local && updated.local.is_downloading_completed) {
          have = count;
          break;
        }
        if (Date.now() - started > WAIT_MS) {
          if (have > 0) break;
          throw new Error('stopped loading');
        }
      }
      // Keep TDLib ahead of the reader even when the bytes were already there.
      if (!covered(fileId, offset + count, MIN_READ) && size > 0 && offset + count < size) {
        pointAt(fileId, offset, size, 32).catch(() => {});
      }
    }
    const n = Math.min(have, count);
    if (n <= 0) return new ArrayBuffer(0);
    try {
      return await readPart(fileId, offset, n);
    } catch (e) {
      // The sparse store had to drop this stretch (memory mode): TDLib is told to forget the file
      // and fetch it afresh.
      if (retried) throw e;
      windows.delete(fileId);
      await call({ '@type': 'deleteFile', file_id: fileId }).catch(() => {});
      return readRangeNow(fileId, offset, count, true);
    }
  }

  const TmTd = {
    start(optionsJson, onUpdate) {
      if (client) return;
      const options = JSON.parse(optionsJson);
      const TdClient = window.tdweb && window.tdweb.default;
      if (!TdClient) throw new Error('tdweb did not load');
      client = new TdClient({
        instanceName: options.instanceName || 'tmplayer',
        isBackground: false,
        jsLogVerbosityLevel: options.jsLogVerbosityLevel || 'error',
        logVerbosityLevel: options.logVerbosityLevel == null ? 1 : options.logVerbosityLevel,
        useDatabase: true,
        mode: 'wasm',
        onUpdate: (update) => {
          const type = update['@type'];
          // A closed client is finished (a sign out, or another tab took over): the next start
          // makes a new one.
          if (type === 'updateAuthorizationState' &&
              update.authorization_state['@type'] === 'authorizationStateClosed') {
            client = null;
            windows.clear();
          }
          if (type === 'updateFile') onFile(update.file);
          let text;
          try {
            text = JSON.stringify(update, replacer);
          } catch (e) {
            return;
          }
          try {
            onUpdate(text);
          } catch (e) {
            console.error('TmTd update handler', e);
          }
        },
      });
    },

    send(queryJson) {
      let query;
      try {
        query = JSON.parse(queryJson);
      } catch (e) {
        return Promise.resolve(errorText(e));
      }
      return call(query).then((r) => JSON.stringify(r, replacer), (e) => errorText(e));
    },

    readBase64(fileId, priority) {
      return serial(fileId, async () => {
        try {
          let f = await file(fileId);
          if (!f.local || !f.local.is_downloading_completed) {
            f = await call({
              '@type': 'downloadFile', file_id: fileId, priority: priority || 8,
              offset: 0, limit: 0, synchronous: true,
            });
          }
          const part = await call({ '@type': 'readFilePart', file_id: fileId, offset: 0, count: 0 });
          const bytes = new Uint8Array(await part.data.arrayBuffer());
          let s = '';
          for (let i = 0; i < bytes.length; i += 0x8000) {
            s += String.fromCharCode.apply(null, bytes.subarray(i, i + 0x8000));
          }
          return btoa(s);
        } catch (e) {
          return '';
        }
      });
    },

    prefix(fileId, offset) {
      return prefix(fileId, offset);
    },

    fileInfo(fileId) {
      return file(fileId);
    },

    readRange(fileId, offset, count) {
      return serial(fileId, () => readRangeNow(fileId, offset, count, false));
    },

    // A stretch fetched ahead of a play (a card hovered or focused for a moment), so the first
    // frame is already there: the head, and the tail where MP4 and MKV keep their index.
    warm(fileId, offset, count) {
      return serial(fileId, async () => {
        try {
          const have = await prefix(fileId, offset);
          if (have >= count) return;
          await call({
            '@type': 'downloadFile', file_id: fileId, priority: 8,
            offset: offset, limit: count, synchronous: false,
          });
        } catch (e) {}
      });
    },

    forget(fileId) {
      windows.delete(fileId);
      return call({ '@type': 'deleteFile', file_id: fileId }).catch(() => {});
    },

    onFile(listener) {
      listeners.add(listener);
      return () => listeners.delete(listener);
    },

    close() {
      if (!client) return Promise.resolve();
      return call({ '@type': 'close' }).catch(() => {});
    },

    get started() {
      return client != null;
    },
  };

  window.TmTd = TmTd;
})();
