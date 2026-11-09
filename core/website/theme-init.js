(() => {
  const root = document.documentElement;
  try {
    const theme = localStorage.getItem('nuvx-theme');
    if (theme === 'light' || theme === 'dark') root.dataset.theme = theme;
    root.toggleAttribute('data-motion-paused', localStorage.getItem('nuvx-motion') === 'paused');
  } catch {}
})();
