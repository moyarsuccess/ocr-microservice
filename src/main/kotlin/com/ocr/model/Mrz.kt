package com.ocr.model

/**
 * Parsed TD3 (passport) Machine-Readable Zone, ICAO Doc 9303 Part 4.
 *
 * Field-level check-digit validity is exposed alongside each value so
 * downstream code can treat passing-check-digit fields as ground truth
 * and flag the rest.
 */
data class Mrz(
    val format: String,                         // "TD3"
    val line1: String,
    val line2: String,
    val documentCode: String,                   // typically "P" or "P<"
    val issuingCountry: String,                 // ISO 3166-1 alpha-3 (with `<` filler stripped)
    val surname: String,
    val givenNames: String,
    val documentNumber: String,
    val documentNumberValid: Boolean,
    val nationality: String,
    val dateOfBirth: String?,                   // YYYY-MM-DD or null if unparseable
    val dateOfBirthValid: Boolean,
    val sex: String,                            // M / F / X
    val dateOfExpiry: String?,                  // YYYY-MM-DD or null
    val dateOfExpiryValid: Boolean,
    val personalNumber: String?,
    val personalNumberValid: Boolean,
    val compositeValid: Boolean,
) {
    /** True iff every check digit in the MRZ verified. The strongest possible signal. */
    val allCheckDigitsValid: Boolean
        get() = documentNumberValid && dateOfBirthValid && dateOfExpiryValid &&
                personalNumberValid && compositeValid
}
