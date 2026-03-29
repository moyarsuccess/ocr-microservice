import base64
import io
import logging
import threading
from typing import ClassVar, Set

import httpx
from PIL import Image

from app.config import OLLAMA_BASE_URL, OLLAMA_OCR_MODEL
from app.engines.base import OcrEngine

log = logging.getLogger(__name__)

_OCR_PROMPT = (
    "You are an OCR engine. Extract all visible text from this image exactly as it appears. "
    "Preserve the original layout and line breaks. "
    "Output only the raw extracted text — no descriptions, no commentary."
)
_MAX_DIM = 1920  # resize images larger than this before sending


class OllamaEngine(OcrEngine):
    """
    Vision-model OCR via a local Ollama instance (default: qwen2.5vl:7b).
    The model is pulled automatically on first use if not already present.
    """

    _pulled: ClassVar[Set[str]] = set()
    _lock: ClassVar[threading.Lock] = threading.Lock()

    def __init__(self, model: str = OLLAMA_OCR_MODEL) -> None:
        self.model = model

    def image_to_text(self, image: Image.Image) -> str:
        try:
            self._ensure_model(self.model)
            image = self._resize(image)

            buf = io.BytesIO()
            image.save(buf, format="PNG")
            b64 = base64.b64encode(buf.getvalue()).decode()

            resp = httpx.post(
                f"{OLLAMA_BASE_URL}/api/generate",
                json={
                    "model": self.model,
                    "prompt": _OCR_PROMPT,
                    "images": [b64],
                    "stream": False,
                },
                timeout=300,
            )
            resp.raise_for_status()
            return resp.json().get("response", "")

        except Exception as e:
            log.error("Ollama OCR failed (model=%s): %s", self.model, e)
            return ""

    # ── Helpers ────────────────────────────────────────────────────────────────

    def _ensure_model(self, model: str) -> None:
        if model in OllamaEngine._pulled:
            return
        with OllamaEngine._lock:
            if model in OllamaEngine._pulled:
                return
            try:
                tags = httpx.get(f"{OLLAMA_BASE_URL}/api/tags", timeout=10)
                available = [m.get("name", "") for m in tags.json().get("models", [])]
                if model not in available:
                    log.info("Pulling Ollama model '%s'… (this may take a while)", model)
                    httpx.post(
                        f"{OLLAMA_BASE_URL}/api/pull",
                        json={"name": model, "stream": False},
                        timeout=1800,
                    )
                    log.info("Ollama model '%s' ready.", model)
                OllamaEngine._pulled.add(model)
            except Exception as e:
                log.warning("Could not verify Ollama model '%s': %s — proceeding anyway.", model, e)

    @staticmethod
    def _resize(image: Image.Image) -> Image.Image:
        w, h = image.size
        scale = min(_MAX_DIM / w, _MAX_DIM / h, 1.0)
        if scale >= 1.0:
            return image
        return image.resize((int(w * scale), int(h * scale)), Image.LANCZOS)
