package com.ocr

import java.io.InputStream

interface OcrProvider {
    fun extractTextFromImage(imageStream: InputStream): String
    fun extractTextFromPdf(pdfStream: InputStream): String
}
