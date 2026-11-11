import { test } from 'node:test';
import assert from 'node:assert/strict';
import { selectAvailableFile } from '../src/selection.mjs';

test('drops stale selection when tracker directory becomes empty', () => {
  assert.equal(selectAvailableFile([], 'old-file'), null);
  assert.equal(selectAvailableFile([{ fileId: 'new-file' }], 'old-file'), 'new-file');
  assert.equal(selectAvailableFile([{ fileId: 'a' }, { fileId: 'b' }], 'b'), 'b');
});
