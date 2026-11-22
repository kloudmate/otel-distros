import json
import os

import pytest

from kloudmate_otel import configurator, distro
from kloudmate_otel._settings import Settings
from kloudmate_otel._status import Status


@pytest.fixture(autouse=True)
def clean_env(monkeypatch):
    for k in list(os.environ):
        if k.startswith(("OTEL_", "KM_")):
            monkeypatch.delenv(k)


def test_settings_defaults_and_block():
    s = Settings.from_block(None)
    assert s.endpoint() == "http://127.0.0.1:4318"
    assert s.runtime_metrics_profile == "standard"
    s = Settings.from_block({
        "service_id": "docker:web",
        "agent": {"otlp_endpoint": "http://10.0.0.1:4318/", "otlp_socket": "/run/x.sock"},
        "runtime_metrics": {"profile": "off"},
        "some_future_key": [1, 2],
    })
    assert (s.service_id, s.endpoint(), s.runtime_metrics_profile) == ("docker:web", "http://10.0.0.1:4318", "off")
    assert Settings.from_block({"runtime_metrics": {"profile": "bogus"}}).runtime_metrics_profile == "standard"


def test_distro_defaults_never_override_app(monkeypatch):
    monkeypatch.setenv("OTEL_EXPORTER_OTLP_PROTOCOL", "grpc")
    distro.APP_SET.clear()
    distro.KloudMateDistro()._configure()
    assert os.environ["OTEL_EXPORTER_OTLP_PROTOCOL"] == "grpc"
    assert os.environ["OTEL_EXPORTER_OTLP_ENDPOINT"] == "http://127.0.0.1:4318"
    assert os.environ["OTEL_PROPAGATORS"] == "tracecontext,baggage"
    assert "OTEL_EXPORTER_OTLP_PROTOCOL" in distro.APP_SET


def test_file_mode_leaves_endpoint_env_alone(monkeypatch, tmp_path):
    cfg = tmp_path / "c.yaml"
    cfg.write_text('file_format: "1.0"\n')
    monkeypatch.setenv("OTEL_CONFIG_FILE", str(cfg))
    distro.KloudMateDistro()._configure()
    assert "OTEL_EXPORTER_OTLP_ENDPOINT" not in os.environ


def test_stamp_never_rewrites_service_id(monkeypatch):
    assert configurator.stamp_attributes("systemd:a")["km.service_id"] == "systemd:a"
    monkeypatch.setenv("OTEL_RESOURCE_ATTRIBUTES", "a=b,km.service_id=app-own")
    attrs = configurator.stamp_attributes("systemd:a")
    assert "km.service_id" not in attrs
    assert attrs["telemetry.distro.name"] == "kloudmate-python"


def test_complete_model_fills_only_unset(tmp_path):
    from opentelemetry.configuration import load_config_file

    cfg = tmp_path / "c.yaml"
    cfg.write_text(
        'file_format: "1.0"\n'
        "resource:\n  attributes:\n    - name: km.service_id\n      value: from-file\n"
        "tracer_provider:\n  processors:\n"
        "    - batch:\n        exporter:\n          otlp_http: {}\n"
        "    - batch:\n        exporter:\n          otlp_http:\n            endpoint: http://x:1/v1/traces\n"
        "distribution:\n  kloudmate:\n    service_id: from-block\n    agent:\n      otlp_endpoint: http://agent:4318\n"
    )
    config = load_config_file(str(cfg))
    st = Status()
    configurator.complete_model(config, Settings.from_block(config.distribution["kloudmate"]), st)
    eps = [p.batch.exporter.otlp_http.endpoint for p in config.tracer_provider.processors]
    assert eps == ["http://agent:4318/v1/traces", "http://x:1/v1/traces"]
    attrs = {a.name: a.value for a in config.resource.attributes}
    assert attrs["km.service_id"] == "from-file"
    assert attrs["telemetry.distro.name"] == "kloudmate-python"
    assert st.service_id == "from-file"


def test_status_written_only_to_existing_dir(tmp_path):
    st = Status()
    st.fail("degraded", "foreign_agent:ddtrace")
    st.fail("ok", "ignored")
    assert st.write(str(tmp_path / "missing")) is None
    path = st.write(str(tmp_path))
    doc = json.load(open(path))
    assert (doc["result"], doc["reason"]) == ("degraded", "foreign_agent:ddtrace")
    assert doc["distro"]["name"] == "kloudmate-python"


def test_app_substitutions():
    from kloudmate_otel._status import app_substitutions

    text = ("value: ${OTEL_SERVICE_NAME:-orders}\nx: ${env:OTEL_EXPORTER_OTLP_ENDPOINT:-http://127.0.0.1:4318}\n"
            "y: ${OTEL_TRACES_SAMPLER_ARG:-1.0}\nz: ${OTEL_CONFIG_FILE}\nw: ${OTEL_PROPAGATORS:-tracecontext}\n")
    env = {"OTEL_SERVICE_NAME": "checkout", "OTEL_EXPORTER_OTLP_ENDPOINT": " ", "OTEL_CONFIG_FILE": "/x",
           "OTEL_PROPAGATORS": "tracecontext,baggage"}
    # OTEL_PROPAGATORS was put there by the distro, not the app
    assert app_substitutions(text, env, {"OTEL_PROPAGATORS"}) == ["OTEL_SERVICE_NAME"]
    assert app_substitutions(None, env) == []


