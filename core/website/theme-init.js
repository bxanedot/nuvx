(() => {
  try {
    const theme = localStorage.getItem('nuvx-theme');
    if (theme === 'light' || theme === 'dark') {
      document.documentElement.dataset.theme = theme;
      const meta = document.querySelector('meta[name="theme-color"]');
      if (meta) meta.content = theme === 'dark' ? '#101114' : '#fcfcfa';
    }
  } catch {}
})();
