package com.radioco.app

import android.os.Build
import android.os.Bundle
import android.view.View
import android.view.WindowManager
import androidx.appcompat.app.AppCompatActivity
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
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

        ranura = Medios.porId(intent.getStringExtra(EXTRA_ID))
    }

    override fun onStart() {
        super.onStart()
        val r = ranura
        if (r == null || !Medios.guardado(this, r)) {
            b.tvError.visibility = View.VISIBLE
            b.tvError.setText(R.string.media_failed)
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
            .build()

        p.addListener(object : Player.Listener {
            override fun onPlayerError(error: PlaybackException) {
                b.tvError.visibility = View.VISIBLE
                b.tvError.text = getString(R.string.media_failed)
            }
        })

        b.player.player = p
        p.setMediaItem(Medios.mediaItem(this, r))
        p.prepare()
        p.playWhenReady = true
        player = p
    }

    override fun onStop() {
        b.player.player = null
        player?.release()
        player = null
        super.onStop()
    }

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
}
