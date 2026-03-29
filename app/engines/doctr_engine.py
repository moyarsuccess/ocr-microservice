import logging
import threading
from typing import ClassVar

import numpy as np
from PIL import Image

from app.engines.base import OcrEngine

log = logging.getLogger(__name__)
_FAILED = object()


class DoctrEngine(OcrEngine):
    """
    DocTR (Mindee) — document text recognition via a PyTorch pipeline.
    Model is lazy-loaded on first request and reused globally.
    """

    _lock: ClassVar[threading.Lock] = threading.Lock()
    _model: ClassVar = None  # None | ocr_predictor instance | _FAILED

    def image_to_text(self, image: Image.Image) -> str:
        with self._lock:
            if DoctrEngine._model is _FAILED:
                log.error("DocTR init previously failed — returning empty string.")
                return ""

            if DoctrEngine._model is None:
                log.info("Initialising DocTR model (first request)…")
                try:
                    from doctr.models import ocr_predictor
                    DoctrEngine._model = ocr_predictor(pretrained=True)
                    log.info("DocTR ready.")
                except Exception as e:
                    log.error("DocTR init failed (will not retry): %s", e)
                    DoctrEngine._model = _FAILED
                    return ""

            model = DoctrEngine._model

        try:
            from doctr.io import DocumentFile
            img_array = np.array(image)
            doc = DocumentFile.from_images([img_array])
            result = model(doc)

            lines = [
                " ".join(word.value for word in line.words)
                for page in result.pages
                for block in page.blocks
                for line in block.lines
            ]
            return "\n".join(line for line in lines if line.strip())

        except Exception as e:
            log.error("DocTR failed: %s", e)
            return ""
