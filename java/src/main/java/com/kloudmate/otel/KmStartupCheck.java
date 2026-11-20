package com.kloudmate.otel;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/**
 * The in-runtime startup check (contract §5): records why the distro may not do its job, without ever
 * stopping the agent or the app. Reads /proc/self/cmdline rather than the RuntimeMXBean, because touching
 * JMX in premain breaks apps that install their own LogManager (JBoss, WildFly).
 */
final class KmStartupCheck {
  /** -javaagent jar names of other vendors' agents, and the reason code reported for each. */
  private static final String[][] FOREIGN = {
    {"dd-java-agent", "foreign_agent:ddtrace"},
    {"newrelic", "foreign_agent:newrelic"},
    {"elastic-apm-agent", "foreign_agent:elastic"},
    {"appdynamics", "foreign_agent:appdynamics"},
    {"dynatrace", "foreign_agent:dynatrace"},
    {"skywalking-agent", "foreign_agent:skywalking"},
    {"pinpoint-bootstrap", "foreign_agent:pinpoint"},
  };

  private KmStartupCheck() {}

  static void run(KmStatus status, boolean sdkDisabled) {
    try {
      if (sdkDisabled) {
        status.fail(KmStatus.DISABLED, "sdk_disabled");
      }
      String cmdline = cmdline();
      if (cmdline != null) {
        for (String arg : cmdline.split("\u0000")) {
          if (!arg.startsWith("-javaagent:")) {
            continue;
          }
          String jar = new File(arg.substring("-javaagent:".length())).getName().toLowerCase(java.util.Locale.ROOT);
          for (String[] f : FOREIGN) {
            if (jar.contains(f[0])) {
              status.fail(KmStatus.DEGRADED, f[1]);
            }
          }
        }
      }
    } catch (Throwable t) {
      KmDistro.warn("startup check failed", t);
    }
  }

  private static String cmdline() {
    File f = new File("/proc/self/cmdline");
    if (!f.canRead()) {
      return null;
    }
    try {
      return new String(read(f), StandardCharsets.UTF_8);
    } catch (Throwable t) {
      return null;
    }
  }

  /** sha256:&lt;hex&gt; of a file, or "" when it can't be read. */
  static String hash(String path) {
    if (!KmDistro.notBlank(path)) {
      return "";
    }
    try {
      MessageDigest md = MessageDigest.getInstance("SHA-256");
      byte[] d = md.digest(read(new File(path)));
      StringBuilder b = new StringBuilder("sha256:");
      for (byte x : d) {
        b.append(String.format("%02x", x & 0xff));
      }
      return b.toString();
    } catch (Throwable t) {
      return "";
    }
  }

  private static byte[] read(File f) throws java.io.IOException {
    InputStream in = new FileInputStream(f);
    try {
      ByteArrayOutputStream out = new ByteArrayOutputStream();
      byte[] buf = new byte[8192];
      int n;
      while ((n = in.read(buf)) > 0) {
        out.write(buf, 0, n);
      }
      return out.toByteArray();
    } finally {
      in.close();
    }
  }
}
