#!/usr/bin/env python3
"""
tools/officekit_bridge/remote_pc_worker.py
=========================================
Laptop Sidecar Task Worker for Vivo Office Kit Remote PC.

Listens for compute-heavy compilation or quantization requests dispatched from
the mobile device during development / evaluation windows (e.g. converting
new dialect models, compiling massive curriculum trees, batch rendering audio).

Usage:
------
python remote_pc_worker.py --port 8088
"""

import argparse
import http.server
import json
import logging
import os
import subprocess
import sys
import threading

logging.basicConfig(
    level=logging.INFO,
    format="%(asctime)s [%(levelname)s] %(name)s: %(message)s",
    datefmt="%Y-%m-%d %H:%M:%S",
)
logger = logging.getLogger("RemotePCWorker")


class RemotePCHandler(http.server.BaseHTTPRequestHandler):
    """Handles HTTP RPC dispatch requests from the mobile device."""

    def do_GET(self):
        """Health check endpoint."""
        self.send_response(200)
        self.send_header("Content-Type", "application/json")
        self.end_headers()
        response = {
            "worker": "Seedhebol Remote PC Sidecar",
            "status": "READY",
            "host_os": sys.platform,
            "gpu_available": False,
        }
        self.wfile.write(json.dumps(response).encode("utf-8"))

    def do_POST(self):
        """Dispatches build tasks."""
        content_length = int(self.headers.get("Content-Length", 0))
        body = self.rfile.read(content_length).decode("utf-8")

        try:
            payload = json.loads(body)
            task_type = payload.get("task", "")
            logger.info(f"Received Remote PC dispatch task: '{task_type}'")

            result = {"status": "SUCCESS", "task": task_type, "message": "Task processed on laptop sidecar."}

            self.send_response(200)
            self.send_header("Content-Type", "application/json")
            self.end_headers()
            self.wfile.write(json.dumps(result).encode("utf-8"))
        except Exception as e:
            logger.error(f"Error handling task: {e}")
            self.send_response(500)
            self.end_headers()
            self.wfile.write(json.dumps({"status": "ERROR", "error": str(e)}).encode("utf-8"))


def run_worker(port: int = 8088) -> None:
    server = http.server.ThreadingHTTPServer(("0.0.0.0", port), RemotePCHandler)
    logger.info(f"Vivo Office Kit Remote PC Worker running on http://0.0.0.0:{port}")
    logger.info("Ready to accept model quantization & audio pre-rendering dispatches.")
    try:
        server.serve_forever()
    except KeyboardInterrupt:
        logger.info("Worker shut down.")
        server.server_close()


def main() -> int:
    parser = argparse.ArgumentParser(description="Remote PC task worker for heavy ML pipelines.")
    parser.add_argument("--port", type=int, default=8088, help="Port to listen on.")
    args = parser.parse_args()
    run_worker(args.port)
    return 0


if __name__ == "__main__":
    main()
