package slopsoft.slopify

import io.ktor.client.HttpClient
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.HttpTimeoutConfig
import io.ktor.client.plugins.timeout
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.head
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.prepareGet
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsChannel
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.URLProtocol
import io.ktor.http.Url
import io.ktor.http.contentType
import io.ktor.utils.io.readAvailable
import io.ktor.utils.io.readByteArray
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.put

private const val MAX_COVER_BYTES = 5 * 1024 * 1024
private const val MAX_PREVIEW_BYTES = 32 * 1024 * 1024

internal class SetupError(
    message: String,
) : Exception(message)

internal class SessionExpired : Exception()

internal data class AccountSession(
    val token: String,
    val isAdmin: Boolean,
)

internal data class LibraryAlbum(
    val id: Long,
    val title: String,
    val artist: String,
    val releaseYear: Int,
    val hasCover: Boolean,
)

internal data class LibraryTrack(
    val id: Long,
    val title: String,
    val number: Int,
)

internal data class SearchTrack(
    val id: String,
    val title: String,
    val artist: String,
    val source: String,
)

internal sealed interface TrackAudio {
    data class Local(
        val path: String,
    ) : TrackAudio

    class Remote(
        val url: Url,
        val token: String,
        val size: Long,
        val revision: String?,
        val read: suspend (start: Long, count: Int) -> ByteArray,
    ) : TrackAudio
}

internal fun setupEndpoint(address: String): Url {
    val base = address.trim().trimEnd('/')
    val url = runCatching { Url(base) }.getOrNull()
    if (url == null || !base.startsWith("${url.protocol.name}://", ignoreCase = true) ||
        base.any { it.isWhitespace() || it == '\\' || it.isISOControl() } ||
        url.host.isBlank() || url.user != null || url.password != null || '?' in base || '#' in base
    ) {
        throw SetupError("Введите адрес сервера, например https://music.example.org")
    }
    val localHttp = url.protocol == URLProtocol.HTTP && url.host in setOf("localhost", "127.0.0.1")
    if (url.protocol != URLProtocol.HTTPS && !localHttp) {
        throw SetupError("Для удалённого сервера нужен адрес с HTTPS.")
    }
    return Url("$base/setup")
}

internal class SetupApi {
    private val client =
        HttpClient {
            followRedirects = false
            install(HttpTimeout) {
                requestTimeoutMillis = 15_000
            }
        }

    fun close() = client.close()

    suspend fun registrationRequired(endpoint: Url): Boolean {
        val response = client.get(endpoint)
        if (response.status != HttpStatusCode.OK) {
            throw SetupError("Сервер вернул HTTP ${response.status.value} при проверке настройки.")
        }
        val required = Json.parseToJsonElement(response.bodyAsText()).jsonObject["registration_required"]?.jsonPrimitive
        if (required == null || required.isString || required.content !in setOf("true", "false")) {
            throw SetupError("Сервер вернул неизвестный ответ на запрос настройки.")
        }
        return required.boolean
    }

    private fun apiEndpoint(
        setup: Url,
        path: String,
    ): Url = Url(setup.toString().removeSuffix("/setup") + path)

    suspend fun albums(
        endpoint: Url,
        token: String,
    ): List<LibraryAlbum> = libraryResponse(endpoint, token, "/library/albums").map { album(it.jsonObject) }

    private fun album(fields: JsonObject) =
        LibraryAlbum(
            fields.getValue("id").jsonPrimitive.long,
            fields.getValue("title").jsonPrimitive.content,
            fields.getValue("artist").jsonPrimitive.content,
            fields.getValue("release_year").jsonPrimitive.int,
            fields.getValue("has_cover").jsonPrimitive.boolean,
        )

    suspend fun search(
        endpoint: Url,
        token: String,
        query: String,
    ): List<SearchTrack> {
        val response =
            client.post(apiEndpoint(endpoint, "/search")) {
                bearerAuth(token)
                contentType(ContentType.Application.Json)
                setBody(buildJsonObject { put("query", query) }.toString())
            }
        return when (response.status) {
            HttpStatusCode.OK -> {
                Json.parseToJsonElement(response.bodyAsText()).jsonArray.map {
                    val result = it.jsonObject
                    SearchTrack(
                        result.getValue("id").jsonPrimitive.content,
                        result.getValue("title").jsonPrimitive.content,
                        result.getValue("artist").jsonPrimitive.content,
                        result.getValue("source").jsonPrimitive.content,
                    )
                }
            }

            HttpStatusCode.NotFound -> {
                throw SetupError("Поиск на этом сервере недоступен.")
            }

            else -> {
                throw importError(response.status)
            }
        }
    }

    suspend fun importYouTubeTrack(
        endpoint: Url,
        token: String,
        id: String,
    ): LibraryAlbum {
        val response =
            client.put(apiEndpoint(endpoint, "/library/imports/youtube/$id")) {
                bearerAuth(token)
                timeout {
                    requestTimeoutMillis = 170_000
                    socketTimeoutMillis = 170_000
                }
            }
        return when (response.status) {
            HttpStatusCode.OK, HttpStatusCode.Created -> album(Json.parseToJsonElement(response.bodyAsText()).jsonObject)
            HttpStatusCode.NotFound -> throw SetupError("Этот трек недоступен для загрузки.")
            else -> throw importError(response.status)
        }
    }

