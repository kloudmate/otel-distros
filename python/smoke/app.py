"""Smoke app: one urllib client call (a span), one log record, then stays up for a metric export."""
import logging
import os
import time
import urllib.request

target = os.environ.get("SMOKE_TARGET", "http://127.0.0.1:4318/health")
time.sleep(float(os.environ.get("SMOKE_DELAY_S", "0")))  # e.g. until a remote config has arrived
try:
    urllib.request.urlopen(target, timeout=2).read()
except Exception:
    pass  # a span is recorded either way
logging.getLogger("smoke").warning("smoke log record")
time.sleep(float(os.environ.get("SMOKE_SLEEP_S", "3")))