def test_status_overridden_sorted_and_instrumentations(tmp_path):
    from kloudmate_otel import _status

    st = Status()
    st.overridden("OTEL_SERVICE_NAME")
    st.overridden("OTEL_EXPORTER_OTLP_ENDPOINT")
    st.overridden("OTEL_SERVICE_NAME")
    assert st.overridden_by_app == ["OTEL_EXPORTER_OTLP_ENDPOINT", "OTEL_SERVICE_NAME"]
    _status.remember(st, str(tmp_path))
    _status.record_instrumentation("flask")
    _status.record_instrumentation("flask")
    _status.record_instrumentation("requests")
    data = json.loads((tmp_path / ("%d.json" % os.getpid())).read_text())
    assert data["instrumentations"] == ["flask", "requests"]
    assert data["overridden_by_app"] == ["OTEL_EXPORTER_OTLP_ENDPOINT", "OTEL_SERVICE_NAME"]
    _status.CURRENT.clear()


@pytest.fixture
def agent_socket(tmp_path):
    import socket

    path = str(tmp_path / "otlp.sock")
    s = socket.socket(socket.AF_UNIX, socket.SOCK_STREAM)
    s.bind(path)
    s.listen(1)
    yield path
    s.close()


def test_socket_target(monkeypatch, tmp_path, agent_socket):
    monkeypatch.setattr("kloudmate_otel._settings.grpc_exporter_installed", lambda: True)
    monkeypatch.setenv("KM_OTLP_SOCKET", agent_socket)
    assert Settings.from_block(None).socket_target() == "unix://" + agent_socket
    # the block's path wins over KM_OTLP_SOCKET
    monkeypatch.setenv("KM_OTLP_SOCKET", "/nowhere.sock")
    assert Settings.from_block({"agent": {"otlp_socket": agent_socket}}).socket_target() == "unix://" + agent_socket
    s = Settings.from_block({"agent": {"otlp_socket": agent_socket}})
    for k in ("OTEL_EXPORTER_OTLP_ENDPOINT", "OTEL_EXPORTER_OTLP_PROTOCOL", "OTEL_EXPORTER_OTLP_METRICS_ENDPOINT",
              "OTEL_EXPORTER_OTLP_TRACES_PROTOCOL"):
        assert s.socket_target({k: "x"}) is None, k
    plain = tmp_path / "plain"
    plain.write_text("")
    for p in (str(plain), str(tmp_path / "missing.sock"), "relative.sock"):
        assert Settings.from_block({"agent": {"otlp_socket": p}}).socket_target() is None, p
    monkeypatch.setattr("kloudmate_otel._settings.grpc_exporter_installed", lambda: False)
    assert s.socket_target() is None


def test_distro_defaults_socket(monkeypatch, agent_socket):
    monkeypatch.setattr("kloudmate_otel._settings.grpc_exporter_installed", lambda: True)
    monkeypatch.setenv("KM_OTLP_SOCKET", agent_socket)
    d = distro.defaults(Settings.from_block(None), False)
    assert (d["OTEL_EXPORTER_OTLP_PROTOCOL"], d["OTEL_EXPORTER_OTLP_ENDPOINT"], d["OTEL_EXPORTER_OTLP_INSECURE"]) == (
        "grpc", "unix://" + agent_socket, "true")
    d = distro.defaults(Settings.from_block(None), True)
    assert d["OTEL_EXPORTER_OTLP_PROTOCOL"] == "http/protobuf" and "OTEL_EXPORTER_OTLP_INSECURE" not in d
    monkeypatch.setattr("kloudmate_otel._settings.grpc_exporter_installed", lambda: False)
    d = distro.defaults(Settings.from_block(None), False)
    assert (d["OTEL_EXPORTER_OTLP_PROTOCOL"], d["OTEL_EXPORTER_OTLP_ENDPOINT"]) == ("http/protobuf", "http://127.0.0.1:4318")


def test_fill_grpc_socket(tmp_path):
    from opentelemetry.configuration import load_config_file

    p = tmp_path / "c.yaml"
    p.write_text(
        'file_format: "1.0"\n'
        "tracer_provider:\n  processors:\n"
        "    - batch:\n        exporter:\n          otlp_grpc:\n"
        "    - simple:\n        exporter:\n          otlp_http: {}\n"
        "meter_provider:\n  readers:\n"
        "    - periodic:\n        exporter:\n          otlp_grpc:\n            endpoint: http://collector:4317\n"
        "logger_provider:\n  processors:\n"
        "    - batch:\n        exporter:\n          otlp_grpc:\n            tls:\n              ca_file: /ca.pem\n"
    )
    config = load_config_file(str(p))
    assert configurator.fill_grpc_socket(config, "unix:///run/otlp.sock") == ["TRACES", "LOGS"]
    tr = config.tracer_provider.processors[0].batch.exporter.otlp_grpc
    assert (tr.endpoint, tr.tls.insecure) == ("unix:///run/otlp.sock", True)
    assert config.tracer_provider.processors[1].simple.exporter.otlp_http.endpoint is None
    assert config.meter_provider.readers[0].periodic.exporter.otlp_grpc.endpoint == "http://collector:4317"
    lg = config.logger_provider.processors[0].batch.exporter.otlp_grpc
    assert (lg.endpoint, lg.tls.ca_file, lg.tls.insecure) == ("unix:///run/otlp.sock", "/ca.pem", None)
