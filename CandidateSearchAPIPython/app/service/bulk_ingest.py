"""Imports many resumes without a review step: extract, skip duplicates, AI-suggest the form, save."""
import uuid
from concurrent.futures import ThreadPoolExecutor

from app import extraction
from app.llm.client import LlmClient
from app.models import UploadRequest
from app.service.candidate_service import EMPTY_FORM, CandidateService

# Parallel files at once - enough to speed a batch up, few enough to stay under LLM rate limits.
CONCURRENCY = 4


class BulkIngestService:

    def __init__(self, candidates: CandidateService, llm: LlmClient):
        self.candidates = candidates
        self.llm = llm

    def ingest(self, files: list[tuple[str | None, bytes]]) -> list[dict]:
        with ThreadPoolExecutor(max_workers=max(1, min(len(files), CONCURRENCY))) as pool:
            return list(pool.map(lambda f: self._ingest_one(*f), files))

    def _ingest_one(self, name: str | None, data: bytes) -> dict:
        try:
            text = extraction.extract(name, data)
            if not text or not text.strip():
                return _result(name, None, False, extraction.NO_TEXT_MESSAGE)
            if duplicate := self.candidates.find_duplicate(text):
                return _result(name, duplicate["candidate_id"], False, f"Already exists as {duplicate['resume_name']}")
            form = self.llm.suggest_applicant_details(text) or EMPTY_FORM
            saved = self.candidates.save(UploadRequest(candidate_id=str(uuid.uuid4()), resume_name=name, resume_text=text,
                                                       applicant_details=form))
            return _result(name, saved["candidate_id"], saved["status"], saved["message"])
        except Exception as e:
            return _result(name, None, False, f"Could not process this file: {e}")


def _result(resume_name: str | None, candidate_id: str | None, status: bool, message: str) -> dict:
    return {"resume_name": resume_name, "candidate_id": candidate_id, "status": status, "message": message}
