from collections.abc import Collection
from dataclasses import dataclass

from app.db.connection import Db
from app.models import ApplicantDetails, Location

FORM_COLUMNS = list(ApplicantDetails.model_fields)
OWN_COLUMNS = ["resume_name", "resume_text", "resume_hash", "profile_json", "total_years",
               "email_key", "phone_key", "location_city", "location_state", "location_country"]
ALL_COLUMNS = OWN_COLUMNS + FORM_COLUMNS

_UPSERT = (f"INSERT INTO candidates (id, {', '.join(ALL_COLUMNS)}) VALUES (%(id)s, {', '.join(f'%({c})s' for c in ALL_COLUMNS)}) "
           f"ON CONFLICT (id) DO UPDATE SET {', '.join(f'{c} = EXCLUDED.{c}' for c in ALL_COLUMNS)}, updated_date = NOW()")


@dataclass(frozen=True)
class Row:
    """What search needs per candidate: filter fields and display fields."""
    id: str
    name: str | None
    experience: str | None
    total_years: float | None
    qualification: str | None
    job_location: str | None
    city: str | None
    state: str | None
    country: str | None
    languages: str | None
    nationality: str | None
    gender: str | None
    marital_status: str | None
    notice_period: str | None
    salary_expected: float | None
    stay_in_ashram: str | None
    any_kind_job: str | None
    duration_with_isha: str | None
    done_isha_program: str | None
    is_meditator: str | None


@dataclass(frozen=True)
class Stored:
    resume_name: str | None
    resume_text: str
    profile_json: str | None
    total_years: float | None


@dataclass(frozen=True)
class Record:
    """Everything written for one candidate besides the form."""
    id: str
    resume_name: str
    resume_text: str
    resume_hash: str
    profile_json: str
    total_years: float | None
    email_key: str | None
    phone_key: str | None
    location: Location


class CandidateRepository:
    """The candidates table: resume, fingerprint, form fields and the stored LLM profile."""

    def __init__(self, db: Db):
        self.db = db

    def find_by_hash(self, resume_hash: str) -> dict | None:
        return self.db.row("SELECT id AS candidate_id, resume_name FROM candidates WHERE resume_hash = %(hash)s LIMIT 1",
                           hash=resume_hash)

    def find_stored(self, candidate_id: str) -> Stored | None:
        row = self.db.row("SELECT resume_name, resume_text, profile_json, total_years::float8 AS total_years "
                          "FROM candidates WHERE id = %(id)s", id=candidate_id)
        return row and Stored(**row)

    def find_by_contact(self, email_key: str | None, phone_key: str | None) -> dict[str, str]:
        """id -> name of candidates sharing this email or phone, most recently updated first."""
        rows = self.db.rows("SELECT id, COALESCE(name, '') AS name FROM candidates "
                            "WHERE email_key = %(email)s OR phone_key = %(phone)s ORDER BY updated_date DESC",
                            email=email_key, phone=phone_key)
        return {r["id"]: r["name"] for r in rows}

    def upsert(self, r: Record, form: ApplicantDetails) -> None:
        self.db.execute(_UPSERT, **form.model_dump(), id=r.id, resume_name=r.resume_name, resume_text=r.resume_text,
                        resume_hash=r.resume_hash, profile_json=r.profile_json, total_years=r.total_years,
                        email_key=r.email_key, phone_key=r.phone_key, location_city=r.location.city,
                        location_state=r.location.state, location_country=r.location.country)

    def delete(self, candidate_id: str) -> bool:
        """Removes one candidate; their expertise and terms go with them (ON DELETE CASCADE)."""
        return self.db.execute("DELETE FROM candidates WHERE id = %(id)s", id=candidate_id) > 0

    def find_form(self, candidate_id: str) -> ApplicantDetails | None:
        columns = ", ".join("salary_expected::float8 AS salary_expected" if c == "salary_expected" else c for c in FORM_COLUMNS)
        row = self.db.row(f"SELECT {columns} FROM candidates WHERE id = %(id)s", id=candidate_id)
        return row and ApplicantDetails.model_validate(row)

    def list_all(self) -> list[dict]:
        return self.db.rows("SELECT id AS candidate_id, name, resume_name, job_location FROM candidates ORDER BY name, id")

    def find_rows(self, ids: Collection[str] | None) -> dict[str, Row]:
        """Search rows for these ids, or for every candidate when ids is None."""
        if ids is not None and not ids:
            return {}
        sql = ("SELECT id, name, experience, total_years::float8 AS total_years, qualification, job_location, "
               "location_city AS city, location_state AS state, location_country AS country, languages, nationality, "
               "gender, marital_status, notice_period, salary_expected::float8 AS salary_expected, stay_in_ashram, "
               "any_kind_job, duration_with_isha, done_isha_program, is_meditator FROM candidates")
        rows = self.db.rows(sql) if ids is None else self.db.rows(sql + " WHERE id = ANY(%(ids)s::text[])", ids=list(ids))
        return {r["id"]: Row(**r) for r in rows}
