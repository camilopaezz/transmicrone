"""Canonical feed settings and one persistent installation ID for PC tooling."""

from __future__ import annotations

import fcntl
import os
import re
import uuid
from pathlib import Path


def feed_field(name: str) -> str:
    environment = "TRANSMICRONE_APP_ID" if name == "APPID" else "TRANSMICRONE_BASE_URL"
    if value := os.environ.get(environment):
        return value.strip()
    root = Path(__file__).resolve().parent.parent
    local = root / "feed.local.properties"
    if local.is_file():
        match = re.search(rf"^{name}=(.+)$", local.read_text(), re.MULTILINE)
        if match:
            return match.group(1).strip()
    document = (root / "docs" / "feed.md").read_text()
    match = re.search(rf"^{name}=(.+)$", document, re.MULTILINE)
    if not match:
        raise RuntimeError(f"Set {environment} or {name} in feed.local.properties")
    return match.group(1).strip()


def installation_id() -> str:
    state = Path(os.environ.get("XDG_STATE_HOME") or Path.home() / ".local" / "state")
    path = state / "transmicrone" / "feed-install-id"
    path.parent.mkdir(parents=True, exist_ok=True)
    fd = os.open(path, os.O_RDWR | os.O_CREAT, 0o600)
    with os.fdopen(fd, "r+") as stream:
        fcntl.flock(stream, fcntl.LOCK_EX)
        saved = stream.read().strip()
        if saved:
            return saved
        value = str(uuid.uuid4())
        stream.seek(0)
        stream.write(value)
        stream.truncate()
        stream.flush()
        os.fsync(stream.fileno())
        return value


BASE_URL = feed_field("BASE_URL")
APPID = feed_field("APPID")
INSTALL_UUID = installation_id()
