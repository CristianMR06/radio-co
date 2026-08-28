package com.radioco.app

import android.Manifest
import android.content.ComponentName
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.format.Formatter
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.MediaController
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionToken
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.MoreExecutors
import com.radioco.app.databinding.ActivityMainBinding
import com.radioco.app.databinding.ItemStationBinding
import java.util.concurrent.Executors

@UnstableApi
class MainActivity : AppCompatActivity() {

    private lateinit var b: ActivityMainBinding
    private val rows = LinkedHashMap<String, ItemStationBinding>()

    private var future: ListenableFuture<MediaController>? = null
    private var controller: MediaController? = null

    private val handler = Handler(Looper.getMainLooper())
    private val minutes = intArrayOf(0, 15, 30, 60, 120)

    // ---- actualizaciones
    private enum class Upd { IDLE, CHECKING, UPTODATE, AVAILABLE, DOWNLOADING, READY, NEED_PERM, FAILED }

    private val io = Executors.newSingleThreadExecutor()
    private var upd = Upd.IDLE
    private var pending: Updater.Release? = null
    private var downloadId = -1L
    private var updMsg = ""

    private val tick = object : Runnable {
        override fun run() {
            paintData()
            paintTimer()
            pollDownload()
            handler.postDelayed(this, 1_000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivityMainBinding.inflate(layoutInflater)
        setContentView(b.root)

        buildRows()

        b.btnReset.setOnClickListener {
            DataMeter.reset(this)
            paintData()
            Toast.makeText(this, R.string.reset_done, Toast.LENGTH_SHORT).show()
        }

        b.spTimer.adapter = ArrayAdapter.createFromResource(
            this, R.array.timer_labels, android.R.layout.simple_spinner_dropdown_item
        )
        b.spTimer.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p: AdapterView<*>?, v: View?, pos: Int, id: Long) {
                sendSleep(minutes.getOrElse(pos) { 0 })
            }

            override fun onNothingSelected(p: AdapterView<*>?) {}
        }

        b.tvVersion.text = getString(R.string.version_fmt, BuildConfig.VERSION_NAME)
        b.btnUpdate.setOnClickListener { onUpdateClick() }
        Updater.cleanOldApks(this, BuildConfig.VERSION_CODE)
        paintUpdate()

        askNotificationPermission()
        paintData()
        maybeAutoCheck()
    }

    private fun buildRows() {
        for (st in Stations.all) {
            val row = ItemStationBinding.inflate(layoutInflater, b.containerStations, false)
            row.tvName.text = st.name
            row.tvCity.text = st.city
            row.tvTag.text = "${st.streams[0].label}\n~${st.mbPerHour} MB/h"
            row.icon.backgroundTintList = android.content.res.ColorStateList.valueOf(st.accent)
            row.rowRoot.setOnClickListener { toggle(st) }
            b.containerStations.addView(row.root)
            rows[st.id] = row
        }
    }

