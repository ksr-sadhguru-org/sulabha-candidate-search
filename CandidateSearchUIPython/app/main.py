"""Sulabha candidate search - the NiceGUI port of the Angular UI. One page holds the three tabs (kept alive, so Search and Upload keep
their state while you look at another tab, like the Angular app's keep-alive routes); the nav switches tabs and updates the URL."""
import os
from pathlib import Path

from nicegui import ui

from app import deps
from app.admin_page import AdminPage
from app.search_page import SearchPage
from app.upload_page import UploadPage

RECONNECT_TIMEOUT = 3.0  # NiceGUI's default, spelled out so the pages work without ui.run (the simulated-user tests)
TABS = [('search', 'Search'), ('upload', 'Upload Resume'), ('admin', 'Admin')]
CSS = (Path(__file__).parent / 'styles.css').read_text(encoding='utf-8')


def _nav_classes(key: str, active: str) -> str:
    return f"nav-link{' admin-link' if key == 'admin' else ''}{' active' if key == active else ''}"


def build(active: str) -> None:
    ui.colors(primary='#2563eb', negative='#dc2626')
    ui.add_css(CSS)
    nav: dict[str, ui.label] = {}

    async def select(name: str) -> None:
        panels.set_value(name)
        for key, link in nav.items():
            link.classes(replace=_nav_classes(key, name))
        ui.run_javascript(f"history.replaceState(null, '', '/{name}')")
        if name == 'admin':  # not kept alive: the lists are read again on every visit
            await admin.load_labels()

    with ui.element('header').classes('topbar'):
        ui.label('Sulabha').classes('brand')
        with ui.element('nav'):
            for key, title in TABS:
                nav[key] = ui.label(title).classes(_nav_classes(key, active)).on('click', lambda k=key: select(k))
    with ui.element('main').classes('content'):
        panels = ui.tab_panels(value=active, animated=False, keep_alive=True)
        with panels:
            with ui.tab_panel('search'):
                SearchPage(deps.api)
            with ui.tab_panel('upload'):
                UploadPage(deps.api, is_active=lambda: panels.value == 'upload')
            with ui.tab_panel('admin'):
                admin = AdminPage(deps.api)
    ui.timer(0.1, admin.load_labels, once=True)


def _register(name: str) -> None:
    @ui.page(f'/{name}', reconnect_timeout=RECONNECT_TIMEOUT)
    def page() -> None:
        build(name)


for _name, _ in TABS:
    _register(_name)


@ui.page('/', reconnect_timeout=RECONNECT_TIMEOUT)
def home() -> None:
    build('search')


if __name__ in {'__main__', '__mp_main__'}:
    ui.run(title='Sulabha', port=int(os.environ.get('PORT', 4201)), reload=False, show=False)
