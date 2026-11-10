import { test } from 'node:test';
import assert from 'node:assert/strict';
import { subscribeToNodeEvents } from '../src/eventStream.mjs';
import { initialState, reducer } from '../src/model.mjs';

test('subscription waits for broker SYNC before requesting the authoritative snapshot', () => {
  let receive;
  let snapshotsRequested = 0;
  let generation = 1;
  let state = reducer(initialState, { type: 'SYNC_START', generation });
  state = reducer(state, { type: 'SNAPSHOT', generation, snapshot: { sequence: 4, torrents: [], downloads: [], events: [] } });
  const client = {
    subscribe(destination, callback) {
      assert.equal(destination, '/topic/node-events');
      receive = callback;
      return { id: 'node-events' };
    },
  };
  subscribeToNodeEvents(client, {
    isActive: () => true,
    onSync() {
      snapshotsRequested++;
      generation++;
      state = reducer(state, { type: 'SYNC_START', generation });
    },
    onEvent(event) { state = reducer(state, { type: 'EVENT', event, generation }); },
    onError() { assert.fail('Valid node events must parse'); },
  });
  assert.equal(snapshotsRequested, 0, 'STOMP CONNECTED and sending SUBSCRIBE do not prove broker registration');
  receive({ body: JSON.stringify({ sequence: 5, type: 'LOG', message: 'before acknowledgement' }) });
  assert.equal(snapshotsRequested, 0);
  receive({ body: JSON.stringify({ sequence: 6, type: 'SYNC', message: 'subscription registered' }) });
  assert.equal(snapshotsRequested, 1);
  assert.equal(state.snapshotReady, false);
  receive({ body: JSON.stringify({ sequence: 8, type: 'LOG', message: 'after acknowledgement' }) });
  state = reducer(state, { type: 'SNAPSHOT', generation, snapshot: { sequence: 7, torrents: [], downloads: [], events: [] } });
  assert.equal(state.sequence, 8);
  assert.deepEqual(state.events.map(event => event.message), ['after acknowledgement']);
});

test('additional SYNC broadcasts resynchronize existing subscribers', () => {
  let receive;
  const observed = [];
  subscribeToNodeEvents({ subscribe(_destination, callback) { receive = callback; } }, {
    isActive: () => true,
    onSync: () => observed.push('snapshot'),
    onEvent: event => observed.push(event.sequence),
    onError: () => observed.push('error'),
  });
  receive({ body: '{"sequence":10,"type":"SYNC"}' });
  receive({ body: '{"sequence":11,"type":"SYNC"}' });
  assert.deepEqual(observed, ['snapshot', 10, 'snapshot', 11]);
});

test('inactive subscriptions and malformed messages cannot resync or mutate state', () => {
  let receive;
  let active = false;
  const observed = [];
  subscribeToNodeEvents({ subscribe(_destination, callback) { receive = callback; } }, {
    isActive: () => active,
    onSync: () => observed.push('snapshot'),
    onEvent: () => observed.push('event'),
    onError: () => observed.push('error'),
  });
  receive({ body: '{"sequence":1,"type":"SYNC"}' });
  assert.deepEqual(observed, []);
  active = true;
  receive({ body: '{invalid-json' });
  assert.deepEqual(observed, ['error']);
});
