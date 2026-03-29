import logging
import threading
from typing import ClassVar

from PIL import Image

from app.engines.base import OcrEngine

log = logging.getLogger(__name__)

# Sentinel — prevents repeated init attempts after a failure
_FAILED = object()


class SuryaEngine(OcrEngine):
    """
    Surya OCR (surya-ocr >= 0.17).

    API used (0.17+):
        from surya.foundation import FoundationPredictor
        from surya.recognition import RecognitionPredictor
        from surya.detection import DetectionPredictor

        found = FoundationPredictor()
        rec   = RecognitionPredictor(found)
        det   = DetectionPredictor()
        preds = rec([image], det_predictor=det)   # langs arg deprecated in 0.17

    Models are lazy-loaded on first request and reused across all subsequent calls.
    """

    _lock: ClassVar[threading.Lock] = threading.Lock()
    # None → not yet initialised | tuple → (rec, det) | _FAILED → init failed
    _state: ClassVar = None

    def image_to_text(self, image: Image.Image) -> str:
        with self._lock:
            if SuryaEngine._state is _FAILED:
                log.error("Surya init previously failed — returning empty string.")
                return ""

            if SuryaEngine._state is None:
                log.info("Initialising Surya predictors (first request)…")
                try:
                    from surya.foundation import FoundationPredictor
                    from surya.recognition import RecognitionPredictor
                    from surya.detection import DetectionPredictor

                    found = FoundationPredictor()
                    rec = RecognitionPredictor(found)
                    det = DetectionPredictor()
                    SuryaEngine._state = (rec, det)
                    log.info("Surya ready.")
                except Exception as e:
                    log.error("Surya init failed (will not retry): %s", e)
                    SuryaEngine._state = _FAILED
                    return ""

            rec, det = SuryaEngine._state  # type: ignore[misc]

        # Run OCR outside the lock so concurrent requests aren't serialised
        try:
            predictions = rec([image], det_predictor=det)
            lines = [
                line.text
                for page in predictions
                for line in page.text_lines
                if line.text.strip()
            ]
            return "\n".join(lines)
        except Exception as e:
            log.error("Surya OCR failed: %s", e)
            return ""
