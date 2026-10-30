const CACHE = 'office-desk-pwa-v4';
const CORE = [
  './',
  './index.html',
  './manifest.webmanifest',
  './icon-192.png',
  './icon-512.png',
  './icon-maskable-512.png',
  './apple-touch-icon.png',
  './adhan-aqsa.mp3',
  './fonts/readex-pro.woff2'
];
self.addEventListener('install', event => {
  // cache each file on its own, so one missing file never blocks the install
  event.waitUntil(caches.open(CACHE)
    .then(cache => Promise.allSettled(CORE.map(u => cache.add(u))))
    .then(() => self.skipWaiting()));
});
self.addEventListener('activate', event => {
  // keep the scenes and sounds saved by an older version, drop the rest
  event.waitUntil(caches.keys()
    .then(keys => Promise.all(keys.filter(k => k !== CACHE && k !== 'office-desk-media').map(k => caches.delete(k))))
    .then(() => self.clients.claim()));
});
const isMedia = url => url.pathname.includes('/media/');
// videos and audio ask for byte ranges; answer them from the saved copy when there is one
async function fromRange(req) {
  const cached = await caches.match(req.url, { ignoreVary: true });
  if (!cached) return fetch(req);
  const buf = await cached.arrayBuffer(), size = buf.byteLength;
  const m = /bytes=(\d*)-(\d*)/.exec(req.headers.get('range') || '');
  let start = m && m[1] ? +m[1] : 0, end = m && m[2] ? +m[2] : size - 1;
  if (m && !m[1] && m[2]) { start = Math.max(0, size - +m[2]); end = size - 1; }
  if (start >= size) return new Response(null, { status: 416, headers: { 'Content-Range': `bytes */${size}` } });
  end = Math.min(end, size - 1);
  return new Response(buf.slice(start, end + 1), { status: 206, headers: {
    'Content-Type': cached.headers.get('Content-Type') || 'application/octet-stream',
    'Content-Range': `bytes ${start}-${end}/${size}`, 'Content-Length': String(end - start + 1), 'Accept-Ranges': 'bytes' } });
}
self.addEventListener('fetch', event => {
  const req = event.request, url = new URL(req.url);
  if (req.method !== 'GET' || url.origin !== self.location.origin) return;
  if (req.headers.has('range')) { event.respondWith(fromRange(req)); return; }
  if (req.mode === 'navigate') {
    event.respondWith(fetch(req).then(res => {
      const copy = res.clone();
      caches.open(CACHE).then(cache => cache.put('./index.html', copy));
      return res;
    }).catch(() => caches.match('./index.html')));
    return;
  }
  const store = isMedia(url) ? 'office-desk-media' : CACHE;
  event.respondWith(caches.match(req, { ignoreVary: true }).then(cached => cached || fetch(req).then(res => {
    if (res.ok && res.status === 200) {
      const copy = res.clone();
      caches.open(store).then(cache => cache.put(req, copy));
    }
    return res;
  })));
});
