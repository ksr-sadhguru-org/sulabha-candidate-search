"""Normalizes free-form applicant field values to standardized DB values."""
from app.models import ApplicantDetails

_GENDER = {"Male": "male", "Female": "female"}
_MARITAL_STATUS = {"Single": "single", "Married": "married", "Divorced": "divorced"}
_EXPERIENCE = {"0 ~ 3years": "0-3", "4 ~ 6years": "4-6", "7 ~ 10years": "7-10", "10+years": "10+"}
_NOTICE_PERIOD = {"Immediate": "immediate", "Less than a Week": "less_than_a_week", "Less than 15 Days": "less_than_15_days",
                  "1 Month": "1month", "2 Months": "2months", "3 Months": "3months", "6 Months": "6months",
                  "More than 6 Months": "more_than_6_months"}
_STAY_IN_ASHRAM = {"Yes": "yes", "No": "no", "No, I would like to work from above stated preferred location": "no"}
_ANY_KIND_JOB = {"Yes": "yes", "No, only relevant to my Experience": "no-exp", "No, only relevant to my Qualification": "no-qual"}
_DURATION_WITH_ISHA = {"Minimum 1 Year": "minimum-1", "1 ~ 2 Years": "1~2", "2 ~ 3 Years": "2~3", "3 ~ 4 Years": "3~4",
                       "5+ Years": "5+"}
_DONE_ISHA_PROGRAM = {"Yes": "yes", "No": "no"}
_IS_MEDITATOR = {"yes": "YES", "no": "NO", "ieo": "IEO", "YES": "YES", "NO": "NO", "IEO": "IEO"}

_BY_FIELD = {"gender": _GENDER, "marital_status": _MARITAL_STATUS, "experience": _EXPERIENCE, "notice_period": _NOTICE_PERIOD,
             "stay_in_ashram": _STAY_IN_ASHRAM, "any_kind_job": _ANY_KIND_JOB, "duration_with_isha": _DURATION_WITH_ISHA,
             "done_isha_program": _DONE_ISHA_PROGRAM, "is_meditator": _IS_MEDITATOR}


def normalize(d: ApplicantDetails) -> ApplicantDetails:
    return d.model_copy(update={field: _map_or_keep(getattr(d, field), mapping) for field, mapping in _BY_FIELD.items()})


def duration_with_isha(value: str | None) -> str | None:
    """The stored form of a query's duration value, so filters compare like with like."""
    return _map_or_keep(value, _DURATION_WITH_ISHA)


def notice_period(value: str | None) -> str | None:
    return _map_or_keep(value, _NOTICE_PERIOD)


def _map_or_keep(value: str | None, mapping: dict[str, str]) -> str | None:
    return None if value is None else mapping.get(value, value)
