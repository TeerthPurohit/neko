import asyncio
import os
import secrets

from fastapi import FastAPI, HTTPException, Request
from fastapi.exceptions import RequestValidationError
from fastapi.responses import JSONResponse
from loguru import logger

from .runtime import AgentRequest, run_agent

# Library diagnostics must not serialize prompts, transaction context, or keys.
logger.disable('nanobot')
app = FastAPI(title='Neko agent', docs_url=None, redoc_url=None, openapi_url=None)
gate = asyncio.Semaphore(4)


@app.exception_handler(RequestValidationError)
async def validation_error(_request, _error):
    # Pydantic errors normally include input values, including the submitted key.
    return JSONResponse({'error': 'Invalid structured agent request'}, status_code=400)


@app.get('/health')
async def health():
    return {'service': 'neko-agent', 'frameworks': ['langgraph', 'nanobot'], 'status': 'ok'}


@app.post('/internal/run')
async def run(request: Request):
    expected = os.getenv('AGENT_SERVICE_TOKEN', '')
    if len(expected) < 32:
        raise HTTPException(503, 'Agent service authentication is not configured')
    if not secrets.compare_digest(request.headers.get('authorization', ''), 'Bearer ' + expected):
        raise HTTPException(401, 'Unauthorized')
    if int(request.headers.get('content-length', '0')) > 100_000:
        raise HTTPException(413, 'Payload is too large')
    raw = await request.body()
    if len(raw) > 100_000:
        raise HTTPException(413, 'Payload is too large')
    try:
        payload = AgentRequest.model_validate_json(raw)
    except Exception:
        raise HTTPException(400, 'Invalid structured agent request') from None
    approved = os.getenv('CHAT_MODELS', 'auto,openai/gpt-oss-120b,deepseek/deepseek-v4.1-flash,xiaomi/mimo-v2.6-pro,openai/gpt-6-luna').split(',')
    if payload.model not in [x.strip() for x in approved]:
        raise HTTPException(400, 'Model is not approved')
    if any(m not in [x.strip() for x in approved] for m in payload.allowed_models):
        raise HTTPException(400, 'Routing candidates are not approved')
    if not payload.consent:
        raise HTTPException(403, 'Cloud AI consent is required')
    try:
        async with asyncio.timeout(1):
            await gate.acquire()
    except TimeoutError:
        raise HTTPException(429, 'Agent is busy; retry the queued task') from None
    try:
        return await run_agent(payload)
    except Exception:
        raise HTTPException(502, 'Agent interrupted; retry the queued task') from None
    finally:
        gate.release()
