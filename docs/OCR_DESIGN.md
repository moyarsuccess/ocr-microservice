# OCR Microservice — IRCC Document Extraction Design

> Scope: structured OCR for documents submitted in IRCC visitor/study/work permit
> applications. Goal is to take a scanned PDF or image of a source document and
> return a strongly-typed JSON object that downstream services can use to
> pre-fill IRCC application PDFs (IMM 5257, IMM 1294, IMM 1295, IMM 5645, etc.).
>
> Stack assumption: Kotlin + Spring Boot 3.4 (already scaffolded). English + French.
> Containerised. Open-source, cloud APIs, and Vision-LLMs are all on the table.

---

## 1. What needs to be OCR'd

IRCC application packages contain three classes of PDFs/images. Only the
*source documents* (class B) are useful OCR targets — IRCC's own fillable forms
(class A) should be parsed via AcroForm field extraction, not OCR.

### Class A — IRCC fillable forms (skip OCR, use PDFBox AcroForm)

| Form | Purpose |
|---|---|
| IMM 5257 | Visitor visa (TRV) application |
| IMM 5645 | Family information |
| IMM 1294 | Study permit application |
| IMM 1295 | Work permit application |
| IMM 5707 | Family information (TR) |
| IMM 5709 | Application to change conditions / extend stay |
| IMM 5476 | Use of a representative |

PDFBox can read these directly via `PDDocument.documentCatalog.acroForm.fields`.
Don't waste OCR on them.

### Class B — Source documents that should be OCR'd (priority order)

Tier 1 — high value, high volume, structured output mandatory:

1. **Passport bio page** (TD3 MRZ + visual zone). The single most-used document
   across all three permit types.
2. **National ID card** (issuing-country-specific).
3. **Bank statement** (multi-page, transactional). Used to demonstrate funds.
4. **Pay stub / salary slip** (employer-specific).
5. **Employment letter** (free-text but with predictable fields).
6. **Letter of Acceptance (LOA)** from a Designated Learning Institution — study
   permit only.
7. **Provincial Attestation Letter (PAL/TAL)** — mandatory for most study
   permits since Jan 2024.
8. **LMIA decision letter / Offer of Employment number (A-number)** — work
   permit, LMIA stream.
9. **Job offer letter / employment contract** — work permit.
10. **GIC (Guaranteed Investment Certificate)** — study permit, SDS stream.

Tier 2 — important, lower volume:

11. **Birth certificate**.
12. **Marriage certificate**.
13. **Education credentials** — diplomas, degree certificates, transcripts.
14. **Tax return / Notice of Assessment / T4** (or country equivalent).
15. **Property deed / land title**.
16. **Business registration / incorporation certificate**.
17. **Police clearance certificate** (PCC).
18. **Old passport pages with visa stamps** (travel history).
19. **Custodianship declaration** — minor study applicants.
20. **Quebec CAQ** (work) / **CAQ for studies** (study).

Tier 3 — pass-through, minimal structuring:

21. **Flight itinerary / e-ticket**.
22. **Hotel booking confirmation**.
23. **Invitation letter** from Canadian host.
24. **Medical exam confirmation (eMedical IMM 1017)**.

Out of scope: passport photos (compliance check is a different service — face
detection, ICAO photo standard, not OCR).

---

## 2. JSON response format

### 2.1 Envelope (all document types)

Every OCR response — regardless of document type — wraps a typed `data` object
in a common envelope:

```json
{
  "success": true,
  "engine": "vision_llm | tesseract | tesseract+llm | document_ai | mrz_specialist | hybrid",
  "engineVersion": "qwen2.5vl:7b",
  "documentType": "passport",
  "documentTypeConfidence": 0.98,
  "languageDetected": ["eng"],
  "processingTimeMs": 1840,
  "pages": 1,
  "data": { /* document-type-specific payload, see below */ },
  "fieldConfidence": { "passportNumber": 0.99, "givenNames": 0.94, ... },
  "warnings": [ "MRZ check digit mismatch on birth date" ],
  "rawText": "optional, full extracted text for debugging",
  "error": null
}
```

