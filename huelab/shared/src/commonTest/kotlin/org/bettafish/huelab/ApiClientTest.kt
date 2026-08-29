package org.bettafish.huelab

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.respondError
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.OutgoingContent
import io.ktor.http.content.TextContent
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ApiClientTest {
    @Test
    fun flexibleIntegerAcceptsNumberAndString() {
        val json = Json
        assertEquals(60, json.decodeFromString<TokenResponse>("""{"accessToken":"a","refreshToken":"r","expiresIn":"60"}""").expiresIn)
        assertEquals(60, json.decodeFromString<TokenResponse>("""{"accessToken":"a","refreshToken":"r","expiresIn":60}""").expiresIn)
        val task = json.decodeFromString<ImageTaskResponse>(
            """{"imageId":"id","imageName":"a.jpg","url":"/a","expireSeconds":"600","markedImageCount":"25","totalImageCount":100,"currentUserMarkedCount":"7"}""",
        )
        assertEquals(25, task.markedImageCount)
        assertEquals(100, task.totalImageCount)
        assertEquals(7, task.currentUserMarkedCount)
    }

    @Test
    fun unauthorizedRequestRefreshesAndRetriesWithRotatedToken() = runTest {
        val store = MemoryTokenStore("refresh-1")
        var taskCalls = 0
        var refreshCalls = 0
        val engine = MockEngine { request ->
            when (request.url.encodedPath) {
                "/api/auth/refresh" -> {
                    refreshCalls++
                    respond(
                        """{"accessToken":"access-$refreshCalls","refreshToken":"refresh-${refreshCalls + 1}","expiresIn":"900"}""",
                        headers = jsonHeaders,
                    )
                }
                "/api/images/task" -> {
                    taskCalls++
                    assertTrue(request.headers[HttpHeaders.Authorization]?.startsWith("Bearer access-") == true)
                    if (taskCalls == 1) respondError(HttpStatusCode.Unauthorized)
                    else respond(
                        """{"imageId":"id-1","imageName":"cover.jpg","url":"/api/images/id-1/content","expireSeconds":"600","markedImageCount":25,"totalImageCount":100,"currentUserMarkedCount":7}""",
                        headers = jsonHeaders,
                    )
                }
                else -> error("Unexpected URL ${request.url}")
            }
        }
        val api = HueLabApi(HttpClient(engine), store, "https://test.invalid")
        assertTrue(api.restoreSession())
        val task = api.claimTask()
        assertEquals("id-1", task.id)
        assertEquals(7, task.progress.currentUserMarkedCount)
        assertEquals(.25f, task.progress.fraction)
        assertEquals(2, refreshCalls)
        assertEquals("refresh-3", store.value)
    }

    @Test
    fun submitSendsExactlyFourNormalizedColors() = runTest {
        val store = MemoryTokenStore()
        var submittedBody = ""
        val engine = MockEngine { request ->
            when (request.url.encodedPath) {
                "/api/auth/login" -> respond("""{"accessToken":"access","refreshToken":"refresh","expiresIn":900}""", headers = jsonHeaders)
                "/api/images/image-1/colors" -> {
                    submittedBody = when (val body = request.body) {
                        is TextContent -> body.text
                        is OutgoingContent.ByteArrayContent -> body.bytes().decodeToString()
                        else -> body.toString()
                    }
                    respond("""{"success":true}""", headers = jsonHeaders)
                }
                else -> error("Unexpected URL ${request.url}")
            }
        }
        val api = HueLabApi(HttpClient(engine), store, "https://test.invalid")
        api.login("name", "password")
        api.submitColors("image-1", listOf(RgbColor(1, 2, 3), RgbColor(10, 11, 12), RgbColor(254, 128, 0), RgbColor(255, 255, 255)))
        assertTrue(submittedBody.contains("#010203"))
        assertTrue(submittedBody.contains("#0A0B0C"))
        assertTrue(submittedBody.contains("#FE8000"))
        assertTrue(submittedBody.contains("#FFFFFF"))
    }

    private class MemoryTokenStore(var value: String? = null) : SecureTokenStore {
        override suspend fun read(): String? = value
        override suspend fun write(token: String) { value = token }
        override suspend fun clear() { value = null }
    }

    companion object {
        private val jsonHeaders = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString())
    }
}
