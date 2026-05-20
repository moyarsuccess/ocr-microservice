package com.ocr.engine.stage2

import com.ocr.model.DocumentType
import org.springframework.stereotype.Component

/**
 * Cheap, deterministic, regex-driven classifier.
 *
 * For each [DocumentType] we maintain a list of (pattern, weight) rules.
 * Total score per type = sum of weights of matched rules.
 *
 * The orchestrator decides whether the top score is high enough and whether
 * the margin over the runner-up is large enough to skip the LLM fallback.
 *
 * Keywords are deliberately broad and English/French where it matters
 * (Canadian docs are bilingual).
 */
@Component
class RuleBasedClassifier {

    data class Result(
        val top: DocumentType,
        val topScore: Int,
        val secondScore: Int,
        val confidence: Double,
        val margin: Double,
        val perType: Map<DocumentType, Int>,
    )

    private data class Rule(val pattern: Regex, val weight: Int)

    private val rules: Map<DocumentType, List<Rule>> = mapOf(
        DocumentType.PASSPORT to listOf(
            Rule(Regex("""^P[<A-Z][A-Z]{3}""", RegexOption.MULTILINE), 100),  // MRZ line 1 prefix
            Rule(Regex("""[A-Z0-9<]{44}"""), 60),                              // any 44-char MRZ-looking line
            Rule(Regex("""\bpassport\b""", RegexOption.IGNORE_CASE), 20),
            Rule(Regex("""\bMRZ\b"""), 30),
            Rule(Regex("""\bnationality\b""", RegexOption.IGNORE_CASE), 5),
            Rule(Regex("""\bdate of expiry\b""", RegexOption.IGNORE_CASE), 8),
            Rule(Regex("""\bplace of birth\b""", RegexOption.IGNORE_CASE), 6),
        ),
        DocumentType.NATIONAL_ID to listOf(
            Rule(Regex("""\bnational[\s-]?id\b""", RegexOption.IGNORE_CASE), 40),
            Rule(Regex("""\bidentity card\b""", RegexOption.IGNORE_CASE), 30),
            Rule(Regex("""carte d'identit[ée]""", RegexOption.IGNORE_CASE), 30),
            Rule(Regex("""\bcitizen(ship)?\b""", RegexOption.IGNORE_CASE), 8),
            Rule(Regex("""\bpersonal\s+number\b""", RegexOption.IGNORE_CASE), 10),
        ),
        DocumentType.BANK_STATEMENT to listOf(
            Rule(Regex("""\bstatement period\b""", RegexOption.IGNORE_CASE), 40),
            Rule(Regex("""\bopening balance\b""", RegexOption.IGNORE_CASE), 30),
            Rule(Regex("""\bclosing balance\b""", RegexOption.IGNORE_CASE), 30),
            Rule(Regex("""\baccount\s+number\b""", RegexOption.IGNORE_CASE), 15),
            Rule(Regex("""\bbank statement\b""", RegexOption.IGNORE_CASE), 40),
            Rule(Regex("""\brelev[ée] de compte""", RegexOption.IGNORE_CASE), 40),  // FR
            Rule(Regex("""\btransactions?\b""", RegexOption.IGNORE_CASE), 5),
            Rule(Regex("""\bIBAN\b"""), 10),
            Rule(Regex("""\bSWIFT\b"""), 8),
        ),
        DocumentType.PAY_STUB to listOf(
            Rule(Regex("""\bgross (pay|earnings|wages)\b""", RegexOption.IGNORE_CASE), 30),
            Rule(Regex("""\bnet (pay|earnings)\b""", RegexOption.IGNORE_CASE), 25),
            Rule(Regex("""\bpay (period|date|stub)\b""", RegexOption.IGNORE_CASE), 25),
            Rule(Regex("""\bYTD\b"""), 15),
            Rule(Regex("""\bearnings statement\b""", RegexOption.IGNORE_CASE), 35),
            Rule(Regex("""\bdeductions?\b""", RegexOption.IGNORE_CASE), 8),
            Rule(Regex("""\bCPP|EI\b""", RegexOption.IGNORE_CASE), 8),  // Canadian deductions
            Rule(Regex("""bulletin de paie""", RegexOption.IGNORE_CASE), 35),  // FR
        ),
        DocumentType.EMPLOYMENT_LETTER to listOf(
            Rule(Regex("""to whom it may concern""", RegexOption.IGNORE_CASE), 30),
            Rule(Regex("""employment verification""", RegexOption.IGNORE_CASE), 40),
            Rule(Regex("""\bemployed (since|as|by|with)\b""", RegexOption.IGNORE_CASE), 25),
            Rule(Regex("""currently employed""", RegexOption.IGNORE_CASE), 25),
            Rule(Regex("""confirms that""", RegexOption.IGNORE_CASE), 6),
            Rule(Regex("""salary of""", RegexOption.IGNORE_CASE), 8),
            Rule(Regex("""this letter (is to confirm|serves)""", RegexOption.IGNORE_CASE), 15),
        ),
        DocumentType.JOB_OFFER_LETTER to listOf(
            Rule(Regex("""offer of employment""", RegexOption.IGNORE_CASE), 40),
            Rule(Regex("""we are pleased to offer""", RegexOption.IGNORE_CASE), 35),
            Rule(Regex("""\boffer letter\b""", RegexOption.IGNORE_CASE), 30),
            Rule(Regex("""start(ing)? date""", RegexOption.IGNORE_CASE), 6),
            Rule(Regex("""annual salary""", RegexOption.IGNORE_CASE), 8),
            Rule(Regex("""\bNOC\b"""), 10),
        ),
        DocumentType.LETTER_OF_ACCEPTANCE to listOf(
            Rule(Regex("""letter of acceptance""", RegexOption.IGNORE_CASE), 50),
            Rule(Regex("""offer of admission""", RegexOption.IGNORE_CASE), 35),
            Rule(Regex("""\bDLI\s*#?\b""", RegexOption.IGNORE_CASE), 30),
            Rule(Regex("""\bO\d{10}\b"""), 35),  // IRCC DLI number pattern
            Rule(Regex("""designated learning institution""", RegexOption.IGNORE_CASE), 35),
            Rule(Regex("""program of study""", RegexOption.IGNORE_CASE), 15),
            Rule(Regex("""\btuition\b""", RegexOption.IGNORE_CASE), 10),
        ),
        DocumentType.PROVINCIAL_ATTESTATION_LETTER to listOf(
            Rule(Regex("""provincial attestation letter""", RegexOption.IGNORE_CASE), 60),
            Rule(Regex("""territorial attestation letter""", RegexOption.IGNORE_CASE), 60),
            Rule(Regex("""\bPAL\b"""), 25),
            Rule(Regex("""\bTAL\b"""), 25),
            Rule(Regex("""attestation number""", RegexOption.IGNORE_CASE), 35),
            Rule(Regex("""lettre d'attestation""", RegexOption.IGNORE_CASE), 50),
        ),
        DocumentType.LMIA_LETTER to listOf(
            Rule(Regex("""\bLMIA\b"""), 50),
            Rule(Regex("""labour market impact assessment""", RegexOption.IGNORE_CASE), 60),
            Rule(Regex("""\bESDC\b"""), 25),
            Rule(Regex("""employment and social development""", RegexOption.IGNORE_CASE), 30),
            Rule(Regex("""\bA\d{7}\b"""), 25),  // LMIA file number
            Rule(Regex("""\bNOC\s*\d{4,5}\b""", RegexOption.IGNORE_CASE), 15),
        ),
        DocumentType.GIC_CERTIFICATE to listOf(
            Rule(Regex("""guaranteed investment certificate""", RegexOption.IGNORE_CASE), 60),
            Rule(Regex("""\bGIC\b"""), 30),
            Rule(Regex("""maturity date""", RegexOption.IGNORE_CASE), 15),
            Rule(Regex("""investment certificate""", RegexOption.IGNORE_CASE), 30),
            Rule(Regex("""student\s+GIC""", RegexOption.IGNORE_CASE), 30),
        ),
        DocumentType.BIRTH_CERTIFICATE to listOf(
            Rule(Regex("""birth certificate""", RegexOption.IGNORE_CASE), 60),
            Rule(Regex("""certificate of birth""", RegexOption.IGNORE_CASE), 60),
            Rule(Regex("""certificat de naissance""", RegexOption.IGNORE_CASE), 60),
            Rule(Regex("""\bregistrar\b""", RegexOption.IGNORE_CASE), 10),
            Rule(Regex("""place of birth""", RegexOption.IGNORE_CASE), 8),
        ),
        DocumentType.MARRIAGE_CERTIFICATE to listOf(
            Rule(Regex("""marriage certificate""", RegexOption.IGNORE_CASE), 60),
            Rule(Regex("""certificate of marriage""", RegexOption.IGNORE_CASE), 60),
            Rule(Regex("""certificat de mariage""", RegexOption.IGNORE_CASE), 60),
            Rule(Regex("""married on""", RegexOption.IGNORE_CASE), 15),
            Rule(Regex("""\bspouse\b""", RegexOption.IGNORE_CASE), 5),
        ),
        DocumentType.EDUCATION_CREDENTIAL to listOf(
            Rule(Regex("""\bdiploma\b""", RegexOption.IGNORE_CASE), 25),
            Rule(Regex("""\bdegree\b""", RegexOption.IGNORE_CASE), 15),
            Rule(Regex("""\btranscript\b""", RegexOption.IGNORE_CASE), 30),
            Rule(Regex("""\bGPA\b"""), 25),
            Rule(Regex("""bachelor of""", RegexOption.IGNORE_CASE), 20),
            Rule(Regex("""master of""", RegexOption.IGNORE_CASE), 20),
            Rule(Regex("""academic record""", RegexOption.IGNORE_CASE), 25),
            Rule(Regex("""\brelev[ée] de notes""", RegexOption.IGNORE_CASE), 30),  // FR
        ),
    )

    fun classify(text: String): Result {
        if (text.isBlank()) {
            return Result(DocumentType.UNKNOWN, 0, 0, 0.0, 0.0, emptyMap())
        }
        val scores: Map<DocumentType, Int> = rules.mapValues { (_, ruleList) ->
            ruleList.sumOf { rule -> if (rule.pattern.containsMatchIn(text)) rule.weight else 0 }
        }
        val sorted = scores.entries.sortedByDescending { it.value }
        val top = sorted.firstOrNull() ?: return Result(DocumentType.UNKNOWN, 0, 0, 0.0, 0.0, scores)
        val second = sorted.getOrNull(1)?.value ?: 0

        // Confidence: top score / (top score + saturation constant). Hits a soft cap as
        // signals accumulate. 100 is a single strong signal; 200+ is multiple strong signals.
        val confidence = top.value.toDouble() / (top.value + 80.0)
        val margin = if (top.value == 0) 0.0 else (top.value - second).toDouble() / top.value

        val winner = if (top.value == 0) DocumentType.UNKNOWN else top.key
        return Result(winner, top.value, second, confidence, margin, scores)
    }
}
