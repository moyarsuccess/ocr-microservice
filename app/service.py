"""
OcrService — resolves the requested engine, handles PDF rendering,
and optionally runs document insight analysis.
"""
from __future__ import annotations

import asyncio
import io
import logging
import time
from typing import Optional

import pypdfium2 as pdfium
from PIL import Image

from app.config import OLLAMA_OCR_MODEL
from app.engines.base import OcrEngine
from app.engines.doctr_engine import DoctrEngine
from app.engines.easyocr_engine import EasyOcrEngine
from app.engines.ollama import OllamaEngine
from app.engines.paddle_engine import PaddleEngine
from app.engines.surya import SuryaEngine
from app.engines.tesseract import TesseractEngine
from app.insight.analyzer import InsightAnalyzer
from app.models import OcrResponse

log = logging.getLogger(__name__)

# 300 DPI — keeps quality comparable to scanning at high resolution
# pypdfium2 scale = desired_dpi / 72  (72 pt == 1 inch)
_PDF_SCALE = 300 / 72


class OcrService:
    def __init__(self) -> None:
        self._analyzer = InsightAnalyzer()

    # ── Public API ─────────────────────────────────────────────────────────────

    async def process(
        self,
        file_bytes: bytes,
        mime_type: str,
        engine: str,
        model: Optional[str],
        with_insights: bool,
    ) -> OcrResponse:
        start = time.monotonic()
        ocr_engine = self._resolve_engine(engine, model)

        try:
            if "pdf" in mime_type.lower():
                text, pages = await asyncio.to_thread(
                    self._process_pdf, file_bytes, ocr_engine
                )
            else:
                image = Image.open(io.BytesIO(file_bytes)).convert("RGB")
                text = await asyncio.to_thread(ocr_engine.image_to_text, image)
                pages = 1

            insights = None
            if with_insights and text.strip():
                insights = await asyncio.to_thread(self._analyzer.analyze, text)

            return OcrResponse(
                success=True,
                engine=engine,
                text=text,
                pages=pages,
                processingTimeMs=int((time.monotonic() - start) * 1000),
                insights=insights,
            )

        except Exception as exc:
            log.error("OCR failed [engine=%s]: %s", engine, exc, exc_info=True)
            return OcrResponse(
                success=False,
                engine=engine,
                text="",
                pages=0,
                processingTimeMs=int((time.monotonic() - start) * 1000),
                error=str(exc),
            )

    # ── Internals ──────────────────────────────────────────────────────────────

    def _resolve_engine(self, engine: str, model: Optional[str]) -> OcrEngine:
        match engine.lower():
            case "tesseract":
                return TesseractEngine()
            case "ollama":
                return OllamaEngine(model or OLLAMA_OCR_MODEL)
            case "surya":
                return SuryaEngine()
            case "easyocr":
                return EasyOcrEngine()
            case "paddle" | "paddleocr":
                return PaddleEngine()
            case "doctr":
                return DoctrEngine()
            case _:
                log.warning("Unknown engine '%s' — falling back to tesseract.", engine)
                return TesseractEngine()

    def _process_pdf(
        self, pdf_bytes: bytes, engine: OcrEngine
    ) -> tuple[str, int]:
        """Render each PDF page at 300 DPI and OCR it."""
        doc = pdfium.PdfDocument(pdf_bytes)
        page_texts: list[str] = []

        for i in range(len(doc)):
            page = doc[i]
            bitmap = page.render(scale=_PDF_SCALE)
            image = bitmap.to_pil().convert("RGB")
            page_text = engine.image_to_text(image)
            page_texts.append(f"=== Page {i + 1} ===\n{page_text}")

        return "\n\n".join(page_texts), len(doc)
