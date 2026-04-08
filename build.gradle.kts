plugins {
    kotlin("jvm") version "1.9.25"
    kotlin("plugin.spring") version "1.9.25"
    id("org.springframework.boot") version "3.4.2"
    id("io.spring.dependency-management") version "1.1.7"
}

group = "com.ocr"
version = "1.0.0"

java {
    sourceCompatibility = JavaVersion.VERSION_21
}

repositories {
    mavenCentral()
}

dependencies {
    implementation("org.springframework.boot:spring-boot-starter-web")
    implementation("com.fasterxml.jackson.module:jackson-module-kotlin")
    implementation("org.jetbrains.kotlin:kotlin-reflect")
    
    // Tess4J - JNA Wrapper for Tesseract OCR
    implementation("net.sourceforge.tess4j:tess4j:5.10.0")
    
    // Apache PDFBox for PDF rendering
    implementation("org.apache.pdfbox:pdfbox:3.0.2")

    // JAI Image I/O - required by PDFBox to decode JPEG2000 (JPX) images in PDFs
    implementation("com.github.jai-imageio:jai-imageio-jpeg2000:1.4.0")
    
    // Logging (Spring Boot brings SLF4J and Logback by default)
    implementation("ch.qos.logback:logback-classic")
    
    // LangChain4j - Ollama integration
    implementation("dev.langchain4j:langchain4j-ollama:0.36.2")

    // OpenAPI / Swagger UI
    implementation("org.springdoc:springdoc-openapi-starter-webmvc-ui:2.7.0")

    testImplementation("org.springframework.boot:spring-boot-starter-test")
}

tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile> {
    kotlinOptions {
        freeCompilerArgs += "-Xjsr305=strict"
        jvmTarget = "21"
    }
}

tasks.withType<Test> {
    useJUnitPlatform()
}
