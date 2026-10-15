(() => {
  'use strict';
  if (window.__simpleCastInstalled) return;
  window.__simpleCastInstalled = true;
  const token = '__SCAN_TOKEN__';
  const ids = new WeakMap();
  const videos = new Map();
  const sent = new Map();
  let nextId = 0, activePlayer = '', scanTimer;
  const short = value => String(value || '').slice(0, 180);
  const absolute = value => {
    if (!value) return '';
    try {
      const url = new URL(value, document.baseURI);
      return /^https?:$/.test(url.protocol) ? url.href : '';
    } catch (_) { return ''; }
  };
  const kind = (url, type = '') => {
    const mime = type.split(';')[0].trim().toLowerCase();
    if (/^(application|audio)\/(x-mpegurl|vnd\.apple\.mpegurl|mpegurl)$/.test(mime)) return 'application/x-mpegURL';
    if (/^(video\/(mp4|webm)|application\/dash\+xml)$/.test(mime)) return mime;
    try {
      const path = new URL(url).pathname.toLowerCase();
      if (path.endsWith('.m3u8')) return 'application/x-mpegURL';
      if (path.endsWith('.mpd')) return 'application/dash+xml';
      if (/\.(mp4|m4v)$/.test(path)) return 'video/mp4';
      if (path.endsWith('.webm')) return 'video/webm';
    } catch (_) { }
    return '';
  };
  function post(payload) {
    try {
      if (window.SimpleCastDetector) window.SimpleCastDetector.postMessage(JSON.stringify({
        token, frame: location.href, title: short(document.title), ...payload
      }));
    } catch (_) { }
  }
  function owner() {
    if (activePlayer && videos.has(activePlayer)) return activePlayer;
    return videos.size === 1 ? videos.keys().next().value : '';
  }
  function stream(value, type = '', live = null, player = owner()) {
    const url = absolute(value);
    const mime = kind(url, type);
    if (!url || !mime) return;
    const key = url + '|' + player;
    const fingerprint = mime + '|' + live;
    if (sent.get(key) === fingerprint) return;
    if (sent.size >= 150 && !sent.has(key)) sent.delete(sent.keys().next().value);
    sent.set(key, fingerprint);
    post({ event: 'stream', url, mime, player, live });
  }
  function scan() {
    for (const [id, video] of videos) if (!video.isConnected) {
      videos.delete(id);
      post({ event: 'remove-player', player: id });
    }
    document.querySelectorAll('video').forEach(video => {
      if (!ids.has(video)) {
        if (videos.size >= 50) return;
        const id = 'video-' + (++nextId);
        ids.set(video, id);
        videos.set(id, video);
        ['play', 'pause', 'ended', 'loadedmetadata', 'durationchange', 'emptied'].forEach(event => {
          video.addEventListener(event, () => {
            if (event === 'play') activePlayer = id;
            scan();
          });
        });
      }
      const id = ids.get(video);
      const rect = video.getBoundingClientRect();
      const label = short(video.getAttribute('aria-label') || video.title || document.title || location.hostname);
      post({ event: 'player', player: id, label, playing: !video.paused,
        width: video.videoWidth || Math.round(rect.width), height: video.videoHeight || Math.round(rect.height) });
      const live = video.duration === Infinity ? true : Number.isFinite(video.duration) && video.duration > 0 ? false : null;
      stream(video.currentSrc || video.src, '', live, id);
      video.querySelectorAll('source').forEach(source => stream(source.src, source.type, live, id));
    });
    // An iframe may contain its own player; the document-start script runs there too.
  }
  function scheduleScan() {
    clearTimeout(scanTimer);
    scanTimer = setTimeout(scan, 200);
  }
  const manifestLive = (body, mime) => {
    if (mime.includes('mpegURL') && body.startsWith('#EXTM3U') && /#EXTINF:/.test(body))
      return !body.includes('#EXT-X-ENDLIST');
    if (mime.includes('dash') && /<MPD\b/.test(body)) return /\btype\s*=\s*["']dynamic["']/.test(body);
    return null; // A master HLS manifest doesn't identify live versus on-demand.
  };
  async function inspectResponse(response, requestedUrl, player) {
    const url = response.url || requestedUrl;
    const mime = kind(url, response.headers.get('content-type') || '');
    if (!mime) return;
    stream(url, mime, null, player);
    if (!mime.includes('mpegURL') && !mime.includes('dash')) return;
    // Inspect only small manifest bodies; never read or download the video itself.
    if (Number(response.headers.get('content-length')) > 1048576) return;
    const clone = response.clone();
    if (!clone.body) return;
    const reader = clone.body.getReader();
    const decoder = new TextDecoder();
    let body = '', bytes = 0;
    try {
      while (true) {
        const chunk = await reader.read();
        if (chunk.done) break;
        bytes += chunk.value.byteLength;
        if (bytes > 1048576) { reader.cancel().catch(() => {}); return; }
        body += decoder.decode(chunk.value, { stream: true });
      }
      body += decoder.decode();
      stream(url, mime, manifestLive(body, mime), player);
    } catch (_) { }
  }
  if (window.fetch) {
    const original = window.fetch;
    window.fetch = function(input, ...args) {
      const url = typeof input === 'string' || input instanceof URL ? String(input) : input.url;
      const player = owner();
      stream(url, '', null, player);
      return original.call(this, input, ...args).then(response => {
        inspectResponse(response, url, player).catch(() => {});
        return response;
      });
    };
  }
  if (window.XMLHttpRequest) {
    const original = XMLHttpRequest.prototype.open;
    XMLHttpRequest.prototype.open = function(method, url, ...args) {
      const player = owner();
      stream(url, '', null, player);
      this.addEventListener('load', () => {
        try {
          const mime = kind(this.responseURL || url, this.getResponseHeader('content-type') || '');
          let live = null;
          if ((mime.includes('mpegURL') || mime.includes('dash')) && (!this.responseType || this.responseType === 'text')) {
            const body = this.responseText;
            if (body.length <= 1048576) live = manifestLive(body, mime);
          }
          stream(this.responseURL || url, mime, live, player);
        } catch (_) { }
      }, { once: true });
      return original.call(this, method, url, ...args);
    };
  }
  try {
    new PerformanceObserver(list => list.getEntries().forEach(entry => stream(entry.name)))
      .observe({ type: 'resource', buffered: true });
  } catch (_) { }
  new MutationObserver(scheduleScan).observe(document, { childList: true, subtree: true, attributes: true,
    attributeFilter: ['src', 'type', 'title', 'aria-label'] });
  window.__simpleCastControl = message => {
    if (message.action === 'scan') scan();
    if (message.action === 'pause') videos.forEach(video => video.pause());
    if (message.action === 'activate' && videos.has(message.player)) {
      const video = videos.get(message.player);
      activePlayer = message.player;
      video.scrollIntoView({ block: 'center', behavior: 'smooth' });
      video.play().catch(() => {});
      scan();
    }
  };
  if (window.SimpleCastDetector) window.SimpleCastDetector.onmessage = event => {
    try { window.__simpleCastControl(JSON.parse(event.data)); } catch (_) { }
  };
  document.addEventListener('DOMContentLoaded', scan);
  scan();
})();
