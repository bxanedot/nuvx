const assert = require('node:assert/strict');
const { test } = require('node:test');
const download = require('./download');

async function request(query, assets, userAgent = '', platform = 'android') {
  const originalFetch = global.fetch;
  let result;
  global.fetch = async (url) => {
    if (url === 'https://api.github.com/repos/bxanedot/nuvx/releases/latest') {
      return {
        ok: true,
        json: async () => ({
          tag_name: 'v0.8.0',
          assets: [...assets].map((name) => ({ name, browser_download_url: 'https://untrusted.example/ignore' })),
        }),
      };
    }
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
      { url: `/?platform=${platform}${query}`, headers: { 'user-agent': userAgent } },
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

test('iOS resolves the highest matching unsigned IPA build without trusting arbitrary asset URLs', async () => {
  const result = await request('', new Set([
    'Nuvx-0.8.0-build1-ios-unsigned.ipa',
    'Nuvx-0.8.0-build12-ios-unsigned.ipa',
    'Nuvx-0.7.0-build99-ios-unsigned.ipa',
    'not-a-nuvx-app.ipa',
  ]), '', 'ios');
  assert.equal(result.headers.Location, 'https://github.com/bxanedot/nuvx/releases/download/v0.8.0/Nuvx-0.8.0-build12-ios-unsigned.ipa');
  assert.equal(result.headers['Cache-Control'], 'no-store');
});

test('iOS falls back to the release page when no IPA has been published', async () => {
  const result = await request('', new Set(['nuvx-setup-0.8.0-macos-aarch64.dmg']), '', 'ios');
  assert.equal(result.headers.Location, 'https://github.com/bxanedot/nuvx/releases/latest');
});

test('macOS download names match Rust Apple silicon and Intel release packages', async () => {
  for (const [platform, arch] of [['mac-arm', 'aarch64'], ['mac-intel', 'x86_64']]) {
    const name = `nuvx-setup-0.8.0-macos-${arch}.dmg`;
    const result = await request('', new Set([name]), '', platform);
    assert.equal(result.headers.Location, `https://github.com/bxanedot/nuvx/releases/download/v0.8.0/${name}`);
  }
});
