package com.ocr.engine.stage1

import com.ocr.model.Mrz
import com.ocr.util.PipelineLogger
import org.springframework.stereotype.Component
import java.awt.image.BufferedImage
import java.time.LocalDate

/**
 * Passport MRZ specialist (ICAO Doc 9303 Part 4, TD3 format).
 *
 * Strategy:
 *   1. Crop the bottom ~30% of the page (where MRZ lives on a passport bio page).
 *   2. Re-OCR that strip with PSM 6 + a tight character whitelist — Tesseract is
 *      much more accurate on MRZ when constrained to `A-Z0-9<`.
 *   3. Scan the result for two consecutive 44-char lines that look like a TD3 MRZ.
 *   4. Parse fields + verify ICAO check digits.
 *
 * If everything check-digit-validates, the resulting [Mrz] is mathematically
 * guaranteed to be correct — no LLM hallucination possible. That's what makes
 * it worth running as a side-channel even when a vision LLM is doing the bulk
 * of the work.
 */
@Component
class MrzExtractor(private val textExtractor: TextExtractor) {

    private val log = PipelineLogger("stage1.mrz")

    private val mrzWhitelist = "ABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789<"
    private val td3Pattern = Regex("^[A-Z0-9<]{44}$")

    /**
     * Try to find and parse a TD3 MRZ on any of the given pages.
     *
     * Strategy per page: re-OCR the bottom strip with MRZ-tuned settings.
     * Returns null if no valid TD3 MRZ is found on any page.
     */
    fun tryExtract(pages: List<BufferedImage>): Mrz? {
        log.begin("pages" to pages.size)
        val start = System.currentTimeMillis()
        for ((idx, page) in pages.withIndex()) {
            val candidate = ocrBottomStrip(page)
            val mrz = parseFromText(candidate)
            if (mrz != null) {
                log.done(
                    "found" to true,
                    "page" to (idx + 1),
                    "check_digits_valid" to mrz.allCheckDigitsValid,
                    "took_ms" to (System.currentTimeMillis() - start),
                )
                return mrz
            }
        }
        log.done("found" to false, "took_ms" to (System.currentTimeMillis() - start))
        return null
    }

    /** Public so the validator can re-parse a candidate string. */
    fun parseFromText(rawText: String): Mrz? {
        if (rawText.isBlank()) return null
        val cleaned = rawText.lineSequence()
            .map { it.uppercase().replace(" ", "").replace("«", "<").replace("«", "<") }
            .map { it.filter { c -> c in mrzWhitelist } }
            .filter { it.isNotEmpty() }
            .toList()

        // Find two adjacent 44-char lines; first must start with passport indicator.
        for (i in 0 until cleaned.size - 1) {
            val l1 = cleaned[i]
            val l2 = cleaned[i + 1]
            if (td3Pattern.matches(l1) && td3Pattern.matches(l2) && (l1.startsWith("P<") || l1.startsWith("P"))) {
                return parseTd3(l1, l2) ?: continue
            }
        }
        return null
    }

    private fun ocrBottomStrip(page: BufferedImage): String {
        val stripHeight = (page.height * 0.30).toInt().coerceAtLeast(64)
        val y = page.height - stripHeight
        val strip = page.getSubimage(0, y, page.width, stripHeight)
        // PSM 6 = uniform block of text; restricts Tesseract to MRZ characters only.
        return textExtractor.ocr(strip, psm = 6, characterWhitelist = mrzWhitelist, language = "eng")
    }

