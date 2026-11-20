package com.kloudmate.otel;

import java.util.Collections;
import java.util.Map;

/**
 * The {@code distribution.kloudmate} block of the generated declarative config (contract §1), or the
 * environment when the process has no config file. Unknown keys are ignored.
 */
final class KmSettings {
  final String serviceId;
  final String otlpSocket;
  final String otlpEndpoint;
  final String runtimeMetricsProfile;
  final String statusDir;

  KmSettings(String serviceId, String otlpSocket, String otlpEndpoint, String runtimeMetricsProfile, String statusDir) {
    this.serviceId = serviceId;
    this.otlpSocket = otlpSocket;
    this.otlpEndpoint = otlpEndpoint;
    this.runtimeMetricsProfile = runtimeMetricsProfile;
    this.statusDir = statusDir;
  }

  /** Settings from the environment only (no config file). */
  static KmSettings fromEnv() {
    return fromBlock(Collections.<String, Object>emptyMap());
  }

  /** Settings from a parsed {@code distribution.kloudmate} map, with the environment as fallback. */
  static KmSettings fromBlock(Map<String, Object> block) {
    if (block == null) {
      block = Collections.emptyMap();
    }
    String id = str(block.get("service_id"));
    if (id == null) {
      id = KmDistro.serviceIdFromEnv();
    }
    Map<String, Object> agent = map(block.get("agent"));
    Map<String, Object> rm = map(block.get("runtime_metrics"));
    String profile = str(rm.get("profile"));
    if (profile == null) {
      profile = "standard";
    }
    String dir = str(block.get("status_dir"));
    if (dir == null) {
      dir = KmDistro.env("KM_SDK_STATUS_DIR");
    }
    if (!KmDistro.notBlank(dir)) {
      dir = KmDistro.DEFAULT_STATUS_DIR;
    }
    return new KmSettings(id, str(agent.get("otlp_socket")), str(agent.get("otlp_endpoint")), profile, dir);
  }

  /**
   * Local-agent endpoint discovery (contract §1). The Java OTLP exporters have no Unix-socket transport,
   * so {@code otlp_socket} is skipped and the TCP fallback is used: the block's {@code otlp_endpoint}, else
   * {@code http://127.0.0.1:4318}. 127.0.0.1 rather than upstream's localhost, which resolves to ::1 first on
   * many hosts where the agent only listens on IPv4.
   */
  String endpoint() {
    return KmDistro.notBlank(otlpEndpoint) ? otlpEndpoint.trim() : KmDistro.DEFAULT_ENDPOINT;
  }

  @SuppressWarnings("unchecked")
  static Map<String, Object> map(Object o) {
    return o instanceof Map ? (Map<String, Object>) o : Collections.<String, Object>emptyMap();
  }

  static String str(Object o) {
    if (o == null) {
      return null;
    }
    String s = String.valueOf(o).trim();
    return s.isEmpty() ? null : s;
  }
}
