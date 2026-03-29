from abc import ABC, abstractmethod

from PIL import Image


class OcrEngine(ABC):
    """Common interface that every OCR backend must implement."""

    @abstractmethod
    def image_to_text(self, image: Image.Image) -> str:
        """Extract text from a single PIL RGB image."""
        ...
