package slopsoft.slopify

import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.unit.dp
import io.ktor.http.Url
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jetbrains.compose.resources.decodeToImageBitmap

@Composable
internal fun Library(
    api: SetupApi,
    endpoint: Url,
    token: String,
    onSessionExpired: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var album by remember(endpoint, token) { mutableStateOf<LibraryAlbum?>(null) }
    var playing by remember(endpoint, token) { mutableStateOf<LibraryTrack?>(null) }
    Column(modifier) {
        playing?.let { track ->
            key(track.id) {
                TrackControls(
                    track = track,
                    load = { api.audio(endpoint, token, track.id) },
                    onSessionExpired = onSessionExpired,
                    onClose = { playing = null },
                )
            }
        }
        val selected = album
        if (selected == null) {
            Text("Библиотека", style = MaterialTheme.typography.titleLarge)
            LibraryList(
                load = { api.albums(endpoint, token) },
                emptyMessage = "В библиотеке пока нет альбомов.",
                onSessionExpired = onSessionExpired,
                itemKey = LibraryAlbum::id,
            ) { entry ->
                ListItem(
                    headlineContent = { Text(entry.title) },
                    supportingContent = { Text("${entry.artist} · ${entry.releaseYear}") },
                    modifier = Modifier.fillMaxWidth().clickable { album = entry },
                )
            }
        } else {
            TextButton(onClick = { album = null }) { Text("Все альбомы") }
            Text(selected.title, style = MaterialTheme.typography.titleLarge)
            Text("${selected.artist} · ${selected.releaseYear}")
            if (selected.hasCover) {
                AlbumCover(selected, { api.cover(endpoint, token, selected.id) }, onSessionExpired)
            }
            LibraryList(
                load = { api.tracks(endpoint, token, selected.id) },
                emptyMessage = "В этом альбоме пока нет треков.",
                onSessionExpired = onSessionExpired,
                itemKey = LibraryTrack::id,
            ) { track ->
                ListItem(
                    headlineContent = { Text(track.title) },
                    leadingContent = { Text(track.number.toString()) },
                    modifier = Modifier.clickable { playing = track },
                )
            }
        }
    }
}

@Composable
private fun AlbumCover(
    album: LibraryAlbum,
    load: suspend () -> ByteArray?,
    onSessionExpired: () -> Unit,
) {
    var cover by remember(album.id) { mutableStateOf<ImageBitmap?>(null) }
    LaunchedEffect(album.id) {
        try {
            val bytes = load()
            cover = bytes?.let { withContext(Dispatchers.Default) { it.decodeToImageBitmap() } }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: SessionExpired) {
            onSessionExpired()
        } catch (_: Exception) {
            cover = null
        }
    }
    cover?.let { Image(it, contentDescription = null, modifier = Modifier.size(96.dp)) }
}

@Composable
private fun <T> LibraryList(
    load: suspend () -> List<T>,
    emptyMessage: String,
    onSessionExpired: () -> Unit,
    itemKey: (T) -> Long,
    row: @Composable (T) -> Unit,
) {
    var retry by remember { mutableStateOf(0) }
    var entries by remember { mutableStateOf<List<T>?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(retry) {
        error = null
        try {
            entries = load()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: SessionExpired) {
            onSessionExpired()
        } catch (failure: SetupError) {
            error = failure.message
        } catch (_: Exception) {
            error = "Не удалось загрузить библиотеку."
        }
    }
    val result = entries
    val failure = error
    when {
        failure != null -> {
            Text(failure, color = MaterialTheme.colorScheme.error)
            TextButton(onClick = { retry++ }) { Text("Повторить") }
        }

        result == null -> {
            CircularProgressIndicator()
        }

        result.isEmpty() -> {
            Text(emptyMessage)
        }

        else -> {
            LazyColumn { items(result, key = itemKey) { row(it) } }
        }
    }
}
