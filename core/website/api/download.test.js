const assert = require('node:assert/strict');
const { test } = require('node:test');
const download = require('./download');

async function request(query, assets, userAgent = '') {
  const originalFetch = global.fetch;
  let result;
  global.fetch = async (url) => {
    if (url === 'https://github.com/bxanedot/nuvx/releases/latest') {
      return {
        status: 302,
        headers: { get: () => 'https://github.com/bxanedot/nuvx/releases/tag/v0.8.0' },
      };
    }
    const name = new URL(url).pathname.split('/').at(-1);
    return { ok: assets.has(name), status: assets.has(name) ? 302 : 404 };
  };
  try {
    await download(
      { url: `/?platform=android${query}`, headers: { 'user-agent': userAgent } },
      { writeHead: (status, headers) => { result = { status, headers }; }, end() {} },
    );
  } finally {
    global.fetch = originalFetch;
  }
  return result;
}

test('Android download chooses the device ABI and falls back to universal APKs', async (t) => {
  await t.test('uses the 64-bit ARM split for a typical Android browser', async () => {
    const result = await request('', new Set(['nuvx-v0.8.0_arm64-v8a.apk']), 'Android 15');
    assert.equal(result.status, 302);
    assert.match(result.headers.Location, /nuvx-v0\.8\.0_arm64-v8a\.apk$/);
  });

  await t.test('honors a supported explicit ABI', async () => {
    const result = await request('&abi=armeabi-v7a', new Set(['nuvx-v0.8.0_armeabi-v7a.apk']), 'x86_64');
    assert.match(result.headers.Location, /nuvx-v0\.8\.0_armeabi-v7a\.apk$/);
  });

  await t.test('falls back to the universal APK when split assets are absent', async () => {
    const result = await request('', new Set(['nuvx-v0.8.0.apk']));
    assert.match(result.headers.Location, /nuvx-v0\.8\.0\.apk$/);
  });
});
