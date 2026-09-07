package com.radioco.app

import android.app.PendingIntent
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Metadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.extractor.metadata.icy.IcyInfo
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import java.util.concurrent.Executors

@UnstableApi
class PlaybackService : MediaSessionService() {

    companion object {
        // Claves de los extras de la sesion: es como la pantalla de la letra
        // se entera de por donde va la cancion.
        const val EX_MODO = "sync_modo"            // "icy" | "triton"
        const val EX_ANCLA_PLAYER = "sync_ancla"   // posicion del player donde empieza
        const val EX_CUE_START = "sync_cue_start"  // epoch ms del inicio en la emisora
        const val EX_ARTISTA = "song_artista"
        const val EX_TITULO = "song_titulo"
        const val EX_DURACION = "song_duracion"
    }

    private var session: MediaSession? = null
    private val handler = Handler(Looper.getMainLooper())

    private var retries = 0
    private var retryPending: Runnable? = null

    /** Cancion que suena ahora, si la emisora la publica. */
    private var song: String? = null
    private val io = Executors.newSingleThreadExecutor()
    private var tritonPending: Runnable? = null

    /** Mide los datos gastados mientras suena. */
    private val meterTick = object : Runnable {
        override fun run() {
            DataMeter.sample(this@PlaybackService)
            handler.postDelayed(this, 5_000)
        }
    }

    override fun onCreate() {
        super.onCreate()

        val player = ExoPlayer.Builder(this)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(C.USAGE_MEDIA)
                    .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
                    .build(),
                /* handleAudioFocus = */ true
            )
            .setHandleAudioBecomingNoisy(true)
            .setWakeMode(C.WAKE_MODE_NETWORK)
            .setLoadControl(
                DefaultLoadControl.Builder()
                    // buffer corto: arranca rápido y no acumula audio que igual se tira
                    .setBufferDurationsMs(10_000, 30_000, 1_500, 3_000)
                    .build()
            )
            .build()

        player.addListener(playerListener)

