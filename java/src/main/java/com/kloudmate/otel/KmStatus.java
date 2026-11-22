package com.kloudmate.otel;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TimeZone;

/**
 * The startup status (contract §5), written to {@code <status_dir>/<pid>.json} before the SDK starts.
 * Written only when the directory already exists (km-agent creates it); never throws.
 */
final class KmStatus {
  static final String OK = "ok";
  static final String DEGRADED = "degraded";
  static final String DISABLED = "disabled";
  static final String DENIED = "denied";
  static final String ERROR = "error";

  String serviceId;
  String result = OK;
  String reason = "";
  String configHash = "";
  final Map<String, String[]> effective = new LinkedHashMap<String, String[]>(); // key -> {value, origin}
  final List<String> overriddenByApp = new ArrayList<String>();

  void put(String key, String value, String origin) {
    if (value != null) {
      effective.put(key, new String[] {value, origin});
    }
  }

  /** Records a KloudMate-managed setting the app replaced with its own value (kept sorted, no duplicates). */
  void overridden(String key) {
    if (key != null && !overriddenByApp.contains(key)) {
      overriddenByApp.add(key);
      java.util.Collections.sort(overriddenByApp);
    }
  }

  private static final java.util.regex.Pattern SUBSTITUTION =
      java.util.regex.Pattern.compile("\\$\\{(?:env:)?(OTEL_[A-Z0-9_]+)(?::-[^}]*)?\\}");

  /**
   * The {@code ${OTEL_*}} substitutions of a config file the app supplies itself (the variable is set in its
   * environment), in file order. Those are the file's KloudMate values the app replaced. Never throws.
   */
  static List<String> appSubstitutions(String fileText, Map<String, String> env) {
    List<String> out = new ArrayList<String>();
    if (fileText == null || env == null) {
      return out;
    }
    java.util.regex.Matcher m = SUBSTITUTION.matcher(fileText);
    while (m.find()) {
      String name = m.group(1);
      String v = env.get(name);
      if (!"OTEL_CONFIG_FILE".equals(name) && v != null && !v.trim().isEmpty() && !out.contains(name)) {
        out.add(name);
      }
    }
    return out;
  }

  /** Records a worse result; the first reason of the worst result wins. */
  void fail(String result, String reason) {
    if (rank(result) > rank(this.result)) {
      this.result = result;
      this.reason = reason;
    }
  }

  private static int rank(String r) {
    if (ERROR.equals(r)) {
      return 4;
    }
    if (DENIED.equals(r)) {
      return 3;
    }
    if (DISABLED.equals(r)) {
      return 2;
    }
    if (DEGRADED.equals(r)) {
      return 1;
    }
    return 0;
  }

  /** Writes the status file; returns the path written, or null. */
  String write(String dir) {
    try {
      File d = new File(dir);
      if (!d.isDirectory()) {
        return null;
      }
      long pid = KmDistro.pid();
      File out = new File(d, pid + ".json");
      File tmp = new File(d, "." + pid + ".json.tmp");
      Writer w = new OutputStreamWriter(new FileOutputStream(tmp), StandardCharsets.UTF_8);
      try {
        w.write(toJson(pid));
      } finally {
        w.close();
      }
      if (!tmp.renameTo(out)) {
        tmp.delete();
        return null;
      }
      return out.getPath();
    } catch (Throwable t) {
      KmDistro.warn("status file not written", t);
      return null;
    }
  }

  String toJson(long pid) {
    String now = now();
    StringBuilder b = new StringBuilder(512);
    b.append("{\"schema_version\":1");
    b.append(",\"pid\":").append(pid);
    b.append(",\"km.service_id\":").append(q(serviceId == null ? "" : serviceId));
    b.append(",\"distro\":{\"name\":").append(q(KmDistro.NAME)).append(",\"version\":").append(q(KmDistro.VERSION)).append('}');
    b.append(",\"result\":").append(q(result));
    b.append(",\"reason\":").append(q(reason));
    b.append(",\"started_at\":").append(q(now));
    b.append(",\"heartbeat_at\":").append(q(now));
    b.append(",\"config_hash\":").append(q(configHash));
    b.append(",\"effective_config\":{");
    boolean first = true;
    for (Map.Entry<String, String[]> e : effective.entrySet()) {
      if (!first) {
        b.append(',');
      }
      first = false;
      b.append(q(e.getKey())).append(":{\"value\":").append(q(e.getValue()[0])).append(",\"origin\":").append(q(e.getValue()[1])).append('}');
    }
    b.append("},\"overridden_by_app\":[");
    for (int i = 0; i < overriddenByApp.size(); i++) {
      if (i > 0) {
        b.append(',');
      }
      b.append(q(overriddenByApp.get(i)));
    }
    b.append("],\"instrumentations\":[]}\n");
    return b.toString();
  }

  private static String now() {
    SimpleDateFormat f = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'");
    f.setTimeZone(TimeZone.getTimeZone("UTC"));
    return f.format(new Date());
  }

  static String q(String s) {
    StringBuilder b = new StringBuilder(s.length() + 2).append('"');
    for (int i = 0; i < s.length(); i++) {
      char c = s.charAt(i);
      switch (c) {
        case '"':
          b.append("\\\"");
          break;
        case '\\':
          b.append("\\\\");
          break;
        case '\n':
          b.append("\\n");
          break;
        case '\r':
          b.append("\\r");
          break;
        case '\t':
          b.append("\\t");
          break;
        default:
          if (c < 0x20) {
            b.append(String.format("\\u%04x", (int) c));
          } else {
            b.append(c);
          }
      }
    }
    return b.append('"').toString();
  }
}
