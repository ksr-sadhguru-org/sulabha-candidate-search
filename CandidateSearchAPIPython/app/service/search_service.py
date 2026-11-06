"""Search: parse the query (cached) into the job asked (role + domain + wording), the skills asked, extras and filters;
find who meets them; check filters; rank.
The job: role + domain -> jobs with both are direct matches, other roles in the same domain follow as related, other
domains are left out; role only -> that role in any domain; domain only -> that domain in any role. Skills count
wherever they appear. Ranking, strongest first: skills met, then the job (direct > same domain > none), then how things
were found (profile > equivalent > resume text), filters passed, extras, score, years."""
from dataclasses import asdict, dataclass

from app.db.candidates import CandidateRepository, Row
from app.db.expertise import ExpertiseRepository, Hit
from app.models import Need, ParsedQuery
from app.service import filter_evaluator
from app.service.filter_evaluator import FilterCheck
from app.service.need_matcher import EXACT, RELATED, SAME_DOMAIN, NeedMatcher, Result
from app.service.query_parser import QueryParser

NOT_UNDERSTOOD = "We couldn't understand this search. Try describing the role, skills or location, e.g. 'Java developer in Coimbatore'."
TOO_VAGUE = "Please add a role, skill or location to search for, e.g. 'Java developer in Coimbatore'."

_JOB_DIRECT, _JOB_SAME_DOMAIN, _JOB_NONE = 2, 1, 0


@dataclass(frozen=True)
class _Ranked:
    """A result plus what it is ranked by."""
    match: dict
    filters_passed: int
    skills_met: int
    job: int
    quality: int
    nice_met: int
    score: int
    years: float

    def sort_key(self):
        return (-self.skills_met, -self.job, -self.quality, -self.filters_passed, -self.nice_met, -self.score, -self.years,
                self.match["name"] if self.match["name"] is not None else "~", self.match["candidate_id"])


@dataclass(frozen=True)
class _JobMatches:
    """Who meets the job directly, and who only has a job in the same domain (another role)."""
    direct: dict[str, Result]
    same_domain: dict[str, Hit]


class SearchService:

    def __init__(self, parser: QueryParser, matcher: NeedMatcher, candidates: CandidateRepository, expertise: ExpertiseRepository):
        self.parser = parser
        self.matcher = matcher
        self.candidates = candidates
        self.expertise = expertise

    def search(self, query: str, offset: int, limit: int) -> dict:
        """The ranked page [offset, offset + limit) of everyone matching; total counts them all."""
        q = self.parser.parse(query)
        if not q.understood:
            return _response(NOT_UNDERSTOOD, q, [], 0)
        asks_job = q.role is not None or q.domain is not None or q.job is not None
        # Only extras asked for ("senior") - treat them as the skills rather than listing everyone.
        only_extras = not q.must and not asks_job
        skills = q.nice if only_extras else q.must
        nice = [] if only_extras else q.nice
        filter_only = not skills and not asks_job
        if filter_only and not filter_evaluator.has_any(q.filters):
            return _response(TOO_VAGUE, q, [], 0)

        skill_results = [self.matcher.match_skill(n) for n in skills]
        job = self._match_job(q) if asks_job else _JobMatches({}, {})
        nice_results = [self.matcher.match_nice(n) for n in nice]
        # Everyone meeting a skill or the job, or with a job in the same domain; a filter-only query considers everyone
        # and keeps those passing all filters.
        ids = None if filter_only else {i for m in skill_results for i in m} | job.direct.keys() | job.same_domain.keys()

        needs = len(skills) + (1 if asks_job else 0)
        ranked = [self._rank(row, q, skills, skill_results, job, needs, nice, nice_results, filter_only)
                  for row in self.candidates.find_rows(ids).values()]
        ranked = sorted((r for r in ranked if not filter_only or r.filters_passed == len(r.match["filters"])), key=_Ranked.sort_key)
        results = [r.match for r in ranked]
        return _response(_no_match_message(skills, q, filter_only) if not results else None, q, results[offset:offset + limit], len(results))

    def _match_job(self, q: ParsedQuery) -> _JobMatches:
        """The job asked: its wording matched against job entries (in the query's domain when there is one), plus every job
        with the asked role and/or domain; with both, other roles in that domain are related (same domain)."""
        direct: dict[str, Result] = {}
        if q.job is not None:
            direct.update(self.matcher.match_job(q.job, q.domain, q.role is None and q.domain is None))
        if q.role is not None or q.domain is not None:
            label = " + ".join(p for p in (q.role, q.domain) if p is not None)
            by_candidate: dict[str, list[Hit]] = {}
            for h in self.expertise.find_jobs(q.role, q.domain):
                by_candidate.setdefault(h.candidate_id, []).append(h.via(label))
            for candidate_id, hits in by_candidate.items():
                new = Result(EXACT, hits)
                old = direct.get(candidate_id)
                direct[candidate_id] = new if old is None or old.quality < new.quality else old
        same_domain: dict[str, Hit] = {}
        if q.role is not None and q.domain is not None:
            for h in self.expertise.find_jobs(None, q.domain):
                old = same_domain.get(h.candidate_id)
                if h.candidate_id not in direct and (old is None or old.score < h.score):
                    same_domain[h.candidate_id] = h
        return _JobMatches(direct, same_domain)

    def _rank(self, row: Row, q: ParsedQuery, skills: list[Need], skill_results: list[dict[str, Result]], job: _JobMatches,
              needs: int, nice: list[Need], nice_results: list[dict[str, Result]], filter_only: bool) -> _Ranked:
        per_skill = [m.get(row.id) for m in skill_results]  # None: skill not met
        job_result = job.direct.get(row.id)
        same_domain_job = job.same_domain.get(row.id)
        met = [r for r in per_skill if r is not None] + ([job_result] if job_result is not None else [])

        filters = [*filter_evaluator.evaluate(q.filters, row),
                   *(_years_check(skills[i], per_skill[i]) for i in range(len(skills))),
                   *([_years_check(q.job, job_result)] if q.job is not None else [])]
        filters = [f for f in filters if f is not None]
        nice_met = [nice[i].term for i in range(len(nice)) if row.id in nice_results[i]]
        quality = min((r.quality for r in met), default=SAME_DOMAIN)
        match_type = ("filters" if filter_only else "domain" if not met else "partial" if len(met) < needs
                      else _match_type(quality))
        only_same_domain = not met and same_domain_job is not None
        entries = ([_entry(same_domain_job.name, same_domain_job.score, same_domain_job.years,
                           "same domain: " + same_domain_job.domain, True)] if only_same_domain else _matched_entries(met))

        passed = sum(f.status == filter_evaluator.PASS for f in filters)
        match = {"candidate_id": row.id, "name": row.name, "match_type": match_type, "must_met": len(met), "must_total": needs,
                 "matched": entries, "text_matches": list(dict.fromkeys(r.text_term for r in met if r.text_term is not None)),
                 "nice_matches": nice_met, "filters": [asdict(f) for f in filters], "filters_passed": passed,
                 "experience": row.experience, "total_years": row.total_years, "qualification": row.qualification,
                 "job_location": row.job_location, "languages": row.languages, "is_meditator": row.is_meditator,
                 "stay_in_ashram": row.stay_in_ashram}
        job_level = _JOB_DIRECT if job_result is not None else _JOB_SAME_DOMAIN if same_domain_job is not None else _JOB_NONE
        score = same_domain_job.score if only_same_domain else sum(r.best_score() for r in met)
        years = ((same_domain_job.years or 0.0) if only_same_domain
                 else sum(y for y in (r.years() for r in met) if y is not None))
        return _Ranked(match, passed, sum(r is not None for r in per_skill), job_level, quality, len(nice_met), score, years)