Design notes:
- `documentType` is detected, not user-supplied — but the caller MAY pass an
  expected type as a hint to constrain the model.
- `fieldConfidence` keys mirror `data` keys. The downstream IRCC form-filler
  can decide whether to ask the human to verify each field.
- `rawText` is opt-in via a query flag — by default we don't return it (it's
  PII and bloats the payload).
- Dates are always ISO 8601 (`YYYY-MM-DD`). Country codes are ISO 3166-1
  alpha-3 (matches the MRZ standard). Genders use MRZ codes `M`/`F`/`X`.

### 2.2 Per-document-type payloads

Only the high-value Tier-1 schemas are spelled out below. Tier 2/3 follow the
same pattern.

#### 2.2.1 `passport`

```json
{
  "documentType": "passport",
  "data": {
    "mrz": {
      "line1": "P<CANSMITH<<JOHN<MICHAEL<<<<<<<<<<<<<<<<<<<<",
      "line2": "AB1234567<2CAN8504159M3001011<<<<<<<<<<<<<<00",
      "format": "TD3",
      "checkDigitsValid": true
    },
    "documentNumber": "AB1234567",
    "documentType3LetterCode": "P",
    "issuingCountry": "CAN",
    "surname": "SMITH",
    "givenNames": "JOHN MICHAEL",
    "nationality": "CAN",
    "dateOfBirth": "1985-04-15",
    "sex": "M",
    "dateOfExpiry": "2030-01-01",
    "personalNumber": null,
    "placeOfBirth": "TORONTO, ON, CANADA",
    "placeOfIssue": "OTTAWA",
    "dateOfIssue": "2020-01-02",
    "issuingAuthority": "GOVERNMENT OF CANADA"
  }
}
```

#### 2.2.2 `national_id`

```json
{
  "documentType": "national_id",
  "data": {
    "issuingCountry": "IRN",
    "idNumber": "1234567890",
    "surname": "MORADYAR",
    "givenNames": "MOHAMMAD",
    "fatherName": "ALI",
    "motherName": "FATEMEH",
    "dateOfBirth": "1990-03-21",
    "placeOfBirth": "TEHRAN",
    "sex": "M",
    "dateOfIssue": "2018-06-10",
    "dateOfExpiry": "2028-06-09"
  }
}
```

#### 2.2.3 `bank_statement`

```json
{
  "documentType": "bank_statement",
  "data": {
    "bankName": "Royal Bank of Canada",
    "branchAddress": "200 Bay St, Toronto ON",
    "accountHolder": {
      "name": "JOHN M SMITH",
      "address": "123 Main St, Toronto ON M5V 1A1"
    },
    "accountNumber": "****1234",
    "accountType": "chequing",
    "currency": "CAD",
    "statementPeriod": { "from": "2026-01-01", "to": "2026-01-31" },
    "openingBalance": 12450.22,
    "closingBalance": 18790.10,
    "averageBalance": 14200.55,
    "totalDeposits": 9000.00,
    "totalWithdrawals": 2660.12,
    "transactions": [
      {
        "date": "2026-01-03",
        "description": "PAYROLL DEPOSIT - ACME CORP",
        "amount": 3000.00,
        "type": "credit",
        "balanceAfter": 15450.22
      }
    ]
  }
}
```

Note: transactions are optional — for IRCC purposes, the balances and period
are usually enough. Per-transaction extraction multiplies token cost on vision
LLMs.

#### 2.2.4 `pay_stub`

```json
{
  "documentType": "pay_stub",
  "data": {
    "employerName": "ACME CORP",
    "employerAddress": "1 Industrial Way, Toronto ON",
    "employeeName": "JOHN M SMITH",
    "employeeId": "E12345",
    "payPeriod": { "from": "2026-01-01", "to": "2026-01-15" },
    "payDate": "2026-01-20",
    "currency": "CAD",
    "grossEarnings": 3500.00,
    "netPay": 2640.55,
    "taxesWithheld": 620.00,
    "deductions": [
      { "label": "CPP", "amount": 180.00 },
      { "label": "EI", "amount": 59.45 }
    ],
    "ytdGross": 7000.00,
    "ytdNet": 5281.10
  }
}
```

