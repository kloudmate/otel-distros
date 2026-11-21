package com.kloudmate.otel;

import io.opentelemetry.sdk.autoconfigure.declarativeconfig.DeclarativeConfigurationCustomizer;
import io.opentelemetry.sdk.autoconfigure.declarativeconfig.DeclarativeConfigurationCustomizerProvider;
import io.opentelemetry.sdk.autoconfigure.declarativeconfig.model.DistributionModel;
import io.opentelemetry.sdk.autoconfigure.declarativeconfig.model.OpenTelemetryConfigurationModel;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Declarative-config mode, the instrumentation settings. Runs first, because the agent reads them (in its own
 * model customizers) before later customizers run: the runtime-metrics profile from {@code distribution.kloudmate},
 * and, in a JVM without bytecode verification ({@link KmStartupCheck#bytecodeVerificationOff}),
 * sets {@code distribution.javaagent.instrumentation.default_enabled: false} when the file doesn't set it, so the
 * agent rewrites no classes. Runs first: the agent reads {@code distribution.javaagent} in its own model
 * customizer, so the value has to be in the model before that. A file that sets {@code default_enabled} keeps its
 * value. Never throws.
 */
public final class KmVerifierGuard implements DeclarativeConfigurationCustomizerProvider {

  @Override
  public void customize(DeclarativeConfigurationCustomizer c) {
    c.addModelCustomizer(KmVerifierGuard::apply);
  }

  static OpenTelemetryConfigurationModel apply(OpenTelemetryConfigurationModel model) {
    try {
      if (KmStartupCheck.bytecodeVerificationOff()) {
        disableByDefault(model);
      }
    } catch (Throwable t) {
      KmDistro.warn("could not turn instrumentation off in a JVM without bytecode verification", t);
    }
    try {
      KmSettings s = KmSettings.fromBlock(KmDeclarativeConfigCustomizer.block(model));
      KmDeclarativeConfigCustomizer.runtimeMetrics(model, s.runtimeMetricsProfile);
    } catch (Throwable t) {
      KmDistro.warn("runtime metrics profile not applied", t);
    }
    return model;
  }

  /** Sets distribution.javaagent.instrumentation.default_enabled to false unless the file sets it. */
  static void disableByDefault(OpenTelemetryConfigurationModel model) {
    DistributionModel dist = model.getDistribution();
    if (dist == null) {
      dist = new DistributionModel();
      model.setDistribution(dist);
    }
    Map<String, Object> agent = KmDistro.mapCopy(dist.getExtensionProperties().get("javaagent"));
    if (agent == null) {
      return; // not a mapping: leave the file's value for the agent to report
    }
    Map<String, Object> instrumentation = KmDistro.mapCopy(agent.get("instrumentation"));
    if (instrumentation == null) {
      return;
    }
    if (!instrumentation.containsKey("default_enabled")) {
      instrumentation.put("default_enabled", Boolean.FALSE);
      agent.put("instrumentation", instrumentation);
      dist.setExtensionProperty("javaagent", agent);
    }
  }

  /**
   * False only when the model leaves instrumentation off by default
   * ({@code distribution.javaagent.instrumentation.default_enabled: false}).
   */
  static boolean instrumentationOnByDefault(OpenTelemetryConfigurationModel model) {
    try {
      if (model.getDistribution() == null) {
        return true;
      }
      Object agent = model.getDistribution().getExtensionProperties().get("javaagent");
      Object i = agent instanceof Map ? ((Map<?, ?>) agent).get("instrumentation") : null;
      if (!(i instanceof Map)) {
        return true;
      }
      Object d = ((Map<?, ?>) i).get("default_enabled");
      return !(Boolean.FALSE.equals(d) || "false".equalsIgnoreCase(String.valueOf(d)));
    } catch (Throwable t) {
      return true;
    }
  }

  @Override
  public int order() {
    return Integer.MIN_VALUE; // before the agent's own distribution.javaagent customizer
  }
}
