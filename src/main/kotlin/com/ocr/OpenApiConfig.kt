package com.ocr

import io.swagger.v3.oas.models.OpenAPI
import io.swagger.v3.oas.models.info.Info
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

@Configuration
class OpenApiConfig {

    @Bean
    fun openApi(): OpenAPI = OpenAPI()
        .info(
            Info()
                .title("Hybrid OCR Microservice")
                .description(
                    "Hybrid 5-stage OCR pipeline for IRCC documents (passport, bank statement, " +
                        "employment letter, LOA, PAL, LMIA, GIC, ...). Returns strongly-typed JSON " +
                        "with per-field confidence scores. See /docs/OCR_DESIGN.md."
                )
                .version("2.0.0")
        )
}
