"""End-to-end check of the running UI in a visible Chrome window - run by hand, not by pytest (the name has no test_ prefix).

Needs the UI running (default http://localhost:4201), the API it talks to (API_URL, default http://localhost:8001) with a working
LLM key and the test data loaded (`node Tests/run-tests.mjs --fresh`), `pip install playwright` and Chrome. It deletes Sneha Joshi's
record first and uploads her resume again through the UI, so it changes that one candidate and one address field.
A Chrome window opens and drives the app for about a minute - don't click in it.

    python tests/e2e_check.py ../Tests/"Candidate Resumes for Tests" [screenshot_dir]
"""
import os
import pathlib
import sys

import httpx
from playwright.sync_api import expect, sync_playwright

UI = os.environ.get("UI_URL", "http://localhost:4201")
API = os.environ.get("API_URL", "http://localhost:8001")
resumes = pathlib.Path(sys.argv[1])
shots = pathlib.Path(sys.argv[2]) if len(sys.argv) > 2 else None
results: list[bool] = []


def check(name: str, ok: bool, detail="") -> None:
    results.append(ok)
    print(f"{'PASS' if ok else 'FAIL'} {name}{'' if ok else '  -> ' + str(detail)}")


def shot(page, name: str) -> None:
    if shots:
        shots.mkdir(parents=True, exist_ok=True)
        page.screenshot(path=shots / name, full_page=True)


for row in httpx.get(f"{API}/candidates").json():  # start from a known state: Sneha's resume not on file
    if row["resume_name"].startswith("Sneha Joshi"):
        httpx.delete(f"{API}/candidates/{row['candidate_id']}")
before = len(httpx.get(f"{API}/candidates").json())

with sync_playwright() as p:
    browser = p.chromium.launch(channel="chrome", headless=False, slow_mo=120)
    page = browser.new_page(viewport={"width": 1100, "height": 900})
    errors: list[str] = []
    page.on("pageerror", lambda e: errors.append(str(e)))
    page.on("console", lambda m: errors.append(m.text) if m.type == "error" else None)

    # Search: results and Load more ("anyone in india" is a filter-only search: everyone with a location in India)
    page.goto(f"{UI}/search")
    page.get_by_label("Search query").fill("anyone in india")
    page.get_by_role("button", name="Search").click()
    expect(page.get_by_text("candidates found")).to_be_visible(timeout=30000)
    total = int(page.get_by_text("candidates found").inner_text().split()[0])
    check("a page of 20 results", page.locator(".panel-header").count() == min(20, total), page.locator(".panel-header").count())
    shot(page, "a-search-results.png")
    if total > 20:
        page.get_by_role("button", name="Load more").click()
        expect(page.get_by_text("Showing")).to_have_count(0, timeout=30000)
        check("Load more appends the rest", page.locator(".panel-header").count() == total, page.locator(".panel-header").count())

    # Search: open a card, edit a field, save
    page.locator(".panel-header").first.click()
    expect(page.get_by_text("Search profile")).to_be_visible(timeout=30000)
    check("opening a card loads its search profile", True)
    page.get_by_label("Address").first.fill("E2E test address")
    page.get_by_role("button", name="Save").first.click()
    expect(page.get_by_text("Saved as")).to_be_visible(timeout=90000)
    check("saving an edited record works", True)
    shot(page, "b-search-saved.png")

    # Upload: a new resume and one already on file
    page.get_by_text("Upload Resume", exact=True).click()
    expect(page.get_by_text("Upload Resumes")).to_be_visible()
    page.set_input_files("input[type=file]", [str(resumes / "Sneha Joshi - Java Developer.pdf"),
                                              str(resumes / "Aditi Deshpande - French Translator.pdf")])
    expect(page.get_by_text("Already exists")).to_be_visible(timeout=60000)
    check("a resume already on file is flagged", True)
    expect(page.get_by_text("Ready for review")).to_be_visible(timeout=60000)
    check("a new resume is extracted and ready for review", True)
    page.locator(".panel-header", has_text="Sneha Joshi").click()
    name = page.get_by_label("Name", exact=True).first.input_value()
    check("the new resume's form is pre-filled by the AI", name == "Sneha Joshi", name)
    shot(page, "c-upload-editor.png")

    # Tabs keep their state
    page.get_by_text("Search", exact=True).first.click()
    check("Search kept its results", page.locator(".panel-header").count() == total, page.locator(".panel-header").count())
    page.get_by_text("Upload Resume", exact=True).click()
    check("Upload kept its panels", page.locator(".panel-header", has_text="Sneha Joshi").count() == 1)
    page.get_by_role("button", name="Save All").click()
    expect(page.get_by_text("Saved", exact=True)).to_be_visible(timeout=120000)
    check("Save All saves the new resume", True)
    shot(page, "d-upload-saved.png")

    # Admin (nothing destructive is clicked)
    page.get_by_text("Admin", exact=True).click()
    expect(page.get_by_text("Destructive actions", exact=True)).to_be_visible()
    expect(page.get_by_text("software & it")).to_be_visible(timeout=15000)
    check("Admin lists roles and domains", True)
    check("destructive buttons start disabled", page.get_by_role("button", name="Clear All Data").is_disabled())
    page.get_by_placeholder("Type DELETE ALL DATA to confirm").fill("DELETE ALL DATA")
    check("typing the phrase enables them", page.get_by_role("button", name="Clear All Data").is_enabled())
    page.get_by_placeholder("Type DELETE ALL DATA to confirm").fill("")
    page.get_by_role("button", name="Create All Tables").click()
    expect(page.get_by_text("Created all tables")).to_be_visible(timeout=15000)
    check("Create All Tables answers", True)
    shot(page, "e-admin.png")
    browser.close()

after = len(httpx.get(f"{API}/candidates").json())
check("the uploaded candidate is in the database", after == before + 1, f"{before} -> {after}")
print("console errors:", errors or "none")
print(f"{sum(results)}/{len(results)} checks passed")
sys.exit(0 if all(results) else 1)
