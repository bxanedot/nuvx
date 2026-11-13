const assert = require('node:assert/strict');
const { test } = require('node:test');
const download = require('./download');

async function request(platform, assets, version = '0.8.0', query = '', userAgent = 'Android 15') {
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
      { url: `/?platform=${platform}${query}`, headers: { 'user-agent': userAgent } },
      { writeHead: (status, headers) => { result = { status, headers }; }, end() {} },
    );
  } finally {
    global.fetch = originalFetch;
  }
  return result;
}

test('release downloads select the requested universal platform package', async (t) => {
  const routes = [
    ['windows', 'nuvx-setup-0.8.0.exe'],
    ['android', 'nuvx-v0.8.0-gms.apk'],
    ['android-foss', 'nuvx-v0.8.0-foss.apk'],
    ['tv', 'NuvxTV-v0.8.0-gms.apk'],
    ['tv-foss', 'NuvxTV-v0.8.0-foss.apk'],
    ['wear', 'NuvxWear-v0.8.0.apk'],
    ['mac-intel', 'nuvx-setup-0.8.0-macos-intel.dmg'],
    ['mac-arm', 'nuvx-setup-0.8.0-macos-apple-silicon.dmg'],
    ['linux', 'nuvx-setup-0.8.0-linux-universal.tar.gz'],
    ['chrome', 'nuvx-extension-chrome-0.8.0.zip'],
    ['firefox', 'nuvx-extension-firefox-0.8.0.zip'],
  ];

  for (const [platform, asset] of routes) {
    await t.test(platform, async () => {
      const result = await request(platform, new Set([asset]));
      assert.equal(result.status, 302);
      assert.ok(result.headers.Location.endsWith(`/${asset}`));
    });
  }
});

test('previous stable assets keep resolving until the new release is published', async (t) => {
  const routes = [
    ['android', 'nuvx-v0.7.0.apk'],
    ['tv', 'NuvxTV-v0.7.0.apk'],
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

test('Android keeps selecting legacy ABI APKs and universal fallbacks', async (t) => {
  await t.test('uses the device ABI', async () => {
    const result = await request('android', new Set(['nuvx-v0.7.0_arm64-v8a.apk']), '0.7.0');
    assert.equal(result.status, 302);
    assert.match(result.headers.Location, /nuvx-v0\.7\.0_arm64-v8a\.apk$/);
  });

  await t.test('honors an explicit supported ABI', async () => {
    const result = await request('android', new Set(['nuvx-v0.7.0_armeabi-v7a.apk']), '0.7.0', '&abi=armeabi-v7a', 'x86_64');
    assert.match(result.headers.Location, /nuvx-v0\.7\.0_armeabi-v7a\.apk$/);
  });

  await t.test('falls back to the previous universal APK', async () => {
    const result = await request('android', new Set(['nuvx-v0.7.0.apk']), '0.7.0');
    assert.match(result.headers.Location, /nuvx-v0\.7\.0\.apk$/);
  });
});
