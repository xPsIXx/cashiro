package com.pennywiseai.tracker.data.ai

import io.ktor.client.HttpClient
import io.ktor.client.engine.android.Android
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import javax.inject.Inject
import javax.inject.Singleton

data class ChatTurn(val role: String, val content: String)

@Singleton
class ByokClient @Inject constructor() {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    private val client = HttpClient(Android) {
        expectSuccess = false
    }

    suspend fun complete(
        provider: String,
        apiKey: String,
        model: String,
        baseUrl: String?,
        systemPrompt: String,
        turns: List<ChatTurn>
    ): String = withContext(Dispatchers.IO) {
        when (provider.lowercase()) {
            "anthropic" -> anthropic(apiKey, model, systemPrompt, turns)
            "google", "gemini" -> gemini(apiKey, model, systemPrompt, turns)
            else -> openaiCompat(provider, apiKey, model, baseUrl, systemPrompt, turns)
        }
    }

    private fun openaiBase(provider: String, baseUrl: String?): String {
        val custom = baseUrl?.trim().orEmpty()
        if (custom.isNotBlank()) {
            var u = custom.trimEnd('/')
            if (!u.startsWith("http")) u = "https://$u"
            return if (u.endsWith("/v1")) u else "$u/v1"
        }
        return when (provider.lowercase()) {
            "openai" -> "https://api.openai.com/v1"
            "groq" -> "https://api.groq.com/openai/v1"
            "openrouter" -> "https://openrouter.ai/api/v1"
            "xai" -> "https://api.x.ai/v1"
            else -> "https://api.openai.com/v1"
        }
    }

    private suspend fun openaiCompat(
        provider: String,
        apiKey: String,
        model: String,
        baseUrl: String?,
        systemPrompt: String,
        turns: List<ChatTurn>
    ): String {
        val messages = buildJsonArray {
            add(buildJsonObject {
                put("role", "system")
                put("content", systemPrompt)
            })
            turns.forEach { turn ->
                add(buildJsonObject {
                    put("role", if (turn.role == "user") "user" else "assistant")
                    put("content", turn.content)
                })
            }
        }
        val body = buildJsonObject {
            put("model", model)
            put("temperature", 0.3)
            put("max_tokens", 700)
            put("messages", messages)
        }
        val response = client.post("${openaiBase(provider, baseUrl)}/chat/completions") {
            header("Authorization", "Bearer $apiKey")
            contentType(ContentType.Application.Json)
            setBody(body.toString())
        }
        val text = response.bodyAsText()
        if (!response.status.isSuccess()) {
            throw IllegalStateException("Provider HTTP ${response.status.value}: ${text.take(240)}")
        }
        val root = json.parseToJsonElement(text).jsonObject
        return root["choices"]?.jsonArray?.firstOrNull()
            ?.jsonObject?.get("message")?.jsonObject
            ?.get("content")?.jsonPrimitive?.contentOrNull
            ?.trim()
            ?: throw IllegalStateException("Empty reply from the model")
    }

    private suspend fun anthropic(
        apiKey: String,
        model: String,
        systemPrompt: String,
        turns: List<ChatTurn>
    ): String {
        val messages = buildJsonArray {
            turns.forEach { turn ->
                add(buildJsonObject {
                    put("role", if (turn.role == "user") "user" else "assistant")
                    put("content", turn.content)
                })
            }
        }
        val body = buildJsonObject {
            put("model", model)
            put("max_tokens", 700)
            put("system", systemPrompt)
            put("messages", messages)
        }
        val response = client.post("https://api.anthropic.com/v1/messages") {
            header("x-api-key", apiKey)
            header("anthropic-version", "2023-06-01")
            contentType(ContentType.Application.Json)
            setBody(body.toString())
        }
        val text = response.bodyAsText()
        if (!response.status.isSuccess()) {
            throw IllegalStateException("Anthropic HTTP ${response.status.value}: ${text.take(240)}")
        }
        val root = json.parseToJsonElement(text).jsonObject
        val parts = root["content"] as? JsonArray ?: throw IllegalStateException("Empty reply from Claude")
        return parts.mapNotNull { it.jsonObject["text"]?.jsonPrimitive?.contentOrNull }.joinToString("").trim()
    }

    private suspend fun gemini(
        apiKey: String,
        model: String,
        systemPrompt: String,
        turns: List<ChatTurn>
    ): String {
        val contents = buildJsonArray {
            turns.forEach { turn ->
                add(buildJsonObject {
                    put("role", if (turn.role == "user") "user" else "model")
                    put("parts", buildJsonArray {
                        add(buildJsonObject { put("text", turn.content) })
                    })
                })
            }
        }
        val body = buildJsonObject {
            put("systemInstruction", buildJsonObject {
                put("parts", buildJsonArray {
                    add(buildJsonObject { put("text", systemPrompt) })
                })
            })
            put("contents", contents)
        }
        val url =
            "https://generativelanguage.googleapis.com/v1beta/models/${model}:generateContent?key=$apiKey"
        val response = client.post(url) {
            contentType(ContentType.Application.Json)
            setBody(body.toString())
        }
        val text = response.bodyAsText()
        if (!response.status.isSuccess()) {
            throw IllegalStateException("Gemini HTTP ${response.status.value}: ${text.take(240)}")
        }
        val root = json.parseToJsonElement(text).jsonObject
        val parts = root["candidates"]?.jsonArray?.firstOrNull()
            ?.jsonObject?.get("content")?.jsonObject?.get("parts") as? JsonArray
            ?: throw IllegalStateException("Empty reply from Gemini")
        return parts.mapNotNull { it.jsonObject["text"]?.jsonPrimitive?.contentOrNull }.joinToString("").trim()
    }
}
