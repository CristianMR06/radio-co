package com.radioco.app

import android.app.DownloadManager
import android.content.Context
import android.database.Cursor
import android.net.Uri
import android.provider.OpenableColumns
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.util.UnstableApi
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

enum class TipoMedio(val id: String) {
    AUDIO("audio"),
    VIDEO("video");

    companion object {
        fun porId(id: String?) = entries.firstOrNull { it.id == id }
    }
}

/**
 * Un archivo propio guardado en el móvil. No lleva URL: una vez está aquí, la
 * reproducción no vuelve a tocar la red.
 */
data class Medio(
    val id: String,
    val tipo: TipoMedio,
    val nombre: String,
    val creado: Long
)

/**
 * Mis medios: las canciones y los vídeos del usuario, en el móvil.
 *
 * Se eligen con el selector del sistema o se bajan de un enlace **una vez**, y
 * la app se queda con una copia en su carpeta. A partir de ahí todo sale del
 * disco: **nunca** tocan la red. Esa era la idea — son siempre los mismos y no
 * tiene sentido que gasten datos cada vez.
 *
 * La copia vive en getExternalFilesDir, que es privado de la app pero sobrevive
 * a las actualizaciones. Solo se va si desinstalas o le das a "borrar copia".
 */
@UnstableApi
object Medios {

    /** El índice de lo que hay guardado, en las preferencias. */
    private const val CLAVE_LISTA = "medios.lista"

    fun accent(t: TipoMedio): Int = when (t) {
        TipoMedio.AUDIO -> 0xFFA78BFA.toInt()
        TipoMedio.VIDEO -> 0xFF34D399.toInt()
    }

    fun mimes(t: TipoMedio): Array<String> = when (t) {
        TipoMedio.AUDIO -> arrayOf("audio/*")
        TipoMedio.VIDEO -> arrayOf("video/*")
    }

    // ------------------------------------------------------------- el índice

