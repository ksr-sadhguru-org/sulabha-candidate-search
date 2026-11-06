from dataclasses import dataclass
from typing import Annotated

from fastapi import APIRouter, File, HTTPException, UploadFile
from fastapi.responses import JSONResponse

from app import extraction
from app.db.connection import Db
from app.db.lookups import DomainRepository, QueryCacheRepository
from app.db.schema import TABLES, SchemaService
from app.llm.client import LlmClient
from app.models import ApplicantDetails, QueryRequest, SuggestRequest, UploadRequest
from app.search import roles
from app.service.bulk_ingest import BulkIngestService
from app.service.candidate_service import CandidateService
from app.service.search_service import SearchService

MAX_FILE_BYTES = 10 * 1024 * 1024


@dataclass
class Services:
    db: Db
    llm: LlmClient
    candidates: CandidateService
    bulk: BulkIngestService
    search: SearchService
    domains: DomainRepository
    query_cache: QueryCacheRepository
    schema: SchemaService


def _read(upload: UploadFile) -> bytes:
    data = upload.file.read()
    if len(data) > MAX_FILE_BYTES:
        raise HTTPException(413, f"{upload.filename} is larger than 10 MB")
    return data


def build_router(c: Services) -> APIRouter:
    router = APIRouter()

    @router.get("/actuator/health")
    def health():
        try:
            c.db.scalar("SELECT 1")
        except Exception:
            return JSONResponse({"status": "DOWN"}, status_code=503)
        return {"status": "UP"}

    @router.post("/extract_resume_text")
    def extract_resume_text(file: UploadFile):
        """Text of a PDF/DOC/DOCX for review, flagged when the same resume text is already on file."""
        text = extraction.extract(file.filename, _read(file))
        if not text or not text.strip():
            raise HTTPException(400, extraction.NO_TEXT_MESSAGE)
        return {"resume_name": file.filename, "extracted_text": text, "duplicate_of": c.candidates.find_duplicate(text)}

    @router.post("/suggest_applicant_details")
    def suggest_applicant_details(request: SuggestRequest):
        details = c.llm.suggest_applicant_details(request.resume_text)
        if details is None:
            raise HTTPException(502, "The AI service could not suggest form values")
        return details.model_dump()

    @router.post("/upload_resume")
    def upload_resume(request: UploadRequest):
        return c.candidates.save(request)

    @router.post("/upload_resumes_bulk")
    def upload_resumes_bulk(files: Annotated[list[UploadFile], File()]):
        return {"results": c.bulk.ingest([(f.filename, _read(f)) for f in files])}

    @router.get("/candidates")
    def list_candidates():
        return c.candidates.list_all()

    @router.get("/candidates/{candidate_id}")
    def candidate_detail(candidate_id: str):
        detail = c.candidates.detail(candidate_id)
        if detail is None:
            raise HTTPException(404, f"No candidate {candidate_id}")
        return detail

    @router.delete("/candidates/{candidate_id}")
    def delete_candidate(candidate_id: str):
        if not c.candidates.delete(candidate_id):
            raise HTTPException(404, f"No candidate {candidate_id}")
        return {"message": f"Deleted candidate {candidate_id}"}

    @router.post("/query")
    def query(request: QueryRequest):
        return c.search.search(request.query, request.offset_or_default(), request.limit_or_default())

    @router.get("/domains")
    def domains():
        """The domain list jobs are labelled with (it grows), and how many candidates are in each."""
        return c.domains.counts()

    @router.get("/roles")
    def list_roles():
        """The fixed role list jobs are labelled with."""
        return roles.ALL

    @router.delete("/query_cache")
    def clear_query_cache():
        """Forgets saved query readings only - use after a query prompt change; candidates are kept."""
        return {"message": "Cleared the query cache", "cleared_queries": c.query_cache.clear()}

    @router.delete("/clear_all_data")
    def clear_all_data():
        return {"message": "Cleared all data", "cleared_tables": c.schema.clear_all_data()}

    @router.delete("/drop_all_tables")
    def drop_all_tables():
        return {"message": "Dropped all tables", "dropped_tables": c.schema.drop_all_tables()}

    @router.post("/create_all_tables")
    def create_all_tables():
        c.schema.ensure_tables_exist()
        return {"message": "Created all tables", "tables": TABLES}

    return router
