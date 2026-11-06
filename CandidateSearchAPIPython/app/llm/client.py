import logging
from typing import Protocol, TypeVar

import httpx
from pydantic import BaseModel

from app.config import Settings
from app.llm import prompts
from app.models import ApplicantDetails, ParsedQuery, Profile
from app.search import roles

log = logging.getLogger(__name__)

M = TypeVar("M", bound=BaseModel)

_ROLES = ", ".join(roles.ALL)


class LlmUnavailableError(Exception):
    """An LLM call a request depends on failed (bad key, endpoint down, unreadable reply) - shown to the user as-is."""


class LlmClient(Protocol):
    """The three LLM calls the app makes. Each returns None when the call fails."""

    def build_profile(self, resume_text: str, verified_skills: str | None, domains: list[str]) -> Profile | None:
        """verified_skills: the reviewed skill competencies field ("Java (2 years), ..."), or blank.
        domains: the current domain list, so jobs are labelled consistently (roles are fixed: roles.ALL)."""

    def parse_query(self, query: str, domains: list[str]) -> ParsedQuery | None:
        """domains: the current domain list - the query's domain is chosen from it."""

    def suggest_applicant_details(self, resume_text: str) -> ApplicantDetails | None:
        """Best-effort form suggestions from resume text alone - a human reviews them before saving."""


class OpenAiLlmClient:
    """Calls an OpenAI-compatible chat.completions endpoint (public OpenAI or Azure OpenAI) in JSON mode."""

    def __init__(self, settings: Settings):
        self.settings = settings
        base_url = settings.openai_base_url.strip() or "https://api.openai.com/v1/"
        self.http = httpx.Client(base_url=base_url if base_url.endswith("/") else base_url + "/", timeout=300,
                                 headers={"Authorization": f"Bearer {settings.openai_api_key}", "Content-Type": "application/json"})

    def build_profile(self, resume_text, verified_skills, domains):
        skills = verified_skills if verified_skills and verified_skills.strip() else "none"
        return self._complete(self.settings.openai_model,
                              prompts.PROFILE % (_ROLES, ", ".join(domains), skills, resume_text), Profile)

    def parse_query(self, query, domains):
        return self._complete(self.settings.openai_query_model, prompts.QUERY % (_ROLES, ", ".join(domains), query), ParsedQuery)

    def suggest_applicant_details(self, resume_text):
        return self._complete(self.settings.openai_model, prompts.SUGGEST % resume_text, ApplicantDetails)

    def _complete(self, model: str, prompt: str, target: type[M]) -> M | None:
        try:
            # temperature 0: extraction, not generation - the same input should give the same output.
            response = self.http.post("chat/completions", json={
                "model": model, "messages": [{"role": "user", "content": prompt}],
                "response_format": {"type": "json_object"}, "temperature": 0})
            response.raise_for_status()
            content = response.json()["choices"][0]["message"]["content"]
            log.info("LLM %s -> %s", target.__name__, content)
            return target.model_validate_json(content)
        except Exception as e:
            log.error("LLM call for %s failed: %s", target.__name__, e, exc_info=True)
            return None
