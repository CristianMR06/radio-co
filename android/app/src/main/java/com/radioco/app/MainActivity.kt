package com.radioco.app

import android.Manifest
import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.content.ActivityNotFoundException
import android.content.ClipboardManager
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
import android.text.InputType
import android.view.View
import android.view.ViewGroup
import android.view.animation.AccelerateDecelerateInterpolator
import android.widget.EditText
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
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
import com.radioco.app.databinding.ItemMediaBinding
import com.radioco.app.databinding.ItemStationBinding
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors

@UnstableApi
class MainActivity : AppCompatActivity() {

    private lateinit var b: ActivityMainBinding
    private val rows = LinkedHashMap<String, ItemStationBinding>()
    private val mediaRows = LinkedHashMap<String, ItemMediaBinding>()
    /** La fila de "anadir" de cada lista, que es tambien la del progreso. */
    private val addRows = HashMap<TipoMedio, ItemMediaBinding>()

    private var canciones: List<Medio> = emptyList()
    private var videos: List<Medio> = emptyList()

    /** Copias en curso: solo una por lista. Tipo -> porcentaje. */
    private val copiando = HashMap<TipoMedio, Int>()
    /** Tipo -> texto del fallo, para poder explicar cual fue. */
    private val fallos = HashMap<String, String>()
    private var tipoPendiente: TipoMedio? = null

    /** Descargas por URL en curso: tipo -> id de DownloadManager. */
    private val descargas = HashMap<TipoMedio, Long>()
    private val urls = HashMap<TipoMedio, String>()
    /** El id que tendra el medio cuando termine de bajarse. */
    private val idEnCurso = HashMap<TipoMedio, String>()
    /** Enlaces que quedan por bajar de la tanda, en orden. */
    private val colaUrls = HashMap<TipoMedio, MutableList<String>>()
    private val pedidas = HashMap<TipoMedio, Int>()
    private val fallidas = HashMap<TipoMedio, Int>()

