// Service worker minimo. Dos reglas:
//
// 1. La pagina (index.html) va PRIMERO A LA RED. Si no, al publicar una version
//    nueva el navegador seguiria mostrando la vieja indefinidamente. La copia en
//    cache solo se usa cuando no hay conexion.
// 2. Los iconos y el manifest, que no cambian, van primero a la cache.
//
// El audio NUNCA pasa por aqui: es de otro dominio y se deja ir directo al
// reproductor, sin proxy ni cache, para no gastar datos de mas.

const CACHE = "radioco-v2";
const SHELL = [
  "./",
  "./index.html",
  "./manifest.webmanifest",
  "./icon-192.png",
  "./icon-512.png"
];

self.addEventListener("install", e => {
  e.waitUntil(caches.open(CACHE).then(c => c.addAll(SHELL)).then(() => self.skipWaiting()));
});

self.addEventListener("activate", e => {
  e.waitUntil(
    caches.keys()
      .then(ks => Promise.all(ks.filter(k => k !== CACHE).map(k => caches.delete(k))))
      .then(() => self.clients.claim())
  );
});

function esPagina(req) {
  return req.mode === "navigate" ||
         (req.headers.get("accept") || "").includes("text/html");
}

self.addEventListener("fetch", e => {
  const req = e.request;
  if (req.method !== "GET") return;

  const url = new URL(req.url);
  // fuera de nuestro origen (los streams, la API de Triton): sin tocar
  if (url.origin !== self.location.origin) return;

  if (esPagina(req)) {
    // red primero, y de paso refrescamos la copia guardada
    e.respondWith(
      fetch(req)
        .then(res => {
          if (res && res.ok) {
            const copia = res.clone();
            caches.open(CACHE).then(c => c.put("./index.html", copia));
          }
          return res;
        })
        .catch(() => caches.match("./index.html").then(hit => hit || caches.match("./")))
    );
    return;
  }

  // el resto (iconos, manifest): cache primero
  e.respondWith(
    caches.match(req).then(hit => hit || fetch(req).then(res => {
      if (res && res.ok) {
        const copia = res.clone();
        caches.open(CACHE).then(c => c.put(req, copia));
      }
      return res;
    }))
  );
});
