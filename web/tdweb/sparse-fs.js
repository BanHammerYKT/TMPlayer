// Sparse storage for TDLib's files directory inside tdweb's worker (CP92).
//
// Stock tdweb keeps a file that is still downloading in emscripten's MEMFS, which stores a file
// as one dense array: a part downloaded at 3.5 GB into a 4 GB video allocates 3.5 GB, so seeking
// near the end of a large video kills the tab, and on an iPhone far sooner. This module replaces
// the file nodes under the files directory with sparse ones:
//
//  - (Off since 2026-10-21, see DISK and prepare: the app keeps videos in memory only.)
//    On the Origin Private File System (OPFS) where the browser has it, through
//    FileSystemSyncAccessHandle in this worker: each part is written at its own offset, nothing
//    is held in memory, and what was downloaded survives a reload, so a video played before
//    opens from the disk, as the apps' watch cache does. Files live flat under
//    `tdweb-files/<id>`; `index.json` maps TDLib's paths to them.
//  - Otherwise in memory, in 1 MiB chunks with a least recently used ceiling (48 MiB on an
//    iPhone or iPad, 192 MiB elsewhere). A chunk that had to be dropped is remembered, and a read
//    over it fails with EIO, which the page answers by having TDLib download the file afresh.
//
// It is loaded into tdweb's worker by web/build.gradle.kts (patchTdweb), which also wires the two
// calls below into tdweb's InboundFileSystem.create: prepare(FS) before the directory is made,
// mount(FS, root) after. The same patch wires in the room keeping at the end of this file (the
// file store emptied on a quota error or on the page's asking). Nothing else in tdweb or TDLib
// changes.
self.TmSparse = (function () {
  'use strict';

  const CHUNK = 1 << 20;
  const mobile = /iPhone|iPad|iPod|Android/.test(self.navigator && self.navigator.userAgent || '');
  const MEMORY_BUDGET = (mobile ? 48 : 192) * CHUNK;
  const EIO = 29;
  // The disk (OPFS) mode below is kept for a build that wants it again; the browser app runs in
  // memory only (prepare).
  const DISK = false;

  let FS = null;
  let MEMFS = null;
  let opfs = null; // FileSystemDirectoryHandle, or null for memory mode
  let indexHandle = null;
  let rootPath = '';
  const restored = new Map(); // relative path -> { id, handle }
  const open = new Map(); // id -> node
  let nextId = 1;
  let saveTimer = 0;

  // Memory mode: chunks shared by every file, least recently used first.
  const lru = new Map(); // "id:chunk" -> node
  let memoryBytes = 0;

  const stats = { mode: 'memory', files: 0, memoryBytes: 0 };

  function log() {
    // console.debug('[tm-sparse]', ...arguments);
  }

  async function prepare(fsPromiseResult) {
    // Low storage, always (user, 2026-10-21): in the browser the videos live in memory only, up to
    // MEMORY_BUDGET, and nothing of them is written to the disk. A site that fills its quota has
    // everything it stored cleared at once by the browser, the sign in with it, and an iPhone's
    // storage is small; what a disk copy bought (a video reopened after a reload starting from
    // the disk) the head and tail warm-up nearly gives anyway. Parts an earlier version wrote to
    // the Origin Private File System are removed; TDLib fetches such a file afresh when asked.
    if (!DISK) {
      try {
        if (self.navigator && navigator.storage && navigator.storage.getDirectory) {
          const top = await navigator.storage.getDirectory();
          await top.removeEntry('tdweb-files', { recursive: true }).catch(() => {});
        }
      } catch (e) {}
      return;
    }
    try {
      if (!self.navigator || !navigator.storage || !navigator.storage.getDirectory) return;
      const top = await navigator.storage.getDirectory();
      const dir = await top.getDirectoryHandle('tdweb-files', { create: true });
      // Probe: some browsers expose OPFS but not the synchronous handle (or not in a worker).
      const probe = await dir.getFileHandle('probe', { create: true });
      if (!probe.createSyncAccessHandle) return;
      const ph = await probe.createSyncAccessHandle();
      ph.close();
      const ih = await dir.getFileHandle('index.json', { create: true });
      indexHandle = await ih.createSyncAccessHandle();
      let index = {};
      try {
        const size = indexHandle.getSize();
        if (size > 0) {
          const buf = new Uint8Array(size);
          indexHandle.read(buf, { at: 0 });
          index = JSON.parse(new TextDecoder().decode(buf)) || {};
        }
      } catch (e) {
        index = {};
      }
      const known = new Set(['index.json', 'probe']);
      for (const [path, entry] of Object.entries(index.files || {})) {
        try {
          const fh = await dir.getFileHandle(String(entry.id));
          const handle = await fh.createSyncAccessHandle();
          restored.set(path, { id: entry.id, handle: handle });
          known.add(String(entry.id));
          nextId = Math.max(nextId, entry.id + 1);
        } catch (e) {
          // Gone from the disk: TDLib finds the file missing and downloads it again.
        }
      }
      // Files no index entry points at are left over from a crash or a cleared database.
      for await (const name of dir.keys()) {
        if (!known.has(name)) {
          try {
            await dir.removeEntry(name);
          } catch (e) {}
        }
      }
      opfs = dir;
      stats.mode = 'opfs';
    } catch (e) {
      opfs = null;
      indexHandle = null;
    }
  }

  function relative(node) {
    const parts = [];
    let n = node;
    while (n && !n.tmRoot) {
      parts.unshift(n.name);
      if (n.parent === n) break;
      n = n.parent;
    }
    return parts.join('/');
  }

  function scheduleSave() {
    if (!indexHandle || saveTimer) return;
    saveTimer = setTimeout(saveIndex, 1000);
  }

  function saveIndex() {
    saveTimer = 0;
    if (!indexHandle) return;
    const files = {};
    for (const node of open.values()) {
      if (node.tm && node.tm.handle && !node.tm.unlinked) files[relative(node)] = { id: node.tm.id };
    }
    const bytes = new TextEncoder().encode(JSON.stringify({ version: 1, files: files }));
    try {
      indexHandle.truncate(0);
      indexHandle.write(bytes, { at: 0 });
      indexHandle.flush();
    } catch (e) {}
  }

  // --- memory chunks -------------------------------------------------------------------------

  function touch(node, index) {
    const key = node.tm.id + ':' + index;
    if (lru.has(key)) {
      lru.delete(key);
      lru.set(key, node);
    }
  }

  function chunkFor(node, index, create) {
    let chunk = node.tm.chunks.get(index);
    if (!chunk && create) {
      chunk = new Uint8Array(CHUNK);
      node.tm.chunks.set(index, chunk);
      node.tm.lost.delete(index);
      lru.set(node.tm.id + ':' + index, node);
      memoryBytes += CHUNK;
      evict(node);
    } else if (chunk) {
      touch(node, index);
    }
    return chunk;
  }

  function evict(keep) {
    while (memoryBytes > MEMORY_BUDGET && lru.size > 1) {
      const [key, node] = lru.entries().next().value;
      lru.delete(key);
      const index = Number(key.slice(key.indexOf(':') + 1));
      if (node === keep && node.tm.chunks.size <= 2) {
        lru.set(key, node);
        break;
      }
      if (node.tm.chunks.delete(index)) {
        memoryBytes -= CHUNK;
        // Still waiting for its disk file: the chunk must survive until it is written there.
        if (!node.tm.handle) node.tm.lost.add(index);
      }
    }
    stats.memoryBytes = memoryBytes;
  }

  function dropChunks(node) {
    for (const index of node.tm.chunks.keys()) {
      lru.delete(node.tm.id + ':' + index);
      memoryBytes -= CHUNK;
    }
    node.tm.chunks.clear();
    stats.memoryBytes = memoryBytes;
  }

  // --- the disk file behind a node ------------------------------------------------------------

  function attachDisk(node) {
    if (!opfs || node.tm.opening || node.tm.handle) return;
    node.tm.opening = true;
    (async () => {
      try {
        const fh = await opfs.getFileHandle(String(node.tm.id), { create: true });
        const handle = await fh.createSyncAccessHandle();
        if (node.tm.unlinked) {
          handle.close();
          await opfs.removeEntry(String(node.tm.id));
          return;
        }
        // Everything written while the handle was opening moves to the disk.
        for (const [index, chunk] of node.tm.chunks) {
          const start = index * CHUNK;
          const end = Math.min(start + CHUNK, node.usedBytes);
          if (end > start) handle.write(chunk.subarray(0, end - start), { at: start });
        }
        handle.truncate(node.usedBytes);
        node.tm.handle = handle;
        dropChunks(node);
        scheduleSave();
      } catch (e) {
        log('disk file failed', e);
      } finally {
        node.tm.opening = false;
      }
    })();
  }

  function releaseDisk(node) {
    const tm = node.tm;
    tm.unlinked = true;
    dropChunks(node);
    open.delete(tm.id);
    if (tm.handle) {
      try {
        tm.handle.close();
      } catch (e) {}
      tm.handle = null;
      const id = String(tm.id);
      opfs && opfs.removeEntry(id).catch(() => {});
    }
    stats.files = open.size;
    scheduleSave();
  }

  // --- emscripten operations ------------------------------------------------------------------

  function makeFile(node, restoredEntry) {
    node.tmSparse = true;
    node.contents = null;
    node.tm = {
      id: restoredEntry ? restoredEntry.id : nextId++,
      chunks: new Map(),
      lost: new Set(),
      handle: restoredEntry ? restoredEntry.handle : null,
      opening: false,
      unlinked: false,
    };
    node.usedBytes = restoredEntry ? restoredEntry.handle.getSize() : 0;
    node.node_ops = fileNodeOps;
    node.stream_ops = fileStreamOps;
    open.set(node.tm.id, node);
    stats.files = open.size;
    if (!node.tm.handle) attachDisk(node);
    else scheduleSave();
  }

  function makeDir(node) {
    node.tmSparse = true;
    node.node_ops = dirNodeOps;
  }

  const fileNodeOps = {
    getattr(node) {
      return MEMFS.node_ops.getattr(node);
    },
    setattr(node, attr) {
      if (attr.mode !== undefined) node.mode = attr.mode;
      if (attr.timestamp !== undefined) node.timestamp = attr.timestamp;
      if (attr.size !== undefined && attr.size !== node.usedBytes) {
        const size = attr.size;
        if (node.tm.handle) {
          node.tm.handle.truncate(size);
        } else {
          for (const index of Array.from(node.tm.chunks.keys())) {
            if (index * CHUNK >= size) {
              node.tm.chunks.delete(index);
              lru.delete(node.tm.id + ':' + index);
              memoryBytes -= CHUNK;
            }
          }
          for (const index of Array.from(node.tm.lost)) {
            if (index * CHUNK >= size) node.tm.lost.delete(index);
          }
          const last = Math.floor(size / CHUNK);
          const chunk = node.tm.chunks.get(last);
          if (chunk) chunk.fill(0, size - last * CHUNK);
        }
        node.usedBytes = size;
      }
    },
  };

  const fileStreamOps = {
    llseek(stream, offset, whence) {
      return MEMFS.stream_ops.llseek(stream, offset, whence);
    },
    read(stream, buffer, offset, length, position) {
      const node = stream.node;
      if (position >= node.usedBytes) return 0;
      const size = Math.min(node.usedBytes - position, length);
      if (node.tm.handle) {
        const target = buffer.subarray(offset, offset + size);
        const got = node.tm.handle.read(target, { at: position });
        // A hole at the end of the disk file reads short: it is zeros.
        if (got < size) target.fill(0, got);
        return size;
      }
      let done = 0;
      while (done < size) {
        const at = position + done;
        const index = Math.floor(at / CHUNK);
        const within = at - index * CHUNK;
        const n = Math.min(size - done, CHUNK - within);
        if (node.tm.lost.has(index)) throw new FS.ErrnoError(EIO);
        const chunk = chunkFor(node, index, false);
        if (chunk) buffer.set(chunk.subarray(within, within + n), offset + done);
        else buffer.fill(0, offset + done, offset + done + n);
        done += n;
      }
      return size;
    },
    write(stream, buffer, offset, length, position) {
      if (!length) return 0;
      const node = stream.node;
      node.timestamp = Date.now();
      if (node.tm.handle) {
        node.tm.handle.write(buffer.subarray(offset, offset + length), { at: position });
      } else {
        let done = 0;
        while (done < length) {
          const at = position + done;
          const index = Math.floor(at / CHUNK);
          const within = at - index * CHUNK;
          const n = Math.min(length - done, CHUNK - within);
          const chunk = chunkFor(node, index, true);
          chunk.set(buffer.subarray(offset + done, offset + done + n), within);
          done += n;
        }
      }
      node.usedBytes = Math.max(node.usedBytes, position + length);
      return length;
    },
    allocate(stream, offset, length) {
      const node = stream.node;
      if (offset + length > node.usedBytes) fileNodeOps.setattr(node, { size: offset + length });
    },
    mmap() {
      throw new FS.ErrnoError(43);
    },
    msync() {
      return 0;
    },
    close(stream) {
      if (stream.node.tm && stream.node.tm.handle) {
        try {
          stream.node.tm.handle.flush();
        } catch (e) {}
      }
    },
  };

  const dirNodeOps = {};

  function buildDirOps() {
    const base = MEMFS.ops_table.dir.node;
    Object.assign(dirNodeOps, base, {
      unlink(parent, name) {
        const node = parent.contents[name];
        if (node && node.tm) releaseDisk(node);
        base.unlink(parent, name);
      },
      rename(oldNode, newDir, newName) {
        const existing = newDir.contents[newName];
        if (existing && existing !== oldNode && existing.tm) releaseDisk(existing);
        base.rename(oldNode, newDir, newName);
        scheduleSave();
      },
    });
  }

  function mount(fs, root) {
    FS = fs;
    MEMFS = fs.filesystems.MEMFS;
    rootPath = root;
    // Fill the operations tables, which MEMFS builds on its first node.
    if (!MEMFS.ops_table) MEMFS.createNode(null, '/', 16384 | 511, 0);
    buildDirOps();
    const original = MEMFS.createNode;
    MEMFS.createNode = function (parent, name, mode, dev) {
      const node = original.call(MEMFS, parent, name, mode, dev);
      if (parent && parent.tmSparse) {
        if (FS.isDir(node.mode)) makeDir(node);
        else if (FS.isFile(node.mode)) makeFile(node, null);
      }
      return node;
    };
    const rootNode = FS.lookupPath(root).node;
    rootNode.tmSparse = true;
    rootNode.tmRoot = true;
    rootNode.node_ops = dirNodeOps;
    // What survived the last session comes back at the same paths, so TDLib, reading its own
    // database, finds the parts it downloaded then.
    for (const [path, entry] of restored) {
      const parts = path.split('/');
      let dir = rootNode;
      for (let i = 0; i < parts.length - 1; i++) {
        dir = dir.contents[parts[i]] || original.call(MEMFS, dir, parts[i], 16384 | 511, 0);
        if (!dir.tmSparse) makeDir(dir);
      }
      const node = original.call(MEMFS, dir, parts[parts.length - 1], 32768 | 420, 0);
      makeFile(node, entry);
    }
    restored.clear();
  }

  // --- room on the disk (storage-keeper.js on the page) ----------------------------------------
  //
  // tdweb keeps every finished file up to 8 MiB (thumbnails, photos) as a Blob in the IndexedDB
  // database named after the instance, and never deletes one: its deleteIdbKey does nothing. That
  // store is the one thing here that grows. TDLib's database is a separate IndexedDB database
  // (IDBFS under <instance>/dbfs) and is never touched.

  let inbound = null; // tdweb's InboundFileSystem, set by the patched InboundFileSystem.create
  let lastTold = 0;
  let lastSyncFix = 0;

  function ask(request) {
    return new Promise((resolve, reject) => {
      request.onsuccess = () => resolve(request.result);
      request.onerror = () => reject(request.error);
    });
  }

  /** Empties the file store and forgets its keys, so a file asked for again is fetched afresh. */
  async function clearFileStore() {
    if (!inbound) return 0;
    const idb = await inbound.idb;
    const n = await ask(idb.transaction(['keyvaluepairs'], 'readonly').objectStore('keyvaluepairs').count());
    if (n > 0) await ask(idb.transaction(['keyvaluepairs'], 'readwrite').objectStore('keyvaluepairs').clear());
    // An empty set, not null: tdweb's has() takes a missing set for "everything is there".
    inbound.pids = new Set();
    return n;
  }

  /** One file let go (TDLib's deleteFile through tdweb, TmTd.forget). */
  function deleteKey(key) {
    if (inbound && key) inbound.unlink(key);
  }

  function errorText(e) {
    return String((e && (e.name || e.message)) || e);
  }

  /** Tells the page's keeper, at most every 10 s. */
  function tell(reason, e, freed) {
    const now = Date.now();
    if (now - lastTold < 10000) return;
    lastTold = now;
    try {
      self.postMessage({ '@type': 'updateTmStorage', reason: reason, error: errorText(e), freed: freed || 0 });
    } catch (err) {}
  }

  /**
   * TDLib's database could not be saved (IDBFS's sync failed, the quota as a rule). The sign in
   * lives in it, so room is made here at once, without waiting for the page, and the save tried
   * again; IDBFS saves whatever changed since the last save that worked, so nothing is lost while
   * the tab stays open.
   */
  function onSyncError(e, dbfs) {
    console.warn('[tm-storage] TDLib\'s database was not saved: ' + errorText(e));
    const now = Date.now();
    if (now - lastSyncFix < 10000) return;
    lastSyncFix = now;
    clearFileStore().catch(() => 0).then((n) => {
      console.warn('[tm-storage] TDLib file store emptied for the database: ' + n + ' files');
      tell('database', e, n);
      setTimeout(() => {
        try {
          dbfs.sync(true);
        } catch (err) {}
      }, 0);
    });
  }

  /** A finished file could not be put in the file store: it stays in memory; the page frees room. */
  function onPersistError(e) {
    const name = (e && e.name) || '';
    if (name === 'QuotaExceededError' || name === 'AbortError' || /quota/i.test(String(e && e.message))) tell('files', e, 0);
  }

  return {
    prepare: prepare,
    mount: mount,
    stats: () => Object.assign({}, stats, { files: open.size }),
    set inbound(ifs) {
      inbound = ifs;
    },
    clearFileStore: clearFileStore,
    deleteKey: deleteKey,
    onSyncError: onSyncError,
    onPersistError: onPersistError,
  };
})();
