package slopsoft.slopify

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

@Composable
internal fun OfflineLibrary(
    downloads: List<DownloadedTrack>,
    storage: AppStorage,
    onChange: (List<DownloadedTrack>) -> Unit,
    modifier: Modifier = Modifier,
) {
    val scope = rememberCoroutineScope()
    var playing by remember { mutableStateOf<DownloadedTrack?>(null) }
    var removing by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    Column(modifier) {
        Text("Скачанные треки", style = MaterialTheme.typography.titleLarge)
        error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        playing?.let { entry ->
            key(entry.id) {
                TrackControls(entry.track, load = { entry.audio }, onSessionExpired = {}, onClose = { playing = null })
            }
        }
        if (downloads.isEmpty()) {
            Text("Скачанных треков пока нет.")
        }
        LazyColumn {
            items(downloads, key = DownloadedTrack::id) { entry ->
                ListItem(
                    headlineContent = { Text(entry.track.title) },
                    supportingContent = { Text("${entry.artist} · ${entry.album}") },
                    trailingContent = {
                        TextButton(enabled = !removing, onClick = {
                            removing = true
                            error = null
                            if (playing?.id == entry.id) playing = null
                            scope.launch {
                                try {
                                    storage.removeDownload(entry.id)
                                } catch (cancelled: CancellationException) {
                                    throw cancelled
                                } catch (_: Exception) {
                                    error = "Не удалось удалить скачанный трек."
                                } finally {
                                    removing = false
                                }
                                onChange(storage.downloads())
                            }
                        }) { Text("Удалить") }
                    },
                    modifier = Modifier.clickable { playing = entry },
                )
            }
        }
    }
}
