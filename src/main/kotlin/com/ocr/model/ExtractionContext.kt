package com.ocr.model

import java.awt.image.BufferedImage

/**
 * Mutable bag of state passed between pipeline stages.
 *
 * Each stage reads what it needs and writes its outputs onto the context.
 * This avoids an explosion of stage-specific DTOs and lets us add stages
 * without changing return types up and down the call tree.
 *
 * Stage 1 populates: pages, rawText, mrz (if applicable).
 * Stage 2 populates: documentType, documentTypeConfidence, classifierUsed.
 * Stage 3 populates: prompt, jsonSchema.
 * Stage 4 populates: extractedData.
 * Stage 5 populates: fieldConfidence, warnings, normalises extractedData.
 */
class ExtractionContext(
    val originalFilename: String?,
    val expectedType: DocumentType? = null,
    val includeRawText: Boolean = false,
    val visionModelOverride: String? = null,
) {
    // Stage 1 outputs
    var pages: List<BufferedImage> = emptyList()
    var rawText: String = ""
    var mrz: Mrz? = null

    // Stage 2 outputs
    var documentType: DocumentType = DocumentType.UNKNOWN
    var documentTypeConfidence: Double = 0.0
    var classifierUsed: String = ""

    // Stage 3 outputs
    var prompt: String = ""
    var jsonSchema: String = ""

    // Stage 4 outputs
    var extractedData: MutableMap<String, Any?> = mutableMapOf()

    // Stage 5 outputs
    var fieldConfidence: MutableMap<String, Double> = mutableMapOf()
    val warnings: MutableList<String> = mutableListOf()

    // Cross-cutting
    val stageTimings: MutableMap<String, Long> = linkedMapOf()
    val languageDetected: MutableList<String> = mutableListOf()

    fun warn(message: String) {
        warnings += message
    }

    inline fun <T> timed(stageName: String, block: () -> T): T {
        val start = System.currentTimeMillis()
        try {
            return block()
        } finally {
            stageTimings[stageName] = System.currentTimeMillis() - start
        }
    }
}
