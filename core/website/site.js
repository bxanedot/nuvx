(() => {
  const root = document.documentElement;
  const toggle = document.querySelector('#theme-toggle');
  const themeColor = document.querySelector('meta[name="theme-color"]');
  const isDark = () => root.dataset.theme === 'dark';
  const updateTheme = () => {
    if (themeColor) themeColor.content = isDark() ? '#0a0a0a' : '#fafafa';
    if (toggle) toggle.setAttribute('aria-label', `Switch to ${isDark() ? 'light' : 'dark'} mode`);
  };
  toggle?.addEventListener('click', () => {
    root.dataset.theme = isDark() ? 'light' : 'dark';
    try { localStorage.setItem('nuvx-theme', root.dataset.theme); } catch {}
    updateTheme();
  });
  updateTheme();

  const topbar = document.querySelector('.topbar');
  const menuToggle = document.querySelector('#menu-toggle');
  const backgroundToggle = document.querySelector('#background-monochrome-toggle');
  const monochrome = () => root.hasAttribute('data-background-monochrome');
  backgroundToggle?.setAttribute('aria-pressed', String(monochrome()));
  backgroundToggle?.addEventListener('click', () => {
    root.toggleAttribute('data-background-monochrome', !monochrome());
    backgroundToggle.setAttribute('aria-pressed', String(monochrome()));
    try { localStorage.setItem('nuvx-background-monochrome', monochrome() ? '1' : '0'); } catch {}
  });
  const updateTopbar = () => {
    if (!topbar) return;
    const isCompact = topbar.classList.contains('is-forward');
    topbar.classList.toggle('is-forward', window.scrollY > (isCompact ? 8 : 20));
  };
  updateTopbar();
  window.addEventListener('scroll', updateTopbar, { passive: true });
  const closeMenu = () => {
    topbar?.classList.remove('is-menu-open');
    menuToggle?.setAttribute('aria-expanded', 'false');
    menuToggle?.setAttribute('aria-label', 'Open navigation');
  };
  menuToggle?.addEventListener('click', () => {
    const open = !topbar?.classList.contains('is-menu-open');
    topbar?.classList.toggle('is-menu-open', open);
    menuToggle.setAttribute('aria-expanded', String(open));
    menuToggle.setAttribute('aria-label', open ? 'Close navigation' : 'Open navigation');
  });
  topbar?.querySelectorAll('#primary-navigation a').forEach((link) => link.addEventListener('click', closeMenu));
  document.addEventListener('keydown', (event) => {
    if (event.key === 'Escape') {
      closeMenu();
      const downloads = document.querySelector('.download-menu');
      if (downloads?.open) {
        downloads.open = false;
        downloads.querySelector('summary')?.focus();
      }
    }
  });
  document.addEventListener('click', (event) => {
    if (topbar && !topbar.contains(event.target)) closeMenu();
    const downloads = document.querySelector('.download-menu');
    if (downloads?.open && !downloads.contains(event.target)) downloads.open = false;
  });

  const motion = matchMedia('(prefers-reduced-motion: reduce)');
  const control = document.querySelector('.showcase-toggle');
  const video = document.querySelector('#promo-video');
  const ambientVideo = document.querySelector('#ambient-video');
  const volume = document.querySelector('#promo-volume');
  if (video && volume) {
    volume.addEventListener('input', () => {
      video.volume = Number(volume.value);
      video.muted = video.volume === 0;
    });
  }
  let manuallyPaused = root.hasAttribute('data-motion-paused');
  let playing = !motion.matches && !manuallyPaused;
  let syncShowcase;
  const syncMotion = () => {
    root.toggleAttribute('data-motion-paused', !playing);
    if (control) control.textContent = playing ? 'Pause showcase and video' : 'Play showcase and video';
    syncShowcase?.();
    if (playing) {
      video?.play().catch(() => {});
    } else {
      video?.pause();
    }
    if (motion.matches) ambientVideo?.pause();
    else ambientVideo?.play().catch(() => {});
  };
  const toggleMotion = () => {
    playing = !playing;
    manuallyPaused = !playing;
    try { localStorage.setItem('nuvx-motion', manuallyPaused ? 'paused' : 'on'); } catch {}
    syncMotion();
  };
  control?.addEventListener('click', toggleMotion);
  if (control) control.hidden = false;
  motion.addEventListener('change', () => {
    playing = !motion.matches && !manuallyPaused;
    syncMotion();
  });

  const viewport = document.querySelector('#showcase-viewport');
  if (viewport) {
    const banners = viewport.querySelector('.showcase-banners');
    const repeat = banners.cloneNode(true);
    repeat.setAttribute('aria-hidden', 'true');
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
      position = (position + cycle - distance) % cycle;
      viewport.scrollLeft = position;
      lastTime = time;
      frame = requestAnimationFrame(advance);
    };
    const sync = () => {
      cancelAnimationFrame(frame);
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
  const hero = document.querySelector('#device-download');
  const downloads = { Windows: 'windows', macOS: 'mac', Linux: 'linux', Android: 'android', 'Android TV': 'tv', 'Wear OS': 'wear' };
  if (hero) hero.href = '#ecosystem';
  if (downloadLabel) downloadLabel.textContent = 'Choose a download';
  if (downloads[device]) {
    const target = downloads[device];
    if (hero) hero.href = '/api/download?platform=' + target;
    if (downloadLabel) downloadLabel.textContent = device === 'iOS' ? 'Download iOS IPA' : `Download for ${device}`;
  } else if (device === 'iOS') {
    if (hero) hero.href = '/api/download?platform=ios';
    if (downloadLabel) downloadLabel.textContent = 'Download iOS IPA';
  }
  const showNote = (version) => {
    if (device === 'iOS') note.textContent = 'The unsigned iOS IPA must be signed before installation.';
    else if (downloads[device]) note.textContent = `The latest nuvx ${version} package for ${device} downloads directly.`;
    else note.textContent = 'Choose a device package from the list below.';
  };
  showNote(document.querySelector('[data-version]')?.textContent.trim() ?? '');
  fetch('https://api.github.com/repos/lylicht/nuvx/releases/latest')
    .then((response) => response.ok ? response.json() : null)
    .then((release) => {
      const version = release?.tag_name?.replace(/^v/, '');
      if (!version) return;
      for (const el of document.querySelectorAll('[data-version]')) el.textContent = version;
      showNote(version);
    }).catch(() => {});
})();
