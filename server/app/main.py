from fastapi import FastAPI
from fastapi.middleware.cors import CORSMiddleware

from app.routers import ai, auth, calendar, exercises, metrics, plans, progress, sessions

app = FastAPI(title="Spotter API", version="0.1.0")

app.add_middleware(
    CORSMiddleware,
    allow_origins=["*"],
    allow_credentials=True,
    allow_methods=["*"],
    allow_headers=["*"],
)

app.include_router(auth.router)
app.include_router(plans.router)
app.include_router(sessions.router)
app.include_router(metrics.router)
app.include_router(ai.router)
app.include_router(calendar.router)
app.include_router(progress.router)
app.include_router(exercises.router)


@app.get("/health", tags=["health"])
async def health() -> dict:
    return {"status": "ok"}
