(() => {
  const root = document.querySelector('.project-stats');
  if (!root) return;

  const endpoints = {
    downloads: 'https://nuvx-listen.lylicht.workers.dev/api/stats/downloads',
    live: 'https://nuvx-listen.lylicht.workers.dev/api/stats/live',
    stars: 'https://api.github.com/repos/lylicht/nuvx',
  };
  const write = (name, value) => {
    if (!Number.isFinite(Number(value))) return;
    const badge = root.querySelector(`[data-stat="${name}"]`);
    const output = badge?.querySelector('[data-stat-value]');
    if (!badge || !output) return;
    output.textContent = Math.max(0, Math.floor(Number(value))).toLocaleString();
    badge.setAttribute('aria-label', `${badge.querySelector('span')?.textContent}: ${output.textContent}`);
  };
  const json = async (url, cache = 'no-store') => {
    const response = await fetch(url, { headers: { accept: 'application/json' }, cache });
    if (!response.ok) throw new Error(`Stats request failed (${response.status}).`);
    return response.json();
  };

  const refreshTotals = async () => {
    const [downloads, stars] = await Promise.allSettled([
      json(endpoints.downloads),
      json(endpoints.stars, 'force-cache'),
    ]);
    if (downloads.status === 'fulfilled') write('downloads', downloads.value.downloads);
    if (stars.status === 'fulfilled') write('stars', stars.value.stargazers_count);
  };

  const refreshLive = async () => {
    const live = await json(endpoints.live).catch(() => null);
    if (!live) return;
    write('together', live.listening);
    write('online', live.online);
  };

  void refreshTotals();
  void refreshLive();
  window.setInterval(() => void refreshLive(), 30_000);
  document.addEventListener('visibilitychange', () => {
    if (document.visibilityState === 'visible') void refreshLive();
  });
})();
