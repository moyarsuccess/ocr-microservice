package com.ocr.engine

import com.ocr.config.OcrProperties
import com.ocr.engine.stage1.MrzExtractor
import com.ocr.engine.stage1.PdfRenderer
import com.ocr.engine.stage1.TextExtractor
import com.ocr.engine.stage2.DocumentClassifier
import com.ocr.engine.stage3.PromptRegistry
import com.ocr.engine.stage4.VisionExtractor
import com.ocr.engine.stage5.ResultValidator
import com.ocr.model.DocumentType
import com.ocr.model.ExtractionContext
import com.ocr.model.OcrEnvelope
import com.ocr.model.OcrError
import com.ocr.model.OcrErrorCodes
import com.ocr.util.PipelineLogger
import org.springframework.stereotype.Component

/**
 * The hybrid OCR engine — orchestrates all five pipeline stages.
 *
 *   Stage 1   render → tesseract text → (conditional) MRZ specialist
 *   Stage 2   classify (rules-first, LLM fallback)
 *   Stage 3   look up per-type prompt + JSON schema
 *   Stage 4   vision LLM extracts structured JSON
 *   Stage 5   validate / cross-check / score confidence
 *
 * Single public entry point: [extract]. Returns an OcrEnvelope ready to serialise.
 */
@Component
class HybridOcrEngine(
    private val pdfRenderer: PdfRenderer,
    private val textExtractor: TextExtractor,
    private val mrzExtractor: MrzExtractor,
    private val classifier: DocumentClassifier,
    private val promptRegistry: PromptRegistry,
    private val visionExtractor: VisionExtractor,
    private val validator: ResultValidator,
    private val props: OcrProperties,
) {

    private val log = PipelineLogger("pipeline")

    fun extract(bytes: ByteArray, ctx: ExtractionContext): OcrEnvelope {
        val total = System.currentTimeMillis()
        log.begin(
            "filename" to ctx.originalFilename,
            "bytes" to bytes.size,
            "expected" to ctx.expectedType?.code,
        )

        return try {
            runPipeline(bytes, ctx)
            val envelope = successEnvelope(ctx, System.currentTimeMillis() - total)
            log.done(
                "doc" to ctx.documentType.code,
                "via" to ctx.classifierUsed,
                "conf" to ctx.documentTypeConfidence,
                "warnings" to ctx.warnings.size,
                "total_ms" to envelope.processingTimeMs,
            )
            envelope
        } catch (e: BadInputException) {
            log.warn("bad input", "code" to e.code, "msg" to e.message)
            errorEnvelope(ctx, System.currentTimeMillis() - total, e.code, e.message ?: "bad input", retryable = false)
        } catch (e: Exception) {
            // ⚠️ WARNING: do not leak e.message verbatim to the API caller — it can
            // contain native pointer addresses, file paths, model names, etc.
            log.error("pipeline failed", e)
            errorEnvelope(
                ctx,
                System.currentTimeMillis() - total,
                OcrErrorCodes.INTERNAL,
                "An unexpected error occurred during extraction.",
                retryable = true,
            )
        }
    }

    private fun runPipeline(bytes: ByteArray, ctx: ExtractionContext) {
        if (bytes.isEmpty()) throw BadInputException(OcrErrorCodes.EMPTY_FILE, "File is empty")

        // Stage 1a — rasterise
        ctx.timed("render") {
            val isPdf = PdfRenderer.looksLikePdf(bytes)
            ctx.pages = try {
                pdfRenderer.render(bytes, isPdf)
            } catch (e: IllegalArgumentException) {
                throw BadInputException(OcrErrorCodes.UNSUPPORTED_FORMAT, e.message ?: "Unsupported file format")
            }
        }

        // Stage 1b — OCR
        ctx.timed("ocr") {
            ctx.rawText = textExtractor.extract(ctx.pages)
        }
        if (ctx.rawText.isBlank()) {
            ctx.warn("Tesseract produced no text — the image may be too low-quality")
        }

        // Stage 1c — MRZ side-channel (only when a passport is likely)
        ctx.timed("mrz") {
            if (shouldTryMrz(ctx)) {
                ctx.mrz = mrzExtractor.tryExtract(ctx.pages)
            } else {
                log.info("[stage1.mrz] skipped — no passport hint")
            }
        }

        // Stage 2 — classify
        ctx.timed("classify") {
            classifier.classify(ctx)
        }

        if (ctx.documentType == DocumentType.UNKNOWN) {
            throw BadInputException(
                OcrErrorCodes.UNSUPPORTED_DOCUMENT_TYPE,
                "Could not identify a supported document type from the input"
            )
        }

        // Stage 3 — prompt + schema
        ctx.timed("template") {
            promptRegistry.build(ctx)
        }

        // Stage 4 — vision LLM
        ctx.timed("vision") {
            visionExtractor.extract(ctx)
        }

        // Stage 5 — validate / reconcile / score
        ctx.timed("validate") {
            validator.validate(ctx)
        }
    }

    private fun shouldTryMrz(ctx: ExtractionContext): Boolean {
        if (ctx.expectedType == DocumentType.PASSPORT) return true
        if (ctx.expectedType != null && ctx.expectedType != DocumentType.UNKNOWN) return false
        return passportMarkers.any { it.containsMatchIn(ctx.rawText) }
    }

    private val passportMarkers = listOf(
        Regex("""\bpassport\b""", RegexOption.IGNORE_CASE),
        Regex("""^P[<A-Z][A-Z]{3}""", RegexOption.MULTILINE),
        Regex("""^[A-Z0-9<]{44}$""", RegexOption.MULTILINE),
    )

    private fun successEnvelope(ctx: ExtractionContext, totalMs: Long): OcrEnvelope =
        OcrEnvelope(
            success = true,
            engine = "hybrid",
            engineVersion = ENGINE_VERSION,
            documentType = ctx.documentType.code,
            documentTypeConfidence = ctx.documentTypeConfidence,
            classifierUsed = ctx.classifierUsed,
            languageDetected = ctx.languageDetected.ifEmpty { listOf(props.tesseract.language) },
            pages = ctx.pages.size,
            processingTimeMs = totalMs,
            stageTimings = ctx.stageTimings.toMap(),
            data = ctx.extractedData,
            fieldConfidence = ctx.fieldConfidence,
            warnings = ctx.warnings.toList(),
            rawText = if (ctx.includeRawText) ctx.rawText else null,
            error = null,
        )

    private fun errorEnvelope(
        ctx: ExtractionContext,
        totalMs: Long,
        code: String,
        message: String,
        retryable: Boolean,
    ): OcrEnvelope = OcrEnvelope(
        success = false,
        engine = "hybrid",
        engineVersion = ENGINE_VERSION,
        documentType = ctx.documentType.code,
        documentTypeConfidence = ctx.documentTypeConfidence.takeIf { it > 0 },
        classifierUsed = ctx.classifierUsed.ifBlank { null },
        languageDetected = ctx.languageDetected.toList(),
        pages = ctx.pages.size,
        processingTimeMs = totalMs,
        stageTimings = ctx.stageTimings.toMap(),
        data = null,
        fieldConfidence = null,
        warnings = ctx.warnings.toList(),
        rawText = if (ctx.includeRawText && ctx.rawText.isNotBlank()) ctx.rawText else null,
        error = OcrError(code = code, message = message, retryable = retryable),
    )

    private class BadInputException(val code: String, message: String) : RuntimeException(message)

    companion object {
        const val ENGINE_VERSION = "hybrid-1.0.0"
    }
}
