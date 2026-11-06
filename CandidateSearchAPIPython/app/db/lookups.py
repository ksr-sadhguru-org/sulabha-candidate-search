from app.db.connection import Db


class DomainRepository:
    """The growing list of domains (the subject side of a job: music, software & it, plumbing, ...)."""

    def __init__(self, db: Db):
        self.db = db

    def names(self) -> list[str]:
        return self.db.column("SELECT name FROM domains ORDER BY name")

    def add(self, name: str) -> None:
        """Adds a new (already normalized) domain; an existing one is left as is."""
        self.db.execute("INSERT INTO domains (name) VALUES (%(name)s) ON CONFLICT DO NOTHING", name=name)

    def counts(self) -> list[dict]:
        """Each domain and how many candidates have a job in it."""
        return self.db.rows("SELECT d.name, COUNT(DISTINCT e.candidate_id)::int AS candidates "
                            "FROM domains d LEFT JOIN expertise e ON e.domain = d.name "
                            "GROUP BY d.name ORDER BY candidates DESC, d.name")


class QueryCacheRepository:
    """Parsed form of each query, keyed by the normalized query text."""

    def __init__(self, db: Db):
        self.db = db

    def find(self, key: str) -> str | None:
        return self.db.scalar("SELECT parsed_json FROM query_cache WHERE query_key = %(key)s", key=key)

    def save(self, key: str, parsed_json: str) -> None:
        self.db.execute("INSERT INTO query_cache (query_key, parsed_json) VALUES (%(key)s, %(json)s) "
                        "ON CONFLICT (query_key) DO NOTHING", key=key, json=parsed_json)

    def clear(self) -> int:
        """Forgets every saved query reading (e.g. after a query prompt change); candidates are untouched."""
        return self.db.execute("DELETE FROM query_cache")


class TextSearchRepository:
    """Full-text fallback over resume text (Postgres english stemming: "teachers" finds "teacher")."""

    def __init__(self, db: Db):
        self.db = db

    def find_containing(self, phrase: str) -> set[str]:
        """Candidates whose resume contains this phrase, words in order."""
        return set(self.db.column("SELECT id FROM candidates WHERE search_tsv @@ phraseto_tsquery('english', %(phrase)s)",
                                  phrase=phrase))
