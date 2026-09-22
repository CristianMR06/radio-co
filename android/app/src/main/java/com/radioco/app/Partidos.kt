package com.radioco.app

import android.content.Context
import android.text.format.DateFormat
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * El último partido y el siguiente de los dos equipos de la casa.
 *
 * Los datos salen de TheSportsDB, que tiene tanto LaLiga como la liga
 * colombiana y deja consultar sin registrarse (la clave "3" es la pública de
 * pruebas). Devuelve un partido por consulta, que es justo lo que hace falta.
 *
 * Se guarda lo último que se supo en las preferencias y solo se vuelve a
 * preguntar cada varias horas: son unos pocos KB, pero esta app existe
 * precisamente para no gastar datos porque sí.
 */
object Partidos {

    private const val API = "https://www.thesportsdb.com/api/v1/json/3"
    private const val TIMEOUT_MS = 10_000

    /** Cada cuánto se vuelve a preguntar si no ha cambiado nada. */
    private const val VIGENCIA_MS = 3 * 60 * 60 * 1000L

    data class Equipo(
        val id: String,
        val idSportsDb: String,
        val nombre: String,
        val corto: String,
        val accent: Int
    )

    val equipos = listOf(
        Equipo("madrid", "133738", "Real Madrid", "RM", 0xFFDCE3EA.toInt()),
        Equipo("tolima", "137609", "Deportes Tolima", "DT", 0xFFF0B429.toInt())
    )

    /**
     * Un partido ya orientado desde el punto de vista del equipo: [rival] es
     * siempre el otro, y [marcador] se lee "los nuestros - los otros".
     */
    data class Partido(
        val rival: String,
        val enCasa: Boolean,
        val marcador: String?,
        /** Epoch ms del comienzo, 0 si la fuente no lo dice. */
        val cuando: Long,
        val torneo: String,
        val aplazado: Boolean
    )

    data class Ficha(val ultimo: Partido?, val proximo: Partido?, val consultado: Long) {
        val vacia: Boolean get() = ultimo == null && proximo == null
    }

    // --------------------------------------------------------------- consulta

    /** Bloqueante: llamar desde un hilo secundario. */
    fun consultar(ctx: Context, eq: Equipo, forzar: Boolean = false): Ficha {
        val guardada = leerCache(ctx, eq)
        if (!forzar && guardada != null && vigente(guardada)) return guardada

        val ultimo = pedirUno(API + "/eventslast.php?id=" + eq.idSportsDb, "results", eq)
        val proximo = pedirUno(API + "/eventsnext.php?id=" + eq.idSportsDb, "events", eq)

        // si la red falla nos quedamos con lo que ya teníamos: mejor un dato
        // de ayer que un hueco en blanco
        if (ultimo == null && proximo == null && guardada != null) return guardada

        val ficha = Ficha(ultimo, proximo, System.currentTimeMillis())
        guardarCache(ctx, eq, ficha)
        return ficha
    }

    /**
     * Además de por tiempo, caduca cuando el próximo partido ya debería haber
     * terminado: es el momento en que hay resultado nuevo que contar.
     */
    private fun vigente(f: Ficha): Boolean {
        val ahora = System.currentTimeMillis()
        if (ahora - f.consultado > VIGENCIA_MS) return false
        val prox = f.proximo?.cuando ?: return true
        if (prox == 0L) return true
        return ahora < prox + 2 * 60 * 60 * 1000L
    }

    private fun pedirUno(url: String, campo: String, eq: Equipo): Partido? {
        var conn: HttpURLConnection? = null
        try {
            conn = (URL(url).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = TIMEOUT_MS
                readTimeout = TIMEOUT_MS
                setRequestProperty("User-Agent", "RadioCO/" + BuildConfig.VERSION_NAME)
            }
            if (conn.responseCode != 200) return null
            val cuerpo = conn.inputStream.bufferedReader().use { it.readText() }
            val lista = JSONObject(cuerpo).optJSONArray(campo) ?: return null
            if (lista.length() == 0) return null
            return deEvento(lista.getJSONObject(0), eq)
        } catch (e: IOException) {
            return null
        } catch (e: Exception) {
            return null
        } finally {
            conn?.disconnect()
        }
    }

