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

  const viewport = document.querySelector('#showcase-viewport');
  if (viewport) {
    const showcase = viewport.closest('.showcase');
    const banners = viewport.querySelector('.showcase-banners');
    const repeat = banners.cloneNode(true);
    repeat.setAttribute('aria-hidden', 'true');
    const control = document.querySelector('.showcase-toggle');
    const motion = matchMedia('(prefers-reduced-motion: reduce)');
    let playing = !motion.matches;
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
      showcase.toggleAttribute('data-flowing', playing && visible);
      if (!playing || !visible || interacting) return;
      if (!repeat.isConnected) banners.after(repeat);
      position = viewport.scrollLeft;
      lastTime = undefined;
      frame = requestAnimationFrame(advance);
    };
    const pause = () => {
      playing = false;
      sync();
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
    control.addEventListener('click', () => {
      playing = !playing;
      sync();
    });
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
    motion.addEventListener('change', (event) => {
      if (event.matches) pause();
    });
    new IntersectionObserver(([entry]) => {
      visible = entry.isIntersecting;
      sync();
    }).observe(viewport);
  }

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
      ? `nuvx ${version} for ${device} is out. ${device === 'Wear OS' ? 'Install the phone app; it adds nuvx to your watch.' : desktop ? 'Choose an installer or a portable build on GitHub.' : 'It runs on Android 8.0 or newer.'}`
      : `No ${device} app yet. Use the web player in your browser, or get nuvx for Android.`;
  };
  showNote(document.querySelector('[data-version]')?.textContent ?? '');
  // A watch can't install the phone APK, so Wear OS keeps the release page.
  if (device === 'Android' || device === 'Android TV') {
    document.querySelector('#device-download')?.setAttribute('data-apk', device === 'Android TV' ? 'NuvxTV-' : 'nuvx-');
  }
  // Point every [data-apk] link at the newest release's universal APK (named "<prefix>vX.Y.Z.apk") and every
  // [data-version] at its version; the release page and the version in the HTML stay the fallback.
  const apkLinks = document.querySelectorAll('[data-apk]');
  fetch('https://api.github.com/repos/bxanedot/nuvx/releases/latest')
    .then((r) => (r.ok ? r.json() : null))
    .then((release) => {
      const version = release?.tag_name?.replace(/^v/, '');
      if (version) {
        for (const el of document.querySelectorAll('[data-version]')) el.textContent = version;
        showNote(version);
      }
      for (const link of apkLinks) {
        const asset = release?.assets?.find((a) => a.name.startsWith(link.dataset.apk) && a.name.endsWith('.apk') && !a.name.includes('_'));
        if (asset) link.href = asset.browser_download_url;
      }
    })
    .catch(() => {});
})();
