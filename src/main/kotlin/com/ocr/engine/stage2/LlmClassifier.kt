package com.ocr.engine.stage2

import com.ocr.config.OcrProperties
import com.ocr.engine.client.OllamaClient
import com.ocr.model.DocumentType
import com.ocr.util.PipelineLogger
import org.springframework.stereotype.Component

/**
 * Text-only fallback classifier.
 *
 * Only invoked when the rule-based classifier is uncertain (low confidence or
 * narrow margin). Uses a small fast model — default `llama3.2:3b` — and asks
 * it to pick from the known DocumentType codes via JSON output.
 *
 * The text is truncated because the classifier doesn't need the whole document;
 * the first ~1500 chars usually contain the title, headers, and enough body to
 * disambiguate.
 */
@Component
class LlmClassifier(
    private val ollama: OllamaClient,
    private val props: OcrProperties,
) {

    private val log = PipelineLogger("stage2.llm")

    private val allowedCodes: String =
        DocumentType.entries.filter { it != DocumentType.UNKNOWN }.joinToString(", ") { "\"${it.code}\"" }

    fun classify(text: String): Pair<DocumentType, Double> {
        if (text.isBlank()) return DocumentType.UNKNOWN to 0.0

        val truncated = text.take(1500)
        log.begin("model" to props.ollama.classifierModel, "prompt_chars" to truncated.length)
        val start = System.currentTimeMillis()
        val prompt = buildString {
            appendLine("You are a document classifier for Canadian immigration (IRCC) applications.")
            appendLine("Given the OCR'd text of a document, identify which document type it is.")
            appendLine()
            appendLine("Allowed document type codes (pick exactly one):")
            appendLine(allowedCodes)
            appendLine("If none of these fit, return \"unknown\".")
            appendLine()
            appendLine("Respond with a single JSON object: {\"documentType\": <code>, \"confidence\": <0..1>}.")
            appendLine("No prose, no markdown, no code fences. JSON only.")
            appendLine()
            appendLine("OCR text:")
            appendLine("---")
            append(truncated)
            appendLine()
            appendLine("---")
        }

        return try {
            val json = ollama.chatExpectingJsonObject(
                model = props.ollama.classifierModel,
                prompt = prompt,
            )
            val code = (json["documentType"] as? String) ?: "unknown"
            val rawConfidence = when (val c = json["confidence"]) {
                is Number -> c.toDouble()
                is String -> c.toDoubleOrNull() ?: 0.5
                else -> 0.5
            }
            val type = DocumentType.fromCode(code)
            val conf = rawConfidence.coerceIn(0.0, 1.0)
            log.done("doc" to type.code, "conf" to conf, "took_ms" to (System.currentTimeMillis() - start))
            type to conf
        } catch (e: Exception) {
            log.warn("classifier failed, defaulting to UNKNOWN", "err" to e.message, "took_ms" to (System.currentTimeMillis() - start))
            DocumentType.UNKNOWN to 0.0
        }
    }
}
