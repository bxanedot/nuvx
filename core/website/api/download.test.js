const assert = require('node:assert/strict');
const { test } = require('node:test');
const download = require('./download');

const downloadsPage = 'https://www.nuvx.lol/#releases';

async function request(platform, assets, version = '1.0.0') {
  const originalFetch = global.fetch;
  let result;
  global.fetch = async (url) => {
    if (url === 'https://github.com/lylicht/nuvx/releases/latest') {
      return {
        status: 302,
        headers: { get: () => `https://github.com/lylicht/nuvx/releases/tag/v${version}` },
      };
    }
    const name = new URL(url).pathname.split('/').at(-1);
    return { ok: assets.has(name), status: assets.has(name) ? 302 : 404 };
  };
  try {
    await download(
      { url: `https://www.nuvx.lol/api/download?platform=${platform}`, headers: {} },
      { writeHead: (status, headers) => { result = { status, headers }; }, end() {} },
    );
  } finally {
    global.fetch = originalFetch;
  }
  return result;
}

test('release downloads select universal platform packages', async (t) => {
  const routes = [
    ['windows', 'nuvx-setup-1.0.0.exe'],
    ['android', 'nuvx-v1.0.0-universal.apk'],
    ['android-auto', 'nuvx-v1.0.0-universal.apk'],
    ['tv', 'NuvxTV-v1.0.0-universal.apk'],
    ['auto', 'NuvxAuto-v1.0.0-universal.apk'],
    ['car', 'NuvxAuto-v1.0.0-universal.apk'],
    ['wear', 'NuvxWear-v1.0.0.apk'],
    ['ios', 'nuvx-1.0.0-universal.ipa'],
    ['mac', 'nuvx-setup-1.0.0-macos-universal.dmg'],
    ['mac-intel', 'nuvx-setup-1.0.0-macos-universal.dmg'],
    ['mac-arm', 'nuvx-setup-1.0.0-macos-universal.dmg'],
    ['android-foss', 'nuvx-v1.0.0-universal.apk'],
    ['tv-foss', 'NuvxTV-v1.0.0-universal.apk'],
    ['linux', 'nuvx-setup-1.0.0-linux-universal.tar.gz'],
    ['chrome', 'nuvx-extension-chrome-1.0.0.zip'],
    ['firefox', 'nuvx-extension-firefox-1.0.0.zip'],
  ];

  for (const [platform, asset] of routes) {
    await t.test(platform, async () => {
      const result = await request(platform, new Set([asset]));
      assert.equal(result.status, 302);
      assert.ok(result.headers.Location.endsWith(`/${asset}`));
    });
  }
});

async function requestNightly(platform, assets, name = 'nuvx 1.0.0-nightly.170.1') {
  const originalFetch = global.fetch;
  let result;
  global.fetch = async () => ({
    ok: true,
    json: async () => [{
      tag_name: 'nightly.170.1',
      name,
      prerelease: true,
      draft: false,
      assets: assets.map((name) => ({
        name,
        browser_download_url: `https://github.com/lylicht/nuvx/releases/download/nightly.170.1/${name}`,
      })),
    }],
  });
  try {
    await download(
      { url: `https://www.nuvx.lol/api/download?channel=nightly&platform=${platform}`, headers: {} },
      { writeHead: (status, headers) => { result = { status, headers }; }, end() {} },
    );
  } finally {
    global.fetch = originalFetch;
  }
  return result;
}

test('nightly downloads select the matching device artifact directly', async (t) => {
  const routes = [
    ['windows', 'nuvx-setup-nightly.170.1.exe'],
    ['linux', 'nuvx-setup-nightly.170.1-linux-universal.tar.gz'],
    ['mac', 'nuvx-setup-nightly.170.1-macos-universal.dmg'],
    ['mac-arm', 'nuvx-setup-nightly.170.1-macos-universal.dmg'],
    ['android', 'nuvx-v1.0.0-nightly.170.1-universal.apk'],
    ['tv', 'NuvxTV-v1.0.0-nightly.170.1-universal.apk'],
    ['auto', 'NuvxAuto-v1.0.0-nightly.170.1-universal.apk'],
    ['wear', 'NuvxWear-v1.0.0-nightly.170.1.apk'],
    ['chrome', 'nuvx-extension-chrome-nightly.170.1.zip'],
    ['firefox', 'nuvx-extension-firefox-nightly.170.1.zip'],
  ];

  for (const [platform, asset] of routes) {
    await t.test(platform, async () => {
      const result = await requestNightly(platform, [asset]);
      assert.equal(result.status, 302);
      assert.equal(result.headers.Location, `https://github.com/lylicht/nuvx/releases/download/nightly.170.1/${asset}`);
    });
  }
});

test('unavailable nightly packages return to the on-site package list', async () => {
  const result = await requestNightly('ios', ['nuvx-v1.0.0-nightly.170.1-universal.apk']);
  assert.equal(result.headers.Location, downloadsPage);
});

test('nightly downloads never pick a build named old', async () => {
  const result = await requestNightly('windows', ['nuvx-setup-nightly.170.1.exe'], 'nuvx 1.1.0-nightly.170.1 (old build)');
  assert.equal(result.headers.Location, downloadsPage);
});

test('an unavailable stable package returns to the on-site package list', async () => {
  const result = await request('auto', new Set(), '1.0.0');
  assert.equal(result.headers.Location, downloadsPage);
});
