package slopsoft.slopify

import androidx.compose.runtime.Composable
import io.ktor.http.Url
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put

private const val CACHE_LIMIT = 256L * 1024 * 1024

internal class AppStorage(
    private val dataDir: AppFile,
    private val cacheDir: AppFile,
) {
    private val downloadDir = dataDir.resolve("downloads")
    private val mutex = Mutex()

    fun trackId(
        endpoint: Url,
        username: String,
        trackId: Long,
    ): String = dataDir.hash("$endpoint\n$username\n$trackId")

    suspend fun cached(
        id: String,
        audio: TrackAudio.Remote,
    ): TrackAudio.Local? =
        withContext(Dispatchers.IO) {
            val file = findCache(id, audio) ?: findSavedAudio(id, audio)
            file?.let { TrackAudio.Local(it.absolutePath) }
        }

    suspend fun cache(
        id: String,
        audio: TrackAudio.Remote,
        api: SetupApi,
    ) {
        if (audio.revision == null || audio.size > CACHE_LIMIT) return
        withContext(Dispatchers.IO) {
            mutex.withLock {
                if (findCache(id, audio) != null || findSavedAudio(id, audio) != null) return@withLock
                val files = cacheDir.children().filter { it.name.endsWith(".mp3") }
                var used = files.sumOf(AppFile::size)
                for (file in files.sortedBy(AppFile::modified)) {
                    if (used + audio.size <= CACHE_LIMIT) break
                    val size = file.size
                    if (file.delete()) used -= size
                }
                if (used + audio.size <= CACHE_LIMIT) {
                    cacheFile(id, audio).write { write -> api.download(audio, write) }
                }
            }
        }
    }

    suspend fun save(
        id: String,
        album: LibraryAlbum,
        track: LibraryTrack,
        audio: TrackAudio.Remote,
        api: SetupApi,
    ) {
        withContext(Dispatchers.IO) {
            mutex.withLock {
                if (readDownload(id) != null) return@withLock
                val target = downloadDir.resolve("$id.mp3")
                val existing = findCache(id, audio)
                if (existing == null) {
                    target.write { write -> api.download(audio, write) }
                } else {
                    target.copyFrom(existing)
                }
                try {
                    val details =
                        buildJsonObject {
                            put("trackId", track.id)
                            put("track", track.title)
                            put("number", track.number)
                            put("album", album.title)
                            put("artist", album.artist)
                            put("size", audio.size)
                            put("revision", audio.revision)
                        }
                    downloadDir.resolve("$id.json").writeText(details.toString())
                } catch (failure: Exception) {
                    target.delete()
                    throw failure
                }
            }
        }
    }

    suspend fun downloads(): List<DownloadedTrack> =
        withContext(Dispatchers.IO) {
            downloadDir
                .children()
                .filter { it.name.endsWith(".json") }
                .mapNotNull { readDownload(it.name.removeSuffix(".json")) }
                .sortedWith(compareBy(DownloadedTrack::artist, DownloadedTrack::album, { it.track.number }, { it.track.title }))
        }

    suspend fun removeDownload(id: String) {
        withContext(Dispatchers.IO) {
            mutex.withLock {
                for (extension in listOf("json", "mp3")) {
                    val file = downloadDir.resolve("$id.$extension")
                    check(file.delete() || !file.exists) { "Could not remove local download" }
                }
            }
        }
    }

    suspend fun theme(): ThemeColors =
        withContext(Dispatchers.IO) {
            val defaults = ThemeColors()
            val values = readJson(dataDir.resolve("theme.json"))
            ThemeColors(
                background = values.color("background") ?: defaults.background,
                accent = values.color("accent") ?: defaults.accent,
            )
        }

    suspend fun saveTheme(colors: ThemeColors) {
        withContext(Dispatchers.IO) {
            val values =
                buildJsonObject {
                    put("background", colors.background)
                    put("accent", colors.accent)
                }
            dataDir.resolve("theme.json").writeText(values.toString())
        }
    }

    private fun findCache(
        id: String,
        audio: TrackAudio.Remote,
    ): AppFile? =
        audio.revision?.let {
            cacheFile(id, audio).takeIf { it.exists && it.size == audio.size }?.also { it.touch() }
        }

    private fun cacheFile(
        id: String,
        audio: TrackAudio.Remote,
    ): AppFile = cacheDir.resolve("$id-${cacheDir.hash(audio.revision.orEmpty())}.mp3")

    private fun findSavedAudio(
        id: String,
        audio: TrackAudio.Remote,
    ): AppFile? {
        val revision = audio.revision ?: return null
        val file = downloadDir.resolve("$id.mp3")
        if (file.size != audio.size) return null
        val details = readJson(downloadDir.resolve("$id.json"))
        return file.takeIf { details.string("revision") == revision }
    }

    private fun readDownload(id: String): DownloadedTrack? {
        val file = downloadDir.resolve("$id.mp3")
        if (!file.exists || file.size <= 0) return null
        val values = readJson(downloadDir.resolve("$id.json"))
        if (values.string("size")?.toLongOrNull() != file.size) return null
        return DownloadedTrack(
            id = id,
            track =
                LibraryTrack(
                    id = values.string("trackId")?.toLongOrNull() ?: return null,
                    title = values.string("track") ?: return null,
                    number = values.string("number")?.toIntOrNull() ?: return null,
                ),
            album = values.string("album") ?: return null,
            artist = values.string("artist") ?: return null,
            audio = TrackAudio.Local(file.absolutePath),
        )
    }

    private fun readJson(file: AppFile): JsonObject =
        runCatching { Json.parseToJsonElement(file.readText()).jsonObject }.getOrElse { JsonObject(emptyMap()) }

    private fun JsonObject.string(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull

    private fun JsonObject.color(key: String): Int? = string(key)?.toIntOrNull()?.takeIf { it in 0..0xFFFFFF }
}

internal data class DownloadedTrack(
    val id: String,
    val track: LibraryTrack,
    val album: String,
    val artist: String,
    val audio: TrackAudio.Local,
)

internal data class ThemeColors(
    val background: Int = 0x101010,
    val accent: Int = 0xFF963F,
)

internal expect class AppFile {
    val name: String
    val absolutePath: String
    val size: Long
    val modified: Long
    val exists: Boolean

    fun resolve(name: String): AppFile

    fun children(): List<AppFile>

    fun touch()

    fun delete(): Boolean

    fun hash(value: String): String

    fun readText(): String

    suspend fun writeText(value: String)

    suspend fun copyFrom(source: AppFile)

    suspend fun write(body: suspend ((ByteArray, Int) -> Unit) -> Unit)
}

@Composable
internal expect fun rememberAppStorage(): AppStorage
