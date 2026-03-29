"""
Shared Pydantic models — single source of truth for all request/response schemas.
"""
from __future__ import annotations

from typing import List, Optional

from pydantic import BaseModel


# ── Request models ─────────────────────────────────────────────────────────────

class Base64Request(BaseModel):
    """JSON body for base64-encoded file input."""
    base64: str
    mimeType: str


# ── Insight models ─────────────────────────────────────────────────────────────

class DocumentInsights(BaseModel):
    """Structured analysis of the OCR'd document."""
    category: str
    summary: str
    keyDetails: List[str]


# ── Response model ─────────────────────────────────────────────────────────────

class OcrResponse(BaseModel):
    success: bool
    engine: str
    text: str
    pages: int
    processingTimeMs: int
    insights: Optional[DocumentInsights] = None
    error: Optional[str] = None