#### 2.2.5 `employment_letter`

```json
{
  "documentType": "employment_letter",
  "data": {
    "employerName": "ACME CORP",
    "employerAddress": "1 Industrial Way, Toronto ON",
    "employerPhone": "+1-416-555-0100",
    "employerEmail": "hr@acme.example",
    "employerWebsite": "acme.example",
    "letterDate": "2026-05-01",
    "issuedBy": { "name": "Jane Doe", "title": "HR Director" },
    "employeeName": "JOHN M SMITH",
    "jobTitle": "Senior Software Engineer",
    "employmentType": "full_time",
    "startDate": "2022-03-01",
    "endDate": null,
    "salaryAmount": 95000.00,
    "salaryCurrency": "CAD",
    "salaryPeriod": "yearly",
    "weeklyHours": 40,
    "vacationDaysPerYear": 20,
    "expectedReturnDate": "2026-08-15",
    "purposeOfTravel": "VACATION",
    "isAddressedToIRCC": true
  }
}
```

#### 2.2.6 `letter_of_acceptance`

```json
{
  "documentType": "letter_of_acceptance",
  "data": {
    "dliNumber": "O19395677482",
    "institutionName": "University of Toronto",
    "institutionAddress": "27 King's College Cir, Toronto ON",
    "studentName": "JOHN M SMITH",
    "studentDateOfBirth": "2005-08-12",
    "programName": "Bachelor of Computer Science",
    "programLevel": "bachelor",
    "programDuration": "4 years",
    "startDate": "2026-09-04",
    "endDate": "2030-04-30",
    "tuitionPerYearAmount": 58000.00,
    "tuitionCurrency": "CAD",
    "scholarshipAmount": 5000.00,
    "isFullTime": true,
    "isCoOp": false,
    "letterDate": "2026-04-12",
    "letterReferenceNumber": "LOA-2026-12345"
  }
}
```

#### 2.2.7 `provincial_attestation_letter` (PAL/TAL)

```json
{
  "documentType": "provincial_attestation_letter",
  "data": {
    "province": "ON",
    "attestationNumber": "PAL-ON-2026-0099887",
    "issuedTo": "JOHN M SMITH",
    "studentDateOfBirth": "2005-08-12",
    "dliNumber": "O19395677482",
    "institutionName": "University of Toronto",
    "issueDate": "2026-04-20",
    "expiryDate": "2027-01-21"
  }
}
```

#### 2.2.8 `lmia_letter`

```json
{
  "documentType": "lmia_letter",
  "data": {
    "lmiaNumber": "A1234567",
    "employerName": "ACME CORP",
    "employerLegalName": "ACME Corporation Ltd.",
    "businessAddress": "1 Industrial Way, Toronto ON",
    "naicsCode": "541510",
    "nocCode": "21231",
    "jobTitle": "Software Engineer",
    "positionsApproved": 1,
    "wageAmount": 45.67,
    "wageCurrency": "CAD",
    "wageUnit": "hour",
    "isHighWage": true,
    "decisionDate": "2026-04-30",
    "validUntil": "2026-10-30",
    "decisionType": "positive"
  }
}
```

#### 2.2.9 `job_offer_letter`

(Same envelope as `employment_letter` plus `nocCode`, `lmiaNumber` if applicable,
`workLocation`, `expectedStartDate`.)

#### 2.2.10 `gic_certificate`

```json
{
  "documentType": "gic_certificate",
  "data": {
    "bankName": "Scotiabank",
    "certificateNumber": "GIC-987654321",
    "studentName": "JOHN M SMITH",
    "studentDateOfBirth": "2005-08-12",
    "investmentAmount": 20635.00,
    "currency": "CAD",
    "issueDate": "2026-05-01",
    "maturityDate": "2027-05-01",
    "monthlyDisbursement": 1719.58,
    "programOfStudy": "Bachelor of Computer Science"
  }
}
```

