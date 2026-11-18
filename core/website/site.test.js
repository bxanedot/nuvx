const assert = require('node:assert/strict');
const { readFileSync } = require('node:fs');
const { runInNewContext } = require('node:vm');
const { test } = require('node:test');

const source = readFileSync(`${__dirname}/site.js`, 'utf8');
const packageList = '#ecosystem';
const openSource = readFileSync(`${__dirname}/open.js`, 'utf8');
const openPage = readFileSync(`${__dirname}/open.html`, 'utf8');
const themeInitSource = readFileSync(`${__dirname}/theme-init.js`, 'utf8');

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
      addEventListener() {},
    },
    navigator: {
      userAgent: device.userAgent ?? 'Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7)',
      platform: device.platform ?? 'MacIntel',
      maxTouchPoints: device.maxTouchPoints ?? 0,
      ...(userAgentData === undefined ? {} : { userAgentData }),
    },
    matchMedia: () => ({ matches: true, addEventListener() {} }),
    fetch: () => Promise.resolve({ ok: false }),
    window: { addEventListener() {}, scrollY: 0 },
  };
  runInNewContext(source, context);
  return { hero, note, label: elements['#download-label'], flush: () => new Promise(setImmediate) };
}

test('macOS links straight to the one universal package', async () => {
  const page = loadPage({ getHighEntropyValues: () => Promise.reject(new Error('unavailable')) });
  await page.flush();
  assert.equal(page.hero.href, '/api/download?platform=mac');
});

test('detected devices link directly to their stable packages', async (t) => {
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
      assert.ok(!page.note.textContent.includes('github.com'));
    });
  }
});

test('unknown browsers stay on the local package list', async () => {
  const page = loadPage(undefined, { userAgent: 'Unknown Browser', platform: 'Unknown' });
  await page.flush();
  assert.equal(page.hero.href, packageList);
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

test('all marketing pages load the shared ambient layer', () => {
  for (const page of ['index.html', 'docs.html', 'changelog.html', 'privacy.html', 'terms.html', 'open.html']) {
    const html = readFileSync(`${__dirname}/${page}`, 'utf8');
    assert.match(html, /href="\/ambient\.css"/, `${page} loads ambient.css`);
    assert.match(html, /src="\/site\.js"/, `${page} loads site.js`);
    assert.match(html, /class="nuvx-ambient"/, `${page} has the ambient layer`);
    assert.match(html, /poster="\/mesh-gradient-poster\.webp"/, `${page} has a static video poster`);
    assert.match(html, /<source src="\/mesh-gradient-web\.webm" type="video\/webm">/, `${page} prefers WebM`);
    assert.match(html, /<source src="\/mesh-gradient-web\.mp4" type="video\/mp4">/, `${page} supports MP4`);
  }
});

function loadBackgroundPage({ paused = false, reducedMotion = false, monochrome = false } = {}) {
  const attributes = new Set();
  const root = {
    dataset: { theme: 'dark' },
    hasAttribute: (name) => attributes.has(name),
    toggleAttribute: (name, force) => force ? attributes.add(name) : attributes.delete(name),
  };
  const handlers = {};
  const buttonAttributes = {};
  const button = {
    addEventListener: (name, callback) => { handlers[name] = callback; },
    setAttribute: (name, value) => { buttonAttributes[name] = value; },
  };
  const video = { pauses: 0, plays: 0, pause() { this.pauses++; }, play() { this.plays++; return Promise.resolve(); } };
  const elements = {
    '#background-monochrome-toggle': button,
    '#ambient-video': video,
  };
  const storage = new Map([
    ...(paused ? [['nuvx-motion', 'paused']] : []),
    ...(monochrome ? [['nuvx-background-monochrome', '1']] : []),
  ]);
  const context = {
    document: {
      documentElement: root,
      querySelector: (selector) => elements[selector] ?? null,
      querySelectorAll: () => [],
      addEventListener() {},
    },
    localStorage: {
      getItem: (key) => storage.get(key) ?? null,
      setItem: (key, value) => storage.set(key, value),
    },
    matchMedia: () => ({ matches: reducedMotion, addEventListener() {} }),
    navigator: { userAgent: 'Unknown Browser', platform: 'Unknown', maxTouchPoints: 0 },
    fetch: () => Promise.resolve({ ok: false }),
    window: { addEventListener() {}, scrollY: 0 },
  };
  runInNewContext(themeInitSource, context);
  runInNewContext(source, context);
  return { attributes, buttonAttributes, handlers, root, storage, video };
}

test('monochrome background toggle persists and exposes its state', () => {
  const page = loadBackgroundPage();
  page.handlers.click();
  assert.ok(page.attributes.has('data-background-monochrome'));
  assert.equal(page.buttonAttributes['aria-pressed'], 'true');
  assert.equal(page.storage.get('nuvx-background-monochrome'), '1');
  const restored = loadBackgroundPage({ monochrome: true });
  assert.ok(restored.attributes.has('data-background-monochrome'));
  assert.equal(restored.buttonAttributes['aria-pressed'], 'true');
});

test('ambient video ignores the showcase pause but respects reduced motion', async (t) => {
  await t.test('saved pause setting', () => {
    const page = loadBackgroundPage({ paused: true });
    assert.ok(page.video.plays > 0);
    assert.equal(page.video.pauses, 0);
    assert.ok(page.attributes.has('data-motion-paused'));
  });
  await t.test('reduced motion', () => {
    const page = loadBackgroundPage({ reducedMotion: true });
    assert.ok(page.video.pauses > 0);
    assert.ok(page.attributes.has('data-motion-paused'));
  });
});
