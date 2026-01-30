from __future__ import annotations

from fastapi import FastAPI


app = FastAPI(title="Wayfarer API")


@app.get("/healthz")
def healthz() -> dict[str, str]:
    return {"status": "ok"}


@app.get("/readyz")
def readyz() -> dict[str, str]:
    return {"status": "ready"}
