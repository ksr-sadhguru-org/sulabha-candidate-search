"""The one normalization used for every searchable phrase - profile terms at upload and query terms at search - so both
sides always agree: lowercase, "&" -> "and", punctuation to spaces (keeping "+", "#" and "." for "c++", "c#", ".net",
"node.js"), dotted abbreviations collapsed ("b.e." -> "be"), and plurals made singular ("teachers" -> "teacher")."""
import re


def normalize(raw: str | None) -> str:
    if raw is None:
        return ""
    cleaned = re.sub(r"[^a-z0-9+#.]+", " ", raw.lower().replace("&", " and ")).strip()
    words = (_collapse_dots(word) for word in re.split(" +", cleaned))
    return " ".join(_singular(w) for w in words if w)


def matches(term: str, query: str) -> bool:
    """True when `term` is `query` or ends with it as whole words: "music teacher" matches "teacher"."""
    return term == query or term.endswith(" " + query)


def _collapse_dots(word: str) -> str:
    """"sr." -> "sr", "b.e" -> "be", "m.sc" -> "msc"; ".net", "node.js" and "asp.net" keep their dots."""
    w = word.rstrip(".")
    if w.startswith(".") or "." not in w:
        return w
    return w if any(len(segment) > 2 for segment in w.split(".")) else w.replace(".", "")


def _singular(w: str) -> str:
    if len(w) <= 4 or not w.isalpha():
        return w
    if w.endswith("ies"):
        return w[:-3] + "y"
    if w.endswith(("sses", "ches", "shes", "xes")):
        return w[:-2]
    if w.endswith(("ss", "us", "is", "ics")):
        return w
    return w[:-1] if w.endswith("s") else w
