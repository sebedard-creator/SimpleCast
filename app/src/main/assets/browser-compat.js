(() => {
  'use strict';
  if (window.__simpleCastCompat) return;
  window.__simpleCastCompat = true;
  // Retry the ad-library popup probe after earlier versions cached a blocked window.
  try {
    const key = 'sndbxchckiborjforqp2';
    const state = JSON.parse(localStorage.getItem(key));
    if (state && state.isSandboxed === true) localStorage.removeItem(key);
  } catch (_) { }
  // A few players use this invalid, three-byte PDF as a sandbox probe.
  // WebView has no PDF viewer: keep that false failure from replacing the player.
  document.addEventListener('error', event => {
    const object = event.target;
    if (object && object.tagName === 'OBJECT'
        && object.getAttribute('data') === 'data:application/pdf;base64,aG1t'
        && object.getAttribute('onerror') === 'window.sandDetect()') {
      event.stopImmediatePropagation();
    }
  }, true);
})();
