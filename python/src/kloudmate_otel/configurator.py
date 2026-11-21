"""SDK setup: env mode via upstream ``_initialize_components``; declarative mode via upstream
``load_config_file`` + ``configure_sdk``, with the model completed from ``distribution.kloudmate`` first.
Any failure here falls back to the plain upstream configurator."""

from __future__ import annotations

import logging
import os
from typing import Any, Mapping

from opentelemetry.sdk._configuration import _OTelSDKConfigurator

from . import DISTRO_NAME, __version__
from . import distro as _distro
from ._settings import Settings
from ._status import Status, app_substitutions, file_hash, file_text, remember

_logger = logging.getLogger(__name__)

KM_SERVICE_ID = "km.service_id"


def _resource_attr_from_env(key: str) -> str | None:
    for item in os.environ.get("OTEL_RESOURCE_ATTRIBUTES", "").split(","):
        k, sep, v = item.partition("=")
        if sep and k.strip() == key:
            return v.strip()
    return None


def read_block(path: str) -> Mapping[str, Any] | None:
    """``distribution.kloudmate`` from the config file (env-substituted, validated), or None."""
    try:
        from opentelemetry.sdk._configuration.file._loader import load_config_file

        dist = load_config_file(path).distribution or {}
        return dist.get("kloudmate")
    except Exception:
        return None


def _env_status(settings: Settings) -> Status:
    st = Status()
    for k, v in _distro.DEFAULTS.items():
        app = k in _distro.APP_SET
        st.put(k, os.environ.get(k, v), "app_env" if app else "distro_default")
        if app:
            st.overridden(k)
    for k in ("OTEL_SERVICE_NAME", "OTEL_TRACES_SAMPLER", "OTEL_TRACES_SAMPLER_ARG"):
        if os.environ.get(k):
            st.put(k, os.environ[k], "app_env")
    if os.environ.get("OTEL_SDK_DISABLED", "").strip().lower() == "true":
        st.fail("disabled", "sdk_disabled")
    return st


def stamp_attributes(service_id: str | None) -> dict[str, str]:
    """Resource attributes the distro adds in env mode. km.service_id only when OTEL_RESOURCE_ATTRIBUTES
    lacks it: an app-set value is never rewritten (contract §2)."""
    attrs = {"telemetry.distro.name": DISTRO_NAME, "telemetry.distro.version": __version__}
    if service_id and _resource_attr_from_env(KM_SERVICE_ID) is None:
        attrs[KM_SERVICE_ID] = service_id
    return attrs


def complete_model(config: Any, settings: Settings, st: Status) -> None:
    """Fills what the file leaves unset; never replaces a value the file sets."""
    from opentelemetry.sdk._configuration import models as m

    if config.resource is None:
        config.resource = m.Resource()
    attrs = list(config.resource.attributes or [])
    file_id = next((a.value for a in attrs if a.name == KM_SERVICE_ID and a.value is not None), None)
    st.service_id = str(file_id) if file_id is not None else settings.service_id
    if file_id is None and settings.service_id:
        attrs.append(m.AttributeNameValue(name=KM_SERVICE_ID, value=settings.service_id))
    attrs = [a for a in attrs if a.name not in ("telemetry.distro.name", "telemetry.distro.version")]
    attrs.append(m.AttributeNameValue(name="telemetry.distro.name", value=DISTRO_NAME))
    attrs.append(m.AttributeNameValue(name="telemetry.distro.version", value=__version__))
    config.resource.attributes = attrs

    base = settings.endpoint()
    filled = 0
    for p in (config.tracer_provider.processors if config.tracer_provider else None) or []:
        for proc in (p.batch, p.simple):
            exp = proc.exporter.otlp_http if proc and proc.exporter else None
            if exp is not None and not exp.endpoint:
                exp.endpoint, filled = base + "/v1/traces", filled + 1
    for r in (config.meter_provider.readers if config.meter_provider else None) or []:
        exp = r.periodic.exporter.otlp_http if r.periodic and r.periodic.exporter else None
        if exp is not None and not exp.endpoint:
            exp.endpoint, filled = base + "/v1/metrics", filled + 1
    for p in (config.logger_provider.processors if config.logger_provider else None) or []:
        for proc in (p.batch, p.simple):
            exp = proc.exporter.otlp_http if proc and proc.exporter else None
            if exp is not None and not exp.endpoint:
                exp.endpoint, filled = base + "/v1/logs", filled + 1
    st.put("OTEL_EXPORTER_OTLP_ENDPOINT", base, "distro_default" if filled else "file")
    st.put("runtime_metrics.profile", settings.runtime_metrics_profile, "file")
    if config.disabled:
        st.fail("disabled", "sdk_disabled")


class _ConfigFileError(Exception):
    pass


def _report(reason: str) -> None:
    st = Status()
    st.fail("error", reason)
    st.write(Settings.from_block(None).status_dir)


class KloudMateConfigurator(_OTelSDKConfigurator):
    """Selected with OTEL_PYTHON_CONFIGURATOR=kloudmate."""

    def _configure(self, **kwargs) -> None:
        path = os.environ.get("OTEL_CONFIG_FILE")
        if path:
            try:
                self._configure_file(path)
            except _ConfigFileError as exc:
                # Upstream would fail on the same file; the app runs uninstrumented rather than crash.
                _logger.warning("OTEL_CONFIG_FILE %s could not be loaded, OpenTelemetry stays off: %s",
                                path, str(exc).splitlines()[0] if str(exc) else type(exc).__name__)
            except Exception:
                _logger.warning("kloudmate configurator failed, using upstream configuration", exc_info=True)
                _report("configurator_failed")
                super()._configure(**kwargs)
            return
        try:
            self._configure_env(**kwargs)
        except Exception:
            _logger.warning("kloudmate configurator failed, using upstream configuration", exc_info=True)
            _report("configurator_failed")
            super()._configure(**kwargs)

    def _configure_env(self, **kwargs) -> None:
        from opentelemetry.sdk._configuration import _initialize_components

        settings = Settings.from_block(None)
        st = _env_status(settings)
        st.service_id = _resource_attr_from_env(KM_SERVICE_ID) or settings.service_id
        st.check_foreign_agents()
        st.write(settings.status_dir)
        remember(st, settings.status_dir)
        attrs = dict(kwargs.pop("resource_attributes", None) or {})
        attrs.update(stamp_attributes(settings.service_id))
        _initialize_components(resource_attributes=attrs, **kwargs)

    def _configure_file(self, path: str) -> None:
        from opentelemetry.sdk._configuration._sdk import configure_sdk
        from opentelemetry.sdk._configuration.file._loader import load_config_file

        st = Status()
        st.config_hash = file_hash(path)
        st.put("OTEL_CONFIG_FILE", path, "app_env")
        distro_set = set(_distro.DEFAULTS) - _distro.APP_SET
        for name in app_substitutions(file_text(path), os.environ, distro_set):
            st.put(name, os.environ[name], "app_env")
            st.overridden(name)
        try:
            config = load_config_file(path)
        except Exception as exc:
            st.fail("error", "config_parse_error")
            st.write(Settings.from_block(None).status_dir)
            raise _ConfigFileError(str(exc)) from exc
        settings = Settings.from_block((config.distribution or {}).get("kloudmate"))
        complete_model(config, settings, st)
        st.check_foreign_agents()
        st.write(settings.status_dir)
        remember(st, settings.status_dir)
        configure_sdk(config)
