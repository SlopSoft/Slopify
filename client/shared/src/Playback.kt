package slopsoft.slopify

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive

internal enum class PlaybackStatus {
    Loading,
    Playing,
    Paused,
    Ended,
    Failed,
    Unavailable,
    Expired,
}

internal data class PlaybackProgress(
    val positionMillis: Long = 0,
    val durationMillis: Long = 0,
)

internal interface Playback {
    val status: StateFlow<PlaybackStatus>

    fun play(audio: TrackAudio)

    fun pause()

    fun resume()

    fun seek(positionMillis: Long)

    suspend fun progress(): PlaybackProgress

    fun close()
}

internal expect fun createPlayback(): Playback

@Composable
internal fun TrackControls(
    title: String,
    load: suspend () -> TrackAudio,
    onSessionExpired: () -> Unit,
    onClose: () -> Unit,
    cache: suspend (TrackAudio.Remote) -> Unit = {},
) {
    var attempt by remember { mutableStateOf(0) }
    key(attempt) {
        ActiveTrackControls(title, load, onSessionExpired, onClose, cache) { attempt++ }
    }
}

@Composable
private fun ActiveTrackControls(
    title: String,
    load: suspend () -> TrackAudio,
    onSessionExpired: () -> Unit,
    onClose: () -> Unit,
    cache: suspend (TrackAudio.Remote) -> Unit,
    onReplay: () -> Unit,
) {
    val playback = remember { createPlayback() }
    DisposableEffect(playback) { onDispose { playback.close() } }
    val status by playback.status.collectAsState()
    var error by remember { mutableStateOf<String?>(null) }
    var progress by remember { mutableStateOf(PlaybackProgress()) }
    var dragPosition by remember { mutableStateOf<Float?>(null) }
    val ready = status == PlaybackStatus.Playing || status == PlaybackStatus.Paused

    LaunchedEffect(playback) {
        try {
            val audio = load()
            playback.play(audio)
            if (audio is TrackAudio.Remote) {
                try {
                    cache(audio)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    // Streaming stays available when writing the optional cache fails.
                }
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: SessionExpired) {
            onSessionExpired()
        } catch (failure: SetupError) {
            error = failure.message
        } catch (_: Exception) {
            error = "Не удалось открыть аудиофайл."
        }
    }

    LaunchedEffect(status) {
        if (status == PlaybackStatus.Expired) {
            onSessionExpired()
        } else if (ready) {
            while (isActive) {
                progress = playback.progress()
                delay(350)
            }
        }
    }

    Column {
        Text(title, style = MaterialTheme.typography.titleMedium)
        val failure =
            error ?: when (status) {
                PlaybackStatus.Failed -> "Не удалось воспроизвести трек."
                PlaybackStatus.Unavailable -> "GStreamer или его плагины недоступны."
                else -> null
            }
        if (failure != null) {
            Text(failure, color = MaterialTheme.colorScheme.error)
        } else if (status == PlaybackStatus.Loading) {
            Text("Загрузка аудио…")
        }
        if (progress.durationMillis > 0 && ready) {
            val position = dragPosition?.toLong() ?: progress.positionMillis.coerceIn(0, progress.durationMillis)
            Slider(
                value = position.toFloat(),
                onValueChange = { dragPosition = it },
                onValueChangeFinished = {
                    dragPosition?.let { playback.seek(it.toLong()) }
                    dragPosition = null
                },
                valueRange = 0f..progress.durationMillis.toFloat(),
                modifier = Modifier.semantics { contentDescription = "Позиция воспроизведения" },
            )
            Text("${playbackTime(position)} / ${playbackTime(progress.durationMillis)}", style = MaterialTheme.typography.bodySmall)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            when {
                failure != null || status == PlaybackStatus.Ended -> Button(onClick = onReplay) { Text("Повторить") }
                status == PlaybackStatus.Playing -> Button(onClick = playback::pause) { Text("Пауза") }
                status == PlaybackStatus.Paused -> Button(onClick = playback::resume) { Text("Продолжить") }
            }
            TextButton(onClick = onClose) { Text("Закрыть плеер") }
        }
    }
}

private fun playbackTime(milliseconds: Long): String {
    val seconds = milliseconds / 1000
    return "${seconds / 60}:${(seconds % 60).toString().padStart(2, '0')}"
}
