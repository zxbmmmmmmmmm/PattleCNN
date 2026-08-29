package org.bettafish.huelab

import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull

object FlexibleIntSerializer : KSerializer<Int> {
    override val descriptor = PrimitiveSerialDescriptor("FlexibleInt", PrimitiveKind.INT)
    override fun serialize(encoder: Encoder, value: Int) = encoder.encodeInt(value)
    override fun deserialize(decoder: Decoder): Int {
        val json = decoder as? JsonDecoder ?: return decoder.decodeInt()
        val primitive = json.decodeJsonElement() as? JsonPrimitive
            ?: throw SerializationException("Expected an integer")
        return primitive.intOrNull ?: primitive.content.toIntOrNull()
            ?: throw SerializationException("Expected an integer")
    }
}

@Serializable
data class CredentialsRequest(val username: String, val password: String)

@Serializable
data class RefreshTokenRequest(val refreshToken: String)

@Serializable
data class TokenResponse(
    val accessToken: String,
    val refreshToken: String,
    @Serializable(with = FlexibleIntSerializer::class) val expiresIn: Int,
)

@Serializable
data class ImageTaskResponse(
    val imageId: String,
    val imageName: String,
    val url: String,
    @Serializable(with = FlexibleIntSerializer::class) val expireSeconds: Int,
    @Serializable(with = FlexibleIntSerializer::class) val markedImageCount: Int,
    @Serializable(with = FlexibleIntSerializer::class) val totalImageCount: Int,
    @Serializable(with = FlexibleIntSerializer::class) val currentUserMarkedCount: Int,
)

@Serializable
data class SubmitColorRequest(val colors: List<String>)

@Serializable
data class SubmitColorResponse(val success: Boolean)

@Serializable
data class UserResultResponse(
    val imageId: String,
    val imageName: String,
    val colors: List<String>,
)

@Serializable
data class UserResultPage(
    val items: List<UserResultResponse>,
    @Serializable(with = FlexibleIntSerializer::class) val page: Int,
    @Serializable(with = FlexibleIntSerializer::class) val pageSize: Int,
    @Serializable(with = FlexibleIntSerializer::class) val totalCount: Int,
    @Serializable(with = FlexibleIntSerializer::class) val totalPages: Int? = null,
)

data class TaskPayload(
    val id: String,
    val name: String,
    val imageUrl: String,
    val expireSeconds: Int,
    val progress: AnnotationProgress,
)

data class AnnotationProgress(
    val currentUserMarkedCount: Int,
    val markedImageCount: Int,
    val totalImageCount: Int,
) {
    val fraction: Float
        get() = if (totalImageCount <= 0) 0f
        else markedImageCount.toFloat().div(totalImageCount).coerceIn(0f, 1f)
}

data class HistoryRecord(
    val imageId: String,
    val imageName: String,
    val colors: List<RgbColor>,
)

class ApiException(val status: Int, message: String) : Exception(message)
