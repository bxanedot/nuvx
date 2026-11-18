const releases = 'https://github.com/lylicht/nuvx/releases';
const downloadsPage = 'https://www.nuvx.lol/#releases';
// shortcut: iOS has no newer IPA asset yet; update this fallback when one ships.
const fallbackAssets = {
  ios: `${releases}/download/v1.0.0/nuvx-1.0.0-universal.ipa`,
};
const files = {
  windows: 'nuvx-setup-{v}.exe',
  linux: 'nuvx-setup-{v}-linux-universal.tar.gz',
  mac: 'nuvx-setup-{v}-macos-universal.dmg',
  android: 'nuvx-v{v}.apk',
  tv: 'NuvxTV-v{v}.apk',
  auto: 'NuvxAuto-v{v}.apk',
  wear: 'NuvxWear-v{v}.apk',
  ios: 'nuvx-{v}-universal.ipa',
  chrome: 'nuvx-extension-chrome-{v}.zip',
  firefox: 'nuvx-extension-firefox-{v}.zip',
};

const nightlyNames = {
  windows: ['nuvx-setup-', '.exe'],
  linux: ['nuvx-setup-', '-linux-universal.tar.gz'],
  mac: ['nuvx-setup-', '-macos-universal.dmg'],
  android: ['nuvx-v', '.apk'],
  tv: ['NuvxTV-v', '.apk'],
  auto: ['NuvxAuto-v', '.apk'],
  wear: ['NuvxWear-v', '.apk'],
  chrome: ['nuvx-extension-chrome-', '.zip'],
  firefox: ['nuvx-extension-firefox-', '.zip'],
};

function candidatesFor(platform, version, requestedPlatform) {
  const primary = files[platform].replace('{v}', version);
  // Keep legacy filenames available for old tagged releases.
  if (platform === 'android') return requestedPlatform === 'android-foss'
    ? [`nuvx-v${version}-foss.apk`, primary, `nuvx-v${version}-universal.apk`, `nuvx-v${version}-gms.apk`]
    : [primary, `nuvx-v${version}-universal.apk`, `nuvx-v${version}-gms.apk`, `nuvx-v${version}-foss.apk`];
  if (platform === 'tv') return requestedPlatform === 'tv-foss'
    ? [`NuvxTV-v${version}-foss.apk`, primary, `NuvxTV-v${version}-universal.apk`, `NuvxTV-v${version}-gms.apk`]
    : [primary, `NuvxTV-v${version}-universal.apk`, `NuvxTV-v${version}-gms.apk`, `NuvxTV-v${version}-foss.apk`];
  if (platform === 'auto') return [primary, `NuvxAuto-v${version}-universal.apk`];
  if (platform === 'wear') return [primary, `NuvxWear-v${version}.apk`];
  if (platform === 'linux') return [primary, `nuvx-${version}-linux-x86_64.tar.gz`];
  // Older releases shipped one disk image per architecture.
  if (requestedPlatform === 'mac-intel') return [primary, `nuvx-setup-${version}-macos-intel.dmg`, `nuvx-setup-${version}-macos-x86_64.dmg`];
  if (platform === 'mac') return [primary, `nuvx-setup-${version}-macos-apple-silicon.dmg`, `nuvx-setup-${version}-macos-aarch64.dmg`];
  return [primary];
}

module.exports = async (req, res) => {
  const params = new URL(req.url, 'https://www.nuvx.lol').searchParams;
  if (params.get('channel') === 'nightly') {
    let target = downloadsPage;
    const requestedPlatform = params.get('platform');
    const platform = requestedPlatform === 'android-foss' || requestedPlatform === 'android-auto' ? 'android'
      : requestedPlatform === 'tv-foss' ? 'tv'
        : requestedPlatform === 'car' ? 'auto'
          : requestedPlatform === 'mac-arm' || requestedPlatform === 'mac-intel' ? 'mac' : requestedPlatform;
    const match = nightlyNames[platform];
    try {
      const response = await fetch('https://api.github.com/repos/lylicht/nuvx/releases?per_page=30', {
        signal: AbortSignal.timeout(4000),
      });
      if (response.ok) {
        const builds = await response.json();
        const latest = builds
          .filter((build) => build.prerelease && !build.draft && /^nightly\.\d+\.\d+$/.test(build.tag_name))
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
  const requestedPlatform = params.get('platform');
  const platform = requestedPlatform === 'android-foss' || requestedPlatform === 'android-auto' ? 'android'
    : requestedPlatform === 'tv-foss' ? 'tv'
      : requestedPlatform === 'car' ? 'auto'
        : requestedPlatform === 'mac-arm' || requestedPlatform === 'mac-intel' ? 'mac' : requestedPlatform;
  let target = downloadsPage;
  if (Object.hasOwn(files, platform)) {
    try {
      const latest = await fetch(releases + '/latest', { method: 'HEAD', redirect: 'manual', signal: AbortSignal.timeout(4000) });
      const tag = new URL(latest.headers.get('location'), releases + '/latest');
      const version = /^\/lylicht\/nuvx\/releases\/tag\/v(\d+\.\d+\.\d+)$/.exec(tag.pathname)?.[1];
      if (latest.status === 302 && tag.origin === 'https://github.com' && version) {
        for (const candidate of candidatesFor(platform, version, requestedPlatform)) {
          const asset = releases + '/download/v' + version + '/' + candidate;
          const response = await fetch(asset, { method: 'HEAD', redirect: 'manual', signal: AbortSignal.timeout(4000) });
          if (response.ok || response.status === 302) {
            target = asset;
            break;
          }
        }
      }
    } catch {
    }
    if (target === downloadsPage && fallbackAssets[platform]) target = fallbackAssets[platform];
  }
  res.writeHead(302, { Location: target, 'Cache-Control': 'no-store' });
  res.end();
};
