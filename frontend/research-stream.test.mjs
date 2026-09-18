import { test } from 'node:test';
import assert from 'node:assert/strict';
import { build } from 'esbuild';
import { mkdtemp, rm } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { pathToFileURL, fileURLToPath } from 'node:url';
const directory = await mkdtemp(join(tmpdir(), 'research-stream-'));
await build({ entryPoints: [fileURLToPath(new URL('./src/services/researchStream.ts', import.meta.url))], bundle: true, platform: 'node', format: 'esm', outfile: join(directory, 'stream.mjs') });
const { subscribeResearchStream } = await import(pathToFileURL(join(directory, 'stream.mjs')));
await rm(directory, { recursive: true });
const active = { status: 'RUNNING' }, final = { status: 'COMPLETED', artifact: { title: 'Saved result' } };
const frame = (name, data) => `event: ${name}\ndata: ${JSON.stringify(data)}\n\n`;
const response = (text, close = true) => new Response(new ReadableStream({ start(c) {
  if (text) c.enqueue(new TextEncoder().encode(text)); if (close) c.close();
} }), { headers: { 'Content-Type': 'text/event-stream' } });
const waitFor = async (predicate) => {
  const end = Date.now() + 1500;
  while (!predicate()) { assert.ok(Date.now() < end, 'condition timed out'); await new Promise(r => setTimeout(r, 5)); }
};

test('idle socket reconnects, replays cursor, deduplicates, and stops on terminal snapshot', async () => {
  const delivered = [], cursors = [], snapshots = [];
  const stop = subscribeResearchStream(0, { event: e => delivered.push(e.sequence), snapshot: s => snapshots.push(s), connection() {} }, {
    idleMs: 30, backoffMs: 5, random: () => 0.5, snapshot: async () => active,
    open: async cursor => {
      cursors.push(cursor);
      return cursors.length === 1 ? response(frame('progress', { sequence: 1 }), false)
        : response(frame('progress', { sequence: 1 }) + frame('artifact', { sequence: 2 }) + frame('snapshot', final), false);
    }
  });
  try {
    await waitFor(() => snapshots.some(s => s.status === 'COMPLETED'));
    assert.deepEqual(cursors, [0, 1]); assert.deepEqual(delivered, [1, 2]);
    await new Promise(r => setTimeout(r, 70)); assert.equal(cursors.length, 2);
  } finally { stop(); }
});

test('snapshot timeout is retried and final snapshot avoids opening a stream', async () => {
  let snapshots = 0, opens = 0, completed = false;
  const stop = subscribeResearchStream(3, { event() {}, snapshot: s => { completed = s.status === 'COMPLETED'; }, connection() {} }, {
    idleMs: 25, backoffMs: 5,
    snapshot: signal => ++snapshots === 1 ? new Promise((_, reject) => signal.addEventListener('abort', () => reject(new Error('timeout')), { once: true })) : Promise.resolve(final),
    open: async () => { opens++; return response(''); }
  });
  try { await waitFor(() => completed); assert.equal(opens, 0); assert.equal(snapshots, 2); } finally { stop(); }
});

test('unsubscribe cancels stalled reader and prevents reconnects', async () => {
  let opens = 0, callbacks = 0;
  const stop = subscribeResearchStream(0, { event() { callbacks++; }, snapshot() {}, connection() {} }, {
    idleMs: 25, backoffMs: 5, snapshot: async () => active,
    open: async () => { opens++; return response('', false); }
  });
  await waitFor(() => opens === 1); stop();
  await new Promise(r => setTimeout(r, 80)); assert.equal(opens, 1); assert.equal(callbacks, 0);
});

test('broken frame is discarded and replay resumes from last complete event', async () => {
  const cursors = [], delivered = []; let completed = false;
  const stop = subscribeResearchStream(0, { event: e => delivered.push(e.sequence), snapshot: s => { completed = s.status === 'COMPLETED'; }, connection() {} }, {
    idleMs: 25, backoffMs: 5, snapshot: async () => active,
    open: async cursor => {
      cursors.push(cursor);
      return cursors.length === 1 ? response(frame('progress', { sequence: 1 }) + 'event: artifact\ndata: {"seq')
        : response(frame('artifact', { sequence: 2 }) + frame('snapshot', final));
    }
  });
  try { await waitFor(() => completed); assert.deepEqual(cursors, [0, 1]); assert.deepEqual(delivered, [1, 2]); } finally { stop(); }
});
