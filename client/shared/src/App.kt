package slopsoft.slopify

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeContentPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import io.ktor.http.Url
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import org.jetbrains.compose.reload.DevelopmentEntryPoint

private enum class SetupStep { Connect, Register, Created, Configured, SignedIn }

@Composable
@DevelopmentEntryPoint
fun App() {
    val api = remember { SetupApi() }
    DisposableEffect(api) { onDispose { api.close() } }
    val scope = rememberCoroutineScope()
    var step by remember { mutableStateOf(SetupStep.Connect) }
    var address by remember { mutableStateOf("") }
    var endpoint by remember { mutableStateOf<Url?>(null) }
    var token by remember { mutableStateOf("") }
    var signedInAs by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    fun finishSession() {
        token = ""
        signedInAs = ""
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
            } catch (failure: SetupError) {
                error = failure.message
            } catch (_: Exception) {
                error = "Проверьте адрес сервера, сертификат и подключение к сети."
            } finally {
                busy = false
            }
        }
    }

    MaterialTheme(colorScheme = darkColorScheme(primary = Color(0xFFFF963F), surface = Color(0xFF101010))) {
        Surface(modifier = Modifier.fillMaxSize()) {
            Box(Modifier.safeContentPadding(), contentAlignment = Alignment.Center) {
                Column(
                    modifier =
                        Modifier
                            .widthIn(max = 460.dp)
                            .fillMaxWidth()
                            .then(if (step == SetupStep.SignedIn) Modifier else Modifier.verticalScroll(rememberScrollState()))
                            .padding(24.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Text("Slopify", style = MaterialTheme.typography.headlineLarge)
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
                                    step = if (required) SetupStep.Register else SetupStep.Configured
                                }
                            }) { Text(if (busy) "Подключение…" else "Подключиться") }
                        }

                        SetupStep.Register -> {
                            CredentialsForm(busy, register = true) { username, password, code ->
                                val target = checkNotNull(endpoint)
                                request {
                                    step = if (api.register(target, username, password, code)) SetupStep.Created else SetupStep.Configured
                                }
                            }
                        }

                        SetupStep.Created, SetupStep.Configured -> {
                            if (step == SetupStep.Created) Text("Учётная запись администратора создана.")
                            CredentialsForm(busy, register = false) { username, password, _ ->
                                request {
                                    token = api.login(checkNotNull(endpoint), username, password)
                                    signedInAs = username
                                    step = SetupStep.SignedIn
                                }
                            }
                        }

                        SetupStep.SignedIn -> {
                            Text("Вы вошли как $signedInAs")
                            val target = checkNotNull(endpoint)
                            Library(
                                api = api,
                                endpoint = target,
                                token = token,
                                onSessionExpired = {
                                    finishSession()
                                    error = "Сессия истекла. Войдите снова."
                                },
                                modifier = Modifier.weight(1f),
                            )
                            Button(enabled = !busy, onClick = {
                                request {
                                    api.logout(target, token)
                                    finishSession()
                                }
                            }) { Text("Выйти") }
                        }
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
    register: Boolean,
    submit: (String, String, String) -> Unit,
) {
    var username by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var code by remember { mutableStateOf("") }
    Text(if (register) "Первый администратор" else "Вход", style = MaterialTheme.typography.titleLarge)
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
            label = { Text("Одноразовый код сервера") },
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
            if (busy) {
                "Подождите…"
            } else if (register) {
                "Создать администратора"
            } else {
                "Войти"
            },
        )
    }
}
