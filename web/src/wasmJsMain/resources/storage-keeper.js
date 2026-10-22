// The site's storage keeper: what can be fetched again is let go before the browser runs out of
// room for the site, so the Telegram sign in is never what goes. A site over its quota has its
// writes refused (TDLib's database among them, so a sign in made then is never saved), and a disk
// under pressure has the browser clear a site that is not persistent whole, sign in included.
//
// What the site keeps, and where:
//
//   IndexedDB "/tmplayer/dbfs"   TDLib's database (emscripten's IDBFS, saved every 5 s): the sign
//                                in (auth key, td.binlog), chats, messages. Never touched here.
//   IndexedDB "tmplayer"         tdweb's store of finished files up to 8 MiB (thumbnails, photos,
//                                avatars), one Blob each. Stock tdweb never deletes from it, so it
//                                only grows. Emptied through tdweb's worker (TmTd.clearFileStore),
//                                which forgets the keys too; TDLib fetches a file again when asked.
//   IndexedDB "wasm-cache"       tdweb's fallback cache of its compiled module, used only when the
//                                streaming compile fails. Left alone.
//   OPFS "tdweb-files/"          video parts versions before 2026-10-21 wrote; removed.
//   Cache Storage tm-shell-v1    the app shell for an offline start (sw.js). Kept.
//   Cache Storage tm-runtime-v1  content-hashed files and fonts (sw.js); each release adds its own
//                                and the old ones stay. What this page did not load is let go.
//   localStorage tm.*            the settings, Watched, bookmarks, the viewer's choices: kept.
//                                tm.meta.answers, extras, seasons, skips, logos: metadata answers,
//                                halved (oldest first) or dropped when room is short.
//   memory                       video parts (tdweb/sparse-fs.js), pictures, the page cache.
//
// A pass runs at start, every minute while the page is shown (navigator.storage.estimate is
// cheap) and at once on any quota error seen: a localStorage write (TmHost.setItem), a Cache
// Storage write in sw.js, TDLib's database save or file store in tdweb's worker, or any promise
// rejected with QuotaExceededError; a full localStorage, whose limit is its own, is answered by
// trimming the metadata shelves there and then. Past HIGH of the quota (or after a quota error) the steps
// below run in order until the site is under LOW; after a quota error the disk steps run whatever
// the estimate says, since it lags behind and localStorage is not counted in it at all.
(function (root) {
  'use strict';

  const HIGH = 0.7;
  const LOW = 0.5;
  const PERIOD_MS = 60 * 1000;
  const START_MS = 4000;

  // The metadata shelves WebMetadata.kt writes; the least needed first.
  const SHELVES = ['tm.meta.logos', 'tm.meta.skips', 'tm.meta.seasons', 'tm.meta.extras', 'tm.meta.answers'];
  // sw.js's names.
  const SHELL = 'tm-shell-v1';
  const RUNTIME = 'tm-runtime-v1';

  // --- the policy (pure, tested by web/tools/storage-keeper.test.mjs) -----------------------

  function isQuota(e) {
    if (!e) return false;
    const name = e.name || (e.target && e.target.error && e.target.error.name) || '';
    return name === 'QuotaExceededError' || name === 'NS_ERROR_DOM_QUOTA_REACHED' ||
      e.code === 22 || e.code === 1014 || /quota/i.test(String(e.message || ''));
  }

  /** Whether a pass has work to do: a quota error, or the site past HIGH of a known quota. */
  function due(e, forced) {
    if (forced) return true;
    return e.quota > 0 && e.usage >= HIGH * e.quota;
  }

  /** Whether the site is back under the lower mark. */
  function under(e) {
    return e.quota > 0 && e.usage <= LOW * e.quota;
  }

  /**
   * One pass over [steps] ({ name, disk, run(forced) -> Promise<string> }), in order. A step runs
   * while the site is over LOW; after a quota error, a disk step runs regardless. Returns
   * { before, after, ran } or null when nothing was due.
   */
  async function pass(steps, measure, forced) {
    const before = await measure();
    if (!due(before, forced)) return null;
    let now = before;
    const ran = [];
    for (const step of steps) {
      if (under(now) && !(forced && step.disk)) continue;
      let note;
      try {
        note = await step.run(!!forced);
      } catch (e) {
        note = 'failed (' + ((e && (e.name || e.message)) || e) + ')';
      }
      ran.push(note ? step.name + ' ' + note : step.name);
      now = await measure();
    }
    return { before: before, after: now, ran: ran };
  }

  /** A metadata shelf's text with only its newest [keep] share of entries, or null to drop it. */
  function trimShelf(text, keep) {
    let list;
    try {
      list = JSON.parse(text).entries;
    } catch (e) {
      return null;
    }
    if (!Array.isArray(list)) return null;
    const n = Math.floor(list.length * keep);
    if (n <= 0) return null;
    return JSON.stringify({ entries: list.slice(list.length - n) });
  }

  // --- localStorage ---------------------------------------------------------------------------

  /** Halves every metadata shelf but [except] (dropping the ones left empty); characters freed. */
  function trimShelves(store, except, keep) {
    let freed = 0;
    for (const key of SHELVES) {
      if (key === except) continue;
      let text = null;
      try {
        text = store.getItem(key);
      } catch (e) {}
      if (text == null) continue;
      const next = keep > 0 ? trimShelf(text, keep) : null;
      try {
        if (next == null) store.removeItem(key);
        else store.setItem(key, next);
        freed += text.length - (next ? next.length : 0);
      } catch (e) {}
    }
    return freed;
  }

  /**
   * localStorage.setItem that never throws a quota error at the app: on one, the metadata shelves
   * are halved, then dropped, and the write tried again; a shelf that still does not fit is
   * written with fewer entries. The settings and the other tm.* keys are never removed. True when
   * the value was written.
   */
  function setItemKept(store, key, value) {
    try {
      store.setItem(key, value);
      return true;
    } catch (e) {
      if (!isQuota(e)) return false;
    }
    // localStorage has a small limit of its own, apart from the site's quota: the shelves below
    // make the room, and the disk steps run only when the estimate says the site is full too.
    request('local storage full', false);
    for (const keep of [0.5, 0]) {
      const freed = trimShelves(store, key, keep);
      log('local storage full writing ' + key + ': metadata trimmed by ' + size(freed * 2));
      try {
        store.setItem(key, value);
        return true;
      } catch (e) {
        if (!isQuota(e)) return false;
      }
    }
    if (SHELVES.indexOf(key) >= 0) {
      let text = value;
      while (text) {
        text = trimShelf(text, 0.5);
        if (!text) break;
        try {
          store.setItem(key, text);
          return true;
        } catch (e) {}
      }
    }
    log('local storage full: ' + key + ' was not saved');
    return false;
  }

  // --- the steps ------------------------------------------------------------------------------

  function used() {
    const set = new Set([location.href]);
    try {
      for (const r of performance.getEntriesByType('resource')) set.add(r.name.split('#')[0]);
    } catch (e) {}
    return set;
  }

  /** Cache Storage: caches of older service workers, and runtime files this page did not load. */
  async function runtimeCaches() {
    if (!root.caches) return '';
    let dropped = 0;
    for (const name of await caches.keys()) {
      if (name !== SHELL && name !== RUNTIME && name.indexOf('tm-') === 0) {
        if (await caches.delete(name)) dropped++;
      }
    }
    let entries = 0;
    if (await caches.has(RUNTIME)) {
      const cache = await caches.open(RUNTIME);
      const keep = used();
      for (const request of await cache.keys()) {
        if (!keep.has(request.url) && (await cache.delete(request))) entries++;
      }
    }
    return entries + ' entries' + (dropped ? ', ' + dropped + ' old caches' : '');
  }

  /** Video parts an older version wrote to the Origin Private File System. */
  async function opfsLeftovers() {
    if (!navigator.storage || !navigator.storage.getDirectory) return '';
    const top = await navigator.storage.getDirectory();
    try {
      await top.getDirectoryHandle('tdweb-files');
    } catch (e) {
      return 'none';
    }
    await top.removeEntry('tdweb-files', { recursive: true });
    return 'removed';
  }

  /** TDLib's finished small files (tdweb's IndexedDB file store), never its database. */
  async function tdlibFiles() {
    if (!root.TmTd || !root.TmTd.clearFileStore) return '';
    const n = await root.TmTd.clearFileStore();
    return n + ' files';
  }

  async function metadata() {
    const store = root.TmHost && root.TmHost.rawStore;
    if (!store) return '';
    return size(trimShelves(store, null, 0.5) * 2);
  }

  const STEPS = [
    { name: 'runtime cache', disk: true, run: runtimeCaches },
    { name: 'old video parts', disk: true, run: opfsLeftovers },
    { name: 'TDLib files', disk: true, run: tdlibFiles },
    { name: 'metadata', disk: false, run: metadata },
  ];

  // --- the browser side -----------------------------------------------------------------------

  function size(bytes) {
    if (!(bytes > 0)) return '0 B';
    const units = ['B', 'kB', 'MB', 'GB', 'TB'];
    let i = 0;
    let n = bytes;
    while (n >= 1000 && i < units.length - 1) {
      n /= 1000;
      i++;
    }
    return (i === 0 ? n : n.toFixed(1)) + ' ' + units[i];
  }

  function percent(e) {
    return e.quota > 0 ? Math.round((e.usage * 100) / e.quota) + '%' : '?';
  }

  function details(e) {
    const d = e.usageDetails;
    if (!d) return '';
    const parts = [];
    for (const k of Object.keys(d)) if (d[k]) parts.push(k + ' ' + size(d[k]));
    return parts.length ? ' (' + parts.join(', ') + ')' : '';
  }

  function log(text) {
    try {
      console.info('[tm-storage] ' + text);
    } catch (e) {}
  }

  async function measure() {
    try {
      const e = await navigator.storage.estimate();
      return { usage: e.usage || 0, quota: e.quota || 0, usageDetails: e.usageDetails };
    } catch (e) {
      return { usage: 0, quota: 0 };
    }
  }

  let running = null;
  let again = null;
  let lastPass = null;

  async function run(reason, forced) {
    const r = await pass(STEPS, measure, forced);
    if (!r) return null;
    lastPass = { at: Date.now(), reason: reason, before: r.before.usage, after: r.after.usage, ran: r.ran };
    log(reason + ': ' + size(r.before.usage) + ' of ' + size(r.before.quota) + ' (' + percent(r.before) + ')' +
      details(r.before) + ' -> ' + size(r.after.usage) + ' (' + percent(r.after) + '); freed ' +
      (r.ran.length ? r.ran.join(', ') : 'nothing'));
    return r;
  }

  /** Runs a pass now, or once more after the one running; quota errors make it a forced one. */
  function request(reason, forced) {
    forced = forced === undefined ? /quota/.test(reason) : forced;
    if (running) {
      if (!again || (forced && !again.forced)) again = { reason: reason, forced: forced };
      return running;
    }
    running = run(reason, forced).catch((e) => log('pass failed: ' + e)).finally(() => {
      running = null;
      const next = again;
      again = null;
      if (next) request(next.reason, next.forced);
    });
    return running;
  }

  let persistAsked = false;

  /**
   * Asks the browser to keep the site's storage under disk pressure (it is then never cleared
   * whole, sign in included), and logs its answer. Called once signed in, and again when the app
   * is installed, which is what Chromium's heuristics weigh most.
   */
  async function persist(why) {
    if (!navigator.storage || !navigator.storage.persist) {
      if (!persistAsked) log('persistent storage: not offered by this browser');
      persistAsked = true;
      return false;
    }
    try {
      if (navigator.storage.persisted && (await navigator.storage.persisted())) {
        if (!persistAsked) log('persistent storage: granted');
        persistAsked = true;
        return true;
      }
      const granted = await navigator.storage.persist();
      const installed = (() => {
        try {
          return matchMedia('(display-mode: standalone)').matches || navigator.standalone === true;
        } catch (e) {
          return false;
        }
      })();
      log('persistent storage (' + why + ', ' + (installed ? 'installed' : 'in a tab') + '): ' +
        (granted ? 'granted' : 'refused, the browser may clear the site under disk pressure'));
      persistAsked = true;
      return granted;
    } catch (e) {
      log('persistent storage: ' + e);
      return false;
    }
  }

  const TmKeeper = {
    HIGH: HIGH,
    LOW: LOW,
    isQuota: isQuota,
    due: due,
    under: under,
    pass: pass,
    trimShelf: trimShelf,
    trimShelves: trimShelves,
    setItemKept: setItemKept,
    STEPS: STEPS,
    SHELVES: SHELVES,
    request: request,
    persist: persist,
    /** A quota error reported from elsewhere (sw.js, tdweb's worker). */
    quotaError(where, error) {
      log('quota error in ' + where + (error ? ': ' + error : ''));
      return request('quota error: ' + where, true);
    },
    get lastPass() {
      return lastPass;
    },
  };

  if (typeof module !== 'undefined' && module.exports) module.exports = TmKeeper;
  if (typeof window === 'undefined' || root !== window) return;
  root.TmKeeper = TmKeeper;

  try {
    performance.setResourceTimingBufferSize(1000);
  } catch (e) {}

  root.addEventListener('unhandledrejection', (e) => {
    if (isQuota(e.reason)) TmKeeper.quotaError('a page write', e.reason && e.reason.name);
  });
  if ('serviceWorker' in navigator) {
    navigator.serviceWorker.addEventListener('message', (e) => {
      if (e.data && e.data.type === 'tm-storage-full') TmKeeper.quotaError('the offline cache', e.data.error);
    });
  }
  root.addEventListener('appinstalled', () => {
    persist('installed');
  });

  setTimeout(async () => {
    const e = await measure();
    let kept = false;
    try {
      kept = !!(navigator.storage && navigator.storage.persisted && (await navigator.storage.persisted()));
    } catch (err) {}
    log(size(e.usage) + ' of ' + size(e.quota) + ' used (' + percent(e) + ')' + details(e) +
      ', persistent: ' + (kept ? 'yes' : 'no'));
    request('start', false);
    setInterval(() => {
      if (document.visibilityState === 'visible') request('check', false);
    }, PERIOD_MS);
  }, START_MS);
})(typeof window !== 'undefined' ? window : globalThis);
