package com.ocr.engine.stage1

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Deterministic tests for the MRZ side-channel. These do not require Tesseract
 * or Ollama — they exercise the ICAO 9303 check-digit math and the TD3 parser
 * directly against text input. If these break, every passport extraction silently
 * regresses, so they're worth their weight.
 */
class MrzExtractorTest {

    // textExtractor is unused in the text-based codepath under test, but is
    // required by the constructor. Pass a no-op stub.
    private val extractor: MrzExtractor = MrzExtractor(textExtractor = stubTextExtractor())

    @Test
    fun `check digit for ICAO 9303 canonical example`() {
        // Worked example from the ICAO 9303 documentation
        assertEquals(6, extractor.checkDigit("L898902C3"))   // doc number
        assertEquals(2, extractor.checkDigit("740812"))      // dob
        assertEquals(9, extractor.checkDigit("120415"))      // expiry
    }

    @Test
    fun `parses canonical TD3 MRZ and validates every check digit`() {
        val l1 = "P<UTOERIKSSON<<ANNA<MARIA<<<<<<<<<<<<<<<<<<<"
        val l2 = "L898902C36UTO7408122F1204159ZE184226B<<<<<10"
        val raw = "$l1\n$l2"

        val mrz = extractor.parseFromText(raw)
        assertNotNull(mrz, "MRZ should parse")
        requireNotNull(mrz)

        assertEquals("TD3", mrz.format)
        assertEquals("P", mrz.documentCode)
        assertEquals("UTO", mrz.issuingCountry)
        assertEquals("ERIKSSON", mrz.surname)
        assertEquals("ANNA MARIA", mrz.givenNames)
        assertEquals("L898902C3", mrz.documentNumber)
        assertEquals("UTO", mrz.nationality)
        assertEquals("1974-08-12", mrz.dateOfBirth)
        assertEquals("F", mrz.sex)
        assertEquals("2012-04-15", mrz.dateOfExpiry)

        assertTrue(mrz.documentNumberValid, "doc number check digit should validate")
        assertTrue(mrz.dateOfBirthValid, "dob check digit should validate")
        assertTrue(mrz.dateOfExpiryValid, "expiry check digit should validate")
        assertTrue(mrz.personalNumberValid, "personal number check digit should validate")
        assertTrue(mrz.compositeValid, "composite check digit should validate")
        assertTrue(mrz.allCheckDigitsValid)
    }

    @Test
    fun `detects tampered document number via failing check digit`() {
        // Change the last digit of the document number — composite + docnum check both flip.
        val l1 = "P<UTOERIKSSON<<ANNA<MARIA<<<<<<<<<<<<<<<<<<<"
        val l2 = "L898902C46UTO7408122F1204159ZE184226B<<<<<10"  // C3 -> C4
        val mrz = extractor.parseFromText("$l1\n$l2")
        assertNotNull(mrz)
        requireNotNull(mrz)
        assertFalse(mrz.documentNumberValid, "tampered doc number must fail its check digit")
        assertFalse(mrz.allCheckDigitsValid, "any single failure flips the aggregate flag")
    }

    @Test
    fun `returns null when input has no MRZ-shaped lines`() {
        val mrz = extractor.parseFromText("This is just a regular employment letter.\nNo MRZ here.")
        assertEquals(null, mrz)
    }

    @Test
    fun `tolerates whitespace and spurious chars before MRZ`() {
        // Tesseract often introduces a leading space on MRZ lines; the parser strips it.
        val l1 = "  P<UTOERIKSSON<<ANNA<MARIA<<<<<<<<<<<<<<<<<<<  "
        val l2 = "  L898902C36UTO7408122F1204159ZE184226B<<<<<10  "
        val mrz = extractor.parseFromText("garbage line\n$l1\n$l2\nmore garbage")
        assertNotNull(mrz)
        requireNotNull(mrz)
        assertTrue(mrz.allCheckDigitsValid)
    }

    /** Returns a minimally-functional TextExtractor so we can construct MrzExtractor. */
    private fun stubTextExtractor(): TextExtractor {
        // OcrProperties is a data class with all defaults; safe to instantiate directly.
        return TextExtractor(com.ocr.config.OcrProperties())
    }
}
