import { test } from 'node:test';
import assert from 'node:assert/strict';
import { initialState, reducer, chunkDetails, transferStats } from '../src/model.mjs';

const metadata = {
  fileId: 'file-a', fileName: 'sample.bin', fileSize: 600000, chunkSize: 262144,
  totalChunks: 3, chunkHashes: ['a'.repeat(64), 'b'.repeat(64), 'c'.repeat(64)], trackerUrl: 'http://localhost:8080',
};
const chunk = (chunkIndex, status, fields = {}) => ({ chunkIndex, status, attemptCount: 0, failoverHistory: [], ...fields });
const download = (status, chunks) => ({ fileId: 'file-a', metadata, status, startedAt: '2026-11-05T12:00:00Z', finishedAt: null, error: null, chunks });
const snapshot = (sequence, downloads = []) => ({ sequence, node: { peerId: 'peer-b', authenticated: true }, torrents: [metadata], downloads, events: [] });
const begin = (state = initialState, generation = 1) => reducer(state, { type: 'SYNC_START', generation });
const receive = (state, event, generation = 1) => reducer(state, { type: 'EVENT', event, generation });
const sync = (state, value, generation = 1) => reducer(state, { type: 'SNAPSHOT', snapshot: value, generation });

test('reconnect replays buffered events newer than the snapshot in sequence order', () => {
  let state = begin();
  state = receive(state, { sequence: 12, type: 'CHUNK', fileId: 'file-a', chunk: chunk(1, 'COMPLETED') });
  state = receive(state, { sequence: 11, type: 'CHUNK', fileId: 'file-a', chunk: chunk(0, 'COMPLETED') });
  state = receive(state, { sequence: 9, type: 'CHUNK', fileId: 'file-a', chunk: chunk(0, 'FAILED_RETRYING') });
  state = sync(state, snapshot(10, [download('DOWNLOADING', [chunk(0, 'DOWNLOADING'), chunk(1, 'PENDING')])]));
  assert.equal(state.sequence, 12);
  assert.equal(state.snapshotReady, true);
  assert.deepEqual(state.downloads['file-a'].chunks.map(c => c.status), ['COMPLETED', 'COMPLETED']);
  assert.equal(state.buffer.length, 0);
});

test('duplicate and late events cannot roll back a completed chunk', () => {
  let state = sync(begin(), snapshot(20, [download('DOWNLOADING', [chunk(0, 'DOWNLOADING')])]));
  state = receive(state, { sequence: 21, type: 'CHUNK', fileId: 'file-a', chunk: chunk(0, 'COMPLETED') });
  assert.equal(state.downloads['file-a'].chunks[0].status, 'COMPLETED');
  const completed = state;
  state = receive(state, { sequence: 20, type: 'CHUNK', fileId: 'file-a', chunk: chunk(0, 'PENDING') });
  state = receive(state, { sequence: 21, type: 'CHUNK', fileId: 'file-a', chunk: chunk(0, 'FAILED_RETRYING') });
  assert.equal(state, completed);
});

test('download restart replaces prior chunks instead of retaining verified state', () => {
  let state = sync(begin(), snapshot(30, [download('FAILED', [chunk(0, 'COMPLETED'), chunk(1, 'FAILED_RETRYING')])]));
  state = receive(state, { sequence: 31, type: 'DOWNLOAD', fileId: 'file-a', download: download('QUEUED', [chunk(0, 'PENDING'), chunk(1, 'PENDING')]) });
  assert.equal(state.downloads['file-a'].status, 'QUEUED');
  assert.deepEqual(state.downloads['file-a'].chunks.map(c => c.status), ['PENDING', 'PENDING']);
});

test('old connection responses are ignored and a server restart may reset sequence', () => {
  let state = sync(begin(), snapshot(80));
  state = begin(state, 2);
  state = receive(state, { sequence: 81, type: 'LOG', message: 'stale' }, 1);
  state = sync(state, snapshot(90), 1);
  assert.equal(state.snapshotReady, false);
  state = receive(state, { sequence: 3, type: 'LOG', message: 'new process' }, 2);
  state = sync(state, snapshot(2), 2);
  assert.equal(state.sequence, 3);
  assert.deepEqual(state.events.map(e => e.message), ['new process']);
});

test('chunk updates preserve sibling chunks and update their source details', () => {
  let state = sync(begin(), snapshot(1, [download('DOWNLOADING', [chunk(0, 'COMPLETED'), chunk(1, 'PENDING')])]));
  state = receive(state, { sequence: 2, type: 'CHUNK', fileId: 'file-a', chunk: chunk(1, 'FAILED_RETRYING', { sourcePeerId: 'peer-c', sourceUsername: 'carol', attemptCount: 2, failoverHistory: ['alice: connection reset'] }) });
  assert.equal(state.downloads['file-a'].chunks[0].status, 'COMPLETED');
  assert.equal(state.downloads['file-a'].chunks[1].sourceUsername, 'carol');
  assert.deepEqual(state.downloads['file-a'].chunks[1].failoverHistory, ['alice: connection reset']);
});

test('inspector exposes exact final-chunk byte range, full hashes and retry history', () => {
  const details = chunkDetails(metadata, chunk(2, 'COMPLETED', { expectedHash: 'c'.repeat(64), sha256Hash: 'd'.repeat(64), sourcePeerId: 'peer-c', sourceUsername: 'carol', sourceEndpoint: '127.0.0.1:6883', downloadDurationMs: 35, failoverHistory: ['peer-a: timeout'] }), 2);
  assert.equal(details.offset, 524288);
  assert.equal(details.length, 75712);
  assert.equal(details.end, 599999);
  assert.equal(details.expectedHash, 'c'.repeat(64));
  assert.equal(details.sha256Hash, 'd'.repeat(64));
  assert.deepEqual(details.failoverHistory, ['peer-a: timeout']);
  assert.equal(details.sourceEndpoint, '127.0.0.1:6883');
});

test('inspector derives pending hash and range before a download begins', () => {
  const details = chunkDetails(metadata, null, 1);
  assert.equal(details.expectedHash, 'b'.repeat(64));
  assert.equal(details.offset, 262144);
  assert.equal(details.length, 262144);
  assert.equal(details.status, 'PENDING');
  assert.equal(chunkDetails(metadata, null, 3), null);
  assert.equal(chunkDetails(metadata, null, -1), null);
});

test('progress counts verified bytes and accounts for a short final chunk', () => {
  const stats = transferStats(metadata, [chunk(0, 'COMPLETED'), chunk(1, 'DOWNLOADING'), chunk(2, 'COMPLETED')]);
  assert.equal(stats.verifiedBytes, 337856);
  assert.equal(stats.verifiedChunks, 2);
  assert.equal(stats.activeChunks, 1);
  assert.ok(Math.abs(stats.percent - 56.30933333333333) < 0.0000001);
  assert.equal(transferStats({ ...metadata, fileSize: 0, totalChunks: 0 }, []).percent, 100);
});
