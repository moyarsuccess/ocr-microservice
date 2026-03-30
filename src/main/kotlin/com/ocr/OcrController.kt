package com.ocr

import org.slf4j.LoggerFactory
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.*
import org.springframework.web.multipart.MultipartFile

@RestController
@RequestMapping("/ocr")
class OcrController(private val tesseractService: TesseractService) {

    private val logger = LoggerFactory.getLogger(OcrController::class.java)

    @PostMapping
    fun processOcr(@RequestParam("file") file: MultipartFile): ResponseEntity<OcrResponse> {
        val start = System.currentTimeMillis()
        logger.info("Received OCR request for file: \${file.originalFilename} (\${file.size} bytes)")
        
        if (file.isEmpty) {
            return ResponseEntity.badRequest().body(
                OcrResponse(success = false, text = null, processingTimeMs = 0, error = "File cannot be empty")
            )
        }

        return try {
            val contentType = file.contentType?.lowercase() ?: ""
            val filename = file.originalFilename?.lowercase() ?: ""
            
            val extractedText = if (contentType == "application/pdf" || filename.endsWith(".pdf")) {
                file.inputStream.use { stream ->
                    tesseractService.extractTextFromPdf(stream)
                }
            } else {
                file.inputStream.use { stream ->
                    tesseractService.extractTextFromImage(stream)
                }
            }
            
            val processingTime = System.currentTimeMillis() - start
            ResponseEntity.ok(
                OcrResponse(
                    success = true,
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
                    text = null,
                    processingTimeMs = processingTime,
                    error = e.message
                )
            )
        }
    }

    @GetMapping("/health")
    fun healthCheck(): Map<String, String> {
        return mapOf("status" to "ok")
    }
}

data class OcrResponse(
    val success: Boolean,
    val engine: String = "tesseract",
    val text: String?,
    val processingTimeMs: Long,
    val error: String?
)
