import asyncio
import logging
import uuid
from dataclasses import dataclass, field

from nicegui import ui

from app import models
from app.api import Api, backend_message
from app.components import ExpandablePanel, RecordEditor, tag

log = logging.getLogger(__name__)


@dataclass
class _Panel:
    candidate_id: str
    file_name: str
    view: ExpandablePanel
    name_label: ui.label
    body: ui.element
    details: dict = field(default_factory=models.empty_applicant_details)
    extracted_text: str = ''
    extracting: bool = True
    error: str | None = None
    duplicate_of: dict | None = None
    save_error: str | None = None
    saved: bool = False
    dirty: bool = False
    editor: RecordEditor | None = None

    def state(self) -> str:
        """Where the panel is in the extract -> review -> save lifecycle (also the colour of its file name)."""
        if self.extracting:
            return 'extracting'
        if self.error:
            return 'error'
        if self.duplicate_of:
            return 'duplicate'
        if self.save_error:
            return 'save-error'
        if self.dirty:
            return 'dirty'
        return 'saved' if self.saved else 'ready'


class UploadPage:
    """The Upload tab. One resume or a batch work the same way: pick file(s), each gets its own collapsible panel that extracts
    the text and asks the LLM to suggest the applicant details in the background, then shows a RecordEditor to review. Each panel
    has its own Save; "Save All" saves every panel, waiting for any still extracting rather than silently skipping them."""

    def __init__(self, api: Api, is_active=lambda: True):
        self.api = api
        self.is_active = is_active
        self.panels: dict[str, _Panel] = {}
        # Every extraction started so far, including settled ones - Save All awaits them all.
        self.pending: list[asyncio.Task] = []
        self.saving_all = False
        self.waiting_for_extraction = False

        tag('h1', 'Upload Resumes')
        ui.label('Pick one or more PDF, DOC or DOCX files. Each gets its own panel below - text is extracted and applicant details '
                 'are AI-suggested automatically, then you can review, edit and save.').classes('hint').style('margin: 1rem 0')
        with ui.element('div').classes('card'):
            self.upload = ui.upload(label='Resume file(s) (.pdf, .doc, .docx)', multiple=True, auto_upload=True,
                                    on_multi_upload=self._files_selected).props('accept=".pdf,.doc,.docx" flat bordered').classes('w-full')
        self.save_row = ui.element('div').classes('save-all-btn')
        with self.save_row:
            self.save_all_button = ui.button('Save All', on_click=self.save_all).props('no-caps unelevated')
            ui.label('Ctrl+Enter to save all').classes('hint shortcut-hint')
        self.panels_box = ui.element('div')
        ui.keyboard(on_key=self._key)
        self._sync_save_all()

    async def _files_selected(self, e) -> None:
        files = [(f.name, await f.read()) for f in e.files]
        self.upload.reset()
        for name, data in files:
            self._add_panel(name, data)

    def _add_panel(self, file_name: str, data: bytes) -> None:
        candidate_id = str(uuid.uuid4())
        with self.panels_box:
            view = ExpandablePanel(on_toggle=lambda: self._toggle(candidate_id))
        with view.title:
            name_label = ui.label(file_name).classes('panel-name state-extracting')
        with view.body:
            body = ui.element('div')
            with body:
                ui.label('Extracting text and suggesting applicant details…').classes('hint')
        panel = _Panel(candidate_id, file_name, view, name_label, body)
        self.panels[candidate_id] = panel
        self._refresh(panel)
        self._sync_save_all()
        self.pending.append(asyncio.create_task(self._extract_and_suggest(panel, data)))

    async def _extract_and_suggest(self, panel: _Panel, data: bytes) -> None:
        try:
            extracted = await self.api.extract_text(panel.file_name, data)
            panel.extracted_text = extracted['extracted_text']
            if extracted['duplicate_of']:
                # Already have this exact resume content on file - skip the AI suggestion rather than pay for a candidate we won't save.
                panel.duplicate_of = extracted['duplicate_of']
            else:
                try:
                    panel.details = await self.api.suggest_applicant_details(panel.extracted_text)
                except Exception as e:
                    log.warning('Could not fetch applicant-detail suggestions - form stays blank/manual: %s', e)
        except Exception as e:
            log.error('Extraction failed: %s', e)
            panel.error = backend_message(e) or 'Could not extract text from this file - only .pdf, .doc and .docx are supported.'
        panel.extracting = False
        if panel.candidate_id in self.panels:
            self._fill_body(panel)
            self._refresh(panel)

    def _fill_body(self, panel: _Panel) -> None:
        panel.body.clear()
        with panel.body:
            if panel.error:
                ui.label(panel.error).classes('error')
            elif panel.duplicate_of:
                ui.label(f"This exact resume content is already saved as {panel.duplicate_of['resume_name']} "
                         f"({panel.duplicate_of['candidate_id']}). Remove this panel, or use Search to find and edit the existing record.") \
                    .classes('hint')
            else:
                panel.editor = RecordEditor(
                    self.api, panel.candidate_id, id_editable=True, resume_name=panel.file_name, resume_text=panel.extracted_text,
                    details=panel.details, on_saved=lambda _response: self._saved(panel),
                    on_dirty_change=lambda dirty: self._dirty_changed(panel, dirty),
                    on_save_failed=lambda message: self._save_failed(panel, message))

    def _refresh(self, panel: _Panel) -> None:
        state = panel.state()
        panel.name_label.classes(replace=f'panel-name state-{state}')
        panel.view.actions.clear()
        with panel.view.actions:
            if state == 'extracting':
                ui.label('Extracting…').classes('hint')
            elif state == 'ready':
                ui.label('Ready for review').classes('hint')
            elif state == 'dirty':
                ui.label('Unsaved changes').classes('hint')
            elif state == 'saved':
                ui.label('Saved').classes('badge badge-match')
            elif state == 'duplicate':
                ui.label('Already exists').classes('badge badge-duplicate').tooltip(f"Already saved as {panel.duplicate_of['resume_name']}")
            elif state == 'save-error':
                ui.label('Save failed').classes('error').tooltip(panel.save_error)
            # click.stop: removing a panel must not also toggle it
            ui.button('Remove').props('flat dense no-caps').classes('link').on('click.stop', lambda: self._remove(panel.candidate_id))

    def _saved(self, panel: _Panel) -> None:
        panel.saved, panel.save_error = True, None
        self._refresh(panel)

    def _dirty_changed(self, panel: _Panel, dirty: bool) -> None:
        panel.dirty = dirty
        self._refresh(panel)

    def _save_failed(self, panel: _Panel, message: str) -> None:
        panel.save_error = message
        self._refresh(panel)

    def _toggle(self, candidate_id: str) -> None:
        view = self.panels[candidate_id].view
        view.set_expanded(not view.expanded)

    def _remove(self, candidate_id: str) -> None:
        self.panels.pop(candidate_id).view.card.delete()
        self._sync_save_all()

    def _sync_save_all(self) -> None:
        self.save_row.set_visibility(bool(self.panels))
        busy = self.saving_all or self.waiting_for_extraction
        self.save_all_button.set_enabled(not busy)
        self.save_all_button.set_text('Waiting for extraction…' if self.waiting_for_extraction else 'Saving All…' if self.saving_all else 'Save All')

    async def _key(self, e) -> None:
        """Ctrl+Enter on the page saves everything; inside a panel's fields it saves just that panel (the editor handles that)."""
        if e.action.keydown and e.key.enter and e.modifiers.ctrl and self.is_active() and self.panels \
                and not (self.saving_all or self.waiting_for_extraction):
            await self.save_all()

    async def save_all(self) -> None:
        """Saves every panel that's ready and not already saved. Panels still extracting when this is called are waited for - not
        silently skipped. A repeat click only retries panels that failed or changed since - it doesn't re-upload saved ones."""
        if self.pending:
            self.waiting_for_extraction = True
            self._sync_save_all()
            try:
                await asyncio.gather(*self.pending, return_exceptions=True)
            finally:
                self.waiting_for_extraction = False
        self.saving_all = True
        self._sync_save_all()
        try:
            editors = [p.editor for p in self.panels.values() if p.editor is not None and (not p.saved or p.dirty)]
            await asyncio.gather(*(editor.save() for editor in editors))
        finally:
            self.saving_all = False
            self._sync_save_all()