    fun lista(ctx: Context): List<Medio> {
        migrarRanurasViejas(ctx)
        val bruto = DataMeter.prefs(ctx).getString(CLAVE_LISTA, null) ?: return emptyList()
        return try {
            val arr = JSONArray(bruto)
            val fuera = ArrayList<Medio>(arr.length())
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                val tipo = TipoMedio.porId(o.optString("tipo")) ?: continue
                val id = o.optString("id").takeIf { it.isNotBlank() } ?: continue
                fuera.add(
                    Medio(
                        id = id,
                        tipo = tipo,
                        nombre = o.optString("nombre").takeIf { it.isNotBlank() } ?: id,
                        creado = o.optLong("creado")
                    )
                )
            }
            // solo lo que sigue estando en disco: si el archivo desapareció,
            // la ficha no sirve de nada
            fuera.filter { fichero(ctx, it).length() > 0L }
        } catch (e: Exception) {
            emptyList()
        }
    }

    fun lista(ctx: Context, tipo: TipoMedio): List<Medio> = lista(ctx).filter { it.tipo == tipo }

    fun porId(ctx: Context, id: String?): Medio? =
        if (id == null) null else lista(ctx).firstOrNull { it.id == id }

    private fun guardarLista(ctx: Context, medios: List<Medio>) {
        val arr = JSONArray()
        for (m in medios) {
            arr.put(
                JSONObject()
                    .put("id", m.id)
                    .put("tipo", m.tipo.id)
                    .put("nombre", m.nombre)
                    .put("creado", m.creado)
            )
        }
        DataMeter.prefs(ctx).edit().putString(CLAVE_LISTA, arr.toString()).apply()
    }

    /** Alta en el índice. El archivo ya tiene que estar en su sitio. */
    private fun registrar(ctx: Context, m: Medio) {
        val actual = lista(ctx).filter { it.id != m.id }
        guardarLista(ctx, actual + m)
    }

    fun borrar(ctx: Context, m: Medio) {
        fichero(ctx, m).delete()
        guardarLista(ctx, lista(ctx).filter { it.id != m.id })
        DataMeter.prefs(ctx).edit().remove("medio." + m.id + ".posicion").apply()
    }

    /**
     * Hasta la 2.2 solo había dos huecos fijos, "audio" y "video", con el
     * nombre en prefs. Se convierten en fichas de la lista **conservando el
     * mismo id**, así que el archivo que ya está descargado en el móvil no hay
     * que moverlo ni volver a bajarlo.
     */
    private fun migrarRanurasViejas(ctx: Context) {
        val prefs = DataMeter.prefs(ctx)
        if (prefs.getBoolean("medios.migrado23", false)) return

        val yaHay = prefs.getString(CLAVE_LISTA, null)
        val viejos = ArrayList<Medio>()
        if (yaHay == null) {
            for (t in TipoMedio.entries) {
                val f = File(carpeta(ctx), t.id)
                if (f.length() <= 0L) continue
                viejos.add(
                    Medio(
                        id = t.id,
                        tipo = t,
                        nombre = prefs.getString("medio." + t.id + ".nombre", null) ?: etiqueta(t),
                        creado = f.lastModified()
                    )
                )
            }
        }
        val e = prefs.edit().putBoolean("medios.migrado23", true)
        if (viejos.isNotEmpty()) {
            val arr = JSONArray()
            for (m in viejos) {
                arr.put(
                    JSONObject()
                        .put("id", m.id)
                        .put("tipo", m.tipo.id)
                        .put("nombre", m.nombre)
                        .put("creado", m.creado)
                )
            }
            e.putString(CLAVE_LISTA, arr.toString())
        }
        e.apply()
    }

    private fun etiqueta(t: TipoMedio) = when (t) {
        TipoMedio.AUDIO -> "Mi audio"
        TipoMedio.VIDEO -> "Mi vídeo"
    }

    // ------------------------------------------------------------ en el móvil

    private fun carpeta(ctx: Context): File =
        File(ctx.getExternalFilesDir(null), "medios").apply { mkdirs() }

    fun fichero(ctx: Context, m: Medio): File = File(carpeta(ctx), m.id)

    fun fichero(ctx: Context, id: String): File = File(carpeta(ctx), id)

    fun tamano(ctx: Context, m: Medio): Long = fichero(ctx, m).length()

    /** Un id nuevo, que no choca con nada aunque añadas dos en el mismo segundo. */
    fun nuevoId(ctx: Context, t: TipoMedio): String {
        val usados = lista(ctx).map { it.id }.toSet()
        var n = System.currentTimeMillis()
        while (usados.contains(t.id + n)) n++
        return t.id + n
    }

    // --------------------------------------------------------------- importar

    /**
     * Copia el archivo elegido a la carpeta de la app. Bloqueante: va en un
     * hilo secundario. Se copia en vez de guardar solo la referencia porque
     * así da igual que luego muevas o borres el original.
     */
    fun importar(
        ctx: Context,
        tipo: TipoMedio,
        id: String,
        origen: Uri,
        avance: (copiados: Long, total: Long) -> Unit
    ): Medio? {
        val destino = fichero(ctx, id)
        val parcial = File(carpeta(ctx), id + ".parcial")
        val total = tamanoDe(ctx, origen)

        try {
            ctx.contentResolver.openInputStream(origen).use { entrada ->
                if (entrada == null) return null
                parcial.outputStream().use { salida ->
                    val buffer = ByteArray(256 * 1024)
                    var copiados = 0L
                    while (true) {
                        val leidos = entrada.read(buffer)
                        if (leidos <= 0) break
                        salida.write(buffer, 0, leidos)
                        copiados += leidos
                        avance(copiados, total)
                    }
                }
            }
        } catch (e: Exception) {
            parcial.delete()
            return null
        }

        // solo al final se deja en su sitio: si falla a medias no queda un
        // fichero roto que parezca bueno
        if (destino.exists()) destino.delete()
        if (!parcial.renameTo(destino)) {
            parcial.delete()
            return null
        }

        val m = Medio(id, tipo, nombreDe(ctx, origen), System.currentTimeMillis())
        registrar(ctx, m)
        return m
    }

    private fun tamanoDe(ctx: Context, uri: Uri): Long = consulta(ctx, uri, OpenableColumns.SIZE)
        ?.toLongOrNull() ?: 0L

    private fun nombreDe(ctx: Context, uri: Uri): String {
        val n = consulta(ctx, uri, OpenableColumns.DISPLAY_NAME)
            ?: uri.lastPathSegment
            ?: "archivo"
        return n.substringBeforeLast('.', n)
    }

    private fun consulta(ctx: Context, uri: Uri, columna: String): String? {
        return try {
            ctx.contentResolver.query(uri, arrayOf(columna), null, null, null)?.use { c ->
                if (c.moveToFirst() && !c.isNull(0)) c.getString(0) else null
            }
        } catch (e: Exception) {
            null
        }
    }

    // ------------------------------------------- descargar de una URL propia

    /**
     * Alternativa a elegir el archivo del móvil: bajarlo de un enlace que pone
     * el usuario (su Drive, su servidor, lo que sea). Solo ocurre esta vez; a
     * partir de aquí se reproduce del disco como cualquier otro medio.
     */
    fun descargar(ctx: Context, tipo: TipoMedio, id: String, url: String): Long {
        val parcial = File(carpeta(ctx), id + ".parcial")
        if (parcial.exists()) parcial.delete()

        val req = DownloadManager.Request(Uri.parse(url))
            .setTitle(etiqueta(tipo))
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE)
            .setDestinationInExternalFilesDir(ctx, "medios", parcial.name)
            .setAllowedOverMetered(true)

        val dm = ctx.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
        return dm.enqueue(req)
    }

    /** Por qué no se pudo dejar el archivo en su sitio. */
    enum class Fallo { NINGUNO, VACIO, ES_UNA_PAGINA, NO_SE_PUDO_GUARDAR }

    /**
     * Pasa el .parcial a su sitio. Igual que al importar: nada a medias.
     *
     * Antes comprueba que lo descargado sea de verdad un archivo. Los enlaces
     * de "compartir" de Drive, Dropbox y compañía devuelven una página web, no
     * el fichero; sin esta comprobación se guardaría el HTML como si fuera el
     * medio y el fallo saldría mucho después, al darle al play, sin explicación.
     */
    fun finalizarDescarga(ctx: Context, tipo: TipoMedio, id: String, url: String): Fallo {
        val parcial = File(carpeta(ctx), id + ".parcial")
        val destino = fichero(ctx, id)
        if (!parcial.exists() || parcial.length() == 0L) {
            parcial.delete()
            return Fallo.VACIO
        }
        if (pareceUnaPagina(parcial)) {
            parcial.delete()
            return Fallo.ES_UNA_PAGINA
        }
        if (destino.exists()) destino.delete()
        if (!parcial.renameTo(destino)) {
            parcial.delete()
            return Fallo.NO_SE_PUDO_GUARDAR
        }
        val nombre = Uri.parse(url).lastPathSegment?.substringBeforeLast('.')
            ?.takeIf { it.isNotBlank() } ?: etiqueta(tipo)
        registrar(ctx, Medio(id, tipo, nombre, System.currentTimeMillis()))
        return Fallo.NINGUNO
    }

    /** Mira los primeros bytes: si empiezan como HTML, no es un medio. */
    private fun pareceUnaPagina(f: File): Boolean {
        return try {
            val bruto = f.inputStream().use { entrada ->
                val buf = ByteArray(256)
                val n = entrada.read(buf)
                if (n <= 0) return false
                // ISO-8859-1: aqui solo interesan los primeros bytes tal cual
                String(buf, 0, n, Charsets.ISO_8859_1)
            }

            // salta espacios y un posible BOM antes de mirar la etiqueta
            val cabecera = bruto
                .dropWhile { it.isWhitespace() || it.code > 127 }
                .lowercase()

            cabecera.startsWith("<!doctype html") ||
                cabecera.startsWith("<html") ||
                cabecera.startsWith("<head")
        } catch (e: Exception) {
            false
        }
    }

    data class Progreso(val estado: Int, val bajados: Long, val total: Long) {
        val porcentaje: Int get() = if (total > 0) ((bajados * 100) / total).toInt() else 0
        val listo: Boolean get() = estado == DownloadManager.STATUS_SUCCESSFUL
        val fallo: Boolean get() = estado == DownloadManager.STATUS_FAILED
    }

    fun progreso(ctx: Context, id: Long): Progreso? {
        val dm = ctx.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
        var c: Cursor? = null
        try {
            c = dm.query(DownloadManager.Query().setFilterById(id)) ?: return null
            if (!c.moveToFirst()) return null
            return Progreso(
                estado = c.getInt(c.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS)),
                bajados = c.getLong(
                    c.getColumnIndexOrThrow(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR)
                ),
                total = c.getLong(
                    c.getColumnIndexOrThrow(DownloadManager.COLUMN_TOTAL_SIZE_BYTES)
                )
            )
        } catch (e: Exception) {
            return null
        } finally {
            c?.close()
        }
    }

    fun cancelar(ctx: Context, id: Long) {
        try {
            val dm = ctx.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
            dm.remove(id)
        } catch (e: Exception) {
            // si no se puede quitar, el .parcial se sobrescribe al reintentar
        }
    }

    // ------------------------------------------------------------- identidad

    /**
     * El prefijo evita chocar con las emisoras, que usan "<id>#<variante>".
     * Stations.parseStation() devuelve null para estos, que es lo que queremos:
     * ni Triton ni ICY tienen nada que decir de un fichero local.
     */
    fun mediaId(m: Medio) = "media:" + m.id

    fun idDeMediaId(mediaId: String?): String? =
        if (mediaId != null && mediaId.startsWith("media:")) mediaId.removePrefix("media:") else null

    fun parseMedio(ctx: Context, mediaId: String?): Medio? = porId(ctx, idDeMediaId(mediaId))

    fun mediaItem(ctx: Context, m: Medio): MediaItem =
        MediaItem.Builder()
            .setMediaId(mediaId(m))
            .setUri(Uri.fromFile(fichero(ctx, m)))
            .setMediaMetadata(
                MediaMetadata.Builder()
                    .setTitle(m.nombre)
                    .setArtist(etiqueta(m.tipo))
                    .setIsBrowsable(false)
                    .setIsPlayable(true)
                    .build()
            )
            .build()
}
