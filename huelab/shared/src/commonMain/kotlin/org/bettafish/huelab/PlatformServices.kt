package org.bettafish.huelab

import io.ktor.client.HttpClient

interface SecureTokenStore {
    suspend fun read(): String?
    suspend fun write(token: String)
    suspend fun clear()
}

expect fun createSecureTokenStore(): SecureTokenStore
expect fun createPlatformHttpClient(): HttpClient
expect fun configuredApiBaseUrl(): String?

interface ImageCache {
    suspend fun read(imageId: String): ByteArray?
    suspend fun write(imageId: String, bytes: ByteArray)
    suspend fun remove(imageId: String)
}

expect fun createImageCache(): ImageCache

interface ShaderFilePicker {
    suspend fun pickShaderSource(): Result<String?>
}

expect fun createShaderFilePicker(): ShaderFilePicker
