"""Splits a "City, State, Country" location (as the LLM writes it into the form) into its parts."""
from app.models import Location


def parse(text: str | None) -> Location:
    parts = [p.strip() for p in (text or "").split(",") if p.strip()]
    match parts:
        case []:
            return Location()
        case [city]:
            return Location(city=city)
        # "Dubai, United Arab Emirates" - a city-state style location with no state part
        case [city, country]:
            return Location(city=city, country=country)
        case _:
            return Location(city=parts[0], state=parts[-2], country=parts[-1])


def format(location: Location | None) -> str | None:
    """"Coimbatore, Tamil Nadu, India" from the parts that are present."""
    if location is None:
        return None
    return ", ".join(p for p in (location.city, location.state, location.country) if p and p.strip()) or None
