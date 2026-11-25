package slopsoft.slopify

import android.media.AudioAttributes
import android.media.MediaDataSource
import android.media.MediaPlayer
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import java.io.IOException

internal actual fun createPlayback(): Playback = AndroidPlayback()

private class AndroidPlayback : Playback {
    override val status = MutableStateFlow(PlaybackStatus.Loading)
    private var player: MediaPlayer? = null
    private var source: RangeAudioSource? = null

    override fun play(audio: TrackAudio) {
        val data = RangeAudioSource(audio)
        source = data
        val media = MediaPlayer()
        player = media
        try {
            media.setAudioAttributes(
                AudioAttributes
                    .Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build(),
            )
            media.setOnPreparedListener {
                it.start()
                status.value = PlaybackStatus.Playing
            }
            media.setOnCompletionListener { status.value = PlaybackStatus.Ended }
            media.setOnErrorListener { _, _, _ ->
                status.value = if (data.expired) PlaybackStatus.Expired else PlaybackStatus.Failed
                true
            }
            media.setDataSource(data)
            media.prepareAsync()
        } catch (_: Exception) {
            close()
            status.value = if (data.expired) PlaybackStatus.Expired else PlaybackStatus.Failed
        }
    }

    override fun pause() {
        if (status.value != PlaybackStatus.Playing) return
        player?.pause()
        status.value = PlaybackStatus.Paused
    }

    override fun resume() {
        if (status.value != PlaybackStatus.Paused) return
        player?.start()
        status.value = PlaybackStatus.Playing
    }

    override fun seek(positionMillis: Long) {
        player?.seekTo(positionMillis.coerceAtMost(Int.MAX_VALUE.toLong()).toInt())
    }

    override suspend fun progress(): PlaybackProgress {
        val media = player ?: return PlaybackProgress()
        if (status.value != PlaybackStatus.Playing && status.value != PlaybackStatus.Paused) return PlaybackProgress()
        return PlaybackProgress(media.currentPosition.toLong(), media.duration.toLong())
    }

    override fun close() {
        source?.close()
        source = null
        player?.release()
        player = null
    }
}

private class RangeAudioSource(
    private val audio: TrackAudio,
) : MediaDataSource() {
    private val requests = Job()
    private var blockStart = -1L
    private var block = ByteArray(0)

    @Volatile
    var expired = false
        private set

    override fun getSize(): Long = audio.size

    @Synchronized
    override fun readAt(
        position: Long,
        buffer: ByteArray,
        offset: Int,
        size: Int,
    ): Int {
        if (size == 0) return 0
        if (position >= audio.size) return -1
        val start = (position / BLOCK_SIZE) * BLOCK_SIZE
        if (start != blockStart) {
            val count = minOf(audio.size - start, BLOCK_SIZE.toLong()).toInt()
            block =
                try {
                    runBlocking(requests) { audio.read(start, count) }
                } catch (_: SessionExpired) {
                    expired = true
                    throw IOException("Audio session expired")
                } catch (failure: Exception) {
                    throw IOException("Audio range unavailable", failure)
                }
            blockStart = start
        }
        val index = (position - blockStart).toInt()
        val count = minOf(size, block.size - index)
        block.copyInto(buffer, offset, index, index + count)
        return count
    }

    override fun close() {
        requests.cancel()
    }

    private companion object {
        const val BLOCK_SIZE = 256 * 1024
    }
}
