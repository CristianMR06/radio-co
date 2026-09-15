package com.radioco.app

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.util.UnstableApi
import java.io.File

enum class TipoMedio { AUDIO, VIDEO }

/**
 * Un hueco para un medio propio. No lleva URL: los archivos no vienen de
 * internet, los eliges tu del movil una sola vez.
 */
data class Ranura(
    val id: String,
    val tipo: TipoMedio,
    val etiqueta: String,
    val accent: Int,
    val mimes: Array<String>
) {
    override fun equals(other: Any?) = other is Ranura && other.id == id
    override fun hashCode() = id.hashCode()
}

/**
 * Medios propios, guardados en el movil.
 *
 * Los eliges con el selector del sistema y la app se queda con una copia en su
 * carpeta. A partir de ahi todo sale del disco: **nunca** tocan la red, ni la
 * primera vez. Esa era la idea — son siempre los mismos dos archivos y no tiene
 * sentido que gasten datos.
 *
 * La copia vive en getExternalFilesDir, que es privado de la app pero sobrevive
 * a las actualizaciones. Solo se va si desinstalas o le das a "borrar copia".
 */
@UnstableApi
object Medios {

    val ranuras: List<Ranura> = listOf(
        Ranura(
            id = "audio",
            tipo = TipoMedio.AUDIO,
            etiqueta = "Mi audio",
            accent = 0xFFA78BFA.toInt(),
            mimes = arrayOf("audio/*")
        ),
        Ranura(
            id = "video",
            tipo = TipoMedio.VIDEO,
            etiqueta = "Mi vídeo",
            accent = 0xFF34D399.toInt(),
            mimes = arrayOf("video/*")
        )
    )

    fun porId(id: String?): Ranura? = ranuras.firstOrNull { it.id == id }

    // ------------------------------------------------------------ en el movil

    private fun carpeta(ctx: Context): File =
        File(ctx.getExternalFilesDir(null), "medios").apply { mkdirs() }

    fun fichero(ctx: Context, r: Ranura): File = File(carpeta(ctx), r.id)

    fun guardado(ctx: Context, r: Ranura): Boolean = fichero(ctx, r).length() > 0L

    fun tamano(ctx: Context, r: Ranura): Long = fichero(ctx, r).length()

    /** El nombre del archivo original, para no enseñar "audio" a secas. */
    fun nombre(ctx: Context, r: Ranura): String? =
        DataMeter.prefs(ctx).getString("medio.${r.id}.nombre", null)

    fun borrar(ctx: Context, r: Ranura) {
        fichero(ctx, r).delete()
        DataMeter.prefs(ctx).edit().remove("medio.${r.id}.nombre").apply()
    }

    // ------------------------------------------------------------- importar

    /**
     * Copia el archivo elegido a la carpeta de la app. Bloqueante: va en un
     * hilo secundario. Se copia en vez de guardar solo la referencia porque
     * asi da igual que luego muevas o borres el original.
     */
    fun importar(
        ctx: Context,
        r: Ranura,
        origen: Uri,
        avance: (copiados: Long, total: Long) -> Unit
    ): Boolean {
        val destino = fichero(ctx, r)
        val parcial = File(destino.parentFile, "${r.id}.parcial")
        val total = tamanoDe(ctx, origen)

        try {
            ctx.contentResolver.openInputStream(origen).use { entrada ->
                if (entrada == null) return false
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
            return false
        }

        // solo al final se deja en su sitio: si falla a medias no queda un
        // fichero roto que parezca bueno
        if (destino.exists()) destino.delete()
        if (!parcial.renameTo(destino)) {
            parcial.delete()
            return false
        }

        DataMeter.prefs(ctx).edit()
            .putString("medio.${r.id}.nombre", nombreDe(ctx, origen))
            .apply()
        return true
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

    // ------------------------------------------------------------- identidad

    /**
     * El prefijo evita chocar con las emisoras, que usan "<id>#<variante>".
     * Stations.parseStation() devuelve null para estos, que es lo que queremos:
     * ni Triton ni ICY tienen nada que decir de un fichero local.
     */
    fun mediaId(r: Ranura) = "media:${r.id}"

    fun parseRanura(mediaId: String?): Ranura? {
        if (mediaId == null || !mediaId.startsWith("media:")) return null
        return porId(mediaId.removePrefix("media:"))
    }

    fun mediaItem(ctx: Context, r: Ranura): MediaItem =
        MediaItem.Builder()
            .setMediaId(mediaId(r))
            .setUri(Uri.fromFile(fichero(ctx, r)))
            .setMediaMetadata(
                MediaMetadata.Builder()
                    .setTitle(nombre(ctx, r) ?: r.etiqueta)
                    .setArtist(r.etiqueta)
                    .setIsBrowsable(false)
                    .setIsPlayable(true)
                    .build()
            )
            .build()
}
