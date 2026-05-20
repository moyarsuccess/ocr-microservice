package com.ocr.engine.stage1

import com.ocr.config.OcrProperties
import com.ocr.util.PipelineLogger
import org.apache.pdfbox.Loader
import org.apache.pdfbox.rendering.ImageType
import org.apache.pdfbox.rendering.PDFRenderer
import org.springframework.stereotype.Component
import java.awt.image.BufferedImage
import javax.imageio.ImageIO

/**
 * Rasterises an input (PDF or image) into a list of BufferedImage pages.
 *
 * Handles:
 *   - PDFs via Apache PDFBox at the configured DPI (300 default).
 *   - Single-image inputs (PNG/JPG/etc.) via ImageIO — returned as a list of one.
 *
 * Page count is hard-capped via OcrProperties.tesseract.maxPages so a hostile
 * uploader can't force the engine into a 1000-page OCR job.
 */
@Component
class PdfRenderer(private val props: OcrProperties) {

    private val log = PipelineLogger("stage1.render")

    /**
     * @param bytes raw file contents
     * @param isPdf true if the magic bytes / sniffed content type indicate PDF
     */
    fun render(bytes: ByteArray, isPdf: Boolean): List<BufferedImage> {
        if (isPdf) return renderPdf(bytes)
        log.info("rasterising single image", "bytes" to bytes.size)
        val image = ImageIO.read(bytes.inputStream())
            ?: throw IllegalArgumentException("Could not decode image (unsupported or corrupt format)")
        log.info("image ready", "size" to "${image.width}x${image.height}")
        return listOf(image)
    }

    private fun renderPdf(bytes: ByteArray): List<BufferedImage> {
        Loader.loadPDF(bytes).use { document ->
            val pageCount = document.numberOfPages
            if (pageCount == 0) throw IllegalArgumentException("PDF contains no pages")
            val limit = minOf(pageCount, props.tesseract.maxPages)
            log.info("rasterising pdf", "pages" to pageCount, "render" to limit, "dpi" to props.tesseract.pdfDpi)
            if (pageCount > limit) {
                log.warn("page cap applied", "pages" to pageCount, "max" to limit)
            }
            val renderer = PDFRenderer(document)
            return (0 until limit).map { renderer.renderImageWithDPI(it, props.tesseract.pdfDpi, ImageType.RGB) }
        }
    }

    companion object {
        /** Cheap magic-byte sniff — PDFs always start with `%PDF-`. */
        fun looksLikePdf(bytes: ByteArray): Boolean =
            bytes.size >= 5 &&
                bytes[0] == 0x25.toByte() &&     // %
                bytes[1] == 0x50.toByte() &&     // P
                bytes[2] == 0x44.toByte() &&     // D
                bytes[3] == 0x46.toByte() &&     // F
                bytes[4] == 0x2D.toByte()        // -
    }
}
