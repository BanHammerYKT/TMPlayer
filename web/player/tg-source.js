// A Telegram video as movi-player's SourceAdapter: getSize, read(offset, length), seek and the
// rest of its contract, over TDLib in the page (TmStream.read, which maps a video cut into
// pieces onto its pieces).
//
// Reads go in blocks with a least recently used cache, after Reelbox's reader:
//  - the blocks a read needs are asked for together, not one after another;
//  - the read the player waits on is the only kind that points TDLib's download anywhere; the
//    read-ahead behind it only copies what TDLib has already fetched (td-bridge.js), so it never
//    holds that read up nor drags the download back after a seek;
//  - the first 2 MiB and the last 1 MiB (where MP4 and MKV keep their headers and index) are
//    never evicted, so the demuxer's returns to them cost nothing;
//  - the cache outlives the source: a switch to the software decoder, a retry or a reopen of the
//    same video reads what is already here rather than the file again.
// TDLib itself downloads a window ahead of the reads (td-bridge.js), so memory stays bounded
// whatever the file's size.
(function () {
  'use strict';

  const mobile = /iPhone|iPad|iPod|Android/.test(navigator.userAgent) ||
    (navigator.platform === 'MacIntel' && navigator.maxTouchPoints > 1);
  // movi reads in 512 KiB steps: a block that size means the read after a seek waits for just
  // what it asked for, not for TDLib to fill a larger block from a download that has only begun.
  const BLOCK = 512 * 1024;
  const MAX_BLOCKS = mobile ? 48 : 96;
  const AHEAD = 12;
  // Kept for good: the first 2 MiB and the last 1 MiB, where the headers and the index are.
  const HEAD_BLOCKS = 4;
  const TAIL_BLOCKS = 2;
  const RETRIES = 4;
  // Videos whose blocks are kept after their source closes: the one playing and the one before.
  const KEPT = 2;

  const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

  /** Blocks by video key, shared by every source over that video. */
  const caches = new Map();
  function cacheFor(key) {
    let c = caches.get(key);
    if (c) {
      caches.delete(key);
    } else {
      c = new Map();
    }
    caches.set(key, c);
    while (caches.size > KEPT) caches.delete(caches.keys().next().value);
    return c;
  }

  class TgSource {
    constructor(source, key) {
      this.source = source;
      this.key = key;
      this.size = source.sizes.reduce((a, b) => a + b, 0);
      this.last = Math.max(0, Math.ceil(this.size / BLOCK) - 1);
      this.pos = 0;
      this.blocks = cacheFor(key);
      this.inflight = new Map();
      this.closed = false;
      this.fatal = null;
      this.refusing = false;
      this.lastBlock = -1;
    }

    getSize() {
      return Promise.resolve(this.size);
    }

    getKey() {
      return this.key;
    }

    getPosition() {
      return this.pos;
    }

    seek(offset) {
      this.pos = Math.max(0, Math.min(offset, this.size));
      return this.pos;
    }

    getFatalError() {
      return this.fatal;
    }

    isRefusing() {
      return this.refusing;
    }

    close() {
      // The blocks stay in the shared cache for the next source over this video.
      this.closed = true;
      this.inflight.clear();
    }

    pinned(index) {
      return index < HEAD_BLOCKS || index > this.last - TAIL_BLOCKS;
    }

    remember(index, buf) {
      this.blocks.set(index, buf);
      if (this.blocks.size <= MAX_BLOCKS) return;
      for (const k of this.blocks.keys()) {
        if (this.blocks.size <= MAX_BLOCKS) break;
        if (!this.pinned(k)) this.blocks.delete(k);
      }
    }

    async fetchBlock(index) {
      const start = index * BLOCK;
      const want = Math.min(BLOCK, this.size - start);
      const out = new Uint8Array(want);
      let done = 0;
      while (done < want && !this.closed) {
        let buf = null;
        for (let attempt = 0; attempt < RETRIES; attempt++) {
          try {
            buf = await window.TmStream.read(this.source, start + done, want - done);
            this.refusing = false;
            break;
          } catch (e) {
            this.refusing = true;
            if (attempt === RETRIES - 1) {
              this.fatal = e instanceof Error ? e : new Error(String(e));
              throw this.fatal;
            }
            await sleep(400 * Math.pow(2, attempt));
          }
        }
        if (!buf || buf.byteLength === 0) break;
        out.set(new Uint8Array(buf), done);
        done += buf.byteLength;
      }
      return done === want ? out : out.subarray(0, done);
    }

    block(index) {
      const cached = this.blocks.get(index);
      if (cached) {
        this.blocks.delete(index);
        this.blocks.set(index, cached);
        return Promise.resolve(cached);
      }
      let p = this.inflight.get(index);
      if (!p) {
        p = this.fetchBlock(index).then((buf) => {
          this.inflight.delete(index);
          if (buf.byteLength === this.expected(index)) this.remember(index, buf);
          return buf;
        }, (e) => {
          this.inflight.delete(index);
          throw e;
        });
        this.inflight.set(index, p);
      }
      return p;
    }

    expected(index) {
      return Math.min(BLOCK, this.size - index * BLOCK);
    }

    /** A block TDLib already has, copied in without waiting or moving the download. */
    async readAhead(index) {
      if (this.blocks.has(index) || this.inflight.has(index)) return;
      const want = this.expected(index);
      const p = window.TmStream.read(this.source, index * BLOCK, want, true).then((buf) => {
        if (!this.closed && buf && buf.byteLength === want) this.remember(index, new Uint8Array(buf));
      }, () => {}).finally(() => {
        if (this.inflight.get(index) === p) this.inflight.delete(index);
      });
      // Not in inflight: a read that needs this block asks TDLib itself rather than waiting on a
      // copy that may come back empty.
      await p;
    }

    async read(offset, length) {
      TgSource.stats.reads++;
      TgSource.stats.last = offset;
      if (TgSource.stats.trace.length < 400) TgSource.stats.trace.push([Math.round(performance.now()), offset, length]);
      if (this.closed || offset >= this.size || length <= 0) return new ArrayBuffer(0);
      const end = Math.min(offset + length, this.size);
      const first = Math.floor(offset / BLOCK);
      const lastIndex = Math.floor((end - 1) / BLOCK);
      const wanted = [];
      for (let i = first; i <= lastIndex; i++) wanted.push(i);
      const got = await Promise.all(wanted.map((i) => this.block(i)));
      const out = new Uint8Array(end - offset);
      let done = 0;
      for (let n = 0; n < got.length; n++) {
        const base = wanted[n] * BLOCK;
        const from = Math.max(0, offset - base);
        const to = Math.min(got[n].byteLength, end - base);
        if (to <= from) break;
        out.set(got[n].subarray(from, to), done);
        done += to - from;
        if (got[n].byteLength < this.expected(wanted[n])) break;
      }
      this.pos = offset + done;
      TgSource.stats.bytes += done;
      // Read ahead only while the reads run forward; a jump (a seek, an index probe) starts the
      // run again rather than fetching around a place the player has already left.
      if (first === this.lastBlock || first === this.lastBlock + 1) {
        for (let i = 1; i <= AHEAD; i++) {
          const next = lastIndex + i;
          if (next <= this.last) this.readAhead(next);
        }
      }
      this.lastBlock = lastIndex;
      return done === out.length ? out.buffer : out.slice(0, done).buffer;
    }
  }

  /** Counters for the tests, the playback details panel and the opening loader (bytes handed to the player). */
  TgSource.stats = { reads: 0, last: 0, trace: [], bytes: 0 };
  window.TgSource = TgSource;
})();
