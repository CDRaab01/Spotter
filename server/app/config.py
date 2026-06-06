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
    # Optional larger model used only for plan/program *generation* turns (a heavier,
    # less frequent request where a slower-but-stronger model is worth the wait).
    # Conversational turns, tweaks, and in-workout advice stay on lm_studio_model.
    # Leave unset to use a single model for everything (identical to prior behaviour).
    lm_studio_plan_model: str | None = None
    # Seconds to wait for an LM Studio response. The first request after a cold start
    # loads the model before inference, so allow generous headroom. The Android client's
    # read timeout should stay above this so the server's error surfaces to the user.
    lm_studio_timeout: float = 90.0
    # Plan/program generation runs on the larger, slower model — give it more headroom.
    # The Android read timeout must stay above this too (see AppModule.kt).
    lm_studio_plan_timeout: float = 180.0

    @property
    def plan_model(self) -> str:
        """Model for plan/program generation; falls back to the chat model when unset."""
        return self.lm_studio_plan_model or self.lm_studio_model

    # Security hardening for public (e.g. Cloudflare Tunnel) multi-user deployments.
    # When set, /auth/register requires a matching invite_code. Leave unset for an open
    # (local/dev or trusted-network) deployment.
    registration_invite_code: str | None = None
    # Trust X-Forwarded-For / CF-Connecting-IP for the rate-limit client key. Only enable
    # behind a trusted reverse proxy (Cloudflare Tunnel, nginx) — otherwise clients can spoof it.
    trust_proxy: bool = False
    # Emit Strict-Transport-Security. Enable only when served over HTTPS (TLS at the proxy/edge).
    hsts_enabled: bool = False
    # Expose the interactive API docs (/docs, /redoc, /openapi.json). Disable on public deploys.
    docs_enabled: bool = True

    # Optional SMTP — if unset, reset codes are printed to stdout instead
    smtp_host: str | None = None
    smtp_port: int = 587
    smtp_user: str | None = None
    smtp_password: str | None = None
    smtp_from: str = "noreply@spotter.local"


settings = Settings()
