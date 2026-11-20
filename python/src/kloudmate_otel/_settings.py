"""The ``distribution.kloudmate`` block (contract §1), with the environment as fallback."""

from __future__ import annotations

import os
from dataclasses import dataclass
from typing import Any, Mapping

DEFAULT_ENDPOINT = "http://127.0.0.1:4318"
DEFAULT_STATUS_DIR = "/run/kmagent/sdk-status"

# runtime_metrics.profile -> OTEL_PYTHON_SYSTEM_METRICS_EXCLUDED_METRICS. Host-level system.* metrics
# are the KloudMate Agent's job, so every profile drops them; standard also drops the deprecated
# process.runtime.* names that duplicate process.* and cpython.*.
PROFILE_EXCLUDES = {
    "standard": "system.*,process.runtime.*",
    "extended": "system.*",
    "off": "*",
}


def _str(v: Any) -> str | None:
    if v is None:
        return None
    s = str(v).strip()
    return s or None


def _map(v: Any) -> Mapping[str, Any]:
    return v if isinstance(v, Mapping) else {}


@dataclass(frozen=True)
class Settings:
    service_id: str | None
    otlp_socket: str | None
    otlp_endpoint: str | None
    runtime_metrics_profile: str
    status_dir: str

    @classmethod
    def from_block(cls, block: Mapping[str, Any] | None = None) -> "Settings":
        """Settings from a parsed ``distribution.kloudmate`` mapping; unknown keys are ignored."""
        block = _map(block)
        agent = _map(block.get("agent"))
        profile = _str(_map(block.get("runtime_metrics")).get("profile")) or "standard"
        if profile not in PROFILE_EXCLUDES:
            profile = "standard"
        return cls(
            service_id=_str(block.get("service_id")) or _str(os.environ.get("KM_SERVICE_ID")),
            otlp_socket=_str(agent.get("otlp_socket")),
            otlp_endpoint=_str(agent.get("otlp_endpoint")),
            runtime_metrics_profile=profile,
            status_dir=_str(block.get("status_dir"))
            or _str(os.environ.get("KM_SDK_STATUS_DIR"))
            or DEFAULT_STATUS_DIR,
        )

    def endpoint(self) -> str:
        """Local-agent discovery. The upstream OTLP/HTTP exporter has no Unix-socket transport, so
        ``otlp_socket`` is skipped and the TCP fallback is used: the block's ``otlp_endpoint``, else
        127.0.0.1:4318 (not localhost, which resolves to ::1 first on many hosts)."""
        return (self.otlp_endpoint or DEFAULT_ENDPOINT).rstrip("/")
