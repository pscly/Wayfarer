"""Thin entrypoint for `uvicorn main:app`.

`run.bat` starts the backend with: `uv run uvicorn main:app ...` (cwd=backend/).
Keep this file as a stable import target.
"""

from __future__ import annotations

import importlib


app = importlib.import_module("app.main").app
