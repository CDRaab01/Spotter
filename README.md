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
cp .env.example .env          # edit SECRET_KEY
./run.sh                      # sets up .venv, runs migrations, starts uvicorn
```
`run.sh` honours `HOST`, `PORT`, and `RELOAD` env vars (e.g. `HOST=0.0.0.0 ./run.sh`
to expose on the LAN/tailnet, `RELOAD=1 ./run.sh` for development), and adds
`--proxy-headers` automatically when `.env` has `TRUST_PROXY=true`. To run it manually instead:
```bash
python -m venv .venv && source .venv/bin/activate
pip install -e ".[dev]"
alembic upgrade head
uvicorn app.main:app --reload
```

API docs: http://localhost:8000/docs

### 3. Android client
Open `android/` in Android Studio, sync Gradle, run on emulator or device.

> The emulator reaches the server at `http://10.0.2.2:8000/` (the build-time default).
> For a physical device or a remote server, set the address in-app under **Settings → Server**
> instead of rebuilding. Changing it to a different host signs you out (tokens are per-server).

## Remote access (off your LAN, e.g. phone on 5G)

The server binds to `127.0.0.1:8000` and has no public listener, so a phone on a different
network can't reach it directly. Pick one connectivity layer, then point the app at it via
**Settings → Server** — the same single URL works on wifi and cellular, so there's nothing to
change when you switch networks.

**Tailscale (recommended for personal use — private, no certs, no port forwarding):**
1. Install Tailscale on the server host and the phone; run `tailscale up` on both.
2. Get the host's tailnet IP: `tailscale ip -4` → e.g. `100.x.y.z`.
3. Run the server reachable on the tailnet: `uvicorn app.main:app --host 0.0.0.0 --port 8000`
   (the tailnet is private — this is not public exposure).
4. In the app, set the server URL to `http://100.x.y.z:8000/`.

Tailscale automatically takes the direct LAN path when you're home (full speed) and a WAN path
on 5G — same address throughout, no app-side switching.

**Cloudflare Tunnel (public HTTPS hostname; needs a domain on Cloudflare):**
1. Run `cloudflared` on the host, pointing the tunnel at `http://localhost:8000` (no uvicorn
   bind change needed).
2. In the app, set the server URL to your `https://spotter.<yourdomain>.com/` hostname.
3. Because all traffic then arrives from Cloudflare, enable forwarded-IP handling so rate
   limiting stays per-client: run uvicorn with `--proxy-headers --forwarded-allow-ips='*'`
   (only behind a trusted proxy).

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
