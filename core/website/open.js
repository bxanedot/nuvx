const status = document.querySelector('#status');
let appOpened = false;

window.addEventListener('pagehide', () => { appOpened = true; });
document.addEventListener('visibilitychange', () => {
  if (document.hidden) appOpened = true;
});

window.setTimeout(() => {
  if (!appOpened && status) status.textContent = 'If the app did not open, you can continue on nuvx.lol.';
}, 1400);

window.location.assign('nuvx://open-player');
