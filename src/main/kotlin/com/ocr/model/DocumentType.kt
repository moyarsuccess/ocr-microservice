package com.ocr.model

/**
 * The set of IRCC-relevant source documents the engine knows how to extract.
 *
 * Each value owns:
 *   - a stable string code that appears in API responses (kept stable across releases),
 *   - a human-readable label (for logs / Swagger),
 *   - whether the MRZ specialist side-channel applies (passports only today).
 *
 * The `UNKNOWN` value is returned when neither the rule classifier nor the
 * LLM classifier can identify the document with sufficient confidence.
 */
enum class DocumentType(
    val code: String,
    val label: String,
    val hasMrz: Boolean = false,
) {
    PASSPORT("passport", "Passport bio page", hasMrz = true),
    NATIONAL_ID("national_id", "National ID card"),
    BANK_STATEMENT("bank_statement", "Bank statement"),
    PAY_STUB("pay_stub", "Pay stub / salary slip"),
    EMPLOYMENT_LETTER("employment_letter", "Employment verification letter"),
    LETTER_OF_ACCEPTANCE("letter_of_acceptance", "Letter of Acceptance (DLI)"),
    PROVINCIAL_ATTESTATION_LETTER("provincial_attestation_letter", "Provincial / Territorial Attestation Letter"),
    LMIA_LETTER("lmia_letter", "LMIA decision letter"),
    JOB_OFFER_LETTER("job_offer_letter", "Job offer letter"),
    GIC_CERTIFICATE("gic_certificate", "GIC investment certificate"),
    BIRTH_CERTIFICATE("birth_certificate", "Birth certificate"),
    MARRIAGE_CERTIFICATE("marriage_certificate", "Marriage certificate"),
    EDUCATION_CREDENTIAL("education_credential", "Diploma / degree / transcript"),
    UNKNOWN("unknown", "Unrecognised document");

    companion object {
        fun fromCode(code: String?): DocumentType =
            entries.firstOrNull { it.code.equals(code, ignoreCase = true) } ?: UNKNOWN
    }
}
