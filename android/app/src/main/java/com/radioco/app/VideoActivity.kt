package com.radioco.app

import android.graphics.Rect
import android.os.Build
import android.os.Bundle
import android.view.View
import android.view.WindowManager
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.doOnLayout
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import com.radioco.app.databinding.ActivityVideoBinding

/**
 * Reproduce un video propio, siempre desde la copia que hay en el movil.
 *
 * Tiene su propio reproductor en vez de usar PlaybackService: un video no tiene
 * sentido en segundo plano, y asi al salir de la pantalla se suelta todo. La
 * radio se para sola por el foco de audio, sin necesidad de coordinar nada.
 */
@UnstableApi
class VideoActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_ID = "medioId"

        /** Cuanto retrocede y avanza cada boton. */
        private const val ATRAS_MS = 10_000L
        private const val ADELANTE_MS = 30_000L

        /**
         * Margen para dar por terminado el video: si te quedaste en los ultimos
         * segundos, la proxima vez empieza de nuevo en vez de ir al final.
         */
        private const val MARGEN_FIN_MS = 15_000L
    }

    private lateinit var b: ActivityVideoBinding
    private var player: ExoPlayer? = null
    private var ranura: Ranura? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivityVideoBinding.inflate(layoutInflater)
        setContentView(b.root)

        // mientras se ve un video la pantalla no se apaga
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        pantallaCompleta()
        reservarLaFranjaDeLaBarra()

        b.btnBack.setOnClickListener { finish() }

        ranura = Medios.porId(intent.getStringExtra(EXTRA_ID))
    }

    override fun onStart() {
        super.onStart()
        val r = ranura
        if (r == null || !Medios.guardado(this, r)) {
            b.tvError.visibility = View.VISIBLE
            b.tvError.setText(R.string.media_failed)
            // sin video no hay controles, asi que la flecha se queda fija:
            // si no, esta pantalla no tendria salida a la vista
            b.btnBack.visibility = View.VISIBLE
            return
        }

        val p = ExoPlayer.Builder(this)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(C.USAGE_MEDIA)
                    .setContentType(C.AUDIO_CONTENT_TYPE_MOVIE)
                    .build(),
                /* handleAudioFocus = */ true      // esto es lo que para la radio
            )
            .setSeekBackIncrementMs(ATRAS_MS)
            .setSeekForwardIncrementMs(ADELANTE_MS)
            .build()

        p.addListener(object : Player.Listener {
            override fun onPlayerError(error: PlaybackException) {
                b.tvError.visibility = View.VISIBLE
                b.tvError.text = getString(R.string.media_failed)
            }
        })

        // Aqui solo hay un video: "anterior" y "siguiente" no llevan a ninguna
        // parte. El de anterior era ademas una trampa, porque con un solo medio
        // lo que hace es saltar al segundo 0 - justo lo contrario de lo que
        // buscas cuando quieres rebobinar un poco. Se quitan los dos y quedan
        // los de retroceder y avanzar, que si mueven el video donde toca.
        b.player.setShowPreviousButton(false)
        b.player.setShowNextButton(false)

        // la flecha de volver va con los controles: toca la pantalla y salen
        // los dos, se esconden juntos
        b.player.setControllerVisibilityListener(
            PlayerView.ControllerVisibilityListener { visibilidad ->
                b.btnBack.visibility = visibilidad
            }
        )
        b.btnBack.visibility = if (b.player.isControllerFullyVisible) View.VISIBLE else View.GONE

        b.player.player = p
        p.setMediaItem(Medios.mediaItem(this, r), posicionGuardada(r))
        p.prepare()
        p.playWhenReady = true
        player = p
    }

    override fun onStop() {
        guardarPosicion()
        b.player.player = null
        player?.release()
        player = null
        super.onStop()
    }

    // ------------------------------------------------- seguir donde lo dejaste

    /**
     * El reproductor se suelta al salir de la pantalla, asi que sin esto el
     * video volvia a empezar de cero cada vez que apagabas la pantalla, entraba
     * una llamada o te ibas un momento a otra app.
     */
    private fun guardarPosicion() {
        val p = player ?: return
        val r = ranura ?: return
        val duracion = p.duration
        val posicion = p.currentPosition
        val guardar = when {
            posicion < MARGEN_FIN_MS -> 0L
            duracion > 0 && posicion > duracion - MARGEN_FIN_MS -> 0L
            else -> posicion
        }
        DataMeter.prefs(this).edit().putLong(clave(r), guardar).apply()
    }

    private fun posicionGuardada(r: Ranura): Long =
        DataMeter.prefs(this).getLong(clave(r), 0L)

    private fun clave(r: Ranura) = "medio.${r.id}.posicion"

    // ------------------------------------------------------------- pantalla

    private fun pantallaCompleta() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            window.insetsController?.hide(android.view.WindowInsets.Type.systemBars())
        } else {
            @Suppress("DEPRECATION")
            window.decorView.systemUiVisibility =
                View.SYSTEM_UI_FLAG_FULLSCREEN or
                    View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
                    View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
        }
    }

    /**
     * La barra de progreso cae justo donde el sistema escucha el gesto de
     * "atras". Si arrastras el mando desde cerca del borde, Android se queda el
     * gesto, cierra la pantalla y el video se pierde. Con esto la franja de
     * abajo es nuestra y el arrastre llega al reproductor.
     */
    private fun reservarLaFranjaDeLaBarra() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return
        b.root.doOnLayout { v ->
            val alto = (120 * resources.displayMetrics.density).toInt()
            v.systemGestureExclusionRects =
                listOf(Rect(0, (v.height - alto).coerceAtLeast(0), v.width, v.height))
        }
    }
}
