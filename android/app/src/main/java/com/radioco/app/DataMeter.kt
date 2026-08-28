package com.radioco.app

import android.content.Context
import android.content.SharedPreferences
import android.net.TrafficStats
import android.os.Process
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Cuenta los datos que realmente gasta la app (que solo descarga audio).
 * Mide con TrafficStats sobre el UID del proceso y acumula por día.
 * El servicio es quien mide; la pantalla solo lee el total.
 */
object DataMeter {

    private const val PREFS = "radioco"
    private const val K_DAY = "day"
    private const val K_BYTES = "bytes"
    private const val K_LAST_RX = "lastRx"
    const val K_SLEEP_AT = "sleepAt"
    const val K_LAST_CHECK = "lastUpdateCheck"

    private val fmt = SimpleDateFormat("yyyy-MM-dd", Locale.US)

    fun prefs(ctx: Context): SharedPreferences =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun today() = fmt.format(Date())

    private fun rxNow(): Long {
        val rx = TrafficStats.getUidRxBytes(Process.myUid())
        return if (rx == TrafficStats.UNSUPPORTED.toLong()) -1L else rx
    }

    /** Empieza a medir desde este momento (al arrancar la reproducción). */
    fun start(ctx: Context) {
        val p = prefs(ctx)
        rollDay(p)
        p.edit().putLong(K_LAST_RX, rxNow()).apply()
    }

    /** Suma lo descargado desde la última llamada. */
    fun sample(ctx: Context) {
        val p = prefs(ctx)
        rollDay(p)
        val rx = rxNow()
        if (rx < 0) return
        val last = p.getLong(K_LAST_RX, -1L)
        if (last in 0..rx) {
            p.edit()
                .putLong(K_BYTES, p.getLong(K_BYTES, 0L) + (rx - last))
                .putLong(K_LAST_RX, rx)
                .apply()
        } else {
            // primer arranque, o el contador del sistema se reinició (reinicio del móvil)
            p.edit().putLong(K_LAST_RX, rx).apply()
        }
    }

    fun bytesToday(ctx: Context): Long {
        val p = prefs(ctx)
        rollDay(p)
        return p.getLong(K_BYTES, 0L)
    }

    fun reset(ctx: Context) {
        prefs(ctx).edit()
            .putString(K_DAY, today())
            .putLong(K_BYTES, 0L)
            .putLong(K_LAST_RX, rxNow())
            .apply()
    }

    private fun rollDay(p: SharedPreferences) {
        if (p.getString(K_DAY, null) != today()) {
            p.edit().putString(K_DAY, today()).putLong(K_BYTES, 0L).apply()
        }
    }

    fun format(bytes: Long): String {
        val mb = bytes / 1048576.0
        return if (mb >= 100) String.format(Locale.getDefault(), "%.0f", mb)
        else String.format(Locale.getDefault(), "%.1f", mb)
    }
}
