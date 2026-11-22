"""Helpers shared by the per-language smoke assertions (stdlib only)."""
import glob
import hashlib
import json
import os
import sys


class Checker:
    def __init__(self, work, distro):
        self.work = work
        self.distro = distro
        self.fails = []

    def records(self, sink):
        p = os.path.join(self.work, sink + ".jsonl")
        if not os.path.exists(p):
            return []
        return [json.loads(line) for line in open(p) if line.strip()]

    def check(self, cond, msg):
        print(("  ok    " if cond else "  FAIL  ") + msg)
        if not cond:
            self.fails.append(msg)

    def resources(self, sink, signal):
        return [r for rec in self.records(sink) if rec["signal"] == signal for r in rec["resources"]]

    def names(self, sink, signal):
        return {n for rec in self.records(sink) if rec["signal"] == signal for n in rec.get("names") or []}

    def status(self, name):
        files = glob.glob(os.path.join(self.work, "status-" + name, "*.json"))
        return json.load(open(files[0])) if files else None

    def common(self, name, sink, service_name, service_id, signals=("traces", "metrics")):
        """Signals for service_name reached the sink with the expected identity and this distro's telemetry.distro.*.
        Records are matched by service.name, so several scenarios can share one sink."""
        for sig in signals:
            res = [r for r in self.resources(sink, sig) if r.get("service.name") == service_name]
            self.check(bool(res), f"[{name}] {sig} for service.name={service_name!r} reached the {sink} sink")
            for r in res[:1]:
                self.check(r.get("km.service_id") == service_id,
                           f"[{name}] {sig} km.service_id={r.get('km.service_id')!r} want {service_id!r}")
                self.check(r.get("telemetry.distro.name") == self.distro,
                           f"[{name}] {sig} telemetry.distro.name={r.get('telemetry.distro.name')!r}")
                self.check(bool(r.get("telemetry.distro.version")), f"[{name}] {sig} telemetry.distro.version set")

    def opamp(self, name, service_name, service_id, sink="default"):
        """What smoke/opampsink recorded for this process, with remote.json {"sampling_rate": 0, "logging_level":
        "debug"}: its identity, health and status; the remote config acknowledged as applied, logging_level as not
        live; and no spans from the process in the sink, while its metrics still arrive."""
        recs = [r for r in self.records("opamp") if (r.get("status") or {}).get("km.service_id") == service_id]
        self.check(bool(recs), f"[{name}] OpAMP messages reached the server")
        if not recs:
            return
        first = next((r for r in recs if r.get("identifying")), {})
        ident, other = first.get("identifying") or {}, first.get("non_identifying") or {}
        self.check((ident.get("service.name"), ident.get("km.service_id")) == (service_name, service_id),
                   f"[{name}] OpAMP identity {ident}")
        self.check(other.get("telemetry.distro.name") == self.distro and other.get("process.pid", "").isdigit(),
                   f"[{name}] OpAMP description {other}")
        self.check(len({r["instance_uid"] for r in recs}) == 1 and len(recs[0]["instance_uid"]) == 32,
                   f"[{name}] one 16-byte instance_uid")
        seqs = [r["sequence_num"] for r in recs]
        self.check(seqs == sorted(seqs) and len(set(seqs)) == len(seqs), f"[{name}] sequence numbers increase {seqs}")
        self.check(all(r.get("healthy") and r.get("health_status") == "ok" for r in recs), f"[{name}] healthy, status ok")
        want = hashlib.sha256(open(os.path.join(self.work, "remote.json"), "rb").read()).hexdigest()
        applied = [r for r in recs if r.get("remote_config_status") == "RemoteConfigStatuses_APPLIED"]
        self.check(bool(applied) and applied[-1].get("remote_config_hash") == want,
                   f"[{name}] remote config acknowledged as applied")
        if applied:
            self.check(applied[-1].get("remote_config_error") == "not applied live: logging_level",
                       f"[{name}] remote config note {applied[-1].get('remote_config_error')!r}")
        rate = recs[-1]["status"]["effective_config"].get("sampling_rate")
        self.check(rate == {"value": "0", "origin": "remote"}, f"[{name}] reported sampling_rate {rate}")
        st = self.status(name)
        self.check(st is not None and st["effective_config"].get("sampling_rate") == {"value": "0", "origin": "remote"},
                   f"[{name}] status file rewritten with the remote setting")
        self.check(not [r for r in self.resources(sink, "traces") if r.get("service.name") == service_name],
                   f"[{name}] sampling_rate 0 applied live: no spans")
        self.check(bool([r for r in self.resources(sink, "metrics") if r.get("service.name") == service_name]),
                   f"[{name}] metrics still sent")

    def done(self):
        if self.fails:
            print(f"{len(self.fails)} assertion(s) failed")
            sys.exit(1)
