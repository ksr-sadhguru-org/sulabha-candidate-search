"""The fixed list of roles - the kind of work a job is, whatever its subject (a music teacher and a maths tutor are both
"teacher"). Fixed so searches compare roles exactly; the subject side is the growing domain list."""
import re

OTHER = "other"

ALL = ["engineer / developer", "tester / qa", "technician", "tradesperson", "teacher", "performer", "designer",
       "writer", "professional", "manager", "healthcare worker", "service worker", "driver / operator",
       "researcher", "trainee", OTHER]


def normalize(raw: str | None) -> str | None:
    """The listed role this names ("Teacher", "tester/QA"), or None when it names none."""
    if raw is None or not raw.strip():
        return None
    r = re.sub(r"\s+", " ", re.sub(r"\s*/\s*", " / ", raw.lower())).strip()
    return r if r in ALL else None
