package com.ocr

import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.Parameter
import io.swagger.v3.oas.annotations.media.Content
import io.swagger.v3.oas.annotations.media.Schema
import io.swagger.v3.oas.annotations.responses.ApiResponse
import io.swagger.v3.oas.annotations.responses.ApiResponses
import io.swagger.v3.oas.annotations.tags.Tag
import org.slf4j.LoggerFactory
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.*
import org.springframework.web.multipart.MultipartFile

@Tag(name = "OCR", description = "Extract text from images and PDF files using Tesseract or Ollama")
@RestController
@RequestMapping("/ocr")
class OcrController(
    private val tesseractService: TesseractService,
    private val ollamaOcrService: OllamaOcrService
) {

    private val logger = LoggerFactory.getLogger(OcrController::class.java)
    private val imageExtensions = setOf("jpeg", "jpg", "png", "gif", "bmp", "webp", "tif", "tiff")

    @Operation(
        summary = "Extract text from a file",
        description = """Accepts an image (JPEG, PNG, GIF, BMP, WEBP, TIFF) or a PDF and returns the extracted text.
Use the **provider** parameter to choose the OCR engine:
- `tesseract` (default) — fast, offline, best for clean scans
- `ollama` — AI-powered, better for complex layouts and handwriting (requires Ollama running)

When using `ollama`, the **model** parameter overrides the server-configured default (e.g. `llama3.2-vision`, `minicpm-v`)."""
    )
    @ApiResponses(
        ApiResponse(
            responseCode = "200",
            description = "Text extracted successfully",
            content = [Content(
                mediaType = MediaType.APPLICATION_JSON_VALUE,
                schema = Schema(implementation = OcrResponse::class)
            )]
        ),
        ApiResponse(
            responseCode = "400",
            description = "Empty or missing file",
            content = [Content(
                mediaType = MediaType.APPLICATION_JSON_VALUE,
                schema = Schema(implementation = OcrResponse::class)
            )]
        ),
        ApiResponse(
            responseCode = "500",
            description = "OCR processing failed",
            content = [Content(
                mediaType = MediaType.APPLICATION_JSON_VALUE,
                schema = Schema(implementation = OcrResponse::class)
            )]
        )
    )
    @PostMapping(consumes = [MediaType.MULTIPART_FORM_DATA_VALUE])
    fun processOcr(
        @Parameter(description = "Image or PDF file to process", required = true)
        @RequestParam("file") file: MultipartFile,
        @Parameter(description = "OCR engine to use: `tesseract` (default) or `ollama`")
        @RequestParam("provider", defaultValue = "tesseract") provider: String,
        @Parameter(description = "Ollama model to use (only applies when provider=ollama). Overrides the server default.")
        @RequestParam("model", required = false) model: String?
    ): ResponseEntity<OcrResponse> {
        val start = System.currentTimeMillis()
        logger.info("Received OCR request for file: ${file.originalFilename} (${file.size} bytes), provider: $provider${if (model != null) ", model: $model" else ""}")

        if (file.isEmpty) {
            return ResponseEntity.badRequest().body(
                OcrResponse(
                    success = false,
                    engine = provider,
                    text = null,
                    processingTimeMs = 0,
                    error = "File cannot be empty"
                )
            )
        }

        return try {
            val filename = file.originalFilename?.lowercase() ?: ""

            val extractedText = when (provider.lowercase()) {
                "ollama" -> when {
                    filename.endsWith(".pdf") -> file.inputStream.use { ollamaOcrService.extractTextFromPdf(it, model) }
                    imageExtensions.any { filename.endsWith(".$it") } -> file.inputStream.use { ollamaOcrService.extractTextFromImage(it, model) }
                    else -> ""
                }
                else -> when {
                    filename.endsWith(".pdf") -> file.inputStream.use { tesseractService.extractTextFromPdf(it) }
                    imageExtensions.any { filename.endsWith(".$it") } -> file.inputStream.use { tesseractService.extractTextFromImage(it) }
                    else -> ""
                }
            }

            val processingTime = System.currentTimeMillis() - start
            ResponseEntity.ok(
                OcrResponse(
                    success = true,
                    engine = provider.lowercase(),
                    text = extractedText,
                    processingTimeMs = processingTime,
                    error = null
                )
            )
        } catch (e: Exception) {
            val processingTime = System.currentTimeMillis() - start
            ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(
                OcrResponse(
                    success = false,
                    engine = provider.lowercase(),
                    text = null,
                    processingTimeMs = processingTime,
                    error = e.message
                )
            )
        }
    }

    @Operation(summary = "Health check", description = "Returns the service status.")
    @ApiResponse(responseCode = "200", description = "Service is running")
    @GetMapping("/health")
    fun healthCheck(): Map<String, String> {
        return mapOf("status" to "ok")
    }
}

data class OcrResponse(
    val success: Boolean,
    val engine: String,
    val text: String?,
    val processingTimeMs: Long,
    val error: String?
)
