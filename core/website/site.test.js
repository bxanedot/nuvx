const assert = require('node:assert/strict');
const { readFileSync } = require('node:fs');
const { runInNewContext } = require('node:vm');
const { test } = require('node:test');

const source = readFileSync(`${__dirname}/site.js`, 'utf8');
const packageList = '#releases';
const openSource = readFileSync(`${__dirname}/open.js`, 'utf8');
const openPage = readFileSync(`${__dirname}/open.html`, 'utf8');

test('nuvx open page tries the app and offers the web fallback', () => {
  const status = { textContent: '' };
  let destination = '';
  let showFallback;
  const context = {
    document: { hidden: false, querySelector: () => status, addEventListener() {} },
    window: {
      location: { assign: (url) => { destination = url; } },
      addEventListener() {},
      setTimeout: (callback) => { showFallback = callback; },
    },
  };

  runInNewContext(openSource, context);
  assert.equal(destination, 'nuvx://open-player');
  assert.match(openPage, /href="https:\/\/nuvx\.lol\/"/);
  showFallback();
  assert.match(status.textContent, /continue on nuvx\.lol/);
});

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

test('macOS links straight to the one universal package', async () => {
  const page = loadPage({ getHighEntropyValues: () => Promise.reject(new Error('unavailable')) });
  await page.flush();
  assert.equal(page.hero.href, '/api/download?platform=mac');
  assert.equal(page.nightly.href, '/api/download?channel=nightly&platform=mac');
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

test('search discovery files keep the IndexNow key and canonical sitemap connected', () => {
  const indexNowKey = '045adc84f9a240278c33ee11e35eff56';
  const key = readFileSync(`${__dirname}/${indexNowKey}.txt`, 'utf8');
  const robots = readFileSync(`${__dirname}/robots.txt`, 'utf8');
  const sitemap = readFileSync(`${__dirname}/sitemap.xml`, 'utf8');
  const urls = [...sitemap.matchAll(/<loc>(.*?)<\/loc>/g)].map(([, url]) => url);
  assert.equal(key, indexNowKey);
  assert.match(robots, /Sitemap: https:\/\/www\.nuvx\.lol\/sitemap\.xml/);
  assert.deepEqual(urls, [
    'https://www.nuvx.lol/',
    'https://www.nuvx.lol/docs',
    'https://www.nuvx.lol/changelog',
    'https://www.nuvx.lol/terms',
    'https://www.nuvx.lol/privacy',
  ]);
});