        val open = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        session = MediaSession.Builder(this, player)
            .setSessionActivity(open)
            .build()
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo) = session

    override fun onTaskRemoved(rootIntent: Intent?) {
        val p = session?.player
        if (p == null || !p.isPlaying) stopSelf()
    }

    override fun onDestroy() {
        cancelRetry()
        cancelTriton()
        io.shutdownNow()
        handler.removeCallbacks(meterTick)
        session?.run {
            player.removeListener(playerListener)
            player.release()
            release()
        }
        session = null
        super.onDestroy()
    }

    // ----------------------------------------------------------- reconexión

    private val playerListener = object : Player.Listener {

        override fun onPlayerError(error: PlaybackException) {
            val p = session?.player ?: return
            val item = p.currentMediaItem ?: return
            val station = Stations.parseStation(item.mediaId) ?: return
            val variant = Stations.parseVariant(item.mediaId)

            retries++
            // 1s, 2s, 4s, 8s, 16s -> máximo 15s; y prueba el otro stream de la emisora
            val wait = minOf(15_000L, 1_000L shl minOf(retries, 4))
            val next = variant + 1

            cancelRetry()
            val r = Runnable {
                val pl = session?.player ?: return@Runnable
                if (pl.currentMediaItem == null) return@Runnable   // el usuario paró
                pl.setMediaItem(Stations.mediaItem(station, next))
                pl.prepare()
                pl.play()
            }
            retryPending = r
            handler.postDelayed(r, wait)
        }

        override fun onPlaybackStateChanged(playbackState: Int) {
            if (playbackState == Player.STATE_READY) retries = 0
        }

        /** La Mega manda el titulo dentro del propio stream, en los bloques ICY. */
        override fun onMetadata(metadata: Metadata) {
            for (i in 0 until metadata.length()) {
                val entrada = metadata.get(i)
                if (entrada is IcyInfo) {
                    // un bloque vacio significa "sigue la misma cancion",
                    // asi que solo se hace caso cuando trae texto de verdad
                    val texto = NowPlaying.deIcy(entrada.title) ?: return
                    val (artista, titulo) = NowPlaying.deIcyPartes(entrada.title)
                    val p = session?.player
                    // el metadato se lee por delante de lo que suena: justo lo
                    // que hay en el buffer. Ahi empieza a oirse esta cancion.
                    val ancla = if (p != null) {
                        p.currentPosition + p.totalBufferedDuration
                    } else {
                        0L
                    }
                    setSong(texto, artista, titulo, anclaPlayer = ancla)
                    return
                }
            }
        }

        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            song = null
            limpiarSync()
            if (Stations.parseStation(mediaItem?.mediaId)?.tritonMount != null) {
                startTriton()
            } else {
                cancelTriton()
            }
        }

        override fun onIsPlayingChanged(isPlaying: Boolean) {
            if (isPlaying) {
                DataMeter.start(this@PlaybackService)
                handler.removeCallbacks(meterTick)
                handler.postDelayed(meterTick, 5_000)
                startTriton()
            } else {
                DataMeter.sample(this@PlaybackService)
                handler.removeCallbacks(meterTick)
                cancelTriton()
            }
        }
    }

    private fun cancelRetry() {
        retryPending?.let { handler.removeCallbacks(it) }
        retryPending = null
        retries = 0
    }

    // ------------------------------------------------------ que suena ahora

    private fun currentStation(): Station? =
        Stations.parseStation(session?.player?.currentMediaItem?.mediaId)

    private fun setSong(
        nuevo: String?,
        artista: String? = null,
        titulo: String? = null,
        cueStartWall: Long = 0L,
        duracionMs: Long = 0L,
        anclaPlayer: Long? = null
    ) {
        if (nuevo == song) return
        song = nuevo

        publicarSync(artista, titulo, cueStartWall, duracionMs, anclaPlayer)

        val p = session?.player ?: return
        val item = p.currentMediaItem ?: return
        val station = Stations.parseStation(item.mediaId) ?: return
        try {
            // Solo cambian los metadatos: media3 1.4 detecta que la URL es la
            // misma y los aplica sin volver a preparar el stream, asi que el
            // audio no se corta al cambiar de cancion.
            p.replaceMediaItem(
                p.currentMediaItemIndex,
                item.buildUpon().setMediaMetadata(Stations.metadata(station, nuevo)).build()
            )
        } catch (e: Exception) {
            // en el peor caso la notificacion se queda con el titulo anterior;
            // la radio sigue sonando igual
        }
    }

    /**
     * Publica en la sesion por donde va la cancion, para que la pantalla de la
     * letra pueda sincronizar. Hay dos casos y son muy distintos:
     *
     * - ICY (La Mega): el titulo viaja DENTRO del stream, asi que el momento en
     *   que lo leemos corresponde al inicio de la cancion en el audio. Como
     *   ExoPlayer va por delante de lo que suena, y sabe cuanto, el desfase se
     *   calcula solo: ancla = posicion actual + lo que hay en el buffer.
     * - Triton (Olimpica): el dato es del reloj de la EMISORA, y no hay forma
     *   de saber cuanto tarda en llegar a tu oido. Se publica tal cual y la
     *   pantalla resta una latencia estimada que el usuario puede afinar.
     */
    private fun publicarSync(
        artista: String?,
        titulo: String?,
        cueStartWall: Long,
        duracionMs: Long,
        anclaPlayer: Long?
    ) {
        val s = session ?: return
        val extras = Bundle().apply {
            when {
                anclaPlayer != null -> {
                    putString(EX_MODO, "icy")
                    putLong(EX_ANCLA_PLAYER, anclaPlayer)
                }

                cueStartWall > 0L -> {
                    putString(EX_MODO, "triton")
                    putLong(EX_CUE_START, cueStartWall)
                }
            }
            artista?.let { putString(EX_ARTISTA, it) }
            titulo?.let { putString(EX_TITULO, it) }
            putLong(EX_DURACION, duracionMs)
        }
        try {
            s.setSessionExtras(extras)
        } catch (e: Exception) {
            // sin extras la letra sigue viendose, solo pierde el sincronismo
        }
    }

    private fun limpiarSync() {
        try {
            session?.setSessionExtras(Bundle.EMPTY)
        } catch (e: Exception) {
        }
    }

    /** Olimpica deja los bloques ICY vacios: hay que preguntarle a Triton. */
    private fun startTriton() {
        cancelTriton()
        val mount = currentStation()?.tritonMount ?: return
        consultarTriton(mount)
    }

    private fun consultarTriton(mount: String) {
        try {
            io.execute {
                val info = NowPlaying.consultarTriton(mount)
                handler.post {
                    val p = session?.player
                    if (p == null || !p.isPlaying) return@post
                    if (currentStation()?.tritonMount != mount) return@post

                    setSong(
                        info.texto,
                        info.artista,
                        info.titulo,
                        cueStartWall = info.cueStartWall,
                        duracionMs = info.duracionMs
                    )

                    val r = Runnable { consultarTriton(mount) }
                    tritonPending = r
                    handler.postDelayed(r, info.siguienteConsultaMs)
                }
            }
        } catch (e: Exception) {
            // el executor ya esta cerrado (servicio muriendo): nada que hacer
        }
    }

    private fun cancelTriton() {
        tritonPending?.let { handler.removeCallbacks(it) }
        tritonPending = null
    }

}
