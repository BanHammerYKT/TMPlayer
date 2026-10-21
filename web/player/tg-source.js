// A Telegram video as movi-player's SourceAdapter: getSize, read(offset, length), seek and the
// rest of its contract, over TDLib in the page (TmStream.read, which maps a video cut into
// pieces onto its pieces). Reads go in 1 MiB blocks with a small least recently used cache and
// one block of read-ahead; TDLib itself downloads a window ahead of the reads (td-bridge.js), so
// this cache only saves the round trips, and memory stays bounded whatever the file's size.
(function () {
  'use strict';

  const BLOCK = 1024 * 1024;
  const mobile = /iPhone|iPad|iPod|Android/.test(navigator.userAgent) ||
    (navigator.platform === 'MacIntel' && navigator.maxTouchPoints > 1);
  const MAX_BLOCKS = mobile ? 12 : 32;
  const AHEAD = 2;
  const RETRIES = 4;

  const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

  class TgSource {
    constructor(source, key) {
      this.source = source;
      this.key = key;
      this.size = source.sizes.reduce((a, b) => a + b, 0);
      this.pos = 0;
      this.blocks = new Map();
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
      this.closed = true;
      this.blocks.clear();
      this.inflight.clear();
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
      return done === want ? out.buffer : out.slice(0, done).buffer;
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
          if (!this.closed && buf.byteLength > 0) {
            this.blocks.set(index, buf);
            while (this.blocks.size > MAX_BLOCKS) this.blocks.delete(this.blocks.keys().next().value);
          }
          return buf;
        }, (e) => {
          this.inflight.delete(index);
          throw e;
        });
        this.inflight.set(index, p);
      }
      return p;
    }

    async read(offset, length) {
      TgSource.stats.reads++;
      TgSource.stats.last = offset;
      if (TgSource.stats.trace.length < 400) TgSource.stats.trace.push([Math.round(performance.now()), offset, length]);
      if (this.closed) return new ArrayBuffer(0);
      if (offset >= this.size) return new ArrayBuffer(0);
      length = Math.min(length, this.size - offset);
      const out = new Uint8Array(length);
      let done = 0;
      let index = Math.floor(offset / BLOCK);
      while (done < length) {
        const at = offset + done;
        index = Math.floor(at / BLOCK);
        const block = await this.block(index);
        const within = at - index * BLOCK;
        const n = Math.min(length - done, block.byteLength - within);
        if (n <= 0) break;
        out.set(new Uint8Array(block, within, n), done);
        done += n;
      }
      this.pos = offset + done;
      // Read ahead only while the reads run forward; a seek starts the run again.
      if (index === this.lastBlock || index === this.lastBlock + 1) {
        for (let i = 1; i <= AHEAD; i++) {
          const next = index + i;
          if (next * BLOCK < this.size && !this.blocks.has(next)) this.block(next).catch(() => {});
        }
      }
      this.lastBlock = index;
      return done === length ? out.buffer : out.slice(0, done).buffer;
    }
  }

  /** Counters for the tests and the playback details panel. */
  TgSource.stats = { reads: 0, last: 0, trace: [] };
  window.TgSource = TgSource;
})();
