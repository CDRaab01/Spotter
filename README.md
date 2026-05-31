# Spotter

A personal fitness app. An Android client connects to a self-hosted FastAPI server that exposes workout planning, an AI chat assistant, and progress tracking.

See [CLAUDE.md](./CLAUDE.md) for full architecture and conventions.

## Quick Start

### 1. Start Postgres
```bash
docker-compose up -d
```

### 2. Start the server
```bash
cd server
python -m venv .venv && source .venv/bin/activate
pip install -e ".[dev]"
cp .env.example .env          # edit SECRET_KEY
alembic upgrade head
uvicorn app.main:app --reload
```

API docs: http://localhost:8000/docs

### 3. Android client
Open `android/` in Android Studio, sync Gradle, run on emulator or device.

> The emulator reaches the server at `http://10.0.2.2:8000/`.  
> For a physical device, update `BASE_URL` in `di/AppModule.kt` to your machine's LAN IP.

## API Surface

| Method | Path | Description |
|--------|------|-------------|
| POST | `/auth/register` | Create account |
| POST | `/auth/login` | Get tokens |
| POST | `/auth/refresh` | Refresh access token |
| GET/POST | `/plans` | List / create workout plans |
| GET | `/plans/{id}` | Get a single plan |
| POST | `/sessions` | Start a workout session |
| GET | `/sessions/{id}` | Get session with set logs |
| POST | `/sessions/{id}/sets` | Log a set |
| GET/POST | `/metrics/weight` | Body-weight metrics |
| GET | `/calendar` | Sessions in a date range |
| POST | `/ai/chat` | Chat with the AI coach |
| GET | `/health` | Server health check |
