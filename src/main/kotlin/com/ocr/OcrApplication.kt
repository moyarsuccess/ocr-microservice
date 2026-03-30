package com.ocr

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication

@SpringBootApplication
class OcrApplication

fun main(args: Array<String>) {
    runApplication<OcrApplication>(*args)
}
