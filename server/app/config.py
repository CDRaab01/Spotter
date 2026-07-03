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
    # Seconds to wait for an LM Studio response. The first request after a cold start
    # loads the model before inference, so allow generous headroom. The Android client's
    # read timeout should stay above this so the server's error surfaces to the user.
    lm_studio_timeout: float = 90.0

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

    # Build/deploy stamp surfaced by GET /version so the app can show what's running
    # (and confirm a redeploy landed). Injected at deploy time by deploy/redeploy.*
    # via docker-compose; "unknown" for an unstamped manual/dev run.
    git_sha: str = "unknown"
    built_at: str = "unknown"

    # Cross-app integration (sister app "Plate" reads workout status via GET /workouts).
    # Plate mints a short-lived JWT signed with THIS secret, carrying the user's email; Spotter
    # validates it and resolves its own user by email. Deliberately separate from `secret_key`
    # so a normal Spotter user/refresh token can't reach the cross-app surface (and vice versa).
    # Leave unset to disable /workouts entirely (every call then 401s).
    cross_app_secret: str | None = None

    # Suite SSO (BROKER.md Phase 2b). When suite_jwks_url + suite_issuer are set, POST /auth/suite
    # accepts a suite access token (RS256, from the Dragonfly identity server), validates it
    # against the published JWKS, and trades it for a Spotter session — linking by email. Unset ⇒
    # the endpoint is disabled and the app's own email/password login is unaffected (dual-auth).
    suite_jwks_url: str | None = None
    suite_issuer: str | None = None
    suite_audience: str = "suite"

    # Optional SMTP — if unset, reset codes are printed to stdout instead
    smtp_host: str | None = None
    smtp_port: int = 587
    smtp_user: str | None = None
    smtp_password: str | None = None
    smtp_from: str = "noreply@spotter.local"
    # True = SSL on port 465 (Outlook, Yahoo). False (default) = STARTTLS on port 587 (Gmail).
    smtp_use_ssl: bool = False


settings = Settings()