    private fun deEvento(e: JSONObject, eq: Equipo): Partido? {
        val casa = e.optString("idHomeTeam") == eq.idSportsDb
        val rival = (if (casa) e.optString("strAwayTeam") else e.optString("strHomeTeam"))
            .takeIf { it.isNotBlank() } ?: return null

        val golesCasa = e.optString("intHomeScore").toIntOrNull()
        val golesFuera = e.optString("intAwayScore").toIntOrNull()
        val marcador = if (golesCasa != null && golesFuera != null) {
            if (casa) golesCasa.toString() + " - " + golesFuera
            else golesFuera.toString() + " - " + golesCasa
        } else null

        return Partido(
            rival = rival,
            enCasa = casa,
            marcador = marcador,
            cuando = momento(e),
            torneo = e.optString("strLeague").takeIf { it.isNotBlank() } ?: "",
            aplazado = e.optString("strPostponed").equals("yes", true)
        )
    }

    /**
     * strTimestamp viene en UTC. Se convierte a epoch y cada móvil lo pinta en
     * su propia hora, que es lo que interesa: el Tolima juega de noche en
     * Colombia y de madrugada en España.
     */
    private fun momento(e: JSONObject): Long {
        val sello = e.optString("strTimestamp").takeIf { it.isNotBlank() }
        if (sello != null) {
            val fmt = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US)
            fmt.timeZone = TimeZone.getTimeZone("UTC")
            try {
                return fmt.parse(sello)?.time ?: 0L
            } catch (ex: Exception) {
                // se prueba con la fecha suelta
            }
        }
        val dia = e.optString("dateEvent").takeIf { it.isNotBlank() } ?: return 0L
        val hora = e.optString("strTime").takeIf { it.isNotBlank() } ?: "00:00:00"
        val fmt = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)
        fmt.timeZone = TimeZone.getTimeZone("UTC")
        return try {
            fmt.parse(dia + " " + hora.take(8))?.time ?: 0L
        } catch (ex: Exception) {
            0L
        }
    }

    // ------------------------------------------------------------------ caché

    private fun clave(eq: Equipo) = "partidos." + eq.id

    fun leerCache(ctx: Context, eq: Equipo): Ficha? {
        val bruto = DataMeter.prefs(ctx).getString(clave(eq), null) ?: return null
        return try {
            val o = JSONObject(bruto)
            Ficha(
                ultimo = dePartidoJson(o.optJSONObject("u")),
                proximo = dePartidoJson(o.optJSONObject("p")),
                consultado = o.optLong("t")
            )
        } catch (e: Exception) {
            null
        }
    }

    private fun guardarCache(ctx: Context, eq: Equipo, f: Ficha) {
        val o = JSONObject()
        o.put("t", f.consultado)
        f.ultimo?.let { o.put("u", aJson(it)) }
        f.proximo?.let { o.put("p", aJson(it)) }
        DataMeter.prefs(ctx).edit().putString(clave(eq), o.toString()).apply()
    }

    private fun aJson(p: Partido): JSONObject = JSONObject()
        .put("riv", p.rival)
        .put("casa", p.enCasa)
        .put("mar", p.marcador ?: JSONObject.NULL)
        .put("cnd", p.cuando)
        .put("tor", p.torneo)
        .put("apl", p.aplazado)

    private fun dePartidoJson(o: JSONObject?): Partido? {
        if (o == null) return null
        return Partido(
            rival = o.optString("riv"),
            enCasa = o.optBoolean("casa"),
            marcador = o.optString("mar").takeIf { it.isNotBlank() && it != "null" },
            cuando = o.optLong("cnd"),
            torneo = o.optString("tor"),
            aplazado = o.optBoolean("apl")
        )
    }

    // ------------------------------------------------------------- para pintar

    /**
     * TheSportsDB nombra las competiciones en ingles y muy largo. Con el nombre
     * completo la linea se parte en tres en un movil normal.
     */
    fun torneoCorto(nombre: String): String = when {
        nombre.contains("La Liga", true) -> "LaLiga"
        nombre.contains("Primera A", true) || nombre.contains("DIMAYOR", true) -> "Primera A"
        nombre.contains("Champions", true) -> "Champions"
        nombre.contains("Libertadores", true) -> "Libertadores"
        nombre.contains("Sudamericana", true) -> "Sudamericana"
        nombre.contains("Copa del Rey", true) -> "Copa del Rey"
        nombre.contains("Copa Colombia", true) -> "Copa Colombia"
        nombre.contains("Supercopa", true) -> "Supercopa"
        nombre.contains("Friendl", true) -> "Amistoso"
        nombre.contains("World Cup", true) -> "Mundial de Clubes"
        else -> nombre
    }

    /** "sáb 27 sep · 18:05", con la hora del móvil. */
    fun cuandoTexto(ctx: Context, p: Partido): String {
        if (p.cuando == 0L) return ""
        val fecha = SimpleDateFormat("EEE d MMM", Locale("es")).format(Date(p.cuando))
        val hora = DateFormat.getTimeFormat(ctx).format(Date(p.cuando))
        return fecha + " · " + hora
    }
}
