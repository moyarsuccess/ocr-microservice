package com.ocr.engine.stage2

import com.ocr.config.OcrProperties
import com.ocr.model.DocumentType
import com.ocr.model.ExtractionContext
import com.ocr.model.Mrz
import com.ocr.util.PipelineLogger
import org.springframework.stereotype.Component

/**
 * Stage 2 orchestrator.
 *
 * Decision flow:
 *   1. If the caller passed an `expectedType` hint, accept it (with confidence 1.0)
 *      and skip classification. The caller knows what they uploaded.
 *   2. If a TD3 MRZ was already parsed in Stage 1, the document is a passport.
 *      MRZ existence is an extremely strong signal — no further classification needed.
 *   3. Run the rule-based classifier. If its top score is confident enough AND
 *      the margin over the runner-up is wide enough, accept its verdict.
 *   4. Otherwise, fall back to the LLM classifier with the OCR text.
 *
 * This keeps the LLM call off the hot path for the vast majority of inputs
 * while still handling the long tail of ambiguous documents.
 */
@Component
class DocumentClassifier(
    private val rules: RuleBasedClassifier,
    private val llm: LlmClassifier,
    private val props: OcrProperties,
) {

    private val log = PipelineLogger("stage2.classify")

    fun classify(ctx: ExtractionContext) {
        log.begin()

        // Path 1 — explicit hint from caller.
        ctx.expectedType?.let {
            if (it != DocumentType.UNKNOWN) {
                ctx.documentType = it
                ctx.documentTypeConfidence = 1.0
                ctx.classifierUsed = "caller_hint"
                log.done("via" to "caller_hint", "doc" to it.code, "conf" to 1.0)
                return
            }
        }

        // Path 2 — MRZ found → passport, full stop.
        if (ctx.mrz != null) {
            ctx.documentType = DocumentType.PASSPORT
            ctx.documentTypeConfidence = if ((ctx.mrz as Mrz).allCheckDigitsValid) 1.0 else 0.95
            ctx.classifierUsed = "mrz_specialist"
            log.done(
                "via" to "mrz_specialist",
                "doc" to "passport",
                "conf" to ctx.documentTypeConfidence,
                "check_digits_valid" to (ctx.mrz as Mrz).allCheckDigitsValid,
            )
            return
        }

        // Path 3 — rules.
        val ruleResult = rules.classify(ctx.rawText)
        log.info(
            "rules",
            "top" to ruleResult.top.code,
            "score" to ruleResult.topScore,
            "second" to ruleResult.secondScore,
            "conf" to ruleResult.confidence,
            "margin" to ruleResult.margin,
        )
        val confidentEnough = ruleResult.confidence >= props.pipeline.ruleClassifierMinConfidence
        val wideEnoughMargin = ruleResult.margin >= props.pipeline.ruleClassifierMinMargin
        if (ruleResult.top != DocumentType.UNKNOWN && confidentEnough && wideEnoughMargin) {
            ctx.documentType = ruleResult.top
            ctx.documentTypeConfidence = ruleResult.confidence
            ctx.classifierUsed = "rules"
            log.done("via" to "rules", "doc" to ruleResult.top.code, "conf" to ruleResult.confidence)
            return
        }

        // Path 4 — LLM fallback.
        log.info("rules inconclusive, escalating to llm fallback")
        val (llmType, llmConfidence) = llm.classify(ctx.rawText)
        ctx.documentType = llmType
        ctx.documentTypeConfidence = llmConfidence
        ctx.classifierUsed = "llm_fallback"
        if (llmType == DocumentType.UNKNOWN) {
            ctx.warn("Document type could not be identified by rules or LLM classifier")
        }
        log.done("via" to "llm_fallback", "doc" to llmType.code, "conf" to llmConfidence)
    }
}
