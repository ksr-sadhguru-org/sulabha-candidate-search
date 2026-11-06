"""Turns an LLM profile plus the form's skill competencies into the expertise rows stored for one candidate."""
import math
from datetime import date

from app.db.expertise import NewExpertise
from app.models import Entry, Profile
from app.search import domains, roles, skill_competencies, terms

# Score curve by relevant years, interpolated between these points: (years, score).
_CURVE = [(0, 55), (1, 65), (3, 75), (6, 85), (10, 93), (20, 97)]
_STALE_AFTER_YEARS, _STALE_PENALTY = 5, 8


def to_expertise(profile: Profile, skills: str | None) -> list[NewExpertise]:
    from_resume = [NewExpertise(e.name.strip(), e.kind, job_role(e), job_domain(e), "resume", _score(e), e.years,
                                _terms([e.name, *(e.terms or [])]))
                   for e in profile.entries or [] if e.name and e.name.strip()]
    # The reviewed form list: a skill without years gets the candidate's total years.
    from_form = [NewExpertise(s.name, "skill", None, None, "form", score_for_years(years, None), years, _terms([s.name]))
                 for s in skill_competencies.parse(skills)
                 for years in [profile.total_years if s.years is None else s.years]]
    return [e for e in from_resume + from_form if e.terms]


def job_role(e: Entry) -> str | None:
    """Only job (profession) entries carry a role and domain; skills count wherever they appear. An unlisted role
    becomes "other"."""
    return (roles.normalize(e.role) or roles.OTHER) if _is_job(e) else None


def job_domain(e: Entry) -> str | None:
    return domains.normalize(e.domain) if _is_job(e) else None


def _is_job(e: Entry) -> bool:
    return (e.kind or "").lower() == "profession"


def _score(e: Entry) -> int:
    if (e.kind or "").lower() == "education" and e.score is not None:
        return max(0, min(100, e.score))
    return score_for_years(e.years, e.last_used_year)


def score_for_years(years: float | None, last_used_year: int | None) -> int:
    """Same years, same score, for every candidate - less if not used in the last few years."""
    y = max(0, years or 0)
    i = 1
    while i < len(_CURVE) - 1 and y > _CURVE[i][0]:
        i += 1
    (y0, s0), (y1, s1) = _CURVE[i - 1], _CURVE[i]
    base = min(s1, s0 + (y - y0) * (s1 - s0) / (y1 - y0))
    stale = last_used_year is not None and date.today().year - last_used_year > _STALE_AFTER_YEARS
    return math.floor(base + 0.5) - (_STALE_PENALTY if stale else 0)


def _terms(raw: list[str | None]) -> tuple[str, ...]:
    return tuple(dict.fromkeys(t for t in (terms.normalize(r) for r in raw if r is not None) if t))
