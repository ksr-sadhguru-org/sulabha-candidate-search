import re

from app.db.lookups import DomainRepository, QueryCacheRepository
from app.llm.client import LlmClient, LlmUnavailableError
from app.models import Need, ParsedQuery
from app.search import domains as domain_names, roles, terms


class QueryParser:
    """Parses a query once with the LLM and caches the result, so the same query always searches the same way."""

    def __init__(self, llm: LlmClient, cache: QueryCacheRepository, domains: DomainRepository):
        self.llm = llm
        self.cache = cache
        self.domains = domains

    def parse(self, query: str) -> ParsedQuery:
        key = re.sub(r"\s+", " ", query.strip().lower())
        if (cached := self.cache.find(key)) is not None:
            return _complete(ParsedQuery.model_validate_json(cached), self.domains.names())
        known = self.domains.names()
        raw = self.llm.parse_query(query, known)
        if raw is None:
            raise LlmUnavailableError("Search failed: the AI service did not answer - check the backend's OPENAI_* settings and logs")
        parsed = _complete(raw, known)
        self.cache.save(key, parsed.model_dump_json(by_alias=True))
        return parsed


def _tidy(n: Need | None) -> Need | None:
    """An alternative that is just the term's last word ("developer" for "software developer") is broader than what was
    asked, so it is dropped."""
    if n is None or n.term is None or not n.term.strip():
        return None
    words = terms.normalize(n.term).split(" ")
    head = words[-1]
    alternatives = [a for a in n.alternatives or [] if len(words) == 1 or terms.normalize(a) != head]
    return Need(term=n.term, alternatives=alternatives, min_years=n.min_years)


def _complete(q: ParsedQuery, known: list[str]) -> ParsedQuery:
    """No Nones downstream: missing lists become empty, a missing "understood" counts as understood; a role not on the
    fixed list (or "other") and a domain not on the domain list are dropped - a query can't invent either. Skills with
    no job ("knows java") name no role or domain, so any the AI added from the technology alone are dropped."""
    role, domain = roles.normalize(q.role), domain_names.normalize(q.domain)
    job, must = _tidy(q.job), _tidy_all(q.must)
    skills_only = job is None and bool(must)
    return ParsedQuery(understood=q.understood is None or q.understood,
                       role=None if skills_only or role == roles.OTHER else role,
                       domain=None if skills_only or domain not in known else domain,
                       job=job, must=must, nice=_tidy_all(q.nice), filters=q.filters)


def _tidy_all(needs: list[Need] | None) -> list[Need]:
    return [t for t in map(_tidy, needs or []) if t is not None]
