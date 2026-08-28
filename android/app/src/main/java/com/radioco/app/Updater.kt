package com.radioco.app

import android.app.DownloadManager
import android.content.Context
import android.content.Intent
import android.database.Cursor
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.Settings
import androidx.core.content.FileProvider
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/**
 * Actualizaciones desde las releases de GitHub del propio repositorio.
 *
 * Convenio: la etiqueta de cada release es "v<versionCode>" (v2, v3, ...) y el
 * APK va adjunto como asset. La app compara ese número con su BuildConfig.
 */
object Updater {

    private const val API = "https://api.github.com/repos/%s/releases/latest"
    private const val TIMEOUT_MS = 12_000

    const val FILE_PREFIX = "radioco-"

    data class Release(
        val versionCode: Int,
        val title: String,
        val notes: String,
        val apkUrl: String,
        val sizeBytes: Long
    )

    sealed class Result {
        data class Available(val release: Release) : Result()
        object UpToDate : Result()
        data class Failed(val reason: String) : Result()
    }

    // ------------------------------------------------------------------ consulta

    /** Bloqueante: llamar siempre desde un hilo secundario. */
    fun check(): Result {
        val url = String.format(API, BuildConfig.GITHUB_REPO)
        var conn: HttpURLConnection? = null
        try {
            conn = (URL(url).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = TIMEOUT_MS
                readTimeout = TIMEOUT_MS
                setRequestProperty("Accept", "application/vnd.github+json")
                setRequestProperty("User-Agent", "RadioCO/${BuildConfig.VERSION_NAME}")
            }
            when (conn.responseCode) {
                200 -> {
                    val body = conn.inputStream.bufferedReader().use { it.readText() }
                    val release = parse(body) ?: return Result.Failed("respuesta inesperada")
                    return if (release.versionCode > BuildConfig.VERSION_CODE) {
                        Result.Available(release)
                    } else {
                        Result.UpToDate
                    }
                }
                // todavía no hay ninguna release publicada
                404 -> return Result.UpToDate
                403 -> return Result.Failed("GitHub limitó las consultas, prueba en un rato")
                else -> return Result.Failed("GitHub respondió ${conn.responseCode}")
            }
        } catch (e: IOException) {
            return Result.Failed("sin conexión")
        } catch (e: Exception) {
            return Result.Failed(e.message ?: "error desconocido")
        } finally {
            conn?.disconnect()
        }
    }

    private fun parse(json: String): Release? {
        val o = JSONObject(json)
        val tag = o.optString("tag_name")
        val code = Regex("\\d+").find(tag)?.value?.toIntOrNull() ?: return null

        val assets = o.optJSONArray("assets") ?: return null
        for (i in 0 until assets.length()) {
            val a = assets.getJSONObject(i)
            val name = a.optString("name")
            if (name.endsWith(".apk", ignoreCase = true)) {
                return Release(
                    versionCode = code,
                    title = o.optString("name").ifBlank { tag },
                    notes = o.optString("body").trim(),
                    apkUrl = a.optString("browser_download_url"),
                    sizeBytes = a.optLong("size")
                )
            }
        }
        return null
    }

    // ------------------------------------------------------------------ descarga

    fun apkFile(ctx: Context, versionCode: Int): File =
        File(
            ctx.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS),
            "$FILE_PREFIX$versionCode.apk"
        )

    /** Encola la descarga y devuelve el id de DownloadManager. */
    fun download(ctx: Context, release: Release): Long {
        val dest = apkFile(ctx, release.versionCode)
        if (dest.exists()) dest.delete()

        val req = DownloadManager.Request(Uri.parse(release.apkUrl))
            .setTitle("Radio CO ${release.title}")
            .setDescription("Descargando actualización")
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE)
            .setDestinationInExternalFilesDir(
                ctx, Environment.DIRECTORY_DOWNLOADS, dest.name
            )
            .setAllowedOverMetered(true)
            .setMimeType("application/vnd.android.package-archive")

        val dm = ctx.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
        return dm.enqueue(req)
    }

    data class Progress(val status: Int, val downloaded: Long, val total: Long) {
        val percent: Int get() = if (total > 0) ((downloaded * 100) / total).toInt() else 0
        val done: Boolean get() = status == DownloadManager.STATUS_SUCCESSFUL
        val failed: Boolean get() = status == DownloadManager.STATUS_FAILED
    }

    fun progress(ctx: Context, id: Long): Progress? {
        val dm = ctx.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
        var c: Cursor? = null
        try {
            c = dm.query(DownloadManager.Query().setFilterById(id)) ?: return null
            if (!c.moveToFirst()) return null
            return Progress(
                status = c.getInt(c.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS)),
                downloaded = c.getLong(
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

    fun cancel(ctx: Context, id: Long) {
        try {
            val dm = ctx.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
            dm.remove(id)
        } catch (e: Exception) {
            // da igual: si no se puede quitar, el fichero se sobrescribe en el siguiente intento
        }
    }

    // ----------------------------------------------------------------- instalación

    /** Android exige permiso explícito por app para instalar APKs (8.0+). */
    fun canInstall(ctx: Context): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.O || ctx.packageManager.canRequestPackageInstalls()

    fun requestInstallPermission(ctx: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val i = Intent(
            Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
            Uri.parse("package:${ctx.packageName}")
        ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        ctx.startActivity(i)
    }

    fun install(ctx: Context, file: File) {
        val uri = FileProvider.getUriForFile(ctx, "${ctx.packageName}.fileprovider", file)
        val i = Intent(Intent.ACTION_VIEW)
            .setDataAndType(uri, "application/vnd.android.package-archive")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        ctx.startActivity(i)
    }

    /** Limpia APKs de descargas anteriores. */
    fun cleanOldApks(ctx: Context, keepVersionCode: Int) {
        val dir = ctx.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS) ?: return
        dir.listFiles()?.forEach { f ->
            if (f.name.startsWith(FILE_PREFIX) && f.name != "$FILE_PREFIX$keepVersionCode.apk") {
                f.delete()
            }
        }
    }
}
