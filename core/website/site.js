(() => {
  const root = document.documentElement;
  const toggle = document.querySelector('#theme-toggle');
  const themeColor = document.querySelector('meta[name="theme-color"]');
  const isDark = () => root.dataset.theme === 'dark';
  const updateTheme = () => {
    if (themeColor) themeColor.content = isDark() ? '#090a10' : '#fafafa';
    if (toggle) toggle.setAttribute('aria-label', `Switch to ${isDark() ? 'light' : 'dark'} mode`);
  };
  toggle?.addEventListener('click', () => {
    root.dataset.theme = isDark() ? 'light' : 'dark';
    try { localStorage.setItem('nuvx-theme', root.dataset.theme); } catch {}
    updateTheme();
  });
  updateTheme();
  const note = document.querySelector('#device-note');
  if (!note) return;
  const ua = navigator.userAgent.toLowerCase();
  const platform = navigator.userAgentData?.platform || navigator.platform || '';
  const device = /wear os|watch/.test(ua) ? 'Wear OS' : /android/.test(ua) && /tv|aft|bravia|shield/.test(ua) ? 'Android TV' : /android/.test(ua) ? 'Android' : /iphone|ipad|ipod/.test(ua) || (/ios/.test(platform.toLowerCase()) || /mac/.test(platform.toLowerCase()) && navigator.maxTouchPoints > 1) ? 'iOS' : /mac/.test(platform.toLowerCase()) ? 'macOS' : /win/.test(platform.toLowerCase()) ? 'Windows' : /linux/.test(platform.toLowerCase()) ? 'Linux' : '';
  const downloadLabel = document.querySelector('#download-label');
  if (downloadLabel && device) downloadLabel.textContent = device === 'iOS' ? 'iOS — coming soon' : `Download for ${device}`;
  const released = device === 'Android' || device === 'Android TV' || device === 'Wear OS';
  if (device && !released && device !== 'iOS' && downloadLabel) {
    downloadLabel.textContent = 'Open the web player';
    if (downloadLabel.nextElementSibling) downloadLabel.nextElementSibling.textContent = '↗';
    document.querySelector('#device-download')?.setAttribute('href', 'https://web.nuvx.lol');
  }
  if (device) note.textContent = released
    ? `nuvx 0.1.0 for ${device} is out. ${device === 'Wear OS' ? 'Install the phone app; it adds nuvx to your watch.' : 'It runs on Android 8.0 or newer.'}`
    : `No ${device} app yet. Use the web player in your browser, or get nuvx for Android.`;
  // A watch can't install the phone APK, so Wear OS keeps the release page.
  if (device === 'Android' || device === 'Android TV') {
    document.querySelector('#device-download')?.setAttribute('data-apk', device === 'Android TV' ? 'NuvxTV-' : 'nuvx-');
  }
  // Point every [data-apk] link at the newest release's universal APK (named "<prefix>vX.Y.Z.apk"); the release page stays the fallback.
  const apkLinks = document.querySelectorAll('[data-apk]');
  if (apkLinks.length) {
    fetch('https://api.github.com/repos/bxanedot/nuvx/releases/latest')
      .then((r) => (r.ok ? r.json() : null))
      .then((release) => {
        for (const link of apkLinks) {
          const asset = release?.assets?.find((a) => a.name.startsWith(link.dataset.apk) && a.name.endsWith('.apk') && !a.name.includes('_'));
          if (asset) link.href = asset.browser_download_url;
        }
      })
      .catch(() => {});
  }
})();
