const assert = require('node:assert/strict');
const { readFileSync } = require('node:fs');
const { runInNewContext } = require('node:vm');
const { test } = require('node:test');

const source = readFileSync(`${__dirname}/site.js`, 'utf8');
const releasePage = 'https://github.com/bxanedot/nuvx/releases/latest';

function loadPage(userAgentData) {
  const hero = { href: releasePage };
  const note = { textContent: '' };
  const version = { textContent: '0.9.0' };
  const elements = {
    '#device-download': hero,
    '#device-note': note,
    '#download-label': { textContent: '' },
    '[data-version]': version,
  };
  const context = {
    document: {
      documentElement: { dataset: { theme: 'dark' }, hasAttribute: () => false, toggleAttribute() {} },
      body: { prepend() {} },
      createElement: () => ({ setAttribute() {} }),
      querySelector: (selector) => elements[selector] ?? null,
      querySelectorAll: () => [],
    },
    navigator: {
      userAgent: 'Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7)',
      platform: 'MacIntel',
      maxTouchPoints: 0,
      ...(userAgentData === undefined ? {} : { userAgentData }),
    },
    matchMedia: () => ({ matches: true, addEventListener() {} }),
    fetch: () => Promise.resolve({ ok: false }),
    window: {},
  };
  runInNewContext(source, context);
  return { hero, flush: () => new Promise(setImmediate) };
}

test('macOS download avoids guessing Apple silicon when architecture is unavailable', async () => {
  const page = loadPage();
  await page.flush();
  assert.equal(page.hero.href, releasePage);
});

test('macOS download uses a supported architecture hint', async (t) => {
  const routes = [
    ['Intel x86', 'x86', '/api/download?platform=mac-intel'],
    ['Intel x86_64', 'x86_64', '/api/download?platform=mac-intel'],
    ['Apple silicon arm', 'arm', '/api/download?platform=mac-arm'],
    ['Apple silicon arm64', 'arm64', '/api/download?platform=mac-arm'],
    ['unknown architecture', 'unknown', releasePage],
  ];

  for (const [name, architecture, expected] of routes) {
    await t.test(name, async () => {
      const page = loadPage({ getHighEntropyValues: () => Promise.resolve({ architecture }) });
      await page.flush();
      assert.equal(page.hero.href, expected);
    });
  }
});

test('macOS download keeps the release chooser when the architecture hint fails', async () => {
  const page = loadPage({ getHighEntropyValues: () => Promise.reject(new Error('unavailable')) });
  await page.flush();
  assert.equal(page.hero.href, releasePage);
});
