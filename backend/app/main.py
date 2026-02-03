from __future__ import annotations

import logging
import uuid

from fastapi import FastAPI
from fastapi.exceptions import RequestValidationError
from fastapi.middleware.cors import CORSMiddleware
from fastapi.responses import JSONResponse
from starlette.exceptions import HTTPException as StarletteHTTPException

from app.api.router import api_router
from app.core.errors import APIError, make_error_payload
from app.core.settings import get_settings


logger = logging.getLogger(__name__)


def create_app() -> FastAPI:
    settings = get_settings()

    app = FastAPI(title="Wayfarer API")

    def _with_trace_id_header(
        headers: dict[str, str] | None, trace_id: str | None
    ) -> dict[str, str] | None:
        """Return headers merged with X-Trace-Id when trace_id is present."""

        if not trace_id:
            return headers
        merged: dict[str, str] = dict(headers or {})
        merged["X-Trace-Id"] = trace_id
        return merged

    # Minimal dev CORS contract (localhost web dev) from plan-supplement.
    app.add_middleware(
        CORSMiddleware,
        allow_origins=[settings.cors_allow_origin],
        allow_credentials=settings.cors_allow_credentials,
        allow_methods=["*"],
        allow_headers=["Content-Type", "Authorization", "X-CSRF-Token"],
    )

    @app.middleware("http")
    async def _trace_id_middleware(request, call_next):
        trace_id = request.headers.get("X-Trace-Id") or str(uuid.uuid4())
        request.state.trace_id = trace_id
        response = await call_next(request)
        response.headers["X-Trace-Id"] = trace_id
        return response

    @app.exception_handler(APIError)
    async def _api_error_handler(request, exc: APIError):
        trace_id = getattr(request.state, "trace_id", None)
        return JSONResponse(
            status_code=exc.status_code,
            headers=_with_trace_id_header(None, trace_id),
            content=make_error_payload(
                code=exc.code,
                message=exc.message,
                trace_id=trace_id,
                details=exc.details,
            ),
        )

    @app.exception_handler(RequestValidationError)
    async def _validation_error_handler(request, exc: RequestValidationError):
        trace_id = getattr(request.state, "trace_id", None)
        return JSONResponse(
            status_code=422,
            headers=_with_trace_id_header(None, trace_id),
            content=make_error_payload(
                code="VALIDATION_ERROR",
                message="Request validation failed",
                trace_id=trace_id,
                details=exc.errors(),
            ),
        )

    @app.exception_handler(StarletteHTTPException)
    async def _http_error_handler(request, exc: StarletteHTTPException):
        trace_id = getattr(request.state, "trace_id", None)
        return JSONResponse(
            status_code=exc.status_code,
            headers=_with_trace_id_header(None, trace_id),
            content=make_error_payload(
                code="HTTP_ERROR",
                message=exc.detail if isinstance(exc.detail, str) else "HTTP error",
                trace_id=trace_id,
                details=None,
            ),
        )

    @app.exception_handler(Exception)
    async def _unhandled_error_handler(request, exc: Exception):
        trace_id = getattr(request.state, "trace_id", None)
        # Log unexpected exceptions with trace_id + request metadata for server-side debugging.
        method = getattr(request, "method", None)
        path = getattr(getattr(request, "url", None), "path", None)

        # Surface the failing endpoint to clients without changing the JSON schema.
        # Keep this intentionally minimal (no query string, no body).
        headers = None
        if isinstance(method, str) and isinstance(path, str) and method and path:
            headers = {"X-Error-Path": f"{method} {path}"}
        headers = _with_trace_id_header(headers, trace_id)
        logger.error(
            "Unhandled exception (trace_id=%s method=%s path=%s)",
            trace_id,
            method,
            path,
            exc_info=(type(exc), exc, exc.__traceback__),
        )
        return JSONResponse(
            status_code=500,
            headers=headers,
            content=make_error_payload(
                code="INTERNAL_ERROR",
                message="Internal error",
                trace_id=trace_id,
                details=None,
            ),
        )

    app.include_router(api_router)

    # Ensure settings are loaded early so misconfig fails fast.
    _ = settings

    return app


app = create_app()
