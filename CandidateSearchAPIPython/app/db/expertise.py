from collections.abc import Collection
from dataclasses import dataclass, replace

from app.db.connection import Db


@dataclass(frozen=True)
class NewExpertise:
    """One expertise row to store. terms must already be normalized."""
    name: str
    kind: str | None
    role: str | None
    domain: str | None
    source: str
    score: int
    years: float | None
    terms: tuple[str, ...]


@dataclass(frozen=True)
class Hit:
    """One stored term that matched a query phrase, with the expertise row it belongs to."""
    candidate_id: str
    expertise_id: int
    name: str
    kind: str | None
    role: str | None
    domain: str | None
    source: str
    score: int
    years: float | None
    term: str

    def via(self, term: str) -> "Hit":
        return replace(self, term=term)


_HIT_SELECT = ("SELECT t.candidate_id, e.id AS expertise_id, e.name, e.kind, e.role, e.domain, e.source, e.score, "
               "e.years::float8 AS years, t.term FROM expertise_terms t JOIN expertise e ON e.id = t.expertise_id ")


class ExpertiseRepository:
    """A candidate's search profile: expertise rows and the normalized terms that find them."""

    def __init__(self, db: Db):
        self.db = db

    def replace(self, candidate_id: str, expertise: list[NewExpertise]) -> None:
        self.db.execute("DELETE FROM expertise WHERE candidate_id = %(id)s", id=candidate_id)
        for e in expertise:
            expertise_id = self.db.scalar(
                "INSERT INTO expertise (candidate_id, name, kind, role, domain, source, score, years) "
                "VALUES (%(candidate_id)s, %(name)s, %(kind)s, %(role)s, %(domain)s, %(source)s, %(score)s, %(years)s) RETURNING id",
                candidate_id=candidate_id, name=e.name, kind=e.kind, role=e.role, domain=e.domain, source=e.source,
                score=e.score, years=e.years)
            self.db.execute_many(
                "INSERT INTO expertise_terms (expertise_id, candidate_id, term) VALUES (%(id)s, %(candidate_id)s, %(term)s) "
                "ON CONFLICT DO NOTHING",
                [{"id": expertise_id, "candidate_id": candidate_id, "term": t} for t in e.terms])

    def find_by_candidate(self, candidate_id: str) -> list[dict]:
        rows = self.db.rows(
            "SELECT e.name, e.kind, e.role, e.domain, e.source, e.score, e.years::float8 AS years, "
            "STRING_AGG(t.term, '|' ORDER BY t.term) AS terms "
            "FROM expertise e LEFT JOIN expertise_terms t ON t.expertise_id = e.id "
            "WHERE e.candidate_id = %(id)s GROUP BY e.id ORDER BY e.source DESC, e.score DESC", id=candidate_id)
        return [{**r, "terms": r["terms"].split("|") if r["terms"] else []} for r in rows]

    def find_jobs(self, role: str | None, domain: str | None) -> list[Hit]:
        """Every job entry with this role and/or domain (None = any). term is empty - no phrase was matched."""
        return [Hit(**r) for r in self.db.rows(
            "SELECT e.candidate_id, e.id AS expertise_id, e.name, e.kind, e.role, e.domain, e.source, e.score, "
            "e.years::float8 AS years, '' AS term FROM expertise e "
            "WHERE e.kind = 'profession' AND (%(role)s::text IS NULL OR e.role = %(role)s) "
            "AND (%(domain)s::text IS NULL OR e.domain = %(domain)s)", role=role, domain=domain)]

    def find_ending_with(self, phrases: Collection[str]) -> list[Hit]:
        """Terms equal to a phrase or ending with it as whole words ("music teacher" for "teacher")."""
        return [Hit(**r) for r in self.db.rows(
            _HIT_SELECT + "WHERE t.term = ANY(%(phrases)s::text[]) OR t.term LIKE ANY(%(suffixes)s::text[])",
            phrases=list(phrases), suffixes=[f"% {p}" for p in phrases])] if phrases else []

    def find_containing(self, phrases: Collection[str]) -> list[Hit]:
        """Terms containing a phrase as whole words ("senior java developer" for "senior") - for ranking extras."""
        return [Hit(**r) for r in self.db.rows(
            _HIT_SELECT + "WHERE ' ' || t.term || ' ' LIKE ANY(%(patterns)s::text[])",
            patterns=[f"% {p} %" for p in phrases])] if phrases else []