def _response(message: str | None, q: ParsedQuery, results: list[dict], total: int) -> dict:
    """One page of ranked results. message: shown when the query was unclear or matched no one, None otherwise."""
    return {"message": message, "parsed": q.model_dump(), "results": results, "total": total}


def _years_check(need: Need, result: Result | None) -> FilterCheck | None:
    """"Python experience: 9 years (wanted 8+)" - for a skill or job asked with years ("python developer with 8+ years")."""
    if need.min_years is None:
        return None
    years = None if result is None else result.years()
    status = (filter_evaluator.FAIL if result is None else filter_evaluator.NOT_ON_FILE if years is None
              else filter_evaluator.PASS if years >= need.min_years else filter_evaluator.FAIL)
    return FilterCheck(f"experience:{need.term}", f"{need.term[:1].upper()}{need.term[1:]} experience", status,
                       f"{filter_evaluator.fmt(need.min_years)}+ years",
                       None if years is None else f"{filter_evaluator.fmt(years)} years")


def _entry(name: str, score: int, years: float | None, via_term: str, related: bool) -> dict:
    """via_term: the query term or equivalent that found this entry; related: found via an equivalent."""
    return {"name": name, "score": score, "years": years, "via_term": via_term, "related": related}


def _matched_entries(met: list[Result]) -> list[dict]:
    """One entry per matched area of expertise, strongest first."""
    by_name: dict[str, dict] = {}
    for r in met:
        for h in sorted(r.hits, key=lambda h: -h.score):
            by_name.setdefault(h.name.lower(), _entry(h.name, h.score, h.years, h.term, r.quality == RELATED))
    return sorted(by_name.values(), key=lambda e: -e["score"])


def _match_type(quality: int) -> str:
    return {EXACT: "profile", RELATED: "related"}.get(quality, "text")


def _no_match_message(skills: list[Need], q: ParsedQuery, filter_only: bool) -> str:
    if filter_only:
        return "No candidates match these filters."
    asked = [q.job.term] if q.job is not None else [p for p in (q.role, q.domain) if p is not None]
    asked += [s.term for s in skills]
    listed = "' and '".join(asked)
    location = q.filters.location if q.filters is not None else None
    place = next((p for p in (location.city, location.state, location.country) if p and p.strip()), None) if location else None
    return f"No candidates match '{listed}'" + (f" in {place}" if place else "") + "."
