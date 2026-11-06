"""Every error reaches the UI as {"message": "..."} it can show as-is."""
import logging

from fastapi import FastAPI, Request
from fastapi.exceptions import RequestValidationError
from fastapi.responses import JSONResponse
from starlette.exceptions import HTTPException

from app.extraction import UnsupportedFileTypeError
from app.llm.client import LlmUnavailableError

log = logging.getLogger(__name__)


def _body(status: int, message: str | None) -> JSONResponse:
    return JSONResponse({"message": message or "Error"}, status_code=status)


def register(app: FastAPI) -> None:
    @app.exception_handler(HTTPException)
    async def http_error(request: Request, e: HTTPException):
        return _body(e.status_code, str(e.detail))

    @app.exception_handler(RequestValidationError)
    async def invalid_request(request: Request, e: RequestValidationError):
        problems = "; ".join("the body is not valid JSON" if err["type"] == "json_invalid"
                             else f"{err['loc'][-1]}: {str(err['msg']).removeprefix('Value error, ')}" for err in e.errors())
        return _body(400, f"Invalid request - {problems}")

    @app.exception_handler(LlmUnavailableError)
    async def llm_unavailable(request: Request, e: LlmUnavailableError):
        return _body(502, str(e))

    @app.exception_handler(UnsupportedFileTypeError)
    async def unsupported_file_type(request: Request, e: UnsupportedFileTypeError):
        return _body(400, str(e))

    @app.exception_handler(Exception)
    async def other(request: Request, e: Exception):
        log.error("Request failed: %s", e, exc_info=e)
        return _body(500, f"Something went wrong: {e}")
