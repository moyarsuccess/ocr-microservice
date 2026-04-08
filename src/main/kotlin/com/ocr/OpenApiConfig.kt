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
                .title("OCR Microservice")
                .description("Extracts text from images and PDF files using Tesseract OCR.")
                .version("1.0.4")
        )
}
