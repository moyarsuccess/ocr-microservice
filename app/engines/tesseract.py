import logging

import pytesseract
from PIL import Image

from app.config import TESSERACT_DATA_PATH, TESSERACT_LANGUAGE, TESSERACT_PAGE_SEG_MODE
from app.engines.base import OcrEngine

log = logging.getLogger(__name__)


class TesseractEngine(OcrEngine):
    """
    Tesseract 4/5 via pytesseract.
    Fast, fully local, no model download required.
    """

    def image_to_text(self, image: Image.Image) -> str:
        try:
            parts = [f"--psm {TESSERACT_PAGE_SEG_MODE}"]
            # Only pass --tessdata-dir when explicitly set (e.g. in Docker).
            # Omitting it lets Tesseract auto-detect from the system install.
            if TESSERACT_DATA_PATH:
                parts.insert(0, f"--tessdata-dir {TESSERACT_DATA_PATH}")
            return pytesseract.image_to_string(
                image,
                lang=TESSERACT_LANGUAGE,
                config=" ".join(parts),
            )
        except Exception as e:
            log.error("Tesseract failed: %s", e)
            return ""
