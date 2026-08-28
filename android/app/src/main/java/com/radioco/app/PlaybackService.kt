package com.radioco.app

import android.app.PendingIntent
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionResult
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture

@UnstableApi
class PlaybackService : MediaSessionService() {

    companion object {
        const val CMD_SLEEP = "com.radioco.app.SLEEP"
        const val EXTRA_MINUTES = "minutes"
    }

    private var session: MediaSession? = null
    private val handler = Handler(Looper.getMainLooper())

    private var retries = 0
    private var retryPending: Runnable? = null
    private var sleepPending: Runnable? = null

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
            .setCallback(callback)
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
        cancelSleep()
        handler.removeCallbacks(meterTick)
        session?.run {
            player.removeListener(playerListener)
            player.release()
            release()
        }
        session = null
        super.onDestroy()
    }

    // ---------------------------------------------------------------- callback

    private val callback = object : MediaSession.Callback {

        override fun onConnect(
            session: MediaSession,
            controller: MediaSession.ControllerInfo
        ): MediaSession.ConnectionResult {
            val commands = MediaSession.ConnectionResult.DEFAULT_SESSION_COMMANDS
                .buildUpon()
                .add(SessionCommand(CMD_SLEEP, Bundle.EMPTY))
                .build()
            return MediaSession.ConnectionResult.AcceptedResultBuilder(session)
                .setAvailableSessionCommands(commands)
                .build()
        }

        override fun onCustomCommand(
            session: MediaSession,
            controller: MediaSession.ControllerInfo,
            customCommand: SessionCommand,
            args: Bundle
        ): ListenableFuture<SessionResult> {
            if (customCommand.customAction == CMD_SLEEP) {
                scheduleSleep(args.getInt(EXTRA_MINUTES, 0))
                return Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
            }
            return Futures.immediateFuture(SessionResult(SessionResult.RESULT_ERROR_NOT_SUPPORTED))
        }
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

        override fun onIsPlayingChanged(isPlaying: Boolean) {
            if (isPlaying) {
                DataMeter.start(this@PlaybackService)
                handler.removeCallbacks(meterTick)
                handler.postDelayed(meterTick, 5_000)
            } else {
                DataMeter.sample(this@PlaybackService)
                handler.removeCallbacks(meterTick)
            }
        }
    }

    private fun cancelRetry() {
        retryPending?.let { handler.removeCallbacks(it) }
        retryPending = null
        retries = 0
    }

    // ------------------------------------------------------------ temporizador

    private fun scheduleSleep(minutes: Int) {
        cancelSleep()
        if (minutes <= 0) {
            DataMeter.prefs(this).edit().putLong(DataMeter.K_SLEEP_AT, 0L).apply()
            return
        }
        val at = System.currentTimeMillis() + minutes * 60_000L
        DataMeter.prefs(this).edit().putLong(DataMeter.K_SLEEP_AT, at).apply()

        val r = Runnable {
            DataMeter.prefs(this).edit().putLong(DataMeter.K_SLEEP_AT, 0L).apply()
            cancelRetry()
            session?.player?.let {
                it.stop()
                it.clearMediaItems()
            }
        }
        sleepPending = r
        handler.postDelayed(r, minutes * 60_000L)
    }

    private fun cancelSleep() {
        sleepPending?.let { handler.removeCallbacks(it) }
        sleepPending = null
    }
}
