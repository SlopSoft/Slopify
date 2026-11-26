package slopsoft.slopify

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeContentPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import io.ktor.http.Url
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import org.jetbrains.compose.reload.DevelopmentEntryPoint

private enum class SetupStep { Connect, Register, Invited, Created, Configured, SignedIn }

@Composable
@DevelopmentEntryPoint
fun App() {
    val api = remember { SetupApi() }
    val storage = rememberAppStorage()
    DisposableEffect(api) { onDispose { api.close() } }
    val scope = rememberCoroutineScope()
    var step by remember { mutableStateOf(SetupStep.Connect) }
    var address by remember { mutableStateOf("") }
    var endpoint by remember { mutableStateOf<Url?>(null) }
    var session by remember { mutableStateOf<AccountSession?>(null) }
    var signedInAs by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var registrationMessage by remember { mutableStateOf<String?>(null) }
    var inviteCode by remember { mutableStateOf<String?>(null) }
    var offline by remember { mutableStateOf(false) }
    var downloads by remember { mutableStateOf(emptyList<DownloadedTrack>()) }
    var colors by remember { mutableStateOf(ThemeColors()) }
    var editingTheme by remember { mutableStateOf(false) }

    LaunchedEffect(storage) {
        colors = storage.theme()
    }
    LaunchedEffect(storage, offline, step) {
        downloads = storage.downloads()
    }

    fun finishSession() {
        session = null
        signedInAs = ""
        inviteCode = null
        step = SetupStep.Configured
    }

    fun request(action: suspend () -> Unit) {
        if (busy) return
        busy = true
        error = null
        scope.launch {
            try {
                action()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: SessionExpired) {
                finishSession()
                error = "Сессия истекла. Войдите снова."
            } catch (failure: SetupError) {
                error = failure.message
            } catch (_: Exception) {
                error = "Проверьте адрес сервера, сертификат и подключение к сети."
            } finally {
                busy = false
            }
        }
    }

    MaterialTheme(colorScheme = colors.scheme()) {
        if (editingTheme) {
            ThemeDialog(colors, storage, onClose = { editingTheme = false }) {
                colors = it
                editingTheme = false
            }
        }
        inviteCode?.let { code ->
            AlertDialog(
                onDismissRequest = { inviteCode = null },
                title = { Text("Код приглашения") },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("Передайте код пользователю. Он действует 24 часа и подходит для одной регистрации.")
                        SelectionContainer { Text(code) }
                    }
                },
                confirmButton = { TextButton(onClick = { inviteCode = null }) { Text("Закрыть") } },
            )
        }
        Surface(modifier = Modifier.fillMaxSize()) {
            Box(Modifier.safeContentPadding(), contentAlignment = Alignment.Center) {
                Column(
                    modifier =
                        Modifier
                            .widthIn(max = 460.dp)
                            .fillMaxWidth()
                            .then(if (step == SetupStep.SignedIn || offline) Modifier else Modifier.verticalScroll(rememberScrollState()))
                            .padding(24.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Slopify", style = MaterialTheme.typography.headlineLarge, modifier = Modifier.weight(1f))
                        TextButton(enabled = !busy, onClick = { editingTheme = true }) { Text("Цвета") }
                    }
                    if (offline) {
                        TextButton(onClick = { offline = false }) { Text("Назад") }
                        OfflineLibrary(downloads, storage, onChange = { downloads = it }, modifier = Modifier.weight(1f))
                        return@Column
                    }
                    when (step) {
                        SetupStep.Connect -> {
                            OutlinedTextField(
                                value = address,
                                onValueChange = {
                                    address = it
                                    error = null
                                },
                                label = { Text("Адрес сервера") },
                                placeholder = { Text("https://music.example.org") },
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                                singleLine = true,
                                enabled = !busy,
                                modifier = Modifier.fillMaxWidth(),
                            )
                            Button(enabled = !busy && address.isNotBlank(), onClick = {
                                request {
                                    val target = setupEndpoint(address)
                                    val required = api.registrationRequired(target)
                                    endpoint = target
                                    registrationMessage = null
                                    step = if (required) SetupStep.Register else SetupStep.Configured
                                }
                            }) { Text(if (busy) "Подключение…" else "Подключиться") }
                        }

                        SetupStep.Register, SetupStep.Invited -> {
                            val invited = step == SetupStep.Invited
                            CredentialsForm(busy, step) { username, password, code ->
                                val target = checkNotNull(endpoint)
                                request {
                                    val created = api.register(target, username, password, code, invited)
                                    registrationMessage =
                                        if (invited) "Учётная запись создана." else "Учётная запись администратора создана."
                                    step = if (created) SetupStep.Created else SetupStep.Configured
                                }
                            }
                            if (invited) {
                                TextButton(enabled = !busy, onClick = {
                                    step = SetupStep.Configured
                                    error = null
                                }) { Text("Ко входу") }
                            }
                        }

                        SetupStep.Configured, SetupStep.Created -> {
                            if (step == SetupStep.Created) registrationMessage?.let { Text(it) }
                            CredentialsForm(busy, step) { username, password, _ ->
                                request {
                                    session = api.login(checkNotNull(endpoint), username, password)
                                    signedInAs = username
                                    step = SetupStep.SignedIn
                                }
                            }
                            TextButton(enabled = !busy, onClick = {
                                step = SetupStep.Invited
                                error = null
                            }) { Text("Регистрация по приглашению") }
                        }

                        SetupStep.SignedIn -> {
                            val account = checkNotNull(session)
                            val target = checkNotNull(endpoint)
                            Text("Вы вошли как $signedInAs")
                            Library(
                                api = api,
                                endpoint = target,
                                token = account.token,
                                username = signedInAs,
                                storage = storage,
                                downloads = downloads,
                                onDownloadsChanged = { downloads = it },
                                onSessionExpired = {
                                    finishSession()
                                    error = "Сессия истекла. Войдите снова."
                                },
                                modifier = Modifier.weight(1f),
                            )
                            Row {
                                if (account.isAdmin) {
                                    TextButton(enabled = !busy, onClick = {
                                        request { inviteCode = api.invite(target, account.token) }
                                    }) { Text("Пригласить") }
                                }
                                TextButton(enabled = !busy, onClick = {
                                    request {
                                        api.logout(target, account.token)
                                        finishSession()
                                    }
                                }) { Text("Выйти") }
                            }
                        }
                    }
                    if (downloads.isNotEmpty()) {
                        TextButton(enabled = !busy, onClick = { offline = true }) { Text("Скачанные треки") }
                    }
                    error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                    if (step != SetupStep.Connect && step != SetupStep.SignedIn) {
                        Text(address, style = MaterialTheme.typography.bodySmall)
                        TextButton(enabled = !busy, onClick = {
                            step = SetupStep.Connect
                            error = null
                        }) {
                            Text("Сменить сервер")
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun CredentialsForm(
    busy: Boolean,
    step: SetupStep,
    submit: (String, String, String) -> Unit,
) {
    val register = step == SetupStep.Register || step == SetupStep.Invited
    var username by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var code by remember { mutableStateOf("") }
    val title =
        when (step) {
            SetupStep.Register -> "Первый администратор"
            SetupStep.Invited -> "Регистрация по приглашению"
            else -> "Вход"
        }
    Text(title, style = MaterialTheme.typography.titleLarge)
    OutlinedTextField(
        value = username,
        onValueChange = { username = it },
        label = { Text("Имя пользователя") },
        singleLine = true,
        enabled = !busy,
        modifier = Modifier.fillMaxWidth(),
    )
    OutlinedTextField(
        value = password,
        onValueChange = { password = it },
        label = { Text("Пароль") },
        supportingText =
            if (register) {
                { Text("От 15 до 128 символов") }
            } else {
                null
            },
        visualTransformation = PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
        singleLine = true,
        enabled = !busy,
        modifier = Modifier.fillMaxWidth(),
    )
    if (register) {
        OutlinedTextField(
            value = code,
            onValueChange = { code = it },
            label = { Text(if (step == SetupStep.Invited) "Код приглашения" else "Одноразовый код сервера") },
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
            singleLine = true,
            enabled = !busy,
            modifier = Modifier.fillMaxWidth(),
        )
    }
    Button(
        enabled = !busy && username.isNotBlank() && password.isNotEmpty() && (!register || code.isNotBlank()),
        onClick = { submit(username.trim(), password, code.trim()) },
    ) {
        Text(
            when {
                busy -> "Подождите…"
                step == SetupStep.Register -> "Создать администратора"
                step == SetupStep.Invited -> "Зарегистрироваться"
                else -> "Войти"
            },
        )
    }
}
