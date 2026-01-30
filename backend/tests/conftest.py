from __future__ import annotations

import asyncio
from pathlib import Path
import sys

import pytest
from fastapi.testclient import TestClient


# Ensure `import app.*` works when pytest chooses an import mode that
# doesn't automatically add the project root to sys.path.
sys.path.insert(0, str(Path(__file__).resolve().parents[1]))


@pytest.fixture()
def client(tmp_path: Path, monkeypatch: pytest.MonkeyPatch) -> TestClient:
    # Isolated sqlite DB per test.
    db_path = tmp_path / "test.db"
    monkeypatch.setenv("WAYFARER_DB_URL", f"sqlite+aiosqlite:///{db_path.as_posix()}")

    # Deterministic JWT key config for tests.
    monkeypatch.setenv("WAYFARER_JWT_SIGNING_KEYS_JSON", '{"test-kid":"test-secret"}')
    monkeypatch.setenv("WAYFARER_JWT_KID_CURRENT", "test-kid")

    # Cookie/CORS defaults (match plan-supplement dev contract).
    monkeypatch.setenv("WAYFARER_DEV_COOKIE_SECURE", "false")
    monkeypatch.setenv("WAYFARER_CORS_ALLOW_ORIGIN", "http://localhost:3000")
    monkeypatch.setenv("WAYFARER_CORS_ALLOW_CREDENTIALS", "true")

    # Clear settings cache and reset DB engine/sessionmaker.
    from app.core.settings import get_settings

    get_settings.cache_clear()

    from app.db import session as db_session

    db_session._engine = None  # noqa: SLF001
    db_session._sessionmaker = None  # noqa: SLF001

    # Import models so Base.metadata is fully populated.
    import app.models  # noqa: F401

    from app.db.base import Base

    async def _init_schema() -> None:
        engine = db_session.get_engine()
        async with engine.begin() as conn:
            await conn.run_sync(Base.metadata.create_all)

    asyncio.run(_init_schema())

    from app.main import create_app

    app = create_app()
    return TestClient(app)
