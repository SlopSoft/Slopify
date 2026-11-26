package slopsoft.slopify

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

internal fun ThemeColors.scheme(): ColorScheme {
    val surface = Color(background or 0xFF000000.toInt())
    val primary = Color(accent or 0xFF000000.toInt())
    val light = surface.luminance() > 0.179f
    val defaults = if (light) lightColorScheme() else darkColorScheme()
    return defaults.copy(
        background = surface,
        surface = surface,
        surfaceContainer = surface,
        surfaceTint = primary,
        onBackground = if (light) Color.Black else Color.White,
        onSurface = if (light) Color.Black else Color.White,
        primary = primary,
        onPrimary = if (primary.luminance() > 0.179f) Color.Black else Color.White,
    )
}

@Composable
internal fun ThemeDialog(
    colors: ThemeColors,
    storage: AppStorage,
    onClose: () -> Unit,
    onSave: (ThemeColors) -> Unit,
) {
    val scope = rememberCoroutineScope()
    var background by remember { mutableStateOf(colors.background.hexColor()) }
    var accent by remember { mutableStateOf(colors.accent.hexColor()) }
    var saving by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf(false) }
    val backgroundValue = background.parseColor()
    val accentValue = accent.parseColor()
    AlertDialog(
        onDismissRequest = { if (!saving) onClose() },
        title = { Text("Цвета интерфейса") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = background,
                    onValueChange = { background = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Основной цвет") },
                    placeholder = { Text("#101010") },
                    isError = backgroundValue == null,
                    singleLine = true,
                    enabled = !saving,
                )
                OutlinedTextField(
                    value = accent,
                    onValueChange = { accent = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Акцентный цвет") },
                    placeholder = { Text("#FF963F") },
                    isError = accentValue == null,
                    singleLine = true,
                    enabled = !saving,
                )
                TextButton(enabled = !saving, onClick = {
                    val defaults = ThemeColors()
                    background = defaults.background.hexColor()
                    accent = defaults.accent.hexColor()
                }) { Text("По умолчанию") }
                if (error) Text("Не удалось сохранить цвета.", color = MaterialTheme.colorScheme.error)
            }
        },
        dismissButton = { TextButton(enabled = !saving, onClick = onClose) { Text("Отмена") } },
        confirmButton = {
            TextButton(enabled = !saving && backgroundValue != null && accentValue != null, onClick = {
                val updated = ThemeColors(checkNotNull(backgroundValue), checkNotNull(accentValue))
                saving = true
                scope.launch {
                    try {
                        storage.saveTheme(updated)
                        onSave(updated)
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (_: Exception) {
                        error = true
                    } finally {
                        saving = false
                    }
                }
            }) { Text("Сохранить") }
        },
    )
}

private fun Int.hexColor(): String = "#" + toString(16).padStart(6, '0').uppercase()

private fun String.parseColor(): Int? =
    trim()
        .removePrefix("#")
        .takeIf { it.length == 6 }
        ?.toIntOrNull(16)
        ?.takeIf { it in 0..0xFFFFFF }
