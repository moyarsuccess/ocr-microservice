package com.ocr

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.context.properties.ConfigurationPropertiesScan
import org.springframework.boot.runApplication

@SpringBootApplication
@ConfigurationPropertiesScan("com.ocr.config")
class OcrApplication

fun main(args: Array<String>) {
    runApplication<OcrApplication>(*args)
}
