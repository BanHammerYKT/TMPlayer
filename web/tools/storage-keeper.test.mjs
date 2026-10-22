// The storage keeper's policy (web/src/wasmJsMain/resources/storage-keeper.js): when a pass runs,
// the order of its steps, where it stops, and how a full local storage is handled.
//
//   node --test web/tools/storage-keeper.test.mjs
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { createRequire } from 'node:module';

const require = createRequire(import.meta.url);
const K = require('../src/wasmJsMain/resources/storage-keeper.js');
console.info = () => {};

const GB = 1e9;

/** Steps that each free [frees] bytes from a shared usage, recording the order they ran in. */
function world(usage, quota, frees) {
  const state = { usage: usage, ran: [] };
  const steps = frees.map(([name, disk, bytes]) => ({
    name: name,
    disk: disk,
    run: async () => {
      state.ran.push(name);
      state.usage = Math.max(0, state.usage - bytes);
      return '';
    },
  }));
  const measure = async () => ({ usage: state.usage, quota: quota });
  return { state, steps, measure };
}

const ORDER = [
  ['runtime cache', true, 0.1 * GB],
  ['old video parts', true, 0.1 * GB],
  ['TDLib files', true, 0.3 * GB],
  ['metadata', false, 0.01 * GB],
];

test('the real steps go disk caches first, metadata last, and never name the database', () => {
  assert.deepEqual(K.STEPS.map((s) => s.name), ['runtime cache', 'old video parts', 'TDLib files', 'metadata']);
  assert.deepEqual(K.STEPS.map((s) => s.disk), [true, true, true, false]);
  assert.ok(K.SHELVES.every((k) => k.startsWith('tm.meta.') && k !== 'tm.meta.settings'));
});

test('under the high mark nothing runs', async () => {
  const w = world(0.69 * GB, 1 * GB, ORDER);
  assert.equal(await K.pass(w.steps, w.measure, false), null);
  assert.deepEqual(w.state.ran, []);
});

test('an unknown quota runs nothing unless a quota error forces it', async () => {
  const w = world(5 * GB, 0, ORDER);
  assert.equal(await K.pass(w.steps, w.measure, false), null);
  const r = await K.pass(w.steps, w.measure, true);
  assert.deepEqual(w.state.ran, ['runtime cache', 'old video parts', 'TDLib files', 'metadata']);
  assert.ok(r.after.usage < r.before.usage);
});

test('past the high mark the steps run in order and stop under the low mark', async () => {
  const w = world(0.75 * GB, 1 * GB, ORDER);
  const r = await K.pass(w.steps, w.measure, false);
  // 0.75 -> 0.65 -> 0.55 -> 0.25: under 0.5 after the TDLib files, so metadata is kept.
  assert.deepEqual(w.state.ran, ['runtime cache', 'old video parts', 'TDLib files']);
  assert.ok(K.under(r.after));
});

test('one step can be enough', async () => {
  const w = world(0.72 * GB, 1 * GB, [['runtime cache', true, 0.5 * GB], ...ORDER.slice(1)]);
  await K.pass(w.steps, w.measure, false);
  assert.deepEqual(w.state.ran, ['runtime cache']);
});

test('a quota error runs every disk step even when the estimate says there is room', async () => {
  const w = world(0.1 * GB, 1 * GB, ORDER);
  await K.pass(w.steps, w.measure, true);
  assert.deepEqual(w.state.ran, ['runtime cache', 'old video parts', 'TDLib files']);
});

test('a failing step does not stop the pass', async () => {
  const w = world(0.9 * GB, 1 * GB, ORDER);
  w.steps[0].run = async () => {
    throw new Error('boom');
  };
  const r = await K.pass(w.steps, w.measure, false);
  assert.equal(r.ran[0], 'runtime cache failed (Error)');
  assert.deepEqual(w.state.ran, ['old video parts', 'TDLib files']);
});

test('quota errors are recognised by name and code', () => {
  assert.ok(K.isQuota({ name: 'QuotaExceededError' }));
  assert.ok(K.isQuota({ name: 'NS_ERROR_DOM_QUOTA_REACHED' }));
  assert.ok(K.isQuota({ code: 22 }));
  assert.ok(!K.isQuota({ name: 'NotFoundError' }));
  assert.ok(!K.isQuota(null));
});

test('a shelf keeps its newest entries', () => {
  const text = JSON.stringify({ entries: [{ key: 'a' }, { key: 'b' }, { key: 'c' }, { key: 'd' }] });
  assert.deepEqual(JSON.parse(K.trimShelf(text, 0.5)).entries.map((e) => e.key), ['c', 'd']);
  assert.equal(K.trimShelf(JSON.stringify({ entries: [{ key: 'a' }] }), 0.5), null);
  assert.equal(K.trimShelf('not json', 0.5), null);
});

/** A localStorage with a character limit, as browsers have. */
function limitedStore(limit) {
  const map = new Map();
  const total = () => [...map].reduce((n, [k, v]) => n + k.length + v.length, 0);
  return {
    map,
    getItem: (k) => (map.has(k) ? map.get(k) : null),
    removeItem: (k) => map.delete(k),
    setItem(k, v) {
      const old = map.get(k);
      map.set(k, String(v));
      if (total() > limit) {
        if (old === undefined) map.delete(k);
        else map.set(k, old);
        const e = new Error('full');
        e.name = 'QuotaExceededError';
        throw e;
      }
    },
  };
}

function shelf(n, pad) {
  return JSON.stringify({ entries: Array.from({ length: n }, (_, i) => ({ key: 'k' + i, v: 'x'.repeat(pad) })) });
}

test('a full local storage frees the metadata, keeps the settings and saves the write', () => {
  const store = limitedStore(20000);
  store.setItem('tm.settings', 'S'.repeat(2000));
  store.setItem('tm.watched', 'W'.repeat(1000));
  store.setItem('tm.meta.answers', shelf(40, 300));
  store.setItem('tm.meta.extras', shelf(10, 300));
  const settings = 'T'.repeat(6000);
  assert.ok(K.setItemKept(store, 'tm.settings', settings));
  assert.equal(store.getItem('tm.settings'), settings);
  assert.equal(store.getItem('tm.watched'), 'W'.repeat(1000));
  // The answers shelf was halved, newest kept.
  const left = JSON.parse(store.getItem('tm.meta.answers') || '{"entries":[]}').entries;
  assert.ok(left.length < 40);
  if (left.length) assert.equal(left[left.length - 1].key, 'k39');
});

test('a metadata shelf too big to fit is written with fewer entries', () => {
  const store = limitedStore(10000);
  store.setItem('tm.settings', 'S'.repeat(3000));
  assert.ok(K.setItemKept(store, 'tm.meta.answers', shelf(40, 300)));
  assert.equal(store.getItem('tm.settings'), 'S'.repeat(3000));
  const left = JSON.parse(store.getItem('tm.meta.answers')).entries;
  assert.ok(left.length > 0 && left.length < 40);
  assert.equal(left[left.length - 1].key, 'k39');
});

test('a settings write that cannot fit fails without removing the other settings', () => {
  const store = limitedStore(5000);
  store.setItem('tm.watched', 'W'.repeat(3000));
  assert.equal(K.setItemKept(store, 'tm.settings', 'S'.repeat(4000)), false);
  assert.equal(store.getItem('tm.watched'), 'W'.repeat(3000));
});
