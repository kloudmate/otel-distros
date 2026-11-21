"""OpAMP client (contract §4): reports health and the startup status to the local KloudMate Agent over OpAMP's
plain-HTTP transport, and applies the remote settings that can change live (``sampling_rate``, ``send_traces``)
through a sampler installed at startup. Off unless an OpAMP endpoint is configured. Standard library only; the
handful of OpAMP messages it needs are encoded by hand. Never raises into the app."""

from __future__ import annotations

import json
import logging
import os
import random
import struct
import threading
import time
import urllib.request
from typing import Any, Callable, Mapping

from opentelemetry.sdk.trace.sampling import (
    ALWAYS_OFF,
    ParentBased,
    Sampler,
    SamplingResult,
    TraceIdRatioBased,
)

from . import DISTRO_NAME, __version__

_logger = logging.getLogger(__name__)

DEFAULT_POLL_SECONDS = 30.0
# ReportsStatus | AcceptsRemoteConfig | ReportsEffectiveConfig | ReportsHealth | ReportsRemoteConfig
CAPABILITIES = 0x1 | 0x2 | 0x4 | 0x800 | 0x1000
APPLIED, FAILED = 1, 3
REPORT_FULL_STATE = 0x1

# Remote keys (contract §4): the ones applied live, and the ones accepted but applied only by a restart, which a
# remote setting (in memory only) never gets.
LIVE = ("sampling_rate", "send_traces")
NOT_LIVE = ("logging_level", "send_metrics", "send_logs", "deactivate_instrumentations",
            "deactivate_all_instrumentations", "metrics_profile")
# The app's own setting for each live key: when it set that itself, the remote value isn't applied.
APP_KEYS = {"sampling_rate": ("OTEL_TRACES_SAMPLER", "OTEL_TRACES_SAMPLER_ARG"), "send_traces": ("OTEL_TRACES_EXPORTER",)}


# --- a minimal protobuf codec: varint, fixed64 and length-delimited fields ---------------------------------------

def _varint(n: int) -> bytes:
    out = bytearray()
    while True:
        b = n & 0x7F
        n >>= 7
        if n:
            out.append(b | 0x80)
        else:
            out.append(b)
            return bytes(out)


def _field(num: int, wire: int) -> bytes:
    return _varint(num << 3 | wire)


def _uint(num: int, v: int) -> bytes:
    return _field(num, 0) + _varint(v) if v else b""


def _bool(num: int, v: bool) -> bytes:
    return _field(num, 0) + b"\x01" if v else b""


def _fixed64(num: int, v: int) -> bytes:
    return _field(num, 1) + struct.pack("<Q", v) if v else b""


def _bytes(num: int, v: bytes) -> bytes:
    return _field(num, 2) + _varint(len(v)) + v


def _str(num: int, v: str) -> bytes:
    return _bytes(num, v.encode("utf-8")) if v else b""


def _decode(buf: bytes) -> dict[int, list[Any]]:
    """Fields by number: ints for varint/fixed, bytes for length-delimited. Raises on malformed input."""
    out: dict[int, list[Any]] = {}
    i = 0
    while i < len(buf):
        key, i = _read_varint(buf, i)
        num, wire = key >> 3, key & 7
        if wire == 0:
            v, i = _read_varint(buf, i)
        elif wire == 1:
            v, i = struct.unpack_from("<Q", buf, i)[0], i + 8
        elif wire == 2:
            n, i = _read_varint(buf, i)
            v, i = buf[i:i + n], i + n
            if len(v) != n:
                raise ValueError("truncated field")
        elif wire == 5:
            v, i = struct.unpack_from("<I", buf, i)[0], i + 4
        else:
            raise ValueError("unsupported wire type %d" % wire)
        out.setdefault(num, []).append(v)
    return out


def _read_varint(buf: bytes, i: int) -> tuple[int, int]:
    n = shift = 0
    while True:
        if i >= len(buf) or shift > 63:
            raise ValueError("bad varint")
        b = buf[i]
        i += 1
        n |= (b & 0x7F) << shift
        if not b & 0x80:
            return n, i
        shift += 7


def _kv(key: str, value: Any) -> bytes:
    """opentelemetry KeyValue with an AnyValue of string or int."""
    if isinstance(value, int):
        any_value = _field(3, 0) + _varint(value & 0xFFFFFFFFFFFFFFFF)
    else:
        any_value = _str(1, str(value))
    return _str(1, key) + _bytes(2, any_value)


def _config_map(entries: Mapping[str, tuple[bytes, str]]) -> bytes:
    """AgentConfigMap: map<string, AgentConfigFile{body, content_type}>."""
    return b"".join(_bytes(1, _str(1, k) + _bytes(2, _bytes(1, body) + _str(2, ctype)))
                    for k, (body, ctype) in entries.items())


