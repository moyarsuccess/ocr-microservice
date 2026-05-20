package com.ocr.util

import org.slf4j.Logger
import org.slf4j.LoggerFactory

/**
 * Tiny wrapper around SLF4J that enforces a consistent, grep-friendly log
 * format across every pipeline stage.
 *
 * Every line is prefixed with `[stage.subsystem]`. Stage start/end events use
 * `BEGIN` / `DONE` markers so you can scan a console and instantly see the
 * shape of a request:
 *
 *   [stage1.ocr]      BEGIN pages=3 lang=eng+fra dpi=300
 *   [stage1.ocr]      page=1/3 size=2480x3508 chars=1234 took=812ms
 *   [stage1.ocr]      DONE total=3324chars took=2418ms
 *   [stage1.mrz]      BEGIN
 *   [stage1.mrz]      DONE found=true checkDigitsValid=true took=620ms
 *   [stage2.classify] verdict=passport via=mrz_specialist conf=1.00
 *   [stage3.template] doc=passport prompt_chars=1240 schema_chars=890
 *   [stage4.vision]   BEGIN model=qwen2.5vl:7b imgs=3
 *   [stage4.vision]   DONE fields=12 took=4812ms
 *   [stage5.validate] mrz_xcheck docNumber=match dob=match expiry=match
 *   [stage5.validate] DONE warnings=0 low_conf=0
 *   [pipeline]        DONE doc=passport conf=1.00 total=9612ms
 */
class PipelineLogger(stage: String) {

    private val tag: String = "[$stage]".padEnd(STAGE_TAG_WIDTH)
    private val log: Logger = LoggerFactory.getLogger("ocr.$stage")

    fun begin(vararg kv: Pair<String, Any?>) {
        log.info("$tag BEGIN${fmt(kv)}")
    }

    fun info(message: String, vararg kv: Pair<String, Any?>) {
        log.info("$tag $message${fmt(kv)}")
    }

    fun done(vararg kv: Pair<String, Any?>) {
        log.info("$tag DONE${fmt(kv)}")
    }

    fun warn(message: String, vararg kv: Pair<String, Any?>) {
        log.warn("$tag WARN $message${fmt(kv)}")
    }

    fun error(message: String, throwable: Throwable? = null, vararg kv: Pair<String, Any?>) {
        if (throwable != null) log.error("$tag ERROR $message${fmt(kv)}", throwable)
        else log.error("$tag ERROR $message${fmt(kv)}")
    }

    fun debug(message: String, vararg kv: Pair<String, Any?>) {
        if (log.isDebugEnabled) log.debug("$tag $message${fmt(kv)}")
    }

    private fun fmt(kv: Array<out Pair<String, Any?>>): String {
        if (kv.isEmpty()) return ""
        val sb = StringBuilder()
        for ((k, v) in kv) {
            sb.append(' ').append(k).append('=').append(formatValue(v))
        }
        return sb.toString()
    }

    private fun formatValue(v: Any?): String = when (v) {
        null -> "null"
        is Double -> "%.2f".format(v)
        is Float -> "%.2f".format(v)
        is String -> if (v.contains(' ')) "\"$v\"" else v
        else -> v.toString()
    }

    companion object {
        private const val STAGE_TAG_WIDTH = 18 // keeps columns aligned across the longest tag we use
    }
}