    private fun parseTd3(line1: String, line2: String): Mrz? {
        if (line1.length != 44 || line2.length != 44) return null
        return try {
            val documentCode = line1.substring(0, 2).trimEnd('<')
            val issuingCountry = line1.substring(2, 5).trimEnd('<')
            val nameField = line1.substring(5).trimEnd('<')
            val nameParts = nameField.split("<<", limit = 2)
            val surname = nameParts.getOrNull(0)?.replace('<', ' ')?.trim() ?: ""
            val givenNames = nameParts.getOrNull(1)?.replace('<', ' ')?.trim() ?: ""

            val docNumberRaw = line2.substring(0, 9)
            val docNumber = docNumberRaw.trimEnd('<')
            val docNumberCheck = line2[9].digitToIntOrNull() ?: -1
            val docNumberValid = checkDigit(docNumberRaw) == docNumberCheck

            val nationality = line2.substring(10, 13).trimEnd('<')

            val dobRaw = line2.substring(13, 19)
            val dobCheck = line2[19].digitToIntOrNull() ?: -1
            val dobValid = checkDigit(dobRaw) == dobCheck
            val dob = parseDate(dobRaw, isExpiry = false)

            val sex = line2[20].let { if (it == '<') "X" else it.toString() }

            val expRaw = line2.substring(21, 27)
            val expCheck = line2[27].digitToIntOrNull() ?: -1
            val expValid = checkDigit(expRaw) == expCheck
            val expiry = parseDate(expRaw, isExpiry = true)

            val personalRaw = line2.substring(28, 42)
            val personal = personalRaw.trimEnd('<').ifBlank { null }
            val personalCheckChar = line2[42]
            // Per ICAO 9303, if personal number is all filler `<`, the check digit
            // may legally be `<` or `0`. Treat either as valid in that case.
            val personalValid = when {
                personalRaw.all { it == '<' } -> personalCheckChar == '<' || personalCheckChar == '0'
                else -> checkDigit(personalRaw) == (personalCheckChar.digitToIntOrNull() ?: -1)
            }

            val compositeInput = line2.substring(0, 10) + line2.substring(13, 20) + line2.substring(21, 43)
            val compositeCheck = line2[43].digitToIntOrNull() ?: -1
            val compositeValid = checkDigit(compositeInput) == compositeCheck

            Mrz(
                format = "TD3",
                line1 = line1,
                line2 = line2,
                documentCode = documentCode,
                issuingCountry = issuingCountry,
                surname = surname,
                givenNames = givenNames,
                documentNumber = docNumber,
                documentNumberValid = docNumberValid,
                nationality = nationality,
                dateOfBirth = dob,
                dateOfBirthValid = dobValid,
                sex = sex,
                dateOfExpiry = expiry,
                dateOfExpiryValid = expValid,
                personalNumber = personal,
                personalNumberValid = personalValid,
                compositeValid = compositeValid,
            )
        } catch (e: Exception) {
            log.debug("td3 parse failed", "err" to e.message)
            null
        }
    }

    // ----- ICAO 9303 check digit -----
    // weights cycle 7,3,1; '<' = 0, '0'..'9' = 0..9, 'A'..'Z' = 10..35

    private fun charValue(c: Char): Int = when (c) {
        '<' -> 0
        in '0'..'9' -> c - '0'
        in 'A'..'Z' -> c - 'A' + 10
        else -> 0
    }

    internal fun checkDigit(input: String): Int {
        val weights = intArrayOf(7, 3, 1)
        var total = 0
        for ((i, c) in input.withIndex()) {
            total += charValue(c) * weights[i % 3]
        }
        return total % 10
    }

    private fun parseDate(yymmdd: String, isExpiry: Boolean): String? {
        if (yymmdd.length != 6 || yymmdd.any { !it.isDigit() }) return null
        val yy = yymmdd.substring(0, 2).toInt()
        val mm = yymmdd.substring(2, 4).toInt()
        val dd = yymmdd.substring(4, 6).toInt()
        if (mm !in 1..12 || dd !in 1..31) return null
        val currentYy = LocalDate.now().year % 100
        val year = when {
            isExpiry -> 2000 + yy   // expiry is forward-looking; 21xx unlikely until well past 2050
            yy > currentYy -> 1900 + yy
            else -> 2000 + yy
        }
        return try {
            LocalDate.of(year, mm, dd).toString()
        } catch (_: Exception) {
            null
        }
    }
}
