"""Helpers shared by the per-language smoke assertions (stdlib only)."""
import glob
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
        """Signals reached the sink with the expected identity and this distro's telemetry.distro.*."""
        for sig in signals:
            res = self.resources(sink, sig)
            self.check(bool(res), f"[{name}] {sig} reached the {sink} sink")
            for r in res[:1]:
                self.check(r.get("km.service_id") == service_id,
                           f"[{name}] {sig} km.service_id={r.get('km.service_id')!r} want {service_id!r}")
                self.check(r.get("service.name") == service_name,
                           f"[{name}] {sig} service.name={r.get('service.name')!r} want {service_name!r}")
                self.check(r.get("telemetry.distro.name") == self.distro,
                           f"[{name}] {sig} telemetry.distro.name={r.get('telemetry.distro.name')!r}")
                self.check(bool(r.get("telemetry.distro.version")), f"[{name}] {sig} telemetry.distro.version set")

    def done(self):
        if self.fails:
            print(f"{len(self.fails)} assertion(s) failed")
            sys.exit(1)
