FROM python:3.13-slim

ENV PYTHONDONTWRITEBYTECODE=1 \
    PYTHONUNBUFFERED=1

WORKDIR /app/backend

# 安装 uv（用于基于 uv.lock 的可复现安装）
RUN python -m pip install --no-cache-dir --upgrade pip uv

# 先拷贝锁文件以提升构建缓存命中率
COPY backend/pyproject.toml backend/uv.lock ./

# 使用锁文件安装依赖（会创建 /app/backend/.venv）
RUN uv sync --frozen

# 再拷贝实际代码
COPY backend/ ./

COPY backend/docker-entrypoint.sh /app/backend/docker-entrypoint.sh
RUN chmod +x /app/backend/docker-entrypoint.sh

EXPOSE 8000

HEALTHCHECK --interval=10s --timeout=3s --start-period=120s --retries=20 \
  CMD python -c "import urllib.request; urllib.request.urlopen('http://127.0.0.1:8000/healthz').read()" || exit 1

ENTRYPOINT ["/app/backend/docker-entrypoint.sh"]
