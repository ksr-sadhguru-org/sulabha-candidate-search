from pydantic_settings import BaseSettings, SettingsConfigDict


class Settings(BaseSettings):
    """Environment / .env settings - the same names the Java API reads."""

    model_config = SettingsConfigDict(env_file=".env", extra="ignore")

    db_host: str = "localhost"
    db_port: int = 5432
    db_name: str = "candidate_db"
    db_user: str = "postgres"
    db_password: str = "password"
    openai_api_key: str = ""
    # Blank for the public OpenAI API, or an Azure OpenAI endpoint (https://<resource>.openai.azure.com/openai/v1/),
    # where model / query model are deployment names.
    openai_base_url: str = ""
    openai_model: str = "gpt-4.1"
    openai_query_model: str = "gpt-4.1"
    mock_llm: bool = False
    cors_origins: str = "http://localhost:4200"

    @property
    def conninfo(self) -> str:
        return (f"host={self.db_host} port={self.db_port} dbname={self.db_name} "
                f"user={self.db_user} password={self.db_password}")