    /**
     * Selector del sistema. Se registra aqui (no en onCreate) porque tiene que
     * existir antes de que la Activity arranque del todo.
     */
    private val selector =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            val t = tipoPendiente
            tipoPendiente = null
            if (uri != null && t != null) importar(t, uri)
        }

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
            pollMedios()
            handler.postDelayed(this, 1_000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivityMainBinding.inflate(layoutInflater)
        setContentView(b.root)

        buildRows()
        recargarMedios()

        b.btnReset.setOnClickListener {
            DataMeter.reset(this)
            paintData()
            Toast.makeText(this, R.string.reset_done, Toast.LENGTH_SHORT).show()
        }

        b.tvVersion.text = getString(R.string.version_fmt, BuildConfig.VERSION_NAME)
        b.btnUpdate.setOnClickListener { onUpdateClick() }
        Lyrics.limpiarAjustesViejos(this)
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
        rows.forEach { (id, r) -> eqParar(id, listOf(r.bar1, r.bar2, r.bar3)) }
        mediaRows.forEach { (id, r) -> eqParar(id, listOf(r.bar1, r.bar2, r.bar3)) }
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
            val barras = listOf(row.bar1, row.bar2, row.bar3)
            if (sonando) eqArrancar(st.id, st.accent, barras) else eqParar(st.id, barras)

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

        renderMedios()
    }

    // --------------------------------------------------------------- medios

    /**
     * Las listas se guardan en memoria: Medios.lista() mira el disco y esto se
     * pinta en cada tick. Se recargan solo cuando cambia algo de verdad.
     */
    private fun recargarMedios() {
        val todos = Medios.lista(this)
        canciones = todos.filter { it.tipo == TipoMedio.AUDIO }
        videos = todos.filter { it.tipo == TipoMedio.VIDEO }
        reconstruirMedios()
    }

    /**
     * Rehace las dos listas. Solo al anadir o borrar: si se hiciera en cada
     * pintado, el ecualizador se reiniciaria una vez por segundo.
     */
    private fun reconstruirMedios() {
        for ((id, row) in mediaRows) eqParar(id, listOf(row.bar1, row.bar2, row.bar3))
        mediaRows.clear()
        addRows.clear()
        b.containerMusic.removeAllViews()
        b.containerVideos.removeAllViews()

        llenar(b.containerMusic, canciones, TipoMedio.AUDIO)
        llenar(b.containerVideos, videos, TipoMedio.VIDEO)

        b.btnPlayAll.visibility = if (canciones.isEmpty()) View.GONE else View.VISIBLE
        b.btnPlayAll.setOnClickListener { reproducirTodo() }
        renderMedios()
    }

    private fun llenar(contenedor: ViewGroup, medios: List<Medio>, tipo: TipoMedio) {
        for (m in medios) {
            val row = ItemMediaBinding.inflate(layoutInflater, contenedor, false)
            pintarIcono(row, Medios.accent(tipo))
            row.rowRoot.setOnClickListener { pulsarMedio(m) }
            row.btnDelete.setOnClickListener { borrarMedio(m) }
            // en el XML nace oculto, que es como lo quiere la fila de "anadir"
            row.btnDelete.visibility = View.VISIBLE
            row.btnUrl.visibility = View.GONE
            contenedor.addView(row.root)
            mediaRows[m.id] = row
        }

        // la fila de "anadir" va al final de cada lista, y es tambien donde se
        // ve el progreso de la copia o de la descarga
        val add = ItemMediaBinding.inflate(layoutInflater, contenedor, false)
        pintarIcono(add, Medios.accent(tipo))
        add.icon.setImageResource(R.drawable.ic_download)
        add.tvName.setText(
            if (tipo == TipoMedio.AUDIO) R.string.media_add_song else R.string.media_add_video
        )
        add.tvSub.visibility = View.GONE
        add.eq.visibility = View.GONE
        add.btnDelete.visibility = View.GONE
        add.rowRoot.setOnClickListener { elegirDelMovil(tipo) }
        add.btnUrl.setOnClickListener { pedirUrl(tipo) }
        contenedor.addView(add.root)
        addRows[tipo] = add
    }

    private fun pintarIcono(row: ItemMediaBinding, accent: Int) {
        row.iconBg.backgroundTintList = ColorStateList.valueOf(accent)
        row.icon.imageTintList =
            ColorStateList.valueOf(ContextCompat.getColor(this, R.color.on_accent))
    }

    /** Esta sonando este medio ahora mismo? */
    private fun sonando(m: Medio): Boolean {
        val c = controller ?: return false
        return Medios.idDeMediaId(c.currentMediaItem?.mediaId) == m.id &&
            (c.isPlaying || c.playWhenReady)
    }

    private fun sonandoMiMusica(): Boolean {
        val c = controller ?: return false
        val id = Medios.idDeMediaId(c.currentMediaItem?.mediaId) ?: return false
        return canciones.any { it.id == id } && (c.isPlaying || c.playWhenReady)
    }

    /**
     * Una cancion no se pone sola: se pone la lista entera empezando por ella,
     * asi los botones de siguiente y anterior (notificacion, auriculares,
     * pantalla de bloqueo) funcionan desde cualquier punto.
     */
    private fun pulsarMedio(m: Medio) {
        when (m.tipo) {
            TipoMedio.VIDEO ->
                startActivity(
                    Intent(this, VideoActivity::class.java)
                        .putExtra(VideoActivity.EXTRA_ID, m.id)
                )

            TipoMedio.AUDIO -> {
                val c = controller ?: return
                if (sonando(m)) {
                    c.stop()
                    c.clearMediaItems()
                } else {
                    ponerCola(canciones.indexOfFirst { it.id == m.id }.coerceAtLeast(0))
                }
            }
        }
        render()
    }

    private fun reproducirTodo() {
        val c = controller ?: return
        if (sonandoMiMusica()) {
            c.stop()
            c.clearMediaItems()
        } else {
            ponerCola(0)
        }
        render()
    }

    private fun ponerCola(desde: Int) {
        val c = controller ?: return
        if (canciones.isEmpty()) return
        c.setMediaItems(canciones.map { Medios.mediaItem(this, it) }, desde, 0L)
        c.prepare()
        c.play()
    }

    // ----------------------------------------------------------- anadir mas

    private fun elegirDelMovil(tipo: TipoMedio) {
        if (ocupado(tipo)) return
        tipoPendiente = tipo
        try {
            selector.launch(Medios.mimes(tipo))
        } catch (e: Exception) {
            tipoPendiente = null
            fallos[tipo.id] = getString(R.string.media_failed)
            renderMedios()
        }
    }

    private fun ocupado(tipo: TipoMedio) =
        copiando.containsKey(tipo) ||
            descargas.containsKey(tipo) ||
            colaUrls[tipo]?.isNotEmpty() == true

    private fun importar(tipo: TipoMedio, uri: android.net.Uri) {
        val id = Medios.nuevoId(this, tipo)
        copiando[tipo] = 0
        fallos.remove(tipo.id)
        renderMedios()

        try {
            io.execute {
                val m = Medios.importar(this, tipo, id, uri) { copiados, total ->
                    val pct = if (total > 0) ((copiados * 100) / total).toInt() else 0
                    handler.post {
                        if (copiando.containsKey(tipo)) {
                            copiando[tipo] = pct
                            renderMedios()
                        }
                    }
                }
                handler.post {
                    if (isFinishing || isDestroyed) return@post
                    copiando.remove(tipo)
                    if (m == null) fallos[tipo.id] = getString(R.string.media_failed)
                    recargarMedios()
                }
            }
        } catch (e: Exception) {
            copiando.remove(tipo)
            fallos[tipo.id] = getString(R.string.media_failed)
            renderMedios()
        }
    }

    /**
     * Con confirmacion: borrar una copia significa volver a gastar datos para
     * recuperarla, que es justo lo que esta app intenta evitar.
     */
    private fun borrarMedio(m: Medio) {
        AlertDialog.Builder(this)
            .setTitle(m.nombre)
            .setMessage(R.string.media_delete_confirm)
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.media_delete_ok) { _, _ ->
                if (sonando(m)) {
                    controller?.stop()
                    controller?.clearMediaItems()
                }
                Medios.borrar(this, m)
                Toast.makeText(this, R.string.media_deleted, Toast.LENGTH_SHORT).show()
                recargarMedios()
            }
            .show()
    }

    /** Pide un enlace y se lo baja. El enlace lo pone el usuario, no la app. */
    private fun pedirUrl(tipo: TipoMedio) {
        if (ocupado(tipo)) return

        val campo = EditText(this).apply {
            hint = getString(R.string.media_url_hint)
            inputType = InputType.TYPE_TEXT_VARIATION_URI
            setSingleLine()
            setPadding(dp(20), dp(12), dp(20), dp(12))
        }

        // Si acabas de copiar un enlace, ya te lo pone: teclear una URL larga
        // en el movil es justo lo que nadie quiere hacer.
        portapapeles()?.let { campo.setText(it) }

        AlertDialog.Builder(this)
            .setTitle(R.string.media_url_title)
            .setMessage(R.string.media_url_help)
            .setView(campo)
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.ok) { _, _ ->
                // se pueden pegar varios de golpe: separados por comas, por
                // punto y coma o por saltos de linea. Una URL nunca lleva
                // espacios, asi que cortar tambien por ahi no rompe nada.
                val lista = campo.text.toString()
                    .split(Regex("[,;\\s]+"))
                    .map { it.trim() }
                    .filter { it.isNotBlank() }

                // solo https: Android bloquea el trafico sin cifrar desde
                // targetSdk 28, asi que un http:// fallaria sin explicacion
                val buenos = lista.filter { it.startsWith("https://") }
                if (buenos.isEmpty()) {
                    Toast.makeText(this, R.string.media_url_bad, Toast.LENGTH_LONG).show()
                    return@setPositiveButton
                }
                if (buenos.size < lista.size) {
                    Toast.makeText(
                        this,
                        getString(R.string.media_url_some_bad, lista.size - buenos.size),
                        Toast.LENGTH_LONG
                    ).show()
                }

                colaUrls[tipo] = buenos.toMutableList()
                pedidas[tipo] = buenos.size
                fallidas[tipo] = 0
                fallos.remove(tipo.id)
                arrancarDescarga(tipo)
            }
            .show()
    }

    /**
     * Baja los enlaces de uno en uno. En serie a proposito: si se lanzaran
     * todos a la vez, cada uno iria mas lento y el progreso no diria nada.
     */
    private fun arrancarDescarga(tipo: TipoMedio) {
        val cola = colaUrls[tipo]
        if (cola == null || cola.isEmpty()) {
            terminarTanda(tipo)
            return
        }
        val url = cola.removeAt(0)
        val id = Medios.nuevoId(this, tipo)
        try {
            descargas[tipo] = Medios.descargar(this, tipo, id, url)
            idEnCurso[tipo] = id
            urls[tipo] = url
        } catch (e: Exception) {
            fallidas[tipo] = (fallidas[tipo] ?: 0) + 1
            arrancarDescarga(tipo)
            return
        }
        renderMedios()
    }

    private fun terminarTanda(tipo: TipoMedio) {
        val total = pedidas[tipo] ?: 0
        val mal = fallidas[tipo] ?: 0
        colaUrls.remove(tipo)
        pedidas.remove(tipo)
        fallidas.remove(tipo)
        if (total > 1) {
            fallos.remove(tipo.id)
            Toast.makeText(
                this,
                getString(R.string.media_batch_done, total - mal, total),
                Toast.LENGTH_LONG
            ).show()
        }
        recargarMedios()
    }

    /** El enlace copiado, si lo hay y tiene pinta de servir. */
    private fun portapapeles(): String? {
        return try {
            val cb = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
            val texto = cb.primaryClip?.getItemAt(0)?.coerceToText(this)?.toString()?.trim()
            texto?.takeIf { it.startsWith("https://") && it.length < 2000 }
        } catch (e: Exception) {
            null   // desde Android 10 solo se puede leer con la app en primer
        }          // plano; si no se puede, simplemente no se rellena
    }

    private fun pollMedios() {
        if (descargas.isEmpty()) return
        val terminadas = mutableListOf<TipoMedio>()

        for ((tipo, dlId) in descargas) {
            val id = idEnCurso[tipo] ?: continue
            val row = addRows[tipo] ?: continue
            val p = Medios.progreso(this, dlId)
            when {
                p == null || p.fallo -> {
                    Medios.cancelar(this, dlId)
                    fallos[tipo.id] = getString(R.string.media_failed)
                    terminadas += tipo
                }

                p.listo -> {
                    when (Medios.finalizarDescarga(this, tipo, id, urls[tipo].orEmpty())) {
                        Medios.Fallo.NINGUNO -> fallos.remove(tipo.id)
                        Medios.Fallo.ES_UNA_PAGINA ->
                            fallos[tipo.id] = getString(R.string.media_failed_html)

                        Medios.Fallo.VACIO ->
                            fallos[tipo.id] = getString(R.string.media_failed_empty)

                        Medios.Fallo.NO_SE_PUDO_GUARDAR ->
                            fallos[tipo.id] = getString(R.string.media_failed)
                    }
                    terminadas += tipo
                }

                else -> row.pb.progress = p.porcentaje
            }
        }

        for (tipo in terminadas) {
            descargas.remove(tipo)
            urls.remove(tipo)
            idEnCurso.remove(tipo)
            if (fallos.containsKey(tipo.id)) fallidas[tipo] = (fallidas[tipo] ?: 0) + 1
            // sigue con el siguiente enlace de la tanda; si no queda ninguno,
            // arrancarDescarga() la da por terminada
            arrancarDescarga(tipo)
        }
        if (terminadas.isEmpty()) renderMedios()
    }

    private fun renderMedios() {
        for (m in canciones + videos) {
            val row = mediaRows[m.id] ?: continue
            val suena = sonando(m)
            val accent = Medios.accent(m.tipo)

            row.tvName.text = m.nombre
            row.rowRoot.background = fondoTarjeta(suena, accent)
            row.icon.setImageResource(if (suena) R.drawable.ic_stop else R.drawable.ic_play)

            val barras = listOf(row.bar1, row.bar2, row.bar3)
            row.eq.visibility = if (suena) View.VISIBLE else View.GONE
            if (suena) eqArrancar(m.id, accent, barras) else eqParar(m.id, barras)

            row.tvSub.text = Formatter.formatShortFileSize(this, Medios.tamano(this, m))
            row.tvEstado.text =
                if (suena) getString(R.string.media_playing) else getString(R.string.media_saved)
            row.tvEstado.setTextColor(
                ContextCompat.getColor(this, if (suena) R.color.txt else R.color.dim)
            )
        }

        for (tipo in TipoMedio.entries) {
            val add = addRows[tipo] ?: continue
            val bajando = descargas.containsKey(tipo)
            val pct = copiando[tipo] ?: if (bajando) add.pb.progress else null
            val ocupada = pct != null

            add.pb.visibility = if (ocupada) View.VISIBLE else View.GONE
            if (copiando[tipo] != null) add.pb.progress = pct!!
            add.btnUrl.visibility = if (ocupada) View.GONE else View.VISIBLE

            val total = pedidas[tipo] ?: 0
            add.tvEstado.text = when {
                bajando && pct != null && total > 1 ->
                    getString(
                        R.string.media_downloading_n,
                        total - (colaUrls[tipo]?.size ?: 0), total, pct
                    )

                bajando && pct != null -> getString(R.string.media_downloading, pct)
                pct != null -> getString(R.string.media_copying, pct)
                fallos.containsKey(tipo.id) -> fallos[tipo.id].orEmpty()
                else -> getString(R.string.media_add_hint)
            }
            add.tvEstado.setTextColor(
                ContextCompat.getColor(
                    this, if (fallos.containsKey(tipo.id)) R.color.err else R.color.txt
                )
            )
        }

        b.btnPlayAll.setText(if (sonandoMiMusica()) R.string.stop_all else R.string.play_all)
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

    private fun eqArrancar(id: String, accent: Int, barras: List<View>) {
        if (eqAnims.containsKey(id)) return             // ya esta animando
        val duraciones = listOf(420L, 660L, 520L)
        val anims = barras.mapIndexed { i, v ->
            v.backgroundTintList = ColorStateList.valueOf(accent)
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
        eqAnims[id] = anims
    }

    private fun eqParar(id: String, barras: List<View>) {
        eqAnims.remove(id)?.forEach { it.cancel() }
        barras.forEach { it.scaleY = 1f }
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