### 2.3 Error envelope

```json
{
  "success": false,
  "engine": "vision_llm",
  "documentType": "unknown",
  "processingTimeMs": 312,
  "data": null,
  "error": {
    "code": "UNSUPPORTED_DOCUMENT_TYPE | LOW_QUALITY_IMAGE | MRZ_PARSE_FAILED | UPSTREAM_TIMEOUT | INTERNAL",
    "message": "Could not detect a passport MRZ on any page",
    "retryable": false
  }
}
```

---

## 3. Implementation approaches

Six concrete strategies, each implementable as an `OcrProvider` (or richer
`StructuredExtractor`) Spring bean. Pick one default and keep the others
behind a `provider` query param so you can A/B and benchmark.

### Approach A — Tesseract → LLM structurer (cheap, offline)

Pipeline: PDFBox renders pages → Tesseract OCR raw text → small local LLM
(or OpenAI/Anthropic) prompted with the target JSON schema turns text into
structured JSON.

- Pros: Fully offline if LLM is local. Cheapest cloud option. Already half
  built — your `TesseractService` produces the raw text.
- Cons: Tesseract is poor on real-world phone photos, glossy IDs, and complex
  layouts (bank statements with columns). Two-stage failure modes compound.
- Kotlin: existing Tess4J + LangChain4j. Add a `StructuredExtractor` bean that
  takes raw text + a schema and returns JSON via constrained decoding
  (Ollama supports `format: "json"` and JSON schema).
- Expected accuracy on passports: ~75–85% field-level. On bank statements:
  ~50–70%.

### Approach B — Vision LLM direct extraction (current Ollama path, upgraded)

Pipeline: render page → send image + JSON schema in the prompt → model emits
JSON directly.

- Pros: Strong on real-world photos, glare, rotations. Single network round
  trip per page. Already wired (`OllamaOcrService` with `qwen2.5vl:7b`).
- Cons: Latency 1–10s per page. Quality depends heavily on model — `qwen2.5vl`
  is decent but for passports you'll get noticeably better extraction from
  `gpt-4o`, `claude-sonnet-4-5`, or `gemini-2.5-flash`. Hallucination risk on
  low-quality input — always cross-validate against MRZ check digits when the
  doc is a passport.
- Kotlin: extend `OllamaOcrService` to pass `format` (JSON schema) per
  document type. Add a parallel `OpenAiVisionService` / `AnthropicVisionService`
  / `GeminiVisionService` (all have official Java SDKs).
- Expected accuracy on passports: 92–98% with frontier models, 85–92% with
  `qwen2.5vl:7b`. On bank statements: 80–90%.

### Approach C — Cloud Document AI (highest accuracy on IDs)

Three vendors with prebuilt parsers tuned for exactly these document classes:

- **Google Document AI** — `Identity Document` processor parses passports,
  national IDs out of the box and returns MRZ-validated fields. Also has
  `Bank Statement`, `Pay Stub`, `Invoice` processors. SDK:
  `com.google.cloud:google-cloud-document-ai`.
- **AWS Textract** — `AnalyzeID` for passport/driver's licence, `AnalyzeExpense`
  for receipts, `AnalyzeDocument` (with `QUERIES`) for arbitrary docs. SDK:
  `software.amazon.awssdk:textract`.
- **Azure AI Document Intelligence** — `prebuilt-idDocument`,
  `prebuilt-bankStatement.us`, `prebuilt-payStub.us`,
  `prebuilt-receipt`. SDK: `com.azure:azure-ai-formrecognizer`.

- Pros: Highest accuracy on the exact document classes IRCC cares about.
  Built-in MRZ parsing and check-digit validation. SLAs.
- Cons: Cost (~$0.005–$0.05 per page depending on vendor and processor).
  PII leaves your network — needs a DPA with the vendor and may conflict with
  IRCC privacy posture. Per-vendor lock-in. Coverage gaps on niche docs
  (Iranian shenasnameh, Indian Aadhaar, etc.).
