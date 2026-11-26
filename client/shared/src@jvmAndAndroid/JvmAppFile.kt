package slopsoft.slopify

import java.io.File
import java.io.IOException
import java.security.MessageDigest

internal class JvmAppFile(
    private val file: File,
) {
    val name: String get() = file.name
    val absolutePath: String get() = file.absolutePath
    val size: Long get() = file.length()
    val modified: Long get() = file.lastModified()
    val exists: Boolean get() = file.isFile

    fun resolve(name: String): JvmAppFile = JvmAppFile(file.resolve(name))

    fun children(): List<JvmAppFile> = file.listFiles().orEmpty().map(::JvmAppFile)

    fun touch() {
        file.setLastModified(System.currentTimeMillis())
    }

    fun delete(): Boolean = file.delete()

    fun hash(value: String): String = MessageDigest.getInstance("SHA-256").digest(value.encodeToByteArray()).toHexString()

    fun readText(): String = file.readText()

    suspend fun writeText(value: String) = writeAtomically { it.writeText(value) }

    suspend fun copyFrom(source: JvmAppFile) {
        writeAtomically { source.file.copyTo(it, overwrite = true) }
    }

    suspend fun write(body: suspend ((ByteArray, Int) -> Unit) -> Unit) {
        writeAtomically { partial ->
            partial.outputStream().use { output -> body { bytes, count -> output.write(bytes, 0, count) } }
        }
    }

    private suspend fun writeAtomically(write: suspend (File) -> Unit) {
        val parent = checkNotNull(file.parentFile)
        parent.mkdirs()
        val partial = File.createTempFile(".slopify-", ".part", parent)
        try {
            if (!partial.setReadable(false, false) || !partial.setReadable(true, true)) {
                throw IOException("Could not restrict local file permissions")
            }
            write(partial)
            if (!partial.renameTo(file)) throw IOException("Could not publish local file")
        } finally {
            partial.delete()
        }
    }
}