    suspend fun downloadYouTubeAudio(
        endpoint: Url,
        token: String,
        id: String,
        write: (ByteArray, Int) -> Unit,
    ) {
        client
            .prepareGet(apiEndpoint(endpoint, "/search/youtube/$id/audio")) {
                bearerAuth(token)
                timeout {
                    requestTimeoutMillis = 170_000
                    socketTimeoutMillis = 170_000
                }
            }.execute { response ->
                when (response.status) {
                    HttpStatusCode.OK -> Unit
                    HttpStatusCode.NotFound -> throw SetupError("Этот трек недоступен для воспроизведения.")
                    else -> throw importError(response.status)
                }
                val channel = response.bodyAsChannel()
                val buffer = ByteArray(64 * 1024)
                var total = 0
                while (true) {
                    val count = channel.readAvailable(buffer, 0, buffer.size)
                    if (count < 0) break
                    total += count
                    if (total > MAX_PREVIEW_BYTES) throw SetupError("Аудиофайл слишком большой.")
                    write(buffer, count)
                }
                if (total == 0) throw SetupError("Аудиофайл недоступен.")
            }
    }

    private fun importError(status: HttpStatusCode): Exception =
        when (status) {
            HttpStatusCode.Unauthorized -> SessionExpired()
            HttpStatusCode.Conflict -> SetupError("В библиотеке уже есть релиз с таким названием, исполнителем и годом.")
            HttpStatusCode.TooManyRequests -> SetupError("Сервер занят. Попробуйте ещё раз чуть позже.")
            HttpStatusCode.BadGateway, HttpStatusCode.ServiceUnavailable -> SetupError("YouTube или загрузчик сейчас недоступен.")
            HttpStatusCode.GatewayTimeout -> SetupError("Сервер не успел обработать запрос. Попробуйте ещё раз.")
            else -> SetupError("Не удалось обработать запрос. HTTP ${status.value}.")
        }

    suspend fun cover(
        endpoint: Url,
        token: String,
        albumId: Long,
    ): ByteArray? = cover(endpoint, token, "/library/albums/$albumId/cover")

    suspend fun searchCover(
        endpoint: Url,
        token: String,
        id: String,
    ): ByteArray? = cover(endpoint, token, "/search/youtube/$id/cover")

    private suspend fun cover(
        endpoint: Url,
        token: String,
        path: String,
    ): ByteArray? =
        client
            .prepareGet(apiEndpoint(endpoint, path)) { bearerAuth(token) }
            .execute { response ->
                if (response.status == HttpStatusCode.Unauthorized) throw SessionExpired()
                val length = response.headers[HttpHeaders.ContentLength]?.toIntOrNull()
                if (response.status != HttpStatusCode.OK || length == null || length !in 1..MAX_COVER_BYTES) {
                    return@execute null
                }
                response.bodyAsChannel().readByteArray(length)
            }

    suspend fun audio(
        endpoint: Url,
        token: String,
        trackId: Long,
    ): TrackAudio.Remote {
        val url = apiEndpoint(endpoint, "/library/tracks/$trackId/audio")
        val response = client.head(url) { bearerAuth(token) }
        if (response.status == HttpStatusCode.Unauthorized) throw SessionExpired()
        val size = response.headers[HttpHeaders.ContentLength]?.toLongOrNull()
        if (response.status != HttpStatusCode.OK || size == null || size <= 0) {
            throw SetupError("Аудиофайл недоступен.")
        }
        val revision = response.headers[HttpHeaders.ETag]
        return TrackAudio.Remote(url, token, size, revision) { start, count ->
            val range = "$start-${start + count - 1}"
            client
                .prepareGet(url) {
                    bearerAuth(token)
                    header(HttpHeaders.Range, "bytes=$range")
                }.execute { part ->
                    if (part.status == HttpStatusCode.Unauthorized) throw SessionExpired()
                    if (part.status != HttpStatusCode.PartialContent || part.headers[HttpHeaders.ContentRange] != "bytes $range/$size") {
                        throw SetupError("Сервер не вернул запрошенный диапазон аудио.")
                    }
                    part.bodyAsChannel().readByteArray(count)
                }
        }
    }

    suspend fun download(
        audio: TrackAudio.Remote,
        write: (ByteArray, Int) -> Unit,
    ) {
        client
            .prepareGet(audio.url) {
                bearerAuth(audio.token)
                timeout {
                    requestTimeoutMillis = HttpTimeoutConfig.INFINITE_TIMEOUT_MS
                    socketTimeoutMillis = 15_000
                }
            }.execute { response ->
                if (response.status == HttpStatusCode.Unauthorized) throw SessionExpired()
                if (response.status != HttpStatusCode.OK ||
                    response.headers[HttpHeaders.ContentLength]?.toLongOrNull() != audio.size ||
                    response.headers[HttpHeaders.ETag] != audio.revision
                ) {
                    throw SetupError("Не удалось загрузить аудиофайл. Попробуйте ещё раз.")
                }
                val channel = response.bodyAsChannel()
                val buffer = ByteArray(64 * 1024)
                var remaining = audio.size
                while (remaining > 0) {
                    val count = channel.readAvailable(buffer, 0, minOf(remaining, buffer.size.toLong()).toInt())
                    if (count < 0) throw SetupError("Загрузка аудиофайла прервалась.")
                    write(buffer, count)
                    remaining -= count
                }
            }
    }

