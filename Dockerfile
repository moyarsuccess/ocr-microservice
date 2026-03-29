# ──────────────────────────────────────────────────────────────────────────────
# OCR Service — single-stage Python 3.11 image
# All six OCR engines included, no GPU required.
# ──────────────────────────────────────────────────────────────────────────────
FROM python:3.11-slim

ARG DEBIAN_FRONTEND=noninteractive

# ── System dependencies ────────────────────────────────────────────────────────
RUN apt-get update && apt-get install -y --no-install-recommends \
        # Tesseract OCR + English language data
        tesseract-ocr \
        tesseract-ocr-eng \
        # OpenCV / image libraries (EasyOCR, DocTR, PaddleOCR)
        libgl1 \
        libglib2.0-0 \
        libsm6 \
        libxrender1 \
        libxext6 \
        libgomp1 \
        # Build tools (some packages compile native extensions)
        build-essential \
        # Health check
        curl \
    && rm -rf /var/lib/apt/lists/*

# ── uv (fast Python package manager) ─────────────────────────────────────────
COPY --from=ghcr.io/astral-sh/uv:latest /uv /usr/local/bin/uv
# Install into the system Python, no virtualenv needed inside Docker
ENV UV_SYSTEM_PYTHON=1 \
    UV_NO_CACHE=1

WORKDIR /service

# ── Python dependencies ────────────────────────────────────────────────────────
# Copy pyproject.toml first so this layer is cached until deps change.
COPY pyproject.toml .
COPY app/ app/

# uv reads [tool.uv.sources] in pyproject.toml and automatically uses the
# CPU-only PyTorch index on Linux — no extra flags needed here.
RUN uv pip install .

# ── Runtime environment variables ─────────────────────────────────────────────
ENV SERVER_PORT=3001 \
    # Tesseract: explicit path needed inside the container
    TESSERACT_DATA_PATH=/usr/share/tesseract-ocr/4.00/tessdata \
    TESSERACT_LANGUAGE=eng \
    TESSERACT_PAGE_SEG_MODE=3 \
    # Ollama: host.docker.internal routes to the host machine's Ollama instance
    OLLAMA_BASE_URL=http://host.docker.internal:11434 \
    OLLAMA_OCR_MODEL=qwen2.5vl:7b \
    OLLAMA_INSIGHTS_MODEL=llama3.2 \
    # Keep ML model caches inside the container (no host-mount leakage)
    HF_HOME=/service/.cache/huggingface \
    EASYOCR_MODULE_PATH=/service/.cache/easyocr \
    DOCTR_CACHE_DIR=/service/.cache/doctr \
    HOME=/service

EXPOSE ${SERVER_PORT}

# ── Health check ───────────────────────────────────────────────────────────────
HEALTHCHECK --interval=30s --timeout=10s --start-period=60s --retries=3 \
    CMD curl -sf http://localhost:${SERVER_PORT}/health || exit 1

# ── Start ──────────────────────────────────────────────────────────────────────
CMD ["sh", "-c", "uvicorn app.main:app --host 0.0.0.0 --port ${SERVER_PORT} --workers 1"]
