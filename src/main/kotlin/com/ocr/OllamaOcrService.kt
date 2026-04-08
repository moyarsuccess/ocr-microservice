package com.ocr

import com.fasterxml.jackson.databind.ObjectMapper
import dev.langchain4j.data.message.ImageContent
import dev.langchain4j.data.message.TextContent
import dev.langchain4j.data.message.UserMessage
import dev.langchain4j.model.ollama.OllamaChatModel
import org.apache.pdfbox.Loader
import org.apache.pdfbox.rendering.ImageType
import org.apache.pdfbox.rendering.PDFRenderer
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.util.*
import javax.imageio.ImageIO

@Service
class OllamaOcrService(
    @param:Value("\${ollama.base-url:http://localhost:11434}")
    private val baseUrl: String,

    @param:Value("\${ollama.model:qwen2.5vl:7b}")
    private val modelName: String
) : OcrProvider {

    private val logger = LoggerFactory.getLogger(OllamaOcrService::class.java)
    private val objectMapper = ObjectMapper()
    private val httpClient: HttpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(10))
        .build()

    private val chatModel: OllamaChatModel by lazy {
        buildChatModel(modelName)
    }

    private fun buildChatModel(model: String): OllamaChatModel =
        OllamaChatModel.builder()
            .baseUrl(baseUrl)
            .modelName(model)
            .timeout(Duration.ofMinutes(15))
            .build()

    private fun normalizeModelName(model: String): String =
        if (model.contains(":")) model else "$model:latest"

    private fun isModelAvailable(model: String): Boolean {
        val normalized = normalizeModelName(model)
        val request = HttpRequest.newBuilder()
            .uri(URI.create("$baseUrl/api/tags"))
            .GET()
            .build()
        val response = httpClient.send(request, HttpResponse.BodyHandlers.ofString())
        val models = objectMapper.readTree(response.body()).get("models") ?: return false
        return models.any {
            val name = it.get("name")?.asText() ?: ""
            name == normalized || name == model
        }
    }

    private fun pullModel(model: String) {
        logger.info("Model '$model' not found locally. Pulling from Ollama registry...")
        val body = objectMapper.writeValueAsString(mapOf("name" to model, "stream" to true))
        val request = HttpRequest.newBuilder()
            .uri(URI.create("$baseUrl/api/pull"))
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(body))
            .build()
        httpClient.send(request, HttpResponse.BodyHandlers.ofLines()).body().use { lines ->
            lines.filter { it.isNotBlank() }.forEach { line ->
                val node = objectMapper.readTree(line)
                val status = node.get("status")?.asText() ?: return@forEach
                val total = node.get("total")?.asLong()
                val completed = node.get("completed")?.asLong()
                if (total != null && completed != null && total > 0) {
                    logger.info("Pulling '$model': $status (${completed * 100 / total}%)")
                } else {
                    logger.info("Pulling '$model': $status")
                }
            }
        }
        logger.info("Model '$model' is ready.")
    }

    private fun ensureModelAvailable(model: String) {
        if (!isModelAvailable(model)) pullModel(model)
    }

    override fun extractTextFromImage(imageStream: InputStream): String = extractTextFromImage(imageStream, null)

    fun extractTextFromImage(imageStream: InputStream, model: String?): String {
        val effectiveModel = model ?: modelName
        ensureModelAvailable(effectiveModel)
        val start = System.currentTimeMillis()
        val base64 = Base64.getEncoder().encodeToString(imageStream.readAllBytes())
        logger.info("Sending image to Ollama ($effectiveModel) for OCR")
        val result = extractWithOllama(base64, model)
        logger.info("Ollama OCR completed in ${System.currentTimeMillis() - start}ms, extracted ${result.length} characters.")
        return result
    }

    override fun extractTextFromPdf(pdfStream: InputStream): String = extractTextFromPdf(pdfStream, null)

    fun extractTextFromPdf(pdfStream: InputStream, model: String?): String {
        val effectiveModel = model ?: modelName
        ensureModelAvailable(effectiveModel)
        val start = System.currentTimeMillis()
        val document = Loader.loadPDF(pdfStream.readAllBytes())
        val renderer = PDFRenderer(document)
        val textBuilder = StringBuilder()
        val pageCount = document.numberOfPages

        logger.info("Processing PDF ($pageCount pages) with Ollama ($effectiveModel)")

        for (page in 0 until pageCount) {
            logger.info("Processing PDF page ${page + 1}/$pageCount")
            val bufferedImage = renderer.renderImageWithDPI(page, 300f, ImageType.RGB)
            val baos = ByteArrayOutputStream()
            ImageIO.write(bufferedImage, "PNG", baos)
            val base64 = Base64.getEncoder().encodeToString(baos.toByteArray())
            textBuilder.append(extractWithOllama(base64, model)).append("\n\n")
        }

        document.close()
        val result = textBuilder.toString().trim()
        logger.info("Ollama PDF OCR completed in ${System.currentTimeMillis() - start}ms, extracted ${result.length} characters.")
        return result
    }

    private fun extractWithOllama(base64Image: String, model: String?): String {
        val cm = if (model != null && model != modelName) buildChatModel(model) else chatModel
        val message = UserMessage.from(
            ImageContent.from(base64Image, "image/png"),
            TextContent.from("Extract all text from this image exactly as it appears. Output only the extracted text with no commentary or explanation.")
        )
        return cm.generate(message).content().text()
    }
}
