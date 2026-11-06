"""The backend's JSON is used as plain dicts (snake_case) - no mapping layer. These are the shared constants and the
small display helpers for search results."""

APPLICANT_FIELDS = ["name", "email_from", "phone", "dob", "gender", "marital_status", "address", "qualification", "experience",
                    "skill_competencies", "other_interests", "reason_for_change", "notice_period", "salary_expected",
                    "stay_in_ashram", "any_kind_job", "duration_with_isha", "done_isha_program", "linkedin_profile", "job_id",
                    "nationality", "job_location", "languages", "applicant_programs", "is_meditator"]


def empty_applicant_details() -> dict:
    return dict.fromkeys(APPLICANT_FIELDS)


# Human-friendly option labels, matching the backend's ApplicantNormalization known values exactly, so a value picked from
# the list normalizes correctly server-side. Editable comboboxes, so HR can still type a value that isn't in the list.
GENDER = ["Male", "Female"]
MARITAL_STATUS = ["Single", "Married", "Divorced"]
EXPERIENCE = ["0 ~ 3years", "4 ~ 6years", "7 ~ 10years", "10+years"]
NOTICE_PERIOD = ["Immediate", "Less than a Week", "Less than 15 Days", "1 Month", "2 Months", "3 Months", "6 Months",
                 "More than 6 Months"]
STAY_IN_ASHRAM = ["Yes", "No", "No, I would like to work from above stated preferred location"]
ANY_KIND_JOB = ["Yes", "No, only relevant to my Experience", "No, only relevant to my Qualification"]
DURATION_WITH_ISHA = ["Minimum 1 Year", "1 ~ 2 Years", "2 ~ 3 Years", "3 ~ 4 Years", "5+ Years"]
DONE_ISHA_PROGRAM = ["Yes", "No"]
IS_MEDITATOR = ["Yes", "No", "IEO"]

# (label, field, options) - options None = free text; "number" = numeric.
FROM_RESUME_FIELDS = [
    ("Name", "name", None), ("Email", "email_from", None), ("Phone", "phone", None),
    ("Qualification", "qualification", None), ("Experience", "experience", EXPERIENCE), ("Nationality", "nationality", None),
    ("Location (City, State, Country)", "job_location", None), ("Languages", "languages", None),
    ("LinkedIn", "linkedin_profile", None), ("Skill competencies", "skill_competencies", None),
    ("Other interests", "other_interests", None), ("Gender", "gender", GENDER),
    ("Marital status", "marital_status", MARITAL_STATUS), ("Date of birth", "dob", None)]
MANUAL_FIELDS = [
    ("Address", "address", None), ("Notice period", "notice_period", NOTICE_PERIOD), ("Salary expected", "salary_expected", "number"),
    ("Reason for change", "reason_for_change", None), ("Stay in ashram", "stay_in_ashram", STAY_IN_ASHRAM),
    ("Open to any kind of job", "any_kind_job", ANY_KIND_JOB), ("Duration with Isha", "duration_with_isha", DURATION_WITH_ISHA),
    ("Done Isha program", "done_isha_program", DONE_ISHA_PROGRAM), ("Is meditator", "is_meditator", IS_MEDITATOR),
    ("Isha programs attended", "applicant_programs", None), ("Job ID", "job_id", None)]

EXAMPLES = ["Java developer in Pune", "Music teacher who is an Isha meditator",
            "Python developer with 8+ years experience in Coimbatore", "Candidate who is willing to stay in ashram"]

_STATUS_MARK = {"pass": "✓", "fail": "✗", "not_on_file": "?"}
_MATCH_LABEL = {"profile": "Profile match", "related": "Related match", "text": "Resume text match", "filters": ""}
_MATCH_CLASS = {"profile": "chip", "related": "badge badge-keyword-related", "text": "badge badge-keyword",
                "partial": "badge badge-keyword-partial", "domain": "badge badge-keyword-related", "filters": ""}
_MATCH_TOOLTIP = {
    "profile": "Every requirement is in this candidate's search profile; score 0-100 from relevant years of experience",
    "related": 'Found through an equivalent term (e.g. "tutor" for "teacher") or only through a degree',
    "text": "Not in the search profile, but the resume text mentions it",
    "partial": "Meets only some of the requirements - ranked below candidates who meet them all",
    "domain": "A different role in the same domain as the job asked (e.g. a performer for a music teacher search) - ranked last",
    "filters": ""}


def years(value: float) -> str:
    return f"{int(value) if float(value).is_integer() else round(value, 1)} yr{'' if value == 1 else 's'}"


def format_entries(entries: list[dict]) -> str:
    """"senior java developer (score 91, 9 yrs)" per matched area of expertise."""
    return ", ".join(f"{e['name']} (score {e['score']}{'' if e['years'] is None else ', ' + years(e['years'])})" for e in entries)


def match_label(match: dict) -> str:
    if match["match_type"] == "domain":
        return f"Related match ({(match['matched'][0]['via_term'] if match['matched'] else None) or 'same domain'})"
    if match["match_type"] == "partial":
        return f"Partial match ({match['must_met']} of {match['must_total']})"
    return _MATCH_LABEL[match["match_type"]]


def match_class(match: dict) -> str:
    return _MATCH_CLASS[match["match_type"]]


def match_tooltip(match: dict) -> str:
    return _MATCH_TOOLTIP[match["match_type"]]


def filter_badge_class(match: dict) -> str:
    if match["filters_passed"] == len(match["filters"]):
        return "badge-match"
    return "badge-nomatch" if match["filters_passed"] == 0 else "badge-partial"


def filter_badge_label(match: dict) -> str:
    total = len(match["filters"])
    return "Filters" if match["filters_passed"] in (total, 0) else f"Filters ({match['filters_passed']}/{total})"


def filter_badge_detail(match: dict) -> str:
    """"✓ Location: Coimbatore, Tamil Nadu, India, ✗ Meditator: not on file (wanted Yes)\""""
    parts = []
    for f in match["filters"]:
        wanted = "" if f["status"] == "pass" or not f["requested"] else f" (wanted {f['requested']})"
        parts.append(f"{_STATUS_MARK[f['status']]} {f['label']}: {f['candidate_value'] or 'not on file'}{wanted}")
    return ", ".join(parts)
