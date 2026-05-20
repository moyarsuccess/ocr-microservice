package com.ocr.engine.stage1

import com.ocr.config.OcrProperties
import com.ocr.util.PipelineLogger
import net.sourceforge.tess4j.Tesseract
import org.springframework.stereotype.Component
import java.awt.image.BufferedImage

/**
 * Stage 1 — run Tesseract against every rendered page and concatenate the text.
 *
 * Configuration:
 *   - OEM 1 (LSTM only) — best accuracy for modern Tesseract 4/5.
 *   - PSM 3 (fully automatic page segmentation, no OSD) — handles arbitrary layouts.
 *   - Language: configurable, defaults to eng+fra (Canada).
 *
 * A fresh Tesseract instance is created per page on purpose — Tess4J is a JNA
 * wrapper around a C library and sharing instances across threads has caused
 * native-memory corruption in production deployments.
 */
@Component
class TextExtractor(private val props: OcrProperties) {

    private val log = PipelineLogger("stage1.ocr")

    fun extract(pages: List<BufferedImage>): String {
        if (pages.isEmpty()) return ""
        log.begin("pages" to pages.size, "lang" to props.tesseract.language)
        val sb = StringBuilder()
        val totalStart = System.currentTimeMillis()
        for ((index, page) in pages.withIndex()) {
            val pageStart = System.currentTimeMillis()
            val text = ocr(page, psm = 3)
            sb.append(text.trim()).append("\n\n")
            log.info(
                "page",
                "n" to "${index + 1}/${pages.size}",
                "size" to "${page.width}x${page.height}",
                "chars" to text.trim().length,
                "took_ms" to (System.currentTimeMillis() - pageStart),
            )
        }
        val out = sb.toString().trim()
        log.done("total_chars" to out.length, "took_ms" to (System.currentTimeMillis() - totalStart))
        return out
    }

    /**
     * Single-page OCR helper exposed for the MRZ extractor, which crops the
     * bottom strip and re-runs with a different PSM + character whitelist.
     */
    fun ocr(
        image: BufferedImage,
        psm: Int = 3,
        characterWhitelist: String? = null,
        language: String = props.tesseract.language,
    ): String {
        val tess = Tesseract().apply {
            setDatapath(props.tesseract.dataPath)
            setLanguage(language)
            setPageSegMode(psm)
            setOcrEngineMode(1) // LSTM only
            if (characterWhitelist != null) {
                setVariable("tessedit_char_whitelist", characterWhitelist)
            }
        }
        return try {
            tess.doOCR(image)
        } catch (e: Exception) {
            log.warn("tesseract call failed", "psm" to psm, "whitelist" to (characterWhitelist != null), "err" to e.message)
            ""
        }
    }
}
