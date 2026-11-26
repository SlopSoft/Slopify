package slopsoft.slopify

import android.os.Build
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext

class AndroidPlatform : Platform {
    override val name: String = "Android ${Build.VERSION.SDK_INT}"
}

actual fun getPlatform(): Platform = AndroidPlatform()

internal actual typealias AppFile = JvmAppFile

@Composable
internal actual fun rememberAppStorage(): AppStorage {
    val context = LocalContext.current.applicationContext
    return remember(context) { AppStorage(AppFile(context.noBackupFilesDir), AppFile(context.cacheDir.resolve("audio"))) }
}
