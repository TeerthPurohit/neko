FROM python:3.12-slim
WORKDIR /app
COPY requirements.txt .
RUN pip install --no-cache-dir -r requirements.txt
COPY neko_agent neko_agent
RUN useradd --create-home neko
USER neko
ENV PORT=8080
CMD ["sh", "-c", "uvicorn neko_agent.server:app --host 0.0.0.0 --port ${PORT} --no-access-log"]
