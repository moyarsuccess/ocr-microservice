package com.ocr.config

import org.springframework.boot.context.properties.ConfigurationProperties

/**
 * Centralised configuration for the hybrid OCR engine.
 *
 * Bound from application.properties / environment via Spring's
 * @ConfigurationProperties so we don't sprinkle @Value across the codebase.
 */
@ConfigurationProperties(prefix = "ocr")
data class OcrProperties(
    val tesseract: TesseractProps = TesseractProps(),
    val ollama: OllamaProps = OllamaProps(),
    val pipeline: PipelineProps = PipelineProps(),
)

data class TesseractProps(
    val dataPath: String = "/usr/share/tessdata",
    /** Default OCR language(s), e.g. `eng`, `eng+fra`. */
    val language: String = "eng+fra",
    /** DPI used when rasterising PDF pages for OCR. 300 is the sweet spot. */
    val pdfDpi: Float = 300f,
    /** Hard cap on PDF pages we OCR per request. */
    val maxPages: Int = 30,
)

data class OllamaProps(
    // ⚠️ WARNING: this Ollama endpoint is on the public internet (ollama.flutra.ca).
    // PII (passport data, bank statements, etc.) leaves the local network on every
    // call. Confirm TLS termination, a DPA / privacy review, and IP allowlisting
    // before processing real applicant data through it.
    val baseUrl: String = "https://ollama.flutra.ca",
    /** Fast text-only model used by the classifier fallback. */
    val classifierModel: String = "llama3.2:3b",
    /** Powerful vision model used by the structured extractor. */
    val visionModel: String = "qwen2.5vl:7b",
    /** Timeout for any single Ollama call. */
    val timeoutSeconds: Long = 180,
    /**
     * Allowlist of models that may be invoked. Prevents callers from
     * forcing an arbitrary model pull via the request.
     *
     * ⚠️ WARNING: leaving this empty allows ANY model to be pulled. Keep
     * this populated in production to prevent disk-fill / bandwidth abuse.
     */
    val allowedModels: Set<String> = setOf("llama3.2:3b", "qwen2.5vl:7b", "qwen2.5vl:3b"),
)

data class PipelineProps(
    /** If true, the raw Tesseract text is included in the response (debugging only — contains PII). */
    val includeRawTextByDefault: Boolean = false,
    /** Confidence threshold below which the rule classifier falls back to the LLM classifier. */
    val ruleClassifierMinConfidence: Double = 0.55,
    /** Confidence threshold below which the rule classifier's #1 must beat #2 to win without LLM fallback. */
    val ruleClassifierMinMargin: Double = 0.15,
)
