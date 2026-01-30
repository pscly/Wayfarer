from __future__ import annotations

import dataclasses
from typing import Any


@dataclasses.dataclass(slots=True)
class APIError(Exception):
    """Business error that must map to the standard error envelope."""

    code: str
    message: str
    status_code: int = 400
    details: Any | None = None


def make_error_payload(
    *, code: str, message: str, trace_id: str | None, details: Any | None
) -> dict[str, Any]:
    return {
        "code": code,
        "message": message,
        "details": details,
        "trace_id": trace_id,
    }
