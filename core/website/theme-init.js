(() => {
  const root = document.documentElement;
  try {
    const theme = localStorage.getItem('nuvx-theme');
    if (theme === 'light' || theme === 'dark') root.dataset.theme = theme;
    root.toggleAttribute('data-motion-paused', localStorage.getItem('nuvx-motion') === 'paused');
    root.toggleAttribute('data-background-monochrome', localStorage.getItem('nuvx-background-monochrome') === '1');
  } catch {}
})();
