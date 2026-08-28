package com.radioco.app

import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi

data class Stream(
    val url: String,
    val kbps: Int,
    val label: String,
    val hls: Boolean = false
)

data class Station(
    val id: String,
    val name: String,
    val city: String,
    val accent: Int,
    val streams: List<Stream>,
    /**
     * Mount de la API de Triton, para las emisoras que no rellenan los
     * metadatos ICY del stream (Olimpica). null = el titulo llega por ICY.
     */
    val tritonMount: String? = null
) {
    /** MB por hora del stream principal, para avisar al usuario antes de darle play. */
    val mbPerHour: Int get() = (streams[0].kbps * 3600.0 / 8 / 1024).toInt()
}

@UnstableApi
object Stations {

    val all: List<Station> = listOf(
        Station(
            id = "olimpica-ibague",
            name = "Olímpica Stéreo",
            city = "Ibagué · 94.3 FM",
            accent = 0xFFFB7185.toInt(),
            streams = listOf(
                Stream(
                    "https://playerservices.streamtheworld.com/api/livestream-redirect/OLP_IBAGUEAAC.aac",
                    64, "AAC 64k"
                ),
                Stream(
                    "https://playerservices.streamtheworld.com/api/livestream-redirect/OLP_IBAGUE.mp3",
                    96, "MP3 96k"
                )
            ),
            tritonMount = "OLP_IBAGUEAAC"
        ),
        Station(
            id = "lamega-bogota",
            name = "La Mega",
            city = "Bogotá · 90.9 FM",
            accent = 0xFF38BDF8.toInt(),
            streams = listOf(
                Stream(
                    "https://mdstrm.com/audio/632c9ae6660fef03fe3855fe/icecast.audio",
                    128, "AAC 128k"
                ),
                Stream(
                    "https://mdstrm.com/audio/632c9ae6660fef03fe3855fe/live.m3u8",
                    98, "HLS 98k", hls = true
                )
            )
        )
    )

    fun byId(id: String?): Station? = all.firstOrNull { it.id == id }

    /** El mediaId lleva la emisora y qué stream de esa emisora se está usando. */
    fun mediaId(station: Station, variant: Int) = "${station.id}#$variant"

    fun parseStation(mediaId: String?): Station? =
        byId(mediaId?.substringBefore('#'))

    fun parseVariant(mediaId: String?): Int =
        mediaId?.substringAfter('#', "0")?.toIntOrNull() ?: 0

    fun streamOf(station: Station, variant: Int): Stream =
        station.streams[((variant % station.streams.size) + station.streams.size) % station.streams.size]

    /**
     * Lo que se ve en la notificacion y en la pantalla de bloqueo.
     * Con cancion: titulo = cancion, subtitulo = emisora.
     * Sin cancion: titulo = emisora, subtitulo = ciudad y dial.
     */
    fun metadata(station: Station, song: String? = null): MediaMetadata =
        MediaMetadata.Builder()
            .setTitle(song ?: station.name)
            .setArtist(if (song != null) station.name else station.city)
            .setStation(station.name)
            .setIsBrowsable(false)
            .setIsPlayable(true)
            .build()

    fun mediaItem(station: Station, variant: Int, song: String? = null): MediaItem {
        val s = streamOf(station, variant)
        val meta = metadata(station, song)

        val b = MediaItem.Builder()
            .setMediaId(mediaId(station, variant))
            .setUri(s.url)
            .setMediaMetadata(meta)
            .setLiveConfiguration(MediaItem.LiveConfiguration.Builder().build())

        if (s.hls) b.setMimeType(MimeTypes.APPLICATION_M3U8)
        return b.build()
    }
}
