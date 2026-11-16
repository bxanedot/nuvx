(() => {
  const board = document.querySelector('#listener-board');
  if (!board) return;

  const memberCount = document.querySelector('#member-count');
  const activeListeners = document.querySelector('#active-listeners');
  let boardState = '';
  const showUnknownCounts = () => {
    if (memberCount) memberCount.textContent = '—';
    if (activeListeners) activeListeners.textContent = '—';
  };
  const showEmpty = (message) => {
    const state = `empty:${message}`;
    if (boardState === state) return;
    boardState = state;
    const empty = document.createElement('p');
    empty.className = 'listener-empty';
    empty.setAttribute('role', 'status');
    empty.textContent = message;
    board.replaceChildren(empty);
  };

  const render = (entries) => {
    const visibleEntries = entries.slice(0, 10);
    const state = `list:${JSON.stringify(visibleEntries.map((entry) => [entry.displayName, entry.minutes]))}`;
    if (boardState === state) return;
    boardState = state;
    const list = document.createElement('ol');
    list.className = 'listener-list';
    visibleEntries.forEach((entry, index) => {
      const row = document.createElement('li');
      row.className = 'listener-row';
      const rank = document.createElement('span');
      rank.className = 'listener-rank';
      rank.textContent = String(index + 1).padStart(2, '0');
      const name = document.createElement('span');
      name.className = 'listener-name';
      name.textContent = String(entry.displayName ?? 'Listener');
      const minutes = document.createElement('span');
      minutes.className = 'listener-minutes';
      const value = Math.max(0, Math.floor(Number(entry.minutes) || 0));
      minutes.textContent = `${value.toLocaleString()} min`;
      row.append(rank, name, minutes);
      list.append(row);
    });
    board.replaceChildren(list);
  };

  const feed = board.dataset.feed;
  if (!feed) return;

  let loading = false;
  const refresh = async () => {
    if (loading || document.visibilityState === 'hidden') return;
    loading = true;
    try {
      const response = await fetch(feed, { headers: { accept: 'application/json' }, cache: 'no-store' });
      if (!response.ok) throw new Error('Community feed unavailable.');
      const data = await response.json();
      if (!data || !Array.isArray(data.listeners)) throw new Error('Community feed is invalid.');
      const updatedAt = Number(data.updatedAt);
      const fresh = Number.isFinite(updatedAt) && Date.now() - updatedAt < 5 * 60 * 1000;
      const entries = data.listeners
        .filter((entry) => entry && typeof entry.displayName === 'string' && Number.isFinite(Number(entry.minutes)))
        .sort((a, b) => Number(b.minutes) - Number(a.minutes));
      if (fresh) {
        if (memberCount) memberCount.textContent = Math.max(0, Number(data.memberCount) || 0).toLocaleString();
        if (activeListeners) activeListeners.textContent = Math.max(0, Number(data.activeListeners) || 0).toLocaleString();
      }
      if (!fresh) {
        showUnknownCounts();
        showEmpty('Rankings are empty until the nuvx Discord bot connects.');
        return;
      }
      if (entries.length === 0) {
        showEmpty('No opt-in listener totals yet. Use `/leaderboard action:opt-in` in Discord to join the ranking.');
        return;
      }
      render(entries);
    } catch {
      showUnknownCounts();
      showEmpty('Rankings are empty until the nuvx Discord bot connects.');
    } finally {
      loading = false;
    }
  };

  void refresh();
  window.setInterval(refresh, 30_000);
  document.addEventListener('visibilitychange', () => {
    if (document.visibilityState === 'visible') void refresh();
  });
})();
