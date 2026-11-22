package com.kloudmate.otel;

import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Properties;
import java.util.TreeMap;

/**
 * The KloudMate properties file named by the arguments of kloudmate-javaagent.jar
 * ({@code -javaagent:kloudmate-javaagent.jar=<file>}, or a runtime attach with the same arguments). The KloudMate
 * Agent writes it before it attaches to a running JVM, whose environment and command line it can't change.
 *
 * <ul>
 *   <li>{@code kloudmate.service_id}, {@code kloudmate.status_dir}, {@code kloudmate.agent.otlp_endpoint},
 *       {@code kloudmate.runtime_metrics.profile}: as in {@code distribution.kloudmate}, and above the
 *       {@code KM_*} environment;
 *   <li>{@code otel.*}: OpenTelemetry properties, below anything the application sets itself and above the
 *       KloudMate defaults.
 * </ul>
 *
 * Other keys, files over 64 KiB and unreadable files are ignored. Never throws.
 */
final class KmAttachConfig {
  /** Set by the agent jar's entry class (com.kloudmate.otel.attach.KmAgent) from the agent arguments. */
  static final String PROPERTY = "kloudmate.javaagent.config";

  static final long MAX_BYTES = 64 * 1024;

  /** The file's path, or null when the agent arguments named none. */
  final String path;
  /** The {@code otel.*} properties, sorted. */
  final Map<String, String> otel;
  /** The {@code kloudmate.*} settings, shaped as a {@code distribution.kloudmate} block. */
  final Map<String, Object> block;

  private KmAttachConfig(String path, Map<String, String> otel, Map<String, Object> block) {
    this.path = path;
    this.otel = otel;
    this.block = block;
  }

  static final KmAttachConfig NONE =
      new KmAttachConfig(null, Collections.<String, String>emptyMap(), Collections.<String, Object>emptyMap());

  static KmAttachConfig load() {
    return load(KmDistro.sysprop(PROPERTY));
  }

  static KmAttachConfig load(String path) {
    if (!KmDistro.notBlank(path)) {
      return NONE;
    }
    try {
      File f = new File(path.trim());
      if (!f.isFile() || f.length() > MAX_BYTES) {
        KmDistro.warn("agent config " + path + " not read (missing or over 64 KiB)", null);
        return NONE;
      }
      Properties p = new Properties();
      InputStream in = new FileInputStream(f);
      try {
        p.load(new InputStreamReader(in, StandardCharsets.UTF_8));
      } finally {
        in.close();
      }
      return parse(f.getPath(), p);
    } catch (Throwable t) {
      KmDistro.warn("agent config " + path + " not read", t);
      return NONE;
    }
  }

  static KmAttachConfig parse(String path, Properties p) {
    Map<String, String> otel = new TreeMap<String, String>();
    Map<String, Object> block = new LinkedHashMap<String, Object>();
    Map<String, Object> agent = new LinkedHashMap<String, Object>();
    for (String k : p.stringPropertyNames()) {
      String v = p.getProperty(k);
      if (v == null || v.trim().isEmpty()) {
        continue;
      }
      v = v.trim();
      if (k.startsWith("otel.")) {
        otel.put(k, v);
      } else if (k.equals("kloudmate.service_id")) {
        block.put("service_id", v);
      } else if (k.equals("kloudmate.status_dir")) {
        block.put("status_dir", v);
      } else if (k.equals("kloudmate.agent.otlp_endpoint")) {
        agent.put("otlp_endpoint", v);
      } else if (k.equals("kloudmate.agent.opamp_endpoint")) {
        agent.put("opamp_endpoint", v);
      } else if (k.equals("kloudmate.runtime_metrics.profile")) {
        block.put("runtime_metrics", Collections.<String, Object>singletonMap("profile", v));
      }
    }
    if (!agent.isEmpty()) {
      block.put("agent", agent);
    }
    return new KmAttachConfig(path, Collections.unmodifiableMap(otel), Collections.unmodifiableMap(block));
  }
}
