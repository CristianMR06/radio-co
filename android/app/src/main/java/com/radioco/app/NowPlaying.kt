package com.radioco.app

import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale

/**
 * De dónde sale el nombre de la canción, que es distinto en cada emisora:
 *
 * - La Mega (Mediastream) lo manda dentro del propio stream, en los metadatos
 *   ICY. Eso lo pilla ExoPlayer solo y llega por Player.Listener.onMetadata.
 * - Olímpica (StreamTheWorld) deja los bloques ICY vacíos, pero publica el dato
 *   en la API pública de Triton. Hay que preguntarle cada cierto tiempo.
 */
object NowPlaying {

    private const val TRITON =
        "https://np.tritondigital.com/public/nowplaying?mountName=%s&numberToFetch=1&eventType=track"
    private const val TIMEOUT_MS = 10_000

    /** Cada cuánto volver a preguntar, si no sabemos lo que dura la canción. */
    const val POLL_POR_DEFECTO_MS = 25_000L
    private const val POLL_MIN_MS = 15_000L
    private const val POLL_MAX_MS = 60_000L

    data class Info(
        /** Ya compuesto ("Artista · Título"), para mostrar en pantalla. */
        val texto: String?,
        /** Las partes sin juntar, que es lo que necesita la búsqueda de letra. */
        val artista: String?,
        val titulo: String?,
        /** Momento en que la EMISORA empezó la canción (epoch ms), 0 si no viene. */
        val cueStartWall: Long,
        val duracionMs: Long,
        val siguienteConsultaMs: Long
    )

    private val VACIA = Info(null, null, null, 0L, 0L, POLL_POR_DEFECTO_MS)

    /**
     * El separador " · " queda bien en pantalla pero estorba al buscar.
     * "Ryan Castro · La Villa" -> "Ryan Castro La Villa"
     */
    fun consultaBusqueda(cancion: String): String =
        cancion.replace(" · ", " ").trim()

    private val CUE_TITLE = Regex("""cue_title"><!\[CDATA\[(.*?)]]>""", RegexOption.DOT_MATCHES_ALL)
    private val ARTIST = Regex("""track_artist_name"><!\[CDATA\[(.*?)]]>""", RegexOption.DOT_MATCHES_ALL)
    private val DURACION = Regex("""cue_time_duration"><!\[CDATA\[(\d+)]]>""")
    private val INICIO = Regex("""cue_time_start"><!\[CDATA\[(\d+)]]>""")

    /** Bloqueante: llamar desde un hilo secundario. */
    fun consultarTriton(mount: String): Info {
        var conn: HttpURLConnection? = null
        try {
            conn = (URL(String.format(TRITON, mount)).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = TIMEOUT_MS
                readTimeout = TIMEOUT_MS
                setRequestProperty("User-Agent", "RadioCO/${BuildConfig.VERSION_NAME}")
            }
            if (conn.responseCode != 200) return VACIA
            val xml = conn.inputStream.bufferedReader().use { it.readText() }

            val titulo = limpiar(CUE_TITLE.find(xml)?.groupValues?.get(1))
            val artista = limpiar(ARTIST.find(xml)?.groupValues?.get(1))

            // volver a preguntar justo cuando se acabe la canción, no antes
            val duracion = DURACION.find(xml)?.groupValues?.get(1)?.toLongOrNull() ?: 0L
            val inicio = INICIO.find(xml)?.groupValues?.get(1)?.toLongOrNull() ?: 0L
            val espera = if (duracion > 0 && inicio > 0) {
                ((inicio + duracion) - System.currentTimeMillis() + 3_000)
                    .coerceIn(POLL_MIN_MS, POLL_MAX_MS)
            } else {
                POLL_POR_DEFECTO_MS
            }

            return Info(
                texto = componer(artista, titulo),
                artista = artista,
                titulo = titulo,
                cueStartWall = inicio,
                duracionMs = duracion,
                siguienteConsultaMs = espera
            )
        } catch (e: IOException) {
            return VACIA
        } catch (e: Exception) {
            return VACIA
        } finally {
            conn?.disconnect()
        }
    }

    private fun componer(artista: String?, titulo: String?): String? {
        val a = limpiar(artista)
        val t = limpiar(titulo)
        return when {
            a != null && t != null -> "${bonito(a)} · ${bonito(t)}"
            t != null -> bonito(t)
            a != null -> bonito(a)
            else -> null
        }
    }

    /** Título tal y como llega en los metadatos ICY: "Artista - Cancion". */
    fun deIcy(raw: String?): String? {
        val partes = deIcyPartes(raw)
        return componer(partes.first, partes.second) ?: limpiar(raw)?.let { bonito(it) }
    }

    /** Las mismas partes, sin juntar, para poder buscar la letra. */
    fun deIcyPartes(raw: String?): Pair<String?, String?> {
        val s = limpiar(raw) ?: return null to null
        val corte = s.indexOf(" - ")
        if (corte <= 0) return null to s
        val a = s.substring(0, corte).trim()
        val t = s.substring(corte + 3).trim()
        return if (a.isNotEmpty() && t.isNotEmpty()) a to t else null to s
    }

    private val BASURA = setOf(
        "", "-", "--", "unknown", "unknown - unknown", "null", "n/a",
        "advertisement", "publicidad", "comerciales", "no title", "sin titulo"
    )

    private fun limpiar(s: String?): String? {
        val v = s?.trim()?.trim('-', ' ') ?: return null
        if (v.lowercase(Locale.ROOT) in BASURA) return null
        if (v.length < 2) return null
        return v
    }

    /**
     * Las emisoras suelen mandarlo TODO EN MAYUSCULAS y grita mucho.
     * Solo se toca si viene entero en mayúsculas; si no, se respeta tal cual.
     */
    private fun bonito(s: String): String {
        if (s != s.uppercase(Locale.ROOT)) return s
        return s.split(' ').joinToString(" ") { p ->
            if (p.length <= 1) p
            else p[0].uppercase(Locale.ROOT) + p.substring(1).lowercase(Locale.ROOT)
        }
    }
}
