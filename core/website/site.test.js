const assert = require('node:assert/strict');
const { readFileSync } = require('node:fs');
const { runInNewContext } = require('node:vm');
const { test } = require('node:test');

const source = readFileSync(`${__dirname}/site.js`, 'utf8');
const packageList = '#releases';

function loadPage(userAgentData, device = {}) {
  const hero = { href: packageList };
  const nightly = { href: packageList };
  const note = { textContent: '' };
  const version = { textContent: '0.9.0' };
  const elements = {
    '#device-download': hero,
    '#nightly-download': nightly,
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
      userAgent: device.userAgent ?? 'Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7)',
      platform: device.platform ?? 'MacIntel',
      maxTouchPoints: device.maxTouchPoints ?? 0,
      ...(userAgentData === undefined ? {} : { userAgentData }),
    },
    matchMedia: () => ({ matches: true, addEventListener() {} }),
    fetch: () => Promise.resolve({ ok: false }),
    window: {},
  };
  runInNewContext(source, context);
  return { hero, nightly, note, label: elements['#download-label'], flush: () => new Promise(setImmediate) };
}

test('macOS uses the package list when its architecture cannot be read', async () => {
  const page = loadPage();
  await page.flush();
  assert.equal(page.hero.href, packageList);
  assert.equal(page.nightly.href, packageList);
});

test('macOS download uses a supported architecture hint', async (t) => {
  const routes = [
    ['Intel x86', 'x86', '/api/download?platform=mac-intel'],
    ['Intel x86_64', 'x86_64', '/api/download?platform=mac-intel'],
    ['Apple silicon arm', 'arm', '/api/download?platform=mac-arm'],
    ['Apple silicon arm64', 'arm64', '/api/download?platform=mac-arm'],
    ['unknown architecture', 'unknown', packageList],
  ];

  for (const [name, architecture, expected] of routes) {
    await t.test(name, async () => {
      const page = loadPage({ getHighEntropyValues: () => Promise.resolve({ architecture }) });
      await page.flush();
      assert.equal(page.hero.href, expected);
    });
  }
});

test('macOS download keeps the on-site package list when architecture detection fails', async () => {
  const page = loadPage({ getHighEntropyValues: () => Promise.reject(new Error('unavailable')) });
  await page.flush();
  assert.equal(page.hero.href, packageList);
});

test('detected devices link directly to their stable and nightly packages', async (t) => {
  const routes = [
    ['Android phone', { userAgent: 'Mozilla/5.0 (Linux; Android 14; Pixel 9)' }, 'android'],
    ['Android TV', { userAgent: 'Mozilla/5.0 (Linux; Android 12; AFTMM)' }, 'tv'],
    ['Wear OS', { userAgent: 'Mozilla/5.0 (Linux; Android 13; Wear OS)' }, 'wear'],
    ['Windows', { userAgentData: { platform: 'Windows' } }, 'windows'],
    ['Linux', { platform: 'Linux x86_64' }, 'linux'],
    ['iPhone', { userAgent: 'Mozilla/5.0 (iPhone; CPU iPhone OS 18_0 like Mac OS X)' }, 'ios'],
  ];

  for (const [name, device, platform] of routes) {
    await t.test(name, async () => {
      const page = loadPage(device.userAgentData, device);
      await page.flush();
      assert.equal(page.hero.href, `/api/download?platform=${platform}`);
      if (platform === 'ios') assert.equal(page.nightly.href, packageList);
      else assert.equal(page.nightly.href, `/api/download?channel=nightly&platform=${platform}`);
      assert.ok(!page.note.textContent.includes('github.com'));
    });
  }
});

test('unknown browsers stay on the local package list', async () => {
  const page = loadPage(undefined, { userAgent: 'Unknown Browser', platform: 'Unknown' });
  await page.flush();
  assert.equal(page.hero.href, packageList);
  assert.equal(page.nightly.href, packageList);
  assert.equal(page.label.textContent, 'Choose a download');
});
