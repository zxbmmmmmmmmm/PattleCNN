package org.bettafish.huelab

import com.microsoft.credentialstorage.StorageProvider
import com.microsoft.credentialstorage.model.StoredToken
import com.microsoft.credentialstorage.model.StoredTokenType
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import javax.swing.JFileChooser
import javax.swing.filechooser.FileNameExtensionFilter

private const val TOKEN_KEY = "org.bettafish.huelab.refresh-token"

actual fun createPlatformHttpClient(): HttpClient = HttpClient(CIO)
actual fun configuredApiBaseUrl(): String? =
    System.getProperty("huelab.api.baseUrl") ?: System.getenv("HUELAB_API_BASE_URL")

actual fun createSecureTokenStore(): SecureTokenStore = DesktopSecureTokenStore()
actual fun createImageCache(): ImageCache = FileImageCache(
    File(System.getProperty("user.home"), ".huelab/image-cache"),
)

private class FileImageCache(private val directory: File) : ImageCache {
    override suspend fun read(imageId: String): ByteArray? = withContext(Dispatchers.IO) {
        val file = fileFor(imageId)
        if (!file.isFile) return@withContext null
        runCatching {
            file.setLastModified(System.currentTimeMillis())
            file.readBytes()
        }.getOrNull()
    }

    override suspend fun write(imageId: String, bytes: ByteArray) = withContext(Dispatchers.IO) {
        directory.mkdirs()
        val target = fileFor(imageId)
        val temporary = File.createTempFile("${target.name}.", ".tmp", directory)
        temporary.writeBytes(bytes)
        if (target.exists()) target.delete()
        if (!temporary.renameTo(target)) {
            target.writeBytes(bytes)
            temporary.delete()
        }
        prune()
    }

    override suspend fun remove(imageId: String) = withContext(Dispatchers.IO) {
        fileFor(imageId).delete()
        Unit
    }

    private fun fileFor(imageId: String): File = File(directory, "${imageId.safeCacheName()}.img")

    private fun prune() {
        val files = directory.listFiles { file -> file.isFile && file.extension == "img" }
            ?.sortedBy { it.lastModified() } ?: return
        var total = files.sumOf { it.length() }
        if (total <= MAX_CACHE_BYTES) return
        for (file in files) {
            if (total <= CACHE_TRIM_BYTES) break
            val length = file.length()
            if (file.delete()) total -= length
        }
    }
}

private fun String.safeCacheName(): String = map { character ->
    if (character.isLetterOrDigit() || character == '-' || character == '_') character else '_'
}.joinToString("").take(120)

private const val MAX_CACHE_BYTES = 256L * 1024L * 1024L
private const val CACHE_TRIM_BYTES = 200L * 1024L * 1024L

private class DesktopSecureTokenStore : SecureTokenStore {
    private val storage = StorageProvider.getTokenStorage(true, StorageProvider.SecureOption.REQUIRED)
    private var sessionFallback: String? = null

    override suspend fun read(): String? = withContext(Dispatchers.IO) {
        val stored = runCatching { storage?.get(TOKEN_KEY) }.getOrNull()
        stored?.value?.concatToString() ?: sessionFallback
    }

    override suspend fun write(token: String) = withContext(Dispatchers.IO) {
        val saved = runCatching {
            storage?.add(TOKEN_KEY, StoredToken(token.toCharArray(), StoredTokenType.REFRESH)) == true
        }.getOrDefault(false)
        if (!saved) sessionFallback = token
    }

    override suspend fun clear() = withContext(Dispatchers.IO) {
        runCatching { storage?.delete(TOKEN_KEY) }
        sessionFallback = null
    }
}

actual fun createShaderFilePicker(): ShaderFilePicker = DesktopShaderFilePicker()

private class DesktopShaderFilePicker : ShaderFilePicker {
    override suspend fun pickShaderSource(): Result<String?> = withContext(Dispatchers.IO) {
        runCatching {
            val chooser = JFileChooser().apply {
                dialogTitle = "选择 Shader 源码"
                fileFilter = FileNameExtensionFilter("Shader 源码 (*.sksl, *.agsl, *.txt)", "sksl", "agsl", "txt")
            }
            if (chooser.showOpenDialog(null) != JFileChooser.APPROVE_OPTION) return@runCatching null
            readShader(chooser.selectedFile)
        }
    }

    private fun readShader(file: File): String {
        require(file.extension.lowercase() in setOf("sksl", "agsl", "txt")) { "仅支持 .sksl、.agsl 或 .txt 文件" }
        require(file.length() <= 256 * 1024) { "Shader 文件不能超过 256 KB" }
        return file.readText(Charsets.UTF_8)
    }
}
