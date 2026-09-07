package com.radioco.app

import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.Locale

/**
 * Letras desde LRCLIB (lrclib.net): base comunitaria, gratis y sin clave.
 * Devuelve formato LRC, con marca de tiempo por línea, que es lo que permite
 * el modo karaoke. Si solo hay letra plana, se muestra sin resaltar.
 */
object Lyrics {

    private const val BUSCAR = "https://lrclib.net/api/search?"
    private const val TIMEOUT_MS = 12_000

    data class Linea(val tMs: Long, val texto: String)

    data class Letra(
        val lineas: List<Linea>,
        val sincronizada: Boolean,
        val referencia: String
    )

    sealed class Resultado {
        data class Ok(val letra: Letra) : Resultado()
        object NoEncontrada : Resultado()
        data class Error(val motivo: String) : Resultado()
    }

    // ------------------------------------------------------- limpieza de titulos

    private val PARENTESIS = Regex(
        """\s*\((?:en\s+vivo|live|audio|video|official[^)]*)\)\s*""",
        RegexOption.IGNORE_CASE
    )
    private val CORCHETES = Regex("""\s*\[[^]]*]\s*""")
    private val ANYO_FINAL = Regex("""\s+(19|20)\d\d\s*$""")
    private val ESPACIOS = Regex("""\s+""")

    /** Las emisoras pegan el año al título ("La Enredadera 2012") y eso no busca. */
    fun limpiarTitulo(t: String): String =
        t.replace(PARENTESIS, " ")
            .replace(CORCHETES, " ")
            .replace(ANYO_FINAL, "")
            .replace(ESPACIOS, " ")
            .trim()

    private val SEPARADOR_ARTISTAS = Regex(
        """\s*(?:,|&|/|\bfeat\.?\b|\bft\.?\b|\bcon\b)\s*""",
        RegexOption.IGNORE_CASE
    )

    /** "Ryan Castro, Kapo, Gangsta" -> "Ryan Castro" */
    fun primerArtista(a: String): String =
        a.split(SEPARADOR_ARTISTAS).firstOrNull()?.trim().orEmpty().ifEmpty { a.trim() }

    // ----------------------------------------------------------------- consulta

    /**
     * Bloqueante: llamar desde un hilo secundario.
     * Prueba varias formas del título porque las emisoras los escriben regular
     * (mayúsculas, erratas, el año pegado, varios artistas juntos).
     */
    fun buscar(artista: String, titulo: String, duracionMs: Long): Resultado {
        val intentos = listOf(
            "artist_name=${enc(artista)}&track_name=${enc(titulo)}",
            "artist_name=${enc(primerArtista(artista))}&track_name=${enc(limpiarTitulo(titulo))}",
            "q=${enc(primerArtista(artista) + " " + limpiarTitulo(titulo))}"
        )

        var huboError: String? = null
        for (query in intentos) {
            when (val r = pedir(BUSCAR + query, duracionMs)) {
                is Resultado.Ok -> return r
                is Resultado.Error -> huboError = r.motivo
                Resultado.NoEncontrada -> Unit   // seguimos con el siguiente intento
            }
        }
        return huboError?.let { Resultado.Error(it) } ?: Resultado.NoEncontrada
    }

    private fun enc(s: String): String = URLEncoder.encode(s, "UTF-8")

    private fun pedir(url: String, duracionMs: Long): Resultado {
        var conn: HttpURLConnection? = null
        try {
            conn = (URL(url).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = TIMEOUT_MS
                readTimeout = TIMEOUT_MS
                setRequestProperty("User-Agent", "RadioCO/${BuildConfig.VERSION_NAME}")
            }
            if (conn.responseCode != 200) return Resultado.NoEncontrada

            val cuerpo = conn.inputStream.bufferedReader().use { it.readText() }
            val lista = JSONArray(cuerpo)
            val mejor = elegirVersion(lista, duracionMs) ?: return Resultado.NoEncontrada

            val referencia = listOfNotNull(
                mejor.optString("artistName").takeIf { it.isNotBlank() },
                mejor.optString("trackName").takeIf { it.isNotBlank() }
            ).joinToString(" · ")

            val sincronizada = mejor.optString("syncedLyrics").takeIf { it.isNotBlank() }
            if (sincronizada != null) {
                val lineas = parsearLrc(sincronizada)
                if (lineas.isNotEmpty()) {
                    return Resultado.Ok(Letra(lineas, true, referencia))
                }
            }

            val plana = mejor.optString("plainLyrics").takeIf { it.isNotBlank() }
            if (plana != null) {
                val lineas = plana.split("\n").map { Linea(-1L, it.trim()) }
                if (lineas.isNotEmpty()) {
                    return Resultado.Ok(Letra(lineas, false, referencia))
                }
            }
            return Resultado.NoEncontrada
        } catch (e: IOException) {
            return Resultado.Error("sin conexión")
        } catch (e: Exception) {
            return Resultado.NoEncontrada
        } finally {
            conn?.disconnect()
        }
    }

