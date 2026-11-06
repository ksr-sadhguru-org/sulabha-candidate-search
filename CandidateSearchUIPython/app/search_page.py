import logging

from nicegui import ui

from app import models
from app.api import Api, backend_message, unreachable
from app.components import ExpandablePanel, RecordEditor, tag

log = logging.getLogger(__name__)


class SearchPage:
    """The Search tab. Its state lives as long as the browser tab (the tabs are kept alive), so leaving Search and coming back
    keeps the last results, like the Angular app's search state service."""

    def __init__(self, api: Api):
        self.api = api
        self.query = ''
        self.searched_query = ''  # the query the shown results belong to - Load more continues it even if the box was edited
        self.results: list[dict] | None = None
        self.total = 0
        self.loading = False
        self.loading_more = False
        self.expanded_id: str | None = None
        self.panels: dict[str, ExpandablePanel] = {}
        self.detail_boxes: dict[str, ui.element] = {}
        self.profile_boxes: dict[str, ui.element] = {}
        self.details: dict[str, dict] = {}

        tag('h1', 'Search Candidates')
        ui.label("Describe who you're looking for in plain language - role, skills, experience, location, anything relevant.") \
            .classes('hint').style('margin: 1rem 0')
        with ui.element('div').classes('card'):
            self.box = ui.textarea('Search query', placeholder='e.g. Python developer with 8+ years experience in Coimbatore who is also an Isha meditator',
                                   on_change=self._query_changed).props('outlined input-style="height: 4rem; min-height: 4rem; resize: vertical"').classes('w-full')
            self.box.on('keydown.ctrl.enter', self.search)
            with ui.element('div').style('margin-top: 0.75rem'):
                self.search_button = ui.button('Search', on_click=self.search).props('no-caps unelevated')
                ui.label('Ctrl+Enter to search').classes('hint shortcut-hint')
            with ui.element('div').classes('examples'):
                ui.label('Try:').classes('hint')
                for example in models.EXAMPLES:
                    ui.button(example, on_click=lambda e=example: self.box.set_value(e)).props('flat no-caps dense').classes('example')
            self.error = ui.label().classes('error')
            self.error.set_visibility(False)
        self.results_box = ui.element('div')
        self._sync_button()

    def _query_changed(self, e) -> None:
        self.query = e.value or ''
        self._sync_button()

    def _sync_button(self) -> None:
        self.search_button.set_enabled(not self.loading and bool(self.query.strip()))
        self.search_button.set_text('Searching…' if self.loading else 'Search')

    def _show_error(self, message: str | None) -> None:
        self.error.set_text(message or '')
        self.error.set_visibility(bool(message))

    async def search(self) -> None:
        query = self.query.strip()
        if not query or self.loading:
            return
        self.loading = True
        self._sync_button()
        self._show_error(None)
        self.expanded_id = None
        try:
            response = await self.api.search(query)
            self.searched_query = query
            self.results, self.total = response['results'], response['total']
            self._render(response['message'])
        except Exception as e:
            log.error('Search failed: %s', e)
            self.results = None
            self._show_error(backend_message(e) or unreachable('Search failed'))
            self.results_box.clear()
        finally:
            self.loading = False
            self._sync_button()

    async def load_more(self) -> None:
        """Appends the next page of the current search."""
        shown = self.results or []
        self.loading_more = True
        self._update_footer()
        self._show_error(None)
        try:
            response = await self.api.search(self.searched_query, len(shown))
            self.results = [*shown, *response['results']]
            self.total = response['total']
            with self.cards:
                for match in response['results']:
                    self._add_card(match)
        except Exception as e:
            log.error('Load more failed: %s', e)
            self._show_error(backend_message(e) or unreachable('Could not load more results'))
        finally:
            self.loading_more = False
            self._update_footer()

    def _render(self, message: str | None) -> None:
        self.results_box.clear()
        self.panels.clear()
        self.detail_boxes.clear()
        self.profile_boxes.clear()
        with self.results_box:
            self.count = tag('h2')
            if message:
                ui.label(message).classes('search-message')
            self.cards = ui.element('div')
            self.footer = ui.element('div')
            for match in self.results or []:
                with self.cards:
                    self._add_card(match)
        self._update_footer()

    def _update_footer(self) -> None:
        self.count.clear()
        self.count.set_visibility(bool(self.results))
        if self.results:
            with self.count:
                ui.label(f"{self.total} candidate{'' if self.total == 1 else 's'} found")
        self.footer.clear()
        shown = len(self.results or [])
        if shown < self.total:
            with self.footer:
                button = ui.button('Loading…' if self.loading_more else 'Load more', on_click=self.load_more) \
                    .props('no-caps unelevated').classes('load-more')
                button.set_enabled(not self.loading_more)
                ui.label(f'Showing {shown} of {self.total}').classes('hint shortcut-hint')

    def _add_card(self, match: dict) -> None:
        candidate_id = match['candidate_id']
        panel = ExpandablePanel(on_toggle=lambda c=candidate_id: self.toggle(c))
        self.panels[candidate_id] = panel
        with panel.title:
            ui.label(match['name'] or candidate_id).classes('result-name')
        if match['name']:
            with panel.actions:
                ui.label(candidate_id).classes('candidate-id')
        with panel.summary:
            self._badges(match)
            self._facts(match)
        with panel.body:
            self.detail_boxes[candidate_id] = ui.element('div').classes('detail-panel')

    @staticmethod
    def _badges(match: dict) -> None:
        with ui.element('div').classes('badges-row'):
            if match['match_type'] != 'filters':
                found = models.format_entries(match['matched']) if match['matched'] else ''
                text = ', '.join(part for part in (found, ', '.join(match['text_matches'])) if part)
                ui.label(f"{models.match_label(match)}: {text}").classes(models.match_class(match)).tooltip(models.match_tooltip(match))
            if match['nice_matches']:
                ui.label(f"Also: {', '.join(match['nice_matches'])}").classes('badge badge-keyword') \
                    .tooltip('Extras from the query this candidate also has - they rank higher')
            if match['filters']:
                ui.label(f"{models.filter_badge_label(match)}: {models.filter_badge_detail(match)}") \
                    .classes(f"badge {models.filter_badge_class(match)}")

    @staticmethod
    def _facts(match: dict) -> None:
        facts = [('Experience', models.years(match['total_years']) if match['total_years'] is not None else match['experience']),
                 ('Qualification', match['qualification']), ('Location', match['job_location']),
                 ('Languages', match['languages']), ('Meditator', match['is_meditator']), ('Stay in ashram', match['stay_in_ashram'])]
        with tag('dl', classes='details'):
            for label, value in facts:
                if value:
                    with ui.element('div'):
                        tag('dt', label)
                        tag('dd', value)

    async def toggle(self, candidate_id: str) -> None:
        if self.expanded_id == candidate_id:
            self.panels[candidate_id].set_expanded(False)
            self.expanded_id = None
            return
        if self.expanded_id in self.panels:
            self.panels[self.expanded_id].set_expanded(False)
        self.expanded_id = candidate_id
        self.panels[candidate_id].set_expanded(True)
        box = self.detail_boxes[candidate_id]
        if candidate_id not in self.details:
            box.clear()
            with box:
                ui.label('Loading…')
            try:
                self.details[candidate_id] = await self.api.get_candidate_detail(candidate_id)
            except Exception as e:
                log.error('Failed to load candidate detail: %s', e)
                box.clear()
                with box:
                    ui.label(backend_message(e) or unreachable('Could not load the full record')).classes('error')
                return
            self._render_detail(candidate_id)

    def _render_detail(self, candidate_id: str) -> None:
        detail = self.details[candidate_id]
        box = self.detail_boxes[candidate_id]
        box.clear()
        with box:
            tag('h3', 'Search profile')
            self.profile_boxes[candidate_id] = ui.element('div')
            with self.profile_boxes[candidate_id]:
                self._profile_table(detail['expertise'])
            RecordEditor(self.api, candidate_id, id_editable=False, resume_name=detail['resume_name'],
                         resume_text=detail['resume_text'], details=detail['applicant_details'],
                         on_saved=lambda _response: self._reload_detail(candidate_id))

    @staticmethod
    def _profile_table(expertise: list[dict]) -> None:
        with ui.element('table').classes('profile-table'):
            with ui.element('thead'), ui.element('tr'):
                for heading in ('Area of expertise', 'Role', 'Domain', 'From', 'Score', 'Years', 'Found by'):
                    tag('th', heading)
            with ui.element('tbody'):
                for e in expertise:
                    with ui.element('tr'):
                        for value, classes in ((e['name'], ''), (e['role'] or '', ''), (e['domain'] or '', ''),
                                               ('form' if e['source'] == 'form' else 'resume', ''), (str(e['score']), ''),
                                               ('' if e['years'] is None else str(e['years']), ''), (', '.join(e['terms']), 'terms')):
                            tag('td', value, classes)

    async def _reload_detail(self, candidate_id: str) -> None:
        """After a save from the open record: fetch it again so the profile table shows the new search profile."""
        try:
            self.details[candidate_id] = await self.api.get_candidate_detail(candidate_id)
        except Exception as e:
            log.error('Failed to reload candidate detail: %s', e)
            return
        # Only the profile table changes; the editor keeps what was typed.
        profile = self.profile_boxes[candidate_id]
        profile.clear()
        with profile:
            self._profile_table(self.details[candidate_id]['expertise'])
