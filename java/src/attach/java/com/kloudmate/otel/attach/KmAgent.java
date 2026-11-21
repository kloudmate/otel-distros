package com.kloudmate.otel.attach;

import java.lang.instrument.Instrumentation;

/**
 * Entry point of kloudmate-javaagent.jar: the upstream OpenTelemetry Java agent with the KloudMate extension
 * embedded. The agent arguments name a KloudMate properties file ({@code -javaagent:kloudmate-javaagent.jar=<file>},
 * or the same through a runtime attach); this class records the path in {@value #CONFIG_PROPERTY} for the extension
 * and hands over to the upstream agent, which ignores its arguments. Never throws: a failure here must not stop the
 * JVM from starting.
 */
public final class KmAgent {
  /** System property holding the path from the agent arguments. */
  public static final String CONFIG_PROPERTY = "kloudmate.javaagent.config";

  private static final String UPSTREAM = "io.opentelemetry.javaagent.OpenTelemetryAgent";

  private KmAgent() {}

  public static void premain(String args, Instrumentation inst) {
    start("premain", args, inst);
  }

  public static void agentmain(String args, Instrumentation inst) {
    start("agentmain", args, inst);
  }

  /** {@code java -jar kloudmate-javaagent.jar}: upstream's main (prints the agent version). */
  public static void main(String[] args) throws Exception {
    Class.forName(UPSTREAM).getMethod("main", String[].class).invoke(null, (Object) args);
  }

  private static void start(String method, String args, Instrumentation inst) {
    try {
      if (args != null && !args.trim().isEmpty() && System.getProperty(CONFIG_PROPERTY) == null) {
        System.setProperty(CONFIG_PROPERTY, args.trim());
      }
    } catch (Throwable t) {
      warn("agent arguments not recorded", t);
    }
    try {
      Class.forName(UPSTREAM).getMethod(method, String.class, Instrumentation.class).invoke(null, args, inst);
    } catch (Throwable t) {
      warn("the OpenTelemetry agent did not start; the application runs uninstrumented", t);
    }
  }

  private static void warn(String msg, Throwable t) {
    try {
      System.err.println("[kloudmate-java] " + msg + ": " + t);
    } catch (Throwable ignored) {
      // nothing left to do
    }
  }
}
