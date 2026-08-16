package com.destur.phoneaudiosender

import android.media.MediaMetadata
import android.media.session.MediaController

data class TrackInfo(
    val title: String,
    val artist: String,
    val coverJpeg: ByteArray? = null,
    val durationMs: Long = 0L,
    val positionMs: Long = 0L,
    val isPlaying: Boolean = false,
    val queueTitles: List<String> = emptyList()
)

object CurrentTrackStore {
    private const val FALLBACK_TITLE = "Телефонный аудиопоток"
    private const val FALLBACK_ARTIST = "DesterPlayer / VKX"

    @Volatile
    private var current = TrackInfo(FALLBACK_TITLE, FALLBACK_ARTIST)

    @Volatile
    private var activeController: MediaController? = null

    fun get(): TrackInfo = current

    fun refresh() {
        activeController?.let { update(it) }
    }

    fun update(controller: MediaController?) {
        activeController = controller
        val metadata = controller?.metadata
        val playbackState = controller?.playbackState
        val queue = controller?.queue.orEmpty()
            .mapNotNull { item ->
                item.description.title?.toString()
                    ?: item.description.description?.toString()
            }
            .map { it.replace('\n', ' ').trim() }
            .filter { it.isNotBlank() }
            .take(10_000)

        if (metadata == null) {
            current = current.copy(
                positionMs = playbackState?.position?.coerceAtLeast(0L) ?: current.positionMs,
                isPlaying = playbackState?.state == android.media.session.PlaybackState.STATE_PLAYING,
                queueTitles = queue
            )
            return
        }

        val title = firstNonBlank(
            metadata.getString(MediaMetadata.METADATA_KEY_TITLE),
            metadata.getString(MediaMetadata.METADATA_KEY_DISPLAY_TITLE)
        ) ?: return
        val artist = firstNonBlank(
            metadata.getString(MediaMetadata.METADATA_KEY_ARTIST),
            metadata.getString(MediaMetadata.METADATA_KEY_ALBUM_ARTIST)
        ) ?: "Исполнитель неизвестен"

        val cover = metadata.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART)
            ?: metadata.getBitmap(MediaMetadata.METADATA_KEY_ART)
        current = TrackInfo(
            title = title.trim(),
            artist = artist.trim(),
            coverJpeg = compressCover(cover),
            durationMs = metadata.getLong(MediaMetadata.METADATA_KEY_DURATION).coerceAtLeast(0L),
            positionMs = playbackState?.position?.coerceAtLeast(0L) ?: 0L,
            isPlaying = playbackState?.state == android.media.session.PlaybackState.STATE_PLAYING,
            queueTitles = queue
        )
    }

    fun sendControl(command: String): Boolean {
        val controller = activeController ?: return false
        return try {
            when (command) {
                "PLAY_PAUSE" -> {
                    if (controller.playbackState?.state == android.media.session.PlaybackState.STATE_PLAYING) {
                        controller.transportControls.pause()
                    } else {
                        controller.transportControls.play()
                    }
                }
                "PREVIOUS" -> controller.transportControls.skipToPrevious()
                "NEXT" -> controller.transportControls.skipToNext()
                else -> if (command.startsWith("QUEUE_INDEX:")) {
                    val index = command.substringAfter(':').toIntOrNull() ?: return false
                    val item = controller.queue.orEmpty().getOrNull(index) ?: return false
                    controller.transportControls.skipToQueueItem(item.queueId)
                } else {
                    return false
                }
            }
            update(controller)
            true
        } catch (_: Exception) {
            false
        }
    }

    private fun firstNonBlank(vararg values: String?): String? {
        return values.firstOrNull { !it.isNullOrBlank() }
    }

    private fun compressCover(bitmap: android.graphics.Bitmap?): ByteArray? {
        if (bitmap == null) {
            return null
        }

        val maxSize = 720
        val scale = minOf(
            1.0,
            maxSize.toDouble() / bitmap.width.toDouble(),
            maxSize.toDouble() / bitmap.height.toDouble()
        )
        val scaled = if (scale < 1.0) {
            android.graphics.Bitmap.createScaledBitmap(
                bitmap,
                (bitmap.width * scale).toInt().coerceAtLeast(1),
                (bitmap.height * scale).toInt().coerceAtLeast(1),
                true
            )
        } else {
            bitmap
        }

        return try {
            val output = java.io.ByteArrayOutputStream()
            scaled.compress(android.graphics.Bitmap.CompressFormat.JPEG, 84, output)
            output.toByteArray().takeIf { it.size <= 700 * 1024 }
        } finally {
            if (scaled !== bitmap) {
                scaled.recycle()
            }
        }
    }
}
