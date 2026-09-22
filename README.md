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

### Publicada en

```
https://cristianmr06.github.io/radio-co/
```

GitHub Pages sirve el repositorio desde la raíz, y la radio vive en `web/`, así
que hay un `index.html` en la raíz que redirige. La dirección larga
(`/radio-co/web/`) también funciona.

Se actualiza sola con cada `git push`: no hay que hacer nada más. Con HTTPS,
Chrome en Android ofrece **Añadir a pantalla de inicio** y queda como una app
(pantalla completa, icono propio, controles en la pantalla de bloqueo).

No se puede publicar como Artifact de Claude: su CSP bloquea las peticiones a
dominios externos y ahí es justo donde vive el audio.

Incluye:
- reloj y fecha,
- contador de datos del día (estimado por bitrate) con botón de reinicio,
- qué suena ahora en Olímpica, y su letra en modo karaoke,
- reconexión automática y cambio al stream de respaldo,
- controles del sistema vía Media Session API,
- service worker que sirve la página **primero desde la red**, para que una
  versión nueva no se quede atrapada en la caché.

`make_icons.py` regenera los iconos si quieres cambiar el diseño.

---

## Android (`android/`)

App nativa en Kotlin con Media3/ExoPlayer. APK ya compilado:

```
android/app/build/outputs/apk/release/app-release.apk   (2,7 MB)
```

O directamente desde https://github.com/CristianMR06/radio-co/releases/latest

Instalar por cable:

```bash
adb install -r C:/Users/PORCEN038/Downloads/RadioCO-1.6.apk
```

O copiar el APK al teléfono y abrirlo (hay que permitir "instalar apps
desconocidas" para el gestor de archivos).

Qué hace:
- reproduce en segundo plano con servicio en primer plano (`mediaPlayback`),
- notificación y controles en la pantalla de bloqueo (MediaSession),
- respeta el foco de audio (llamadas, otras apps) y pausa al quitar auriculares,
- **mide los datos reales** consumidos por la app con `TrafficStats`, por día,
- qué suena ahora en las dos emisoras, con letra y modo karaoke,
- buffer corto (10 s) para arrancar rápido sin descargar audio que se va a tirar,
- reconexión automática con cambio de stream, igual que la web.

Permisos: solo INTERNET, red, wakelock, servicio en primer plano y notificaciones.
Ninguna librería de anuncios ni de analítica.

### Letra y modo karaoke

Cuando se sabe qué suena, aparece un icono de letra en la fila de la emisora.
Las letras vienen de **LRCLIB** (`lrclib.net`): base comunitaria, gratis, sin
clave y con CORS abierto, así que sirve igual para la app y para la web. Cuando
la letra viene en formato LRC (con marca de tiempo por línea) se resalta la
línea actual y se desplaza sola; si solo hay letra plana, se muestra sin
resaltar y se dice por qué.

Dos detalles que costaron trabajo:

**Los títulos de las emisoras hay que limpiarlos.** Vienen en mayúsculas, con el
año pegado (`LA ENREDADERA 2012`), con varios artistas juntos y a veces con
erratas. Se prueban tres formas del título, de la más literal a la más laxa, y
se usa la duración que da la emisora para acertar con la versión correcta entre
los resultados. Aun así, un título mal escrito en origen no se encuentra.

**El sincronismo es el problema de verdad.** Los metadatos van en el reloj de la
emisora, no en el tuyo: tú oyes el audio entre 10 y 30 segundos más tarde por el
buffer del reproductor y del CDN. Hay dos casos:

- **Con título en banda (ICY) se sincroniza solo.** media3 marca el bloque ICY
  con el instante del final de lo bufereado y el `MetadataRenderer` lo entrega
  cuando la reproducción llega ahí: `onMetadata` salta justo cuando empieza a
  oírse la canción, así que el ancla es `currentPosition` **a secas**. Sumarle
  `totalBufferedDuration` (que es lo que se hacía al principio) adelantaba el
  ancla unos 30 s: no se resaltaba nada al empezar cada canción y después iba
  atrasado, y los botones de ±1 s parecían no hacer nada.
- **Sin título en banda hay que estimar.** Se parte de una latencia de 20 s y
  hay botones de ±1 s; el ajuste se guarda por emisora.
- Las dos emisoras mandan ICY (Olímpica también, aunque su reproductor web use
  la API de Triton). Cuando el stream da el título, **Triton se aparta**: si se
  mezclan, el sincronismo cambia de base de una canción a otra y el ajuste
  manual deja de cuadrar.

Si la radio rebuferea, el karaoke se descuadra hasta la siguiente canción.

### Mis medios (archivos propios del móvil)

Además de las emisoras, la app reproduce dos archivos tuyos: un audio y un
vídeo. La idea es que si siempre pones los mismos, no tiene sentido que gasten
datos cada vez.

**No se descargan de ningún sitio.** Los eliges con el selector del sistema y la
app se queda con una copia en su carpeta:

```
Android/data/com.radioco.app/files/medios/
```

A partir de ahí todo sale del disco: la reproducción **no toca la red ni una
sola vez**. Se copia en vez de guardar solo una referencia para que da igual que
luego muevas o borres el original; la copia sobrevive a las actualizaciones de
la app y solo se va si desinstalas o pulsas «borrar copia».

Detalles de implementación:

- El audio va por el mismo `PlaybackService` que las emisoras, así que suena en
  segundo plano y con controles en la pantalla de bloqueo.
- El vídeo tiene pantalla propia (`VideoActivity`), a pantalla completa y sin
  dejar que se apague. Al arrancar, la radio se para **sola**: no hay
  coordinación entre los dos reproductores, lo resuelve el foco de audio.
- El `mediaId` de un medio es `media:<ranura>`, con prefijo para que
  `Stations.parseStation()` devuelva `null` y ni Triton ni ICY se metan donde no
  les toca.
- La copia se escribe a un `.parcial` y solo se renombra al terminar, para que
  un fallo a medias no deje un fichero roto con pinta de bueno.
- La pantalla del vídeo **esconde los botones de "anterior" y "siguiente"**. Con
  un solo medio, "anterior" no va a otra pista: salta al segundo 0, que es justo
  lo contrario de lo que se busca al rebobinar. Quedan retroceder 10 s y avanzar
  30 s, más una flecha de volver que aparece con el resto de controles.
- Se guarda por dónde ibas (`medio.<id>.posicion`), porque el reproductor se
  suelta al salir de la pantalla y si no el vídeo empezaba de cero cada vez.
- La franja de abajo está en `systemGestureExclusionRects`: sin eso, arrastrar
  la barra desde cerca del borde izquierdo lo entiende Android como el gesto de
  "atrás" y cierra la pantalla.

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
