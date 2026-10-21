// The page's one door to TDLib (tdweb), for the Kotlin/Wasm app and for the player layer.
//
// Everything crosses into Wasm as TDLib JSON text, so Kotlin parses it with
// kotlinx-serialization and no generated classes are needed. Video bytes never enter Wasm:
// readRange answers the Service Worker in JavaScript, with the ArrayBuffer transferred.
//
//   TmTd.start(optionsJson, onUpdate)     one client per page; onUpdate gets each update as text
//   TmTd.send(queryJson)                  Promise<string>: the result, or an "error" object as text
//   TmTd.readBase64(fileId, priority)      Promise<string>: a small file downloaded whole, base64
//   TmTd.readRange(fileId, offset, count) Promise<ArrayBuffer>: up to count bytes at offset
//   TmTd.prefix(fileId, offset)           Promise<number>: bytes on hand from offset
//   TmTd.close()
//
// Reads of one file are serialized: TDLib ends a waiting synchronous downloadFile when another
// with different bounds arrives for the same file (docs/WEB.md, "The bridge").
(function () {
  'use strict';

  let client = null;
  const queues = new Map();

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

  async function file(fileId) {
    return call({ '@type': 'getFile', file_id: fileId });
  }

  async function blobOf(fileId, offset, count) {
    const part = await call({ '@type': 'readFilePart', file_id: fileId, offset: offset, count: count });
    return part.data;
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
          // A closed client is finished (a sign out, or another tab took over): the next start
          // makes a new one.
          if (update['@type'] === 'updateAuthorizationState' &&
              update.authorization_state['@type'] === 'authorizationStateClosed') {
            client = null;
          }
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
          const blob = await blobOf(fileId, 0, 0);
          const bytes = new Uint8Array(await blob.arrayBuffer());
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

    async prefix(fileId, offset) {
      try {
        const r = await call({ '@type': 'getFileDownloadedPrefixSize', file_id: fileId, offset: offset });
        return r.count || 0;
      } catch (e) {
        return 0;
      }
    },

    // Up to count bytes at offset: what is already downloaded there, else a synchronous download
    // of that window first. A short answer is fine; the caller asks again for the rest.
    readRange(fileId, offset, count) {
      return serial(fileId, async () => {
        let f = await file(fileId);
        const size = f.size || f.expected_size || 0;
        if (size > 0 && offset >= size) return new ArrayBuffer(0);
        if (size > 0) count = Math.min(count, size - offset);
        let have = 0;
        if (f.local && f.local.is_downloading_completed) {
          have = count;
        } else {
          have = await TmTd.prefix(fileId, offset);
          if (have <= 0) {
            await call({
              '@type': 'downloadFile', file_id: fileId, priority: 32,
              offset: offset, limit: count, synchronous: true,
            });
            have = await TmTd.prefix(fileId, offset);
            f = await file(fileId);
            if (f.local && f.local.is_downloading_completed) have = count;
          }
        }
        const n = Math.min(have, count);
        if (n <= 0) return new ArrayBuffer(0);
        const blob = await blobOf(fileId, offset, n);
        return blob.arrayBuffer();
      });
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
