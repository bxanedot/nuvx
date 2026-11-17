const releases = 'https://github.com/lylicht/nuvx/releases';
const downloadsPage = 'https://www.nuvx.lol/#releases';
const files = {
  windows: 'nuvx-setup-{v}.exe',
  linux: 'nuvx-setup-{v}-linux-universal.tar.gz',
  mac: 'nuvx-setup-{v}-macos-universal.dmg',
  android: 'nuvx-v{v}-universal.apk',
  tv: 'NuvxTV-v{v}-universal.apk',
  auto: 'NuvxAuto-v{v}-universal.apk',
  wear: 'NuvxWear-v{v}.apk',
  ios: 'nuvx-{v}-universal.ipa',
  chrome: 'nuvx-extension-chrome-{v}.zip',
  firefox: 'nuvx-extension-firefox-{v}.zip',
};

const nightlyNames = {
  windows: ['nuvx-setup-', '.exe'],
  linux: ['nuvx-setup-', '-linux-universal.tar.gz'],
  mac: ['nuvx-setup-', '-macos-universal.dmg'],
  android: ['nuvx-v', '-universal.apk'],
  tv: ['NuvxTV-v', '-universal.apk'],
  auto: ['NuvxAuto-v', '-universal.apk'],
  wear: ['NuvxWear-v', '.apk'],
  chrome: ['nuvx-extension-chrome-', '.zip'],
  firefox: ['nuvx-extension-firefox-', '.zip'],
};

// Old links keep working: the flavor and per-architecture names all point at the one universal package.
function platformOf(requested) {
  if (requested === 'android-foss' || requested === 'android-auto') return 'android';
  if (requested === 'tv-foss') return 'tv';
  if (requested === 'car') return 'auto';
  if (requested === 'mac-arm' || requested === 'mac-intel') return 'mac';
  return requested;
}

module.exports = async (req, res) => {
  const params = new URL(req.url, 'https://www.nuvx.lol').searchParams;
  if (params.get('channel') === 'nightly') {
    let target = downloadsPage;
    const platform = platformOf(params.get('platform'));
    const match = nightlyNames[platform];
    try {
      const response = await fetch('https://api.github.com/repos/lylicht/nuvx/releases?per_page=30', {
        signal: AbortSignal.timeout(4000),
      });
      if (response.ok) {
        const builds = await response.json();
        const latest = builds
          .filter((build) => build.prerelease && !build.draft && /^nightly\.\d+\.\d+$/.test(build.tag_name) && !/\bold\b/i.test(build.name ?? ''))
          .sort((a, b) => b.tag_name.localeCompare(a.tag_name, 'en', { numeric: true }))[0];
        const asset = latest && match && latest.assets?.find(({ name }) =>
          name.startsWith(match[0]) && name.includes(latest.tag_name) && name.endsWith(match[1]));
        const assetUrl = asset?.browser_download_url;
        if (assetUrl && new URL(assetUrl).origin === 'https://github.com') target = assetUrl;
      }
    } catch {
    }
    res.writeHead(302, {
      Location: target,
      'Cache-Control': 'no-store',
    });
    res.end();
    return;
  }
  const platform = platformOf(params.get('platform'));
  let target = downloadsPage;
  if (Object.hasOwn(files, platform)) {
    try {
      const latest = await fetch(releases + '/latest', { method: 'HEAD', redirect: 'manual', signal: AbortSignal.timeout(4000) });
      const tag = new URL(latest.headers.get('location'), releases + '/latest');
      const version = /^\/lylicht\/nuvx\/releases\/tag\/v(\d+\.\d+\.\d+)$/.exec(tag.pathname)?.[1];
      if (latest.status === 302 && tag.origin === 'https://github.com' && version) {
        const asset = releases + '/download/v' + version + '/' + files[platform].replace('{v}', version);
        const response = await fetch(asset, { method: 'HEAD', redirect: 'manual', signal: AbortSignal.timeout(4000) });
        if (response.ok || response.status === 302) target = asset;
      }
    } catch {
    }
  }
  res.writeHead(302, { Location: target, 'Cache-Control': 'no-store' });
  res.end();
};
