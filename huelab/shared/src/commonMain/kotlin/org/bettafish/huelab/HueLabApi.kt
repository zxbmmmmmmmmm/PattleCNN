package org.bettafish.huelab

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.accept
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json

class HueLabApi(
    engineClient: HttpClient = createPlatformHttpClient(),
    private val tokenStore: SecureTokenStore = createSecureTokenStore(),
    baseUrl: String = configuredApiBaseUrl() ?: DEFAULT_BASE_URL,
) {
    private val baseUrl = baseUrl.trimEnd('/')
    private val client = engineClient.config {
        install(ContentNegotiation) {
            json(Json { ignoreUnknownKeys = true; isLenient = true })
        }
    }
    private val refreshMutex = Mutex()
    private var accessToken: String? = null
    private var refreshToken: String? = null

    val isAuthenticated: Boolean get() = accessToken != null

    suspend fun restoreSession(): Boolean {
        refreshToken = tokenStore.read()
        if (refreshToken.isNullOrBlank()) return false
        return refreshAccessToken(force = true)
    }

    suspend fun login(username: String, password: String) = authenticate("/api/auth/login", username, password)
    suspend fun register(username: String, password: String) = authenticate("/api/auth/register", username, password)

    private suspend fun authenticate(path: String, username: String, password: String) {
        val response = client.post("$baseUrl$path") {
            contentType(ContentType.Application.Json)
            accept(ContentType.Application.Json)
            setBody(CredentialsRequest(username.trim(), password))
        }
        val tokens = response.bodyOrThrow<TokenResponse>()
        setTokens(tokens)
    }

    suspend fun logout() {
        val token = refreshToken ?: tokenStore.read()
        try {
            if (!token.isNullOrBlank()) {
                client.post("$baseUrl/api/auth/logout") {
                    contentType(ContentType.Application.Json)
                    setBody(RefreshTokenRequest(token))
                }
            }
        } finally {
            accessToken = null
            refreshToken = null
            tokenStore.clear()
        }
    }

    suspend fun claimTask(): TaskPayload {
        val dto = protectedCall { token ->
            client.get("$baseUrl/api/images/task") { bearerAuth(token); accept(ContentType.Application.Json) }
        }.bodyOrThrow<ImageTaskResponse>()
        val imageUrl = if (dto.url.startsWith("http://") || dto.url.startsWith("https://")) dto.url
        else "$baseUrl/${dto.url.trimStart('/')}"
        return TaskPayload(
            id = dto.imageId,
            name = dto.imageName,
            imageUrl = imageUrl,
            expireSeconds = dto.expireSeconds,
            progress = AnnotationProgress(
                currentUserMarkedCount = dto.currentUserMarkedCount,
                markedImageCount = dto.markedImageCount,
                totalImageCount = dto.totalImageCount,
            ),
        )
    }

    suspend fun downloadImage(imageId: String, preferredUrl: String? = null): ByteArray {
        val safeUrl = preferredUrl?.takeIf { it.startsWith("https://") } ?: "$baseUrl/api/images/$imageId/content"
        return protectedCall { token -> client.get(safeUrl) { bearerAuth(token) } }.bodyOrThrow()
    }

    suspend fun submitColors(imageId: String, colors: List<RgbColor>) {
        require(colors.size == 4) { "必须提交四个颜色" }
        val result = protectedCall { token ->
            client.post("$baseUrl/api/images/$imageId/colors") {
                bearerAuth(token)
                contentType(ContentType.Application.Json)
                setBody(SubmitColorRequest(colors.map { it.hex }))
            }
        }.bodyOrThrow<SubmitColorResponse>()
        if (!result.success) throw ApiException(200, "服务器未接受本次标注")
    }

    suspend fun history(page: Int, pageSize: Int = 24): UserResultPage =
        protectedCall { token ->
            client.get("$baseUrl/api/users/me/results") {
                bearerAuth(token)
                url { parameters.append("Page", page.toString()); parameters.append("PageSize", pageSize.toString()) }
                accept(ContentType.Application.Json)
            }
        }.bodyOrThrow()

    private suspend fun protectedCall(block: suspend (String) -> HttpResponse): HttpResponse {
        val initialToken = accessToken ?: if (refreshAccessToken(force = false)) accessToken else null
        val first = block(initialToken ?: throw ApiException(401, "登录已失效，请重新登录"))
        if (first.status != HttpStatusCode.Unauthorized) return first
        if (!refreshAccessToken(force = true, staleAccessToken = initialToken)) {
            throw ApiException(401, "登录已失效，请重新登录")
        }
        return block(accessToken ?: throw ApiException(401, "登录已失效，请重新登录"))
    }

    private suspend fun refreshAccessToken(force: Boolean, staleAccessToken: String? = null): Boolean = refreshMutex.withLock {
        if (force && staleAccessToken != null && accessToken != staleAccessToken && accessToken != null) return@withLock true
        if (!force && accessToken != null) return@withLock true
        val token = refreshToken ?: tokenStore.read()?.also { refreshToken = it } ?: return@withLock false
        return@withLock try {
            val response = client.post("$baseUrl/api/auth/refresh") {
                contentType(ContentType.Application.Json)
                accept(ContentType.Application.Json)
                setBody(RefreshTokenRequest(token))
            }
            if (!response.status.isSuccess()) throw ApiException(response.status.value, "刷新登录状态失败")
            setTokens(response.body())
            true
        } catch (_: Exception) {
            accessToken = null
            refreshToken = null
            tokenStore.clear()
            false
        }
    }

    private suspend fun setTokens(tokens: TokenResponse) {
        accessToken = tokens.accessToken
        refreshToken = tokens.refreshToken
        tokenStore.write(tokens.refreshToken)
    }

    private suspend inline fun <reified T> HttpResponse.bodyOrThrow(): T {
        if (!status.isSuccess()) {
            val detail = runCatching { bodyAsText() }.getOrDefault("").take(300)
            throw ApiException(status.value, userMessage(status, detail))
        }
        return body()
    }

    companion object {
        const val DEFAULT_BASE_URL = "https://huelab.raspberrykan.dev:16386"
        private fun userMessage(status: HttpStatusCode, detail: String): String = when (status.value) {
            400 -> "请求内容不正确${detail.takeIf { it.isNotBlank() }?.let { "：$it" } ?: ""}"
            401 -> "用户名、密码或登录状态无效"
            403 -> "没有执行此操作的权限"
            404 -> "没有找到请求的数据"
            409 -> "该用户名或数据已存在"
            in 500..599 -> "服务器暂时不可用（${status.value}）"
            else -> "请求失败（${status.value}）"
        }
    }
}

private fun HttpStatusCode.isSuccess(): Boolean = value in 200..299
