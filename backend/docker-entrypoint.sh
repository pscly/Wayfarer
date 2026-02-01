#!/usr/bin/env sh
set -eu

cd /app/backend

# 运行目录下的相对路径：./data/exports
mkdir -p ./data/exports

MIGRATE_ON_START="${WAYFARER_MIGRATE_ON_START:-1}"
MIGRATE_STRICT="${WAYFARER_MIGRATE_STRICT:-0}"

if [ "$MIGRATE_ON_START" = "1" ]; then
  echo "[wayfarer-backend] alembic upgrade head"

  attempt=1
  max_attempts="${WAYFARER_MIGRATE_MAX_ATTEMPTS:-3}"
  sleep_s=2

  while [ "$attempt" -le "$max_attempts" ]; do
    if uv run alembic upgrade head; then
      echo "[wayfarer-backend] migration OK"
      break
    fi

    if [ "$attempt" -ge "$max_attempts" ]; then
      echo "[wayfarer-backend] migration FAILED (attempt=${attempt}/${max_attempts})"
      if [ "$MIGRATE_STRICT" = "1" ]; then
        echo "[wayfarer-backend] MIGRATE_STRICT=1，退出进程"
        exit 1
      fi
      echo "[wayfarer-backend] 继续启动服务（MIGRATE_STRICT=0）"
      break
    fi

    echo "[wayfarer-backend] migration failed (attempt=${attempt}/${max_attempts}), retry in ${sleep_s}s..."
    sleep "$sleep_s"
    attempt=$((attempt + 1))
    if [ "$sleep_s" -lt 30 ]; then
      sleep_s=$((sleep_s + 2))
    fi
  done
else
  echo "[wayfarer-backend] skip migrations (WAYFARER_MIGRATE_ON_START=${MIGRATE_ON_START})"
fi

echo "[wayfarer-backend] uvicorn main:app"
exec uv run uvicorn main:app \
  --host 0.0.0.0 \
  --port 8000 \
  --proxy-headers \
  --forwarded-allow-ips "*"