def uuid7() -> bytes:
    ms = int(time.time() * 1000)
    b = bytearray(ms.to_bytes(6, "big") + os.urandom(10))
    b[6] = 0x70 | (b[6] & 0x0F)
    b[8] = 0x80 | (b[8] & 0x3F)
    return bytes(b)


def _uuid_str(b: bytes) -> str:
    h = b.hex()
    return "%s-%s-%s-%s-%s" % (h[:8], h[8:12], h[12:16], h[16:20], h[20:])


# --- remote settings ----------------------------------------------------------------------------------------------

class RemoteSampler(Sampler):
    """The configured sampler until a remote setting says otherwise: ``send_traces: false`` drops every span, and a
    ``sampling_rate`` samples parent-based at that ratio."""

    def __init__(self, delegate: Sampler) -> None:
        self.delegate = delegate
        self._override: Sampler | None = None

    def set(self, rate: float | None, send: bool) -> None:
        if not send:
            self._override = ALWAYS_OFF
        elif rate is not None:
            self._override = ParentBased(TraceIdRatioBased(rate))
        else:
            self._override = None

    def should_sample(self, parent_context, trace_id, name, kind=None, attributes=None, links=None,
                      trace_state=None) -> SamplingResult:
        s = self._override or self.delegate
        return s.should_sample(parent_context, trace_id, name, kind, attributes, links, trace_state)

    def get_description(self) -> str:
        return "KloudMateRemote{%s}" % (self._override or self.delegate).get_description()


def parse_remote(body: bytes | None) -> dict[str, Any]:
    """The remote settings, validated. Raises ValueError with a message for the server."""
    if not body or not body.strip():
        return {}
    try:
        data = json.loads(body.decode("utf-8"))
    except Exception:
        raise ValueError("remote config is not a JSON object")
    if not isinstance(data, dict):
        raise ValueError("remote config is not a JSON object")
    if "sampling_rate" in data:
        r = data["sampling_rate"]
        if isinstance(r, bool) or not isinstance(r, (int, float)) or not 0 <= r <= 1:
            raise ValueError("sampling_rate: a number from 0 to 1")
    if "send_traces" in data and not isinstance(data["send_traces"], bool):
        raise ValueError("send_traces: true or false")
    return {k: v for k, v in data.items() if k in LIVE or k in NOT_LIVE}


# --- the client -----------------------------------------------------------------------------------------------------

