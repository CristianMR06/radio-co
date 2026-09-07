package com.radioco.app

import android.content.ComponentName
import android.graphics.Typeface
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.MoreExecutors
import com.radioco.app.databinding.ActivityLyricsBinding
import java.util.concurrent.Executors

/**
 * Letra de lo que suena, en modo karaoke cuando la letra viene sincronizada.
 *
 * El sincronismo lo publica PlaybackService en los extras de la sesión, y hay
 * dos casos:
 *  - "icy"    (La Mega): se calcula solo con la profundidad del buffer.
 *  - "triton" (Olímpica): hay que restar una latencia estimada, que el usuario
 *             afina con los botones de ±1 s. Se recuerda por emisora.
 */
@UnstableApi
class LyricsActivity : AppCompatActivity() {

    private companion object {
        /** Retardo típico entre lo que emite la emisora y lo que oyes. */
        const val LATENCIA_BASE_MS = 20_000L
        const val TICK_MS = 250L
    }

    private lateinit var b: ActivityLyricsBinding

    private var future: ListenableFuture<MediaController>? = null
    private var controller: MediaController? = null

    private val handler = Handler(Looper.getMainLooper())
    private val io = Executors.newSingleThreadExecutor()

    private var claveActual: String? = null      // "artista|titulo" ya pedido
    private var letra: Lyrics.Letra? = null
    private var mensaje: String = ""
    private var lineaActual = -1
    private var vistas: List<TextView> = emptyList()

    private var offsetMs = 0L
    private var stationId: String? = null

