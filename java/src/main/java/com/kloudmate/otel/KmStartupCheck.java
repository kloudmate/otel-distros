package com.kloudmate.otel;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

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

  /** Reason code reported when the JVM runs without bytecode verification. */
  static final String VERIFICATION_OFF = "bytecode_verification_off";

  /** JVM options that take their value as the next argument (so the value isn't read as the main class). */
  private static final Set<String> TAKES_VALUE = new HashSet<String>(Arrays.asList(
      "-cp", "-classpath", "--class-path", "-p", "--module-path", "--upgrade-module-path", "--add-modules",
      "--limit-modules", "--add-reads", "--add-exports", "--add-opens", "--patch-module", "--enable-native-access",
      "--source"));

  private static volatile Boolean verificationOff;

  private KmStartupCheck() {}

  /**
   * Records why the distro may not do its job. {@code instrumentationForcedOn} is true when the app (or its config
   * file) turned instrumentation on explicitly, which keeps it running in a JVM without bytecode verification.
   */
  static void run(KmStatus status, boolean sdkDisabled, boolean instrumentationForcedOn) {
    try {
      if (sdkDisabled) {
        status.fail(KmStatus.DISABLED, "sdk_disabled");
      }
      if (bytecodeVerificationOff()) {
        status.fail(instrumentationForcedOn ? KmStatus.DEGRADED : KmStatus.DENIED, VERIFICATION_OFF);
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

  /**
   * True when this JVM runs without bytecode verification for application classes ({@code -Xverify:none},
   * {@code -noverify}, {@code -XX:-BytecodeVerificationRemote}). Classes the agent rewrites are then never
   * checked, and a bad rewrite reaches the JIT compiler instead of failing one class with a VerifyError: the
   * JVM can crash ("guarantee(_stack_height < _max_stack) failed" in the bytecode escape analyzer). Reads the
   * option sources in the order the JVM applies them, without JMX (see the class comment). Cached; never throws.
   */
  static boolean bytecodeVerificationOff() {
    Boolean v = verificationOff;
    if (v == null) {
      try {
        String c = cmdline();
        v = verificationOff(KmDistro.env("JAVA_TOOL_OPTIONS"), KmDistro.env("JDK_JAVA_OPTIONS"),
            c == null ? null : c.split("\u0000"), KmDistro.env("_JAVA_OPTIONS"));
      } catch (Throwable t) {
        v = Boolean.FALSE;
      }
      verificationOff = v;
    }
    return v;
  }

  /**
   * The verifier state the JVM ends up with, from its option sources in the order it applies them:
   * {@code JAVA_TOOL_OPTIONS}, then {@code JDK_JAVA_OPTIONS} (the launcher's), then the command line, then
   * {@code _JAVA_OPTIONS}. The last setting wins. Command-line scanning stops at the main class, {@code -jar} or
   * {@code -m}, so an application argument is never read as a JVM option.
   */
  static boolean verificationOff(String javaToolOptions, String jdkJavaOptions, String[] cmdline, String javaOptions) {
    Boolean off = null;
    off = apply(off, split(javaToolOptions));
    off = apply(off, split(jdkJavaOptions));
    off = apply(off, jvmOptions(cmdline));
    off = apply(off, split(javaOptions));
    return Boolean.TRUE.equals(off);
  }

  private static Boolean apply(Boolean off, List<String> options) {
    for (String o : options) {
      if ("-Xverify:none".equals(o) || "-noverify".equals(o) || "-XX:-BytecodeVerificationRemote".equals(o)) {
        off = Boolean.TRUE;
      } else if ("-Xverify:remote".equals(o) || "-Xverify:all".equals(o) || "-Xverify".equals(o)
          || "-XX:+BytecodeVerificationRemote".equals(o)) {
        off = Boolean.FALSE;
      }
    }
    return off;
  }

  private static List<String> split(String s) {
    List<String> out = new ArrayList<String>();
    if (s != null) {
      for (String t : s.trim().split("\\s+")) {
        if (!t.isEmpty()) {
          out.add(t);
        }
      }
    }
    return out;
  }

  /** The JVM options of an argv (index 0 is the executable): everything before the main class. */
  static List<String> jvmOptions(String[] argv) {
    List<String> out = new ArrayList<String>();
    if (argv == null) {
      return out;
    }
    for (int i = 1; i < argv.length; i++) {
      String a = argv[i];
      if (a.isEmpty()) {
        continue;
      }
      if (!a.startsWith("-") || "-jar".equals(a) || "-m".equals(a) || "--module".equals(a) || a.startsWith("--module=")) {
        break;
      }
      out.add(a);
      if (TAKES_VALUE.contains(a)) {
        i++;
      }
    }
    return out;
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

  /** A small text file's contents, or null when it can't be read. */
  static String text(String path) {
    if (!KmDistro.notBlank(path)) {
      return null;
    }
    try {
      File f = new File(path);
      if (f.length() > 1024 * 1024) {
        return null;
      }
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
