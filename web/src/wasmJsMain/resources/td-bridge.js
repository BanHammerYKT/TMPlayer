// The page's one door to TDLib (tdweb), for the Kotlin/Wasm app and for the player layer.
//
// Everything crosses into Wasm as TDLib JSON text, so Kotlin parses it with
// kotlinx-serialization and no generated classes are needed. Video bytes never enter Wasm: the
// player and the Service Worker read them here, in JavaScript.
//
//   TmTd.start(optionsJson, onUpdate)     one client per page; onUpdate gets each update as text
//   TmTd.send(queryJson)                  Promise<string>: the result, or an "error" object as text
//   TmTd.readBase64(fileId, priority)     Promise<string>: a small file downloaded whole, base64
//   TmTd.readRange(fileId, offset, count, background)
//                                         Promise<ArrayBuffer>: up to count bytes at offset; a
//                                         background read takes only what TDLib already has
//   TmTd.fileInfo(fileId)                 Promise<object>: TDLib's file object
//   TmTd.warm(fileId, size, head, tail)   fetches a file's head and tail ahead of a play
//   TmTd.stop(fileId)                     stops fetching ahead once a video closes
//   TmTd.forget(fileId)                   drops what TDLib holds of a file (cache trimming)
//   TmTd.close()
//
// Streaming follows the apps' TdByteWindow: TDLib is pointed at the byte the player wants with a
// bounded window ahead of it (downloadFile with offset and limit), reads wait for TDLib's file
// updates until the bytes are there, and the window moves on as the reads do. Reads of one file
// are serialized: TDLib ends a waiting download when another with different bounds arrives.
// Only the reads the player waits on take that queue; read-ahead never waits and never points
// TDLib anywhere, so it can neither hold a waiting read up nor drag the window back.
//
// The playhead's download is the one only real seeks move, as in the apps (TdByteWindow). A read
// far from where playback reads (an index, MKV Cues, a step of a seek's search through the file)
// is a probe: TDLib is pointed at just the 2 MiB around it, so it fetches only what the probe
// needs, and goes back to the playhead's window once the probes stop. Reads that carry on past a
// probe's end are a real seek, and the playhead's window follows them.
(function () {
  'use strict';

  const ios = /iPhone|iPad|iPod/.test(navigator.userAgent) ||
    (navigator.platform === 'MacIntel' && navigator.maxTouchPoints > 1);
  const mobile = ios || /Android/.test(navigator.userAgent);
  // How far ahead of the read TDLib downloads. Smaller on phones, whose storage and memory are
  // tighter; the window moves on as playback does, so this is not a ceiling on what plays.
  const WINDOW = (mobile ? 24 : 64) * 1024 * 1024;
  const PROBE = 2 * 1024 * 1024;
  const FAR = 4 * 1024 * 1024;
  // How long after the last probe the download goes back to the playhead.
  const BACK_MS = 400;
  const MIN_READ = 256 * 1024;
  const WAIT_MS = 30000;
  // How long a read waits for TDLib's next file update before it asks again.
  const POLL_MS = 1000;

  let client = null;
  const queues = new Map();
  const windows = new Map();
  const sizes = new Map();
  // Where playback reads, per file, and the timer that points TDLib back there after probes.
  const heads = new Map();
  const backTimers = new Map();
  const waiters = new Map();
  const listeners = new Set();
  // The last few hundred streaming decisions, for the playback details panel and the tests.
  const log = [];
  function trace(what, fileId, a, b) {
    if (log.length > 400) log.splice(0, 100);
    log.push([Math.round(performance.now()), what, fileId, a, b]);
  }

  // Which scripts Telegram's text has used so far, for the page's fonts (WebFonts.due): the
  // emoji and East Asian fonts are megabytes each and are fetched only once one is needed.
  const scripts = (window.TmScripts = window.TmScripts || {});
  const SCRIPT_TESTS = [
    ['emoji', /\p{Extended_Pictographic}/u],
    ['kana', /[\u3040-\u30ff]/],
    ['hangul', /[\uac00-\ud7af\u1100-\u11ff]/],
    ['han', /[\u3400-\u9fff]/],
  ];
  function scan(text) {
    for (const [name, re] of SCRIPT_TESTS) if (!scripts[name] && re.test(text)) scripts[name] = true;
  }

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

  async function pointAt(fileId, offset, size, priority, probe) {
    const span = probe ? PROBE : WINDOW;
    const limit = size > 0 ? Math.min(span, size - offset) : span;
    trace(probe ? 'probe' : 'point', fileId, offset, limit);
    windows.set(fileId, { start: offset, end: offset + limit, probe: !!probe });
    await call({
      '@type': 'downloadFile', file_id: fileId, priority: priority || 32,
      offset: offset, limit: limit, synchronous: false,
    });
  }

  /**
   * Points TDLib where a waiting read needs it: the playhead's window, a probe around a far read,
   * or, for reads carrying on past a probe, the playhead's window moved there (a real seek).
   */
  async function aim(fileId, offset, want, size) {
    clearTimeout(backTimers.get(fileId));
    const w = windows.get(fileId);
    const head = heads.get(fileId);
    if (w && w.probe && offset >= w.start && offset < w.end + FAR) {
      if (offset + want <= w.end - MIN_READ && offset + want <= w.end) return;
      heads.set(fileId, offset);
      return pointAt(fileId, offset, size, 32, false);
    }
    if (head != null && Math.abs(offset - head) > FAR) {
      // From the MiB the read starts in, so the read sits in the probe's first half.
      return pointAt(fileId, Math.floor(offset / (PROBE / 2)) * (PROBE / 2), size, 32, true);
    }
    heads.set(fileId, offset);
    return pointAt(fileId, offset, size, 32, false);
  }

  /** After the last probe, the download goes back to the playhead rather than sitting idle. */
  function backSoon(fileId, size) {
    const w = windows.get(fileId);
    const head = heads.get(fileId);
    if (!w || !w.probe || head == null) return;
    clearTimeout(backTimers.get(fileId));
    backTimers.set(fileId, setTimeout(() => {
      backTimers.delete(fileId);
      const now = windows.get(fileId);
      if (now && now.probe && queues.size === 0) pointAt(fileId, head, size, 32, false).catch(() => {});
    }, BACK_MS));
  }

  function covered(fileId, offset, need) {
    const w = windows.get(fileId);
    // A probe's window covers only its own stretch, and is never moved on.
    if (w && w.probe) return offset >= w.start && offset + need <= w.end;
    // Move the window on once the reads are past its middle, so TDLib keeps ahead of them.
    return w && offset >= w.start && offset + need <= w.end && offset - w.start < (w.end - w.start) / 2;
  }

  async function readPart(fileId, offset, n) {
    const part = await call({ '@type': 'readFilePart', file_id: fileId, offset: offset, count: n });
    return part.data.arrayBuffer();
  }

  /** The file's size and whether it is all here, asking TDLib only until it is known. */
  async function shape(fileId) {
    const known = sizes.get(fileId);
    if (known && known.complete) return known;
    const f = await file(fileId);
    const s = { size: f.size || f.expected_size || 0, complete: !!(f.local && f.local.is_downloading_completed) };
    if (s.size > 0) sizes.set(fileId, s);
    return s;
  }

  async function readRangeNow(fileId, offset, count, retried) {
    const f = await shape(fileId);
    const size = f.size;
    if (size > 0 && offset >= size) return new ArrayBuffer(0);
    if (size > 0) count = Math.min(count, size - offset);
    const want = Math.min(count, MIN_READ);
    let have = 0;
    if (f.complete) {
      have = count;
    } else {
      const started = Date.now();
      let quiet = 0;
      while (true) {
        have = await prefix(fileId, offset);
        trace('prefix', fileId, offset, have);
        if (have >= want) break;
        if (!covered(fileId, offset, want)) {
          await aim(fileId, offset, want, size);
          quiet = 0;
        }
        const updated = await waitFile(fileId, POLL_MS);
        if (updated && updated.local && updated.local.is_downloading_completed) {
          sizes.set(fileId, { size: size, complete: true });
          have = count;
          break;
        }
        // TDLib can end or move a download on its own (a cancelled or replaced request, a
        // warm-up, a dropped connection) while the window here still says it is coming. Its
        // updates say where it really is: when that is not where this read needs it, the window
        // is forgotten and the next turn points TDLib at the bytes again.
        const w = windows.get(fileId);
        const at = updated && updated.local ? updated.local.download_offset : null;
        if (w && updated && updated.local && updated.local.is_downloading_active && at != null &&
            (at > offset || at + WINDOW < offset)) {
          trace('moved', fileId, at, offset);
          windows.delete(fileId);
          quiet = 0;
        } else if (!updated || !updated.local || !updated.local.is_downloading_active) {
          if (++quiet >= 3) {
            const now = await file(fileId);
            if (!now.local || !now.local.is_downloading_active) windows.delete(fileId);
            quiet = 0;
          }
        } else {
          quiet = 0;
        }
        if (Date.now() - started > WAIT_MS) {
          if (have > 0) break;
          throw new Error('stopped loading');
        }
      }
      const w = windows.get(fileId);
      const head = heads.get(fileId);
      if (w && w.probe && head != null && Math.abs(offset - head) > FAR) {
        // A probe answered: back to the playhead once no more come. Reads that carry on past
        // its end move the playhead there instead (aim), as a real seek does.
        backSoon(fileId, size);
      } else {
        // The playhead moves with the reads near it; a far read served from the disk is a probe
        // too and leaves it where it is.
        if (head == null || Math.abs(offset - head) <= FAR) heads.set(fileId, offset + count);
        // Keep TDLib ahead of the reader even when the bytes were already there.
        if (!covered(fileId, offset + count, MIN_READ) && size > 0 && offset + count < size) {
          pointAt(fileId, offset, size, 32, false).catch(() => {});
        }
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
      sizes.delete(fileId);
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
          if (type === 'updateAuthorizationState' &&
              update.authorization_state['@type'] === 'authorizationStateReady') {
            // The sign in is kept against the browser clearing the site's storage. The videos
            // themselves are never written to it (tdweb/sparse-fs.js, memory only).
            try {
              if (navigator.storage && navigator.storage.persist) navigator.storage.persist().catch(() => {});
            } catch (e) {}
          }
          let text;
          try {
            text = JSON.stringify(update, replacer);
          } catch (e) {
            return;
          }
          if (type === 'updateNewChat' || type === 'updateChatTitle' || type === 'updateNewMessage' ||
            type === 'updateChatLastMessage' || type === 'updateUser') scan(text);
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
      return call(query).then((r) => {
        const text = JSON.stringify(r, replacer);
        // Message lists and chats answered to a request carry titles and file names too.
        if (r && /^(messages|foundChatMessages|foundMessages|chat|chats|messageProperties)$/.test(r['@type'])) scan(text);
        return text;
      }, (e) => errorText(e));
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

    readRange(fileId, offset, count, background) {
      if (!background) return serial(fileId, () => readRangeNow(fileId, offset, count, false));
      // Read-ahead: what TDLib already has at offset, now, or nothing.
      return (async () => {
        const f = await shape(fileId);
        if (f.size > 0 && offset >= f.size) return new ArrayBuffer(0);
        const n = Math.min(count, f.size > 0 ? f.size - offset : count, f.complete ? count : await prefix(fileId, offset));
        if (n < count) return new ArrayBuffer(0);
        return readPart(fileId, offset, n);
      })().catch(() => new ArrayBuffer(0));
    },

    // A video about to be played (a card held under the pointer or the focus for a moment, the
    // next episode near the end of this one): its head, then its last megabyte, where MP4 and MKV
    // often keep their index, so the first frame needs almost nothing more after the press.
    warm(fileId, size, head, tail) {
      // Not in the file's read queue: a warm-up must never hold up a play that starts meanwhile,
      // and a read that arrives points TDLib at its own window, which replaces this one.
      if (queues.has(fileId) || windows.has(fileId)) return Promise.resolve();
      const job = async () => {
        try {
          if ((await prefix(fileId, 0)) < head) {
            await Promise.race([
              call({ '@type': 'downloadFile', file_id: fileId, priority: 8, offset: 0, limit: head, synchronous: true }),
              new Promise((r) => setTimeout(r, 8000)),
            ]);
          }
          if (queues.has(fileId) || windows.has(fileId)) return;
          if (size > head + tail && tail > 0 && (await prefix(fileId, size - tail)) < tail) {
            await call({ '@type': 'downloadFile', file_id: fileId, priority: 8, offset: size - tail, limit: tail, synchronous: false });
          }
        } catch (e) {}
      };
      return job();
    },

    // Playback of a file is over: TDLib stops fetching ahead for it at once, so the next video
    // gets the whole connection.
    stop(fileId) {
      windows.delete(fileId);
      sizes.delete(fileId);
      heads.delete(fileId);
      clearTimeout(backTimers.get(fileId));
      return call({ '@type': 'cancelDownloadFile', file_id: fileId, only_if_pending: false }).catch(() => {});
    },

    forget(fileId) {
      windows.delete(fileId);
      sizes.delete(fileId);
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

    get log() {
      return log;
    },

    get started() {
      return client != null;
    },
  };

  window.TmTd = TmTd;
})();
