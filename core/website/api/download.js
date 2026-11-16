const releases = 'https://github.com/lylicht/nuvx/releases';
const downloadsPage = 'https://www.nuvx.lol/#releases';
const files = {
  windows: 'nuvx-setup-{v}.exe',
  linux: 'nuvx-setup-{v}-linux-universal.tar.gz',
  'mac-arm': 'nuvx-setup-{v}-macos-apple-silicon.dmg',
  'mac-intel': 'nuvx-setup-{v}-macos-intel.dmg',
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
  'mac-arm': ['nuvx-setup-', '-macos-apple-silicon.dmg'],
  'mac-intel': ['nuvx-setup-', '-macos-intel.dmg'],
  android: ['nuvx-v', '-universal.apk'],
  tv: ['NuvxTV-v', '-universal.apk'],
  auto: ['NuvxAuto-v', '-universal.apk'],
  wear: ['NuvxWear-v', '.apk'],
  chrome: ['nuvx-extension-chrome-', '.zip'],
  firefox: ['nuvx-extension-firefox-', '.zip'],
};

function candidatesFor(platform, version, requestedPlatform) {
  const primary = files[platform].replace('{v}', version);
  // Keep legacy filenames available for old tagged releases.
  if (platform === 'android') return [primary, ...(requestedPlatform === 'android-foss'
    ? [`nuvx-v${version}-foss.apk`, `nuvx-v${version}.apk`, `nuvx-v${version}-gms.apk`]
    : [`nuvx-v${version}.apk`, `nuvx-v${version}-gms.apk`, `nuvx-v${version}-foss.apk`])];
  if (platform === 'tv') return [primary, ...(requestedPlatform === 'tv-foss'
    ? [`NuvxTV-v${version}-foss.apk`, `NuvxTV-v${version}.apk`, `NuvxTV-v${version}-gms.apk`]
    : [`NuvxTV-v${version}.apk`, `NuvxTV-v${version}-gms.apk`, `NuvxTV-v${version}-foss.apk`])];
  if (platform === 'wear') return [primary, `NuvxWear-v${version}.apk`];
  if (platform === 'linux') return [primary, `nuvx-${version}-linux-x86_64.tar.gz`];
  if (platform === 'mac-arm') return [primary, `nuvx-setup-${version}-macos-aarch64.dmg`];
  if (platform === 'mac-intel') return [primary, `nuvx-setup-${version}-macos-x86_64.dmg`];
  return [primary];
}

module.exports = async (req, res) => {
  const params = new URL(req.url, 'https://www.nuvx.lol').searchParams;
  if (params.get('channel') === 'nightly') {
    let target = downloadsPage;
    const requestedPlatform = params.get('platform');
    const platform = requestedPlatform === 'android-foss' || requestedPlatform === 'android-auto' ? 'android'
      : requestedPlatform === 'tv-foss' ? 'tv'
        : requestedPlatform === 'car' ? 'auto' : requestedPlatform;
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
      : requestedPlatform === 'car' ? 'auto' : requestedPlatform;
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
  }
  res.writeHead(302, { Location: target, 'Cache-Control': 'no-store' });
  res.end();
};
