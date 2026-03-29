import logging
import threading
from typing import ClassVar

import numpy as np
from PIL import Image

from app.engines.base import OcrEngine

log = logging.getLogger(__name__)
_FAILED = object()


class PaddleEngine(OcrEngine):
    """
    PaddleOCR — Baidu's high-accuracy OCR pipeline.

    Supports both PaddleOCR 3.x (predict() API) and 2.x (ocr() API) via
    runtime detection so the same code works regardless of the installed version.

    PaddleOCR 3.x result format:  res.json['rec_texts']  (list[str])
    PaddleOCR 2.x result format:  result[0] = [[bbox, (text, score)], …]
    """

    _lock: ClassVar[threading.Lock] = threading.Lock()
    _ocr: ClassVar = None  # None | PaddleOCR instance | _FAILED

    def image_to_text(self, image: Image.Image) -> str:
        with self._lock:
            if PaddleEngine._ocr is _FAILED:
                log.error("PaddleOCR init previously failed — returning empty string.")
                return ""

            if PaddleEngine._ocr is None:
                log.info("Initialising PaddleOCR (first request)…")
                try:
                    from paddleocr import PaddleOCR
                    # Use minimal args that work on both 2.x and 3.x
                    PaddleEngine._ocr = PaddleOCR(lang="en", show_log=False)
                    log.info("PaddleOCR ready.")
                except Exception as e:
                    log.error("PaddleOCR init failed (will not retry): %s", e)
                    PaddleEngine._ocr = _FAILED
                    return ""

            ocr = PaddleEngine._ocr

        try:
            return self._extract(ocr, image)
        except Exception as e:
            log.error("PaddleOCR failed: %s", e)
            return ""

    def _extract(self, ocr, image: Image.Image) -> str:
        img = np.array(image)

        # ── PaddleOCR 3.x API ────────────────────────────────────────────────
        if hasattr(ocr, "predict"):
            try:
                results = ocr.predict(img)
                texts: list[str] = []
                for res in results:
                    data = res.json if hasattr(res, "json") else (res if isinstance(res, dict) else {})
                    texts.extend(data.get("rec_texts", []))
                if texts:
                    return "\n".join(t for t in texts if t)
            except Exception:
                pass  # fall through to 2.x API

        # ── PaddleOCR 2.x API ────────────────────────────────────────────────
        if hasattr(ocr, "ocr"):
            result = ocr.ocr(img, cls=True)
            if result and result[0]:
                return "\n".join(
                    line[1][0] for line in result[0] if line and len(line) > 1
                )

        return ""
