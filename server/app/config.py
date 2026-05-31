from pydantic_settings import BaseSettings, SettingsConfigDict


class Settings(BaseSettings):
    model_config = SettingsConfigDict(env_file=".env", extra="ignore")

    database_url: str
    secret_key: str
    algorithm: str = "HS256"
    access_token_expire_minutes: int = 30
    refresh_token_expire_days: int = 7
    lm_studio_base_url: str = "http://localhost:1234/v1"
    lm_studio_model: str = "lmstudio-community/Meta-Llama-3-8B-Instruct-GGUF"


settings = Settings()
