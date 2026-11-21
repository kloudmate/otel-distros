package com.kloudmate.otel;

import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.common.AttributesBuilder;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.sdk.autoconfigure.spi.AutoConfigurationCustomizer;
import io.opentelemetry.sdk.autoconfigure.spi.AutoConfigurationCustomizerProvider;
import io.opentelemetry.sdk.resources.Resource;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Env/system-property mode (no {@code otel.config.file}). Adds the KloudMate defaults as the lowest-priority
 * property source, so anything the app sets itself wins (ADR-0002), stamps the resource, and writes the
 * startup status. Every step is guarded: on failure the agent runs with upstream defaults.
 */
public final class KmAutoConfigCustomizer implements AutoConfigurationCustomizerProvider {
  static final String DEFAULT_ENABLED = "otel.instrumentation.common.default-enabled";

  /** KloudMate defaults over upstream 2.29.0 (README "What it adds"). */
  static Map<String, String> defaults(KmSettings s) {
    Map<String, String> d = new LinkedHashMap<String, String>();
    d.put("otel.exporter.otlp.endpoint", s.endpoint());
    d.put("otel.exporter.otlp.protocol", "http/protobuf");
    d.put("otel.propagators", "tracecontext,baggage");
    if ("off".equals(s.runtimeMetricsProfile)) {
      d.put("otel.instrumentation.runtime-telemetry.enabled", "false");
    } else {
      d.put("otel.instrumentation.runtime-telemetry.emit-experimental-metrics", "true");
    }
    return d;
  }

  @Override
  public void customize(AutoConfigurationCustomizer c) {
    final KmSettings settings;
    final Map<String, String> defaults;
    try {
      settings = KmSettings.fromEnv();
      defaults = defaults(settings);
      if (KmStartupCheck.bytecodeVerificationOff()) {
        // No instrumentation by default in a JVM that never verifies the classes the agent rewrites. Lowest
        // priority like every default here: an app that sets it to true itself keeps its instrumentation.
        defaults.put(DEFAULT_ENABLED, "false");
      }
    } catch (Throwable t) {
      KmDistro.warn("disabled, running with upstream defaults", t);
      return;
    }
    c.addPropertiesSupplier(() -> defaults);
    c.addPropertiesCustomizer(
        config -> {
          try {
            KmStatus st = new KmStatus();
            st.serviceId = settings.serviceId;
            for (String k : defaults.keySet()) {
              boolean app = KmDistro.setByApp(k);
              st.put(KmDistro.envName(k), config.getString(k), app ? "app_env" : "distro_default");
              if (app) {
                st.overridden(KmDistro.envName(k));
              }
            }
            for (String k : new String[] {"otel.service.name", "otel.traces.sampler", "otel.traces.sampler.arg"}) {
              if (KmDistro.setByApp(k)) {
                st.put(KmDistro.envName(k), config.getString(k), "app_env");
              }
            }
            KmStartupCheck.run(st, config.getBoolean("otel.sdk.disabled", false), config.getBoolean(DEFAULT_ENABLED, true));
            st.write(settings.statusDir);
          } catch (Throwable t) {
            KmDistro.warn("status not reported", t);
          }
          return Collections.<String, String>emptyMap();
        });
    c.addResourceCustomizer((resource, config) -> stamp(resource, settings.serviceId));
  }

  /**
   * Adds km.service_id when the resource doesn't carry one (contract §2: never rewritten), and sets
   * telemetry.distro.* to this distro, replacing upstream's opentelemetry-java-instrumentation values.
   */
  static Resource stamp(Resource resource, String serviceId) {
    try {
      AttributesBuilder b = Attributes.builder()
          .put(AttributeKey.stringKey(KmDistro.DISTRO_NAME), KmDistro.NAME)
          .put(AttributeKey.stringKey(KmDistro.DISTRO_VERSION), KmDistro.VERSION);
      AttributeKey<String> sid = AttributeKey.stringKey(KmDistro.KM_SERVICE_ID);
      if (serviceId != null && resource.getAttribute(sid) == null) {
        b.put(sid, serviceId);
      }
      return resource.merge(Resource.create(b.build()));
    } catch (Throwable t) {
      KmDistro.warn("resource not stamped", t);
      return resource;
    }
  }

  @Override
  public int order() {
    return Integer.MAX_VALUE; // after upstream's customizers, so telemetry.distro.* is ours
  }

}