class Client:
    def __init__(self, endpoint: str, status: Any, status_dir: str, sampler: RemoteSampler | None,
                 service_name: str, interval: float = DEFAULT_POLL_SECONDS,
                 post: Callable[[str, bytes], bytes] | None = None, env: Mapping[str, str] | None = None) -> None:
        self.endpoint = endpoint
        self.status = status
        self.status_dir = status_dir
        self.sampler = sampler
        self.service_name = service_name
        self.interval = interval
        self.post = post or _post
        self.env = os.environ if env is None else env
        self.uid = uuid7()
        self.seq = 0
        self.start_ns = time.time_ns()
        self.full_state = True
        self.remote_hash = b""
        self.remote_status = 0
        self.remote_error = ""
        self.applied: dict[str, Any] = {}
        self._stop = threading.Event()

    def message(self) -> bytes:
        st = self.status
        healthy = st.result in ("ok", "degraded")
        health = (_bool(1, healthy) + _fixed64(2, self.start_ns) + _str(3, "" if st.result == "ok" else st.reason)
                  + _str(4, st.result))
        status_json = json.dumps(st.to_dict(), separators=(",", ":")).encode("utf-8")
        msg = _bytes(1, self.uid) + _uint(2, self.seq)
        if self.full_state:
            ident = [("service.name", self.service_name), ("service.instance.id", _uuid_str(self.uid))]
            if st.service_id:
                ident.append(("km.service_id", st.service_id))
            other = [("telemetry.distro.name", DISTRO_NAME), ("telemetry.distro.version", __version__),
                     ("process.pid", os.getpid()), ("os.type", "linux")]
            msg += _bytes(3, b"".join(_bytes(1, _kv(k, v)) for k, v in ident)
                          + b"".join(_bytes(2, _kv(k, v)) for k, v in other))
        msg += _uint(4, CAPABILITIES) + _bytes(5, health)
        msg += _bytes(6, _bytes(1, _config_map({"kloudmate.status": (status_json, "application/json")})))
        if self.remote_hash:
            msg += _bytes(7, _bytes(1, self.remote_hash) + _uint(2, self.remote_status) + _str(3, self.remote_error))
        return msg

    def handle(self, reply: bytes) -> None:
        f = _decode(reply)
        if REPORT_FULL_STATE & (f.get(6) or [0])[-1]:
            self.full_state = True
        for rc in f.get(3) or []:
            rcf = _decode(rc)
            h = (rcf.get(2) or [b""])[-1]
            if not h or h == self.remote_hash:
                continue
            body = None
            for cm in rcf.get(1) or []:
                for entry in _decode(cm).get(1) or []:
                    e = _decode(entry)
                    if (e.get(1) or [b""])[-1] == b"kloudmate":
                        body = (_decode((e.get(2) or [b""])[-1]).get(1) or [b""])[-1]
            self.apply(h, body)

    def apply(self, config_hash: bytes, body: bytes | None) -> None:
        self.remote_hash = config_hash
        try:
            remote = parse_remote(body)
        except ValueError as exc:
            self.remote_status, self.remote_error = FAILED, str(exc)
            return
        notes = []
        overridden = [k for k in LIVE if k in remote and any(self.env.get(a) for a in APP_KEYS[k])]
        not_live = sorted(k for k in remote if k in NOT_LIVE)
        live = {k: v for k, v in remote.items() if k in LIVE and k not in overridden}
        if self.sampler is None and live:
            not_live = sorted(not_live + list(live))
            live = {}
        if self.sampler is not None:
            self.sampler.set(live.get("sampling_rate"), live.get("send_traces", True))
        st = self.status
        for k in LIVE:
            st.effective.pop(k, None)
        for k, v in live.items():
            st.put(k, json.dumps(v), "remote")
        for k in overridden:
            st.overridden(k)
        self.applied = live
        if overridden:
            notes.append("set by the application: " + ", ".join(overridden))
        if not_live:
            notes.append("not applied live: " + ", ".join(not_live))
        self.remote_status, self.remote_error = APPLIED, "; ".join(notes)
        st.write(self.status_dir)

    def poll_once(self) -> None:
        reply = self.post(self.endpoint, self.message())
        self.seq += 1
        self.full_state = False
        self.handle(reply)

    def run(self) -> None:
        while not self._stop.is_set():
            try:
                self.poll_once()
            except Exception as exc:  # the agent may not be up yet; try again next time
                self.full_state = True
                _logger.debug("OpAMP poll failed: %s", exc)
            self._stop.wait(self.interval * random.uniform(0.9, 1.1))

    def start(self) -> None:
        threading.Thread(target=self.run, name="kloudmate-opamp", daemon=True).start()

    def stop(self) -> None:
        self._stop.set()


def _post(url: str, body: bytes) -> bytes:
    """POSTs with instrumentation suppressed: the client's own requests are never traced."""
    from opentelemetry.context import _SUPPRESS_INSTRUMENTATION_KEY, attach, detach, set_value

    token = attach(set_value(_SUPPRESS_INSTRUMENTATION_KEY, True))
    try:
        req = urllib.request.Request(url, data=body, method="POST",
                                     headers={"Content-Type": "application/x-protobuf"})
        with urllib.request.urlopen(req, timeout=10) as resp:  # noqa: S310 - the local agent's URL from configuration
            return resp.read()
    finally:
        detach(token)


def poll_interval(env: Mapping[str, str] | None = None) -> float:
    env = os.environ if env is None else env
    try:
        return max(1.0, float(env.get("KM_OPAMP_POLL_INTERVAL") or DEFAULT_POLL_SECONDS))
    except ValueError:
        return DEFAULT_POLL_SECONDS


def install_sampler(provider: Any) -> RemoteSampler | None:
    """Wraps the tracer provider's sampler before any tracer is created. None when the provider has none."""
    try:
        current = getattr(provider, "sampler", None)
        if not isinstance(current, Sampler):
            return None
        wrapped = RemoteSampler(current)
        provider.sampler = wrapped
        return wrapped
    except Exception:
        return None


_CLIENT: list[Client] = []


def start(endpoint: str | None, status: Any, status_dir: str, provider: Any, service_name: str) -> Client | None:
    """Starts the client when an endpoint is configured; in a forked child, a new one with its own identity."""
    if not endpoint:
        return None
    try:
        sampler = install_sampler(provider)
        client = Client(endpoint, status, status_dir, sampler, service_name, poll_interval())
        client.start()
        _CLIENT[:] = [client]

        def _after_fork() -> None:
            old = _CLIENT[0] if _CLIENT else client
            child = Client(endpoint, status, status_dir, sampler, service_name, old.interval)
            child.remote_hash, child.applied = old.remote_hash, old.applied
            child.remote_status, child.remote_error = old.remote_status, old.remote_error
            child.start()
            _CLIENT[:] = [child]

        os.register_at_fork(after_in_child=_after_fork)
        return client
    except Exception:
        _logger.debug("OpAMP client not started", exc_info=True)
        return None
