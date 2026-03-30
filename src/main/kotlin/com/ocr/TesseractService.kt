package com.ocr

import net.sourceforge.tess4j.Tesseract
import org.apache.pdfbox.Loader
import org.apache.pdfbox.rendering.ImageType
import org.apache.pdfbox.rendering.PDFRenderer
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service
import java.awt.image.BufferedImage
import java.io.InputStream
import javax.imageio.ImageIO

@Service
class TesseractService(
    @Value("\${tesseract.datapath:/usr/share/tessdata}")
    private val dataPath: String,
    
    @Value("\${tesseract.language:eng}")
    private val language: String
) {
    private val logger = LoggerFactory.getLogger(TesseractService::class.java)
    
    private fun createTesseract(): Tesseract {
        val instance = Tesseract()
        instance.setDatapath(dataPath)
        instance.setLanguage(language)
        instance.setPageSegMode(3)
        instance.setOcrEngineMode(1)
        return instance
    }

    fun extractTextFromImage(imageStream: InputStream): String {
        val start = System.currentTimeMillis()
        return try {
            val bufferedImage: BufferedImage? = ImageIO.read(imageStream)
            if (bufferedImage == null) {
                throw IllegalArgumentException("Could not read image from stream. Unsupported format.")
            }
            
            logger.info("Starting OCR processing on image (dimensions: \${bufferedImage.width}x\${bufferedImage.height})")
            val tesseract = createTesseract()
            val result = tesseract.doOCR(bufferedImage)
            
            val duration = System.currentTimeMillis() - start
            logger.info("OCR completed successfully in \${duration}ms, extracted \${result.length} characters.")
            
            result.trim()
        } catch (e: Exception) {
            logger.error("OCR extraction failed after \${System.currentTimeMillis() - start}ms: \${e.message}", e)
            throw RuntimeException("OCR processing failed", e)
        }
    }

    fun extractTextFromPdf(pdfStream: InputStream): String {
        val start = System.currentTimeMillis()
        return try {
            val bytes = pdfStream.readAllBytes()
            val document = Loader.loadPDF(bytes)
            val renderer = PDFRenderer(document)
            val textBuilder = StringBuilder()
            
            val pageCount = document.numberOfPages
            logger.info("Starting OCR processing on PDF (\${pageCount} pages)")

            for (page in 0 until pageCount) {
                logger.info("Rendering PDF page \${page + 1}/\$pageCount")
                val bufferedImage = renderer.renderImageWithDPI(page, 300f, ImageType.RGB)
                
                // Create a strictly new instance per page processing round to fully isolate JNA pointers
                val tesseract = createTesseract()
                val pageText = tesseract.doOCR(bufferedImage)
                textBuilder.append(pageText).append("\n\n")
            }
            document.close()
            
            val duration = System.currentTimeMillis() - start
            val result = textBuilder.toString().trim()
            logger.info("PDF OCR completed successfully in \${duration}ms, extracted \${result.length} characters.")
            
            result
        } catch (e: Exception) {
            logger.error("PDF OCR extraction failed: \${e.message}", e)
            throw RuntimeException("PDF processing failed", e)
        }
    }
}
