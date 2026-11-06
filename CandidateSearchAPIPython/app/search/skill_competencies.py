"""Parses the form's skill competencies field: "Java (2 years), Spring Boot (4 years), Git"."""
import re
from dataclasses import dataclass

_ITEM = re.compile(r"\s*([^,(]+?)\s*(?:\(([^)]*)\))?\s*(?:,|$)")
_NUMBER = re.compile(r"(\d+(?:\.\d+)?)")


@dataclass(frozen=True)
class Item:
    name: str
    years: float | None  # None when the entry gave none


def parse(text: str | None) -> list[Item]:
    items: list[Item] = []
    if text is None or not text.strip():
        return items
    for m in _ITEM.finditer(text):
        name = m.group(1).strip()
        if name:
            items.append(Item(name, _years(m.group(2))))
        if m.end() >= len(text):
            break
    return items


def _years(in_parens: str | None) -> float | None:
    if in_parens is None:
        return None
    if "<" in in_parens:
        return 0.5  # "<1 year"
    n = _NUMBER.search(in_parens)
    return float(n.group(1)) if n else None
