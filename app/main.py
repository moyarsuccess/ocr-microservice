"""
FastAPI entry point — exposes POST /ocr (multipart or JSON) and GET /health.
"""
import base64
import logging
from typing import Optional

from fastapi import FastAPI, Query, Request
from fastapi.responses import JSONResponse

from app.config import MAX_UPLOAD_BYTES
from app.models import Base64Request, OcrResponse
from app.service import OcrService

# ── Logging ────────────────────────────────────────────────────────────────────
logging.basicConfig(
    level=logging.INFO,
    format="%(asctime)s [%(levelname)s] %(name)s — %(message)s",
    datefmt="%Y-%m-%d %H:%M:%S",
)
log = logging.getLogger("ocr-service")

# ── App ────────────────────────────────────────────────────────────────────────
app = FastAPI(
    title="OCR Service",
    version="1.0.0",
    description="Multi-engine OCR microservice — Tesseract, Ollama, Surya, EasyOCR, PaddleOCR, DocTR",
)

_service = OcrService()


# ── Routes ─────────────────────────────────────────────────────────────────────

@app.get("/health", tags=["system"])
def health():
    """Liveness probe."""
    return {"status": "ok"}


@app.post("/ocr", response_model=OcrResponse, tags=["ocr"])
async def ocr(
        request: Request,
        engine: str = Query(
            default="tesseract",
            description="OCR engine: tesseract | ollama | surya | easyocr | paddle | doctr",
        ),
        model: Optional[str] = Query(
            default=None,
            description="Model name override (Ollama only, e.g. qwen2.5vl:7b)",
        ),
        insights: bool = Query(
            default=False,
            description="Run Ollama-powered document insight analysis on the extracted text",
        ),
):
    """
    Extract text from an image or PDF.

    **Input options (mutually exclusive):**

    1. **Multipart form** — field name `file`, any image or PDF.
    2. **JSON body** — `{"base64": "<b64>", "mimeType": "image/png"}`.
    """
    content_type = request.headers.get("content-type", "")

    if "multipart/form-data" in content_type:
        form = await request.form()
        upload = form.get("file")
        if upload is None:
            return JSONResponse(status_code=400, content={"error": "Missing 'file' field in form."})
        file_bytes = await upload.read()
        mime_type = upload.content_type or "application/octet-stream"

    elif "application/json" in content_type:
        body = await request.json()
        try:
            req = Base64Request(**body)
        except Exception as e:
            return JSONResponse(status_code=422, content={"error": str(e)})
        file_bytes = base64.b64decode(req.base64)
        mime_type = req.mimeType

    else:
        return JSONResponse(
            status_code=415,
            content={"error": "Unsupported content type. Use multipart/form-data or application/json."},
        )

    if len(file_bytes) > MAX_UPLOAD_BYTES:
        mb = MAX_UPLOAD_BYTES // (1024 * 1024)
        return JSONResponse(status_code=413, content={"error": f"File exceeds {mb} MB limit."})

    log.info(
        "OCR request — engine=%s, mime=%s, size=%d bytes",
        engine, mime_type, len(file_bytes),
    )

    return await _service.process(file_bytes, mime_type, engine, model, insights)
