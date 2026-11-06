"""Request / LLM / stored shapes. Field names are snake_case (the API's JSON); the camelCase aliases are the LLM's own
shape, also used for the stored profile and query cache. Either spelling is accepted when reading."""
from typing import Annotated

from pydantic import AfterValidator, BaseModel, ConfigDict, Field
from pydantic.alias_generators import to_camel


class _Model(BaseModel):
    model_config = ConfigDict(alias_generator=to_camel, populate_by_name=True, extra="ignore", coerce_numbers_to_str=True)


def _not_blank(value: str) -> str:
    if not value.strip():
        raise ValueError("must not be blank")
    return value


NotBlank = Annotated[str, AfterValidator(_not_blank)]


class ApplicantDetails(_Model):
    """The HR application form. Most fields can be AI-suggested from the resume; the Isha-specific ones
    (stay_in_ashram, is_meditator, ...) are only ever entered by hand."""
    name: str | None = None
    email_from: str | None = None
    phone: str | None = None
    dob: str | None = None
    gender: str | None = None
    marital_status: str | None = None
    address: str | None = None
    qualification: str | None = None
    experience: str | None = None
    skill_competencies: str | None = None
    other_interests: str | None = None
    reason_for_change: str | None = None
    notice_period: str | None = None
    salary_expected: float | None = None
    stay_in_ashram: str | None = None
    any_kind_job: str | None = None
    duration_with_isha: str | None = None
    done_isha_program: str | None = None
    linkedin_profile: str | None = None
    job_id: str | None = None
    nationality: str | None = None
    job_location: str | None = None
    languages: str | None = None
    applicant_programs: str | None = None
    is_meditator: str | None = None


class Entry(_Model):
    """kind: profession | skill | education | language. role / domain: professions only. terms: every phrase a search
    may use that this entry proves. score: education only (by level) - others are scored in code from years."""
    name: str | None = None
    kind: str | None = None
    role: str | None = None
    domain: str | None = None
    score: int | None = None
    years: float | None = None
    last_used_year: int | None = None
    terms: list[str] | None = None


class Profile(_Model):
    """The LLM's search profile of one resume: one entry per area of expertise, plus total years of work."""
    total_years: float | None = None
    entries: list[Entry] | None = None


class Need(_Model):
    """min_years: years of experience tied to this need ("python developer with 8+ years"), else None."""
    term: str | None = None
    alternatives: list[str] | None = None
    min_years: float | None = None


class Location(_Model):
    city: str | None = None
    state: str | None = None
    country: str | None = None


class Filters(_Model):
    location: Location | None = None
    languages: list[str] | None = None
    min_total_years: float | None = None
    nationality: str | None = None
    gender: str | None = None
    marital_status: str | None = None
    notice_period: str | None = None
    max_salary: float | None = None
    stay_in_ashram: str | None = None
    done_isha_program: str | None = None
    is_meditator: str | None = None
    any_kind_job: str | None = None
    duration_with_isha: str | None = None


class ParsedQuery(_Model):
    """The LLM's reading of an HR query: the job asked (role + domain + wording), skills (must), extras (nice), filters."""
    understood: bool | None = None
    role: str | None = None
    domain: str | None = None
    job: Need | None = None
    must: list[Need] | None = None
    nice: list[Need] | None = None
    filters: Filters | None = None


class QueryRequest(BaseModel):
    """offset / limit page through the ranked results (defaults: first 20)."""
    query: NotBlank
    offset: int | None = Field(default=None, ge=0)
    limit: int | None = Field(default=None, ge=1, le=100)

    def offset_or_default(self) -> int:
        return self.offset or 0

    def limit_or_default(self) -> int:
        return 20 if self.limit is None else self.limit


class SuggestRequest(BaseModel):
    resume_text: NotBlank


class UploadRequest(BaseModel):
    candidate_id: NotBlank
    resume_name: NotBlank
    resume_text: NotBlank
    applicant_details: ApplicantDetails | None = None
