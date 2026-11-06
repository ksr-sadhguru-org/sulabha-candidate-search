import logging
from contextlib import asynccontextmanager

from fastapi import FastAPI
from fastapi.middleware.cors import CORSMiddleware

from app.config import Settings
from app.db.candidates import CandidateRepository
from app.db.connection import Db
from app.db.expertise import ExpertiseRepository
from app.db.lookups import DomainRepository, QueryCacheRepository, TextSearchRepository
from app.db.schema import SchemaService
from app.llm.client import OpenAiLlmClient
from app.llm.mock import MockLlmClient
from app.service.bulk_ingest import BulkIngestService
from app.service.candidate_service import CandidateService
from app.service.need_matcher import NeedMatcher
from app.service.query_parser import QueryParser
from app.service.search_service import SearchService
from app.web import errors
from app.web.routes import Services, build_router

logging.basicConfig(level=logging.INFO, format="%(asctime)s %(levelname)s %(name)s - %(message)s")


def create_app(settings: Settings | None = None) -> FastAPI:
    s = settings or Settings()
    db = Db(s.conninfo)
    llm = MockLlmClient() if s.mock_llm else OpenAiLlmClient(s)
    candidates, expertise = CandidateRepository(db), ExpertiseRepository(db)
    domains, query_cache = DomainRepository(db), QueryCacheRepository(db)
    candidate_service = CandidateService(db, candidates, expertise, domains, llm)
    search = SearchService(QueryParser(llm, query_cache, domains), NeedMatcher(expertise, TextSearchRepository(db)),
                           candidates, expertise)
    services = Services(db, llm, candidate_service, BulkIngestService(candidate_service, llm), search, domains, query_cache,
                        SchemaService(db))

    @asynccontextmanager
    async def lifespan(app: FastAPI):
        db.open()
        services.schema.ensure_tables_exist()
        yield
        db.close()

    app = FastAPI(title="Candidate Search API", lifespan=lifespan)
    app.add_middleware(CORSMiddleware, allow_origins=[o.strip() for o in s.cors_origins.split(",")],
                       allow_methods=["GET", "POST", "DELETE", "PUT"], allow_headers=["*"])
    errors.register(app)
    app.include_router(build_router(services))
    return app


app = create_app()
