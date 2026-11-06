import logging

from nicegui import ui

from app.api import Api, unreachable
from app.components import tag

log = logging.getLogger(__name__)

CONFIRM_PHRASE = 'DELETE ALL DATA'


class AdminPage:
    """The Admin tab: destructive actions for local testing / demo use, and the role and domain lists."""

    def __init__(self, api: Api):
        self.api = api
        self.confirm_text = ''
        self.busy: set[str] = set()

        tag('h1', 'Admin')
        ui.label('Destructive actions for local testing/demo use only.').classes('hint').style('margin: 1rem 0')

        with ui.element('div').classes('card'):
            tag('h2', 'Create All Tables')
            ui.label('Safe, idempotent - creates any missing tables. Does nothing if they already exist.').classes('hint')
            self.create_button = ui.button('Create All Tables', on_click=lambda: self._run(
                'create', self.create_button, 'Creating…', 'Create All Tables', self.api.create_all_tables,
                lambda r: f"{r['message']}: {', '.join(r.get('tables') or [])}", 'Failed to create tables')).props('no-caps unelevated')

        with ui.element('div').classes('card'):
            tag('h2', 'Clear Query Cache')
            ui.label('Safe - forgets how past searches were read, so the next searches are read afresh by the AI. '
                     'Candidates are kept.').classes('hint')
            self.cache_button = ui.button('Clear Query Cache', on_click=lambda: self._run(
                'cache', self.cache_button, 'Clearing…', 'Clear Query Cache', self.api.clear_query_cache,
                lambda r: f"{r['message']} ({r['cleared_queries']} saved queries)", 'Failed to clear the query cache')) \
                .props('no-caps unelevated')

        with ui.element('div').classes('card'):
            tag('h2', 'Roles and domains')
            ui.label('Every job is labelled with a role - the kind of work, from this fixed list:').classes('hint')
            self.roles_label = ui.label().style('margin: 0.5rem 0')
            ui.label('and a domain - its subject. The domain list grows when a resume needs a new one; '
                     'Clear All Data resets it to the starter list.').classes('hint')
            self.domains_box = ui.element('ul').classes('fields')
            ui.button('Refresh', on_click=self.load_labels).props('flat dense no-caps').classes('link')

        with ui.element('div').classes('card danger'):
            tag('h2', 'Destructive actions')
            ui.label(f'Type {CONFIRM_PHRASE} below to enable the buttons.').classes('hint')
            ui.input(placeholder=f'Type {CONFIRM_PHRASE} to confirm', on_change=self._confirm_changed).props('dense outlined') \
                .classes('w-full').style('margin: 1rem 0')
            with ui.element('div').classes('admin-actions'):
                with ui.element('div'):
                    self.clear_button = ui.button('Clear All Data', color='negative', on_click=lambda: self._run(
                        'clear', self.clear_button, 'Clearing…', 'Clear All Data', self.api.clear_all_data,
                        lambda r: f"{r['message']}: {', '.join(r.get('cleared_tables') or [])}", 'Failed to clear data', reset_confirm=True)) \
                        .props('no-caps unelevated')
                    ui.label('Recommended. Empties every table. Schema is never touched.').classes('hint')
                with ui.element('div'):
                    self.drop_button = ui.button('Drop All Tables', color='negative', on_click=lambda: self._run(
                        'drop', self.drop_button, 'Dropping…', 'Drop All Tables', self.api.drop_all_tables,
                        lambda r: f"{r['message']}: {', '.join(r.get('dropped_tables') or [])}", 'Failed to drop tables', reset_confirm=True)) \
                        .props('no-caps unelevated')
                    ui.label("Matches the backend's raw endpoint exactly - also removes the table schema. Click \"Create All Tables\" "
                             'above afterward to rebuild it.').classes('hint')

        self.error = ui.label().classes('error')
        self.success = ui.label().classes('success')
        self._show(None, None)
        self._sync_destructive()

    async def load_labels(self) -> None:
        """Jobs are labelled with a role (fixed list) and a domain (grows) - duplicate domains stand out here."""
        try:
            domains, roles = await self.api.get_domains(), await self.api.get_roles()
        except Exception as e:
            log.error('Loading roles and domains failed: %s', e)
            return
        self.roles_label.set_text(' · '.join(roles))
        self.domains_box.clear()
        with self.domains_box:
            for domain in domains:
                with ui.element('li'):
                    ui.label(domain['name'])
                    ui.label(f"{domain['candidates']} candidate{'' if domain['candidates'] == 1 else 's'}").classes('hint')

    def _confirm_changed(self, e) -> None:
        self.confirm_text = (e.value or '').strip()
        self._sync_destructive()

    def _sync_destructive(self) -> None:
        confirmed = self.confirm_text == CONFIRM_PHRASE
        self.clear_button.set_enabled(confirmed and 'clear' not in self.busy)
        self.drop_button.set_enabled(confirmed and 'drop' not in self.busy)

    def _show(self, success: str | None, error: str | None) -> None:
        for label, text in ((self.success, success), (self.error, error)):
            label.set_text(text or '')
            label.set_visibility(bool(text))

    async def _run(self, key: str, button: ui.button, busy_text: str, idle_text: str, action, success, failure: str,
                   reset_confirm: bool = False) -> None:
        self.busy.add(key)
        button.set_text(busy_text)
        button.set_enabled(False)
        self._show(None, None)
        try:
            self._show(success(await action()), None)
            if reset_confirm:
                self.confirm_text = ''
            await self.load_labels()
        except Exception as e:
            log.error('%s: %s', failure, e)
            self._show(None, unreachable(failure))
        finally:
            self.busy.discard(key)
            button.set_text(idle_text)
            button.set_enabled(key in ('create', 'cache') or self.confirm_text == CONFIRM_PHRASE)
