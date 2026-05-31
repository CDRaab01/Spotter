# CLAUDE.md

## Project Overview
A personal fitness app. An Android client connects to a self-hosted server that exposes workout planning, an AI chat assistant, and progress tracking. The AI runs locally via LM Studio and is proxied through the backend — the Android app never talks to the LLM directly.

## Architecture
```
[Android app (Kotlin/Compose)]  <-- HTTPS/REST -->  [FastAPI server]
                                                          |
                                          +---------------+----------------+
                                          |                                |
                                   [Postgres]                   [LM Studio (OpenAI-compatible API)]
```

- **Client:** Kotlin + Jetpack Compose (native Android). Room/SQLite for local cache + offline workout mode.
- **Server:** Python + FastAPI. Owns business logic, auth, and all LLM access.
- **AI:** LM Studio running locally, exposing an OpenAI-compatible endpoint (default `http://localhost:1234/v1`). FastAPI proxies to it; the model is never exposed to the network directly.
- **Database:** Postgres for the source of truth (workouts, sets, weights, reps, calendar). Android Room mirrors a working subset for offline use, syncs on reconnect.

## Tech Stack & Conventions

### Android (Kotlin/Compose)
- MVVM: `ui/` (Composables) → `viewmodel/` → `repository/` → `data/` (Room + Retrofit).
- Retrofit + OkHttp + kotlinx.serialization for the API client.
- Room for local persistence; Repository decides local-vs-remote and handles sync.
- Coroutines + Flow for async/state. No blocking calls on the main thread.
- Use `sealed interface` for UI state (Loading/Success/Error).

### Server (FastAPI)
- Layout: `app/routers/`, `app/services/`, `app/models/` (SQLAlchemy), `app/schemas/` (Pydantic).
- SQLAlchemy 2.0 style + Alembic migrations. Never edit the DB schema by hand.
- All request/response bodies validated with Pydantic; no raw dicts across boundaries.
- Async endpoints; use an async Postgres driver (asyncpg).
- Config via environment variables (`pydantic-settings`), never hardcoded.

## Core Features
1. **AI chat** — conversational workout-plan setup. See AI Guardrails below.
2. **Workout mode** — per-exercise list with a target header (e.g. `8×115lb`, `3×8 BW`). Each set is a tap-to-complete control showing its reps; tapping marks it done (filled vs. dim states). Weight is logged per set beneath it and can differ across sets. Supports a "+" to add sets, bodyweight ("BW") exercises (no weight), a running session timer, per-exercise notes, and an inline edit mode. Must work offline.
3. **Calendar** — view/track scheduled and completed workouts by date.
4. **Progress tracking** — persist weight (bodyweight and/or per-exercise load) and reps over time; expose for charting.

## Data Model (initial)
- `User` — id, name, settings.
- `Exercise` — id, name, muscle_group, equipment.
- `WorkoutPlan` — id, user_id, name, source (manual | ai), created_at.
- `PlannedExercise` — plan_id, exercise_id, target_sets, target_reps, target_weight, is_bodyweight, order.
- `WorkoutSession` — id, user_id, plan_id, date, status, duration_seconds, note.
- `SetLog` — id, session_id, exercise_id, set_number, reps, weight (nullable for bodyweight), completed (bool), completed_at. Each set stores its own reps AND weight — they vary set-to-set (e.g. 7×125, 8×115, 8×115).
- `BodyMetric` — id, user_id, date, weight, (optional bodyfat, etc.).

## AI Guardrails (critical)
The AI assists with workout planning only. The server enforces these — never rely on the client.

- **System prompt** lives server-side in `app/services/ai/prompts.py` and is never client-editable.
- **Scope:** fitness/exercise programming only. Refuse medical diagnosis, nutrition-as-medical-advice, injury treatment, supplements/PEDs dosing. Redirect users to a professional for these.
- **Safety framing:** include a non-medical-advice disclaimer; advise consulting a doctor before new programs; encourage proper form, warmups, and rest.
- **Structured output:** when generating a plan, require the model to return JSON matching the `WorkoutPlan`/`PlannedExercise` schema. Validate with Pydantic before persisting; reject and re-prompt on malformed output.
- **Input handling:** treat user chat as untrusted. Guard against prompt injection (e.g. "ignore previous instructions") — the system prompt and validation layer take precedence.
- **Sanity bounds:** server-side validation caps absurd values (e.g. reps, sets, weight ranges) regardless of what the model returns.
- **No tool/system access:** the LLM proxy has no file, shell, or DB write access; it only returns text/JSON that the server validates and stores.
- Keep the prompt + guardrail logic in one module so it's auditable in isolation.

## API Surface (sketch)
- `POST /auth/...`
- `GET/POST /plans`, `GET /plans/{id}`
- `POST /ai/chat` — proxies to LM Studio, applies guardrails, returns reply (+ optional validated plan)
- `POST /sessions`, `POST /sessions/{id}/sets`
- `GET/POST /metrics/weight`
- `GET /calendar?from=&to=`

## Security
- Auth on every endpoint (token-based); no anonymous access to user data.
- LM Studio bound to localhost; only FastAPI reaches it.
- Secrets in env vars / `.env` (gitignored), never committed.
- HTTPS between Android and server (self-signed/reverse-proxy is fine for personal use, but use TLS).

## Testing
- Server: pytest for routers + the AI guardrail/validation layer (mock the LLM; assert malformed/out-of-bounds output is rejected).
- Android: unit-test ViewModels and the sync/repository logic.

## Conventions for Claude
- Ask before adding new dependencies or changing the architecture above.
- Keep AI guardrail changes isolated and call them out explicitly.
- Prefer migrations over manual schema edits.
- This is a personal-use app, not a medical or clinical product — keep that scope.
