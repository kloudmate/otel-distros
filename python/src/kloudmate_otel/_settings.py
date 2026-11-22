"""The ``distribution.kloudmate`` block (contract §1), with the environment as fallback."""

from __future__ import annotations

import importlib.util
import os
import stat
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
    opamp_endpoint: str | None = None

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
            otlp_socket=_str(agent.get("otlp_socket")) or _str(os.environ.get("KM_OTLP_SOCKET")),
            otlp_endpoint=_str(agent.get("otlp_endpoint")),
            runtime_metrics_profile=profile,
            status_dir=_str(block.get("status_dir"))
            or _str(os.environ.get("KM_SDK_STATUS_DIR"))
            or DEFAULT_STATUS_DIR,
            opamp_endpoint=_str(agent.get("opamp_endpoint")) or _str(os.environ.get("KM_OPAMP_ENDPOINT")),
        )

    def endpoint(self) -> str:
        """Local-agent discovery over TCP: the block's ``otlp_endpoint``, else 127.0.0.1:4318 (not localhost,
        which resolves to ::1 first on many hosts). Used when the agent's socket isn't (``socket_target``)."""
        return (self.otlp_endpoint or DEFAULT_ENDPOINT).rstrip("/")

    def socket_target(self, env: Mapping[str, str] | None = None) -> str | None:
        """The agent's OTLP socket as a gRPC target (``unix:///path``), when it is used: a socket path is
        configured (``agent.otlp_socket``, else ``KM_OTLP_SOCKET``), it is an absolute path to a socket that
        exists, the OTLP/gRPC exporter is installed (the agent's socket speaks OTLP/gRPC; this distribution
        ships the HTTP exporter only), and the app chose no OTLP endpoint or protocol itself."""
        env = os.environ if env is None else env
        p = self.otlp_socket
        if not p or not os.path.isabs(p) or any(env.get(k) for k in TRANSPORT_KEYS):
            return None
        try:
            if not stat.S_ISSOCK(os.stat(p).st_mode):
                return None
        except OSError:
            return None
        return "unix://" + p if grpc_exporter_installed() else None


# OTLP variables that choose a transport: one set by the app leaves the socket unused.
TRANSPORT_KEYS = tuple(
    f"OTEL_EXPORTER_OTLP_{sig}{what}" for sig in ("", "TRACES_", "METRICS_", "LOGS_") for what in ("ENDPOINT", "PROTOCOL")
)


def grpc_exporter_installed() -> bool:
    try:
        return (
            importlib.util.find_spec("grpc") is not None
            and importlib.util.find_spec("opentelemetry.exporter.otlp.proto.grpc") is not None
        )
    except (ImportError, ValueError):
        return False
