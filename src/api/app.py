"""FastAPI application for authenticated Percorsa trip ingestion."""

from __future__ import annotations

import os
import secrets
from dataclasses import dataclass
from io import BytesIO
from pathlib import Path
from typing import Annotated, Any

import pandas as pd
from fastapi import Depends, FastAPI, File, Header, HTTPException, UploadFile
from fastapi.middleware.cors import CORSMiddleware
from fastapi.responses import FileResponse
from pydantic import BaseModel, Field
from starlette.middleware.trustedhost import TrustedHostMiddleware

from src.api.storage import TripStore
from src.data.live_trip import MAX_TRIP_ROWS, normalize_trip_frame

PROJECT_ROOT = Path(__file__).resolve().parents[2]
MAX_UPLOAD_BYTES = 25 * 1024 * 1024


@dataclass(frozen=True)
class ApiSettings:
    api_key: str
    allowed_hosts: tuple[str, ...] = ("127.0.0.1", "localhost", "testserver")
    allowed_origins: tuple[str, ...] = ()
    storage_root: Path = PROJECT_ROOT / "data" / "incoming"

    @classmethod
    def from_environment(cls) -> "ApiSettings":
        hosts = tuple(
            item.strip()
            for item in os.getenv(
                "PERCORSA_ALLOWED_HOSTS", "127.0.0.1,localhost,testserver"
            ).split(",")
            if item.strip()
        )
        origins = tuple(
            item.strip()
            for item in os.getenv("PERCORSA_ALLOWED_ORIGINS", "").split(",")
            if item.strip()
        )
        return cls(
            api_key=os.getenv("PERCORSA_API_KEY", ""),
            allowed_hosts=hosts,
            allowed_origins=origins,
        )


class TripBatch(BaseModel):
    trip_id: str = Field(min_length=1, max_length=64)
    samples: list[dict[str, Any]] = Field(min_length=2, max_length=MAX_TRIP_ROWS)


def create_app(
    settings: ApiSettings | None = None, store: TripStore | None = None
) -> FastAPI:
    settings = settings or ApiSettings.from_environment()
    store = store or TripStore(settings.storage_root)
    app = FastAPI(
        title="Percorsa Trip API",
        version="1.0.0",
        description="Authenticated ingestion for recorded Android trips.",
    )
    app.add_middleware(
        TrustedHostMiddleware, allowed_hosts=list(settings.allowed_hosts)
    )
    if settings.allowed_origins:
        app.add_middleware(
            CORSMiddleware,
            allow_origins=list(settings.allowed_origins),
            allow_credentials=False,
            allow_methods=["GET", "POST"],
            allow_headers=["X-Percorsa-Key", "Content-Type"],
        )

    def require_api_key(
        supplied: Annotated[str | None, Header(alias="X-Percorsa-Key")] = None,
    ) -> None:
        if len(settings.api_key) < 32:
            raise HTTPException(
                status_code=503,
                detail=(
                    "Trip ingestion is disabled until PERCORSA_API_KEY is set "
                    "to at least 32 characters."
                ),
            )
        if supplied is None or not secrets.compare_digest(
            supplied.encode("utf-8"), settings.api_key.encode("utf-8")
        ):
            raise HTTPException(status_code=401, detail="Invalid API key")

    auth = Depends(require_api_key)

    @app.get("/health", tags=["system"])
    def health() -> dict[str, object]:
        return {
            "status": "ready",
            "authentication_configured": len(settings.api_key) >= 32,
        }

    @app.get("/api/v1/trips", dependencies=[auth], tags=["trips"])
    def list_trips() -> dict[str, object]:
        records = store.list()
        return {"count": len(records), "trips": records}

    @app.post("/api/v1/trips/upload", dependencies=[auth], tags=["trips"])
    async def upload_trip(
        file: Annotated[UploadFile, File(description="Android trip CSV")],
    ) -> dict[str, object]:
        filename = file.filename or "trip.csv"
        if Path(filename).suffix.lower() != ".csv":
            raise HTTPException(status_code=415, detail="Only CSV uploads are accepted")
        content = await file.read(MAX_UPLOAD_BYTES + 1)
        if len(content) > MAX_UPLOAD_BYTES:
            raise HTTPException(status_code=413, detail="Trip CSV exceeds 25 MB")
        try:
            source = pd.read_csv(BytesIO(content))
            frame, validation = normalize_trip_frame(source, Path(filename).stem)
        except (ValueError, pd.errors.ParserError, UnicodeDecodeError) as error:
            raise HTTPException(status_code=422, detail=str(error)) from error
        return {"accepted": True, **store.save(frame, validation)}

    @app.post("/api/v1/trips/batches", dependencies=[auth], tags=["trips"])
    def upload_batch(batch: TripBatch) -> dict[str, object]:
        try:
            frame, validation = normalize_trip_frame(
                pd.DataFrame(batch.samples), batch.trip_id
            )
        except ValueError as error:
            raise HTTPException(status_code=422, detail=str(error)) from error
        return {"accepted": True, **store.save(frame, validation)}

    @app.get("/api/v1/trips/{record_id}/csv", dependencies=[auth], tags=["trips"])
    def download_trip(record_id: str) -> FileResponse:
        try:
            path = store.csv_path(record_id)
        except FileNotFoundError as error:
            raise HTTPException(status_code=404, detail="Trip not found") from error
        return FileResponse(
            path,
            media_type="text/csv",
            filename=f"{record_id}.csv",
        )

    return app


app = create_app()