    suspend fun tracks(
        endpoint: Url,
        token: String,
        albumId: Long,
    ): List<LibraryTrack> =
        libraryResponse(endpoint, token, "/library/albums/$albumId/tracks").map {
            val track = it.jsonObject
            LibraryTrack(
                track.getValue("id").jsonPrimitive.long,
                track.getValue("title").jsonPrimitive.content,
                track.getValue("track_number").jsonPrimitive.int,
            )
        }

    private suspend fun libraryResponse(
        endpoint: Url,
        token: String,
        path: String,
    ) = client.get(apiEndpoint(endpoint, path)) { bearerAuth(token) }.let { response ->
        when (response.status) {
            HttpStatusCode.OK -> Json.parseToJsonElement(response.bodyAsText()).jsonArray
            HttpStatusCode.Unauthorized -> throw SessionExpired()
            HttpStatusCode.NotFound -> throw SetupError("Альбом больше не доступен.")
            else -> throw SetupError("Не удалось загрузить библиотеку. HTTP ${response.status.value}.")
        }
    }

    suspend fun login(
        endpoint: Url,
        username: String,
        password: String,
    ): AccountSession {
        val response =
            client.post(apiEndpoint(endpoint, "/session")) {
                contentType(ContentType.Application.Json)
                setBody(
                    buildJsonObject {
                        put("username", username)
                        put("password", password)
                    }.toString(),
                )
            }
        return when (response.status) {
            HttpStatusCode.Created -> {
                val session = Json.parseToJsonElement(response.bodyAsText()).jsonObject
                val token = session.getValue("token").jsonPrimitive.content
                if (token.length != 64 || token.any { it !in '0'..'9' && it !in 'a'..'f' }) {
                    throw SetupError("Сервер вернул некорректный токен сессии.")
                }
                AccountSession(token, session["is_admin"]?.jsonPrimitive?.boolean == true)
            }

            HttpStatusCode.Unauthorized -> {
                throw SetupError("Неверное имя пользователя или пароль.")
            }

            HttpStatusCode.TooManyRequests -> {
                throw SetupError("Сервер занят. Повторите вход чуть позже.")
            }

            else -> {
                throw SetupError("Сервер вернул HTTP ${response.status.value} при входе.")
            }
        }
    }

    suspend fun logout(
        endpoint: Url,
        token: String,
    ) {
        val response = client.delete(apiEndpoint(endpoint, "/session")) { bearerAuth(token) }
        if (response.status != HttpStatusCode.NoContent && response.status != HttpStatusCode.Unauthorized) {
            throw SetupError("Сервер вернул HTTP ${response.status.value} при выходе.")
        }
    }

    suspend fun register(
        endpoint: Url,
        username: String,
        password: String,
        code: String,
        invited: Boolean = false,
    ): Boolean {
        val response =
            client.post(if (invited) apiEndpoint(endpoint, "/accounts") else endpoint) {
                contentType(ContentType.Application.Json)
                setBody(
                    buildJsonObject {
                        put("username", username)
                        put("password", password)
                        put("code", code)
                    }.toString(),
                )
            }
        return when (response.status) {
            HttpStatusCode.Created -> {
                true
            }

            HttpStatusCode.Conflict -> {
                if (invited) throw SetupError("Это имя пользователя уже занято.")
                false
            }

            HttpStatusCode.Forbidden -> {
                throw SetupError("Код недействителен, уже использован или истёк.")
            }

            HttpStatusCode.UnprocessableEntity -> {
                throw SetupError("Проверьте имя и длину пароля.")
            }

            HttpStatusCode.TooManyRequests -> {
                throw SetupError("Сервер занят. Повторите регистрацию чуть позже.")
            }

            else -> {
                throw SetupError("Сервер вернул HTTP ${response.status.value} при регистрации.")
            }
        }
    }

    suspend fun invite(
        endpoint: Url,
        token: String,
    ): String {
        val response = client.post(apiEndpoint(endpoint, "/invitations")) { bearerAuth(token) }
        return when (response.status) {
            HttpStatusCode.Created -> {
                Json
                    .parseToJsonElement(response.bodyAsText())
                    .jsonObject
                    .getValue("code")
                    .jsonPrimitive.content
            }

            HttpStatusCode.Unauthorized -> {
                throw SessionExpired()
            }

            HttpStatusCode.Forbidden -> {
                throw SetupError("Создавать приглашения может только администратор.")
            }

            else -> {
                throw SetupError("Сервер вернул HTTP ${response.status.value} при создании приглашения.")
            }
        }
    }
}
