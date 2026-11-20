import { describe, expect, it } from 'vitest';
import { authorizedSenderOrigin } from './senderAuth';

const own = 'chrome-extension://testext';
const allowed = new Set(['https://web.nuvx.lol', 'http://localhost:5173']);
const check = (origin?: string | null, url?: string | null, id?: string | null) =>
  authorizedSenderOrigin({ origin, url, id }, allowed, own, 'testext');

describe('extension sender authorization', () => {
  it('allows permitted website and own extension identities', () => {
    expect(check('https://web.nuvx.lol', 'https://web.nuvx.lol/index.html')).toBe('https://web.nuvx.lol');
    expect(check(undefined, 'http://localhost:5173/player')).toBe('http://localhost:5173');
    expect(check(own, own + '/popup.html', 'testext')).toBe(own);
  });

  it('denies mismatched origin and URL, spoofed hosts, opaque and other extension senders', () => {
    for (const [origin, url, id] of [
      ['https://web.nuvx.lol', 'https://evil.example/'], ['https://evil.example', 'https://web.nuvx.lol/'],
      ['https://web.nuvx.lol.evil.example', null], ['null', null],
      [own, own + '/popup.html', 'another-extension'], [own, 'https://web.nuvx.lol/', 'testext'],
      [null, 'not-a-url'], [null, 'javascript:alert(1)'],
    ] as const) {
      expect(check(origin, url, id)).toBeNull();
    }
  });
});
