from __future__ import annotations

import os

from celery import Celery

from app.core.settings import Settings, get_settings


def create_celery_app(settings: Settings | None = None) -> Celery:
    """Create and configure the project's Celery application.

    Default dev behavior is eager mode (inline execution). When eager mode is
    disabled, we require a broker URL (Redis) to be provided via env.
    """

    settings = settings or get_settings()

    app = Celery("wayfarer")

    if settings.celery_eager:
        app.conf.update(
            task_always_eager=True,
            task_eager_propagates=True,
        )
        return app

    broker_url = settings.redis_url or os.getenv("REDIS_URL")
    if not broker_url:
        raise ValueError(
            "Celery eager mode is disabled (WAYFARER_CELERY_EAGER=0) but no broker URL was provided. "
            "Set WAYFARER_REDIS_URL or REDIS_URL."
        )

    app.conf.update(
        broker_url=broker_url,
        task_always_eager=False,
        task_eager_propagates=False,
    )
    return app


celery_app = create_celery_app()
