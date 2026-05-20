package com.ocr.engine.stage4

import com.ocr.config.OcrProperties
import com.ocr.engine.client.OllamaClient
import com.ocr.model.ExtractionContext
import com.ocr.util.PipelineLogger
import org.springframework.stereotype.Component
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.util.Base64
import javax.imageio.ImageIO

/**
 * Stage 4 — the heavy lift.
 *
 * Sends every page as a base64-encoded PNG plus the Stage-3 prompt to the
 * configured vision model, and expects a single JSON object back conforming
 * to the prompt's schema. The model is asked to emit JSON only (Ollama's
 * `format: "json"`) so we don't have to scrape it out of prose.
 *
 * The result is written into ctx.extractedData unchanged — normalisation
 * (date formats, ISO codes, MRZ cross-check) happens in Stage 5.
 */
@Component
class VisionExtractor(
    private val ollama: OllamaClient,
    private val props: OcrProperties,
) {

    private val log = PipelineLogger("stage4.vision")

    fun extract(ctx: ExtractionContext) {
        val model = ctx.visionModelOverride ?: props.ollama.visionModel
        if (ctx.pages.isEmpty()) {
            log.warn("no pages to send to vision model — skipping")
            return
        }
        if (ctx.prompt.isBlank()) {
            log.warn("no prompt prepared by stage 3 — skipping")
            return
        }

        log.begin(
            "model" to model,
            "imgs" to ctx.pages.size,
            "prompt_chars" to ctx.prompt.length,
        )
        val start = System.currentTimeMillis()

        val imagesBase64 = ctx.pages.map { encodePng(it) }
        log.info(
            "images encoded",
            "imgs" to imagesBase64.size,
            "avg_kb" to (imagesBase64.sumOf { it.length } / imagesBase64.size / 1024),
        )

        val json: Map<String, Any?> = try {
            ollama.chatExpectingJsonObject(
                model = model,
                prompt = ctx.prompt,
                imagesBase64 = imagesBase64,
            )
        } catch (e: Exception) {
            log.error("vision model call failed", e, "model" to model, "took_ms" to (System.currentTimeMillis() - start))
            ctx.warn("Vision model extraction failed: ${e.message?.take(120) ?: "unknown error"}")
            emptyMap()
        }

        ctx.extractedData = json.toMutableMap()
        log.done(
            "fields" to json.size,
            "took_ms" to (System.currentTimeMillis() - start),
        )
    }

    private fun encodePng(image: BufferedImage): String {
        val baos = ByteArrayOutputStream()
        ImageIO.write(image, "PNG", baos)
        return Base64.getEncoder().encodeToString(baos.toByteArray())
    }
}
