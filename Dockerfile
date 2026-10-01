FROM node:22-bookworm-slim AS backend-build
WORKDIR /workspace/backend
COPY backend/package.json backend/package-lock.json ./
RUN npm ci
COPY backend/ ./
RUN npm run build && npm prune --omit=dev

FROM python:3.12-slim AS runtime
ENV PYTHONDONTWRITEBYTECODE=1 \
    PYTHONUNBUFFERED=1 \
    PATH="/opt/venv/bin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin" \
    PORT=8080 \
    AGENT_SERVICE_URL="http://127.0.0.1:8081"

RUN apt-get update \
    && apt-get install -y --no-install-recommends ca-certificates libstdc++6 passwd \
    && rm -rf /var/lib/apt/lists/* \
    && useradd --create-home --uid 10001 neko \
    && python -m venv /opt/venv

COPY --from=node:22-bookworm-slim /usr/local/bin/node /usr/local/bin/node
COPY --from=backend-build /workspace/backend/dist /app/backend/dist
COPY --from=backend-build /workspace/backend/node_modules /app/backend/node_modules
COPY backend/package.json /app/backend/package.json
COPY preview/ /app/preview/
COPY agent-service/requirements-runtime.txt /tmp/requirements-runtime.txt
RUN /opt/venv/bin/pip install --no-cache-dir -r /tmp/requirements-runtime.txt \
    && rm /tmp/requirements-runtime.txt
COPY agent-service/neko_agent/ /app/agent-service/neko_agent/
COPY deploy/fly_supervisor.py /app/deploy/fly_supervisor.py

USER neko
WORKDIR /app
EXPOSE 8080
CMD ["python", "/app/deploy/fly_supervisor.py"]
