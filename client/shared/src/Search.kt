package slopsoft.slopify

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.painterResource
import slopsoft.slopify.resources.Res
import slopsoft.slopify.resources.ic_download
import slopsoft.slopify.resources.ic_play

@Composable
internal fun Search(
    search: suspend (String) -> List<SearchTrack>,
    add: suspend (String) -> LibraryAlbum,
    loadCover: suspend (String) -> ByteArray?,
    playingId: String?,
    onPlay: (SearchTrack) -> Unit,
    onOpen: (LibraryAlbum) -> Unit,
    onBack: () -> Unit,
    onSessionExpired: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val focus = LocalFocusManager.current
    var query by remember { mutableStateOf("") }
    var searching by remember { mutableStateOf(false) }
    var adding by remember { mutableStateOf<String?>(null) }
    var results by remember { mutableStateOf<List<SearchTrack>?>(null) }
    var added by remember { mutableStateOf(emptyMap<String, LibraryAlbum>()) }
    var error by remember { mutableStateOf<String?>(null) }
    val canSearch = query.isNotBlank() && !searching && adding == null

    suspend fun runRequest(action: suspend () -> Unit) {
        error = null
        try {
            action()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: SessionExpired) {
            onSessionExpired()
        } catch (failure: SetupError) {
            error = failure.message
        } catch (_: Exception) {
            error = "Не удалось связаться с сервером. Попробуйте ещё раз."
        }
    }

    fun submit() {
        if (!canSearch) return
        val submitted = query.trim()
        focus.clearFocus()
        searching = true
        results = null
        scope.launch {
            try {
                runRequest { results = search(submitted) }
            } finally {
                searching = false
            }
        }
    }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        TextButton(onClick = onBack) { Text("Библиотека") }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            OutlinedTextField(
                value = query,
                onValueChange = { if (it.length <= 150) query = it },
                label = { Text("Поиск на YouTube") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { submit() }),
                modifier = Modifier.weight(1f),
            )
            Button(enabled = canSearch, onClick = ::submit) { Text("Найти") }
        }
        error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        if (searching) {
            CircularProgressIndicator()
        }
        results?.let { tracks ->
            if (tracks.isEmpty()) {
                Text("Подходящих треков не найдено.")
            }
            LazyColumn {
                items(tracks, key = SearchTrack::id) { track ->
                    ListItem(
                        headlineContent = { Text(track.title) },
                        supportingContent = { Text("${track.artist} · ${track.source}") },
                        leadingContent = {
                            val cover = rememberCover(track.id, { loadCover(track.id) }, onSessionExpired)
                            SearchPlayButton(track.title, cover, enabled = playingId != track.id) { onPlay(track) }
                        },
                        trailingContent = {
                            val album = added[track.id]
                            if (album != null) {
                                TextButton(onClick = { onOpen(album) }) { Text("Открыть") }
                            } else {
                                IconButton(enabled = adding == null, onClick = {
                                    adding = track.id
                                    scope.launch {
                                        try {
                                            runRequest { added = added + (track.id to add(track.id)) }
                                        } finally {
                                            adding = null
                                        }
                                    }
                                }) {
                                    if (adding == track.id) {
                                        CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 2.dp)
                                    } else {
                                        Icon(
                                            painterResource(Res.drawable.ic_download),
                                            contentDescription = "Добавить ${track.title} в библиотеку",
                                        )
                                    }
                                }
                            }
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun SearchPlayButton(
    title: String,
    cover: ImageBitmap?,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    val play = painterResource(Res.drawable.ic_play)
    val description = "Воспроизвести $title"
    IconButton(enabled = enabled, onClick = onClick) {
        if (cover == null) {
            Icon(play, contentDescription = description)
        } else {
            Box(
                modifier = Modifier.size(40.dp).clip(RoundedCornerShape(8.dp)),
                contentAlignment = Alignment.Center,
            ) {
                Image(cover, contentDescription = null, modifier = Modifier.matchParentSize(), contentScale = ContentScale.Crop)
                Box(Modifier.matchParentSize().background(Color.Black.copy(alpha = 0.45f)))
                Icon(play, contentDescription = description, tint = Color.White.copy(alpha = if (enabled) 1f else 0.6f))
            }
        }
    }
}
