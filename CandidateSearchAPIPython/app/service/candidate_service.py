"""Saving a candidate: pick the record to write (new, same id, or the same person by email/phone), build the search
profile with the LLM, then store candidate + profile in one transaction."""
import logging
import re
from typing import NamedTuple

from app.db.candidates import CandidateRepository, Record
from app.db.connection import Db
from app.db.expertise import ExpertiseRepository
from app.db.lookups import DomainRepository
from app.llm.client import LlmClient
from app.models import ApplicantDetails, Profile, UploadRequest
from app.search import locations
from app.service import applicant_normalization, profile_mapper
from app.util import sha256_hex

log = logging.getLogger(__name__)

EMPTY_FORM = ApplicantDetails()


class _Target(NamedTuple):
    id: str
    updated_existing: bool
    existing_name: str | None = None


class CandidateService:

    def __init__(self, db: Db, candidates: CandidateRepository, expertise: ExpertiseRepository, domains: DomainRepository,
                 llm: LlmClient):
        self.db = db
        self.candidates = candidates
        self.expertise = expertise
        self.domains = domains
        self.llm = llm

    def save(self, request: UploadRequest) -> dict:
        try:
            form = applicant_normalization.normalize(request.applicant_details or EMPTY_FORM)
            target = self._resolve_target(request.candidate_id, form)
            profile = self._reusable_profile(target.id, request.resume_text) or self.llm.build_profile(
                request.resume_text, form.skill_competencies, self.domains.names())
            if profile is None:
                return _response(False, "The AI service could not read this resume - nothing was saved.", request.candidate_id, False)
            record = Record(target.id, request.resume_name, request.resume_text, sha256_hex(request.resume_text),
                            profile.model_dump_json(by_alias=True), profile.total_years, _email_key(form.email_from),
                            _phone_key(form.phone), locations.parse(form.job_location))
            rows = profile_mapper.to_expertise(profile, form.skill_competencies)
            with self.db.transaction():
                # A domain the AI named that isn't on the list yet joins it, so later resumes and queries can use it.
                for domain in dict.fromkeys(r.domain for r in rows if r.domain is not None):
                    self.domains.add(domain)
                self.candidates.upsert(record, form)
                self.expertise.replace(target.id, rows)
            message = (f"Updated the existing candidate {target.existing_name} (same email or phone)"
                       if target.updated_existing else "Saved")
            return _response(True, message, target.id, target.updated_existing)
        except Exception as e:
            log.error("Saving candidate %s failed: %s", request.candidate_id, e, exc_info=True)
            return _response(False, f"Saving failed: {e}", request.candidate_id, False)

    def find_duplicate(self, resume_text: str | None) -> dict | None:
        return self.candidates.find_by_hash(sha256_hex(resume_text)) if resume_text and resume_text.strip() else None

    def detail(self, candidate_id: str) -> dict | None:
        s = self.candidates.find_stored(candidate_id)
        return s and {"candidate_id": candidate_id, "resume_name": s.resume_name, "resume_text": s.resume_text,
                      "total_years": s.total_years,
                      "applicant_details": (self.candidates.find_form(candidate_id) or EMPTY_FORM).model_dump(),
                      "expertise": self.expertise.find_by_candidate(candidate_id)}

    def delete(self, candidate_id: str) -> bool:
        return self.candidates.delete(candidate_id)

    def list_all(self) -> list[dict]:
        return self.candidates.list_all()

    def _resolve_target(self, requested_id: str, form: ApplicantDetails) -> _Target:
        """The same id updates that record; otherwise a matching email or phone - with a compatible name - means the same
        person sent an updated resume, so their existing record is updated instead of creating a second one."""
        if self.candidates.find_stored(requested_id) is not None:
            return _Target(requested_id, False)
        email, phone = _email_key(form.email_from), _phone_key(form.phone)
        if email is None and phone is None:
            return _Target(requested_id, False)
        same_person = next(((i, name) for i, name in self.candidates.find_by_contact(email, phone).items()
                            if _same_first_name(name, form.name)), None)
        return _Target(same_person[0], True, same_person[1]) if same_person else _Target(requested_id, False)

    def _reusable_profile(self, candidate_id: str, resume_text: str) -> Profile | None:
        """Resume text unchanged since the last save (e.g. only form fields edited): reuse its profile, no LLM call."""
        stored = self.candidates.find_stored(candidate_id)
        if stored is None or stored.resume_text != resume_text or stored.profile_json is None:
            return None
        try:
            return Profile.model_validate_json(stored.profile_json)
        except ValueError:
            return None


def _response(status: bool, message: str, candidate_id: str, updated_existing: bool) -> dict:
    """candidate_id is the record actually written - an existing candidate's id when the upload was recognized as an updated
    resume of someone already on file (updated_existing)."""
    return {"status": status, "message": message, "candidate_id": candidate_id, "updated_existing": updated_existing}


def _same_first_name(stored: str | None, incoming: str | None) -> bool:
    if not stored or not stored.strip() or not incoming or not incoming.strip():
        return True
    return _first_word(stored) == _first_word(incoming)


def _first_word(name: str) -> str:
    return re.split(r"\s+", name.strip())[0].lower()


def _email_key(email: str | None) -> str | None:
    return None if email is None or not email.strip() else email.strip().lower()


def _phone_key(phone: str | None) -> str | None:
    """Last 10 digits, so "+91 98220 41567" and "9822041567" agree."""
    digits = re.sub(r"\D", "", phone or "")
    return None if len(digits) < 7 else digits[-10:]
