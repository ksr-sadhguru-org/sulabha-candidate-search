from app.models import ApplicantDetails, Entry, Filters, Need, ParsedQuery, Profile

_KEYWORDS = ["java", "python", "spring boot", "django", "plumber", "electrician", "carpenter", "teacher", "accountant", "tally"]


class MockLlmClient:
    """Keyword-based stand-in for the LLM (MOCK_LLM=true), so the pipeline runs without an API key."""

    def build_profile(self, resume_text, verified_skills, domains):
        lower = resume_text.lower()
        return Profile(total_years=3.0, entries=[Entry(name=k, kind="skill", years=3.0, terms=[k]) for k in _KEYWORDS if k in lower])

    def parse_query(self, query, domains):
        lower = query.lower()
        filters = Filters(languages=[], stay_in_ashram="Yes" if "ashram" in lower else None,
                          is_meditator="Yes" if "meditator" in lower else None)
        return ParsedQuery(understood=True, must=[Need(term=k, alternatives=[]) for k in _KEYWORDS if k in lower], nice=[],
                           filters=filters)

    def suggest_applicant_details(self, resume_text):
        return ApplicantDetails()
