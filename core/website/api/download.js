const releases = 'https://github.com/bxanedot/nuvx/releases';
const files = {
  windows: 'nuvx-setup-{v}.exe',
  linux: 'nuvx-setup-{v}-linux-universal.tar.gz',
  'mac-arm': 'nuvx-setup-{v}-macos-apple-silicon.dmg',
  'mac-intel': 'nuvx-setup-{v}-macos-intel.dmg',
  android: 'nuvx-v{v}-universal.apk',
  tv: 'NuvxTV-v{v}-universal.apk',
  car: 'NuvxCar-v{v}-universal.apk',
  wear: 'NuvxWear-v{v}-universal.apk',
  ios: 'nuvx-v{v}-universal.ipa',
  chrome: 'nuvx-extension-chrome-{v}.zip',
  firefox: 'nuvx-extension-firefox-{v}.zip',
};

function candidatesFor(platform, version, requestedPlatform) {
  const primary = files[platform].replace('{v}', version);
  // shortcut: old releases still resolve to one GMS/FOSS APK; remove this fallback once universal assets ship.
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
    let tag;
    try {
      const response = await fetch('https://api.github.com/repos/bxanedot/nuvx/releases?per_page=30', {
        signal: AbortSignal.timeout(4000),
      });
      if (response.ok) {
        const builds = await response.json();
        tag = builds
          .filter((build) => build.prerelease && !build.draft && /^nightly\.\d+\.\d+$/.test(build.tag_name))
          .map((build) => build.tag_name)
          .sort((a, b) => b.localeCompare(a, 'en', { numeric: true }))[0];
      }
    } catch {
    }
    res.writeHead(302, {
      Location: tag ? releases + '/tag/' + tag : releases,
      'Cache-Control': 'no-store',
      ...(tag && { 'Vercel-CDN-Cache-Control': 's-maxage=60' }),
    });
    res.end();
    return;
  }
  const requestedPlatform = params.get('platform');
  const platform = requestedPlatform === 'android-foss' ? 'android'
    : requestedPlatform === 'tv-foss' ? 'tv' : requestedPlatform;
  let target = releases + '/latest';
  if (Object.hasOwn(files, platform)) {
    try {
      const latest = await fetch(target, { method: 'HEAD', redirect: 'manual', signal: AbortSignal.timeout(4000) });
      const tag = new URL(latest.headers.get('location'), target);
      const version = /^\/bxanedot\/nuvx\/releases\/tag\/v(\d+\.\d+\.\d+)$/.exec(tag.pathname)?.[1];
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
