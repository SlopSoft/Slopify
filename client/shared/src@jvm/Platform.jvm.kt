package slopsoft.slopify

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import java.io.File

class JVMPlatform : Platform {
    override val name: String = "Java ${System.getProperty("java.version")}"
}

actual fun getPlatform(): Platform = JVMPlatform()

internal actual typealias AppFile = JvmAppFile

@Composable
internal actual fun rememberAppStorage(): AppStorage =
    remember {
        val home = File(System.getProperty("user.home"))
        val data = System.getenv("XDG_DATA_HOME")?.let(::File)?.takeIf(File::isAbsolute) ?: home.resolve(".local/share")
        val cache = System.getenv("XDG_CACHE_HOME")?.let(::File)?.takeIf(File::isAbsolute) ?: home.resolve(".cache")
        AppStorage(AppFile(data.resolve("slopify")), AppFile(cache.resolve("slopify/audio")))
    }
