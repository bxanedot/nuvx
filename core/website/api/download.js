const releases = 'https://github.com/bxanedot/nuvx/releases';
const files = {
  windows: 'nuvx-setup-{v}.exe',
  linux: 'nuvx-{v}-linux-x86_64.tar.gz',
  'mac-arm': 'nuvx-setup-{v}-macos-aarch64.dmg',
  'mac-intel': 'nuvx-setup-{v}-macos-x86_64.dmg',
  android: 'nuvx-v{v}.apk',
  tv: 'NuvxTV-v{v}.apk',
  wear: 'NuvxWear-v{v}.apk',
  chrome: 'nuvx-extension-chrome-{v}.zip',
  firefox: 'nuvx-extension-firefox-{v}.zip',
};
const androidAbis = new Set(['arm64-v8a', 'armeabi-v7a', 'x86_64']);

function androidAbi(params, req) {
  const requested = params.get('abi');
  if (androidAbis.has(requested)) return requested;
  const agent = req.headers?.['user-agent'] ?? '';
  if (/\b(?:x86_64|amd64)\b/i.test(agent)) return 'x86_64';
  if (/\b(?:armeabi-v7a|armv7l|armv8l)\b/i.test(agent)) return 'armeabi-v7a';
  return 'arm64-v8a';
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
  const platform = params.get('platform');
  let target = releases + '/latest';
  if (platform === 'ios') {
    try {
      const response = await fetch('https://api.github.com/repos/bxanedot/nuvx/releases/latest', {
        signal: AbortSignal.timeout(4000),
      });
      if (response.ok) {
        const release = await response.json();
        const version = /^v(\d+\.\d+\.\d+)$/.exec(release.tag_name)?.[1];
        if (version) {
          const pattern = new RegExp(`^Nuvx-${version.replaceAll('.', '\\.')}-build(\\d+)-ios-unsigned\\.ipa$`);
          const candidates = (release.assets ?? [])
            .flatMap(({ name }) => {
              const match = typeof name === 'string' && pattern.exec(name);
              return match ? [{ name, build: Number(match[1]) }] : [];
            })
            .sort((a, b) => b.build - a.build);
          if (candidates[0]) target = `${releases}/download/v${version}/${candidates[0].name}`;
        }
      }
    } catch {
    }
  }
  if (Object.hasOwn(files, platform)) {
    try {
      const latest = await fetch(target, { method: 'HEAD', redirect: 'manual', signal: AbortSignal.timeout(4000) });
      const tag = new URL(latest.headers.get('location'), target);
      const version = /^\/bxanedot\/nuvx\/releases\/tag\/v(\d+\.\d+\.\d+)$/.exec(tag.pathname)?.[1];
      if (latest.status === 302 && tag.origin === 'https://github.com' && version) {
        const candidates = platform === 'android'
          ? [`nuvx-v${version}_${androidAbi(params, req)}.apk`, files.android.replace('{v}', version)]
          : [files[platform].replace('{v}', version)];
        for (const candidate of candidates) {
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
