# Neko Cost Control Agent

Kotlin Android personal finance agent with local transaction capture, a private
backend, configurable AI cost limits, focused task agents, and durable correction
memory.

## Project layout

- `app/` and `core/`: Android app and ledger logic.
- `backend/`: Node API, Postgres migrations, durable tasks, and AI budget controls.
- `agent-service/`: private LangGraph/Nanobot task agents.
- `preview/`: browser interface preview.
- `docs/agent-learning.md`: correction memory and specialist behavior.

## Configuration

Copy `.env.example` to `.env` locally and supply your own configuration. Real
credentials, local environment files, signing keys, databases, and personal
spending policy are excluded from Git. Model API keys belong on the backend.
Raw transaction SMS stays on the Android device.

The repository is intended to remain private. Review source, documents, images,
and configuration before any future visibility change.
