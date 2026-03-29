"""
All configuration is read from environment variables with sensible defaults
so the service works out-of-the-box in both local dev and Docker.
"""
import os

# ── Server ─────────────────────────────────────────────────────────────────────
SERVER_PORT = int(os.getenv("SERVER_PORT", "3001"))

# ── Upload limits ──────────────────────────────────────────────────────────────
MAX_UPLOAD_BYTES = int(os.getenv("MAX_UPLOAD_BYTES", str(200 * 1024 * 1024)))  # 200 MB

# ── Tesseract ──────────────────────────────────────────────────────────────────
# Leave empty to let Tesseract auto-detect tessdata from the system install
# (correct for local dev). Set explicitly in Docker via ENV.
TESSERACT_DATA_PATH = os.getenv("TESSERACT_DATA_PATH", "")
TESSERACT_LANGUAGE = os.getenv("TESSERACT_LANGUAGE", "eng")
TESSERACT_PAGE_SEG_MODE = int(os.getenv("TESSERACT_PAGE_SEG_MODE", "3"))

# ── Ollama ─────────────────────────────────────────────────────────────────────
OLLAMA_BASE_URL = os.getenv("OLLAMA_BASE_URL", "http://localhost:11434").rstrip("/")
OLLAMA_OCR_MODEL = os.getenv("OLLAMA_OCR_MODEL", "qwen2.5vl:7b")
OLLAMA_INSIGHTS_MODEL = os.getenv("OLLAMA_INSIGHTS_MODEL", "llama3.2")
