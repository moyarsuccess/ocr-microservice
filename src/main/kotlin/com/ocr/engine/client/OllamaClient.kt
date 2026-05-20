package com.ocr.engine.client

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import com.ocr.config.OcrProperties
import com.ocr.util.PipelineLogger
import org.springframework.stereotype.Component
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

/**
 * Thin HTTP client for Ollama's `/api/chat` endpoint.
 *
 * Shared by the LLM classifier (text-only, fast model) and the vision extractor
 * (multimodal, powerful model). Centralises:
 *   - The allowlist check so callers can't induce arbitrary model pulls.
 *   - JSON-mode forcing (`format: "json"`) for the structured stages.
 *   - Connection and read timeouts.
 *
 * ⚠️ WARNING: passing PII (passport data, bank statements) to a remote
 * Ollama endpoint sends the data over the public internet. The configured
 * base URL must use TLS, and the deployment must have a DPA with whoever
 * operates the inference server.
 */
@Component
class OllamaClient(private val props: OcrProperties) {

    private val log = PipelineLogger("ollama.client")
    private val mapper = ObjectMapper()
    private val http: HttpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(15))
        .build()

    /** Plain text chat completion. If `expectJson` is true, asks Ollama to emit JSON only. */
    fun chat(model: String, prompt: String, expectJson: Boolean): String {
        enforceAllowlist(model)
        val body = buildRequestBody(model, prompt, imagesBase64 = null, expectJson = expectJson)
        return send(body)
    }

    /** Multimodal chat completion. `imagesBase64` is one or more page images. */
    fun chatWithImages(model: String, prompt: String, imagesBase64: List<String>, expectJson: Boolean): String {
        enforceAllowlist(model)
        require(imagesBase64.isNotEmpty()) { "chatWithImages requires at least one image" }
        val body = buildRequestBody(model, prompt, imagesBase64 = imagesBase64, expectJson = expectJson)
        return send(body)
    }

    private fun enforceAllowlist(model: String) {
        val allowed = props.ollama.allowedModels
        // Empty allowlist = no enforcement (developer mode). Keep this populated in prod.
        if (allowed.isEmpty()) return
        if (model !in allowed) {
            throw IllegalArgumentException("Model '$model' is not in the allowlist. Allowed: $allowed")
        }
    }

    private fun buildRequestBody(
        model: String,
        prompt: String,
        imagesBase64: List<String>?,
        expectJson: Boolean,
    ): String {
        val message = buildMap<String, Any> {
            put("role", "user")
            put("content", prompt)
            if (!imagesBase64.isNullOrEmpty()) put("images", imagesBase64)
        }
        val payload = buildMap<String, Any> {
            put("model", model)
            put("messages", listOf(message))
            put("stream", false)
            if (expectJson) put("format", "json")
            put("options", mapOf("temperature" to 0.0))
        }
        return mapper.writeValueAsString(payload)
    }

    private fun send(body: String): String {
        val request = HttpRequest.newBuilder()
            .uri(URI.create("${props.ollama.baseUrl.trimEnd('/')}/api/chat"))
            .timeout(Duration.ofSeconds(props.ollama.timeoutSeconds))
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(body))
            .build()

        val start = System.currentTimeMillis()
        val response = http.send(request, HttpResponse.BodyHandlers.ofString())
        val took = System.currentTimeMillis() - start
        if (response.statusCode() !in 200..299) {
            // ⚠️ WARNING: do NOT echo response.body() back to API callers — Ollama
            // error messages can contain prompt fragments and model names that
            // leak internal config. Logging server-side is fine.
            log.warn(
                "http error",
                "status" to response.statusCode(),
                "took_ms" to took,
                "body_excerpt" to response.body().take(200),
            )
            throw RuntimeException("Ollama request failed with status ${response.statusCode()}")
        }
        log.debug("http ok", "took_ms" to took, "bytes_in" to response.body().length)
        val node = mapper.readTree(response.body())
        return node.get("message")?.get("content")?.asText()
            ?: throw RuntimeException("Ollama response missing message.content")
    }

    /** Convenience for callers that expect a JSON object back. */
    fun chatExpectingJsonObject(model: String, prompt: String, imagesBase64: List<String>? = null): Map<String, Any?> {
        val raw = if (imagesBase64.isNullOrEmpty()) {
            chat(model, prompt, expectJson = true)
        } else {
            chatWithImages(model, prompt, imagesBase64, expectJson = true)
        }
        return try {
            mapper.readValue<Map<String, Any?>>(raw)
        } catch (e: Exception) {
            // Model occasionally wraps JSON in prose despite format=json — try to recover.
            val firstBrace = raw.indexOf('{')
            val lastBrace = raw.lastIndexOf('}')
            if (firstBrace >= 0 && lastBrace > firstBrace) {
                mapper.readValue<Map<String, Any?>>(raw.substring(firstBrace, lastBrace + 1))
            } else {
                throw RuntimeException("Could not parse JSON from model response: ${e.message}")
            }
        }
    }
}
