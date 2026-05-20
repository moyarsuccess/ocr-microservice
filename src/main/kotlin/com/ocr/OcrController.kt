package com.ocr

import com.ocr.engine.HybridOcrEngine
import com.ocr.model.DocumentType
import com.ocr.model.ExtractionContext
import com.ocr.model.OcrEnvelope
import com.ocr.util.PipelineLogger
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.Parameter
import io.swagger.v3.oas.annotations.media.Content
import io.swagger.v3.oas.annotations.media.Schema
import io.swagger.v3.oas.annotations.responses.ApiResponse
import io.swagger.v3.oas.annotations.tags.Tag
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.*
import org.springframework.web.multipart.MultipartFile

/**
 * REST surface for the hybrid OCR engine.
 *
 *   POST /ocr/extract     multipart, returns structured OcrEnvelope JSON
 *   GET  /ocr/health      service liveness
 *   GET  /health          alias for the Docker HEALTHCHECK
 *
 * There is no `provider` parameter any more — there is one engine, the hybrid pipeline.
 */
@Tag(name = "OCR", description = "Hybrid OCR engine for IRCC document extraction")
@RestController
class OcrController(private val engine: HybridOcrEngine) {

    private val log = PipelineLogger("http")

    @Operation(
        summary = "Extract structured fields from an IRCC document",
        description = """Accepts a PDF or image file (passport, bank statement, employment letter, LOA, PAL, LMIA, GIC, etc.)
and runs it through the hybrid 5-stage OCR pipeline:

1. Tesseract OCR + (conditional) MRZ specialist
2. Rule-based document classification with LLM fallback
3. Per-type prompt + JSON schema lookup
4. Vision LLM structured extraction
5. Cross-validation and confidence scoring

Returns a strongly-shaped JSON envelope with the extracted fields, per-field confidence, and warnings."""
    )
    @ApiResponse(
        responseCode = "200",
        description = "Extraction complete (check `success` and `error`)",
        content = [Content(mediaType = MediaType.APPLICATION_JSON_VALUE, schema = Schema(implementation = OcrEnvelope::class))]
    )
    @PostMapping("/ocr/extract", consumes = [MediaType.MULTIPART_FORM_DATA_VALUE])
    fun extract(
        @Parameter(description = "PDF or image file to process", required = true)
        @RequestParam("file") file: MultipartFile,
        @Parameter(description = "Optional document type hint, e.g. `passport`, `bank_statement`. Skips classification when provided.")
        @RequestParam("expectedType", required = false) expectedType: String?,
        @Parameter(description = "Optional vision model override (must be in the server allowlist)")
        @RequestParam("visionModel", required = false) visionModel: String?,
        @Parameter(description = "If true, include the raw Tesseract text in the response (debugging — contains PII)")
        @RequestParam("includeRawText", defaultValue = "false") includeRawText: Boolean,
    ): ResponseEntity<OcrEnvelope> {

        // ⚠️ WARNING: do not log file.originalFilename verbatim if filenames may
        // contain applicant names. We log only the size; the engine logs further
        // pipeline detail without PII.
        log.info(
            "extract request",
            "bytes" to file.size,
            "content_type" to (file.contentType ?: "unknown"),
            "expected" to (expectedType ?: "(detect)"),
            "model_override" to (visionModel ?: "(default)"),
        )

        val ctx = ExtractionContext(
            originalFilename = sanitiseFilename(file.originalFilename),
            expectedType = expectedType?.let { DocumentType.fromCode(it) },
            includeRawText = includeRawText,
            visionModelOverride = visionModel,
        )
        val envelope = engine.extract(file.bytes, ctx)
        return ResponseEntity.ok(envelope)
    }

    @Operation(summary = "Health check")
    @GetMapping("/ocr/health", "/health")
    fun health(): Map<String, String> = mapOf("status" to "ok")

    /**
     * Filenames can contain user PII (e.g. "JohnSmith_passport.pdf"). Strip path
     * components and keep only the trailing token, then truncate. This is used
     * for logs / debugging, not security.
     */
    private fun sanitiseFilename(name: String?): String? {
        if (name.isNullOrBlank()) return null
        val tail = name.substringAfterLast('/').substringAfterLast('\\')
        return tail.take(64)
    }
}
