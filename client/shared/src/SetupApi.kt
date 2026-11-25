package slopsoft.slopify

import io.ktor.client.HttpClient
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.URLProtocol
import io.ktor.http.Url
import io.ktor.http.contentType
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

internal class SetupError(
    message: String,
) : Exception(message)

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

    suspend fun register(
        endpoint: Url,
        username: String,
        password: String,
        code: String,
    ): Boolean {
        val response =
            client.post(endpoint) {
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
            HttpStatusCode.Created -> true
            HttpStatusCode.Conflict -> false
            HttpStatusCode.Forbidden -> throw SetupError("Неверный одноразовый код.")
            HttpStatusCode.UnprocessableEntity -> throw SetupError("Проверьте имя и длину пароля.")
            else -> throw SetupError("Сервер вернул HTTP ${response.status.value} при регистрации.")
        }
    }
}
