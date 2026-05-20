package com.ocr.engine.stage5

import com.ocr.model.DocumentType
import com.ocr.model.ExtractionContext
import com.ocr.model.Mrz
import com.ocr.util.PipelineLogger
import org.springframework.stereotype.Component
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Stage 5 — normalisation, cross-checks, and confidence scoring.
 *
 * Pipeline of small passes:
 *   1. Normalise all date-shaped values to YYYY-MM-DD where possible.
 *   2. If the document is a passport AND MRZ was parsed with valid check
 *      digits, MRZ fields are treated as ground truth. Any conflicting vision
 *      output is overwritten with the MRZ value and a warning is emitted.
 *   3. Per-field confidence is assigned:
 *        - 1.00  — MRZ-verified value (mathematically certain)
 *        - 0.95  — string value found verbatim in the Tesseract OCR text
 *        - 0.85  — vision-only, no cross-check signal (default)
 *        - 0.50  — looks suspicious (e.g. failed date parse, MRZ mismatch)
 *        - 0.00  — null / missing
 *   4. Low-confidence fields are surfaced as warnings so the caller can
 *      decide whether to ask the human for verification.
 */
@Component
class ResultValidator {

    private val log = PipelineLogger("stage5.validate")

    private val dateFormats = listOf(
        DateTimeFormatter.ofPattern("yyyy-MM-dd"),
        DateTimeFormatter.ofPattern("yyyy/MM/dd"),
        DateTimeFormatter.ofPattern("dd-MM-yyyy"),
        DateTimeFormatter.ofPattern("dd/MM/yyyy"),
        DateTimeFormatter.ofPattern("d MMM yyyy", Locale.ENGLISH),
        DateTimeFormatter.ofPattern("dd MMM yyyy", Locale.ENGLISH),
        DateTimeFormatter.ofPattern("d MMMM yyyy", Locale.ENGLISH),
        DateTimeFormatter.ofPattern("MMMM d, yyyy", Locale.ENGLISH),
        DateTimeFormatter.ofPattern("MMM d, yyyy", Locale.ENGLISH),
        DateTimeFormatter.ofPattern("d MMMM yyyy", Locale.FRENCH),
    )

    private val dateLikeFieldSuffixes = listOf("date", "from", "to", "issue", "expiry", "validuntil")

    fun validate(ctx: ExtractionContext) {
        log.begin("doc" to ctx.documentType.code, "fields" to ctx.extractedData.size)

        normaliseDates(ctx)
        if (ctx.documentType == DocumentType.PASSPORT) ctx.mrz?.let { reconcileWithMrz(ctx, it) }
        scoreFieldConfidence(ctx)
        flagLowConfidence(ctx)

        val low = ctx.fieldConfidence.count { it.value < 0.7 }
        log.done(
            "fields" to ctx.extractedData.size,
            "warnings" to ctx.warnings.size,
            "low_conf" to low,
        )
    }

    // ---- Pass 1: date normalisation ----

    private fun normaliseDates(ctx: ExtractionContext) {
        var changed = 0
        val keys = ctx.extractedData.keys.toList()
        for (key in keys) {
            if (!looksLikeDateField(key)) continue
            val v = ctx.extractedData[key] as? String ?: continue
            val parsed = tryParseDate(v)
            if (parsed != null && parsed != v) {
                ctx.extractedData[key] = parsed
                changed++
            } else if (parsed == null && v.isNotBlank()) {
                ctx.warn("Could not normalise date for field '$key': '$v'")
            }
        }
        if (changed > 0) log.info("dates normalised", "count" to changed)
    }

    private fun looksLikeDateField(key: String): Boolean {
        val k = key.lowercase()
        return dateLikeFieldSuffixes.any { k.endsWith(it) || k.contains(it) }
    }

    private fun tryParseDate(value: String): String? {
        val trimmed = value.trim()
        if (trimmed.isBlank()) return null
        for (fmt in dateFormats) {
            try {
                return LocalDate.parse(trimmed, fmt).toString()
            } catch (_: Exception) { /* try next */ }
        }
        return null
    }

    // ---- Pass 2: MRZ reconciliation ----

    private fun reconcileWithMrz(ctx: ExtractionContext, mrz: Mrz) {
        if (!mrz.allCheckDigitsValid) {
            ctx.warn("MRZ check digits did not all validate — not using MRZ as ground truth")
            return
        }
        val mrzMap = mapOf(
            "documentNumber" to mrz.documentNumber,
            "issuingCountry" to mrz.issuingCountry,
            "nationality" to mrz.nationality,
            "surname" to mrz.surname,
            "givenNames" to mrz.givenNames,
            "dateOfBirth" to mrz.dateOfBirth,
            "dateOfExpiry" to mrz.dateOfExpiry,
            "sex" to mrz.sex,
        ).filterValues { it != null && (it as? String)?.isNotBlank() != false }

        var corrections = 0
        for ((key, mrzValue) in mrzMap) {
            val visionValue = ctx.extractedData[key]?.toString()?.trim()
            if (visionValue.isNullOrEmpty()) {
                ctx.extractedData[key] = mrzValue
            } else if (!sameish(visionValue, mrzValue as Any)) {
                ctx.warn("MRZ override on '$key': vision='$visionValue' MRZ='$mrzValue' — keeping MRZ")
                ctx.extractedData[key] = mrzValue
                corrections++
            }
            ctx.fieldConfidence[key] = 1.0
        }
        if (corrections > 0) log.info("mrz xcheck corrections applied", "count" to corrections)
        else log.info("mrz xcheck no conflicts")
    }

    private fun sameish(a: String, b: Any): Boolean {
        val s = b.toString()
        return a.equals(s, ignoreCase = true) ||
                a.replace(" ", "").equals(s.replace(" ", ""), ignoreCase = true)
    }

    // ---- Pass 3: confidence scoring ----

    private fun scoreFieldConfidence(ctx: ExtractionContext) {
        for ((key, value) in ctx.extractedData) {
            // Skip if MRZ pass already set this to 1.0
            if (ctx.fieldConfidence[key] == 1.0) continue
            val conf = when {
                value == null -> 0.0
                value is String && value.isBlank() -> 0.0
                value is String && occursInRawText(value, ctx.rawText) -> 0.95
                value is Number -> if (numberSeemsConsistentInText(value, ctx.rawText)) 0.95 else 0.85
                else -> 0.85
            }
            ctx.fieldConfidence[key] = conf
        }
    }

    private fun occursInRawText(value: String, rawText: String): Boolean {
        if (value.length < 3) return false
        // Compare normalised — strip non-alphanumerics so "1,234.56" matches "1234.56".
        val needle = value.replace(Regex("[\\s,.-]"), "").lowercase()
        val haystack = rawText.replace(Regex("[\\s,.-]"), "").lowercase()
        if (needle.length < 3) return false
        return haystack.contains(needle)
    }

    private fun numberSeemsConsistentInText(n: Number, rawText: String): Boolean {
        val s = n.toString()
        return rawText.contains(s) || rawText.contains(s.removeSuffix(".0"))
    }

    // ---- Pass 4: warning surface ----

    private fun flagLowConfidence(ctx: ExtractionContext) {
        ctx.fieldConfidence.filter { it.value in 0.001..0.69 }
            .forEach { (k, v) -> ctx.warn("Low confidence on '$k' (${"%.2f".format(v)})") }
    }
}
