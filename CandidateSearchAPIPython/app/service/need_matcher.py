"""Finds who has one need by its words (a term plus its equivalents), in order of strength:
EXACT - a profile term is the query term or ends with it ("music teacher" for "teacher");
RELATED - the same, via one of the LLM's equivalents ("tutor") or only via an education entry;
TEXT - only the resume text contains it (full-text search)."""
import re
from collections import defaultdict
from collections.abc import Callable
from dataclasses import dataclass

from app.db.expertise import ExpertiseRepository, Hit
from app.db.lookups import TextSearchRepository
from app.models import Need
from app.search import terms

EXACT, RELATED, TEXT, SAME_DOMAIN = 3, 2, 1, 0


@dataclass(frozen=True)
class Result:
    """How one candidate meets one need. hits are empty for a TEXT match, text_term is None otherwise."""
    quality: int
    hits: list[Hit]
    text_term: str | None = None

    def best_score(self) -> int:
        return max((h.score for h in self.hits), default=0)

    def years(self) -> float | None:
        """Years for this need - the reviewed form value when there is one, else the profile's."""
        for source in ("form", "resume"):
            years = [h.years for h in self.hits if h.source == source and h.years is not None]
            if years:
                return max(years)
        return None


def _is(kind: str | None, wanted: str) -> bool:
    return (kind or "").lower() == wanted


class NeedMatcher:

    def __init__(self, expertise: ExpertiseRepository, text_search: TextSearchRepository):
        self.expertise = expertise
        self.text_search = text_search

    def match_skill(self, need: Need) -> dict[str, Result]:
        """A skill ("java") counts wherever it appears - any job, a skill entry, or the resume text."""
        return self._match(need, False, lambda h: True, True)

    def match_job(self, need: Need, domain: str | None, text_fallback: bool) -> dict[str, Result]:
        """The job as worded ("music instructor"): only job entries count, only in the query's domain when there is one;
        resume text only when the query names neither a role nor a domain ("astronaut")."""
        return self._match(need, False, lambda h: _is(h.kind, "profession") and (domain is None or domain == h.domain),
                           text_fallback)

    def match_nice(self, need: Need) -> dict[str, Result]:
        """Ranking extras ("senior"): profile terms containing the need anywhere."""
        return self._match(need, True, lambda h: True, True)

    def _match(self, need: Need, anywhere: bool, allowed: Callable[[Hit], bool], text_fallback: bool) -> dict[str, Result]:
        main = terms.normalize(need.term)
        phrases = list(dict.fromkeys(p for p in [main, *(terms.normalize(a) for a in need.alternatives or [])] if p))

        by_candidate: dict[str, list[Hit]] = defaultdict(list)
        for h in (self.expertise.find_containing(phrases) if anywhere else self.expertise.find_ending_with(phrases)):
            if allowed(h):
                by_candidate[h.candidate_id].append(h)
        results: dict[str, Result] = {}
        for candidate_id, hits in by_candidate.items():
            # A degree ("B.Tech Electrical Engineering") is not the profession itself - it counts as related.
            exact = [h for h in hits if not _is(h.kind, "education")
                     and (_contains_words(h.term, main) if anywhere else terms.matches(h.term, main))]
            results[candidate_id] = Result(EXACT, exact) if exact else Result(RELATED, hits)
        if text_fallback:
            for p in phrases:
                if re.fullmatch(r"[a-z0-9 ]+", p):  # full-text search can't represent "c++", "c#", ".net"
                    for candidate_id in self.text_search.find_containing(p):
                        results.setdefault(candidate_id, Result(TEXT, [], p))
        return results


def _contains_words(term: str, phrase: str) -> bool:
    return f" {phrase} " in f" {term} "
