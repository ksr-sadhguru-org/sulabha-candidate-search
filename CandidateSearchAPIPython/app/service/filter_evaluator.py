"""Checks a candidate's form fields against the query's filters: pass, fail, or not on file."""
import re
from collections.abc import Callable
from dataclasses import dataclass

from app.db.candidates import Row
from app.models import Filters, Location
from app.search import locations
from app.service import applicant_normalization

PASS, FAIL, NOT_ON_FILE = "pass", "fail", "not_on_file"


@dataclass(frozen=True)
class FilterCheck:
    """status: "pass" | "fail" | "not_on_file"."""
    field: str
    label: str
    status: str
    requested: str
    candidate_value: str | None


def has_any(f: Filters | None) -> bool:
    if f is None:
        return False
    values = [locations.format(f.location), "x" if f.languages else None, f.min_total_years, f.nationality, f.gender,
              f.marital_status, f.notice_period, f.max_salary, _yes_no(f.stay_in_ashram), _yes_no(f.done_isha_program),
              _yes_no(f.is_meditator), _yes_no(f.any_kind_job), f.duration_with_isha]
    return any(v is not None and str(v).strip() for v in values)


def evaluate(f: Filters | None, r: Row) -> list[FilterCheck]:
    if f is None:
        return []
    checks = [
        _location(f.location, r),
        _languages(f.languages, r.languages),
        _total_years(f.min_total_years, r),
        _check("nationality", "Nationality", f.nationality, r.nationality, lambda v: f.nationality.lower() in v.lower()),
        _check("gender", "Gender", f.gender, r.gender, lambda v: v.lower() == f.gender.lower()),
        _check("maritalStatus", "Marital status", f.marital_status, r.marital_status, lambda v: v.lower() == f.marital_status.lower()),
        _check("noticePeriod", "Notice period", f.notice_period, r.notice_period,
               lambda v: v.lower() == applicant_normalization.notice_period(f.notice_period).lower()),
        _check("maxSalary", "Salary expected", None if f.max_salary is None else str(f.max_salary),
               None if r.salary_expected is None else str(r.salary_expected), lambda v: r.salary_expected <= f.max_salary),
        _yes_no_check("stayInAshram", "Stay in ashram", f.stay_in_ashram, r.stay_in_ashram),
        _yes_no_check("doneIshaProgram", "Done Isha program", f.done_isha_program, r.done_isha_program),
        _yes_no_check("isMeditator", "Meditator", f.is_meditator, r.is_meditator),
        _yes_no_check("anyKindJob", "Any kind of job", f.any_kind_job, r.any_kind_job),
        _check("durationWithIsha", "Duration with Isha", f.duration_with_isha, r.duration_with_isha,
               lambda v: v.lower() == applicant_normalization.duration_with_isha(f.duration_with_isha).lower())]
    return [c for c in checks if c is not None]


def _location(wanted: Location | None, r: Row) -> FilterCheck | None:
    """Compares at the most specific level asked: city, else state, else country."""
    if wanted is None:
        return None
    for want, have in ((wanted.city, r.city), (wanted.state, r.state), (wanted.country, r.country)):
        if want is not None and want.strip():
            return _check("jobLocation", "Location", locations.format(wanted), r.job_location,
                          lambda v: have.lower() == want.strip().lower() if have is not None else want.strip().lower() in v.lower())
    return None


def _languages(wanted: list[str] | None, have: str | None) -> FilterCheck | None:
    if not wanted:
        return None
    spoken = [] if have is None else [s.strip().lower() for s in re.split(r",|/|;|\band\b", have)]
    return _check("languages", "Languages", ", ".join(wanted), have, lambda v: all(w.strip().lower() in spoken for w in wanted))


def _total_years(minimum: float | None, r: Row) -> FilterCheck | None:
    """Uses the profile's total years; falls back to the form's experience range ("7-10") when that decides it."""
    if minimum is None:
        return None
    requested = f"{fmt(minimum)}+ years"
    if r.total_years is not None:
        return FilterCheck("experience", "Experience", PASS if r.total_years >= minimum else FAIL, requested,
                           f"{fmt(r.total_years)} years")
    numbers = [int(n) for n in re.findall(r"\d+", r.experience or "")]
    status = (NOT_ON_FILE if not numbers else PASS if numbers[0] >= minimum
              else FAIL if len(numbers) > 1 and numbers[-1] < minimum else NOT_ON_FILE)
    return FilterCheck("experience", "Experience", status, requested, r.experience)


def _yes_no_check(field: str, label: str, wanted: str | None, have: str | None) -> FilterCheck | None:
    w = _yes_no(wanted)
    return _check(field, label, w, have, lambda v: v.lower().startswith(w.lower()))


def _yes_no(value: str | None) -> str | None:
    """"Yes"/"No", or None for anything else ("any", blank)."""
    return value if value is not None and value.lower() in ("yes", "no") else None


def _check(field: str, label: str, wanted: str | None, have: str | None, passes: Callable[[str], bool]) -> FilterCheck | None:
    if wanted is None or not wanted.strip():
        return None
    status = NOT_ON_FILE if have is None or not have.strip() else PASS if passes(have) else FAIL
    return FilterCheck(field, label, status, wanted, have)


def fmt(years: float) -> str:
    return str(int(years)) if years == int(years) else str(years)
