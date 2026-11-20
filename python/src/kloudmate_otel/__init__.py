"""KloudMate distribution of OpenTelemetry Python.

Loaded through the standard ``opentelemetry_distro`` and ``opentelemetry_configurator`` entry points
(``opentelemetry-instrument``, or the KloudMate ``sitecustomize`` bootstrap). Upstream instrumentation is
used as is; this package only sets defaults, stamps the resource, reads ``distribution.kloudmate`` and
reports a startup status. It never raises into the application.
"""

__version__ = "0.1.0"
DISTRO_NAME = "kloudmate-python"
