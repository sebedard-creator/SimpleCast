const test = require('node:test');
const assert = require('node:assert/strict');
const vm = require('node:vm');
const fs = require('node:fs');
const script = fs.readFileSync('app/src/main/assets/detector.js', 'utf8');

function environment(videoList, frame = 'https://page.test/watch') {
  const messages = [];
  const events = {};
  let mutation;
  const response = new Response('#EXTM3U\n#EXT-X-TARGETDURATION:6\n#EXTINF:6,\nsegment.ts\n', {
    headers: { 'content-type': 'application/vnd.apple.mpegurl' }
  });
  Object.defineProperty(response, 'url', { value: 'https://cdn.test/manifest?token=A%2FB' });
  class XHR {
    open() { }
    addEventListener(name, listener) { this.listener = listener; }
    getResponseHeader() { return 'application/dash+xml'; }
  }
  const context = {
    URL, TextDecoder, setTimeout, clearTimeout,
    document: { baseURI: frame, title: 'A test page', querySelectorAll: () => videoList,
      addEventListener: (name, listener) => { events[name] = listener; } },
    location: { href: frame, hostname: new URL(frame).hostname },
    SimpleCastDetector: { postMessage: value => messages.push(JSON.parse(value)) },
    fetch: () => Promise.resolve(response), XMLHttpRequest: XHR,
    MutationObserver: class { constructor(callback) { mutation = callback; } observe() { } },
    PerformanceObserver: class { constructor(callback) { this.callback = callback; } observe() {
      this.callback({ getEntries: () => [ { name: 'https://cdn.test/segment.ts' }, { name: 'https://cdn.test/image?video=fake.mp4' } ] });
    } }
  };
  context.window = context;
  vm.runInNewContext(script, context);
  return { messages, context, events, response, mutate: () => mutation() };
}
function video(label, src = '', sources = []) {
  const listeners = {};
  return {
    title: '', src, currentSrc: src, paused: true, duration: NaN, isConnected: true,
    videoWidth: 1920, videoHeight: 1080,
    getAttribute: () => label,
    getBoundingClientRect: () => ({ width: 300, height: 169 }),
    querySelectorAll: () => sources,
    addEventListener: (event, listener) => { listeners[event] = listener; },
    scrollIntoView() { this.scrolled = true; },
    play() { this.paused = false; listeners.play?.(); return Promise.resolve(); },
    pause() { this.paused = true; }, listeners
  };
}
test('lists two actual players and associates their separate sources', () => {
  const env = environment([
    video('First', '', [{ src: 'https://cdn.test/one.mp4', type: 'video/mp4' }]),
    video('Second', '', [{ src: 'https://cdn.test/two.webm', type: 'video/webm' }])
  ]);
  assert.equal(env.messages.filter(m => m.event === 'player').length, 2);
  const streams = env.messages.filter(m => m.event === 'stream');
  assert.equal(streams.length, 2);
  assert.equal(streams[0].player, 'video-1');
  assert.equal(streams[1].player, 'video-2');
  assert.ok(streams.every(m => !m.url.includes('segment') && !m.url.includes('fake')));
});
test('finds extensionless HLS behind a blob player, preserves signed URL and identifies live', async () => {
  const env = environment([video('Blob player', 'blob:https://page.test/id')]);
  const returned = await env.context.fetch('https://cdn.test/play?id=12');
  assert.equal(returned, env.response, 'must preserve the original response for the page');
  await new Promise(resolve => setTimeout(resolve, 20));
  const streams = env.messages.filter(m => m.event === 'stream');
  assert.ok(streams.some(m => m.url === 'https://cdn.test/manifest?token=A%2FB' && m.live === true && m.player === 'video-1'));
  assert.ok(!streams.some(m => m.url.startsWith('blob:')));
  assert.match(await returned.text(), /EXTM3U/, 'page can still consume its response body');
});
test('XHR detects a redirected, extensionless dynamic DASH manifest inside another frame', () => {
  const env = environment([video('Embedded')], 'https://embed.test/frame');
  const xhr = new env.context.XMLHttpRequest();
  xhr.open('GET', 'https://cdn.test/play');
  xhr.responseURL = 'https://cdn.test/redirected';
  xhr.responseText = '<MPD type="dynamic"></MPD>';
  xhr.listener();
  const stream = env.messages.find(m => m.event === 'stream');
  assert.equal(stream.frame, 'https://embed.test/frame');
  assert.equal(stream.url, 'https://cdn.test/redirected');
  assert.equal(stream.mime, 'application/dash+xml');
  assert.equal(stream.live, true);
});
test('native selection activates the selected player and pause stops page audio', () => {
  const first = video('First'), second = video('Second');
  const env = environment([first, second]);
  env.context.SimpleCastDetector.onmessage({ data: JSON.stringify({ action: 'activate', player: 'video-2' }) });
  assert.equal(first.paused, true); assert.equal(second.paused, false); assert.equal(second.scrolled, true);
  env.context.SimpleCastDetector.onmessage({ data: JSON.stringify({ action: 'pause' }) });
  assert.equal(second.paused, true);
});
test('removed players disappear and repeat scanning deduplicates streams', () => {
  const first = video('First', 'https://cdn.test/one.mp4');
  const env = environment([first]);
  env.context.__simpleCastControl({ action: 'scan' });
  assert.equal(env.messages.filter(m => m.event === 'stream').length, 1);
  first.isConnected = false;
  env.context.document.querySelectorAll = () => [];
  env.context.__simpleCastControl({ action: 'scan' });
  assert.ok(env.messages.some(m => m.event === 'remove-player' && m.player === 'video-1'));
});

test('paused playback updates the native player state immediately', () => {
  const player = video('Game', 'https://cdn.test/live.m3u8');
  const env = environment([player]);
  player.paused = false; player.listeners.play();
  assert.equal(env.messages.filter(m => m.event === 'player').at(-1).playing, true);
  player.paused = true; player.listeners.pause();
  assert.equal(env.messages.filter(m => m.event === 'player').at(-1).playing, false);
});

test('long playback keeps detecting new signed URLs after the catalog limit', () => {
  const env = environment([video('Game', 'blob:https://page.test/id')]);
  for (let i = 0; i < 180; i++) {
    const xhr = new env.context.XMLHttpRequest();
    xhr.open('GET', 'https://cdn.test/live.m3u8?token=' + i);
  }
  assert.ok(env.messages.some(m => m.event === 'stream' && m.url.endsWith('?token=179')));
});
