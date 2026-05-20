# syntax=docker/dockerfile:1
# 1. Build stage
FROM gradle:8.6.0-jdk21 AS builder
WORKDIR /app
COPY . /app
RUN gradle build --no-daemon -x test

# 2. Runtime stage — small Alpine image with Tesseract + English/French data
FROM eclipse-temurin:21-jre-alpine

# Tesseract + the language packs Canada actually needs (eng, fra).
# Adding spa/deu costs a few MB and broadens applicability for translated docs.
RUN apk update && \
    apk add --no-cache \
    tesseract-ocr \
    tesseract-ocr-data-eng \
    tesseract-ocr-data-fra \
    tesseract-ocr-data-spa \
    tesseract-ocr-data-deu \
    fontconfig \
    ttf-dejavu \
    curl && \
    rm -rf /var/cache/apk/*

WORKDIR /app

ENV TESSERACT_DATA_PATH=/usr/share/tessdata
ENV SERVER_PORT=3001
# Default Ollama target — override via OLLAMA_BASE_URL at runtime.
ENV OLLAMA_BASE_URL=https://ollama.flutra.ca

COPY --from=builder /app/build/libs/*.jar app.jar

EXPOSE 3001

# /health is now served by OcrController — matches what this HEALTHCHECK hits.
HEALTHCHECK --interval=30s --timeout=5s --start-period=45s --retries=3 \
  CMD curl -fsS http://localhost:3001/health || exit 1

ENTRYPOINT ["java", "-Djava.net.preferIPv4Stack=true", "-jar", "app.jar"]
