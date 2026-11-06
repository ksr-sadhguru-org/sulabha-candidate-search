from app.models import Location
from app.search import domains, locations, roles, skill_competencies, terms
from app.search.skill_competencies import Item


def test_normalizes_terms_the_same_way_on_both_sides():
    assert terms.normalize("Teachers") == "teacher"
    assert terms.normalize("B.E.") == "be"
    assert terms.normalize("Sr. Java Developer") == "sr java developer"
    assert terms.normalize("C++ programmers") == "c++ programmer"
    assert terms.normalize(".NET Developer") == ".net developer"
    assert terms.normalize("Node.js") == "node.js"
    assert terms.normalize("yoga classes") == "yoga class"
    assert terms.normalize("Analytics") == "analytics"
    assert terms.normalize("Research & Development") == "research and development"


def test_normalizes_domains_and_roles():
    assert domains.normalize("Software and IT") == "software & it"
    assert domains.normalize(" Carpentry  and Woodwork ") == "carpentry & woodwork"
    assert domains.normalize("  ") is None
    assert roles.normalize("Engineer/Developer") == "engineer / developer"
    assert roles.normalize("Teacher") == "teacher"
    assert roles.normalize("astronaut") is None


def test_matches_whole_trailing_words_only():
    assert terms.matches("music teacher", "teacher")
    assert terms.matches("teacher", "teacher")
    assert not terms.matches("javascript", "java")
    assert not terms.matches("electrical engineer", "electrician")


def test_parses_locations():
    assert locations.parse("Coimbatore, Tamil Nadu, India") == Location(city="Coimbatore", state="Tamil Nadu", country="India")
    assert locations.parse("Dubai, United Arab Emirates") == Location(city="Dubai", country="United Arab Emirates")
    assert locations.parse("Pune") == Location(city="Pune")
    assert locations.format(Location(state="Tamil Nadu", country="India")) == "Tamil Nadu, India"


def test_parses_skill_competencies():
    assert skill_competencies.parse("Java (2 years), Spring Boot (4 years), Git, C++ (<1 year)") == [
        Item("Java", 2.0), Item("Spring Boot", 4.0), Item("Git", None), Item("C++", 0.5)]
