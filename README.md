# Hybrid OCR Microservice — IRCC Document Extraction

A single-purpose Kotlin + Spring Boot microservice that turns scanned IRCC
documents (passports, bank statements, employment letters, LOAs, PALs, LMIAs,
GICs, ...) into strongly-typed JSON, with per-field confidence and warnings.

There is **one engine** — the hybrid pipeline. No provider switching, no
A/B knobs. Five stages, each with a clear contract:

```
┌──────────────────────────────────────────────────────────────────────────┐
│ Stage 1  render → tesseract OCR → (conditional) MRZ specialist           │
│ Stage 2  classify (rules first, fast-LLM fallback)                       │
│ Stage 3  per-type prompt + JSON-schema lookup (static, no LLM)           │
│ Stage 4  powerful vision LLM extracts structured JSON                    │
│ Stage 5  validate / reconcile / score confidence                         │
└──────────────────────────────────────────────────────────────────────────┘
```

See `docs/OCR_DESIGN.md` for the long-form design doc (document inventory,
per-type JSON schemas, and the trade-off analysis behind picking this
architecture over the alternatives).

## Why hybrid

- **Tesseract first** because raw OCR is cheap and produces useful evidence —
  the vision LLM gets both the image AND the noisy text, which catches
  hallucination on critical fields.
