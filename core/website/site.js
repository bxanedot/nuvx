(() => {
  const root = document.documentElement;
  const toggle = document.querySelector('#theme-toggle');
  const themeColor = document.querySelector('meta[name="theme-color"]');
  const isDark = () => root.dataset.theme === 'dark';
  const updateTheme = () => {
    if (themeColor) themeColor.content = isDark() ? '#000000' : '#fbf8f9';
    if (toggle) toggle.setAttribute('aria-label', `Switch to ${isDark() ? 'light' : 'dark'} mode`);
  };
  toggle?.addEventListener('click', () => {
    root.dataset.theme = isDark() ? 'light' : 'dark';
    try { localStorage.setItem('nuvx-theme', root.dataset.theme); } catch {}
    updateTheme();
  });
  updateTheme();

  const ambient = document.createElement('div');
  ambient.className = 'nuvx-ambient';
  ambient.setAttribute('aria-hidden', 'true');
  document.body.prepend(ambient);
  const motion = matchMedia('(prefers-reduced-motion: reduce)');
  const motionToggle = document.createElement('button');
  motionToggle.type = 'button';
  motionToggle.className = 'theme-toggle motion-toggle';
  toggle?.after(motionToggle);
  let manuallyPaused = root.hasAttribute('data-motion-paused');
  let playing = !motion.matches && !manuallyPaused;
  let syncShowcase;
  const syncMotion = () => {
    root.toggleAttribute('data-motion-paused', !playing);
    const label = playing ? 'Pause motion' : 'Resume motion';
    motionToggle.textContent = playing ? 'Ⅱ' : '▷';
    motionToggle.setAttribute('aria-label', label);
    motionToggle.title = label;
    syncShowcase?.();
  };
  const toggleMotion = () => {
    playing = !playing;
    manuallyPaused = !playing;
    try { localStorage.setItem('nuvx-motion', manuallyPaused ? 'paused' : 'on'); } catch {}
    syncMotion();
  };
  motionToggle.addEventListener('click', toggleMotion);
  motion.addEventListener('change', () => {
    playing = !motion.matches && !manuallyPaused;
    syncMotion();
  });

  const viewport = document.querySelector('#showcase-viewport');
  if (viewport) {
    const banners = viewport.querySelector('.showcase-banners');
    const repeat = banners.cloneNode(true);
    repeat.setAttribute('aria-hidden', 'true');
    const control = document.querySelector('.showcase-toggle');
    let visible = false;
    let frame;
    let lastTime;
    let position;
    let interacting = false;
    let pointer;
    let resumeTimer;

    const advance = (time) => {
      const cycle = banners.getBoundingClientRect().width;
      const distance = Math.min(time - (lastTime ?? time), 50) * 0.034;
      // Decreasing scrollLeft moves the cards right. Both copies meet at the same visual position.
      position = (position + cycle - distance) % cycle;
      viewport.scrollLeft = position;
      lastTime = time;
      frame = requestAnimationFrame(advance);
    };
    const sync = () => {
      cancelAnimationFrame(frame);
      control.textContent = playing ? 'Pause showcase' : 'Play showcase';
      if (!playing || !visible || interacting) return;
      if (!repeat.isConnected) banners.after(repeat);
      position = viewport.scrollLeft;
      lastTime = undefined;
      frame = requestAnimationFrame(advance);
    };
    const yieldToInput = () => {
      if (!interacting) {
        interacting = true;
        sync();
      }
      clearTimeout(resumeTimer);
      if (pointer !== undefined) return;
      // Give native scrolling, including touch momentum, time to settle.
      resumeTimer = setTimeout(() => {
        interacting = false;
        sync();
      }, 900);
    };
    const releasePointer = (event) => {
      if (pointer === undefined || (event.type !== 'blur' && event.pointerId !== pointer)) return;
      pointer = undefined;
      yieldToInput();
    };
    syncShowcase = sync;
    control.addEventListener('click', toggleMotion);
    control.hidden = false;
    viewport.addEventListener('keydown', yieldToInput);
    viewport.addEventListener('pointerdown', (event) => {
      pointer = event.pointerId;
      yieldToInput();
    });
    window.addEventListener('pointerup', releasePointer);
    window.addEventListener('pointercancel', releasePointer);
    window.addEventListener('blur', releasePointer);
    viewport.addEventListener('scroll', () => {
      if (interacting) yieldToInput();
    }, { passive: true });
    viewport.addEventListener('wheel', (event) => {
      if (event.deltaX || event.shiftKey) yieldToInput();
    }, { passive: true });
    new IntersectionObserver(([entry]) => {
      visible = entry.isIntersecting;
      sync();
    }).observe(viewport);
  }
  syncMotion();

  const note = document.querySelector('#device-note');
  if (!note) return;
  const ua = navigator.userAgent.toLowerCase();
  const platform = navigator.userAgentData?.platform || navigator.platform || '';
  const device = /wear os|watch/.test(ua) ? 'Wear OS' : /android/.test(ua) && /tv|aft|bravia|shield/.test(ua) ? 'Android TV' : /android/.test(ua) ? 'Android' : /iphone|ipad|ipod/.test(ua) || (/ios/.test(platform.toLowerCase()) || /mac/.test(platform.toLowerCase()) && navigator.maxTouchPoints > 1) ? 'iOS' : /mac/.test(platform.toLowerCase()) ? 'macOS' : /win/.test(platform.toLowerCase()) ? 'Windows' : /linux/.test(platform.toLowerCase()) ? 'Linux' : '';
  const downloadLabel = document.querySelector('#download-label');
  if (downloadLabel && device) downloadLabel.textContent = device === 'iOS' ? 'iOS — coming soon' : `Download for ${device}`;
  const desktop = ['Windows', 'Linux', 'macOS'].includes(device);
  const released = desktop || device === 'Android' || device === 'Android TV' || device === 'Wear OS';
  if (device && !released && device !== 'iOS' && downloadLabel) {
    downloadLabel.textContent = 'Open the web player';
    if (downloadLabel.nextElementSibling) downloadLabel.nextElementSibling.textContent = '↗';
    document.querySelector('#device-download')?.setAttribute('href', 'https://web.nuvx.lol');
  }
  // Fills the device note; called again with the latest release's version once GitHub answers.
  const showNote = (version) => {
    if (device) note.textContent = released
      ? `nuvx ${version} for ${device} is out. ${device === 'Wear OS' ? 'Install the phone app; it adds nuvx to your watch.' : desktop ? 'The button downloads its installer; portable builds are on GitHub.' : 'It runs on Android 8.0 or newer.'}`
      : `No ${device} app yet. Use the web player in your browser, or get nuvx for Android.`;
  };
  showNote(document.querySelector('[data-version]')?.textContent ?? '');
  // A watch can't install the phone APK, so Wear OS keeps the release page.
  if (device === 'Android' || device === 'Android TV') {
    document.querySelector('#device-download')?.setAttribute('data-apk', device === 'Android TV' ? 'NuvxTV-' : 'nuvx-');
  }
  // A desktop gets its installer, never a portable build. A Mac counts as Apple silicon unless
  // the browser says it's Intel (only Chromium can tell; Safari reports every Mac as Intel).
  const hero = document.querySelector('#device-download');
  const installers = { Windows: String.raw`^nuvx-setup-[\d.]+\.exe$`, Linux: String.raw`^nuvx-[\d.]+-linux-x86_64\.tar\.gz$` };
  if (hero && installers[device]) hero.dataset.asset = installers[device];
  const files = { Windows: 'nuvx-setup-{v}.exe', Linux: 'nuvx-{v}-linux-x86_64.tar.gz', macOS: 'nuvx-setup-{v}-macos-aarch64.dmg' };
  if (hero && files[device]) hero.dataset.file = files[device];
  // Until GitHub's API answers, and when it never does (it allows 60 calls an hour per visitor), each
  // download links its file in the release of the version this page names, never the release page.
  const pageVersion = document.querySelector('[data-version]')?.textContent.trim();
  if (pageVersion) {
    for (const link of document.querySelectorAll('[data-file], [data-apk]')) {
      const name = link.dataset.file ? link.dataset.file.replace('{v}', pageVersion) : `${link.dataset.apk}v${pageVersion}.apk`;
      link.href = `https://github.com/bxanedot/nuvx/releases/download/v${pageVersion}/${name}`;
    }
  }
  const macArch = device === 'macOS' && navigator.userAgentData?.getHighEntropyValues
    ? navigator.userAgentData.getHighEntropyValues(['architecture']).then((v) => v.architecture === 'x86' ? 'x86_64' : 'aarch64').catch(() => 'aarch64')
    : Promise.resolve('aarch64');
  // Point every [data-apk] link at the newest release's universal APK (named "<prefix>vX.Y.Z.apk"), every
  // [data-asset] link at the asset its pattern names, and every [data-version] at its version; the release
  // page and the version in the HTML stay the fallback.
  const apkLinks = document.querySelectorAll('[data-apk]');
  Promise.all([fetch('https://api.github.com/repos/bxanedot/nuvx/releases/latest').then((r) => (r.ok ? r.json() : null)), macArch])
    .then(([release, arch]) => {
      if (hero && device === 'macOS') hero.dataset.asset = String.raw`^nuvx-setup-.*-macos-${arch}\.dmg$`;
      const version = release?.tag_name?.replace(/^v/, '');
      if (version) {
        for (const el of document.querySelectorAll('[data-version]')) el.textContent = version;
        showNote(version);
      }
      for (const link of apkLinks) {
        const asset = release?.assets?.find((a) => a.name.startsWith(link.dataset.apk) && a.name.endsWith('.apk') && !a.name.includes('_'));
        if (asset) link.href = asset.browser_download_url;
      }
      for (const link of document.querySelectorAll('[data-asset]')) {
        const pattern = new RegExp(link.dataset.asset);
        const asset = release?.assets?.find((a) => pattern.test(a.name));
        if (asset) link.href = asset.browser_download_url;
      }
    })
    .catch(() => {});
})();

// Promo video: loops muted on its own (browsers block sound without a click), unless
// the visitor asks for reduced motion. Stop rewinds to the start; the slider sets sound.
{
  const video = document.getElementById('promo-video');
  const play = document.getElementById('promo-play');
  const volume = document.getElementById('promo-volume');
  if (video && play && volume) {
    const label = () => { play.textContent = video.paused ? 'Play' : 'Stop'; };
    video.addEventListener('play', label);
    video.addEventListener('pause', label);
    play.addEventListener('click', () => {
      if (video.paused) video.play().catch(() => {});
      else { video.pause(); video.currentTime = 0; }
    });
    volume.addEventListener('input', () => {
      video.volume = Number(volume.value);
      video.muted = video.volume === 0;
    });
    if (!matchMedia('(prefers-reduced-motion: reduce)').matches) video.play().catch(() => {});
  }
}
