"""Smoke app: one urllib client call (a span), one log record, then stays up for a metric export."""
import logging
import os
import time
import urllib.request

target = os.environ.get("SMOKE_TARGET", "http://127.0.0.1:4318/health")
try:
    urllib.request.urlopen(target, timeout=2).read()
except Exception:
    pass  # a span is recorded either way
logging.getLogger("smoke").warning("smoke log record")
time.sleep(float(os.environ.get("SMOKE_SLEEP_S", "3")))
