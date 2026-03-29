import logging
import threading
from typing import ClassVar

import numpy as np
from PIL import Image

from app.engines.base import OcrEngine

log = logging.getLogger(__name__)
_FAILED = object()


class EasyOcrEngine(OcrEngine):
    """
    EasyOCR — deep-learning OCR supporting 80+ languages.
    The Reader is lazy-loaded on first request and reused globally.
    """

    _lock: ClassVar[threading.Lock] = threading.Lock()
    _reader: ClassVar = None  # None | Reader instance | _FAILED

    def image_to_text(self, image: Image.Image) -> str:
        with self._lock:
            if EasyOcrEngine._reader is _FAILED:
                log.error("EasyOCR init previously failed — returning empty string.")
                return ""

            if EasyOcrEngine._reader is None:
                log.info("Initialising EasyOCR reader (first request)…")
                try:
                    import easyocr
                    EasyOcrEngine._reader = easyocr.Reader(["en"], gpu=False)
                    log.info("EasyOCR ready.")
                except Exception as e:
                    log.error("EasyOCR init failed (will not retry): %s", e)
                    EasyOcrEngine._reader = _FAILED
                    return ""

            reader = EasyOcrEngine._reader

        try:
            results = reader.readtext(np.array(image), detail=0)
            return "\n".join(str(r) for r in results if r)
        except Exception as e:
            log.error("EasyOCR failed: %s", e)
            return ""
