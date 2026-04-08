# 1. Build environment using Gradle
FROM gradle:8.6.0-jdk21 AS builder

WORKDIR /app
# We first copy everything, then build
COPY . /app

# Run gradle build (this will compile the Kotlin code to a fat jar)
RUN gradle build --no-daemon -x test

# 2. Production runtime image
FROM eclipse-temurin:21-jre-alpine

# Install tesseract OCR and its language packs
RUN apk update && \
    apk add --no-cache \
    tesseract-ocr \
    tesseract-ocr-data-eng \
    tesseract-ocr-data-fra \
    tesseract-ocr-data-spa \
    tesseract-ocr-data-deu \
    fontconfig \
    ttf-dejavu && \
    rm -rf /var/cache/apk/*

WORKDIR /app

# The default path to tessdata in Alpine's tesseract-ocr package
ENV TESSERACT_DATA_PATH=/usr/share/tessdata
ENV SERVER_PORT=3001

COPY --from=builder /app/build/libs/*.jar app.jar

EXPOSE 3001

HEALTHCHECK --interval=30s --timeout=5s --start-period=30s --retries=3 \
  CMD wget --quiet --tries=1 --spider http://localhost:3001/health || exit 1

ENTRYPOINT ["java", "-Djava.net.preferIPv4Stack=true", "-jar", "app.jar"]
