"""Startup status (contract §5): ``<status_dir>/<pid>.json``, written only into an existing directory,
replaced atomically, never raising."""

from __future__ import annotations

import hashlib
import json
import os
import re
import sys
import time
from typing import Any, Iterable, Mapping

from . import DISTRO_NAME, __version__

ORDER = {"ok": 0, "degraded": 1, "unsupported_runtime": 2, "denied": 2, "disabled": 2, "error": 3}

# Modules of other vendors' agents; the reason code reported when one is loaded.
FOREIGN = {
    "ddtrace": "foreign_agent:ddtrace",
    "newrelic": "foreign_agent:newrelic",
    "elasticapm": "foreign_agent:elastic",
    "sentry_sdk.integrations.opentelemetry": "foreign_agent:sentry",
}


_SUBSTITUTION = re.compile(r"\$\{(?:env:)?(OTEL_[A-Z0-9_]+)(?::-[^}]*)?\}")


def app_substitutions(text: str | None, env: Mapping[str, str], distro_set: Iterable[str] = ()) -> list[str]:
    """The ``${OTEL_*}`` substitutions of a config file that the app supplies itself, in file order: the file's
    KloudMate values the app replaced. ``distro_set`` names variables the distro itself put in the environment."""
    out: list[str] = []
    if not text:
        return out
    skip = set(distro_set) | {"OTEL_CONFIG_FILE"}
    for name in _SUBSTITUTION.findall(text):
        if name not in skip and (env.get(name) or "").strip() and name not in out:
            out.append(name)
    return out


class Status:
    def __init__(self) -> None:
        self.service_id: str | None = None
        self.result = "ok"
        self.reason = ""
        self.config_hash = ""
        self.effective: dict[str, dict[str, str]] = {}
        self.overridden_by_app: list[str] = []
        self.instrumentations: list[str] = []

    def put(self, key: str, value: Any, origin: str) -> None:
        if value is not None:
            self.effective[key] = {"value": str(value), "origin": origin}

    def overridden(self, key: str) -> None:
        """A KloudMate-managed setting the app replaced with its own value (sorted, no duplicates)."""
        if key not in self.overridden_by_app:
            self.overridden_by_app.append(key)
            self.overridden_by_app.sort()

    def fail(self, result: str, reason: str) -> None:
        if ORDER.get(result, 3) > ORDER.get(self.result, 0):
            self.result, self.reason = result, reason

    def check_foreign_agents(self) -> None:
        for mod, reason in FOREIGN.items():
            if mod in sys.modules:
                self.fail("degraded", reason)

    def to_dict(self) -> dict[str, Any]:
        now = time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime())
        return {
            "schema_version": 1,
            "pid": os.getpid(),
            "km.service_id": self.service_id or "",
            "distro": {"name": DISTRO_NAME, "version": __version__},
            "result": self.result,
            "reason": self.reason,
            "started_at": now,
            "heartbeat_at": now,
            "config_hash": self.config_hash,
            "effective_config": self.effective,
            "overridden_by_app": self.overridden_by_app,
            "instrumentations": self.instrumentations,
        }

    def write(self, directory: str) -> str | None:
        try:
            if not os.path.isdir(directory):
                return None
            path = os.path.join(directory, "%d.json" % os.getpid())
            tmp = os.path.join(directory, ".%d.json.tmp" % os.getpid())
            with open(tmp, "w", encoding="utf-8") as f:
                json.dump(self.to_dict(), f, separators=(",", ":"))
                f.write("\n")
            os.replace(tmp, path)
            return path
        except Exception:  # never break the app
            return None


# The status written at startup and its directory, so instrumentations loaded afterwards can be added to it.
CURRENT: list[Any] = []


def remember(st: Status, directory: str) -> None:
    CURRENT[:] = [st, directory]


def record_instrumentation(name: str) -> None:
    """Adds an instrumentation the distro activated and rewrites the status. Never raises."""
    try:
        if not CURRENT:
            return
        st, directory = CURRENT
        if name and name not in st.instrumentations:
            st.instrumentations.append(name)
            st.write(directory)
    except Exception:
        pass


def file_text(path: str | None) -> str | None:
    if not path:
        return None
    try:
        if os.path.getsize(path) > 1024 * 1024:
            return None
        with open(path, encoding="utf-8") as f:
            return f.read()
    except Exception:
        return None


def file_hash(path: str | None) -> str:
    if not path:
        return ""
    try:
        with open(path, "rb") as f:
            return "sha256:" + hashlib.sha256(f.read()).hexdigest()
    except Exception:
        return ""
