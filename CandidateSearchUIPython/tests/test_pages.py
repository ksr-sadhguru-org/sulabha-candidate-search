"""The pages, driven by NiceGUI's simulated user (no browser) against a fake backend."""
import pytest
from nicegui import ui
from nicegui.testing import User

from app import deps
from app.api import ApiError

pytest_plugins = ['nicegui.testing.user_plugin']


def match(candidate_id: str, name: str, **overrides) -> dict:
    return {"candidate_id": candidate_id, "name": name, "match_type": "profile", "must_met": 1, "must_total": 1,
            "matched": [{"name": "Java Developer", "score": 91, "years": 9.0, "via_term": "java", "related": False}],
            "text_matches": [], "nice_matches": [], "filters": [], "filters_passed": 0, "experience": None, "total_years": 9.0,
            "qualification": "Bachelor's", "job_location": "Pune, Maharashtra, India", "languages": "English", "is_meditator": None,
            "stay_in_ashram": None, **overrides}


class FakeApi:
    def __init__(self):
        self.searches: list[tuple[str, int]] = []
        self.matches = [match("id-1", "Priya Menon"), match("id-2", "Rohan Kulkarni")]
        self.fail_search: str | None = None

    async def search(self, query, offset=0, limit=20):
        self.searches.append((query, offset))
        if self.fail_search:
            raise ApiError(self.fail_search)
        return {"message": None, "results": self.matches[offset:offset + limit], "total": len(self.matches)}

    async def get_candidate_detail(self, candidate_id):
        return {"candidate_id": candidate_id, "resume_name": "priya.pdf", "resume_text": "Priya Menon\nQA engineer", "total_years": 9.0,
                "applicant_details": {"name": "Priya Menon", "job_location": "Pune, Maharashtra, India"},
                "expertise": [{"name": "QA Engineer", "kind": "profession", "role": "tester / qa", "domain": "software & it",
                               "source": "resume", "score": 91, "years": 9.0, "terms": ["qa engineer", "java"]}]}

    async def get_domains(self):
        return [{"name": "software & it", "candidates": 12}, {"name": "music", "candidates": 1}]

    async def get_roles(self):
        return ["engineer / developer", "teacher"]

    async def create_all_tables(self):
        return {"message": "Created all tables", "tables": ["candidates"]}


@pytest.fixture
def api(monkeypatch) -> FakeApi:
    fake = FakeApi()
    monkeypatch.setattr(deps, 'api', fake)
    return fake


async def test_search_lists_the_matches(user: User, api: FakeApi):
    await user.open('/search')
    user.find(ui.textarea).type('java developer')
    user.find(kind=ui.button, content='Search').click()
    await user.should_see('2 candidates found')
    await user.should_see('Priya Menon')
    await user.should_see('Rohan Kulkarni')
    await user.should_see('Profile match: Java Developer (score 91, 9 yrs)')
    assert api.searches == [('java developer', 0)]


async def test_search_needs_some_text(user: User, api: FakeApi):
    await user.open('/search')
    user.find(kind=ui.button, content='Search').click()
    await user.should_not_see('candidates found')
    assert api.searches == []


async def test_search_shows_the_backends_message_on_failure(user: User, api: FakeApi, caplog: pytest.LogCaptureFixture):
    api.fail_search = 'Search failed: the AI service did not answer'
    await user.open('/search')
    user.find(ui.textarea).type('anything')
    user.find(kind=ui.button, content='Search').click()
    await user.should_see('Search failed: the AI service did not answer')
    caplog.clear()  # the failure is logged at ERROR on purpose; the user fixture fails a test that leaves ERROR logs


async def test_an_example_fills_the_search_box(user: User, api: FakeApi):
    await user.open('/search')
    user.find(kind=ui.button, content='Java developer in Pune').click()
    user.find(kind=ui.button, content='Search').click()
    await user.should_see('2 candidates found')
    assert api.searches == [('Java developer in Pune', 0)]


async def test_opening_a_card_loads_the_profile_and_the_editor(user: User, api: FakeApi):
    await user.open('/search')
    user.find(ui.textarea).type('qa')
    user.find(kind=ui.button, content='Search').click()
    await user.should_see('Priya Menon')
    user.find(marker='panel-header').click()  # the simulated user does not bubble a label's click up to its card header
    await user.should_see('Search profile')
    await user.should_see('tester / qa')
    await user.should_see('Manual entry (never inferable from a resume)')


async def test_upload_page_asks_for_files(user: User, api: FakeApi):
    await user.open('/upload')
    await user.should_see('Upload Resumes')
    await user.should_see('Resume file(s) (.pdf, .doc, .docx)')
    await user.should_not_see('Save All')


async def test_admin_lists_roles_and_domains(user: User, api: FakeApi):
    await user.open('/admin')
    await user.should_see('Destructive actions')
    await user.should_see('engineer / developer · teacher')
    await user.should_see('software & it')
    await user.should_see('12 candidates')
    await user.should_see('1 candidate')


async def test_admin_create_tables_reports_the_result(user: User, api: FakeApi):
    await user.open('/admin')
    user.find(kind=ui.button, content='Create All Tables').click()
    await user.should_see('Created all tables: candidates')


async def test_destructive_buttons_wait_for_the_phrase(user: User, api: FakeApi):
    await user.open('/admin')
    assert all(not button.enabled for button in user.find(kind=ui.button, content='Clear All Data').elements)
    assert all(not button.enabled for button in user.find(kind=ui.button, content='Drop All Tables').elements)
    user.find(ui.input).type('DELETE ALL DATA')
    assert all(button.enabled for button in user.find(kind=ui.button, content='Clear All Data').elements)
    assert all(button.enabled for button in user.find(kind=ui.button, content='Drop All Tables').elements)
