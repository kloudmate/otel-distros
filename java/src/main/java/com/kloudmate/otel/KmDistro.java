package com.kloudmate.otel;

import java.io.File;
import java.io.InputStream;
import java.util.Locale;
import java.util.Properties;

/** Constants and small helpers shared by the KloudMate Java distro. Never throws. */
final class KmDistro {
  static final String NAME = "kloudmate-java";
  static final String VERSION;
  static final String AGENT_VERSION;

  static final String KM_SERVICE_ID = "km.service_id";
  static final String DISTRO_NAME = "telemetry.distro.name";
  static final String DISTRO_VERSION = "telemetry.distro.version";

  static final String DEFAULT_ENDPOINT = "http://127.0.0.1:4318";
  static final String DEFAULT_STATUS_DIR = "/run/kmagent/sdk-status";

  static {
    String v = "0.0.0";
    String a = "";
    try (InputStream in = KmDistro.class.getResourceAsStream("distro.properties")) {
      if (in != null) {
        Properties p = new Properties();
        p.load(in);
        v = p.getProperty("version", v);
        a = p.getProperty("agent.version", a);
      }
    } catch (Throwable ignored) {
      // keep the fallback
    }
    VERSION = v;
    AGENT_VERSION = a;
  }

  private KmDistro() {}

  /** The env var name of a dotted OTel property: otel.exporter.otlp.endpoint -> OTEL_EXPORTER_OTLP_ENDPOINT. */
  static String envName(String property) {
    return property.toUpperCase(Locale.ROOT).replace('.', '_').replace('-', '_');
  }

  /** True when the app set this property itself, as a system property or in its environment. */
  static boolean setByApp(String property) {
    return notBlank(sysprop(property)) || notBlank(env(envName(property)));
  }

  static String env(String name) {
    try {
      return System.getenv(name);
    } catch (Throwable t) {
      return null;
    }
  }

  static String sysprop(String name) {
    try {
      return System.getProperty(name);
    } catch (Throwable t) {
      return null;
    }
  }

  /** A mutable copy of a YAML mapping; an empty one for null; null when the value isn't a mapping. */
  static java.util.Map<String, Object> mapCopy(Object v) {
    java.util.Map<String, Object> out = new java.util.LinkedHashMap<String, Object>();
    if (v == null) {
      return out;
    }
    if (!(v instanceof java.util.Map)) {
      return null;
    }
    for (java.util.Map.Entry<?, ?> e : ((java.util.Map<?, ?>) v).entrySet()) {
      out.put(String.valueOf(e.getKey()), e.getValue());
    }
    return out;
  }

  static boolean notBlank(String s) {
    return s != null && !s.trim().isEmpty();
  }

  /** km.service_id from KM_SERVICE_ID (or -Dkm.service.id for attached JVMs), else null. */
  static String serviceIdFromEnv() {
    String id = env("KM_SERVICE_ID");
    if (!notBlank(id)) {
      id = sysprop("km.service.id");
    }
    return notBlank(id) ? id.trim() : null;
  }

  static long pid() {
    try {
      String n = new File("/proc/self").getCanonicalFile().getName();
      return Long.parseLong(n);
    } catch (Throwable ignored) {
      // not Linux, or no procfs
    }
    try {
      Class<?> ph = Class.forName("java.lang.ProcessHandle");
      Object cur = ph.getMethod("current").invoke(null);
      return (Long) ph.getMethod("pid").invoke(cur);
    } catch (Throwable ignored) {
      return -1;
    }
  }

  /** One line on stderr. The distro never uses java.util.logging: touching it in premain breaks apps with their own LogManager. */
  static void warn(String msg, Throwable t) {
    try {
      System.err.println("[" + NAME + "] " + msg + (t == null ? "" : ": " + t));
    } catch (Throwable ignored) {
      // nothing left to do
    }
  }
}