- **Rules-first classifier** so the LLM classifier only runs on ambiguous docs.
  Most inputs (passport with MRZ, PAL with the literal phrase "Provincial
  Attestation Letter", LMIA with "LMIA" + a NOC code) classify deterministically
  in <1 ms.
- **MRZ specialist for passports** because ICAO 9303 check digits make MRZ
  fields mathematically verifiable — no LLM can beat that for `documentNumber`,
  `dateOfBirth`, `dateOfExpiry`, `nationality`. They become ground truth.
- **Per-type prompts + JSON schemas, static lookup** so what we send to the
  vision model is reviewable and version-controlled. We deliberately do not
  ask an LLM to "write a prompt" — that creates a prompt-injection vector
  through the OCR text.
- **Cross-validation in Stage 5** — vision values found verbatim in the
  Tesseract text get confidence 0.95, MRZ-verified values get 1.0, unverified
  values get 0.85, suspicious values get flagged as warnings.

## Requirements

- Java 21
- Tesseract OCR + `eng`, `fra` traineddata
- An Ollama endpoint reachable from the service. Default points at
  `https://ollama.flutra.ca`. Override via `OLLAMA_BASE_URL`.

## Run locally

```bash
gradle bootRun
```

The API starts on `http://localhost:3001`. Swagger UI at
`http://localhost:3001/swagger-ui.html`.

## Run in Docker

```bash
docker build -t ocr-microservice .
docker run -p 3001:3001 \
  -e OLLAMA_BASE_URL=https://ollama.flutra.ca \
  ocr-microservice
```

## Run with Docker Compose

```bash
docker compose up --build           # build + start
docker compose logs -f ocr          # tail logs
docker compose down                 # stop
```

Override any setting by exporting it before `docker compose up`, or by
creating a `.env` file next to `docker-compose.yml` — for example to point
at a local Ollama instead of the public one:

```
OLLAMA_BASE_URL=http://host.docker.internal:11434
OLLAMA_VISION_MODEL=qwen2.5vl:3b
```

## API

### `POST /ocr/extract`

`multipart/form-data`:

| Param | Required | Description |
| --- | --- | --- |
| `file` | yes | PDF or image (PNG, JPG, TIFF, BMP, WEBP, GIF) |
| `expectedType` | no | Document type hint, e.g. `passport`, `bank_statement`, `lmia_letter`. Skips classification |
| `visionModel` | no | Override the vision model (must be in `ocr.ollama.allowed-models`) |
| `includeRawText` | no | Set `true` to include the raw Tesseract text in the response (debugging — contains PII) |

```bash
curl -X POST http://localhost:3001/ocr/extract \
  -F "file=@/path/to/passport.pdf" \
  -F "expectedType=passport"
```

Response (truncated):

```json
{
  "success": true,
  "engine": "hybrid",
  "engineVersion": "hybrid-1.0.0",
  "documentType": "passport",
  "documentTypeConfidence": 1.0,
  "classifierUsed": "mrz_specialist",
  "languageDetected": ["eng+fra"],
  "pages": 1,
  "processingTimeMs": 9612,
  "stageTimings": {
    "render": 81, "ocr": 2418, "mrz": 620,
    "classify": 4, "template": 1, "vision": 4812, "validate": 6
  },
  "data": {
    "documentNumber": "AB1234567",
    "issuingCountry": "CAN",
    "surname": "SMITH",
    "givenNames": "JOHN MICHAEL",
    "nationality": "CAN",
    "dateOfBirth": "1985-04-15",
    "sex": "M",
    "dateOfExpiry": "2030-01-01",
    "placeOfBirth": "TORONTO, ON, CANADA",
    "placeOfIssue": "OTTAWA",
    "dateOfIssue": "2020-01-02",
    "issuingAuthority": "GOVERNMENT OF CANADA"
  },
  "fieldConfidence": {
    "documentNumber": 1.0, "dateOfBirth": 1.0, "dateOfExpiry": 1.0,
    "placeOfBirth": 0.95, "issuingAuthority": 0.85
  },
  "warnings": [],
  "rawText": null
}
```

### `GET /ocr/health` and `GET /health`

`{ "status": "ok" }`.

## Supported document types

`passport`, `national_id`, `bank_statement`, `pay_stub`, `employment_letter`,
`job_offer_letter`, `letter_of_acceptance`, `provincial_attestation_letter`,
`lmia_letter`, `gic_certificate`, `birth_certificate`, `marriage_certificate`,
`education_credential`. Anything else falls back to a generic schema or returns
`UNSUPPORTED_DOCUMENT_TYPE`.

## Reading the logs

Every line is prefixed with `[stageN.subsystem]` and uses `BEGIN` / `DONE`
markers so a request reads like:

```
[stage1.render]    rasterising pdf pages=1 render=1 dpi=300.00
[stage1.ocr]       BEGIN pages=1 lang=eng+fra
[stage1.ocr]       page n=1/1 size=2480x3508 chars=1382 took_ms=812
[stage1.ocr]       DONE total_chars=1382 took_ms=812
[stage1.mrz]       BEGIN pages=1
[stage1.mrz]       DONE found=true page=1 check_digits_valid=true took_ms=620
[stage2.classify]  BEGIN
[stage2.classify]  DONE via=mrz_specialist doc=passport conf=1.00 check_digits_valid=true
[stage3.template]  template selected doc=passport prompt_chars=1240 schema_chars=672
[stage4.vision]    BEGIN model=qwen2.5vl:7b imgs=1 prompt_chars=1240
[stage4.vision]    DONE fields=12 took_ms=4812
[stage5.validate]  BEGIN doc=passport fields=12
[stage5.validate]  mrz xcheck no conflicts
[stage5.validate]  DONE fields=12 warnings=0 low_conf=0
[pipeline]         DONE doc=passport via=mrz_specialist conf=1.00 warnings=0 total_ms=9612
```

## Configuration

All settings are bound to `com.ocr.config.OcrProperties` and can be overridden
via environment variable.

| Env var | Default | What it controls |
| --- | --- | --- |
| `SERVER_PORT` | `3001` | HTTP port |
| `TESSERACT_DATA_PATH` | `/usr/share/tessdata` | Tesseract `tessdata` folder |
| `TESSERACT_LANGUAGE` | `eng+fra` | OCR language(s) |
| `TESSERACT_PDF_DPI` | `300` | DPI used when rasterising PDF pages |
| `OCR_MAX_PAGES` | `30` | Hard cap on PDF pages per request |
| `OLLAMA_BASE_URL` | `https://ollama.flutra.ca` | Ollama endpoint |
| `OLLAMA_CLASSIFIER_MODEL` | `llama3.2:3b` | Stage 2 fallback model |
| `OLLAMA_VISION_MODEL` | `qwen2.5vl:7b` | Stage 4 vision model |
| `OLLAMA_TIMEOUT_SECONDS` | `180` | Per-call Ollama timeout |
| `OLLAMA_ALLOWED_MODELS` | `llama3.2:3b,qwen2.5vl:7b,qwen2.5vl:3b` | Allowlist of model strings — set empty to disable enforcement (dev only) |
| `OCR_RULES_MIN_CONFIDENCE` | `0.55` | Below this, rules-classifier escalates to LLM |
| `OCR_RULES_MIN_MARGIN` | `0.15` | Margin top-vs-second below which rules escalate |
| `OCR_INCLUDE_RAW_TEXT` | `false` | Default `includeRawText` when caller omits it |
| `MAX_UPLOAD_SIZE` | `200MB` | Multipart upload cap |

## Security notes

- ⚠️ The default `OLLAMA_BASE_URL` points at a public domain. PII (passport
  data, bank statements) leaves the local network on every Stage 2/4 call.
  Confirm TLS, a DPA, and IP allowlisting before production use.
- ⚠️ CORS is currently `*` — restrict `allowedOriginPatterns` in `WebConfig`
  to your front-end origin before exposing publicly.
- ⚠️ There is no authentication on `/ocr/extract`. Add a service-to-service
  API key or JWT before deploying.
- ⚠️ There is no rate limiting; an attacker can exhaust the container with
  large PDFs. Add Bucket4j or Spring Cloud Gateway in front.
- The engine never echoes Ollama's raw error responses back to API callers;
  it logs server-side and returns a generic error code.
- Filenames are sanitised before logging (path components stripped, truncated).
  Default response does not include `rawText` so passport numbers don't end
  up in downstream logs by accident.