    private val tick = object : Runnable {
        override fun run() {
            refrescar()
            handler.postDelayed(this, TICK_MS)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivityLyricsBinding.inflate(layoutInflater)
        setContentView(b.root)

        b.btnBack.setOnClickListener { finish() }

        b.btnMinus.setOnClickListener { ajustarOffset(-1000L) }
        b.btnPlus.setOnClickListener { ajustarOffset(1000L) }

        mensaje = getString(R.string.lyrics_loading)
        pintar()
    }

    override fun onStart() {
        super.onStart()
        val token = SessionToken(this, ComponentName(this, PlaybackService::class.java))
        val f = MediaController.Builder(this, token).buildAsync()
        future = f
        f.addListener({
            controller = try {
                f.get()
            } catch (e: Exception) {
                null
            }
            refrescar()
        }, MoreExecutors.directExecutor())
        handler.post(tick)
    }

    override fun onStop() {
        handler.removeCallbacks(tick)
        controller = null
        future?.let { MediaController.releaseFuture(it) }
        future = null
        super.onStop()
    }

    override fun onDestroy() {
        io.shutdownNow()
        super.onDestroy()
    }

    // ------------------------------------------------------------------ estado

    /**
     * Se lee del controlador en cada tick en vez de suscribirse a cambios:
     * son lecturas en memoria, y así no hay estados intermedios raros cuando
     * la canción cambia justo al abrir la pantalla.
     */
    private fun refrescar() {
        val c = controller
        if (c == null) {
            mensaje = getString(R.string.lyrics_no_playback)
            pintar()
            return
        }

        val extras = c.sessionExtras
        val artista = extras.getString(PlaybackService.EX_ARTISTA)
        val titulo = extras.getString(PlaybackService.EX_TITULO)
        val station = Stations.parseStation(c.currentMediaItem?.mediaId)

        if (station?.id != stationId) {
            stationId = station?.id
            offsetMs = leerOffset()
        }

        b.tvSong.text = c.mediaMetadata.title?.toString()
            ?: getString(R.string.lyrics_nothing)

        if (titulo.isNullOrBlank()) {
            // la emisora no publica el titulo: sin titulo no hay letra que buscar
            if (claveActual != null || letra != null) {
                claveActual = null
                letra = null
                lineaActual = -1
            }
            mensaje = getString(R.string.lyrics_no_song)
            pintar()
            return
        }

        val clave = "${artista.orEmpty()}|$titulo"
        if (clave != claveActual) {
            claveActual = clave
            letra = null
            lineaActual = -1
            mensaje = getString(R.string.lyrics_loading)
            pintar()
            pedirLetra(clave, artista.orEmpty(), titulo, extras.getLong(PlaybackService.EX_DURACION, 0L))
            return
        }

        pintarKaraoke()
    }

    private fun pedirLetra(clave: String, artista: String, titulo: String, duracionMs: Long) {
        try {
            io.execute {
                val r = Lyrics.buscar(artista, titulo, duracionMs)
                handler.post {
                    if (isFinishing || isDestroyed) return@post
                    if (claveActual != clave) return@post      // ya cambió la canción
                    when (r) {
                        is Lyrics.Resultado.Ok -> {
                            letra = r.letra
                            mensaje = ""
                        }

                        Lyrics.Resultado.NoEncontrada -> {
                            letra = null
                            mensaje = getString(R.string.lyrics_not_found)
                        }

                        is Lyrics.Resultado.Error -> {
                            letra = null
                            mensaje = getString(R.string.lyrics_error, r.motivo)
                        }
                    }
                    lineaActual = -1
                    pintar()
                }
            }
        } catch (e: Exception) {
            // executor cerrado: la pantalla se esta yendo
        }
    }

    // ----------------------------------------------------------------- pintado

    private fun pintar() {
        val l = letra

        // La referencia solo aporta cuando NO dice lo mismo que la cabecera
        // (p.ej. cuando la version encontrada es de otro artista).
        val cancion = b.tvSong.text?.toString().orEmpty()
        val ref = l?.referencia.orEmpty()
        val repetida = ref.isBlank() || Lyrics.mismaCancion(cancion, ref)
        b.tvRef.text = ref
        b.tvRef.visibility = if (repetida) View.GONE else View.VISIBLE

        val sincronizada = l != null && l.sincronizada
        b.syncBar.visibility = if (sincronizada) View.VISIBLE else View.GONE
        if (sincronizada) pintarOffset()

        b.tvMsg.visibility = if (mensaje.isBlank()) View.GONE else View.VISIBLE
        b.tvMsg.text = mensaje

        b.lines.removeAllViews()
        if (l == null) {
            vistas = emptyList()
            return
        }

        val nuevas = ArrayList<TextView>(l.lineas.size)
        val dim = ContextCompat.getColor(this, R.color.dim)
        for (linea in l.lineas) {
            val tv = TextView(this).apply {
                // texto de fuera: se asigna como texto, nunca se interpreta
                text = linea.texto.ifBlank { " " }
                textSize = if (l.sincronizada) 18f else 15f
                setTextColor(dim)
                setPadding(dp(2), dp(6), dp(2), dp(6))
                gravity = Gravity.START
            }
            b.lines.addView(
                tv,
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
            )
            nuevas += tv
        }
        vistas = nuevas

        if (!l.sincronizada) {
            b.tvMsg.visibility = View.VISIBLE
            b.tvMsg.text = getString(R.string.lyrics_not_synced)
        }
        lineaActual = -1
    }

    /** Por dónde va la canción, en ms, o null si no se puede saber. */
    private fun posicionCancion(): Long? {
        val c = controller ?: return null
        if (!c.isPlaying) return null
        val extras = c.sessionExtras

        return when (extras.getString(PlaybackService.EX_MODO)) {
            // el titulo viajaba dentro del stream: el desfase se sabe exacto
            "icy" -> {
                val ancla = extras.getLong(PlaybackService.EX_ANCLA_PLAYER, -1L)
                if (ancla < 0L) null else c.currentPosition - ancla - offsetMs
            }
            // dato del reloj de la emisora: hay que restar la latencia estimada
            "triton" -> {
                val cue = extras.getLong(PlaybackService.EX_CUE_START, 0L)
                if (cue <= 0L) null
                else System.currentTimeMillis() - cue - (LATENCIA_BASE_MS + offsetMs)
            }

            else -> null
        }
    }

    private fun pintarKaraoke() {
        val l = letra ?: return
        if (!l.sincronizada || vistas.isEmpty()) return
        val pos = posicionCancion() ?: return

        var idx = -1
        for (i in l.lineas.indices) {
            if (l.lineas[i].tMs <= pos) idx = i else break
        }
        if (idx == lineaActual) return
        lineaActual = idx

        val dim = ContextCompat.getColor(this, R.color.dim)
        val txt = ContextCompat.getColor(this, R.color.txt)
        for (i in vistas.indices) {
            val tv = vistas[i]
            when {
                i == idx -> {
                    tv.setTextColor(txt)
                    tv.setTypeface(null, Typeface.BOLD)
                    tv.alpha = 1f
                }

                i < idx -> {
                    tv.setTextColor(dim)
                    tv.setTypeface(null, Typeface.NORMAL)
                    tv.alpha = 0.45f
                }

                else -> {
                    tv.setTextColor(dim)
                    tv.setTypeface(null, Typeface.NORMAL)
                    tv.alpha = 1f
                }
            }
        }

        if (idx >= 0) {
            val tv = vistas[idx]
            val destino = tv.top - b.scroll.height / 3
            b.scroll.smoothScrollTo(0, destino.coerceAtLeast(0))
        }
    }

    // ---------------------------------------------------------------- offset

    private fun clave() = "lyricsOffset." + (stationId ?: "?")

    private fun leerOffset(): Long = DataMeter.prefs(this).getLong(clave(), 0L)

    private fun ajustarOffset(delta: Long) {
        offsetMs = (offsetMs + delta).coerceIn(-60_000L, 60_000L)
        DataMeter.prefs(this).edit().putLong(clave(), offsetMs).apply()
        pintarOffset()
        lineaActual = -1
        pintarKaraoke()
    }

    private fun pintarOffset() {
        val s = offsetMs / 1000
        b.tvOffset.text = if (s > 0) "+$s s" else "$s s"
        b.tvSyncHint.setText(
            if (controller?.sessionExtras?.getString(PlaybackService.EX_MODO) == "icy") {
                R.string.sync_hint_auto
            } else {
                R.string.sync_hint_manual
            }
        )
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()
}
