import json

import httpx
import pytest

from app import models
from app.api import Api, ApiError, backend_message


def _match(**overrides) -> dict:
    return {"candidate_id": "1", "name": "A", "match_type": "profile", "must_met": 1, "must_total": 1, "matched": [],
            "text_matches": [], "nice_matches": [], "filters": [], "filters_passed": 0, **overrides}


def test_years_are_shown_like_the_angular_app():
    assert [models.years(v) for v in (1, 9, 9.5, 0.5, 0)] == ["1 yr", "9 yrs", "9.5 yrs", "0.5 yrs", "0 yrs"]


def test_matched_entries_list_score_and_years():
    entries = [{"name": "Music Teacher", "score": 93, "years": 10.0}, {"name": "Tamil", "score": 55, "years": None}]
    assert models.format_entries(entries) == "Music Teacher (score 93, 10 yrs), Tamil (score 55)"


def test_match_labels():
    assert models.match_label(_match(match_type="partial", must_met=1, must_total=3)) == "Partial match (1 of 3)"
    assert models.match_label(_match(match_type="domain", matched=[{"via_term": "same domain: music"}])) == "Related match (same domain: music)"
    assert models.match_label(_match()) == "Profile match"
    assert models.match_class(_match(match_type="profile")) == "chip"


def test_filter_badge_summarises_the_checks():
    filters = [{"status": "pass", "label": "Location", "requested": "Pune", "candidate_value": "Pune, India"},
               {"status": "not_on_file", "label": "Meditator", "requested": "Yes", "candidate_value": None}]
    match = _match(match_type="filters", filters=filters, filters_passed=1)
    assert models.filter_badge_label(match) == "Filters (1/2)"
    assert models.filter_badge_class(match) == "badge-partial"
    assert models.filter_badge_detail(match) == "✓ Location: Pune, India, ? Meditator: not on file (wanted Yes)"
    assert models.filter_badge_class(_match(filters=filters, filters_passed=0)) == "badge-nomatch"


def test_empty_details_cover_every_form_field():
    assert list(models.empty_applicant_details()) == models.APPLICANT_FIELDS
    assert sorted(f for _, f, _ in models.FROM_RESUME_FIELDS + models.MANUAL_FIELDS) == sorted(models.APPLICANT_FIELDS)


def _api(handler) -> Api:
    api = Api("http://backend")
    api.http = httpx.AsyncClient(base_url="http://backend", transport=httpx.MockTransport(handler))
    return api


async def test_api_returns_the_json():
    api = _api(lambda request: httpx.Response(200, json={"total": 0, "results": [], "message": None}))
    assert (await api.search("java"))["total"] == 0


async def test_api_error_carries_the_backends_message():
    api = _api(lambda request: httpx.Response(502, json={"message": "The AI service could not suggest form values"}))
    with pytest.raises(ApiError) as caught:
        await api.suggest_applicant_details("text")
    assert backend_message(caught.value) == "The AI service could not suggest form values"


async def test_unreachable_backend_has_no_message():
    def refuse(request):
        raise httpx.ConnectError("refused")

    with pytest.raises(ApiError) as caught:
        await _api(refuse).get_roles()
    assert backend_message(caught.value) is None


async def test_search_sends_offset_and_limit():
    seen = []

    def handler(request):
        seen.append(json.loads(request.content))
        return httpx.Response(200, json={"results": [], "total": 0, "message": None})

    await _api(handler).search("java", 20)
    assert seen == [{"query": "java", "offset": 20, "limit": 20}]