    /**
     * La duración que da la emisora sirve para acertar con la versión correcta
     * (single, remix, en directo...) entre todos los resultados.
     */
    private fun elegirVersion(lista: JSONArray, duracionMs: Long): JSONObject? {
        if (lista.length() == 0) return null

        val todos = (0 until lista.length()).mapNotNull { lista.optJSONObject(it) }
        val conSync = todos.filter { it.optString("syncedLyrics").isNotBlank() }
        val pool = conSync.ifEmpty { todos }
        if (pool.isEmpty()) return null
        if (duracionMs <= 0L) return pool.first()

        return pool.minByOrNull {
            val d = (it.optDouble("duration", 0.0) * 1000).toLong()
            kotlin.math.abs(d - duracionMs)
        }
    }

    // -------------------------------------------------------------- parseo LRC

    private val MARCA = Regex("""\[(\d+):(\d+(?:[.:]\d+)?)]""")
    private val CUALQUIER_ETIQUETA = Regex("""\[[^]]*]""")

    fun parsearLrc(txt: String): List<Linea> {
        val salida = mutableListOf<Linea>()
        for (l in txt.split("\n")) {
            val marcas = MARCA.findAll(l).toList()
            if (marcas.isEmpty()) continue          // [ar:], [by:]... se ignoran
            val texto = l.replace(CUALQUIER_ETIQUETA, "").trim()
            // Muchos LRC traen marcas sin texto para los silencios y el final.
            // Si se guardan, la linea "actual" acaba siendo una vacia y parece
            // que el karaoke se ha colgado. Mejor dejar la ultima con letra.
            if (texto.isBlank()) continue
            for (m in marcas) {
                val min = m.groupValues[1].toLongOrNull() ?: continue
                val seg = m.groupValues[2].replace(',', '.').toDoubleOrNull() ?: continue
                salida += Linea(min * 60_000 + (seg * 1000).toLong(), texto)
            }
        }
        return salida.sortedBy { it.tMs }
    }

    // ------------------------------------------------- comparar dos titulos

    private val NO_ALFANUM = Regex("""[^\p{L}\p{N}]+""")

    private fun normalizar(s: String): String =
        s.lowercase(Locale.ROOT)
            .replace('á', 'a').replace('é', 'e').replace('í', 'i')
            .replace('ó', 'o').replace('ú', 'u').replace('ü', 'u').replace('ñ', 'n')
            .replace(NO_ALFANUM, "")

    /**
     * Si la referencia de LRCLIB dice practicamente lo mismo que el titulo de
     * la emisora, no hace falta ensenar las dos. Se compara el titulo, y los
     * artistas se dan por buenos cuando uno es el principio del otro
     * ("Ryan Castro" vs "Ryan Castro Ft Kapo").
     */
    fun mismaCancion(a: String, b: String): Boolean {
        val pa = a.split(" · ")
        val pb = b.split(" · ")
        if (pa.size < 2 || pb.size < 2) return normalizar(a) == normalizar(b)

        val tituloA = normalizar(pa.last())
        val tituloB = normalizar(pb.last())
        if (tituloA != tituloB) return false

        val artistaA = normalizar(pa.first())
        val artistaB = normalizar(pb.first())
        return artistaA.startsWith(artistaB) || artistaB.startsWith(artistaA)
    }

    // --------------------------------------------------------------- formato

    fun mmss(ms: Long): String {
        val s = (ms / 1000).coerceAtLeast(0)
        return String.format(Locale.US, "%d:%02d", s / 60, s % 60)
    }
}
