# -*- coding: utf-8 -*-
"""KloudMate Python bootstrap: the startup check, then OpenTelemetry auto-instrumentation.

CPython imports this at startup when its directory is on PYTHONPATH. The directory holds one package cell
per interpreter minor (3.10/, 3.11/, ...), because C-extension wheels are version-specific.

Written in syntax every CPython from 2.7 parses, stdlib only, so the versions it rejects still get a status
file saying why (contract §5). It never raises: the app always starts, instrumented or not.
"""

import os
import sys

MIN_VERSION = (3, 10)
DISTRO_VERSION = "0.0.0-dev"  # replaced by release.sh


def _status(result, reason):
    """Writes <status_dir>/<pid>.json for a result the distro itself can't report."""
    try:
        import json
        import time

        d = os.environ.get("KM_SDK_STATUS_DIR") or "/run/kmagent/sdk-status"
        if not os.path.isdir(d):
            return
        now = time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime())
        doc = {
            "schema_version": 1,
            "pid": os.getpid(),
            "km.service_id": os.environ.get("KM_SERVICE_ID", ""),
            "distro": {"name": "kloudmate-python", "version": _version()},
            "result": result,
            "reason": reason,
            "started_at": now,
            "heartbeat_at": now,
            "config_hash": "",
            "effective_config": {},
            "overridden_by_app": [],
            "instrumentations": [],
            "runtime": {"name": "python", "version": "%d.%d.%d" % tuple(sys.version_info[:3])},
        }
        path = os.path.join(d, "%d.json" % os.getpid())
        tmp = os.path.join(d, ".%d.json.tmp" % os.getpid())
        f = open(tmp, "w")
        try:
            f.write(json.dumps(doc, separators=(",", ":")) + "\n")
        finally:
            f.close()
        os.rename(tmp, path)
    except Exception:
        pass


def _version():
    if DISTRO_VERSION != "0.0.0-dev":
        return DISTRO_VERSION
    try:
        f = open(os.path.join(os.path.dirname(os.path.abspath(__file__)), "VERSION"))
        try:
            return f.read().strip()
        finally:
            f.close()
    except Exception:
        return DISTRO_VERSION


def _main():
    if os.environ.get("OTEL_SDK_DISABLED", "").strip().lower() == "true":
        _status("disabled", "sdk_disabled")
        return
    if sys.version_info[:2] < MIN_VERSION:
        _status("unsupported_runtime", "python_too_old")
        return
    if getattr(sys, "implementation", None) is not None and sys.implementation.name != "cpython":
        _status("unsupported_runtime", "python_implementation:" + sys.implementation.name)
        return
    base = os.path.dirname(os.path.abspath(__file__))
    # One cell per minor; a musl interpreter takes the <minor>-musl cell, as native wheels differ by libc.
    name = "%d.%d" % sys.version_info[:2]
    if "musl" in (getattr(sys.implementation, "_multiarch", "") or ""):
        name += "-musl"
    cell = os.path.join(base, name)
    if not os.path.isdir(cell):
        _status("unsupported_runtime", "no_bundle_for_python_" + name)
        return
    if cell not in sys.path:
        sys.path.insert(0, cell)
    # Select the KloudMate distro and configurator unless the app chose others.
    os.environ.setdefault("OTEL_PYTHON_DISTRO", "kloudmate")
    os.environ.setdefault("OTEL_PYTHON_CONFIGURATOR", "kloudmate")
    try:
        from opentelemetry.instrumentation.auto_instrumentation import initialize

        initialize()
    except Exception as exc:
        _status("error", "initialize_failed:" + type(exc).__name__)
        return
    # An app that sets its own MeterProvider later would be ignored by the API's set-once latch, silently
    # dropping its instruments. Reopen the latch once so the app's provider wins (guarded: a renamed
    # internal degrades to upstream behaviour).
    try:
        from opentelemetry.metrics._internal import _METER_PROVIDER_SET_ONCE

        _METER_PROVIDER_SET_ONCE._done = False
    except Exception:
        pass


try:
    _main()
except Exception:
    pass
