const assert = require('node:assert/strict');
const { test } = require('node:test');
const download = require('./download');

const stableV090Assets = new Set([
  'nuvx-extension-chrome-0.9.0.zip',
  'nuvx-extension-firefox-0.9.0.zip',
  'nuvx-setup-0.9.0-linux-universal.tar.gz',
  'nuvx-setup-0.9.0-macos-apple-silicon.dmg',
  'nuvx-setup-0.9.0-macos-intel.dmg',
  'nuvx-setup-0.9.0.exe',
  'nuvx-v0.9.0-foss.apk',
  'nuvx-v0.9.0-gms.apk',
  'NuvxTV-v0.9.0-foss.apk',
  'NuvxTV-v0.9.0-gms.apk',
  'NuvxWear-v0.9.0.apk',
]);
const downloadsPage = 'https://www.nuvx.lol/#releases';

async function request(platform, assets, version = '1.0.0') {
  const originalFetch = global.fetch;
  let result;
  global.fetch = async (url) => {
    if (url === 'https://github.com/bxanedot/nuvx/releases/latest') {
      return {
        status: 302,
        headers: { get: () => `https://github.com/bxanedot/nuvx/releases/tag/v${version}` },
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
    ['mac-intel', 'nuvx-setup-1.0.0-macos-intel.dmg'],
    ['mac-arm', 'nuvx-setup-1.0.0-macos-apple-silicon.dmg'],
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

test('v0.9.0 routes match the published assets and defer upcoming packages', async (t) => {
  const routes = [
    ['windows', 'nuvx-setup-0.9.0.exe'],
    ['android', 'nuvx-v0.9.0-gms.apk'],
    ['android-foss', 'nuvx-v0.9.0-foss.apk'],
    ['tv', 'NuvxTV-v0.9.0-gms.apk'],
    ['tv-foss', 'NuvxTV-v0.9.0-foss.apk'],
    ['wear', 'NuvxWear-v0.9.0.apk'],
    ['linux', 'nuvx-setup-0.9.0-linux-universal.tar.gz'],
    ['mac-arm', 'nuvx-setup-0.9.0-macos-apple-silicon.dmg'],
    ['mac-intel', 'nuvx-setup-0.9.0-macos-intel.dmg'],
    ['chrome', 'nuvx-extension-chrome-0.9.0.zip'],
    ['firefox', 'nuvx-extension-firefox-0.9.0.zip'],
    ['auto', null],
    ['ios', null],
  ];

  for (const [platform, asset] of routes) {
    await t.test(platform, async () => {
      const result = await request(platform, stableV090Assets, '0.9.0');
      assert.equal(result.status, 302);
      if (asset) assert.ok(result.headers.Location.endsWith(`/${asset}`));
      else assert.equal(result.headers.Location, downloadsPage);
    });
  }
});

async function requestNightly(platform, assets) {
  const originalFetch = global.fetch;
  let result;
  global.fetch = async () => ({
    ok: true,
    json: async () => [{
      tag_name: 'nightly.170.1',
      prerelease: true,
      draft: false,
      assets: assets.map((name) => ({
        name,
        browser_download_url: `https://github.com/bxanedot/nuvx/releases/download/nightly.170.1/${name}`,
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
    ['mac-arm', 'nuvx-setup-nightly.170.1-macos-apple-silicon.dmg'],
    ['mac-intel', 'nuvx-setup-nightly.170.1-macos-intel.dmg'],
    ['android', 'nuvx-v0.9.0-nightly.170.1-universal.apk'],
    ['tv', 'NuvxTV-v0.9.0-nightly.170.1-universal.apk'],
    ['auto', 'NuvxAuto-v0.9.0-nightly.170.1-universal.apk'],
    ['wear', 'NuvxWear-v0.9.0-nightly.170.1.apk'],
    ['chrome', 'nuvx-extension-chrome-nightly.170.1.zip'],
    ['firefox', 'nuvx-extension-firefox-nightly.170.1.zip'],
  ];

  for (const [platform, asset] of routes) {
    await t.test(platform, async () => {
      const result = await requestNightly(platform, [asset]);
      assert.equal(result.status, 302);
      assert.equal(result.headers.Location, `https://github.com/bxanedot/nuvx/releases/download/nightly.170.1/${asset}`);
    });
  }
});

test('unavailable nightly packages return to the on-site package list', async () => {
  const result = await requestNightly('ios', ['nuvx-v0.9.0-nightly.170.1-universal.apk']);
  assert.equal(result.headers.Location, downloadsPage);
});

test('downloads fall back to legacy assets when universal naming is unavailable', async (t) => {
  const routes = [
    ['android', 'nuvx-v0.7.0-gms.apk'],
    ['tv', 'NuvxTV-v0.7.0.apk'],
    ['wear', 'NuvxWear-v0.7.0.apk'],
    ['linux', 'nuvx-0.7.0-linux-x86_64.tar.gz'],
    ['mac-arm', 'nuvx-setup-0.7.0-macos-aarch64.dmg'],
    ['mac-intel', 'nuvx-setup-0.7.0-macos-x86_64.dmg'],
  ];

  for (const [platform, asset] of routes) {
    await t.test(platform, async () => {
      const result = await request(platform, new Set([asset]), '0.7.0');
      assert.equal(result.status, 302);
      assert.ok(result.headers.Location.endsWith(`/${asset}`));
    });
  }
});

test('FOSS compatibility routes keep legacy releases on the matching flavor', async (t) => {
  const routes = [
    ['android-foss', 'nuvx-v0.9.0-foss.apk', 'nuvx-v0.9.0-gms.apk'],
    ['tv-foss', 'NuvxTV-v0.9.0-foss.apk', 'NuvxTV-v0.9.0-gms.apk'],
  ];

  for (const [platform, fossAsset, gmsAsset] of routes) {
    await t.test(platform, async () => {
      const result = await request(platform, new Set([fossAsset, gmsAsset]), '0.9.0');
      assert.equal(result.status, 302);
      assert.ok(result.headers.Location.endsWith(`/${fossAsset}`));
    });
  }
});