    // ------------------------------------------------------- conexión al servicio

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
            controller?.addListener(playerListener)
            render()
        }, MoreExecutors.directExecutor())
        handler.post(tick)
    }

    override fun onResume() {
        super.onResume()
        // vuelta de los ajustes de "instalar apps desconocidas"
        if (upd == Upd.NEED_PERM && Updater.canInstall(this)) installPending()
    }

    override fun onStop() {
        handler.removeCallbacks(tick)
        controller?.removeListener(playerListener)
        controller = null
        future?.let { MediaController.releaseFuture(it) }
        future = null
        super.onStop()
    }

    override fun onDestroy() {
        io.shutdownNow()
        super.onDestroy()
    }

    private val playerListener = object : Player.Listener {
        override fun onEvents(player: Player, events: Player.Events) = render()
    }

    // ------------------------------------------------------------------ acciones

    private fun toggle(st: Station) {
        val c = controller ?: return
        val currentId = Stations.parseStation(c.currentMediaItem?.mediaId)?.id
        if (currentId == st.id && (c.isPlaying || c.playWhenReady)) {
            c.stop()
            c.clearMediaItems()
        } else {
            c.setMediaItem(Stations.mediaItem(st, 0))
            c.prepare()
            c.play()
        }
        render()
    }

    private fun sendSleep(min: Int) {
        val c = controller ?: return
        val args = Bundle().apply { putInt(PlaybackService.EXTRA_MINUTES, min) }
        c.sendCustomCommand(SessionCommand(PlaybackService.CMD_SLEEP, Bundle.EMPTY), args)
        if (min == 0) b.tvTimeLeft.text = ""
    }

    // ------------------------------------------------------------ actualizaciones

    private fun maybeAutoCheck() {
        val p = DataMeter.prefs(this)
        val last = p.getLong(DataMeter.K_LAST_CHECK, 0L)
        if (System.currentTimeMillis() - last > 6 * 60 * 60 * 1000L) checkUpdates()
    }

    private fun onUpdateClick() {
        when (upd) {
            Upd.AVAILABLE -> startDownload()
            Upd.READY -> installPending()
            Upd.NEED_PERM -> Updater.requestInstallPermission(this)
            Upd.CHECKING, Upd.DOWNLOADING -> Unit
            else -> checkUpdates()
        }
    }

    private fun checkUpdates() {
        if (upd == Upd.CHECKING || upd == Upd.DOWNLOADING) return
        upd = Upd.CHECKING
        paintUpdate()
        io.execute {
            val result = Updater.check()
            handler.post {
                if (isFinishing || isDestroyed) return@post
                DataMeter.prefs(this)
                    .edit()
                    .putLong(DataMeter.K_LAST_CHECK, System.currentTimeMillis())
                    .apply()
                when (result) {
                    is Updater.Result.Available -> {
                        pending = result.release
                        upd = if (Updater.apkFile(this, result.release.versionCode).exists()) {
                            Upd.READY
                        } else {
                            Upd.AVAILABLE
                        }
                    }

                    is Updater.Result.UpToDate -> {
                        pending = null
                        upd = Upd.UPTODATE
                    }

                    is Updater.Result.Failed -> {
                        updMsg = result.reason
                        upd = Upd.FAILED
                    }
                }
                paintUpdate()
            }
        }
    }

    private fun startDownload() {
        val r = pending ?: return
        try {
            downloadId = Updater.download(this, r)
            b.pbUpdate.progress = 0
            upd = Upd.DOWNLOADING
        } catch (e: Exception) {
            updMsg = getString(R.string.download_failed)
            upd = Upd.FAILED
        }
        paintUpdate()
    }

    private fun pollDownload() {
        if (upd != Upd.DOWNLOADING || downloadId <= 0L) return
        val p = Updater.progress(this, downloadId)
        when {
            p == null -> {
                updMsg = getString(R.string.download_failed)
                upd = Upd.FAILED
            }

            p.failed -> {
                Updater.cancel(this, downloadId)
                downloadId = -1L
                updMsg = getString(R.string.download_failed)
                upd = Upd.FAILED
            }

            p.done -> {
                downloadId = -1L
                upd = Upd.READY
                installPending()
            }

            else -> b.pbUpdate.progress = p.percent
        }
        paintUpdate()
    }

    private fun installPending() {
        val r = pending ?: return
        val f = Updater.apkFile(this, r.versionCode)
        if (!f.exists()) {
            upd = Upd.AVAILABLE
            paintUpdate()
            return
        }
        if (!Updater.canInstall(this)) {
            upd = Upd.NEED_PERM
            paintUpdate()
            return
        }
        try {
            Updater.install(this, f)
            upd = Upd.READY
        } catch (e: Exception) {
            updMsg = e.message ?: getString(R.string.download_failed)
            upd = Upd.FAILED
        }
        paintUpdate()
    }

    private fun paintUpdate() {
        val st = b.tvUpdateStatus
        b.pbUpdate.visibility = if (upd == Upd.DOWNLOADING) View.VISIBLE else View.GONE
        st.visibility = View.VISIBLE

        when (upd) {
            Upd.IDLE -> {
                b.btnUpdate.text = getString(R.string.check_updates)
                st.visibility = View.GONE
            }

            Upd.CHECKING -> {
                b.btnUpdate.text = getString(R.string.checking)
                st.visibility = View.GONE
            }

            Upd.UPTODATE -> {
                b.btnUpdate.text = getString(R.string.check_updates)
                st.text = getString(R.string.up_to_date)
            }

            Upd.AVAILABLE -> {
                val r = pending
                b.btnUpdate.text = getString(
                    R.string.update_download_fmt,
                    Formatter.formatShortFileSize(this, r?.sizeBytes ?: 0L)
                )
                st.text = getString(R.string.update_found_fmt, r?.title ?: "")
            }

            Upd.DOWNLOADING -> {
                b.btnUpdate.text = getString(R.string.downloading_fmt, b.pbUpdate.progress)
                st.visibility = View.GONE
            }

            Upd.READY -> {
                b.btnUpdate.text = getString(R.string.install_now)
                st.text = getString(R.string.downloaded_ready)
            }

            Upd.NEED_PERM -> {
                b.btnUpdate.text = getString(R.string.grant_install)
                st.text = getString(R.string.grant_install_hint)
            }

            Upd.FAILED -> {
                b.btnUpdate.text = getString(R.string.retry)
                st.text = updMsg
            }
        }
    }

    // -------------------------------------------------------------------- pintado

    private fun render() {
        val c = controller
        val activeId = Stations.parseStation(c?.currentMediaItem?.mediaId)?.id
        val live = c != null && (c.isPlaying || c.playWhenReady)

        for (st in Stations.all) {
            val row = rows[st.id] ?: continue
            val on = live && st.id == activeId
            row.icon.setImageResource(if (on) R.drawable.ic_stop else R.drawable.ic_play)
            row.tvStatus.setTextColor(
                if (on) st.accent else ContextCompat.getColor(this, R.color.dim)
            )
            row.tvStatus.text = when {
                !on -> ""
                c!!.isPlaying -> {
                    val v = Stations.parseVariant(c.currentMediaItem?.mediaId)
                    "En directo · " + Stations.streamOf(st, v).label
                }

                c.playbackState == Player.STATE_BUFFERING -> "Conectando…"
                else -> "Sin señal, reintentando…"
            }
        }

        val dot = when {
            c == null -> R.color.dim
            c.isPlaying -> R.color.ok
            c.playbackState == Player.STATE_BUFFERING -> R.color.warn
            c.playerError != null -> R.color.err
            else -> R.color.dim
        }
        b.dotState.backgroundTintList =
            android.content.res.ColorStateList.valueOf(ContextCompat.getColor(this, dot))
    }

    private fun paintData() {
        b.tvData.text = DataMeter.format(DataMeter.bytesToday(this)) + " MB"
    }

    private fun paintTimer() {
        val at = DataMeter.prefs(this).getLong(DataMeter.K_SLEEP_AT, 0L)
        if (at <= 0L) {
            b.tvTimeLeft.text = ""
            return
        }
        val left = at - System.currentTimeMillis()
        if (left <= 0L) {
            b.tvTimeLeft.text = ""
            if (b.spTimer.selectedItemPosition != 0) b.spTimer.setSelection(0)
            return
        }
        val m = left / 60_000
        val s = left % 60_000 / 1_000
        b.tvTimeLeft.text = String.format("%d:%02d", m, s)
    }

    // --------------------------------------------------------------- permisos

    private fun askNotificationPermission() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        val granted = ContextCompat.checkSelfPermission(
            this, Manifest.permission.POST_NOTIFICATIONS
        ) == PackageManager.PERMISSION_GRANTED
        if (!granted) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        }
    }
}
