from __future__ import annotations

import signal
import subprocess
import sys
import time
from pathlib import Path
from urllib.error import URLError
from urllib.request import urlopen


ROOT = Path("/app")
children: list[tuple[str, subprocess.Popen[bytes]]] = []
stopping = False


def request_shutdown(_signum: int, _frame: object) -> None:
    global stopping
    stopping = True


def stop_children() -> None:
    for _name, process in children:
        if process.poll() is None:
            process.terminate()
    deadline = time.monotonic() + 25
    for _name, process in children:
        remaining = max(0.1, deadline - time.monotonic())
        try:
            process.wait(timeout=remaining)
        except subprocess.TimeoutExpired:
            process.kill()
            process.wait(timeout=2)


def wait_for_agent(process: subprocess.Popen[bytes]) -> None:
    deadline = time.monotonic() + 60
    while time.monotonic() < deadline:
        if process.poll() is not None:
            raise RuntimeError("Agent service exited during startup")
        try:
            with urlopen("http://127.0.0.1:8081/health", timeout=2) as response:
                if response.status == 200:
                    return
        except (OSError, URLError):
            time.sleep(0.5)
    raise RuntimeError("Agent service did not become healthy")


def main() -> int:
    signal.signal(signal.SIGTERM, request_shutdown)
    signal.signal(signal.SIGINT, request_shutdown)

    agent = subprocess.Popen(
        [
            sys.executable,
            "-m",
            "uvicorn",
            "neko_agent.server:app",
            "--host",
            "127.0.0.1",
            "--port",
            "8081",
            "--no-access-log",
        ],
        cwd=ROOT / "agent-service",
    )
    children.append(("agent", agent))

    try:
        wait_for_agent(agent)
        backend = subprocess.Popen(
            ["node", "dist/server.js"],
            cwd=ROOT / "backend",
        )
        children.append(("backend", backend))

        while not stopping:
            for name, process in children:
                status = process.poll()
                if status is not None:
                    print(f"Neko {name} process exited with status {status}", flush=True)
                    return status or 1
            time.sleep(0.5)
        return 0
    except Exception as error:
        print(f"Neko startup failed: {error}", flush=True)
        return 1
    finally:
        stop_children()


if __name__ == "__main__":
    raise SystemExit(main())
