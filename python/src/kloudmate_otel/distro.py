"""KloudMate defaults, applied as ``os.environ.setdefault`` so any value the app sets itself wins."""

from __future__ import annotations

import logging
import os

from opentelemetry.instrumentation.distro import BaseDistro

from ._settings import PROFILE_EXCLUDES, Settings

_logger = logging.getLogger(__name__)

# Env keys the app had set before the distro ran (origin app_env in the status).
APP_SET: set[str] = set()
DEFAULTS: dict[str, str] = {}


def defaults(settings: Settings, file_mode: bool) -> dict[str, str]:
    d = {
        "OTEL_TRACES_EXPORTER": "otlp",
        "OTEL_METRICS_EXPORTER": "otlp",
        "OTEL_LOGS_EXPORTER": "otlp",
        "OTEL_EXPORTER_OTLP_PROTOCOL": "http/protobuf",
        "OTEL_PROPAGATORS": "tracecontext,baggage",
        "OTEL_PYTHON_SYSTEM_METRICS_EXCLUDED_METRICS": PROFILE_EXCLUDES[settings.runtime_metrics_profile],
    }
    if not file_mode:
        # With a config file the file is the source of truth: setting the endpoint here would leak into
        # its ${OTEL_EXPORTER_OTLP_ENDPOINT:-...} substitutions and look like an app setting.
        d["OTEL_EXPORTER_OTLP_ENDPOINT"] = settings.endpoint()
    return d


class KloudMateDistro(BaseDistro):
    """Selected with OTEL_PYTHON_DISTRO=kloudmate (the bootstrap sets it unless the app chose another)."""

    def _configure(self, **kwargs) -> None:
        try:
            file_mode = bool(os.environ.get("OTEL_CONFIG_FILE"))
            block = None
            if file_mode:
                from .configurator import read_block  # local: needs the file-configuration extras

                block = read_block(os.environ["OTEL_CONFIG_FILE"])
            settings = Settings.from_block(block)
            DEFAULTS.clear()
            DEFAULTS.update(defaults(settings, file_mode))
            for k, v in DEFAULTS.items():
                if os.environ.get(k):
                    APP_SET.add(k)
                else:
                    os.environ[k] = v
        except Exception:  # never break the app: upstream defaults apply
            _logger.debug("kloudmate distro defaults not applied", exc_info=True)
