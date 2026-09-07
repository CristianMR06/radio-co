package com.radioco.app

import android.Manifest
import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.format.DateFormat
import android.text.format.Formatter
import android.view.View
import android.view.animation.AccelerateDecelerateInterpolator
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.graphics.ColorUtils
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.MoreExecutors
import com.radioco.app.databinding.ActivityMainBinding
import com.radioco.app.databinding.ItemStationBinding
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors

@UnstableApi
class MainActivity : AppCompatActivity() {

    private lateinit var b: ActivityMainBinding
    private val rows = LinkedHashMap<String, ItemStationBinding>()

    private var future: ListenableFuture<MediaController>? = null
    private var controller: MediaController? = null

    private val handler = Handler(Looper.getMainLooper())

    // ---- actualizaciones
    private enum class Upd { IDLE, CHECKING, UPTODATE, AVAILABLE, DOWNLOADING, READY, NEED_PERM, FAILED }

    private val io = Executors.newSingleThreadExecutor()
    private var upd = Upd.IDLE
    private var pending: Updater.Release? = null
    private var downloadId = -1L
    private var updMsg = ""

    /** Respeta el formato de 12/24 h que tenga configurado el movil. */
    private val horaFmt by lazy { DateFormat.getTimeFormat(this) }
    /**
     * La fecha va en espanol a proposito: toda la app esta en espanol, y con
     * el idioma del movil saldria "Monday, 7 September". La HORA si respeta
     * el ajuste del movil (12/24 h), que eso es preferencia del usuario.
     */
    private val fechaFmt by lazy { SimpleDateFormat("EEEE, d 'de' MMMM", Locale("es")) }

    /** Un animador por barra del ecualizador, por emisora. */
    private val eqAnims = HashMap<String, List<ObjectAnimator>>()

    private val tick = object : Runnable {
        override fun run() {
            paintClock()
            paintData()
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

        b.tvVersion.text = getString(R.string.version_fmt, BuildConfig.VERSION_NAME)
        b.btnUpdate.setOnClickListener { onUpdateClick() }
        Updater.cleanOldApks(this, BuildConfig.VERSION_CODE)
        paintUpdate()

        askNotificationPermission()
        paintClock()
        paintData()
        maybeAutoCheck()
    }

    private fun buildRows() {
        for (st in Stations.all) {
            val row = ItemStationBinding.inflate(layoutInflater, b.containerStations, false)
            row.tvName.text = st.name
            row.tvCity.text = st.city
            row.tvTag.text = "${st.streams[0].label}\n~${st.mbPerHour} MB/h"
            row.iconBg.backgroundTintList = ColorStateList.valueOf(st.accent)
            row.icon.imageTintList =
                ColorStateList.valueOf(ContextCompat.getColor(this, R.color.on_accent))
            val tinte = ColorStateList.valueOf(st.accent)
            row.btnLyrics.compoundDrawableTintList = tinte
            row.btnSong.compoundDrawableTintList = tinte
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
        rows.forEach { (id, row) -> eqParar(id, row) }
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

    private fun abrirLetra() {
        startActivity(Intent(this, LyricsActivity::class.java))
    }

    /**
     * Abre la cancion en YouTube Music. Si no esta instalada, tira del
     * navegador, que tambien sirve.
     */
    private fun buscarCancion(cancion: String) {
        val url = "https://music.youtube.com/search?q=" +
            Uri.encode(NowPlaying.consultaBusqueda(cancion))
        val web = Intent(Intent.ACTION_VIEW, Uri.parse(url))

        val app = Intent(web).setPackage("com.google.android.apps.youtube.music")
        try {
            startActivity(app)
            return
        } catch (e: ActivityNotFoundException) {
            // no esta instalada: seguimos abajo
        }
        try {
            startActivity(web)
        } catch (e: ActivityNotFoundException) {
            Toast.makeText(this, R.string.no_music_app, Toast.LENGTH_SHORT).show()
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
            val sonando = on && c!!.isPlaying

            // el servicio pone la cancion como titulo cuando la emisora la
            // publica; si no, el titulo sigue siendo el nombre de la emisora
            val cancion =
                if (sonando) c!!.mediaMetadata.title?.toString()?.takeIf { it != st.name }
                else null

            row.rowRoot.background = fondoTarjeta(on, st.accent)
            row.icon.setImageResource(if (on) R.drawable.ic_stop else R.drawable.ic_play)

            // la tarjeta activa crece: es el foco de la pantalla
            row.expand.visibility = if (on) View.VISIBLE else View.GONE
            row.pills.visibility = if (cancion != null) View.VISIBLE else View.GONE
            row.tvTag.visibility = if (on) View.GONE else View.VISIBLE

            row.eq.visibility = if (sonando) View.VISIBLE else View.GONE
            if (sonando) eqArrancar(st, row) else eqParar(st.id, row)

            row.tvStatus.setTextColor(
                ContextCompat.getColor(this, if (cancion != null) R.color.txt else R.color.dim)
            )
            row.tvStatus.text = when {
                !on -> ""
                cancion != null -> "♪  " + cancion
                c!!.isPlaying ->
                    "En directo · " + Stations.streamOf(
                        st, Stations.parseVariant(c.currentMediaItem?.mediaId)
                    ).label

                c.playbackState == Player.STATE_BUFFERING -> getString(R.string.connecting)
                else -> getString(R.string.no_signal)
            }

            row.btnSong.setOnClickListener { cancion?.let { buscarCancion(it) } }
            row.btnLyrics.setOnClickListener { abrirLetra() }
        }
    }

    /** Sin borde cuando esta apagada; con el acento marcado cuando suena. */
    private fun fondoTarjeta(activa: Boolean, accent: Int): Drawable =
        GradientDrawable().apply {
            cornerRadius = dp(24).toFloat()
            setColor(
                ContextCompat.getColor(
                    this@MainActivity, if (activa) R.color.card2 else R.color.card
                )
            )
            if (activa) setStroke(dp(2), ColorUtils.setAlphaComponent(accent, 120))
        }

    private fun eqArrancar(st: Station, row: ItemStationBinding) {
        if (eqAnims.containsKey(st.id)) return          // ya esta animando
        val barras = listOf(row.bar1, row.bar2, row.bar3)
        val duraciones = listOf(420L, 660L, 520L)
        val anims = barras.mapIndexed { i, v ->
            v.backgroundTintList = ColorStateList.valueOf(st.accent)
            v.post { v.pivotY = v.height.toFloat() }    // que crezca desde abajo
            ObjectAnimator.ofFloat(v, "scaleY", 0.22f, 1f).apply {
                duration = duraciones[i]
                startDelay = i * 90L
                repeatMode = ValueAnimator.REVERSE
                repeatCount = ValueAnimator.INFINITE
                interpolator = AccelerateDecelerateInterpolator()
                start()
            }
        }
        eqAnims[st.id] = anims
    }

    private fun eqParar(id: String, row: ItemStationBinding) {
        eqAnims.remove(id)?.forEach { it.cancel() }
        listOf(row.bar1, row.bar2, row.bar3).forEach { it.scaleY = 1f }
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    private fun paintClock() {
        val ahora = Date()
        b.tvClock.text = horaFmt.format(ahora)
        b.tvDate.text = fechaFmt.format(ahora)
            .replaceFirstChar { if (it.isLowerCase()) it.titlecase(Locale("es")) else it.toString() }
    }

    private fun paintData() {
        b.tvData.text = DataMeter.format(DataMeter.bytesToday(this)) + " MB"
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
