// Minimal service worker so browsers treat the app as installable. Live data needs the PC anyway, so it
// always goes to the network and only falls back to the cached shell when the PC is unreachable.
const CACHE = 'pocketmon-shell-v3';
const SHELL = ['./', 'index.html', 'style.css', 'js/app.js', 'js/format.js', 'js/gauges.js', 'js/dashboards.js', 'vendor/NoSleep.min.js', 'favicon.png', 'logo.png', 'fonts/Orbitron.ttf', 'fonts/Cinzel.ttf'];

self.addEventListener('install', (e) => {
  e.waitUntil(caches.open(CACHE).then((c) => c.addAll(SHELL)).then(() => self.skipWaiting()));
});

self.addEventListener('activate', (e) => {
  e.waitUntil(caches.keys().then((keys) => Promise.all(keys.filter((k) => k !== CACHE).map((k) => caches.delete(k)))).then(() => self.clients.claim()));
});

self.addEventListener('fetch', (e) => {
  if (e.request.method !== 'GET') return;
  e.respondWith(
    // Always revalidate with the agent so updated app files are picked up immediately.
    fetch(e.request, { cache: 'no-cache' })
      .then((res) => {
        const copy = res.clone();
        caches.open(CACHE).then((c) => c.put(e.request, copy)).catch(() => {});
        return res;
      })
      .catch(() => caches.match(e.request)),
  );
});
