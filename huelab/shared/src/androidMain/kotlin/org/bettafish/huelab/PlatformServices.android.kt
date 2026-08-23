package org.bettafish.huelab

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.provider.OpenableColumns
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.security.KeyStore
import java.io.File
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

private object HueLabAndroidEnvironment {
    lateinit var activity: Activity
    var pendingShader: CompletableDeferred<Result<String?>>? = null
}

fun initializeAndroidPlatform(activity: Activity) {
    HueLabAndroidEnvironment.activity = activity
}

fun handleAndroidShaderResult(requestCode: Int, resultCode: Int, data: Intent?): Boolean {
    if (requestCode != ANDROID_SHADER_REQUEST) return false
    val deferred = HueLabAndroidEnvironment.pendingShader ?: return true
    HueLabAndroidEnvironment.pendingShader = null
    if (resultCode != Activity.RESULT_OK || data?.data == null) deferred.complete(Result.success(null))
    else deferred.complete(runCatching {
        val resolver = HueLabAndroidEnvironment.activity.contentResolver
        val name = resolver.query(requireNotNull(data.data), arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) cursor.getString(0) else null
        }
        require(name?.substringAfterLast('.', "")?.lowercase() in setOf("sksl", "agsl", "txt")) {
            "仅支持 .sksl、.agsl 或 .txt 文件"
        }
        resolver.openInputStream(requireNotNull(data.data)).use { input ->
            requireNotNull(input) { "无法读取 Shader 文件" }
            val bytes = input.readBytes()
            require(bytes.size <= 256 * 1024) { "Shader 文件不能超过 256 KB" }
            bytes.decodeToString()
        }
    })
    return true
}

actual fun createPlatformHttpClient(): HttpClient = HttpClient(OkHttp)
actual fun configuredApiBaseUrl(): String? = runCatching {
    HueLabAndroidEnvironment.activity.packageManager
        .getApplicationInfo(
            HueLabAndroidEnvironment.activity.packageName,
            PackageManager.ApplicationInfoFlags.of(PackageManager.GET_META_DATA.toLong()),
        ).metaData?.getString("huelab.api.baseUrl")
}.getOrNull()
actual fun createSecureTokenStore(): SecureTokenStore = AndroidSecureTokenStore(HueLabAndroidEnvironment.activity.applicationContext)
actual fun createImageCache(): ImageCache = AndroidFileImageCache(
    File(HueLabAndroidEnvironment.activity.applicationContext.filesDir, "image-cache"),
)

private class AndroidFileImageCache(private val directory: File) : ImageCache {
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

    private fun fileFor(imageId: String): File = File(directory, "${imageId.androidSafeCacheName()}.img")

    private fun prune() {
        val files = directory.listFiles { file -> file.isFile && file.extension == "img" }
            ?.sortedBy { it.lastModified() } ?: return
        var total = files.sumOf { it.length() }
        if (total <= ANDROID_MAX_CACHE_BYTES) return
        for (file in files) {
            if (total <= ANDROID_CACHE_TRIM_BYTES) break
            val length = file.length()
            if (file.delete()) total -= length
        }
    }
}

private fun String.androidSafeCacheName(): String = map { character ->
    if (character.isLetterOrDigit() || character == '-' || character == '_') character else '_'
}.joinToString("").take(120)

private const val ANDROID_MAX_CACHE_BYTES = 256L * 1024L * 1024L
private const val ANDROID_CACHE_TRIM_BYTES = 200L * 1024L * 1024L

private class AndroidSecureTokenStore(context: Context) : SecureTokenStore {
    private val preferences = context.getSharedPreferences("huelab_secure_session", Context.MODE_PRIVATE)
    private val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }

    override suspend fun read(): String? = withContext(Dispatchers.IO) {
        val encoded = preferences.getString("refresh", null) ?: return@withContext null
        runCatching {
            val all = Base64.decode(encoded, Base64.NO_WRAP)
            val iv = all.copyOfRange(0, 12)
            val encrypted = all.copyOfRange(12, all.size)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, iv))
            cipher.doFinal(encrypted).decodeToString()
        }.getOrElse {
            preferences.edit().remove("refresh").apply()
            null
        }
    }

    override suspend fun write(token: String) = withContext(Dispatchers.IO) {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key())
        val encrypted = cipher.doFinal(token.encodeToByteArray())
        preferences.edit().putString("refresh", Base64.encodeToString(cipher.iv + encrypted, Base64.NO_WRAP)).apply()
    }

    override suspend fun clear() = withContext(Dispatchers.IO) {
        preferences.edit().remove("refresh").apply()
    }

    private fun key(): SecretKey {
        (keyStore.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        generator.init(
            KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            ).setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .build(),
        )
        return generator.generateKey()
    }

    companion object { private const val KEY_ALIAS = "HueLabRefreshToken" }
}

actual fun createShaderFilePicker(): ShaderFilePicker = object : ShaderFilePicker {
    override suspend fun pickShaderSource(): Result<String?> {
        check(HueLabAndroidEnvironment.pendingShader == null) { "已有文件选择操作正在进行" }
        val deferred = CompletableDeferred<Result<String?>>()
        HueLabAndroidEnvironment.pendingShader = deferred
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "text/*"
            putExtra(Intent.EXTRA_MIME_TYPES, arrayOf("text/plain", "application/octet-stream"))
        }
        HueLabAndroidEnvironment.activity.startActivityForResult(intent, ANDROID_SHADER_REQUEST)
        return deferred.await()
    }
}

private const val ANDROID_SHADER_REQUEST = 7042
