"""The backend's REST API (the Python or the Java one - same endpoints), mirroring the Angular ResumeApiService."""
import os

import httpx

API_URL = os.environ.get("API_URL", "http://localhost:8001").rstrip("/")
PAGE_SIZE = 20


class ApiError(Exception):
    """A failed call. `backend_message` is the backend's own explanation ({"message": ...}) when it sent one."""

    def __init__(self, backend_message: str | None):
        super().__init__(backend_message or "no answer from the backend")
        self.backend_message = backend_message


class Api:

    def __init__(self, base_url: str = API_URL):
        self.http = httpx.AsyncClient(base_url=base_url, timeout=300)

    async def _call(self, method: str, path: str, **kwargs):
        try:
            response = await self.http.request(method, path, **kwargs)
        except httpx.HTTPError as e:
            raise ApiError(None) from e
        if response.is_error:
            try:
                message = response.json().get("message")
            except ValueError:
                message = None
            raise ApiError(message)
        return response.json()

    async def extract_text(self, file_name: str, data: bytes) -> dict:
        return await self._call("POST", "/extract_resume_text", files={"file": (file_name, data)})

    async def suggest_applicant_details(self, resume_text: str) -> dict:
        return await self._call("POST", "/suggest_applicant_details", json={"resume_text": resume_text})

    async def upload_resume(self, candidate_id: str, resume_name: str, resume_text: str, applicant_details: dict) -> dict:
        return await self._call("POST", "/upload_resume", json={
            "candidate_id": candidate_id, "resume_name": resume_name, "resume_text": resume_text,
            "applicant_details": applicant_details})

    async def search(self, query: str, offset: int = 0, limit: int = PAGE_SIZE) -> dict:
        """One page of ranked results, starting at offset."""
        return await self._call("POST", "/query", json={"query": query, "offset": offset, "limit": limit})

    async def get_candidate_detail(self, candidate_id: str) -> dict:
        return await self._call("GET", f"/candidates/{candidate_id}")

    async def get_domains(self) -> list[dict]:
        return await self._call("GET", "/domains")

    async def get_roles(self) -> list[str]:
        return await self._call("GET", "/roles")

    async def clear_query_cache(self) -> dict:
        return await self._call("DELETE", "/query_cache")

    async def clear_all_data(self) -> dict:
        return await self._call("DELETE", "/clear_all_data")

    async def drop_all_tables(self) -> dict:
        return await self._call("DELETE", "/drop_all_tables")

    async def create_all_tables(self) -> dict:
        return await self._call("POST", "/create_all_tables", json={})


def backend_message(err: BaseException) -> str | None:
    """The backend's own explanation of an error, when it sent one."""
    return err.backend_message if isinstance(err, ApiError) else None


def unreachable(what: str) -> str:
    return f"{what} - is the backend running at {API_URL}?"
