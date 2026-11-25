package slopsoft.slopify

import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import org.jetbrains.compose.resources.painterResource
import slopsoft.slopify.resources.Res
import slopsoft.slopify.resources.slopify_icon

fun main() =
    application {
        Window(
            onCloseRequest = ::exitApplication,
            title = "Slopify",
            icon = painterResource(Res.drawable.slopify_icon),
        ) {
            App()
        }
    }
