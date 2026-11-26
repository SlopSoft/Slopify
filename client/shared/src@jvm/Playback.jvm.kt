package slopsoft.slopify

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.freedesktop.gstreamer.Bus
import org.freedesktop.gstreamer.ElementFactory
import org.freedesktop.gstreamer.Format
import org.freedesktop.gstreamer.Gst
import org.freedesktop.gstreamer.State
import org.freedesktop.gstreamer.Structure
import org.freedesktop.gstreamer.elements.PlayBin
import org.freedesktop.gstreamer.elements.PlayFlags
import org.freedesktop.gstreamer.event.SeekFlags
import java.io.File
import java.net.URI
import java.util.concurrent.TimeUnit

internal actual fun createPlayback(): Playback = GstreamerPlayback()

private object GstreamerRuntime {
    val available by lazy {
        try {
            Gst.init()
            ElementFactory.find("playbin").dispose()
            true
        } catch (_: RuntimeException) {
            false
        } catch (_: LinkageError) {
            false
        }
    }
}

private class GstreamerPlayback : Playback {
    override val status = MutableStateFlow(PlaybackStatus.Loading)
    private val dispatcher = Dispatchers.IO.limitedParallelism(1)
    private val scope = CoroutineScope(SupervisorJob() + dispatcher)
    private var player: PlayBin? = null
    private var wantsPlay = true
    private var buffering = false

    override fun play(audio: TrackAudio) {
        scope.launch {
            if (!GstreamerRuntime.available) {
                status.value = PlaybackStatus.Unavailable
                return@launch
            }
            try {
                val pipeline = PlayBin("slopify-audio")
                player = pipeline
                pipeline.flags = setOf(PlayFlags.AUDIO, PlayFlags.SOFT_VOLUME)
                val uri =
                    when (audio) {
                        is TrackAudio.Local -> {
                            File(audio.path).toURI()
                        }

                        is TrackAudio.Remote -> {
                            prepareRemote(pipeline, audio)
                            URI(audio.url.toString())
                        }
                    }
                val bus = pipeline.bus
                bus.connect(
                    Bus.STATE_CHANGED { source, _, state, _ ->
                        if (source == pipeline && state == State.PLAYING) {
                            scope.launch {
                                if (player == pipeline && wantsPlay && !buffering) {
                                    status.value = PlaybackStatus.Playing
                                }
                            }
                        }
                    },
                )
                bus.connect(
                    Bus.BUFFERING { _, percent ->
                        scope.launch {
                            if (player == pipeline && buffering != (percent < 100)) {
                                buffering = percent < 100
                                if (wantsPlay) {
                                    if (buffering) {
                                        status.value = PlaybackStatus.Loading
                                        pipeline.pause()
                                    } else {
                                        pipeline.play()
                                    }
                                }
                            }
                        }
                    },
                )
                bus.connect(
                    Bus.ERROR { _, _, _ ->
                        scope.launch {
                            if (player == pipeline) fail(PlaybackStatus.Failed)
                        }
                    },
                )
                bus.connect(
                    Bus.EOS {
                        scope.launch {
                            if (player == pipeline) fail(PlaybackStatus.Ended)
                        }
                    },
                )
                pipeline.setURI(uri)
                pipeline.play()
            } catch (_: RuntimeException) {
                fail(PlaybackStatus.Unavailable)
            }
        }
    }

    private fun prepareRemote(
        pipeline: PlayBin,
        audio: TrackAudio.Remote,
    ) {
        ElementFactory.find("souphttpsrc").dispose()
        pipeline.flags = pipeline.flags + PlayFlags.BUFFERING
        pipeline.connect(
            PlayBin.SOURCE_SETUP { _, source ->
                try {
                    check(source.factory.name == "souphttpsrc" && source.get("location") == audio.url.toString())
                    source.set("automatic-redirect", false)
                    source.set("http-log-level", 0)
                    val headers = Structure.fromString("headers, Authorization=(string)\"Bearer ${audio.token}\"")
                    try {
                        source.set("extra-headers", headers)
                    } finally {
                        headers.dispose()
                    }
                } catch (_: RuntimeException) {
                    scope.launch {
                        if (player == pipeline) fail(PlaybackStatus.Unavailable)
                    }
                }
            },
        )
    }

    override fun pause() {
        scope.launch {
            val pipeline = player ?: return@launch
            wantsPlay = false
            pipeline.pause()
            status.value = PlaybackStatus.Paused
        }
    }

    override fun resume() {
        scope.launch {
            val pipeline = player ?: return@launch
            wantsPlay = true
            status.value = PlaybackStatus.Loading
            if (!buffering) pipeline.play()
        }
    }

    override fun seek(positionMillis: Long) {
        scope.launch {
            player?.seekSimple(Format.TIME, setOf(SeekFlags.FLUSH, SeekFlags.KEY_UNIT), TimeUnit.MILLISECONDS.toNanos(positionMillis))
        }
    }

    override suspend fun progress(): PlaybackProgress =
        withContext(dispatcher) {
            val pipeline = player ?: return@withContext PlaybackProgress()
            PlaybackProgress(
                TimeUnit.NANOSECONDS.toMillis(pipeline.queryPosition(Format.TIME)).coerceAtLeast(0),
                TimeUnit.NANOSECONDS.toMillis(pipeline.queryDuration(Format.TIME)).coerceAtLeast(0),
            )
        }

    override fun close() {
        scope.launch {
            release()
            scope.cancel()
        }
    }

    private fun fail(result: PlaybackStatus) {
        release()
        status.value = result
    }

    private fun release() {
        val pipeline = player ?: return
        player = null
        pipeline.stop()
        pipeline.dispose()
    }
}
