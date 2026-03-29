# OCR Service

A clean, production-ready OCR microservice written in Python. Six interchangeable OCR engines behind a single REST API — swap engines per-request with no code changes.

## Engines

| Key | Engine | Notes |
|-----|--------|-------|
| `tesseract` | Tesseract 4/5 | Default. Fast, fully local, no model download. |
| `ollama` | Ollama vision model | Requires a running Ollama instance. Default model: `qwen2.5vl:7b`. |
| `surya` | Surya OCR | Transformer-based, 90+ languages. Models downloaded on first use. |
| `easyocr` | EasyOCR | Deep-learning OCR, 80+ languages. |
| `paddle` | PaddleOCR | High accuracy, strong on complex layouts. |
| `doctr` | DocTR (Mindee) | PyTorch document recognition pipeline. |

## Requirements

- **Python 3.11**
- **[uv](https://docs.astral.sh/uv/)** — install with `curl -LsSf https://astral.sh/uv/install.sh | sh`
- **Tesseract** — install via your package manager (`brew install tesseract` / `apt install tesseract-ocr`)
- **Ollama** (optional) — only needed for the `ollama` engine or document insights

## Local development

```bash
# 1. Clone / enter the project
cd ocr-service

# 2. Install all dependencies (creates .venv automatically)
uv sync

# 3. Run the server
uv run uvicorn app.main:app --host 0.0.0.0 --port 3001 --reload
```

The API is now available at `http://localhost:3001`.
Interactive docs: `http://localhost:3001/docs`

> **macOS note:** `uv sync` picks up the standard PyTorch wheel (MPS/CPU). No extra steps needed.
> **Linux note:** uv automatically selects the compact CPU-only PyTorch wheel via `[tool.uv.sources]`.

## Docker

```bash
# Build
docker build -t ocr-service .

# Run
docker run -p 3001:3001 --add-host=host.docker.internal:host-gateway ocr-service

# Or with docker-compose
docker compose up --build
```

The Docker image uses CPU-only PyTorch — no NVIDIA drivers required. Ollama running on your host machine is reachable via `host.docker.internal`.

## API

### Health check

```
GET /health
```

```json
{ "status": "ok" }
```

### OCR — multipart upload

```bash
curl -X POST "http://localhost:3001/ocr?engine=tesseract" \
     -F "file=@/path/to/document.pdf"
```

### OCR — base64 JSON

```bash
curl -X POST "http://localhost:3001/ocr?engine=surya" \
     -H "Content-Type: application/json" \
     -d '{"base64": "<base64-encoded-bytes>", "mimeType": "image/png"}'
```

### Query parameters

| Parameter | Type | Default | Description |
|-----------|------|---------|-------------|
| `engine` | string | `tesseract` | OCR engine to use (see table above). |
| `model` | string | `qwen2.5vl:7b` | Ollama model override (Ollama engine only). |
| `insights` | bool | `false` | Run Ollama-powered document analysis on the extracted text. |

### Response

```json
{
  "success": true,
  "engine": "tesseract",
  "text": "Extracted text here…",
  "pages": 3,
  "processingTimeMs": 1240,
  "insights": {
    "category": "FINANCIAL",
    "summary": "A utility bill for …",
    "keyDetails": ["Amount due: $120", "Due date: …", "Account: …"]
  },
  "error": null
}
```

`insights` is `null` unless `?insights=true` is passed and Ollama is reachable.

## Environment variables

| Variable | Default | Description |
|----------|---------|-------------|
| `SERVER_PORT` | `3001` | Port the server listens on. |
| `TESSERACT_DATA_PATH` | *(auto)* | Path to tessdata directory. Auto-detected locally; set explicitly in Docker. |
| `TESSERACT_LANGUAGE` | `eng` | Tesseract language code(s), e.g. `eng+fra`. |
| `TESSERACT_PAGE_SEG_MODE` | `3` | Tesseract PSM mode. |
| `OLLAMA_BASE_URL` | `http://localhost:11434` | Ollama server URL. |
| `OLLAMA_OCR_MODEL` | `qwen2.5vl:7b` | Vision model used by the Ollama engine. |
| `OLLAMA_INSIGHTS_MODEL` | `llama3.2` | Text model used for document insights. |
| `MAX_UPLOAD_BYTES` | `209715200` | Maximum upload size (200 MB). |

## Project structure

```
ocr-service/
├── app/
│   ├── main.py              # FastAPI app & routes
│   ├── config.py            # Environment variable config
│   ├── models.py            # Pydantic request/response models
│   ├── service.py           # OcrService — orchestration & PDF rendering
│   ├── engines/
│   │   ├── base.py          # OcrEngine abstract base class
│   │   ├── tesseract.py     # Tesseract engine
│   │   ├── ollama.py        # Ollama vision engine
│   │   ├── surya.py         # Surya OCR engine
│   │   ├── easyocr_engine.py
│   │   ├── paddle_engine.py
│   │   └── doctr_engine.py
│   └── insight/
│       ├── analyzer.py      # InsightAnalyzer (Ollama text model)
│       └── (models in app/models.py)
├── Dockerfile
├── docker-compose.yml
├── pyproject.toml           # Dependencies + uv config
├── .python-version          # Pins Python 3.11 for uv
└── .gitignore
```

## Adding a new engine

1. Create `app/engines/my_engine.py` extending `OcrEngine`.
2. Implement `image_to_text(self, image: Image.Image) -> str`.
3. Add a `case "myengine"` branch in `app/service.py → _resolve_engine`.
4. Add the package to `pyproject.toml` dependencies and run `uv sync`.
