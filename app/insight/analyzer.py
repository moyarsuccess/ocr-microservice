"""
Optional document insight generation using an Ollama text model (default: llama3.2).
Returns None gracefully when Ollama is unavailable.
"""
import json
import logging
from typing import Optional

import httpx

from app.config import OLLAMA_BASE_URL, OLLAMA_INSIGHTS_MODEL
from app.models import DocumentInsights

log = logging.getLogger(__name__)

_MAX_TEXT_CHARS = 4000

_PROMPT = """\
You are a document analysis assistant. Analyze the OCR-extracted text below and \
respond with a valid JSON object (no markdown, no code fences) using exactly this shape:

{{
  "category": "<one of: PERSONAL_IDENTITY | IMMIGRATION | FINANCIAL | LEGAL_CONTRACT | \
MEDICAL | EDUCATIONAL | INSURANCE | UTILITY_BILL | BUSINESS | UNKNOWN>",
  "summary": "<2-3 sentence summary>",
  "keyDetails": ["<detail 1>", "<detail 2>", "<detail 3>"]
}}

OCR TEXT:
{text}
"""


class InsightAnalyzer:
    def analyze(self, text: str) -> Optional[DocumentInsights]:
        try:
            prompt = _PROMPT.format(text=text[:_MAX_TEXT_CHARS])
            resp = httpx.post(
                f"{OLLAMA_BASE_URL}/api/generate",
                json={
                    "model": OLLAMA_INSIGHTS_MODEL,
                    "prompt": prompt,
                    "stream": False,
                    "format": "json",
                },
                timeout=120,
            )
            resp.raise_for_status()
            raw = resp.json().get("response", "{}")
            data = json.loads(raw)
            return DocumentInsights(
                category=data.get("category", "UNKNOWN"),
                summary=data.get("summary", ""),
                keyDetails=data.get("keyDetails", []),
            )
        except Exception as e:
            log.warning("Insight analysis failed (Ollama unavailable?): %s", e)
            return None
