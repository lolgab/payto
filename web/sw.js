// Minimal service worker — enables PWA install, no caching (avoids stale assets during dev)
self.addEventListener('install', () => self.skipWaiting());
self.addEventListener('activate', (e) => e.waitUntil(clients.claim()));