- Kotlin: thin adapter classes that map vendor response → your envelope.
- Expected accuracy on passports: 97–99.5%. On bank statements: 90–95%
  (better on US/EU banks than emerging markets).

### Approach D — MRZ specialist + Vision LLM hybrid (recommended default for passports)

Run a deterministic MRZ parser first; if it succeeds, MRZ fields are ground
truth (check digits prove correctness). Use a vision model only for the
*visual zone* fields not in the MRZ (place of birth, place of issue,
issuing authority, photo crop).

- Pros: MRZ extraction becomes deterministic and verifiable — no
  hallucination possible on the most critical fields (document number, name,
  DOB, expiry). Reduces vision-LLM token use ~70% per passport.
- Cons: Only applies to passport-class docs. Adds a dependency.
- Kotlin: there is no first-party MRZ Kotlin library, but:
  - JMRTD (`org.jmrtd:jmrtd:0.7.42`) parses MRZ strings if you've already
    located them. You still need to OCR the bottom of the page first — feed
    the bottom 1/3 strip into Tesseract with `PSM 7` and the MRZ char
    whitelist (`A-Z0-9<`).
  - Alternative: shell out to PassportEye (Python) via a sidecar container.
- Expected accuracy on passport MRZ fields: 99%+. Visual zone via LLM: 95%+.

### Approach E — Open-source modern OCR (PaddleOCR / docTR / Surya) via Python sidecar

Tesseract is old. Modern open-source OCR engines (PP-OCRv4, docTR, Surya) are
substantially better on real-world inputs. None has good JVM bindings, so the
sane integration is a second container that exposes HTTP and lives behind
your Kotlin service.

- Pros: Best open-source raw OCR quality, on par with cloud APIs for text
  detection. Multilingual out of the box. Stays self-hosted.
- Cons: Two-container deploy. Heavier images (CUDA optional). Still need an
  LLM/regex layer to turn text into JSON.
- Kotlin: add a `PaddleOcrClient` bean that POSTs the image to the sidecar
  and gets back lines + bounding boxes; reuse Approach A's structurer.
- Expected accuracy: 90–97% raw text on passports, 85–92% on bank statements.

### Approach F — Fine-tuned document model (LayoutLMv3 / Donut / Pix2Struct)

For the highest open-source accuracy on form-like docs (Letters of Acceptance,
PAL, LMIA letters), fine-tune a small transformer (Donut is encoder-decoder,
outputs JSON natively; LayoutLMv3 needs labelled bounding boxes) on a few
hundred labelled examples per document type.

- Pros: Best accuracy ceiling for narrow document classes. Offline.
- Cons: Training data needed (manual labelling). Per-doc-class model. ML ops
  overhead (model registry, GPU at inference).
- Kotlin: same sidecar pattern as Approach E.

### Recommendation

A pragmatic mix for an IRCC microservice that has to ship soon:

| Document class | Default approach |
|---|---|
| Passport | **D** (MRZ specialist) + B (vision LLM for VIZ fields) |
| National ID, birth/marriage certificate | **C** Google Document AI (Identity Document) — or B if PII must stay in-network |
| Bank statement, pay stub, tax return | **C** Document AI (Bank Statement / Pay Stub processors) — or B with a strong vision model |
| LOA / PAL / LMIA / employment letter | **B** vision LLM direct extraction with per-doc-type JSON schema |
| Diplomas / property deeds / misc | **B** vision LLM, schema-light |
| Flight / hotel / invitation letters | **A** Tesseract + LLM, low-stakes |

Keep the `provider` query param so a single endpoint can route by document
type or be overridden in benchmarks.

---

## 4. Suggested service shape (Kotlin)

Minimal change to the existing layout:

