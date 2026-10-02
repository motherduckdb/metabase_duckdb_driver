#!/usr/bin/env python3
# /// script
# requires-python = ">=3.10"
# dependencies = []
# ///
"""Start a metabase-duckdb image and run a DuckDB query through it.

Metabase loads the driver lazily, so a healthy /api/health says nothing about
the driver: this also adds a DuckDB database and checks that the DuckDB
version it reports matches deps.edn.

    uv run ci/smoke_image.py <image>    (or python3 ci/smoke_image.py <image>)
"""

import json
import re
import subprocess
import sys
import time
import urllib.error
import urllib.request
from pathlib import Path

BOOT_TIMEOUT_S = 300


def api(base, method, path, body=None, session=None):
    req = urllib.request.Request(
        base + path,
        method=method,
        data=None if body is None else json.dumps(body).encode(),
        headers={"Content-Type": "application/json", **({"X-Metabase-Session": session} if session else {})},
    )
    try:
        with urllib.request.urlopen(req, timeout=120) as resp:
            return json.load(resp)
    except urllib.error.HTTPError as e:
        raise RuntimeError(f"{method} {path} -> {e.code}: {e.read().decode()[:500]}") from None


def expected_duckdb_version():
    # deps.edn pins e.g. 1.5.5.0, DuckDB reports v1.5.5
    pinned = re.search(r'duckdb_jdbc \{:mvn/version "([^"]+)"', (Path(__file__).parent.parent / "deps.edn").read_text())
    return "v" + ".".join(pinned.group(1).split(".")[:3])


def smoke(base):
    deadline = time.monotonic() + BOOT_TIMEOUT_S
    while True:
        try:
            if api(base, "GET", "/api/health").get("status") == "ok":
                break
        except (urllib.error.URLError, ConnectionError):
            pass
        if time.monotonic() > deadline:
            raise RuntimeError(f"Metabase not healthy after {BOOT_TIMEOUT_S}s")
        time.sleep(3)

    token = api(base, "GET", "/api/session/properties")["setup-token"]
    session = api(base, "POST", "/api/setup", {
        "token": token,
        "user": {"first_name": "smoke", "last_name": "test", "email": "smoke@example.com", "password": "Sm0ke-test-only!"},
        "prefs": {"site_name": "smoke", "allow_tracking": False},
    })["id"]
    db = api(base, "POST", "/api/database",
             {"engine": "duckdb", "name": "smoke", "details": {"database_file": ":memory:"}}, session)
    result = api(base, "POST", "/api/dataset",
                 {"database": db["id"], "type": "native", "native": {"query": "select version()"}}, session)
    if result.get("status") != "completed":
        raise RuntimeError(f"query failed: {result.get('error')}")
    actual, expected = result["data"]["rows"][0][0], expected_duckdb_version()
    if actual != expected:
        raise RuntimeError(f"DuckDB {actual} in the image, deps.edn pins {expected}")
    print(f"ok: driver loaded, DuckDB {actual}")


def main(image):
    container = subprocess.run(["docker", "run", "-d", "-p", "127.0.0.1::3000", image],
                               check=True, capture_output=True, text=True).stdout.strip()
    try:
        port = subprocess.run(["docker", "port", container, "3000/tcp"],
                              check=True, capture_output=True, text=True).stdout.split(":")[-1].strip()
        smoke(f"http://127.0.0.1:{port}")
    except Exception:
        subprocess.run(["docker", "logs", "--tail", "200", container])
        raise
    finally:
        subprocess.run(["docker", "rm", "-f", container], capture_output=True)


if __name__ == "__main__":
    if len(sys.argv) != 2:
        sys.exit(__doc__)
    main(sys.argv[1])
