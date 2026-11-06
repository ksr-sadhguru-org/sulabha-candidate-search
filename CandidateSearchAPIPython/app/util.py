import hashlib


def sha256_hex(text: str) -> str:
    """SHA-256 of the text's UTF-8 bytes, as lowercase hex - used to detect duplicate resume uploads by content."""
    return hashlib.sha256(text.encode("utf-8")).hexdigest()
