"""One spelling per domain, so "Software and IT", "software & it " and "Software & IT" are the same domain, and
"Trades - Plumbing" is "trades-plumbing"."""
import re


def normalize(raw: str | None) -> str | None:
    """Lowercase, "and" as "&", "-" without spaces around it, single spaces; None or blank stays None."""
    if raw is None or not raw.strip():
        return None
    s = re.sub(r"\band\b", "&", raw.lower())
    s = re.sub(r"\s*&\s*", " & ", s)
    s = re.sub(r"[^a-z0-9&\- ]+", " ", s)
    s = re.sub(r"\s*-\s*", "-", s)
    return re.sub(r"\s+", " ", s).strip()
