import inspect
import logging

from nicegui import ui

from app.api import Api, backend_message, unreachable
from app.models import FROM_RESUME_FIELDS, MANUAL_FIELDS, empty_applicant_details

log = logging.getLogger(__name__)


async def call(callback, *args):
    """Runs a callback that may be plain or async."""
    if callback is not None:
        result = callback(*args)
        if inspect.isawaitable(result):
            await result


def tag(name: str, text: str | None = None, classes: str = ''):
    """A plain HTML element (dl, th, td, ...) holding a text label."""
    element = ui.element(name).classes(classes)
    if text is not None:
        with element:
            ui.label(text)
    return element


class ExpandablePanel:
    """A card with a clickable header that expands and collapses its body - shared by the Upload panels and the Search results
    so both behave the same. Slots: `title` and `actions` in the header, `summary` always visible under it, `body` below. The
    body stays rendered while collapsed (hidden, not removed), so Upload's "Save All" can still reach every editor."""

    def __init__(self, expanded: bool = False, on_toggle=None):
        self.expanded = expanded
        self.on_toggle = on_toggle
        self.card = ui.element('div').classes('card panel')
        with self.card:
            self.header = ui.element('div').classes('panel-header').props('role=button tabindex=0').mark('panel-header')
            self.header.on('click', self._clicked)
            self.header.on('keydown.enter', self._clicked)
            with self.header:
                self.title = ui.element('span')
                with ui.element('span').classes('panel-header-right'):
                    self.actions = ui.element('span').classes('panel-actions')
                    self.chevron = ui.label('▾' if expanded else '▸').classes('chevron')
            self.summary = ui.element('div')
            self.body = ui.element('div')
            self.body.set_visibility(expanded)

    async def _clicked(self):
        await call(self.on_toggle)

    def set_expanded(self, expanded: bool) -> None:
        self.expanded = expanded
        self.body.set_visibility(expanded)
        self.chevron.set_text('▾' if expanded else '▸')


class RecordEditor:
    """Editable resume text + applicant details, with its own Save button - reused for the Upload panels and for editing an
    already-saved candidate from the search results. The caller supplies the initial values; this owns the save call and its
    own loading / success / error state, and tells the caller about it through the callbacks."""

    def __init__(self, api: Api, candidate_id: str, *, id_editable: bool = True, resume_name: str = '', resume_text: str = '',
                 details: dict | None = None, on_saved=None, on_dirty_change=None, on_save_failed=None):
        self.api = api
        self.editable_id = candidate_id
        self.resume_name = resume_name
        self.resume_text = resume_text
        self.details = {**empty_applicant_details(), **(details or {})}
        self.on_saved, self.on_dirty_change, self.on_save_failed = on_saved, on_dirty_change, on_save_failed
        self.dirty = False
        self.submitting = False
        with ui.element('div').classes('record-editor') as self.root:
            fields = ui.element('div')
            # Ctrl+Enter in any field saves - and stops here, so it doesn't also trigger a page-level "Save All".
            fields.on('keydown.ctrl.enter.stop.prevent', self.save_from_shortcut)
            with fields:
                if id_editable:
                    self._text_input('Candidate ID', self.editable_id, 'editable_id')
                else:
                    ui.label('Candidate ID').classes('hint')
                    ui.label(candidate_id).classes('readonly-id')
                self._text_input('Resume name', self.resume_name, 'resume_name')
                ui.textarea('Resume text', value=self.resume_text, on_change=self._resume_text_changed) \
                    .props('outlined input-style="height: 20rem; resize: vertical"').classes('w-full resume-text-input')
                self._section('From the resume (AI-suggested - please verify)', FROM_RESUME_FIELDS)
                self._section('Manual entry (never inferable from a resume)', MANUAL_FIELDS)
            with ui.element('div'):
                self.save_button = ui.button('Save', on_click=self.save).props('no-caps unelevated')
                ui.label('Ctrl+Enter to save').classes('hint shortcut-hint')
            self.error = ui.label().classes('error')
            self.success = ui.label().classes('success')
        self.error.set_visibility(False)
        self.success.set_visibility(False)
        self._sync_button()

    def _section(self, title: str, specs) -> None:
        with ui.element('h3'):
            ui.label(title)
        with ui.element('div').classes('form-grid'):
            for label, field, kind in specs:
                value = self.details[field]
                if kind == 'number':
                    ui.number(label, value=value, on_change=lambda e, f=field: self._set_detail(f, e.value)) \
                        .props('dense outlined').classes('w-full')
                elif kind is None:
                    ui.input(label, value=value or '', on_change=lambda e, f=field: self._set_detail(f, e.value)) \
                        .props('dense outlined').classes('w-full')
                else:
                    options = kind if value in (None, *kind) else [*kind, value]
                    ui.select(options, label=label, value=value, with_input=True, new_value_mode='add-unique', clearable=True,
                              on_change=lambda e, f=field: self._set_detail(f, e.value)) \
                        .props('dense outlined').classes('w-full')

    def _text_input(self, label: str, value: str, attribute: str) -> None:
        ui.input(label, value=value, on_change=lambda e: self._set_attribute(attribute, e.value)) \
            .props('dense outlined').classes('w-full')

    def _set_attribute(self, attribute: str, value: str) -> None:
        setattr(self, attribute, value)
        self._changed()

    def _set_detail(self, field: str, value) -> None:
        self.details[field] = value if value not in ('', None) else None
        self._changed()

    def _resume_text_changed(self, e) -> None:
        self.resume_text = e.value or ''
        self._sync_button()
        self._changed()

    def _changed(self) -> None:
        """Any edit to any field marks the record as changed since it was loaded or last saved."""
        if not self.dirty:
            self.dirty = True
            if self.on_dirty_change:
                self.on_dirty_change(True)

    def _sync_button(self) -> None:
        self.save_button.set_enabled(not self.submitting and bool(self.resume_text.strip()))
        self.save_button.set_text('Saving…' if self.submitting else 'Save')

    async def save_from_shortcut(self) -> None:
        if not self.submitting and self.resume_text.strip():
            await self.save()

    async def save(self) -> bool:
        """Public so the Upload page's "Save All" can save every panel - guards against blank resume text itself, since a
        bulk caller bypasses the Save button's disabled state."""
        if not self.resume_text.strip():
            return self._fail('Resume text is required - this file may not have extracted any text (e.g. a scanned/image-only document).')
        self.submitting = True
        self._sync_button()
        self.error.set_visibility(False)
        self.success.set_visibility(False)
        try:
            response = await self.api.upload_resume(self.editable_id, self.resume_name, self.resume_text, self.details)
            if not response['status']:
                return self._fail(response['message'])
            self.success.set_text(response['message'] if response['updated_existing'] else f"Saved as {response['candidate_id']}")
            self.success.set_visibility(True)
            self.dirty = False
            if self.on_dirty_change:
                self.on_dirty_change(False)
            await call(self.on_saved, response)
            return True
        except Exception as e:
            log.error('Save failed: %s', e)
            return self._fail(backend_message(e) or unreachable('Save failed'))
        finally:
            self.submitting = False
            self._sync_button()

    def _fail(self, message: str) -> bool:
        self.error.set_text(message)
        self.error.set_visibility(True)
        if self.on_save_failed:
            self.on_save_failed(message)
        return False
