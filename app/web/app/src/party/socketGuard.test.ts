import { describe, expect, it } from 'vitest';
import { isCurrentPartySocket } from './socketGuard';

describe('Listen Together reconnect socket authority', () => {
  it('accepts the active connection and rejects older sockets within the same loop', () => {
    const oldSocket = {}, nextSocket = {};
    const loop = 11;
    expect(isCurrentPartySocket(loop, loop, oldSocket, oldSocket)).toBe(true);
    // A network drop closes socket A; reconnect creates socket B using the
    // same loop ID. A delayed message or late onopen from A is no longer valid.
    expect(isCurrentPartySocket(loop, loop, oldSocket, nextSocket)).toBe(false);
    expect(isCurrentPartySocket(loop, loop, nextSocket, nextSocket)).toBe(true);
    expect(isCurrentPartySocket(loop, loop + 1, nextSocket, nextSocket)).toBe(false);
    expect(isCurrentPartySocket(loop, loop, nextSocket, null)).toBe(false);
  });

  it('does not treat a same-looking new transport as the original socket', () => {
    const oldSocket = { url: 'wss://example.test/party', readyState: 1 };
    const replacement = { ...oldSocket };
    expect(isCurrentPartySocket(7, 7, oldSocket, replacement)).toBe(false);
  });
});
