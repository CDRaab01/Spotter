from fastapi import FastAPI, Request, Response
from fastapi.middleware.cors import CORSMiddleware
from slowapi import _rate_limit_exceeded_handler
from slowapi.errors import RateLimitExceeded

from app.limiter import limiter
from app.routers import ai, auth, calendar, exercises, metrics, plans, progress, sessions, users

app = FastAPI(title="Spotter API", version="0.1.0")
app.state.limiter = limiter
app.add_exception_handler(RateLimitExceeded, _rate_limit_exceeded_handler)

# CORS: credentials=True is incompatible with wildcard origins (browser spec) and
# unnecessary for the Android client which uses Authorization Bearer headers.
app.add_middleware(
    CORSMiddleware,
    allow_origins=["*"],
    allow_credentials=False,
    allow_methods=["*"],
    allow_headers=["*"],
)


@app.middleware("http")
async def security_headers(request: Request, call_next) -> Response:
    response = await call_next(request)
    response.headers["X-Content-Type-Options"] = "nosniff"
    response.headers["X-Frame-Options"] = "DENY"
    response.headers["X-XSS-Protection"] = "1; mode=block"
    response.headers["Referrer-Policy"] = "strict-origin-when-cross-origin"
    return response


app.include_router(auth.router)
app.include_router(plans.router)
app.include_router(sessions.router)
app.include_router(metrics.router)
app.include_router(ai.router)
app.include_router(calendar.router)
app.include_router(progress.router)
app.include_router(exercises.router)
app.include_router(users.router)


@app.get("/health", tags=["health"])
async def health() -> dict:
    return {"status": "ok"}
