import assert from 'node:assert/strict';
import { test } from 'node:test';
import { authorizedSenderOrigin } from './senderAuth.ts';

const own = 'chrome-extension://testext';
const allowed = new Set(['https://web.nuvx.lol', 'http://localhost:5173']);
const check = (origin, url, id) =>
  authorizedSenderOrigin({ origin, url, id }, allowed, own, 'testext');

test('permitted website and own extension messages remain authorized', () => {
  assert.equal(check('https://web.nuvx.lol', 'https://web.nuvx.lol/index.html'), 'https://web.nuvx.lol');
  assert.equal(check(undefined, 'http://localhost:5173/player'), 'http://localhost:5173');
  assert.equal(check(own, own + '/popup.html', 'testext'), own);
});

test('mismatched origins and other extension senders are denied', () => {
  for (const [origin, url, id] of [
    ['https://web.nuvx.lol', 'https://evil.example/'],
    ['https://evil.example', 'https://web.nuvx.lol/'],
    ['https://web.nuvx.lol.evil.example', null],
    ['null', null],
    [own, own + '/popup.html', 'other-extension'],
    [own, 'https://web.nuvx.lol/', 'testext'],
    [null, 'not-a-url'], [null, 'javascript:alert(1)'],
  ]) {
    assert.equal(check(origin, url, id), null, JSON.stringify({ origin, url, id }));
  }
});
