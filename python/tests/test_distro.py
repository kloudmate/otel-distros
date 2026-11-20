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
    from opentelemetry.sdk._configuration.file._loader import load_config_file

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
