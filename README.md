# Radio CO

Radio mínima para **Olímpica Stéreo Ibagué (94.3 FM)** y **La Mega Bogotá (90.9 FM)**.
Sin anuncios, sin rastreadores, sin imágenes ni scripts de terceros: solo el audio.

Dos entregables independientes:

- `web/` → página que funciona en cualquier navegador (y se instala como app).
- `android/` → app nativa Android (APK).

---

## Streams usados

Sacados de los reproductores oficiales de cada emisora, verificados con `curl`:

| Emisora | Stream principal | Bitrate | Datos |
|---|---|---|---|
| Olímpica Stéreo Ibagué | `playerservices.streamtheworld.com/api/livestream-redirect/OLP_IBAGUEAAC.aac` | AAC+ 64k | ~28 MB/h |
| Olímpica (respaldo) | `.../OLP_IBAGUE.mp3` | MP3 96k | ~42 MB/h |
| La Mega Bogotá | `mdstrm.com/audio/632c9ae6660fef03fe3855fe/icecast.audio` | AAC 128k | ~56 MB/h |
| La Mega (respaldo) | `mdstrm.com/audio/632c9ae6660fef03fe3855fe/live.m3u8` | HLS ~98k | ~44 MB/h |

Si una emisora falla, la app salta automáticamente al stream de respaldo y sigue
reintentando con espera creciente (1, 2, 4, 8, 15 s).

**Nota sobre el consumo:** los 128k de La Mega los fija la emisora, no la app;
no hay forma de pedir menos por ese stream. El respaldo HLS (~98k) gasta algo
menos, así que si te preocupan los datos, en el móvil puedes dejar sonando La
Mega y verás en la cabecera cuántos MB va gastando de verdad.

---

## Web (`web/`)

Cinco archivos, ~50 KB en total, cero dependencias externas.

Abrir en local:

```bash
python -m http.server 5510 --directory C:/Users/PORCEN038/RadioCo/web
```

Luego `http://localhost:5510`.

Para usarla desde el móvil basta subir la carpeta `web/` a cualquier hosting
estático con HTTPS (Cloudways, Netlify, GitHub Pages, un FTP...). Con HTTPS,
Chrome en Android ofrece **Añadir a pantalla de inicio** y queda como una app
(pantalla completa, icono propio, controles en la pantalla de bloqueo).

No se puede publicar como Artifact de Claude: su CSP bloquea las peticiones a
dominios externos y ahí es justo donde vive el audio.

Incluye:
- contador de datos del día (estimado por bitrate) con botón de reinicio,
- volumen persistente,
- temporizador de apagado,
- reconexión automática y cambio al stream de respaldo,
- controles del sistema vía Media Session API,
- service worker que **solo** cachea la propia página, nunca el audio.

`make_icons.py` regenera los iconos si quieres cambiar el diseño.

---

## Android (`android/`)

App nativa en Kotlin con Media3/ExoPlayer. APK ya compilado:

```
android/app/build/outputs/apk/release/app-release.apk   (2,8 MB)
```

Copia lista para pasar al móvil: `~/Downloads/RadioCO-1.0.apk`

Instalar por cable:

```bash
adb install -r C:/Users/PORCEN038/Downloads/RadioCO-1.0.apk
```

O copiar el APK al teléfono y abrirlo (hay que permitir "instalar apps
desconocidas" para el gestor de archivos).

Qué hace:
- reproduce en segundo plano con servicio en primer plano (`mediaPlayback`),
- notificación y controles en la pantalla de bloqueo (MediaSession),
- respeta el foco de audio (llamadas, otras apps) y pausa al quitar auriculares,
- **mide los datos reales** consumidos por la app con `TrafficStats`, por día,
- temporizador de apagado que sobrevive a cerrar la pantalla,
- buffer corto (10 s) para arrancar rápido sin descargar audio que se va a tirar,
- reconexión automática con cambio de stream, igual que la web.

Permisos: solo INTERNET, red, wakelock, servicio en primer plano y notificaciones.
Ninguna librería de anuncios ni de analítica.

### Publicar el repositorio (una sola vez)

```powershell
gh auth login
C:\Users\PORCEN038\RadioCo\subir-a-github.ps1
```

Crea el repositorio público, sube el código y publica la primera release con el
APK. Si tu usuario de GitHub no es `CristianMR06`, el script lo detecta, corrige
`GITHUB_REPO` en `app/build.gradle.kts` y recompila solo.

### Actualizaciones desde la propia app

La app trae un botón **Buscar actualizaciones**. Mira las *releases* de este
repositorio en GitHub, y si hay una más nueva la descarga y lanza el instalador.
También comprueba sola al abrir, como mucho una vez cada 6 horas.

El convenio es: **la etiqueta de cada release tiene que ser `v<versionCode>`**
(`v2`, `v3`, `v4`...), y el APK va adjunto como asset. Ese número es lo que la app
compara con el suyo.

Publicar una versión nueva es un comando, desde una consola normal:

```powershell
C:\Users\PORCEN038\RadioCo\publicar-actualizacion.ps1 -Version "1.2" -Notas "Añadida Olímpica Bogotá"
```

Ese script sube el `versionCode`, compila el APK firmado, hace commit y push, y
crea la release en GitHub con la etiqueta correcta. Después, en el móvil: abrir la
app y pulsar el botón.

La primera vez Android pedirá permiso para que Radio CO instale aplicaciones; el
botón te lleva directo a esa pantalla de ajustes.

### Firma

El APK va firmado con una clave propia:

```
C:\Users\PORCEN038\.keystores\radioco-release.jks
```

La contraseña está en `android/keystore.properties`, que **no se sube al
repositorio** (está en `.gitignore`), y también en
`C:\Users\PORCEN038\.keystores\radioco-pass.txt`.

> **Haz copia de seguridad de esos dos archivos.** Android solo instala una
> actualización si va firmada con la misma clave que la versión instalada. Si
> pierdes el keystore, la única salida es desinstalar la app de cada móvil y
> empezar de cero con una clave nueva.

### Recompilar

**Importante:** Gradle no arranca dentro de Claude Desktop. El proceso corre en un
contenedor de Windows donde `Pipe.open()` de la JVM falla
(`SocketException: Invalid argument: connect`), y Gradle lo necesita para hablar
con su daemon. Se compila desde una consola normal:

```bash
C:\Users\PORCEN038\RadioCo\android\build-apk.bat
```

O desde Android Studio abriendo la carpeta `android/`.

El APK release va firmado con la clave de *debug* a propósito, para poder
instalarlo directamente. Si algún día lo quieres subir a Play Store, hay que
crear una clave propia y cambiar `signingConfig` en `app/build.gradle.kts`.

### Añadir más emisoras

Un solo sitio en cada plataforma:

- Android: la lista `Stations.all` en
  `android/app/src/main/java/com/radioco/app/Stations.kt`
- Web: el array `STATIONS` al principio del `<script>` de `web/index.html`

Cada emisora acepta varios streams; el primero es el que se usa y el resto son
respaldos por orden.

---

## Entorno

Compilado con Gradle 8.7, AGP 8.6.0, Kotlin 1.9.0, Media3 1.4.1,
JDK 17 de Android Studio, compileSdk 34, minSdk 24 (Android 7 en adelante).
