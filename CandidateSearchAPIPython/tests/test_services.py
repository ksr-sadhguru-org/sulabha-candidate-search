from app.db.candidates import Row
from app.models import ApplicantDetails, Entry, Filters, Location, ParsedQuery, Profile
from app.service import applicant_normalization, filter_evaluator, profile_mapper
from app.service.candidate_service import _email_key, _phone_key
from app.service.query_parser import _complete


def _row(**overrides) -> Row:
    fields = dict.fromkeys(Row.__dataclass_fields__)
    return Row(**{**fields, "id": "1", **overrides})


def test_score_curve_by_years():
    assert [profile_mapper.score_for_years(y, None) for y in (0, 1, 2, 3, 6, 10, 20, 30)] == [55, 65, 70, 75, 85, 93, 97, 97]
    assert profile_mapper.score_for_years(None, None) == 55
    assert profile_mapper.score_for_years(3, 1990) == 67  # not used for years: 8 points less


def test_only_jobs_carry_a_role_and_domain():
    job = Entry(name="Music Teacher", kind="profession", role="Teacher", domain="Music")
    assert (profile_mapper.job_role(job), profile_mapper.job_domain(job)) == ("teacher", "music")
    assert profile_mapper.job_role(Entry(name="x", kind="profession", role="astronaut")) == "other"
    assert profile_mapper.job_role(Entry(name="Python", kind="skill", role="teacher")) is None


def test_expertise_rows_from_profile_and_form():
    profile = Profile(total_years=9, entries=[
        Entry(name="Senior Java Developer", kind="profession", role="engineer / developer", domain="Software and IT",
              years=9, terms=["Java Developer", "Spring Boot", "java"]),
        Entry(name="Bachelors", kind="education", score=80, terms=["B.E."])])
    rows = profile_mapper.to_expertise(profile, "Python (2 years), Git")
    assert [(r.name, r.source, r.score, r.years) for r in rows] == [
        ("Senior Java Developer", "resume", 91, 9), ("Bachelors", "resume", 80, None), ("Python", "form", 70, 2.0), ("Git", "form", 91, 9)]
    assert rows[0].terms == ("senior java developer", "java developer", "spring boot", "java")
    assert rows[0].domain == "software & it"


def test_contact_keys():
    assert _email_key("  Priya@Example.COM ") == "priya@example.com"
    assert _phone_key("+91 98220 41567") == _phone_key("9822041567") == "9822041567"
    assert _phone_key("12345") is None


def test_applicant_normalization():
    n = applicant_normalization.normalize(ApplicantDetails(gender="Male", experience="7 ~ 10years", is_meditator="yes", name="A"))
    assert (n.gender, n.experience, n.is_meditator, n.name) == ("male", "7-10", "YES", "A")


def test_filters_pass_fail_and_not_on_file():
    row = _row(city="Pune", state="Maharashtra", country="India", job_location="Pune, Maharashtra, India", languages="English, Tamil",
               total_years=9.0, is_meditator="YES")
    f = Filters(location=Location(city="Pune"), languages=["Tamil", "Hindi"], min_total_years=8, is_meditator="Yes", gender="Female")
    status = {c.field: c.status for c in filter_evaluator.evaluate(f, row)}
    assert status == {"jobLocation": "pass", "languages": "fail", "experience": "pass", "isMeditator": "pass", "gender": "not_on_file"}
    assert filter_evaluator.has_any(f) and not filter_evaluator.has_any(Filters(is_meditator="any"))


def test_experience_range_decides_only_when_it_can():
    check = lambda experience, minimum: filter_evaluator.evaluate(Filters(min_total_years=minimum), _row(experience=experience))[0].status
    assert [check("7-10", 5), check("0-3", 5), check("4-6", 5), check("10+", 5), check(None, 5)] == [
        "pass", "fail", "not_on_file", "pass", "not_on_file"]


def test_query_reading_is_completed():
    q = _complete(ParsedQuery(role="Teacher", domain="Astronomy", must=None, job=None), ["music"])
    assert (q.understood, q.role, q.domain, q.must, q.nice) == (True, "teacher", None, [], [])
    assert _complete(ParsedQuery(role="other"), []).role is None
    broad = ParsedQuery.model_validate({"job": {"term": "software developer", "alternatives": ["developer", "programmer"]}})
    assert _complete(broad, []).job.alternatives == ["programmer"]


def test_skills_without_a_job_drop_role_and_domain():
    java = {"role": "engineer / developer", "domain": "software & it", "must": [{"term": "java"}]}
    q = _complete(ParsedQuery.model_validate(java), ["software & it"])
    assert (q.role, q.domain, [n.term for n in q.must]) == (None, None, ["java"])
    with_job = _complete(ParsedQuery.model_validate({**java, "job": {"term": "java developer"}}), ["software & it"])
    assert (with_job.role, with_job.domain) == ("engineer / developer", "software & it")
    music = _complete(ParsedQuery.model_validate({"domain": "music"}), ["music"])
    assert music.domain == "music"
