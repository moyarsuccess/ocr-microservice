# Tesseract OCR Microservice

A highly optimized, single-purpose OCR microservice written in Kotlin and Spring Boot. It provides a simple REST API to extract text from images using the Tesseract OCR engine via Tess4J.

## Features

- **Blazing Fast**: Uses standard Spring Boot and a thread-local cached instance of Tesseract for minimal overhead per request.
- **Tesseract 4/5 Optimized**: Configured with LSTM engine mode (`OEM_LSTM_ONLY`) and fully automatic page segmentation (`PSM 3`) for the best balance of speed and accuracy.
- **Tiny Footprint**: The provided `Dockerfile` uses Alpine Linux, reducing the image size from 20GB+ (in multi-engine setups) to just a few hundred megabytes while still packing the necessary language packs.
- **Clean Architecture**: 100% Kotlin codebase that is easily extensible by Java/Kotlin developers. Easy to configure via `application.properties`.
- **Pretty Logging**: Includes a beautifully formatted Logback configuration for console output.

## Requirements

To run this locally without Docker, you need:
- **Java 21**
- **Tesseract OCR** installed on your system system-wide (e.g., `apt install tesseract-ocr`, `brew install tesseract`, or the Windows installer).
- Ensure the `TESSDATA_PREFIX` or local `tesseract.datapath` is set correctly so Tess4J can find your `tessdata` folder containing `eng.traineddata`.

## Running Locally

```bash
# 1. Build and run the project using Gradle Wrapper (if generated) or your local Gradle install
gradle bootRun
```

The API will start at `http://localhost:3001`.

## Docker

The recommended way to run this microservice is via Docker. The Dockerfile handles all system dependencies including Tesseract and the English (`eng`), French (`fra`), Spanish (`spa`), and German (`deu`) language packs.

```bash
# Build the extremely lightweight image
docker build -t ocr-microservice .

# Run the container
docker run -p 3001:3001 ocr-microservice
```

## API Usage

### Health Check

```
GET /ocr/health
```

**Response:**
```json
{
  "status": "ok"
}
```

### OCR — Image Upload (Multipart)

Extract text from an image by uploading it.

```bash
curl -X POST http://localhost:3001/ocr \
  -F "file=@/path/to/document.png"
```

**Response:**
```json
{
  "success": true,
  "engine": "tesseract",
  "text": "Your extracted text will appear here...",
  "processingTimeMs": 420,
  "error": null
}
```

## Configuration

You can override default configuration via environment variables:

| Variable | Description | Default |
| -------- | ----------- | ------- |
| `SERVER_PORT` | The port the application binds to. | `3001` |
| `TESSERACT_DATA_PATH` | Path to the `tessdata` directory. | `/usr/share/tessdata` (Alpine default in Docker) |
| `TESSERACT_LANGUAGE` | Default language(s) for OCR (e.g., `eng`, `eng+fra`). | `eng` |

## Project Structure

- `src/main/kotlin/com/ocr/OcrApplication.kt` - Spring Boot main application class.
- `src/main/kotlin/com/ocr/OcrController.kt` - The unified REST controller exposing `/ocr` endpoints.
- `src/main/kotlin/com/ocr/TesseractService.kt` - The core wrapper around Tesseract (Tess4J), aggressively configured for fast OCR processing.
- `Dockerfile` - A multi-stage Docker build resulting in a clean Alpine Linux image with Tesseract pre-installed.
