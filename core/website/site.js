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
  if (downloadLabel && device) downloadLabel.textContent = device === 'iOS' ? 'Latest release' : `Download for ${device}`;
  const hero = document.querySelector('#device-download');
  const downloads = { Windows: 'windows', Linux: 'linux', macOS: 'mac-arm', Android: 'android', 'Android TV': 'tv', 'Wear OS': 'wear' };
  if (hero && downloads[device]) hero.href = '/api/download?platform=' + downloads[device];
  if (hero && device === 'macOS' && navigator.userAgentData?.getHighEntropyValues) {
    navigator.userAgentData.getHighEntropyValues(['architecture'])
      .then(({ architecture }) => {
        if (architecture === 'x86') hero.href = '/api/download?platform=mac-intel';
      }).catch(() => {});
  }
  const showNote = (version) => {
    if (downloads[device]) note.textContent = `Download nuvx ${version} for ${device} from the latest GitHub release.`;
    else if (device === 'iOS') note.textContent = 'No iOS app yet. The latest release lists all available downloads.';
  };
  showNote(document.querySelector('[data-version]')?.textContent.trim() ?? '');
  fetch('https://api.github.com/repos/bxanedot/nuvx/releases/latest')
    .then((response) => response.ok ? response.json() : null)
    .then((release) => {
      const version = release?.tag_name?.replace(/^v/, '');
      if (!version) return;
      for (const el of document.querySelectorAll('[data-version]')) el.textContent = version;
      showNote(version);
    }).catch(() => {});
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
