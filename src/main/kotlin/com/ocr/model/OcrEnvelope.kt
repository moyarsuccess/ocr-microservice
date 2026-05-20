package com.ocr.model

import com.fasterxml.jackson.annotation.JsonInclude

/**
 * Uniform envelope returned for every extraction request, success or failure.
 *
 * `data` is intentionally a free-form map (parsed from the vision model's
 * JSON output) rather than a sealed type-per-document hierarchy — the schema
 * lives in PromptRegistry, the validator normalises it, and Jackson handles
 * serialisation back out. This keeps adding a new document type to a 2-file
 * change (DocumentType + PromptRegistry) instead of N files.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
data class OcrEnvelope(
    val success: Boolean,
    val engine: String,
    val engineVersion: String,
    val documentType: String,
    val documentTypeConfidence: Double?,
    val classifierUsed: String?,
    val languageDetected: List<String>,
    val pages: Int,
    val processingTimeMs: Long,
    val stageTimings: Map<String, Long>,
    val data: Map<String, Any?>?,
    val fieldConfidence: Map<String, Double>?,
    val warnings: List<String>,
    val rawText: String? = null,
    val error: OcrError? = null,
)

data class OcrError(
    val code: String,
    val message: String,
    val retryable: Boolean,
)

/** Stable error codes — keep these in sync with API consumers. */
object OcrErrorCodes {
    const val EMPTY_FILE = "EMPTY_FILE"
    const val UNSUPPORTED_FORMAT = "UNSUPPORTED_FORMAT"
    const val UNSUPPORTED_DOCUMENT_TYPE = "UNSUPPORTED_DOCUMENT_TYPE"
    const val LOW_QUALITY_INPUT = "LOW_QUALITY_INPUT"
    const val MRZ_PARSE_FAILED = "MRZ_PARSE_FAILED"
    const val OCR_FAILED = "OCR_FAILED"
    const val CLASSIFIER_FAILED = "CLASSIFIER_FAILED"
    const val VISION_MODEL_FAILED = "VISION_MODEL_FAILED"
    const val MODEL_NOT_ALLOWED = "MODEL_NOT_ALLOWED"
    const val UPSTREAM_TIMEOUT = "UPSTREAM_TIMEOUT"
    const val INTERNAL = "INTERNAL"
}
