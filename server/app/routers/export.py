"""GET /export — download all of the signed-in user's data as a JSON document (ROADMAP T3 #6)."""

import datetime
from typing import Annotated

from fastapi import APIRouter, Depends, Request
from fastapi.responses import JSONResponse, Response
from sqlalchemy.ext.asyncio import AsyncSession

from app.database import get_db
from app.limiter import limiter
from app.security import CurrentUser
from app.services.export_service import build_export, build_sets_csv

router = APIRouter(tags=["export"])

DbSession = Annotated[AsyncSession, Depends(get_db)]


@router.get("/export")
@limiter.limit("5/minute")
async def export(request: Request, current_user: CurrentUser, db: DbSession) -> JSONResponse:
    """The user's full data export (own-session auth). Returned as a downloadable JSON file."""
    data = await build_export(db, current_user)
    filename = f"spotter-export-{datetime.date.today().isoformat()}.json"
    return JSONResponse(
        data, headers={"Content-Disposition": f'attachment; filename="{filename}"'}
    )


@router.get("/export/sets.csv")
@limiter.limit("5/minute")
async def export_sets_csv(request: Request, current_user: CurrentUser, db: DbSession) -> Response:
    """Every set log as a flat CSV (newest session first), spreadsheet-ready."""
    csv_text = await build_sets_csv(db, current_user.id)
    filename = f"spotter-sets-{datetime.date.today().isoformat()}.csv"
    return Response(
        csv_text,
        media_type="text/csv",
        headers={"Content-Disposition": f'attachment; filename="{filename}"'},
    )
