plugins {
    kotlin("jvm") version "1.9.25"
    kotlin("plugin.spring") version "1.9.25"
    id("org.springframework.boot") version "3.4.2"
    id("io.spring.dependency-management") version "1.1.7"
}

group = "com.ocr"
version = "2.0.0"

java {
    sourceCompatibility = JavaVersion.VERSION_21
}

repositories {
    mavenCentral()
}

dependencies {
    // --- Spring Boot ---
    implementation("org.springframework.boot:spring-boot-starter-web")
    implementation("com.fasterxml.jackson.module:jackson-module-kotlin")
    implementation("org.jetbrains.kotlin:kotlin-reflect")

    // --- OCR ---
    // Tess4J — JNA wrapper around the native Tesseract library.
    implementation("net.sourceforge.tess4j:tess4j:5.10.0")

    // --- PDF rendering ---
    implementation("org.apache.pdfbox:pdfbox:3.0.2")
    // JPEG-2000 (JPX) support — required for PDFs that embed JP2-encoded scans.
    implementation("com.github.jai-imageio:jai-imageio-jpeg2000:1.4.0")

    // --- Logging (Logback comes via Spring Boot) ---
    implementation("ch.qos.logback:logback-classic")

    // --- OpenAPI / Swagger UI ---
    implementation("org.springdoc:springdoc-openapi-starter-webmvc-ui:2.7.0")

    // --- Test ---
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