```
com.ocr/
  OcrController.kt              -> exposes /ocr/extract?expectedType=…&provider=…
  OcrProvider.kt                -> existing interface, returns raw text
  StructuredExtractor.kt        -> NEW interface, returns OcrEnvelope<T>
  envelope/
    OcrEnvelope.kt              -> generic envelope data class
    DocumentType.kt             -> enum: PASSPORT, BANK_STATEMENT, …
    schemas/                    -> data classes per doc type (PassportData, …)
  providers/
    TesseractRawProvider.kt     -> existing
    OllamaVisionProvider.kt     -> existing, refactor to emit envelope
    GoogleDocumentAiProvider.kt -> NEW
    AwsTextractProvider.kt      -> NEW
    AzureDocIntelProvider.kt    -> NEW
    MrzSpecialistProvider.kt    -> NEW (passport-only)
    PaddleOcrProvider.kt        -> NEW (HTTP client to sidecar)
  routing/
    DocumentTypeDetector.kt     -> first-pass classifier (vision LLM with a short prompt)
    ProviderRouter.kt           -> picks the right provider given detected type
  post/
    MrzValidator.kt             -> ICAO 9303 check-digit math
    DateNormalizer.kt           -> normalize all dates to ISO-8601
    ConfidenceMerger.kt         -> merge per-field confidence from multiple sources
```

Endpoint contract:

```
POST /ocr/extract
  multipart: file (required), expectedType (optional), provider (optional),
             includeRawText=false, language=eng+fra
  -> OcrEnvelope<T>  (typed JSON, T depends on detected/expected document type)
```

Keep `/ocr` (existing raw-text endpoint) for backward compatibility.

---

## 5. Security / privacy notes on the current code

Quick observations from reading the existing service — flagging now so they're
in the design rather than discovered later:

- ⚠️ `OcrController.processOcr` returns `e.message` directly to the client.
  This can leak internal paths, JNI errors, or stack traces. Map to a fixed
  error code/message before responding.
- ⚠️ `OllamaOcrService.pullModel` is reachable from the request `model`
  parameter — any caller can trigger an arbitrary model pull from the Ollama
  registry (disk-fill + bandwidth abuse vector). Allowlist permitted model
  names and require auth on the endpoint.
- ⚠️ No file content-type sniffing — extension check on the filename is
  trivially bypassed. Use magic-byte detection (e.g. `Apache Tika`) before
  routing to PDF vs. image path.
- ⚠️ No authn/authz on `/ocr`. For PII (passports, bank statements) this
  needs at minimum a service-to-service API key plus mTLS or a JWT.
- ⚠️ No rate limiting. A single attacker uploading 200MB PDFs can exhaust
  the container.
- ⚠️ Logs include `file.originalFilename`. If filenames contain applicant
  names, that's PII in logs. Hash or drop.
- ⚠️ No PII redaction on `rawText`. If the response is logged or cached,
  passport numbers/MRZ end up in logs.
- ⚠️ Docker `HEALTHCHECK` hits `/health` but controller exposes `/ocr/health`
  — health check is broken today.
- ⚠️ TLS / pinning to upstream (Ollama, cloud Document AI) is not configured;
  in production set `OLLAMA_BASE_URL` to an HTTPS endpoint and pin the cert
  if Ollama is remote.

None are blockers for the design, but worth tracking.

---

## 6. Benchmark plan (so "find the best performant way" is data-driven)

To compare approaches honestly, build a small evaluation harness:

1. **Eval set** — 30–50 documents per Tier-1 class, labelled by hand. Mix:
   clean scans, phone photos, glare, rotated, low light, watermarked.
   Don't use real applicant data — use sample passports / public templates.
2. **Metrics**:
   - Field-level exact match (per JSON key).
   - Field-level fuzzy match (Levenshtein ratio ≥ 0.9 for names/addresses).
   - End-to-end latency p50 / p95 / p99.
   - $/document (for cloud providers).
   - "Critical-field" accuracy — passport number, DOB, expiry. These are
     the ones that, if wrong, break the IRCC form. They should be ≥ 99%.
3. **Harness**: a JUnit test that loads each labelled doc, runs every
   provider, dumps results to CSV. Wire it as a Gradle `benchmark` task.

Without this you're guessing which approach is "best".
