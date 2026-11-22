import json

from opentelemetry.sdk.trace.sampling import ALWAYS_ON, Decision

from kloudmate_otel import _opamp as op
from kloudmate_otel._status import Status


def server_reply(remote: dict | None, config_hash: bytes = b"h1", flags: int = 0) -> bytes:
    """A ServerToAgent message, encoded with the client's own helpers."""
    msg = b""
    if remote is not None:
        cfg = op._config_map({"kloudmate": (json.dumps(remote).encode(), "application/json")})
        msg += op._bytes(3, op._bytes(1, cfg) + op._bytes(2, config_hash))
    return msg + op._uint(6, flags)


def client(tmp_path, env=None, sampler=True):
    st = Status()
    st.service_id = "systemd:web.service"
    s = op.RemoteSampler(ALWAYS_ON) if sampler else None
    return op.Client("http://x/v1/opamp", st, str(tmp_path), s, "web", 1, post=lambda u, b: b"", env=env or {}), st, s


def sample(s):
    return s.should_sample(None, 0x7FFFFFFFFFFFFFFF0000000000000000, "x").decision


def test_message_fields(tmp_path):
    c, st, _ = client(tmp_path)
    f = op._decode(c.message())
    assert f[1] == [c.uid] and len(c.uid) == 16 and c.uid[6] >> 4 == 7
    assert f[4] == [op.CAPABILITIES]
    desc = op._decode(f[3][0])
    ident = {op._decode(kv)[1][0].decode(): op._decode(op._decode(kv)[2][0]) for kv in desc[1]}
    assert ident["service.name"][1] == [b"web"] and ident["km.service_id"][1] == [b"systemd:web.service"]
    health = op._decode(f[5][0])
    assert health[1] == [1] and health[4] == [b"ok"]
    entry = op._decode(op._decode(op._decode(f[6][0])[1][0])[1][0])
    assert entry[1] == [b"kloudmate.status"]
    body = json.loads(op._decode(entry[2][0])[1][0])
    assert body["km.service_id"] == "systemd:web.service"
    # agent_description only until the server has it
    c.poll_once()
    assert 3 not in op._decode(c.message())


def test_remote_applied_live(tmp_path):
    c, st, s = client(tmp_path)
    assert sample(s) == Decision.RECORD_AND_SAMPLE
    c.handle(server_reply({"sampling_rate": 0, "logging_level": "debug", "future_key": 1}))
    assert sample(s) == Decision.DROP
    assert st.effective["sampling_rate"] == {"value": "0", "origin": "remote"}
    assert (c.remote_status, c.remote_error) == (op.APPLIED, "not applied live: logging_level")
    rcs = op._decode(op._decode(c.message())[7][0])
    assert rcs[1] == [b"h1"] and rcs[2] == [op.APPLIED]
    # an empty config resets to the configured sampler
    c.handle(server_reply({}, b"h2"))
    assert sample(s) == Decision.RECORD_AND_SAMPLE and "sampling_rate" not in st.effective
    c.handle(server_reply({"send_traces": False}, b"h3"))
    assert sample(s) == Decision.DROP


def test_same_hash_not_reapplied(tmp_path):
    c, st, s = client(tmp_path)
    c.handle(server_reply({"send_traces": False}))
    s.set(None, True)
    c.handle(server_reply({"send_traces": False}))
    assert sample(s) == Decision.RECORD_AND_SAMPLE


def test_invalid_remote_fails_and_changes_nothing(tmp_path):
    c, st, s = client(tmp_path)
    for bad, msg in (({"sampling_rate": 2}, "sampling_rate"), ({"sampling_rate": True}, "sampling_rate"),
                     ({"send_traces": "no"}, "send_traces"), ([1, 2], "JSON object")):
        c.remote_hash = b""
        c.handle(server_reply(bad))
        assert c.remote_status == op.FAILED and msg in c.remote_error
        assert sample(s) == Decision.RECORD_AND_SAMPLE


def test_app_settings_win(tmp_path):
    c, st, s = client(tmp_path, env={"OTEL_TRACES_SAMPLER": "always_on"})
    c.handle(server_reply({"sampling_rate": 0, "send_traces": True}))
    assert sample(s) == Decision.RECORD_AND_SAMPLE
    assert st.overridden_by_app == ["sampling_rate"] and "sampling_rate" not in st.effective
    assert c.remote_error == "set by the application: sampling_rate"


def test_full_state_on_request(tmp_path):
    c, st, s = client(tmp_path)
    c.full_state = False
    c.handle(server_reply(None, flags=op.REPORT_FULL_STATE))
    assert 3 in op._decode(c.message())


def test_decoder_rejects_garbage():
    import pytest

    for bad in (b"\x0a\x05ab", b"\xff\xff\xff\xff\xff\xff\xff\xff\xff\xff\xff", b"\x0f"):
        with pytest.raises(Exception):
            op._decode(bad)
