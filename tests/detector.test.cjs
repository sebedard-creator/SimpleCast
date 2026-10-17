const test = require('node:test');
const assert = require('node:assert/strict');
const vm = require('node:vm');
const fs = require('node:fs');
const script = fs.readFileSync('app/src/main/assets/detector.js', 'utf8');

function environment(videoList, frame = 'https://page.test/watch', suppliedResponse = null) {
  const messages = [];
  const events = {};
  let mutation;
  const response = suppliedResponse || new Response('#EXTM3U\n#EXT-X-TARGETDURATION:6\n#EXTINF:6,\nsegment.ts\n', {
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
    getAttribute: name => name === 'src' ? src : name === 'aria-label' ? label : null,
    getBoundingClientRect: () => ({ width: 300, height: 169 }),
    querySelectorAll: () => sources.map(source => ({...source,
      getAttribute: name => name === 'src' ? (Object.hasOwn(source, 'rawSrc') ? source.rawSrc : source.src) : null})),
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

test('ignores empty source placeholders that the DOM resolves to the HTML page', () => {
  const frame = 'https://page.test/watch?id=12';
  const element = video('Player', '', [{src:frame, rawSrc:'', type:'video/mp4'}, {src:frame, rawSrc:null, type:'video/webm'}]);
  element.currentSrc = frame;
  const env = environment([element], frame);
  assert.equal(env.messages.filter(m => m.event === 'player').length, 1);
  assert.equal(env.messages.filter(m => m.event === 'stream').length, 0);
});

test('finds complete videoUrl sources in JSON arrays without inventing a MIME for metadata endpoints', () => {
  const env = environment([video('Player')]);
  const xhr = new env.context.XMLHttpRequest();
  xhr.getResponseHeader = () => 'application/json';
  xhr.open('GET','https://page.test/api/media');xhr.responseURL='https://page.test/api/media';
  xhr.responseText=JSON.stringify([{videoUrl:'https://cdn.test/movie.mp4?t=A%2FB',quality:'720'},
    {videoUrl:'https://page.test/get_media?id=12',format:'mp4'}]);
  xhr.listener();
  const streams=env.messages.filter(m=>m.event==='stream');
  assert.equal(streams.length,1);assert.equal(streams[0].url,'https://cdn.test/movie.mp4?t=A%2FB');
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

test('captures complete and fallback streams from XHR player metadata without treating fragments as videos', () => {
  const env = environment([video('Player')]);
  const xhr = new env.context.XMLHttpRequest();
  xhr.getResponseHeader = name => name === 'content-type' ? 'application/json' : null;
  xhr.open('GET', 'https://page.test/api/player'); xhr.responseURL = 'https://page.test/api/player';
  xhr.responseType = 'json'; xhr.response = { sources: {
    hls: { auto: { src: 'https://cdn.test/av1/master.m3u8?t=A%2FB', srcFallback: 'https://cdn.test/avc/master.m3u8?t=C%2FD', type: 'application/x-mpegURL' } },
    mp4: { '1080p': { src: 'https://cdn.test/1080p.mp4?t=E%2FF', type: 'video/mp4' },
      fragment: { src: 'https://cdn.test/seg-1.m4s', type: 'video/mp4' } }
  }};
  xhr.listener();
  const streams = env.messages.filter(m => m.event === 'stream');
  assert.equal(streams.length, 3);
  assert.ok(streams.every(m => m.player === 'video-1'));
  assert.equal(streams.find(m => m.fallback).url, 'https://cdn.test/avc/master.m3u8?t=C%2FD');
});

test('reads small fetch metadata without consuming the response used by the page', async () => {
  const response = new Response(JSON.stringify({sources: [{file:'https://cdn.test/video.mp4?token=A%2FB', type:'video/mp4'}]}), {
    headers: {'content-type':'application/json'}
  });
  const env = environment([video('Player')], 'https://page.test/watch', response);
  const original = await env.context.fetch('https://page.test/api/player');
  await new Promise(resolve => setTimeout(resolve, 20));
  assert.ok(env.messages.some(m => m.url === 'https://cdn.test/video.mp4?token=A%2FB'));
  assert.equal((await original.json()).sources.length, 1);
});

test('ignores MP4-typed media fragments and initialization files while preserving the full manifest', () => {
  const env = environment([video('Player', 'blob:https://page.test/id')]);
  for (const path of ['movie.mp4/seg-61-v1-a1.m4s?hash=A%2FB', 'segment.ts', 'track.cmfv', 'track.cmfa',
    'movie.mp4/init-v1-a1.mp4?token=1', 'initialization.mp4']) {
    const url = 'https://cdn.test/' + path;
    const xhr = new env.context.XMLHttpRequest();
    xhr.getResponseHeader = () => 'video/mp4';
    xhr.open('GET', url);
    xhr.responseURL = url; xhr.responseType = 'arraybuffer'; xhr.listener();
  }
  assert.equal(env.messages.filter(m => m.event === 'stream').length, 0);
  const manifest = new env.context.XMLHttpRequest();
  manifest.getResponseHeader = () => 'application/vnd.apple.mpegurl';
  manifest.open('GET', 'https://cdn.test/movie.mp4/index.m3u8?token=A%2FB');
  manifest.responseURL = 'https://cdn.test/movie.mp4/index.m3u8?token=A%2FB';
  manifest.responseText = '#EXTM3U\n#EXTINF:6,\nsegment.m4s\n#EXT-X-ENDLIST\n'; manifest.listener();
  const streams = env.messages.filter(m => m.event === 'stream');
  assert.ok(streams.every(m => m.mime === 'application/x-mpegURL'));
  assert.ok(streams.some(m => m.url.endsWith('?token=A%2FB') && m.live === false));
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


test('reports the active source and rendered size for automatic player selection', () => {
  const player = video('Main', 'https://cdn.test/playing.mp4?t=A%2FB');
  player.paused = false;
  const env = environment([player]);
  const found = env.messages.find(m => m.event === 'player');
  assert.equal(found.playing, true);
  assert.equal(found.visible, true);
  assert.equal(found.area, 300 * 169);
  assert.equal(found.source, 'https://cdn.test/playing.mp4?t=A%2FB');
});

test('identifies HLS master playlists so audio renditions do not displace them', async () => {
  const response = new Response('#EXTM3U\n#EXT-X-STREAM-INF:BANDWIDTH=4000000\nvideo.m3u8\n', {
    headers: {'content-type':'application/vnd.apple.mpegurl'}
  });
  const env = environment([video('Main')], 'https://page.test/', response);
  await env.context.fetch('https://cdn.test/master.m3u8');
  await new Promise(resolve => setTimeout(resolve, 50));
  assert.ok(env.messages.some(m => m.event === 'stream' && m.master === true));
});
